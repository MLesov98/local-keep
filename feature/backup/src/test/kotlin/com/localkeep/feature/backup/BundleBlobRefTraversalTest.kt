package com.localkeep.feature.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.feature.files.FileBlobStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A blob ref read out of a picked `.vault` must never name a file outside `blobs/` (S2-01, 2026-10-02).
 *
 * The ref went from `VaultBundle.Reader.forEachBlob` straight into `File(blobs, "$ref.enc")`, and the
 * blob records sit outside the vault container's AEAD, so a correct password vouched for none of
 * them. A record naming `../vault` resolved to `filesDir/vault.enc`, and the same-vault restore branch
 * renamed the attacker's bytes over it — at stage time, before Merge or Replace was even offered.
 *
 * Both layers are pinned separately: the bundle reader refuses any ref that is not the shape the app
 * mints, and the blob store refuses any ref that could leave its own directory, so neither depends on
 * the other being there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundleBlobRefTraversalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val liveVault get() = File(context.filesDir, "vault.enc")

    @Before
    fun seedLiveVaultAndBlobsDir() {
        liveVault.writeBytes(LIVE)
        // Present on any device that has ever stored a photo, and needed for `blobs/..` to resolve.
        File(context.filesDir, "blobs").mkdirs()
    }

    private fun blob(ref: String, bytes: ByteArray) =
        VaultBundle.BlobSource(ref, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    @Test
    fun `the bundle reader refuses a blob ref that is not one this app mints`() {
        val bytes = ByteArrayOutputStream()
            .also { VaultBundle.write(it, ByteArray(16), listOf(blob("../vault", EVIL))) }
            .toByteArray()
        var handedOver = false

        assertFailsWith<IllegalArgumentException> {
            VaultBundle.open(ByteArrayInputStream(bytes)).forEachBlob { _, stream, _ ->
                handedOver = true
                stream.readBytes()
            }
        }
        assertFalse(handedOver, "a traversal ref must be refused before any caller can write under it")
    }

    /** The control: a ref of the minted shape still reaches the caller with its bytes. */
    @Test
    fun `a minted blob ref still reaches the caller`() {
        val ref = UUID.randomUUID().toString()
        val bytes = ByteArrayOutputStream()
            .also { VaultBundle.write(it, ByteArray(16), listOf(blob(ref, EVIL))) }
            .toByteArray()
        val seen = mutableMapOf<String, ByteArray>()

        VaultBundle.open(ByteArrayInputStream(bytes)).forEachBlob { r, stream, _ -> seen[r] = stream.readBytes() }

        assertEquals(setOf(ref), seen.keys)
        assertContentEquals(EVIL, seen.getValue(ref))
    }

    @Test
    fun `the blob store will not resolve a ref outside its own directory`() {
        val store = FileBlobStore(context)

        assertFalse(store.hasBlob("../vault"), "vault.enc must not answer as a blob")
        assertFailsWith<IOException> { store.writeEncrypted("../vault", ByteArrayInputStream(EVIL)) }
        store.deleteBlob("../vault")

        assertContentEquals(LIVE, liveVault.readBytes(), "vault.enc must be untouched")
        assertFalse(File(context.filesDir, "vault.enc.tmp").exists())
    }

    /** End to end, through the real store: the stage-time restore of a crafted bundle. */
    @Test
    fun `staging a crafted bundle leaves vault enc untouched`() = runBlocking<Unit> {
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val header = VaultHeaderCodec.create(
            salt,
            kdfParams,
            EnvelopeCrypto.wrapDek(kek, dek),
            EnvelopeCrypto.slotCommitment(dek),
        )
        val now = Instant.now()
        val vault = Vault(items = emptyList(), createdAt = now, modifiedAt = now)
        val vaultFileBytes = VaultFileCodec.encrypt(
            vault,
            VaultSubkeys.encKey(dek),
            header,
            VaultFileCodec.revokedRecoveryBlock(),
        )
        val bundle = ByteArrayOutputStream()
            .also { VaultBundle.write(it, vaultFileBytes, listOf(blob("../vault", EVIL))) }
            .toByteArray()

        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bundle))
        val pickerContext = mock<Context>()
        whenever(pickerContext.contentResolver).thenReturn(resolver)
        // The user's own vault, so restore takes the same-vault branch: the one that wrote verbatim.
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull()).thenAnswer {
            VaultLockManager.SessionSnapshot(vault = vault, dek = dek.copyOf())
        }
        val importer = VaultImporter(pickerContext, FileBlobStore(context), lockManager)

        val outcome = importer.readVault(uri, "password".toCharArray())

        assertIs<Outcome.Failure>(outcome)
        assertContentEquals(LIVE, liveVault.readBytes(), "staging an import must never rewrite vault.enc")
    }

    private companion object {
        val LIVE = "the user's live vault".toByteArray()
        val EVIL = "attacker-chosen bytes".toByteArray()
    }
}

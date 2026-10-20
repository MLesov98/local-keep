package com.localkeep.feature.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.localkeep.core.common.AppError
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Two guards on the read side of a `.vault` import, both driven through the real `readVault`.
 *
 *  - **S2-13** — the bundle magic was sniffed with a single `read()`. A provider whose first chunk is
 *    shorter than the eight magic bytes routed an intact bundle into the pre-bundle branch, which then
 *    called it "not a valid .vault file".
 *  - **S2-04** — the file's own Argon2 cost ran unguarded. A cost the heap cannot hold is now refused
 *    with the named memory failure instead of an `OutOfMemoryError` escaping to kill the process.
 */
class ImportReadGuardsTest {

    private val now: Instant = Instant.now()
    private val vault = Vault(items = emptyList(), createdAt = now, modifiedAt = now)
    private val dek = EnvelopeCrypto.generateDek()

    private fun bundleBytes(): ByteArray {
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val header = VaultHeaderCodec.create(
            salt,
            kdfParams,
            EnvelopeCrypto.wrapDek(kek, dek),
            EnvelopeCrypto.slotCommitment(dek),
        )
        val vaultFile = VaultFileCodec.encrypt(
            vault,
            VaultSubkeys.encKey(dek),
            header,
            VaultFileCodec.revokedRecoveryBlock(),
        )
        return ByteArrayOutputStream().also { VaultBundle.write(it, vaultFile, emptyList()) }.toByteArray()
    }

    private fun importerReading(stream: InputStream): Pair<VaultImporter, Uri> {
        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(stream)
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull()).thenAnswer {
            VaultLockManager.SessionSnapshot(vault = vault, dek = dek.copyOf())
        }
        return VaultImporter(context, mock(), lockManager) to uri
    }

    /** One byte per read, and nothing ever "available" — a pipe-backed provider at its slowest. */
    private class TrickleInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (len == 0) 0 else delegate.read(b, off, 1)
        override fun available(): Int = 0
    }

    @Test
    fun `a bundle delivered one byte at a time is still read as a bundle`() = runBlocking {
        val (importer, uri) = importerReading(TrickleInputStream(bundleBytes()))

        val outcome = importer.readVault(uri, "password".toCharArray())

        assertIs<Outcome.Success<VaultImporter.ImportResult>>(outcome)
        Unit
    }

    @Test
    fun `a key-derivation cost larger than the heap is refused with the memory failure`() = runBlocking {
        val (importer, uri) = importerReading(ByteArrayInputStream(bundleBytes()))
        // FLOOR is 19 MiB; a 16 MiB heap cannot hold it at all.
        importer.heapLimitBytes = { 16L * 1024 * 1024 }

        val outcome = importer.readVault(uri, "password".toCharArray())

        val failure = assertIs<Outcome.Failure>(outcome)
        val error = assertIs<AppError.Storage>(failure.error)
        assertEquals(IMPORT_KDF_NEEDS_MORE_MEMORY, error.message)
    }

    /** The control: the same file under the real heap cap still opens. */
    @Test
    fun `the same file opens when the heap can hold its cost`() = runBlocking {
        val (importer, uri) = importerReading(ByteArrayInputStream(bundleBytes()))

        val outcome = importer.readVault(uri, "password".toCharArray())

        assertIs<Outcome.Success<VaultImporter.ImportResult>>(outcome)
        Unit
    }
}

package com.localkeep.feature.backup

import android.content.Context
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
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.FileBlobStore
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A snapshot that was just written is never the one pruned (O4-06, 2026-10-02).
 *
 * Snapshot names are wall-clock stamps and the ring keeps the five newest *by name*. With the device
 * clock moved back behind five existing stamps, every new snapshot sorted as the oldest and was
 * deleted the instant it was written — while `snapshotNow` returned it as a success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollingBackupClockRollbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backups get() = File(context.filesDir, "backups")

    private fun writeLiveVault() {
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, KdfParams.FLOOR)
        val header = VaultHeaderCodec.create(
            salt,
            KdfParams.FLOOR,
            EnvelopeCrypto.wrapDek(kek, dek),
            EnvelopeCrypto.slotCommitment(dek),
        )
        val now = Instant.now()
        val bytes = VaultFileCodec.encrypt(
            Vault(items = emptyList(), createdAt = now, modifiedAt = now),
            VaultSubkeys.encKey(dek),
            header,
            VaultFileCodec.revokedRecoveryBlock(),
        )
        File(context.filesDir, "vault.enc").writeBytes(bytes)
    }

    private fun manager() = RollingBackupManager(
        context = context,
        vaultRepository = mock<VaultRepository>(),
        vaultLockManager = mock<VaultLockManager>(),
        fileBlobStore = mock<FileBlobStore>(),
    )

    @Test
    fun `a snapshot written after the clock went back survives its own prune`() = runBlocking {
        writeLiveVault()
        backups.mkdirs()
        // Five generations stamped in a future the clock has since been set back from.
        (1..5).forEach { File(backups, "vault-20990101-00000$it.enc").writeBytes(byteArrayOf(it.toByte())) }

        val written = assertIs<Outcome.Success<File>>(manager().snapshotNow()).value

        assertTrue(written.exists(), "the snapshot just reported as written must still be on disk")
        assertEquals(RollingBackupManager.MAX_SNAPSHOTS, RollingBackupManager.snapshotsNewestFirst(backups).size)
        assertFalse(File(backups, "vault-20990101-000001.enc").exists(), "the oldest other generation goes instead")
    }

    /** The control: with an honest clock the ring prunes exactly as it always did. */
    @Test
    fun `an ordinary snapshot still prunes the oldest generation`() = runBlocking {
        writeLiveVault()
        backups.mkdirs()
        (1..5).forEach { File(backups, "vault-20000101-00000$it.enc").writeBytes(byteArrayOf(it.toByte())) }

        val written = assertIs<Outcome.Success<File>>(manager().snapshotNow()).value

        assertTrue(written.exists())
        assertEquals(RollingBackupManager.MAX_SNAPSHOTS, RollingBackupManager.snapshotsNewestFirst(backups).size)
        assertFalse(File(backups, "vault-20000101-000001.enc").exists())
        assertTrue(File(backups, "vault-20000101-000002.enc").exists())
    }
}

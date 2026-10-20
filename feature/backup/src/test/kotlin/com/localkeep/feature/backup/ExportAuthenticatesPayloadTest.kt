package com.localkeep.feature.backup

import android.app.Application
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
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The master-password export must not copy a container whose payload no longer authenticates.
 *
 * `BackupSettingsViewModel.export` gates on `VaultLockManager.reauthenticate`, which verifies an
 * *unlock slot* — and slots live in the header. Nothing on this path ever touched the ciphertext,
 * and `exportWithMasterPassword` then streamed `vault.enc` out byte for byte. So an export taken
 * between a payload corruption and the next save or unlock produced a file that will never open,
 * reported as an ordinary "Exported 4.5 MB" (fixed 2026-09-13). A backup failure
 * that reports success is the worst shape available: the user stops looking for a second copy.
 *
 * The sibling path is unaffected and is the control here: `exportWithPassphrase` re-encrypts from
 * the decrypted session model, so it cannot emit a container it did not just build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportAuthenticatesPayloadTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    /** The message `AppError.toBackupMessage` maps to `backup_error_vault_file_missing`. */
    private val unreadable = "vault file missing or structurally corrupt; backup recovery failed"

    @Test
    fun `a container whose payload no longer authenticates is refused instead of copied`() =
        runTest {
            writeVaultContainer()
            val lockManager = mock<VaultLockManager>()
            whenever(lockManager.sessionAuthenticatesContainer(any())).thenReturn(false)
            val exporter = exporter(lockManager)

            val result = exporter.exportWithMasterPassword(mock<Uri>())

            assertTrue(result is Outcome.Failure, "expected a refusal, got $result")
            assertEquals(
                unreadable,
                result.error.message,
                "the refusal must carry the literal that maps to the missing-or-damaged string; " +
                    "anything else lands in backup_error_generic and offers a retry that cannot work",
            )
            verify(lockManager).sessionAuthenticatesContainer(any())
        }

    /**
     * The control. With the payload authenticating, the export must get *past* the gate and go on
     * to the write — so the failure it reports here is a write failure, not the refusal above.
     *
     * Asserted as "not the refusal" rather than as a success because a Robolectric `ContentResolver`
     * has no stream for a mock `Uri`, and `noBackupFilesDir.usableSpace` on the JVM is whatever the
     * build machine has. Either way the operation is past this gate, which is the whole claim; a
     * change that gated the export unconditionally fails this.
     */
    @Test
    fun `a healthy container is not refused by the payload gate`() = runTest {
        writeVaultContainer()
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionAuthenticatesContainer(any())).thenReturn(true)
        val exporter = exporter(lockManager)

        val result = exporter.exportWithMasterPassword(mock<Uri>())

        assertTrue(result is Outcome.Failure, "expected the write to fail in this harness, got $result")
        assertNotEquals(
            unreadable,
            result.error.message,
            "a container that authenticates must reach the write; reporting it as damaged would " +
                "make every export fail",
        )
    }

    /**
     * The second control, and the one that stops the gate spreading. `exportWithPassphrase` builds
     * its container from the session model rather than copying `vault.enc`, so it has no window to
     * close — and it is the path a duress session uses to export its decoy, where a region-0 check
     * would refuse every time.
     */
    @Test
    fun `the independent-passphrase export is not gated on the live container`() = runTest {
        writeVaultContainer()
        val lockManager = mock<VaultLockManager>()
        val exporter = exporter(lockManager)
        val dek = EnvelopeCrypto.generateDek()
        val now = Instant.now()

        exporter.exportWithPassphrase(
            destination = mock<Uri>(),
            vault = Vault(items = emptyList(), createdAt = now, modifiedAt = now),
            dek = dek,
            exportPassphrase = "an export passphrase".toCharArray(),
        )

        verify(lockManager, never()).sessionAuthenticatesContainer(any())
    }

    private fun exporter(lockManager: VaultLockManager) = VaultExporter(
        context = application,
        fileBlobStore = mock(),
        blobGarbageCollector = mock(),
        vaultLockManager = lockManager,
    )

    /**
     * A container `VaultLoader` accepts. The filename is spelled out because `AtomicVaultWriter`'s
     * constants are `internal` to `:core:vault` — same note as
     * [BackupSettingsViewModelLockFailureTest].
     */
    private fun writeVaultContainer() {
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
        val bytes = VaultFileCodec.encrypt(
            Vault(items = emptyList(), createdAt = now, modifiedAt = now),
            VaultSubkeys.encKey(dek),
            header,
            VaultFileCodec.revokedRecoveryBlock(),
        )
        File(application.filesDir, "vault.enc").writeBytes(bytes)
    }
}

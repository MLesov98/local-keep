package com.localkeep.feature.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.model.File as FileContent
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.RestoreOutcome
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.FileBlobStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for a 2026-09-09 finding: restoring a rolling snapshot never counted blob refs
 * the restored vault's items point at that this device no longer has on disk, so a restore that
 * silently dropped attachments — their blobs cleaned up as unreferenced sometime after the
 * snapshot was taken and before it was restored — reported unqualified success. Import already had
 * this via [VaultImporter.ImportResult.missingBlobRefCount]; restore had no equivalent.
 *
 * Drives [RollingBackupManager.restoreSnapshot] through a real encrypted container, following
 * [VaultImporterTest]'s "readVault reports the distinct referenced files this backup did not
 * contain" fixture pattern — same shape, restore side. `restoreVaultFile` itself is mocked (it is
 * `:core:vault` internals this module does not own), but the DEK-driven decrypt-and-count that this
 * finding is actually about runs for real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollingBackupManagerMissingBlobRefCountTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    private class Fixture(refs: List<String>) {
        val dek: ByteArray = EnvelopeCrypto.generateDek()
        val vault: Vault
        val snapshotFile: File

        init {
            val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
            // Argon2id's floor rather than DEFAULT — this test pays the KDF cost for real, and the
            // floor is still slow enough to prove the container decrypts correctly.
            val kdfParams = KdfParams.FLOOR
            val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
            val wrappedDek = EnvelopeCrypto.wrapDek(kek, dek)
            val commitment = EnvelopeCrypto.slotCommitment(dek)
            val header = VaultHeaderCodec.create(salt, kdfParams, wrappedDek, commitment)
            val encKey = VaultSubkeys.encKey(dek)

            val now = Instant.now()
            vault = Vault(
                items = refs.map { ref ->
                    VaultItem(
                        id = UUID.randomUUID(),
                        createdAt = now,
                        updatedAt = now,
                        content = FileContent(filename = "$ref.jpg", blobRef = ref),
                    )
                },
                createdAt = now,
                modifiedAt = now,
            )
            val snapshotBytes =
                VaultFileCodec.encrypt(vault, encKey, header, VaultFileCodec.revokedRecoveryBlock())
            snapshotFile = File.createTempFile("vault-20260909-000000", ".enc").apply {
                writeBytes(snapshotBytes)
                deleteOnExit()
            }
        }
    }

    // `whenever(vaultLockManager.restoreVaultFile(any()))` calls the real suspend method to build
    // the stub, so evaluating it needs a coroutine — matching RollingBackupRestoreNoticeTest's own
    // `managerRestoring` helper.
    private fun managerFor(
        fixture: Fixture,
        onDiskRefs: Set<String>,
    ): RollingBackupManager = runBlocking {
        val fileBlobStore = mock<FileBlobStore>()
        whenever(fileBlobStore.hasBlob(any())).thenAnswer { invocation ->
            invocation.arguments[0] as String in onDiskRefs
        }
        val vaultLockManager = mock<VaultLockManager>()
        whenever(vaultLockManager.sessionSnapshotOrNull()).thenAnswer {
            // A fresh copy per call, matching the production contract: the caller owns and wipes it.
            VaultLockManager.SessionSnapshot(vault = fixture.vault, dek = fixture.dek.copyOf())
        }
        whenever(vaultLockManager.restoreVaultFile(any())).thenReturn(RestoreOutcome.OK)
        RollingBackupManager(
            context = application,
            vaultRepository = mock<VaultRepository>(),
            vaultLockManager = vaultLockManager,
            fileBlobStore = fileBlobStore,
        )
    }

    @Test
    fun `a successful restore counts distinct missing blob refs and records them for the notice`() =
        runBlocking {
            // Two ITEMS share "missing-ref-1", so a naive per-item count would answer 3; the
            // distinct-ref count (see blobRefsOf) must answer 2.
            val fixture = Fixture(listOf("present-ref", "missing-ref-1", "missing-ref-1", "missing-ref-2"))
            val manager = managerFor(fixture, onDiskRefs = setOf("present-ref"))
            BackupPreferences.consumeRestoreNoticePendingMissingBlobRefCount(application)
            BackupPreferences.consumeRestoreNoticePending(application)

            val outcome = manager.restoreSnapshot(fixture.snapshotFile)

            assertIs<Outcome.Success<*>>(outcome)
            assertEquals(
                2,
                BackupPreferences.consumeRestoreNoticePendingMissingBlobRefCount(application),
                "two distinct blob refs are missing on disk, so the count recorded for the " +
                    "post-unlock notice must be 2 — not 3 items, and not 0 because the restore " +
                    "itself \"succeeded\"",
            )
        }

    @Test
    fun `a restore with every referenced blob present records zero missing`() = runBlocking {
        val fixture = Fixture(listOf("present-ref"))
        val manager = managerFor(fixture, onDiskRefs = setOf("present-ref"))
        BackupPreferences.consumeRestoreNoticePendingMissingBlobRefCount(application)
        BackupPreferences.consumeRestoreNoticePending(application)

        val outcome = manager.restoreSnapshot(fixture.snapshotFile)

        assertIs<Outcome.Success<*>>(outcome)
        assertEquals(
            0,
            BackupPreferences.consumeRestoreNoticePendingMissingBlobRefCount(application),
            "every referenced blob is present, so nothing should be reported missing",
        )
    }

    @Test
    fun `a restore with no live session records zero rather than crashing`() = runBlocking {
        // The vault is locked at the moment restoreSnapshot runs — sessionSnapshotOrNull() answers
        // null, which is the only DEK missingBlobRefCount ever had a chance to use. This must not
        // throw, and must not claim a count it cannot know.
        val fixture = Fixture(listOf("present-ref"))
        val fileBlobStore = mock<FileBlobStore>()
        val vaultLockManager = mock<VaultLockManager>()
        whenever(vaultLockManager.sessionSnapshotOrNull()).thenReturn(null)
        whenever(vaultLockManager.restoreVaultFile(any())).thenReturn(RestoreOutcome.OK)
        val manager = RollingBackupManager(
            context = application,
            vaultRepository = mock<VaultRepository>(),
            vaultLockManager = vaultLockManager,
            fileBlobStore = fileBlobStore,
        )
        BackupPreferences.consumeRestoreNoticePendingMissingBlobRefCount(application)
        BackupPreferences.consumeRestoreNoticePending(application)

        val outcome = manager.restoreSnapshot(fixture.snapshotFile)

        assertIs<Outcome.Success<*>>(outcome)
        assertEquals(0, BackupPreferences.consumeRestoreNoticePendingMissingBlobRefCount(application))
    }

    @Test
    fun `the missing-file count is consumed once, like the pending flag it rides with`() = runBlocking {
        val fixture = Fixture(listOf("present-ref", "missing-ref"))
        val manager = managerFor(fixture, onDiskRefs = setOf("present-ref"))
        BackupPreferences.consumeRestoreNoticePendingMissingBlobRefCount(application)
        BackupPreferences.consumeRestoreNoticePending(application)

        assertIs<Outcome.Success<*>>(manager.restoreSnapshot(fixture.snapshotFile))

        assertEquals(1, BackupPreferences.consumeRestoreNoticePendingMissingBlobRefCount(application))
        assertEquals(
            0,
            BackupPreferences.consumeRestoreNoticePendingMissingBlobRefCount(application),
            "a second read after the first consume must not repeat the same count — the next " +
                "unlock's notice would otherwise double-count files that were only ever missing once",
        )
    }
}

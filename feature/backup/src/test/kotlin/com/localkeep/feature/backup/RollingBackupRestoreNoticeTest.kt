package com.localkeep.feature.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.Outcome
import com.localkeep.core.vault.RestoreOutcome
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.FileBlobStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.io.File

/**
 * Regression test: a successful snapshot restore reported its success into a
 * ViewModel the restore itself had already destroyed.
 *
 * [VaultLockManager.restoreVaultFile] writes the file and then force-locks the vault before it
 * returns OK. `MainActivity`'s lock collector reacts by popping the whole nav graph inclusive, which
 * clears the Backup screen's back-stack entry — so `BackupSettingsViewModel`'s `viewModelScope` is
 * cancelled, and the status assignment that used to be the only confirmation never rendered (in
 * practice never ran). The restore therefore ended at the unlock screen in silence, indistinguishable
 * from an auto-lock interruption, and worst of all for a snapshot predating a password change: the
 * user is bounced to unlock, types the current password, and is told it is wrong.
 *
 * The fix records the outcome from inside [RollingBackupManager.restoreSnapshot] — the last code
 * still alive on that path — and `MainActivity` posts the notice after the next successful unlock.
 * The second test below is the one that matters: it cancels the caller's job the way the forced lock
 * does, and asserts the flag is written anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollingBackupRestoreNoticeTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a successful restore records a notice for the next unlock`() = runBlocking {
        val manager = managerRestoring(RestoreOutcome.OK)
        assertFalse(
            BackupPreferences.consumeRestoreNoticePending(application),
            "nothing has been restored yet, so nothing should be pending",
        )

        assertIs<Outcome.Success<*>>(manager.restoreSnapshot(snapshotFile()))

        assertTrue(
            BackupPreferences.consumeRestoreNoticePending(application),
            "a successful restore must leave something for the post-unlock notice to say",
        )
        assertFalse(
            BackupPreferences.consumeRestoreNoticePending(application),
            "the notice is posted once, not on every unlock afterwards",
        )
    }

    /**
     * The whole point. `restoreVaultFile` locking the vault is what cancels the caller — so the
     * record has to be written before the cancellation can take effect, not by anything downstream
     * of the suspend call.
     */
    @Test
    fun `the notice survives the caller's scope being cancelled by the forced lock`() = runBlocking {
        // Created before the stub so the answer cannot race the assignment: the mock runs on the
        // launched coroutine's own thread, and a `var job` filled in after `launch` is sometimes
        // still null when it gets there.
        val callerJob = Job()
        val vaultLockManager = mock<VaultLockManager>()
        whenever(vaultLockManager.restoreVaultFile(any())).thenAnswer {
            // Stands in for lock() -> MainActivity's collector -> inclusive pop -> onCleared().
            callerJob.cancel()
            RestoreOutcome.OK
        }
        val manager = RollingBackupManager(
            context = application,
            vaultRepository = mock<VaultRepository>(),
            vaultLockManager = vaultLockManager,
            fileBlobStore = mock<FileBlobStore>(),
        )
        BackupPreferences.consumeRestoreNoticePending(application)

        val job = CoroutineScope(Dispatchers.IO + callerJob)
            .launch { manager.restoreSnapshot(snapshotFile()) }
        job.join()

        assertTrue(job.isCancelled, "the test must actually reproduce the cancellation it models")
        assertTrue(
            BackupPreferences.consumeRestoreNoticePending(application),
            "the restore succeeded on disk, so the user is owed the notice even though the screen " +
                "that asked for it is already gone",
        )
    }

    @Test
    fun `a rejected snapshot records nothing`() = runBlocking {
        val manager = managerRestoring(RestoreOutcome.INVALID_FILE)
        BackupPreferences.consumeRestoreNoticePending(application)

        assertIs<Outcome.Failure>(manager.restoreSnapshot(snapshotFile()))

        assertFalse(
            BackupPreferences.consumeRestoreNoticePending(application),
            "nothing was written, so the next unlock must not claim a restore happened",
        )
    }

    private fun managerRestoring(outcome: RestoreOutcome): RollingBackupManager = runBlocking {
        val vaultLockManager = mock<VaultLockManager>()
        whenever(vaultLockManager.restoreVaultFile(any())).thenReturn(outcome)
        RollingBackupManager(
            context = application,
            vaultRepository = mock<VaultRepository>(),
            vaultLockManager = vaultLockManager,
            fileBlobStore = mock<FileBlobStore>(),
        )
    }

    /** Content is irrelevant: the mocked lock manager decides the outcome, not the bytes. */
    private fun snapshotFile(): File =
        File.createTempFile("vault-20260831-000000", ".enc").apply {
            writeBytes(ByteArray(64) { it.toByte() })
            deleteOnExit()
        }
}

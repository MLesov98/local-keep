package com.localkeep.feature.backup

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.Outcome
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.FileBlobStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.File

/**
 * Regression test for F5: `RollingBackupManager.start()`'s `vaultRepository.onSaved.collect {
 * snapshotNow() }` used to discard the `Outcome<File>` [RollingBackupManager.snapshotNow]
 * returns, so a failed automatic snapshot produced no log and no user-visible signal at all —
 * while the very same function's prune-undeleted case (a few lines below it) already did
 * `Log.w`. Fixed by logging on `Outcome.Failure`, matching that existing tag/style.
 *
 * No fake writer is needed to make [RollingBackupManager.snapshotNow] fail: under Robolectric
 * there is no `vault.enc` under `filesDir`, so `VaultLoader.loadFileBytes` fails organically —
 * this is the exact failure at RollingBackupManager.kt's line ~55 the finding names. That is
 * confirmed directly before relying on the `start()` collector to notice it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollingBackupManagerSnapshotFailureTest {

    @Test
    fun `a staged snapshot is deleted when staging fails`() {
        val tmp = File.createTempFile("rolling-backup", ".tmp")
        try {
            assertFailsWith<IllegalStateException> {
                withStagedSnapshotCleanup(tmp) {
                    error("fsync failed")
                }
            }
            assertTrue(!tmp.exists(), "a failed staging operation must not leave a snapshot temp file")
        } finally {
            tmp.delete()
        }
    }

    // RollingBackupManager.LOG_TAG is private; kept in sync by hand.
    private val logTag = "LocalKeepBackup"

    @Test
    fun `a failed automatic snapshot is logged, not silently dropped`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val onSaved = MutableSharedFlow<Unit>()
        val vaultRepository = mock<VaultRepository>()
        whenever(vaultRepository.onSaved).thenReturn(onSaved)
        val manager = RollingBackupManager(
            context = application,
            vaultRepository = vaultRepository,
            vaultLockManager = mock<VaultLockManager>(),
            fileBlobStore = mock<FileBlobStore>(),
        )

        // No vault.enc exists on a fresh Robolectric filesDir, so this really does fail.
        assertIs<Outcome.Failure>(manager.snapshotNow())

        manager.start()
        // start()'s collector subscribes on a Dispatchers.IO thread, asynchronously. A
        // MutableSharedFlow with no buffer drops a value emitted while it has zero subscribers, so
        // wait for the subscription to land before emitting — otherwise this races and flakes.
        var subWait = 0
        while (onSaved.subscriptionCount.value == 0 && subWait < 200) {
            Thread.sleep(5)
            subWait++
        }
        onSaved.emit(Unit)

        // start()'s collector runs on a real Dispatchers.IO thread, independent of this test's
        // dispatcher, so poll briefly for the log line to land rather than assuming it already has
        // by the time emit() returns (emit() only guarantees hand-off to the collector, not that
        // its suspend body has finished running).
        var attempts = 0
        while (!hasFailureLog() && attempts < 50) {
            Thread.sleep(10)
            attempts++
        }

        assertTrue(hasFailureLog(), "expected a Log.w tagged $logTag after an automatic snapshot failed")
    }

    private fun hasFailureLog(): Boolean =
        ShadowLog.getLogs().any { it.tag == logTag && it.type == Log.WARN }
}

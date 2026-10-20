package com.localkeep.feature.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.Outcome
import com.localkeep.core.vault.RestoreOutcome
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.FileBlobStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every way a snapshot restore fails for good must reach the user as something other than
 * "Something went wrong. Please try again."
 *
 * [RollingBackupManager.restoreSnapshot] interpolated the snapshot's filename into all of its
 * failure literals, and `AppError.toBackupMessage`'s table matches on exact text — deliberately, so
 * a message carrying a filename cannot leak it through a partial match. The consequence was that no
 * restore failure could ever match: a snapshot that cannot be read, one that is not a vault file,
 * and one that is corrupt all resolved to [R.string.backup_error_generic]. All three are permanent
 * for the snapshot the user picked and none is changed by trying again, while the answer that does
 * work — pick an older snapshot, listed on the same screen directly above the message slot — was
 * never offered.
 *
 * The test drives the **real** [RollingBackupManager] to produce each literal and feeds that literal
 * to the ViewModel, so it fails if either half drifts: a renamed literal, or a table entry that
 * stops matching one. Asserting the ViewModel against a hand-typed string would keep passing while
 * the two halves disagreed, which is precisely the defect being fixed.
 *
 * Resource ids, not resolved text: the assertion is about which string is chosen, and no unit test
 * in this repo depends on Robolectric resolving a library module's strings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotRestoreFailureMessageTest {

    private val dispatcher = StandardTestDispatcher()

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A name distinctive enough that a leak of it into a mapped literal is unmistakable. */
    private fun snapshotFile(): File =
        File.createTempFile("vault-20260907-101112", ".enc").apply {
            writeBytes(ByteArray(64) { it.toByte() })
            deleteOnExit()
        }

    /** The failure the real manager raises for [outcome], literal and all. */
    private suspend fun realFailureFor(outcome: RestoreOutcome, file: File): Outcome.Failure {
        val vaultLockManager = mock<VaultLockManager>()
        whenever(vaultLockManager.restoreVaultFile(any())).thenReturn(outcome)
        val manager = RollingBackupManager(
            context = application,
            vaultRepository = mock<VaultRepository>(),
            vaultLockManager = vaultLockManager,
            fileBlobStore = mock<FileBlobStore>(),
        )
        return manager.restoreSnapshot(file) as Outcome.Failure
    }

    /** Runs [failure] through the ViewModel's restore path and returns the string it picks. */
    private suspend fun messageResFor(failure: Outcome.Failure, file: File): Int {
        val rollingBackupManager = mock<RollingBackupManager>()
        whenever(rollingBackupManager.restoreSnapshot(any())).thenReturn(failure)
        // The restore is behind a master-password gate (1C-O4), so this fixture has to get past it
        // before it can reach the failure mapping it is actually about.
        val vaultLockManager = mock<VaultLockManager>()
        whenever(vaultLockManager.reauthenticate(any())).thenReturn(true)
        val viewModel = BackupSettingsViewModel(
            application = application,
            vaultExporter = mock(),
            vaultImporter = mock(),
            rollingBackupManager = rollingBackupManager,
            vaultLockManager = vaultLockManager,
            vaultRepository = mock<VaultRepository>(),
        )
        viewModel.restoreSnapshot(file, "TestVaultAbc99".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()
        return (viewModel.state.value.error as BackupMessage.Res).res
    }

    /** The four permanent failures, in the order they are asserted about below. */
    private suspend fun permanentFailures(file: File): List<Outcome.Failure> {
        // Never read: `restoreSnapshot` fails on `readBytes` before it consults the lock manager,
        // which is what makes a file that is not there the way to reach the read-failure branch.
        val unreadable = File(file.parentFile, "vault-20260907-101112-absent.enc")
        assertFalse(unreadable.exists(), "the read-failure case needs a file that is not there")
        return listOf(
            realFailureFor(RestoreOutcome.OK, unreadable),
            realFailureFor(RestoreOutcome.INVALID_FILE, file),
            realFailureFor(RestoreOutcome.CORRUPT, file),
            // Regression for a 2026-09-09 finding: a snapshot from a newer LocalKeep must get its
            // own message too, not share INVALID_FILE's ("not a valid vault file") or CORRUPT's
            // ("was not restored") — both name the wrong next step for a snapshot that is fine and
            // just needs a newer app.
            realFailureFor(RestoreOutcome.NEWER_VERSION, file),
        )
    }

    @Test
    fun `an unreadable, an invalid, a corrupt and a too-new snapshot each get their own message`() =
        runTest(dispatcher) {
            val file = snapshotFile()
            val chosen = permanentFailures(file).map { messageResFor(it, file) }

            for (res in chosen) {
                assertNotEquals(
                    R.string.backup_error_generic,
                    res,
                    "a permanent restore failure was answered with a retry offer — either the " +
                        "literal carries a filename again, or the table no longer matches it",
                )
            }
            assertEquals(
                4,
                chosen.toSet().size,
                "four conditions with four different answers must not share one string",
            )
        }

    @Test
    fun `a mapped restore failure literal never carries the snapshot filename`() =
        runTest(dispatcher) {
            // The property the exact-match table exists to protect, kept rather than traded: a
            // snapshot is named `vault-<UTC stamp>.enc`, an internal storage id the UI shows
            // nowhere. It is also *why* these literals must be filename-free — an interpolated name
            // is what made them unmatchable in the first place.
            val file = snapshotFile()
            val stem = file.name.substringBefore(".")
            for (failure in permanentFailures(file)) {
                assertFalse(
                    failure.error.message.contains(stem),
                    "a mapped literal named the snapshot: ${failure.error.message}",
                )
            }
        }
}

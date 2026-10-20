package com.localkeep.feature.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for 16-1: `BackupSettingsUiState.snapshots` used to be read from
 * `rollingBackupManager.listSnapshots()` (a directory listing) in the state's field initializer,
 * on the ViewModel's constructing thread. The fix starts `emptyList()` and loads on
 * Dispatchers.IO in `init`.
 *
 * `VaultLockManager`/`VaultRepository` are final classes whose real construction chain reaches
 * `KeystoreManager`, which is not constructible under Robolectric (see the note in
 * feature/settings' SettingsViewModelBiometricLoadTest) — mocked here rather than built for real.
 * `RollingBackupManager` is mocked directly too so the test controls exactly when
 * `listSnapshots()` returns, without touching a real filesystem listing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSettingsViewModelSnapshotLoadTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(rollingBackupManager: RollingBackupManager): BackupSettingsViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return BackupSettingsViewModel(
            application = application,
            vaultExporter = mock(),
            vaultImporter = mock(),
            rollingBackupManager = rollingBackupManager,
            vaultLockManager = mock<VaultLockManager>(),
            vaultRepository = mock<VaultRepository>(),
        )
    }

    @Test
    fun `construction does no directory listing`() = runTest(dispatcher) {
        val rollingBackupManager = mock<RollingBackupManager>()
        whenever(rollingBackupManager.listSnapshots()).thenReturn(listOf(File("snapshot-1")))

        val viewModel = buildViewModel(rollingBackupManager)

        // Nothing has run on the (queuing, not auto-running) test dispatcher yet — this is the
        // state exactly as constructed.
        assertEquals(emptyList(), viewModel.state.value.snapshots)
    }

    @Test
    fun `snapshots arrive once the init coroutine runs`() = runTest(dispatcher) {
        val rollingBackupManager = mock<RollingBackupManager>()
        val expected = listOf(File("snapshot-1"), File("snapshot-2"))
        whenever(rollingBackupManager.listSnapshots()).thenReturn(expected)

        val viewModel = buildViewModel(rollingBackupManager)
        assertEquals(emptyList(), viewModel.state.value.snapshots)

        // listSnapshots() runs on the real Dispatchers.IO thread pool, not the virtual-time test
        // dispatcher — poll briefly for the continuation to post back, same as the settings test.
        var attempts = 0
        while (viewModel.state.value.snapshots.isEmpty() && attempts < 50) {
            dispatcher.scheduler.advanceUntilIdle()
            if (viewModel.state.value.snapshots.isEmpty()) Thread.sleep(10)
            attempts++
        }

        assertTrue(viewModel.state.value.snapshots.isNotEmpty())
        assertEquals(expected, viewModel.state.value.snapshots)
    }
}

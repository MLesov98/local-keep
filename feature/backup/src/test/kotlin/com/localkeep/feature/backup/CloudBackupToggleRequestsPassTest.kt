package com.localkeep.feature.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.vault.VaultRepository
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cloud-backup switch asks Android for a backup pass, in both directions.
 *
 * The toggle wrote its preference and nothing else. That preference is read only by
 * `LocalKeepBackupAgent.onFullBackup`, which Android calls on its own schedule — so until a pass
 * ran, the setting the user had just chosen was not the one in force: OFF left a `vault.enc`
 * already in Google's backup set sitting there, and ON left the vault out of it (S3, sweep
 * `visual-20260915`). `dataChanged()` cannot promise a time and does not upload anything itself; it
 * marks the app as due, which is the most this setting can do about the copy that already exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudBackupToggleRequestsPassTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModelWith(notifier: BackupDataChangeNotifier): BackupSettingsViewModel =
        BackupSettingsViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            vaultExporter = mock(),
            vaultImporter = mock(),
            rollingBackupManager = mock(),
            vaultLockManager = mock(),
            vaultRepository = mock<VaultRepository>(),
            backupDataChangeNotifier = notifier,
        )

    @Test
    fun `turning cloud backup off asks for a backup pass`() = runTest(dispatcher) {
        val notifier = mock<BackupDataChangeNotifier>()

        viewModelWith(notifier).setDisableCloudBackup(true)

        verify(notifier).dataChanged()
    }

    /**
     * The other direction, and not a duplicate of the test above: turning the backup back ON has
     * the same problem in mirror image — the vault stays out of Google's set until a pass runs —
     * and a fix that only notified on the way off would leave it.
     */
    @Test
    fun `turning cloud backup on asks for a backup pass too`() = runTest(dispatcher) {
        val notifier = mock<BackupDataChangeNotifier>()

        viewModelWith(notifier).setDisableCloudBackup(false)

        verify(notifier).dataChanged()
    }

    /** Every change asks, not just the first — the pass has to follow the setting each time. */
    @Test
    fun `each change asks again`() = runTest(dispatcher) {
        val notifier = mock<BackupDataChangeNotifier>()
        val viewModel = viewModelWith(notifier)

        viewModel.setDisableCloudBackup(true)
        viewModel.setDisableCloudBackup(false)
        viewModel.setDisableCloudBackup(true)

        verify(notifier, times(3)).dataChanged()
    }
}

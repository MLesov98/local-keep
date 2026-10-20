package com.localkeep.feature.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.vault.VaultLockManager
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Regression test for F17: `BackupSettingsViewModel` used to set
 * `BackupMessage.Raw(result.error.message)` straight from the `AppError` on every export/import/
 * restore failure path, and `BackupSettingsScreen`'s `resolveBackupMessage` rendered that `Raw`
 * text verbatim — an unlocalized `AppError.message` reaching the UI, contradicting
 * the rule that "AppError messages never reach the UI by design". The `Raw` variant is
 * now gone; every failure goes through `AppError.toBackupMessage()`, which maps each known
 * `VaultImporter`/`VaultExporter`/`RollingBackupManager` literal to a real `@StringRes` and falls
 * back to [R.string.backup_error_generic] for anything else.
 *
 * Drives [BackupSettingsViewModel.stageImport] because its failure path needs no `reauthenticate`
 * stub (unlike `export`) to reach the mapping — `vaultImporter.readVault` is the only call in the
 * way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSettingsViewModelErrorMappingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(vaultImporter: VaultImporter): BackupSettingsViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return BackupSettingsViewModel(
            application = application,
            vaultExporter = mock(),
            vaultImporter = vaultImporter,
            rollingBackupManager = mock(),
            vaultLockManager = mock<VaultLockManager>(),
            vaultRepository = mock<VaultRepository>(),
        )
    }

    @Test
    fun `a known import failure maps to its own string resource, not a raw AppError message`() =
        runTest(dispatcher) {
            val vaultImporter = mock<VaultImporter>()
            whenever(vaultImporter.readVault(any(), any())).thenReturn(
                Outcome.Failure(AppError.Validation("not a valid .vault file")),
            )
            val viewModel = buildViewModel(vaultImporter)

            viewModel.stageImport(mock<Uri>(), "pw".toCharArray())
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                BackupMessage.Res(R.string.backup_error_import_invalid_file),
                viewModel.state.value.error,
            )
        }

    @Test
    fun `an unmapped AppError literal falls back to the generic resource, not a raw passthrough`() =
        runTest(dispatcher) {
            val vaultImporter = mock<VaultImporter>()
            whenever(vaultImporter.readVault(any(), any())).thenReturn(
                Outcome.Failure(AppError.Storage("some future failure text nobody mapped yet")),
            )
            val viewModel = buildViewModel(vaultImporter)

            viewModel.stageImport(mock<Uri>(), "pw".toCharArray())
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                BackupMessage.Res(R.string.backup_error_generic),
                viewModel.state.value.error,
            )
        }
}

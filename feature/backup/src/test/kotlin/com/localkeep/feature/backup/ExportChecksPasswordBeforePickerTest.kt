package com.localkeep.feature.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Export checks the master password before it asks for a destination.
 *
 * Driven on device 2026-09-15: a wrong master password opened the SAF **save** picker, and only
 * after the user had browsed for a folder, typed a name and tapped SAVE did the screen answer
 * `Wrong master password. Enter it again to retry.` — by which point `ACTION_CREATE_DOCUMENT` had
 * already made a 0-byte `.vault` there for `discardEmptyExport` to clean up (F-39, sweep
 * `visual-20260915`).
 *
 * [BackupSettingsUiState.exportDestinationRequested] is the whole of the ordering: it is the only
 * thing that opens the picker, and nothing else in the ViewModel sets it. A wrong password must
 * therefore leave it false while still producing the same refusal in the same slot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportChecksPasswordBeforePickerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // `suspend` only because stubbing `reauthenticate`, which is one, needs a coroutine to be named
    // from. Every caller is already inside `runTest`.
    private suspend fun viewModelFor(passwordAccepted: Boolean): BackupSettingsViewModel {
        val vaultLockManager = mock<VaultLockManager>()
        whenever(vaultLockManager.reauthenticate(any())).thenAnswer { passwordAccepted }
        return BackupSettingsViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            vaultExporter = mock(),
            vaultImporter = mock(),
            rollingBackupManager = mock(),
            vaultLockManager = vaultLockManager,
            vaultRepository = mock<VaultRepository>(),
        )
    }

    @Test
    fun `a wrong master password never asks for a destination`() = runTest(dispatcher) {
        val viewModel = viewModelFor(passwordAccepted = false)

        viewModel.requestExportDestination("wrong".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.state.value.exportDestinationRequested)
        assertEquals(
            BackupMessage.Res(R.string.backup_error_export_wrong_password),
            viewModel.state.value.error,
        )
        assertFalse(viewModel.state.value.isWorking)
    }

    /**
     * The control that must stay green: a refusal that also refused the right password would pass
     * the test above while making export impossible.
     */
    @Test
    fun `the right master password asks for a destination`() = runTest(dispatcher) {
        val viewModel = viewModelFor(passwordAccepted = true)

        viewModel.requestExportDestination("right".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value.exportDestinationRequested)
        assertEquals(null, viewModel.state.value.error)
    }

    /** One approval, one picker: the screen consumes the request as it launches the intent. */
    @Test
    fun `consuming the request clears it`() = runTest(dispatcher) {
        val viewModel = viewModelFor(passwordAccepted = true)

        viewModel.requestExportDestination("right".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.consumeExportDestinationRequest()

        assertFalse(viewModel.state.value.exportDestinationRequested)
    }

    /**
     * The password array is this call's to own, on the refused path as much as the accepted one —
     * the same term [BackupSettingsViewModel.export] documents about double taps.
     */
    @Test
    fun `the master password is wiped whichever way the check goes`() = runTest(dispatcher) {
        val viewModel = viewModelFor(passwordAccepted = false)
        val password = "wrong".toCharArray()

        viewModel.requestExportDestination(password)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(password.all { it == '\u0000' })
    }
}

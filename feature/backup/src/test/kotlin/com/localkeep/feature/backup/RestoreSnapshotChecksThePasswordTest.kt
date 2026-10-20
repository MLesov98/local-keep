package com.localkeep.feature.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.Outcome
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.io.File
import kotlin.test.assertEquals
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Restoring a snapshot re-verifies the vault password before it overwrites anything.
 *
 * Driven on device 2026-09-20 (release sweep, 1C-O4): a snapshot restore asked for one tap on a
 * confirmation dialog and nothing else, and then replaced the live vault — discarding everything
 * saved since and detaching every file added since. It was the only irreversible control in the app
 * with no re-authentication in front of it; export, the KDF recalibration and both duress changes
 * all ask for the same password.
 *
 * The ordering is the property. A refused password must leave [RollingBackupManager] untouched:
 * `restoreSnapshot` force-locks and replaces `vault.enc`, so "checked afterwards" is indistinguishable
 * from "not checked at all".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreSnapshotChecksThePasswordTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Fixture(
        val viewModel: BackupSettingsViewModel,
        val rollingBackupManager: RollingBackupManager,
    )

    // `suspend` only because stubbing `reauthenticate`, which is one, needs a coroutine to be named
    // from. Every caller is already inside `runTest`.
    private suspend fun fixtureFor(passwordAccepted: Boolean): Fixture {
        val vaultLockManager = mock<VaultLockManager>()
        whenever(vaultLockManager.reauthenticate(any())).thenAnswer { passwordAccepted }
        val rollingBackupManager = mock<RollingBackupManager>()
        whenever(rollingBackupManager.restoreSnapshot(any())).thenAnswer { Outcome.Success(Unit) }
        return Fixture(
            viewModel = BackupSettingsViewModel(
                application = ApplicationProvider.getApplicationContext<Application>(),
                vaultExporter = mock(),
                vaultImporter = mock(),
                rollingBackupManager = rollingBackupManager,
                vaultLockManager = vaultLockManager,
                vaultRepository = mock<VaultRepository>(),
            ),
            rollingBackupManager = rollingBackupManager,
        )
    }

    private fun snapshot(): File = File("vault-20260920-092355.enc")

    @Test
    fun `a wrong master password never reaches the restore`() = runTest(dispatcher) {
        val fixture = fixtureFor(passwordAccepted = false)

        fixture.viewModel.restoreSnapshot(snapshot(), "wrong".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()

        // `never()` on the one method, not `verifyNoInteractions`: the ViewModel's own init lists
        // the snapshots through this same manager, so "touched it at all" is not the property.
        verify(fixture.rollingBackupManager, never()).restoreSnapshot(any())
        assertEquals(
            BackupMessage.Res(R.string.backup_error_restore_wrong_password),
            fixture.viewModel.state.value.error,
            "a refused restore must say which credential was wrong, in the screen's error slot",
        )
    }

    @Test
    fun `the right master password restores`() = runTest(dispatcher) {
        val fixture = fixtureFor(passwordAccepted = true)
        val file = snapshot()

        fixture.viewModel.restoreSnapshot(file, "right".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()

        verify(fixture.rollingBackupManager).restoreSnapshot(file)
        assertEquals(null, fixture.viewModel.state.value.error)
    }

    /**
     * The control the case above needs: a test that only asserted "the restore did not run" would
     * pass on a ViewModel that never restores at all.
     */
    @Test
    fun `a refused restore is not simply a broken one`() = runTest(dispatcher) {
        val fixture = fixtureFor(passwordAccepted = false)

        fixture.viewModel.restoreSnapshot(snapshot(), "wrong".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()

        verify(fixture.rollingBackupManager, never()).restoreSnapshot(any())
    }
}

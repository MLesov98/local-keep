package com.localkeep.feature.settings

import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.vault.AutoLockPreset
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.feature.files.BlobGarbageCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression test for 2-2 (`verdicts/2-2.json`, PARTIAL).
 *
 * `restorePurchases` used to be the only mutating entry point in [SettingsViewModel] that neither
 * checked `isWorking` before starting nor went through `runOperation` — yet it unconditionally
 * cleared `isWorking` when it finished. The headline concurrent-rotation harm the original finding
 * described was refuted (the submit form clears its own fields before calling the ViewModel, and
 * `VaultLockManager` serialises destructive operations on its own mutex), but three real symptoms
 * survived review: the "Working…" note can disappear mid-operation, a spurious "no Pro purchase
 * found" error can appear while something else is still running, and — the part the original report missed —
 * a successful operation's own completion did not clear an error left behind by whatever raced it,
 * so a stale error could sit on screen beside a success note.
 *
 * The fix guards `restorePurchases` with a plain `if (_state.value.isWorking) return`
 * (`SettingsViewModel.kt`, start of `restorePurchases`) rather than routing it through
 * `runOperation`, because `runOperation` maps a null result message to `GENERIC_FAILURE` and a
 * restore that found nothing to restore is not a failure. It also makes `runOperation`'s own
 * success and failure branches clear the field they do not own, so a leftover from an unrelated
 * setter cannot survive next to a fresh result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestorePurchasesInFlightGuardTest {

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
        val viewModel: SettingsViewModel,
        val entitlement: Entitlement,
        val biometricUnlock: BiometricUnlock,
    )

    private fun fixture(biometricAvailable: Boolean = false): Fixture {
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))
        // `removeDuress` is behind a vault-password re-auth; this fixture uses it only as a
        // long-running operation, so the gate is satisfied.
        lockManager.stub { onBlocking { reauthenticateForCurrentVault(any()) } doReturn true }
        val keystoreManager = mock<KeystoreManager>()
        whenever(keystoreManager.isStrongBiometricAvailable()).thenReturn(biometricAvailable)
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))
        val biometricUnlock = mock<BiometricUnlock>()

        // A locked repository: SettingsViewModel's usage collector reads nothing while the vault
        // is shut, and an unstubbed presentationState would hand its collector a null flow.
        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow(VaultPresentationState.Locked))

        val viewModel = SettingsViewModel(
            lockManager = lockManager,
            repository = repository,
            keystoreManager = keystoreManager,
            biometricUnlock = biometricUnlock,
            entitlement = entitlement,
            breachFilter = mock<BreachFilter>(),
            blobGarbageCollector = mock<BlobGarbageCollector>(),
            context = ApplicationProvider.getApplicationContext(),
        )
        return Fixture(viewModel, entitlement, biometricUnlock)
    }

    /**
     * `removeDuress` finishes with an opportunistic blob sweep on `Dispatchers.IO`, which is a real
     * dispatcher: advancing virtual time can return while the resume task has not been posted back
     * yet, so the operation reads as "never completed" and its message is still null. `isWorking` is
     * the operation's own completion flag — set true synchronously by `runOperation`, cleared only
     * once the whole block is done. Same helper, same reasoning, as `DuressTransitionReclaimsBlobsTest`.
     */
    private fun Fixture.awaitOperation() {
        var attempts = 0
        while (attempts < 200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (!viewModel.state.value.isWorking) return
            Thread.sleep(10)
            attempts++
        }
    }

    /**
     * Production line under test: the `if (_state.value.isWorking) return` guard at the top of
     * `SettingsViewModel.restorePurchases`. Delete it and this test goes red — `restorePurchases`
     * would launch its coroutine regardless, call `entitlement.restore()`, and clear `isWorking`
     * out from under `removeDuress`, which is still running.
     */
    @Test
    fun `restorePurchases refuses to start while another operation is already in flight`() =
        runTest(dispatcher) {
            val f = fixture()

            // Starts a real runOperation-backed mutation; its start-clear runs synchronously, so
            // isWorking is already true here even though the coroutine has not been driven yet.
            f.viewModel.removeDuress("TestVaultAbc99".toCharArray())
            assertEquals(true, f.viewModel.state.value.isWorking)

            f.viewModel.restorePurchases()

            dispatcher.scheduler.advanceUntilIdle()

            verifyBlocking(f.entitlement, never()) { restore() }
        }

    /**
     * Production line under test: `error = null` added to `runOperation`'s success branch
     * (`SettingsViewModel.kt`, the `else` branch inside `.onSuccess`). Remove just that clause and
     * this test goes red — the stale error set by `enableBiometric`'s synchronous early return
     * would still be present in the state that `removeDuress`'s successful completion produces.
     */
    @Test
    fun `a successful operation clears a stale error left by an unrelated setter`() =
        runTest(dispatcher) {
            val f = fixture(biometricAvailable = false)

            f.viewModel.removeDuress("TestVaultAbc99".toCharArray())
            assertEquals(true, f.viewModel.state.value.isWorking)

            // biometricAvailable is false, so this takes the synchronous setError early return —
            // it never reaches runOperation and so never checks or touches isWorking.
            f.viewModel.enableBiometric(mock<FragmentActivity>())
            assertEquals(
                SettingsMessage.Simple(R.string.settings_error_biometric_unavailable),
                f.viewModel.state.value.error,
            )

            f.awaitOperation()

            assertEquals(
                SettingsMessage.Simple(R.string.settings_message_duress_removed),
                f.viewModel.state.value.message,
            )
            assertNull(
                f.viewModel.state.value.error,
                "the completed operation's success must supersede a stale error, not sit beside it",
            )
        }

    /**
     * Production line under test: `message = null` added to `runOperation`'s failure branch
     * (`SettingsViewModel.kt`, the final `_state.update` inside `.onFailure`). Remove just that
     * clause and this test goes red — the stale "autolock updated" message set while the biometric
     * enable was in flight would still be present in the state its failure produces.
     */
    @Test
    fun `a failed operation clears a stale message left by an unrelated setter`() =
        runTest(dispatcher) {
            val f = fixture(biometricAvailable = true)
            val biometricUnlock = f.biometricUnlock
            whenever(biometricUnlock.encryptCipher())
                .thenThrow(RuntimeException("keystore unavailable"))

            // Start-clear runs synchronously and wipes any prior message, so the stale message has
            // to be introduced after the operation has already started, not before.
            f.viewModel.enableBiometric(mock<FragmentActivity>())
            assertEquals(true, f.viewModel.state.value.isWorking)

            // A different, ungated setter — like the auto-lock row — writes into message while the
            // biometric enable is still in flight.
            f.viewModel.setAutoLockPreset(AutoLockPreset.BALANCED)
            assertEquals(
                SettingsMessage.Simple(R.string.settings_message_autolock_updated),
                f.viewModel.state.value.message,
            )

            dispatcher.scheduler.advanceUntilIdle()

            // encryptCipher() throwing is uncaught inside the runOperation block, so it lands on
            // onFailure's generic (`else ->`) branch.
            assertEquals(
                SettingsMessage.Simple(R.string.settings_error_generic),
                f.viewModel.state.value.error,
            )
            assertNull(
                f.viewModel.state.value.message,
                "the failed operation's error must supersede a stale message, not sit beside it",
            )
        }
}

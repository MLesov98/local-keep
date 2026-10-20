package com.localkeep.feature.settings

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.billing.PurchaseOutcome
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.vault.DeviceKeyStore
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.feature.files.BlobGarbageCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import com.localkeep.core.ui.R as CoreUiR

/**
 * Regression test for 3-3: the result of [Entitlement.purchase] was discarded, here and at the
 * vault list's and item editor's identical copies of `unlockPro`.
 *
 * `PlayBillingEntitlement.purchase` has several paths that return without Play's sheet ever
 * appearing — no billing connection, no product details, a misconfigured product, a launch Play
 * refused. On every one of them the price button, the app's only paid-conversion control, produced
 * no sheet, no error and no state change at all. Being offline is the ordinary state of this app's
 * users by design, so that was the ordinary experience of trying to pay.
 *
 * The two answers are deliberately different. A failure is retryable and says so; a PENDING
 * purchase is already on its way (Play's listener completes it asynchronously and the eventual
 * `isPro` flip announces itself), so offering it a retry would be a lie that invites a second
 * charge — it goes on the confirmation channel, not the error one.
 *
 * Mocking follows `SettingsViewModelBiometricLoadTest`: `KeystoreManager`/`VaultLockManager` are
 * final classes whose real constructors touch AndroidKeyStore, which Robolectric does not
 * implement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockProReportsItsOutcomeTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a purchase that never started is reported as retryable`() = runTest(dispatcher) {
        val state = stateAfterPurchase(PurchaseOutcome.Failed)

        assertEquals(
            SettingsMessage.Simple(CoreUiR.string.core_ui_pro_gate_purchase_failed),
            state.error,
            "an unlock that produced no Play sheet must say so — otherwise the button reads as inert",
        )
    }

    @Test
    fun `a pending purchase is announced as waiting, never as something to retry`() =
        runTest(dispatcher) {
            val state = stateAfterPurchase(PurchaseOutcome.Pending)

            assertEquals(
                SettingsMessage.Simple(CoreUiR.string.core_ui_pro_gate_purchase_pending),
                state.message,
                "a purchase Play is still clearing has its own copy",
            )
            assertNull(
                state.error,
                "a pending purchase has not failed; an error invites a second charge",
            )
        }

    @Test
    fun `a cancelled purchase says nothing`() = runTest(dispatcher) {
        val state = stateAfterPurchase(PurchaseOutcome.Cancelled)

        assertNull(state.error, "the user dismissed Play's own sheet; a complaint about it is noise")
        assertNull(state.message)
    }

    @Test
    fun `a second tap while a flow is already open says nothing`() = runTest(dispatcher) {
        val state = stateAfterPurchase(PurchaseOutcome.AlreadyInFlight)

        assertNull(state.error, "the first flow is still on screen and speaks for itself")
        assertNull(state.message)
    }

    @Test
    fun `a completed purchase says nothing here`() = runTest(dispatcher) {
        val state = stateAfterPurchase(PurchaseOutcome.Purchased)

        assertNull(state.error)
        assertNull(state.message, "the isPro flip is what announces a successful unlock")
    }

    private suspend fun kotlinx.coroutines.test.TestScope.stateAfterPurchase(
        outcome: PurchaseOutcome,
    ): SettingsUiState {
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))
        whenever(entitlement.purchase(any())).thenReturn(outcome)

        val viewModel = buildViewModel(entitlement)
        awaitInitBiometricProbe(viewModel)
        viewModel.unlockPro(mock<Activity>())
        advanceUntilIdle()

        return viewModel.state.value
    }

    /**
     * Waits for [SettingsViewModel]'s init biometric probe to land, and it is not optional.
     *
     * That probe is `viewModelScope.launch { withContext(Dispatchers.IO) { DeviceKeyStore.load() } }`
     * — a hop onto the **real** IO pool, which the test scheduler does not drive. `advanceUntilIdle`
     * therefore cannot wait for it: it drains the scheduler, not a foreign thread. When the read
     * finishes, the continuation dispatches back to Main, and if `@After`'s `resetMain()` happens to
     * run at that instant, `TestMainDispatcher` throws
     * `Dispatchers.Main is used concurrently with setting it`.
     *
     * That is the flake this suite showed on 2026-09-13, reproduced deterministically at iteration
     * 77 of 400 by replaying this class's own setMain/build/resetMain shape in a loop. It is a race,
     * not a broken assertion, which is why re-running "fixed" it and why a retry would have hidden it
     * rather than closed it.
     *
     * Awaiting here moves the dispatch *inside* the test body, while Main is still ours — so by the
     * time `@After` runs there is nothing left in flight. The three collectors init also starts stay
     * suspended on flows that never emit again; a suspended coroutine dispatches nothing, so they are
     * not part of this and are deliberately left alone.
     *
     * The probe is only observable because [buildViewModel] plants a loadable device-key file, which
     * flips `biometricEnabled` to true. Nothing in these tests asserts on that flag.
     */
    private suspend fun awaitInitBiometricProbe(viewModel: SettingsViewModel) {
        viewModel.state.first { it.biometricEnabled }
    }

    /**
     * A device-key file [DeviceKeyStore.load] will accept, so the init probe has a visible result.
     *
     * The contents are never decrypted — `load` only checks the length — but the length has to be
     * right or it returns null, the flag never flips and [awaitInitBiometricProbe] waits forever.
     * The assertion below is what turns that into an immediate, explained failure instead of a
     * sixty-second timeout: `FILE_NAME` is `internal` to `:core:vault`, so this spelling of it is a
     * copy that a rename would silently rot.
     */
    private fun plantLoadableDeviceKeyFile(context: android.content.Context) {
        val size = CryptoConstants.AEAD_NONCE_LEN + CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN
        File(context.filesDir, "device_keys.bin").writeBytes(ByteArray(size))
        assertNotNull(
            DeviceKeyStore.load(context),
            "the planted device-key file must be loadable, or awaitInitBiometricProbe never returns " +
                "— DeviceKeyStore.FILE_NAME or its expected size has changed",
        )
    }

    private fun buildViewModel(entitlement: Entitlement): SettingsViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        plantLoadableDeviceKeyFile(context)

        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))

        // A locked repository: SettingsViewModel's usage collector reads nothing while the vault
        // is shut, and an unstubbed presentationState would hand its collector a null flow.
        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow(VaultPresentationState.Locked))

        return SettingsViewModel(
            lockManager = lockManager,
            repository = repository,
            keystoreManager = mock<KeystoreManager>(),
            biometricUnlock = mock<BiometricUnlock>(),
            entitlement = entitlement,
            breachFilter = mock<BreachFilter>(),
            blobGarbageCollector = mock<BlobGarbageCollector>(),
            context = context,
        )
    }
}

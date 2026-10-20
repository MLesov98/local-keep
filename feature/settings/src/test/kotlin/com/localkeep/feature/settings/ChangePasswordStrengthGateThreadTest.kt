package com.localkeep.feature.settings

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The master-password strength gate must not run on the caller's thread.
 *
 * `changePassword` is invoked straight from a click handler, so its synchronous prelude is the main
 * thread. zxcvbn's `measure` is tens of milliseconds of matcher passes plus a search — enough to
 * drop frames — and it sat in that prelude, in the one function that already moves its three Argon2
 * derivations to `Dispatchers.Default` for exactly this reason.
 *
 * What makes this observable without a device: `Dispatchers.Main` is a [StandardTestDispatcher],
 * which *queues* launched work instead of running it. So if the gate still ran inline, the rejection
 * would be visible the instant `changePassword` returned. It must not be — it may only appear after
 * the scheduler is allowed to run.
 *
 * `KeystoreManager`/`VaultLockManager` are mocked for the reason given in
 * `SettingsViewModelBiometricLoadTest`: their real constructors reach AndroidKeyStore, which
 * Robolectric does not implement. Nothing in this test reaches either of them — a rejected password
 * never gets as far as the vault.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangePasswordStrengthGateThreadTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): SettingsViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))
        val keystoreManager = mock<KeystoreManager>()
        val biometricUnlock = mock<BiometricUnlock>()
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))
        val breachFilter = mock<BreachFilter>()

        // A locked repository: SettingsViewModel's usage collector reads nothing while the vault
        // is shut, and an unstubbed presentationState would hand its collector a null flow.
        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow(VaultPresentationState.Locked))

        return SettingsViewModel(
            lockManager = lockManager,
            repository = repository,
            keystoreManager = keystoreManager,
            biometricUnlock = biometricUnlock,
            entitlement = entitlement,
            breachFilter = breachFilter,
            blobGarbageCollector = mock<BlobGarbageCollector>(),
            context = context,
        )
    }

    @Test
    fun `the strength gate does not run on the calling thread`() = runTest(dispatcher) {
        val viewModel = buildViewModel()

        // Long enough to clear the length gate (which is a cheap size check and stays inline), weak
        // enough to fail the score gate — the exact password the production comment names.
        viewModel.changePassword("whatever-the-old-one-is".toCharArray(), "Password12345".toCharArray())

        assertNull(
            viewModel.state.value.error,
            "the strength gate ran inline on the calling thread — no queued work has been allowed to run yet",
        )
        assertTrue(
            viewModel.state.value.isWorking,
            "scoring is work and must be covered by the working state, not done before it is set",
        )
    }

    @Test
    fun `a weak password is still rejected, with the same message`() = runTest(dispatcher) {
        val viewModel = buildViewModel()

        viewModel.changePassword("whatever-the-old-one-is".toCharArray(), "Password12345".toCharArray())

        // The score runs on the real Dispatchers.Default pool rather than the virtual-time test
        // dispatcher, so advancing the scheduler alone can race the continuation posting back.
        var error: SettingsMessage? = null
        var attempts = 0
        while (error == null && attempts < 50) {
            dispatcher.scheduler.advanceUntilIdle()
            error = viewModel.state.value.error
            if (error == null) Thread.sleep(10)
            attempts++
        }

        assertEquals(SettingsMessage.Simple(R.string.settings_error_password_too_weak), error)
        assertTrue(!viewModel.state.value.isWorking, "the working state must be cleared on rejection")
    }
}

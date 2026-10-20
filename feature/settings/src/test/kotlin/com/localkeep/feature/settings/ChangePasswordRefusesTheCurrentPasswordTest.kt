package com.localkeep.feature.settings

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Re-setting the master password to the password it already is must be refused.
 *
 * It was accepted (device, 2026-09-20): the rotation ran, the banner said "Master password
 * changed", and the Recovery Kit the user had written down stopped opening the vault — while the
 * password that opened it before still did. Two things are wrong with that, and the second is the
 * damage: a kit is revoked for a change that did not happen.
 *
 * Refused before any derivation, so the assertion is on the synchronous prelude: with
 * `Dispatchers.Main` a [StandardTestDispatcher] nothing launched has been allowed to run when the
 * error is already on screen.
 *
 * `KeystoreManager`/`VaultLockManager` are mocked for the reason given in
 * `SettingsViewModelBiometricLoadTest`. Nothing here reaches either — a refused password never
 * gets as far as the vault.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangePasswordRefusesTheCurrentPasswordTest {

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
    fun `a new password equal to the current one is refused, and nothing is started`() =
        runTest(dispatcher) {
            val viewModel = buildViewModel()

            viewModel.changePassword("TestVaultAbc99".toCharArray(), "TestVaultAbc99".toCharArray())

            assertEquals(
                SettingsMessage.Simple(R.string.settings_error_password_unchanged),
                viewModel.state.value.error,
            )
            assertFalse(
                viewModel.state.value.isWorking,
                "the refusal is synchronous — no operation may have been started",
            )
        }

    @Test
    fun `both arrays are wiped by the refusal`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        val current = "TestVaultAbc99".toCharArray()
        val candidate = "TestVaultAbc99".toCharArray()

        viewModel.changePassword(current, candidate)

        assertTrue(current.all { it == '\u0000' }, "the current password was left in memory")
        assertTrue(candidate.all { it == '\u0000' }, "the new password was left in memory")
    }

    @Test
    fun `a genuinely different password is not refused by this gate`() = runTest(dispatcher) {
        val viewModel = buildViewModel()

        viewModel.changePassword("TestVaultAbc99".toCharArray(), "DifferentAbc99x".toCharArray())

        assertEquals(null, viewModel.state.value.error)
        assertTrue(
            viewModel.state.value.isWorking,
            "the change must have been started, so the gate is not refusing everything",
        )
    }
}

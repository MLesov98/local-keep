package com.localkeep.feature.settings

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricAuthException
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.feature.files.BlobGarbageCollector
import javax.crypto.Cipher
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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Backing out of the biometric prompt is a choice, not a failure.
 *
 * `BiometricUnlock.authenticate` resumes with a [BiometricAuthException] for *every* non-success
 * code, and [SettingsViewModel.enableBiometric] runs inside `runOperation`, whose failure mapping
 * had no arm for it — so tapping the prompt's "Use master password" button, or dismissing it,
 * landed on `else -> GENERIC_FAILURE` and answered a routine, offered action with the coral "That
 * didn't go through. Try again." `feature/unlock` has treated the same two codes as a choice since
 * 2026-08-30, citing `LkColors`' own test for coral ("is the user's data less safe than they
 * believe"), which a user declining a prompt is not.
 *
 * Nothing is enrolled on that path either way — `lockManager.enableBiometricUnlock` is never
 * reached — so saying nothing is the correct outcome, and the row keeps reading "off".
 *
 * The classifier is [com.localkeep.core.crypto.isUserChoice], which lives beside the exception in
 * `:core:crypto` rather than being copied a third time: `feature/unlock`'s
 * `biometricErrorIsUserChoice` is `internal` to that module and `core/ui`'s `revealOutcomeFor` is
 * `internal` to its own, so neither was reachable from here.
 *
 * Production line under test: the `if (cause is BiometricAuthException && cause.isUserChoice())`
 * branch at the top of `runOperation`'s `.onFailure`. Delete that branch and the first test goes
 * red; the two control tests below stay green either way, which is what makes them controls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricEnableCancelIsNotAFailureTest {

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
        val activity: FragmentActivity,
    )

    /**
     * [errorCode] is the code the mocked prompt reports. A real, uninitialised [Cipher] rather than
     * a mocked one: `encryptCipher()` must hand back something non-null, and nothing ever uses it
     * because `authenticate` is stubbed.
     */
    private fun fixture(errorCode: Int?): Fixture {
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))
        val keystoreManager = mock<KeystoreManager>()
        whenever(keystoreManager.isStrongBiometricAvailable()).thenReturn(true)
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))
        val biometricUnlock = mock<BiometricUnlock>()
        whenever(biometricUnlock.encryptCipher()).thenReturn(Cipher.getInstance("AES/GCM/NoPadding"))
        if (errorCode != null) {
            // `doAnswer`, not `doThrow`: BiometricAuthException is a checked exception and a
            // suspend function compiles to a Java method with no `throws` clause, which Mockito's
            // stubbing validator rejects. Same idiom, for the same reason, as
            // BackupSettingsViewModelLockFailureTest.
            biometricUnlock.stub {
                onBlocking { authenticate(any(), any(), any(), any(), any()) } doAnswer {
                    throw BiometricAuthException("prompt reported $errorCode", errorCode)
                }
            }
        }

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
        return Fixture(viewModel, mock<FragmentActivity>())
    }

    /** `enableBiometric` is entirely on the test dispatcher; the loop only covers the init hop. */
    private fun Fixture.awaitOperation() {
        var attempts = 0
        while (attempts < 200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (!viewModel.state.value.isWorking) return
            Thread.sleep(10)
            attempts++
        }
    }

    @Test
    fun `declining the prompt says nothing at all`() = runTest(dispatcher) {
        listOf(
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_USER_CANCELED,
        ).forEach { code ->
            val f = fixture(code)

            f.viewModel.enableBiometric(f.activity)
            f.awaitOperation()

            assertNull(
                f.viewModel.state.value.error,
                "code $code is the user choosing the master password instead, and it was " +
                    "answered with the coral generic failure",
            )
            assertNull(f.viewModel.state.value.message, "nothing happened, so nothing to confirm")
            assertFalse(f.viewModel.state.value.biometricEnabled, "nothing was enrolled")
            assertFalse(f.viewModel.state.value.isWorking)
        }
    }

    @Test
    fun `a real biometric failure is still reported`() = runTest(dispatcher) {
        // Control. `core/ui`'s revealOutcomeFor is the repo's own authority that ERROR_CANCELED is
        // not a user's choice — it is the sensor going away underneath them — so it must keep
        // reaching the screen. Nothing is known about it beyond that, which is what the generic
        // sentence is for.
        //
        // ERROR_LOCKOUT used to be asserted here too and now has a test of its own below: it is
        // the one code in this group the app *does* know something about.
        listOf(BiometricPrompt.ERROR_CANCELED).forEach { code ->
            val f = fixture(code)

            f.viewModel.enableBiometric(f.activity)
            f.awaitOperation()

            assertEquals(
                SettingsMessage.Simple(R.string.settings_error_generic),
                f.viewModel.state.value.error,
                "code $code was suppressed along with the two that are a user's choice",
            )
        }
    }

    /**
     * A lockout during enrolment was answered with "That didn't go through. Try again." — advice
     * that reproduces the failure, since retrying is precisely what the lockout refuses (device,
     * 2026-09-20). `core/ui`'s `revealOutcomeFor` had mapped these two codes to their own outcome
     * since before this; only the enable side had not.
     */
    @Test
    fun `a lockout is named rather than answered with try again`() = runTest(dispatcher) {
        listOf(
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        ).forEach { code ->
            val f = fixture(code)

            f.viewModel.enableBiometric(f.activity)
            f.awaitOperation()

            assertEquals(
                SettingsMessage.Simple(R.string.settings_error_biometric_lockout),
                f.viewModel.state.value.error,
                "code $code fell into the catch-all and told the user to do the thing the " +
                    "sensor is currently refusing",
            )
            assertFalse(f.viewModel.state.value.biometricEnabled, "nothing was enrolled")
        }
    }

    @Test
    fun `an enrolment that simply fails is still reported`() = runTest(dispatcher) {
        // The second control, and the one the fix must not swallow: `authenticate` succeeds, then
        // `enableBiometricUnlock` returns false, which `runOperation` reads as a null result
        // message and maps to the generic failure. That is a real failure and it arrives by a
        // different route from any BiometricAuthException.
        val f = fixture(errorCode = null)

        f.viewModel.enableBiometric(f.activity)
        f.awaitOperation()

        assertEquals(SettingsMessage.Simple(R.string.settings_error_generic), f.viewModel.state.value.error)
    }
}

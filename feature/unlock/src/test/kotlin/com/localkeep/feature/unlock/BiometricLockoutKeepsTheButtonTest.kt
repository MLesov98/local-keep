package com.localkeep.feature.unlock

import androidx.biometric.BiometricPrompt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A transient biometric lockout must not remove `Unlock with biometrics` for the life of the
 * process.
 *
 * Four rejected fingerprints put the framework into its own 30-second cool-off. For that window
 * `KeystoreManager.isStrongBiometricAvailable()` answers false — so the failure handler, which
 * recomputes `biometricAvailable` from it, removed the button. Nothing recomputed it afterwards:
 * the button was still gone through HOME and back, and only a force-stop brought it back, on a
 * block the framework had already released (device, 2026-09-20). The enrolment was never touched.
 *
 * Two halves, both asserted here. The classification is a free function and is tested directly.
 * The state decision lives inside a coroutine that needs an Activity, a Keystore and a real prompt,
 * and `:feature:unlock` has no mocking library on its unit-test classpath — so it is asserted on
 * the source, the same idiom `PanicLockNoticeTest` uses and for the same reason.
 */
class BiometricLockoutKeepsTheButtonTest {

    private val viewModel: String =
        File("src/main/kotlin/com/localkeep/feature/unlock/UnlockViewModel.kt").readText()

    /** The `BiometricAuthException` handler alone, up to the catch-all that follows it. */
    private val handler: String =
        viewModel.substringAfter("} catch (e: BiometricAuthException) {")
            .substringBefore("} catch (_: Exception) {")

    @Test
    fun `the slice is bounded`() {
        assertTrue(
            handler.isNotEmpty() && handler.length < viewModel.length,
            "the handler slice is unbounded, so the assertions below are measuring the whole " +
                "file and cannot fail; length=${handler.length} of ${viewModel.length}",
        )
        assertTrue(
            "biometricErrorIsUserChoice" in handler,
            "the slice does not contain the handler it is named for",
        )
    }

    @Test
    fun `a cool-off is transient and a permanent lockout is not`() {
        assertTrue(biometricErrorIsTransientLockout(BiometricPrompt.ERROR_LOCKOUT))
        assertFalse(
            biometricErrorIsTransientLockout(BiometricPrompt.ERROR_LOCKOUT_PERMANENT),
            "a permanent lockout holds until the device credential is entered, so re-offering " +
                "the button would be an offer the app cannot keep",
        )
        assertFalse(biometricErrorIsTransientLockout(BiometricPrompt.ERROR_HW_UNAVAILABLE))
        assertFalse(biometricErrorIsTransientLockout(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
    }

    @Test
    fun `the handler keeps the button while the sensor is cooling off`() {
        assertTrue(
            "biometricAvailable = if (cooling) it.biometricAvailable else biometricAvailable()"
                in handler,
            "the handler recomputes biometricAvailable unconditionally: during the framework's " +
                "cool-off that answers false, so the button is removed and nothing puts it back. " +
                "Handler: $handler",
        )
    }

    @Test
    fun `a cool-off is named rather than reported as a failed sensor`() {
        assertTrue(
            "R.string.unlock_error_biometric_lockout" in handler,
            "a cool-off still reports the generic \"Biometric unlock was not completed\", which " +
                "invites the immediate retry that is the one thing that cannot work. " +
                "Handler: $handler",
        )
    }

    @Test
    fun `the screen re-reads availability on resume`() {
        val screen = File("src/main/kotlin/com/localkeep/feature/unlock/UnlockScreen.kt").readText()
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            "viewModel.refreshBiometricAvailability()" in screen,
            "nothing re-reads the biometric flags after the screen has been composed, so a " +
                "lockout that expires — or a fingerprint enrolled in device settings — while " +
                "this screen is in the background is never noticed",
        )
    }
}

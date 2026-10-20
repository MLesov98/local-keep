package com.localkeep.feature.unlock

import androidx.biometric.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Taking the biometric prompt's own way out is not a failure.
 *
 * The prompt's negative button on this screen reads "Use master password", and dismissing the
 * prompt says the same thing another way. Both used to land in `unlockWithBiometric`'s catch-all
 * and paint the coral `unlock_error_biometric_incomplete` banner — the same treatment a lockout or
 * a hardware fault gets — over a deliberate, correct choice the app had just offered.
 *
 * `LkColors.kt` states the test for coral: "is the user's data less safe than they believe". A user
 * who chose the password field, which is already on screen behind the prompt, is not.
 *
 * The distinguishing code was already being carried the whole time: `BiometricUnlock`'s
 * `onAuthenticationError` puts `errorCode` on `BiometricAuthException`, and nothing read it.
 * Found by the F02 UX persona, 2026-08-29.
 */
class BiometricErrorIsUserChoiceTest {

    @Test
    fun `the negative button is a choice, not a failure`() {
        assertTrue(biometricErrorIsUserChoice(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
    }

    @Test
    fun `dismissing the prompt is a choice, not a failure`() {
        assertTrue(biometricErrorIsUserChoice(BiometricPrompt.ERROR_USER_CANCELED))
    }

    /**
     * The controls, and the reason this is not simply "swallow every biometric error". Each of
     * these really did fail, the user cannot just try again, and silently returning them to the
     * password field with no explanation would be the opposite defect.
     */
    @Test
    fun `a lockout still reports`() {
        assertFalse(biometricErrorIsUserChoice(BiometricPrompt.ERROR_LOCKOUT))
        assertFalse(biometricErrorIsUserChoice(BiometricPrompt.ERROR_LOCKOUT_PERMANENT))
    }

    @Test
    fun `a hardware fault still reports`() {
        assertFalse(biometricErrorIsUserChoice(BiometricPrompt.ERROR_HW_NOT_PRESENT))
        assertFalse(biometricErrorIsUserChoice(BiometricPrompt.ERROR_HW_UNAVAILABLE))
        assertFalse(biometricErrorIsUserChoice(BiometricPrompt.ERROR_NO_BIOMETRICS))
    }

    /**
     * `BiometricUnlock` raises code -1 itself when a successful authentication somehow carries no
     * cipher. That is a real failure with no user behind it, so it must keep reporting.
     */
    @Test
    fun `a result with no cipher still reports`() {
        assertFalse(biometricErrorIsUserChoice(-1))
    }
}

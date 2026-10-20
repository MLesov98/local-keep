package com.localkeep.feature.onboarding

import androidx.biometric.BiometricPrompt
import com.localkeep.core.crypto.BiometricAuthException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Saying no to the enrolment prompt is not a failure to enrol.
 *
 * The attempt used to be folded to a boolean, so the prompt's negative button, dismissing it, a
 * lockout and a hardware fault all produced the same coral "Biometric unlock wasn't enabled" error.
 * The first two are the user answering the question the step asks; only the others are errors.
 */
class BiometricEnrolDeclinedTest {

    private fun failure(code: Int) =
        Result.failure<Boolean>(BiometricAuthException("prompt error $code", code))

    @Test
    fun `the negative button is declined, not failed`() {
        assertEquals(
            BiometricEnrolOutcome.DECLINED,
            biometricEnrolOutcome(failure(BiometricPrompt.ERROR_NEGATIVE_BUTTON)),
        )
    }

    @Test
    fun `dismissing the prompt is declined, not failed`() {
        assertEquals(
            BiometricEnrolOutcome.DECLINED,
            biometricEnrolOutcome(failure(BiometricPrompt.ERROR_USER_CANCELED)),
        )
    }

    /** Controls: real failures still report, and success still succeeds. */
    @Test
    fun `a lockout or hardware fault still fails`() {
        assertEquals(BiometricEnrolOutcome.FAILED, biometricEnrolOutcome(failure(BiometricPrompt.ERROR_LOCKOUT)))
        assertEquals(BiometricEnrolOutcome.FAILED, biometricEnrolOutcome(failure(BiometricPrompt.ERROR_HW_UNAVAILABLE)))
        assertEquals(BiometricEnrolOutcome.FAILED, biometricEnrolOutcome(failure(-1)))
    }

    @Test
    fun `an unrelated exception still fails`() {
        assertEquals(
            BiometricEnrolOutcome.FAILED,
            biometricEnrolOutcome(Result.failure(IllegalStateException("keystore"))),
        )
    }

    @Test
    fun `enabling still succeeds and a refused enable still fails`() {
        assertEquals(BiometricEnrolOutcome.ENABLED, biometricEnrolOutcome(Result.success(true)))
        assertEquals(BiometricEnrolOutcome.FAILED, biometricEnrolOutcome(Result.success(false)))
    }
}

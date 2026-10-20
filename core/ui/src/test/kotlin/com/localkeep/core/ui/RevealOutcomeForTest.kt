package com.localkeep.core.ui

import androidx.biometric.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A sensor that went away is not a user who changed their mind.
 *
 * `ERROR_CANCELED` shared an arm with the two codes a user actually produces, so the file viewer —
 * the one reveal surface that distinguishes them — told the user they had cancelled when the
 * framework had cancelled underneath them. androidx documents the code as the sensor being
 * unavailable, and delivers a real user exit as `ERROR_USER_CANCELED` or `ERROR_NEGATIVE_BUTTON`.
 *
 * Found 2026-08-30 and kept as `PARTIAL` on verification: the classification is wrong,
 * the security consequence the finding claimed is not — every branch still refuses the reveal.
 */
class RevealOutcomeForTest {

    @Test
    fun `a framework cancellation is an error, not a user choice`() {
        assertEquals(
            RevealAuth.RevealOutcome.Error(BiometricPrompt.ERROR_CANCELED),
            revealOutcomeFor(BiometricPrompt.ERROR_CANCELED),
        )
    }

    @Test
    fun `the two codes a user really produces stay user choices`() {
        assertEquals(
            RevealAuth.RevealOutcome.UserCancelled,
            revealOutcomeFor(BiometricPrompt.ERROR_USER_CANCELED),
        )
        assertEquals(
            RevealAuth.RevealOutcome.UserCancelled,
            revealOutcomeFor(BiometricPrompt.ERROR_NEGATIVE_BUTTON),
        )
    }

    /** The controls: the other arms must keep their meaning, or this became a different change. */
    @Test
    fun `missing hardware is unavailable`() {
        assertEquals(RevealAuth.RevealOutcome.Unavailable, revealOutcomeFor(BiometricPrompt.ERROR_NO_BIOMETRICS))
        assertEquals(RevealAuth.RevealOutcome.Unavailable, revealOutcomeFor(BiometricPrompt.ERROR_HW_NOT_PRESENT))
        assertEquals(RevealAuth.RevealOutcome.Unavailable, revealOutcomeFor(BiometricPrompt.ERROR_HW_UNAVAILABLE))
    }

    @Test
    fun `a lockout is denied`() {
        assertEquals(RevealAuth.RevealOutcome.Denied, revealOutcomeFor(BiometricPrompt.ERROR_LOCKOUT))
        assertEquals(RevealAuth.RevealOutcome.Denied, revealOutcomeFor(BiometricPrompt.ERROR_LOCKOUT_PERMANENT))
    }
}

package com.localkeep.credential

import androidx.biometric.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * "Use master password" on the locked-vault prompt has to lead to the master password.
 *
 * Device evidence, 2026-10-04 on emulator-5562 (API 36, foss release, finger enrolled): with the
 * vault locked, a passkey sign-in or create raised "Unlock LocalKeep / Verify to use a saved
 * credential" with a "Use master password" button. Pressing it ended the request —
 * `GetCredentialException.TYPE_UNKNOWN / user verification failed` (create: the
 * `CreateCredentialException` twin) — and the sign-in sheet stayed blank. The in-flow
 * master-password dialog (2C-F14) was reachable only when the biometric could not run at all.
 *
 * The cause: the negative button arrives as a `BiometricAuthException` with
 * [BiometricPrompt.ERROR_NEGATIVE_BUTTON], and `unlockWithBiometric` swept every exception into
 * [Verification.FAILED], which [openLockedVault] reports rather than falls back from. A button
 * that says "Use master password" and fails the request instead is a dead end for every user who
 * cannot or will not use a fingerprint just then.
 */
class LockedCeremonyPasswordRouteTest {

    private var passwordAsked = 0

    private fun open(biometric: Verification): Verification = runBlocking {
        openLockedVault(
            biometric = { biometric },
            password = {
                passwordAsked++
                Verification.OK
            },
        )
    }

    @Test
    fun `the negative button asks for the master password`() {
        val refusal = biometricRefusal(BiometricPrompt.ERROR_NEGATIVE_BUTTON, passwordOffered = true)

        assertEquals(Verification.OK, open(refusal), "the password unlock was not reached")
        assertEquals(1, passwordAsked)
    }

    /**
     * Control, green before and after: a biometric that cannot run at all still reaches the
     * password, which is what 2C-F14 built.
     */
    @Test
    fun `an unavailable biometric still falls back to the password`() {
        assertEquals(Verification.OK, open(Verification.UNAVAILABLE))
        assertEquals(1, passwordAsked)
    }

    /**
     * Control: dismissing the prompt is "not now", not "the other way". Raising a password dialog
     * after it would turn a refusal into a second demand — the reason 2C-F14 fell back from
     * UNAVAILABLE alone.
     */
    @Test
    fun `dismissing the prompt does not raise the password dialog`() {
        val dismissed = biometricRefusal(BiometricPrompt.ERROR_USER_CANCELED, passwordOffered = true)

        open(dismissed)
        assertEquals(0, passwordAsked, "a dismissed prompt raised the password dialog")
    }

    /** Control: a lockout or a hardware error is still a failed verification, not a password route. */
    @Test
    fun `other prompt errors are not a route to the password`() {
        listOf(
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_TIMEOUT,
        ).forEach { code ->
            assertEquals(
                Verification.FAILED,
                open(biometricRefusal(code, passwordOffered = true)),
                "error $code",
            )
        }
        assertEquals(0, passwordAsked)
    }
}

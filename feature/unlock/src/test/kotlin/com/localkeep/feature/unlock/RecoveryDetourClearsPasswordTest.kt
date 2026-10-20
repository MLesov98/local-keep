package com.localkeep.feature.unlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Walking off to the Recovery Kit must not leave the master password sitting in the field.
 *
 * The Recovery-Kit pane replaces the password field in place, so a half-typed master password
 * stayed in [UnlockUiState] behind it — and was still in the well, dots and character count intact,
 * after the round trip back through "Back to password". Observed on emulator-5554, 2026-09-15:
 * `[197,1202][787,1328] text='••••••••'` beside `text='8'` on return (`shots/014-back-to-password.png`).
 *
 * The detour is the problem, not the dots. Someone who reaches for the Recovery Kit is going to
 * find a piece of paper, which means leaving the phone — and a secret typed into a screen the user
 * has walked away from is a secret they did not leave there deliberately.
 *
 * [hideRecovery] has always cleared the 24 words on the way back for the same reason; this is the
 * other direction, which nothing covered.
 */
class RecoveryDetourClearsPasswordTest {

    @Test
    fun `switching to the Recovery Kit clears the typed master password`() {
        val typed = UnlockUiState(password = "correct horse battery staple")

        val after = typed.showingRecovery()

        assertEquals("", after.password, "the master password must not survive the detour")
        assertTrue(after.recoveryVisible)
    }

    /**
     * The control. The switch must still do the thing it was written for — open the pane and drop a
     * stale error — so a change that simply stopped calling it would fail here rather than pass by
     * removing the feature.
     */
    @Test
    fun `switching to the Recovery Kit opens the pane and clears a stale error`() {
        val failed = UnlockUiState(
            password = "wrong",
            error = UnlockError.Simple(R.string.unlock_error_wrong_password),
        )

        val after = failed.showingRecovery()

        assertTrue(after.recoveryVisible)
        assertEquals(null, after.error)
    }

    /** The second control: nothing else on the state is disturbed on the way through. */
    @Test
    fun `switching to the Recovery Kit leaves the rest of the state alone`() {
        val state = UnlockUiState(
            password = "typed",
            biometricAvailable = true,
            recoveryWords = "eight pool ocean",
        )

        val after = state.showingRecovery()

        assertTrue(after.biometricAvailable)
        assertEquals("eight pool ocean", after.recoveryWords)
        assertFalse(after.unlocked)
    }
}

package com.localkeep.feature.unlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The unlock screen's own state prints nothing, for the same reason `ItemEditUiState` does not.
 *
 * `UnlockUiState` holds the typed master password (`password`) and the full 24-word Recovery Kit
 * phrase (`recoveryWords`) as plain `String` fields. It is the same latent-leak shape V27
 * (2026-09-12) fixed for `ItemEditUiState`/`ImportUiState` — this class was simply missed by that
 * pass and picked up on 2026-09-13.
 *
 * Nothing interpolates it today. This test is what makes that still true later.
 */
class UnlockUiStateRedactionTest {

    private val password = "correct-horse-battery-staple"
    private val recoveryWords =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private val state = UnlockUiState(
        password = password,
        recoveryWords = recoveryWords,
    )

    @Test
    fun `the unlock state prints neither secret it holds`() {
        val printed = state.toString()
        assertFalse(printed.contains(password), "UnlockUiState.toString() leaked the password — it must stay redacted")
        assertFalse(
            printed.contains(recoveryWords),
            "UnlockUiState.toString() leaked the Recovery Kit phrase — it must stay redacted",
        )
    }

    @Test
    fun `the unlock state prints a stable redacted marker`() {
        assertEquals("UnlockUiState(redacted)", state.toString())
    }

    /**
     * Control: a state built with only a non-secret field set must produce the identical redacted
     * string, proving the override redacts the whole class rather than being accidentally
     * field-selective (e.g. checking `password.isNotEmpty()` and falling through to the default
     * toString otherwise).
     */
    @Test
    fun `a state with no secrets set still prints the same redacted marker`() {
        val nonSecretState = UnlockUiState(biometricAvailable = true)
        assertEquals("UnlockUiState(redacted)", nonSecretState.toString())
    }
}

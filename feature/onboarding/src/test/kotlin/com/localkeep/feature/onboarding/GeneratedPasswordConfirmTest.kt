package com.localkeep.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Confirm is not asked for when the master password was generated (2026-07-29).
 *
 * Confirmation catches a typo in something typed from memory. A suggested passphrase is neither
 * typed nor memorised — it is on screen and revealed — and `suggestPassphrase` had always filled
 * both fields anyway, so the second field was never a check. It was a full, deliberately
 * unrevealable box asking the user to confirm a value they had not entered.
 *
 * These exercise [OnboardingUiState]'s derived properties rather than the composable, because the
 * button and `createVault` both read them and the point is that the two cannot disagree.
 */
class GeneratedPasswordConfirmTest {

    private val strong = "correct horse battery staple mountain"

    private fun typed(password: String, confirmation: String) = OnboardingUiState(
        password = password,
        confirmation = confirmation,
        strengthScore = 4,
    )

    private fun generated(password: String) = OnboardingUiState(
        password = password,
        confirmation = password,
        passwordWasGenerated = true,
        strengthScore = 4,
    )

    @Test
    fun `a generated passphrase does not ask for confirmation`() {
        val state = generated(strong)
        assertFalse(state.confirmationRequired)
        assertTrue(state.readyToCreate, "Continue must be live with no Confirm field on screen")
    }

    @Test
    fun `a hand-typed password still requires a matching confirmation`() {
        val state = typed(strong, "")
        assertTrue(state.confirmationRequired)
        assertFalse(state.readyToCreate, "an empty Confirm must still hold Continue back")
    }

    @Test
    fun `a hand-typed mismatch is refused`() {
        assertFalse(typed(strong, "$strong-x").readyToCreate)
        assertFalse(typed(strong, "$strong-x").confirmationMatches)
    }

    @Test
    fun `a hand-typed match is accepted`() {
        assertTrue(typed(strong, strong).readyToCreate)
    }

    @Test
    fun `an empty confirmation is not yet a mismatch`() {
        // The error line appears once there is something to disagree with, not the moment the
        // field takes focus.
        assertTrue(typed(strong, "").confirmationMatches)
    }

    @Test
    fun `a generated passphrase still fills confirmation underneath`() {
        // Hidden, not unset. createVault re-checks `password != confirmation` as its own last
        // guard, and that guard must not start failing just because the field left the screen.
        val state = generated(strong)
        assertEquals(state.password, state.confirmation)
    }

    @Test
    fun `weak still needs acknowledging even when generated`() {
        // The generated flag speaks to confirmation only. It is not a way around the weak-password
        // acknowledgement, which is a different question about a different risk.
        val weak = OnboardingUiState(
            password = "abc",
            confirmation = "abc",
            passwordWasGenerated = true,
            strengthScore = 0,
        )
        assertTrue(weak.isWeakPassword)
        assertFalse(weak.readyToCreate)
        assertTrue(weak.copy(weakAccepted = true).readyToCreate)
    }

    @Test
    fun `an empty form is never ready`() {
        assertFalse(OnboardingUiState().readyToCreate)
        assertFalse(OnboardingUiState(passwordWasGenerated = true).readyToCreate)
    }

    @Test
    fun `work in flight holds the button regardless of the rest`() {
        assertFalse(generated(strong).copy(isWorking = true).readyToCreate)
    }

    // ---------------------------------------------------------------------------------------
    // The stale-confirmation trap. Found 2026-07-29 while implementing the above.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `editing a suggested passphrase clears the confirmation it silently filled`() {
        // Without this: Suggest fills both fields, the user deletes one character from the
        // password, and Confirm still holds the whole old passphrase. The screen then reports
        // "These don't match yet." about a value the user never typed and cannot reveal — the
        // Confirm field is deliberately non-revealable — with nothing on screen saying it is full.
        val edited = generated(strong).withPasswordEntered(strong.dropLast(1))

        assertEquals("", edited.confirmation, "the stale suggestion must not survive the edit")
        assertFalse(edited.passwordWasGenerated, "an edited suggestion is the user's own password")
        assertTrue(edited.confirmationRequired, "Confirm must come back once they have typed")
        assertTrue(edited.confirmationMatches, "an empty Confirm is not a mismatch to report")
    }

    @Test
    fun `editing a hand-typed password leaves its confirmation alone`() {
        // The clear above is scoped to the generated case. Someone who filled both fields by hand
        // and then fixes a typo in the first must not silently lose the second.
        val edited = typed(strong, strong).withPasswordEntered("${strong}x")

        assertEquals(strong, edited.confirmation)
        assertFalse(edited.confirmationMatches, "this one is a real mismatch and must be reported")
    }

    @Test
    fun `typing over a suggestion is never briefly ready to create`() {
        // The gate must not open in the gap between clearing the flag and the user reaching
        // Confirm — that would let a half-edited passphrase through on an accidental tap.
        assertFalse(generated(strong).withPasswordEntered("$strong ").readyToCreate)
    }

    @Test
    fun `typing over a suggestion takes down the line that told you to write it down`() {
        // QA 2026-10-03: "Write this down before continuing — it is your master password" stayed
        // under a password the user had typed over the suggestion with their own.
        val suggested = generated(strong).copy(notice = R.string.onboarding_password_notice_suggested)

        assertEquals(null, suggested.withPasswordEntered("my own words entirely").notice)
    }
}

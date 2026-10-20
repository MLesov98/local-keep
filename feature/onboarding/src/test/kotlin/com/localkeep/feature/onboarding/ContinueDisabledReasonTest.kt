package com.localkeep.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A dimmed Continue has to say why, out loud.
 *
 * The Recovery Kit step has done this since it was written, and its own comment gives the argument:
 * "Sighted users see a dimmed button next to the row that un-dims it. A screen reader gets neither
 * adjacency nor dimming, so the reason is said out loud." The master-password step had the
 * identical shape — a button dimmed by state explained only by text elsewhere on screen — and none
 * of the treatment, so TalkBack announced "Continue, dimmed" and nothing about what to fix.
 *
 * Found by the F01 QA persona, 2026-08-29, which noticed the codebase already held the precedent
 * one step later in the same file.
 *
 * Asserted on the state rather than the composable for the reason `readyToCreate` is derived there:
 * the button and `createVault` must not be able to disagree, and this is the form a JVM test can
 * reach.
 */
class ContinueDisabledReasonTest {

    private val base = OnboardingUiState()

    @Test
    fun `an empty form says a password is needed`() {
        assertEquals(
            R.string.onboarding_password_continue_disabled_empty,
            base.continueDisabledReason,
        )
    }

    @Test
    fun `a typed password with no confirmation says so`() {
        val state = base.copy(password = "TestVaultAbc99", strengthScore = 4)
        assertEquals(
            R.string.onboarding_password_continue_disabled_confirm,
            state.continueDisabledReason,
        )
    }

    @Test
    fun `a mismatch says the passwords differ`() {
        val state = base.copy(
            password = "TestVaultAbc99",
            confirmation = "TestVaultAbc98",
            strengthScore = 4,
        )
        assertEquals(
            R.string.onboarding_password_continue_disabled_mismatch,
            state.continueDisabledReason,
        )
    }

    /**
     * The weak-password consent gate. Distinct from the others because the password is complete and
     * matching — nothing is missing, there is a decision outstanding — and a reason that said
     * "enter a password" here would be actively wrong.
     */
    @Test
    fun `an unacknowledged weak password says a decision is outstanding`() {
        val state = base.copy(
            // Weak by length: `isWeakPassword` is derived, so it is made true the way the app
            // makes it true — five characters is under ADVISED_LENGTH (12).
            password = "test1",
            confirmation = "test1",
            strengthScore = 4,
            weakAccepted = false,
        )
        assertEquals(
            R.string.onboarding_password_continue_disabled_weak,
            state.continueDisabledReason,
        )
    }

    /**
     * The control that makes the rest meaningful: a ready form must announce plain "Continue", not
     * a reason. Without it a fix that always returned some string would pass every test above.
     */
    @Test
    fun `a ready form has no reason to give`() {
        val state = base.copy(
            password = "TestVaultAbc99",
            confirmation = "TestVaultAbc99",
            strengthScore = 4,
        )
        assertEquals(true, state.readyToCreate, "the fixture is not actually ready")
        assertNull(state.continueDisabledReason)
    }

    /**
     * Working is deliberately silent: the button is dimmed because the vault is being created, and
     * the deriving overlay already says that at length. A reason here would talk over it.
     */
    @Test
    fun `creating the vault gives no spoken reason`() {
        val state = base.copy(
            password = "TestVaultAbc99",
            confirmation = "TestVaultAbc99",
            strengthScore = 4,
            isWorking = true,
        )
        assertEquals(false, state.readyToCreate)
        assertNull(state.continueDisabledReason)
    }
}

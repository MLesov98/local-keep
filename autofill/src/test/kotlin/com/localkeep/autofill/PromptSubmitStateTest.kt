package com.localkeep.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MasterPasswordPrompt] shows no pending state during `VaultLockManager.unlock`'s multi-second
 * Argon2id derivation — the button just goes dark, unlike the main unlock screen's
 * `LkDerivingOverlay`. This module can't drive that `View` in a JVM unit test (no Robolectric
 * dependency here — see [AuthPromptMessageTest]), so [PromptSubmitState] is the seam: the pending
 * transition itself, extracted as plain state, is what these assert on.
 */
class PromptSubmitStateTest {

    @Test
    fun idleByDefault() {
        val state = PromptSubmitState()

        assertFalse(state.isPending)
        assertEquals(R.string.autofill_password_prompt_continue, state.labelRes)
    }

    @Test
    fun submittingEntersThePendingStateAndShowsTheUnlockingLabel() {
        val state = PromptSubmitState()

        state.beginSubmit()

        assertTrue(state.isPending)
        assertEquals(R.string.autofill_password_prompt_unlocking, state.labelRes)
    }

    @Test
    fun aFailedAttemptResetsBackToIdle() {
        val state = PromptSubmitState()
        state.beginSubmit()

        state.reset()

        assertFalse(state.isPending)
        assertEquals(R.string.autofill_password_prompt_continue, state.labelRes)
    }
}

package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Password-history entries are re-auth gated on every item, not only on reprompt items.
 *
 * Deliberately stricter than the live password on the same screen. A password the user has stopped
 * using is very often one they still use somewhere else, and there is no routine reason to look at
 * one — nobody signs in with a former password. A secret with no everyday use loses nothing by
 * asking every time, so the gate does not depend on a per-item flag the user may never have set.
 */
class PasswordHistoryGateTest {

    @Test
    fun `a history entry is gated on an ordinary item`() {
        val state = ItemEditUiState(reprompt = false)
        assertTrue(state.requiresReauthFor(passwordHistoryRevealKey(0)))
        // The point of the assertion pair: the live password on that same item is NOT gated, so
        // this is a real difference in treatment rather than the reprompt flag leaking in.
        assertFalse(state.requiresReauthFor("password"))
    }

    @Test
    fun `every entry has its own key, so revealing one never reveals the next`() {
        val revealed = ItemEditUiState(revealedFields = setOf(passwordHistoryRevealKey(0)))
        assertTrue(passwordHistoryRevealKey(0) in revealed.revealedFields)
        assertFalse(passwordHistoryRevealKey(1) in revealed.revealedFields)
    }

    @Test
    fun `the history key is not the live password's key`() {
        // They render as adjacent rows of bullets. Sharing a key would mean tapping Show on one
        // silently exposed the other.
        assertFalse(passwordHistoryRevealKey(0) == "password")
    }
}

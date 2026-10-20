package com.localkeep.feature.itemedit

import com.localkeep.core.model.COORDINATE_FIELD
import com.localkeep.core.model.PHRASE_FIELD
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A government ID number is re-auth gated on every identity item, not only on a reprompt one.
 *
 * These three were gated by the item's `reprompt` flag alone — the tier a card *number* sits in —
 * so on an identity item with reprompt off they revealed and copied on a single tap. The test this
 * set has always applied is whether the value is still dangerous after it leaks: a card can be
 * cancelled in an afternoon and a password retyped, while an SSN, a passport number and a licence
 * number cannot be reissued to undo anything. That is the same argument that put a saved position
 * in the set.
 */
class IdentityRevealGateTest {

    private val notReprompted = ItemEditUiState(reprompt = false)

    @Test
    fun `the identity document numbers are gated with reprompt off`() {
        listOf("ssn", "passport", "license").forEach { field ->
            assertTrue(notReprompted.requiresReauthFor(field), "$field revealed without re-auth")
        }
    }

    /** The tier below, unchanged: these follow the item's own flag, as the spec's §5.2 has it. */
    @Test
    fun `the fields that were always reprompt-gated still are`() {
        listOf("cardNumber", "identityEmail", "identityPhone").forEach { field ->
            assertFalse(notReprompted.requiresReauthFor(field), "$field became always-gated")
            assertTrue(notReprompted.copy(reprompt = true).requiresReauthFor(field))
        }
    }

    /** The company this set already kept — a control, so a wholesale change to it fails here. */
    @Test
    fun `the always-gated set still holds the secrets it was written for`() {
        listOf("totpSecret", "cardCvv", "cardPin", COORDINATE_FIELD, PHRASE_FIELD).forEach { field ->
            assertTrue(notReprompted.requiresReauthFor(field), "$field lost its gate")
        }
    }
}

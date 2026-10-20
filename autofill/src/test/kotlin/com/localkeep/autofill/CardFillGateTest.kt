package com.localkeep.autofill

import com.localkeep.core.model.ALWAYS_REAUTH_FIELDS
import com.localkeep.core.model.CARD_CVV_FIELD
import com.localkeep.core.model.CARD_PIN_FIELD
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * The precondition that made filling cards safe to build at all.
 *
 * A card can now be offered back through autofill, which it could not before: the vault captured
 * one at checkout and no fill surface could ever return it. The reason that gap was left open
 * rather than patched shut is written down as a follow-up — the fill path releases a CVV into
 * *another app's* form, and the set naming which secrets always demand re-authentication was
 * `internal` to `:feature:itemedit`, invisible from here. So the service could only gate on the
 * per-item `reprompt` flag, which the user can turn off and which defaults to false.
 *
 * This test exists to fail if that regresses in either of the two ways it can. If the constant
 * stops resolving from this module, the move has been undone and the compile breaks here — which
 * is the intended alarm, not a nuisance. If a card's CVV or PIN ever leaves the always-reauth set,
 * the assertions below fail and `buildCardAuthDataset`'s unconditional authentication stops being
 * justified by anything.
 *
 * The other half of the guarantee is structural rather than assertable here: `buildCardAuthDataset`
 * takes a non-null `PendingIntent` and calls `setAuthentication` on every row it builds, so there
 * is no unauthenticated card dataset to construct. A test cannot prove the absence of a code path;
 * the signature can.
 */
class CardFillGateTest {

    @Test
    fun `a card's security code and PIN always demand re-authentication`() {
        assertTrue(
            "a CVV filled inline into a foreign app's checkout is a worse exposure than an in-app " +
                "reveal, not a lesser one — if this is ever false, card rows must stop being offered",
            CARD_CVV_FIELD in ALWAYS_REAUTH_FIELDS,
        )
        assertTrue(CARD_PIN_FIELD in ALWAYS_REAUTH_FIELDS)
    }

    @Test
    fun `the always-reauth set is reachable from the autofill module`() {
        // The point of the move. Before it, this module could not name these fields at all, and
        // the reference above would not compile.
        assertTrue(ALWAYS_REAUTH_FIELDS.isNotEmpty())
        assertTrue("totpSecret" in ALWAYS_REAUTH_FIELDS)
    }
}

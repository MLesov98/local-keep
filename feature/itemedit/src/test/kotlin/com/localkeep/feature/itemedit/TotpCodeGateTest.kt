package com.localkeep.feature.itemedit

import com.localkeep.core.model.ALWAYS_REAUTH_FIELDS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which items gate the *live* TOTP code, and which do not.
 *
 * The detail card and the editor's preview both used to call `ClipboardHelper.copy` directly with
 * `state.totpCurrentCode`, so on an item the user had marked "reprompt" the current second factor
 * rendered in the clear on open and reached the clipboard on one tap — while `VaultItemRow` refused
 * inline copy and the autofill dataset withheld the same value, for the same item, because of the
 * same flag.
 *
 * The pair of assertions matters more than either one alone. Gating the code on *every* item is the
 * wrong fix, not a safer one: §5.2 deliberately leaves the one-shot code out of
 * [ALWAYS_REAUTH_FIELDS] because a fingerprint every thirty seconds is a second factor nobody keeps
 * using, and the seed beside it is what clones the authenticator.
 */
class TotpCodeGateTest {

    @Test
    fun `a reprompt item gates its live two-factor code`() {
        assertTrue(ItemEditUiState(reprompt = true).requiresReauthFor(TOTP_CODE_FIELD))
    }

    @Test
    fun `an ordinary item does not gate the live code — the seed beside it still does`() {
        val state = ItemEditUiState(reprompt = false)
        assertFalse(state.requiresReauthFor(TOTP_CODE_FIELD))
        assertTrue(state.requiresReauthFor("totpSecret"))
    }

    @Test
    fun `the code is not an always-reauth field`() {
        assertFalse(TOTP_CODE_FIELD in ALWAYS_REAUTH_FIELDS)
    }

    @Test
    fun `the gate key is distinct from the seed's, so one reveal never grants the other`() {
        assertFalse(TOTP_CODE_FIELD == "totpSecret")
        val seedRevealed = ItemEditUiState(reprompt = true, revealedFields = setOf("totpSecret"))
        assertFalse(TOTP_CODE_FIELD in seedRevealed.revealedFields)
    }
}

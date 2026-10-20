package com.localkeep.feature.itemedit

import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A custom field the user marked Hidden is re-auth gated on every item (2026-09-20, sweep 2B-1).
 *
 * The bug this closes: `requiresReauthFor` was `field in ALWAYS_REAUTH_FIELDS ||
 * field.startsWith(PASSWORD_HISTORY_FIELD_PREFIX) || reprompt`, and a custom field's key is
 * `custom:N` — in no fixed set. So on an ordinary imported login with reprompt off, the fields
 * called "TOTP seed" and "Security answer" revealed on one tap and copied on the next, the copy
 * control not even labelled as gated, while UI/UX spec §5.2 lists "notes/fields the user marked
 * secret" beside the CVV and the TOTP seed.
 *
 * Tested on the pure state function rather than through the ViewModel for the reason
 * `ItemEditViewModelTest` gives: this module's `ItemEditViewModel` cannot be built on a JVM. Both
 * the reveal path (`toggleCustomFieldRevealed`) and the copy path (`requestCopy`) ask this one
 * question, and the two screens derive their "unlocks first" labels from it.
 */
class HiddenCustomFieldIsAlwaysGatedTest {

    private val noReprompt = ItemEditUiState(
        reprompt = false,
        customFields = listOf(
            CustomField(name = "Security answer", value = "Fido", type = CustomFieldType.TEXT),
            CustomField(name = "TOTP seed", value = "JBSWY3DP", type = CustomFieldType.HIDDEN),
        ),
    )

    @Test
    fun `a hidden custom field is gated even when the item does not reprompt`() {
        assertTrue(noReprompt.requiresReauthFor(customFieldRevealKey(1)))
    }

    @Test
    fun `a plain text custom field is not gated`() {
        // The marking is the request. A field the user left as Text is not a secret, and gating it
        // is how a gate teaches people to dismiss gates.
        assertFalse(noReprompt.requiresReauthFor(customFieldRevealKey(0)))
    }

    @Test
    fun `a text custom field on a reprompt item is still gated by the flag`() {
        assertTrue(noReprompt.copy(reprompt = true).requiresReauthFor(customFieldRevealKey(0)))
    }

    @Test
    fun `a custom-field key past the end of the list is not gated by this rule`() {
        // An index no field answers to must not become a gate by accident — and must not throw.
        assertFalse(noReprompt.requiresReauthFor(customFieldRevealKey(7)))
    }

    @Test
    fun `nothing else changed meaning`() {
        assertTrue(noReprompt.requiresReauthFor("cardCvv"))
        assertTrue(noReprompt.requiresReauthFor("${PASSWORD_HISTORY_FIELD_PREFIX}0"))
        assertFalse(noReprompt.requiresReauthFor("password"))
    }
}

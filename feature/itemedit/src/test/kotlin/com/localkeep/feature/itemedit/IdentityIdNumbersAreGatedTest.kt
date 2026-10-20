package com.localkeep.feature.itemedit

import com.localkeep.core.model.ALWAYS_REAUTH_FIELDS
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The three government-ID numbers are masked and gated on both screens.
 *
 * This is the guard for F-23 (2026-09-15), which read the editor's identity form off a `uiautomator`
 * dump of three *empty* wells and concluded they had "no mask or gated copy". They have both, and
 * the finding's own screenshot shows them: `shots/078-identity-byhand.png` draws an eye and a copy
 * glyph in every one of the three wells. The dump did not surface the labels because the fields were
 * empty — an empty value's Copy is `textFaint` and a no-op, and `SecretReadField` early-returns on a
 * blank, so the detail screen renders no row for one at all.
 *
 * Nothing was changed for it. This exists so the next reader does not have to re-derive that, and so
 * a future edit that *does* drop a gate fails here rather than in a screenshot six weeks later.
 *
 * The tier is the reason it matters: these are in [ALWAYS_REAUTH_FIELDS] beside the CVV and the
 * saved position, not merely reprompt-gated like a card *number*. A card can be cancelled in an
 * afternoon; a national ID cannot be reissued to undo anything.
 */
class IdentityIdNumbersAreGatedTest {

    private val ids = listOf("ssn", "passport", "license")

    @Test
    fun `each ID number always demands re-authentication, whatever the item's reprompt flag says`() {
        val noReprompt = ItemEditUiState(reprompt = false)
        ids.forEach { field ->
            assertTrue(field in ALWAYS_REAUTH_FIELDS, "$field left the always-gated set")
            assertTrue(noReprompt.requiresReauthFor(field), "$field is gated only by reprompt again")
        }
    }

    @Test
    fun `the editor draws all three as secrets, like the card's CVV`() {
        val fields = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
            .readText()
            .substringAfter("private fun IdentityFields(", "")
            .substringBefore("private fun CustomFieldsSection(")
        ids.forEach { field ->
            assertTrue("\"$field\"" in fields, "the editor's $field well is no longer keyed for the gate")
        }
        // Three wells under GOVERNMENT IDS and all three are secret wells: that block is the one
        // that must never fall back to an unmasked input.
        val govIds = fields.substringAfter("itemedit_identity_gov_ids_heading", "")
            .substringBefore("itemedit_identity_contact_heading")
        assertTrue(govIds.split("SecretField(").size - 1 == 3, "a government-ID well is not a SecretField")
        assertTrue(
            govIds.split("Field(").size - 1 == 3,
            "the GOVERNMENT IDS block holds a well that is not one of the three secrets",
        )
    }

    @Test
    fun `the detail screen draws all three as secrets too`() {
        val detail = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt")
            .readText()
            .substringAfter("private fun IdentityDetail(", "")
            .substringBefore("private fun TotpCard(")
        ids.forEach { field ->
            assertTrue("\"$field\"" in detail, "the detail screen's $field row lost its gate key")
        }
        assertTrue(detail.split("SecretReadField(").size - 1 == 3)
    }
}

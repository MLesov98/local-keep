package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Typing an identity in by hand is a five-step flow, not the fourteen-well form.
 *
 * Nothing in it is required, so what the tests pin is the two decisions the flow makes for the user:
 * what the primary button says (Skip over an empty step, Continue over a filled one) and whether
 * Review will save at all. The rest is structure a device is needed to see, held by source scans.
 *
 * Every test names the change that turns it red.
 */
class IdentityWizardTest {

    private fun blank() = ItemEditUiState()

    private val wizard = File("src/main/kotlin/com/localkeep/feature/itemedit/IdentityWizard.kt").readText()
    private val screen = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
    private val capture = File("src/main/kotlin/com/localkeep/feature/itemedit/IdentityCapture.kt").readText()

    // ── Skip or Continue ────────────────────────────────────────────────────────────────────────

    /** Turns red if a step with nothing in it is not recognised as empty — the button would say Continue over nothing. */
    @Test
    fun `an untouched step is blank, on every step but review`() {
        for (step in 0..3) assertTrue(identityStepIsBlank(step, blank()), "step $step")
        assertFalse(identityStepIsBlank(4, blank()), "review is never blank: it asks for nothing")
    }

    @Test
    fun `each step turns to Continue on its own wells and no others`() {
        val typed = listOf(
            0 to blank().copy(identityCompany = "Acme"),
            1 to blank().copy(identityPassport = "X123"),
            2 to blank().copy(identityPhone = "+359"),
            3 to blank().copy(identityAddressCountry = "BG"),
        )
        for ((step, state) in typed) {
            assertFalse(identityStepIsBlank(step, state), "step $step with content")
            for (other in 0..3) {
                if (other != step) assertTrue(identityStepIsBlank(other, state), "step $other must stay blank")
            }
        }
    }

    /** Spaces are not a detail: a step holding only whitespace would otherwise offer Continue and store nothing. */
    @Test
    fun `whitespace alone is still blank`() {
        assertTrue(identityStepIsBlank(0, blank().copy(identityFirstName = "   ")))
        assertFalse(identityHasAnyDetail(blank().copy(identityEmail = " ")))
    }

    // ── Review refuses an empty identity ────────────────────────────────────────────────────────

    @Test
    fun `an identity with nothing typed has no detail to save`() {
        assertFalse(identityHasAnyDetail(blank()))
    }

    /** The control: one well in any group is enough. Turns red if a group falls out of the check. */
    @Test
    fun `any one well in any group is a detail`() {
        listOf(
            blank().copy(identityLastName = "Lesov"),
            blank().copy(identityLicense = "B1"),
            blank().copy(identityEmail = "a@b.c"),
            blank().copy(identityAddressZip = "4000"),
        ).forEach { assertTrue(identityHasAnyDetail(it), "$it") }
    }

    // ── Structure ───────────────────────────────────────────────────────────────────────────────

    /** Turns red if the by-hand route falls back to the flat form: the wall comes back for every new identity. */
    @Test
    fun `the by-hand route opens the wizard and never the flat form`() {
        val branch = screen.substringAfter("IdentityRoute.MANUAL -> {").substringBefore("\n            }")
        assertTrue("IdentityWizard(" in branch, "the MANUAL branch no longer renders IdentityWizard")
        assertTrue("return" in branch, "the MANUAL branch must return past the ordinary form")
        assertFalse("IdentityRoute.MANUAL -> Unit" in screen, "MANUAL falls through to the ordinary form again")
    }

    /** Turns red if the tile gets its hint back: "Fastest — nothing to type" belongs to the photo tile alone. */
    @Test
    fun `only the photo tile carries a second line`() {
        assertFalse("itemedit_identity_start_type_hint" in capture, "the by-hand tile advertises its length again")
        val tiles = capture.substringAfter("IdentityRouteTile(\n                            icon = LkIcons.Camera")
        val byHand = tiles.substringAfter("IdentityRouteTile(").substringBefore("onClick = onType,")
        assertFalse("subtitle" in byHand, "the by-hand tile passes a subtitle: $byHand")
        assertTrue("itemedit_identity_start_photo_hint" in capture, "the photo tile lost its 'Fastest' line")
    }

    /** Turns red if the step title stops being a heading: navigate-by-heading would have no stop on five screens. */
    @Test
    fun `the step title carries heading semantics and the body does not`() {
        val heading = wizard.substringAfter("private fun IdentityWizardHeading(").substringBefore("\n}\n")
        val at = heading.indexOf("modifier = Modifier.semantics { heading() }")
        assertTrue(at > 0, "no heading() on the step title")
        assertTrue(heading.indexOf("style = LkText.display", at) in at..(at + 120), "heading() is not on the display title")
        val bodyAt = heading.indexOf("LkText.bodyQuiet")
        assertTrue("heading()" !in heading.substring(heading.lastIndexOf("Text(", bodyAt), bodyAt))
    }

    /** The keyboard choreography is the card's, and the order of its two modifiers matters — see CardWizardKeyboardTest. */
    @Test
    fun `consumed insets are read before imePadding`() {
        // The root column both wizards share lives in WizardFrame; assert the identity wizard uses it.
        assertTrue("WizardFrame(" in wizard, "the identity wizard no longer draws through WizardFrame")
        val frame = File("src/main/kotlin/com/localkeep/feature/itemedit/WizardFrame.kt").readText()
        val consumed = frame.indexOf("keyboard.consumedInsetsModifier()")
        val padding = frame.indexOf(".imePadding()")
        assertTrue(consumed in 0 until padding, "consumed=$consumed imePadding=$padding")
    }

    /** Turns red if an ID number moves to a plain well: it would lose its mask and its re-auth gate. */
    @Test
    fun `the three id numbers are secret fields`() {
        val ids = wizard.substringAfter("private fun IdsStep(").substringBefore("\n}\n")
        assertEquals(3, Regex("SecretField\\(").findAll(ids).count(), "IdsStep must use SecretField three times")
        assertFalse(Regex("\\bField\\(").containsMatchIn(ids.replace("SecretField(", "")), "a plain Field in IdsStep")
    }

    /** Review names which IDs were entered and never draws them. Turns red if a value reaches a Text. */
    @Test
    fun `review never echoes an id number`() {
        val review = wizard.substringAfter("private fun IdentityReviewStep(").substringBefore("\n}\n")
        for (id in listOf("identitySsn", "identityPassport", "identityLicense")) {
            val uses = Regex("state\\.$id\\b.*").findAll(review).map { it.value }.toList()
            assertEquals(1, uses.size, "$id is read ${uses.size} times in the review")
            assertTrue(" to R.string." in uses.single(), "$id is read for something other than its label: ${uses.single()}")
        }
    }

    /** The wizard is a way in only: an existing identity keeps the ordinary form. Turns red if the isNew gate goes. */
    @Test
    fun `the wizard is gated on a new identity`() {
        val gate = screen.indexOf("if (state.isNew && state.type == ItemType.IDENTITY) {")
        val route = screen.indexOf("IdentityRoute.MANUAL -> {")
        assertTrue(gate in 0 until route, "the MANUAL branch is not inside the new-identity gate")
    }
}

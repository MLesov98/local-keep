package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * On a fresh wizard step, the loudest control is not the one that throws the work away.
 *
 * `shots/072-card-wizard.png`, 2026-09-15: step 1 of 5, Continue disabled because no card number has
 * been typed yet. `LkPrimaryButton` draws a disabled primary as a hollow plate — ring and a faint
 * label, no fill — which is how the button family says "no primary action is available yet". Below
 * it sat `Discard` as a full `LkSecondaryButton`: a filled `wash` plate with a bright label, and
 * therefore the most prominent thing on the screen (F-22).
 *
 * `Back` on steps 2–5 keeps the secondary weight it had. It is not destructive — the wizard holds
 * every field collected so far — and the primary above it is live and brighter there anyway.
 *
 * FAILS WITHOUT THE FIX: one `LkSecondaryButton` served both, its text switching on `step == 0`.
 */
class CardWizardExitIsQuieterThanContinueTest {

    private val actions = File("src/main/kotlin/com/localkeep/feature/itemedit/CardWizard.kt")
        .readText()
        .substringAfter("internal fun CardWizardActions(", "")
        .substringBefore("/** How the card face's number should be announced.")

    @Test
    fun `Continue is the primary, disabled or not`() {
        assertTrue("LkPrimaryButton(" in actions)
        assertTrue("enabled = canAdvance && !isSaving" in actions)
    }

    @Test
    fun `Discard is the lowest weight in the stack`() {
        val step0 = actions.substringAfter("if (step == 0) {", "").substringBefore("} else {")
        assertTrue("LkTextButton(" in step0, "Discard is drawn as a plate again")
        assertTrue("R.string.itemedit_edit_discard_confirm" in step0)
    }

    @Test
    fun `Back keeps its secondary weight, because it loses nothing`() {
        val laterSteps = actions.substringAfter("if (step == 0) {", "").substringAfter("} else {", "")
        assertTrue("LkSecondaryButton(" in laterSteps)
        assertTrue("R.string.itemedit_card_wizard_back" in laterSteps)
    }
}

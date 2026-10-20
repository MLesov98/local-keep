package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The free-tier allowance refusal has to have a renderer on every route that can raise it.
 *
 * `blockedBeforeTitle` reports most refusals through `error`, which both linear routes can show.
 * The allowance is the one exception: it sets `showProPrompt` and nothing else. That flag's only
 * renderer is the `LkDialog` in `ItemEditScreen`, and it used to be declared *inside* the flat
 * form's column — past the two branches that `return` before the form is ever composed. So a
 * free-tier user at the cap who pressed Save on the identity naming step, or Continue on the card
 * wizard's Review step, got a button that produced no dialog, no message and no change of state.
 *
 * A dialog is a window: declaring it earlier in the function does not move where it draws, only
 * which routes still have it in composition. Hence a position assertion rather than a render — and
 * a source scan for the reason `PhrasePillIsSafeToMissTest` gives, that this module has no
 * Robolectric wiring. Each claim carries a control that was true before the fix as well, so a scan
 * whose anchors stopped matching fails loudly instead of passing on an empty region.
 */
class ProPromptReachesEveryRouteTest {

    private val dialogAnchor = "if (state.showProPrompt) {"
    private val wizardAnchor = "if (state.isNew && state.type == ItemType.CARD && !enteredFullForm) {"
    private val photoAnchor = "IdentityRoute.PHOTO -> {"

    private fun screenSource(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
        assertTrue(src.exists(), "ItemEditScreen.kt not found at ${src.absolutePath}")
        return src.readText()
    }

    @Test
    fun `the allowance dialog is declared before the card wizard takes the screen`() {
        val text = screenSource()
        val dialog = text.indexOf(dialogAnchor)
        val wizard = text.indexOf(wizardAnchor)
        // The controls: both branches are still there to be ordered. True before the fix and
        // after — without them a renamed anchor would make the comparison below vacuous.
        assertTrue(dialog > 0, "$dialogAnchor not found — did the dialog get renamed or removed?")
        assertTrue(wizard > 0, "$wizardAnchor not found — did the wizard branch move?")
        assertTrue(
            dialog < wizard,
            "the showProPrompt dialog is declared after the card wizard branch, which returns " +
                "past the form. readyToName() on the Review step sets showProPrompt and returns " +
                "false, so Continue would refuse to advance with nothing on screen to say why.",
        )
    }

    @Test
    fun `the allowance dialog is declared before the identity photograph route takes the screen`() {
        val text = screenSource()
        val dialog = text.indexOf(dialogAnchor)
        val photo = text.indexOf(photoAnchor)
        assertTrue(dialog > 0, "$dialogAnchor not found — did the dialog get renamed or removed?")
        assertTrue(photo > 0, "$photoAnchor not found — did the identity route move?")
        assertTrue(
            dialog < photo,
            "the showProPrompt dialog is declared after the identity photograph branch, which " +
                "returns past the form. save() from the naming step sets showProPrompt without " +
                "touching error or isSaving, so Save would look like a dead button.",
        )
    }

    @Test
    fun `the dialog is still the only thing that renders the flag, and still dismissible`() {
        val text = screenSource()
        // Controls, both true before the fix: the flag has exactly one renderer, and that renderer
        // can be closed. If a second renderer ever appears the position assertions above stop
        // being the whole story and this test should be the one that says so.
        assertTrue(
            text.split("state.showProPrompt").size - 1 == 1,
            "state.showProPrompt is read in more than one place in ItemEditScreen.kt — the " +
                "position assertions above no longer describe every renderer.",
        )
        assertTrue(
            "viewModel.dismissProPrompt()" in text,
            "the allowance dialog no longer offers a dismissal — region wrong?",
        )
    }
}

package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F49, 2026-09-13 (device walk): the card wizard's number-step Continue carried no reason in its
 * accessibility name while dimmed on an empty field. Mirrors [ContinueDisabledReasonTest]'s shape in
 * `feature/onboarding` — a pure function of the same two inputs the button's own `enabled` gate
 * uses, asserted directly rather than through a render.
 */
class CardWizardContinueDisabledReasonTest {

    @Test
    fun `an empty card number on the number step gives a reason`() {
        assertEquals(
            R.string.itemedit_card_wizard_continue_disabled_number_a11y,
            cardWizardContinueDisabledReason(step = 0, digits = ""),
        )
    }

    @Test
    fun `a typed card number on the number step has no reason to give`() {
        assertNull(cardWizardContinueDisabledReason(step = 0, digits = "4111111111111111"))
    }

    /**
     * The control that makes the first assertion meaningful: every later step's Continue is never
     * disabled on an empty [digits] (CVV, expiry, "who" and review all gate on their own fields, if
     * at all), so a fix that returned a reason whenever [digits] is empty — rather than only on the
     * number step specifically — would still pass the first test while announcing a wrong reason
     * on every other step of a card with an unusually short number.
     */
    @Test
    fun `an empty digits string on any other step has no reason to give`() {
        assertNull(cardWizardContinueDisabledReason(step = 1, digits = ""))
        assertNull(cardWizardContinueDisabledReason(step = 2, digits = ""))
        assertNull(cardWizardContinueDisabledReason(step = 3, digits = ""))
        assertNull(cardWizardContinueDisabledReason(step = 4, digits = ""))
    }
}

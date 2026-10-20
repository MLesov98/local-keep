package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A one-digit expiry year is never saved.
 *
 * Device pass 2026-10-04 (C1 finding 3): the flat card form took year "1" and the card face read
 * "expires 12 / 1". The wizard cannot produce it — its expiry step will not continue until all four
 * MM/YY digits are in — so only "Enter everything at once" and the edit form could, and the year
 * stored was the integer 1. Refused at save the way the three-digit year already is
 * ([CardExpiryValidationTest]), and for the same reason left unmarked while the user is still typing.
 */
class CardExpiryOneDigitYearTest {

    private fun state(year: String) = ItemEditUiState(cardExpYear = year)

    @Test
    fun `a one-digit year is unfinished, so save refuses it`() {
        assertTrue(state("1").cardExpYearUnfinished, "a one-digit year saved as the year 1")
        assertTrue(state(" 7 ").cardExpYearUnfinished)
    }

    /** The control: the wizard types every year through a single digit, and nothing may flag it. */
    @Test
    fun `a single digit is still not marked while it is being typed`() {
        assertFalse(cardExpiryYearInvalid("3", currentYear = 2026))
    }

    @Test
    fun `two and four digits, and blank, are finished`() {
        assertFalse(state("30").cardExpYearUnfinished)
        assertFalse(state("2030").cardExpYearUnfinished)
        assertFalse(state("").cardExpYearUnfinished)
    }
}

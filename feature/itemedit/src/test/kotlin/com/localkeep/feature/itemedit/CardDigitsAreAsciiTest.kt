package com.localkeep.feature.itemedit

import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.CardBrands
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The card fields store ASCII digits whatever keyboard typed them.
 *
 * S3-10, 2026-10-02, the half of that finding this fixes. The filter kept anything `isDigit`
 * accepts — every Unicode decimal digit — so a card number typed on an Arabic-Indic or fullwidth
 * keypad was stored in those characters. `CardBrands.detect` matches Visa on the character '4' and
 * missed it, and the indexed last four could not be found by an ASCII search.
 *
 * The other half — letters and separators dropped, a 19-digit cap — is the documented digit-only
 * decision at `onCardNumberChanged` and is unchanged; the control below pins it.
 */
class CardDigitsAreAsciiTest {

    @Test
    fun `arabic-indic digits are stored as ascii`() {
        val typed = "٤١١١١١١١١١١١١١١١"
        val stored = typed.digits(max = 19)

        assertEquals("4111111111111111", stored)
        assertEquals(CardBrand.VISA, CardBrands.detect(stored), "brand detection still cannot read it")
    }

    @Test
    fun `fullwidth digits are stored as ascii`() {
        assertEquals("0529", "０５２９".digits(max = 4))
    }

    /** The control: the digit-only rule and the cap are a documented decision and stay as they were. */
    @Test
    fun `ascii input keeps the digit-only rule and the cap`() {
        assertEquals("4111111111111111", "4111 1111-1111 1111".digits(max = 19))
        assertEquals("1234567890123456789", "12345678901234567890123".digits(max = 19))
        assertEquals("123", "AB123".digits(max = 19))
    }
}

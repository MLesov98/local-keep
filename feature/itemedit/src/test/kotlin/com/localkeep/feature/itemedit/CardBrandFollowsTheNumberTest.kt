package com.localkeep.feature.itemedit

import com.localkeep.core.model.CardBrand
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The network a saved card records follows the number it is saved with.
 *
 * Device pass 2026-10-04 (C1 finding 2): a Visa edited to `9999999999999995` and saved still read
 * "Visa card, 9999999999999995" on the detail screen. The brand the card was loaded with was carried
 * so that an *imported* brand the IIN ranges cannot read survives an edit — and it survived an edit
 * of the number too, which is the one edit that makes it untrue. Visa to `5555555555554444` was
 * right only because detection recognised the new number and overrode the carried brand.
 */
class CardBrandFollowsTheNumberTest {

    @Test
    fun `a number changed to one no network recognises keeps no old brand`() {
        assertEquals(
            CardBrand.UNKNOWN,
            cardBrandToStore("9999999999999995", carried = CardBrand.VISA, carriedFor = "4111111111111111"),
            "the old card's network was saved against a number it does not describe",
        )
    }

    /** The control the carried brand exists for, and it must stay green (QA 2026-08-06, A18 L2). */
    @Test
    fun `an imported brand survives an edit that leaves its number alone`() {
        assertEquals(
            CardBrand.VISA,
            cardBrandToStore("9700123456", carried = CardBrand.VISA, carriedFor = "9700123456"),
        )
    }

    @Test
    fun `a recognised number names its own network whatever was carried`() {
        assertEquals(
            CardBrand.MASTERCARD,
            cardBrandToStore("5555555555554444", carried = CardBrand.VISA, carriedFor = "4111111111111111"),
        )
    }

    @Test
    fun `a new card carries nothing`() {
        assertEquals(CardBrand.UNKNOWN, cardBrandToStore("9999999999999995", carried = CardBrand.UNKNOWN, carriedFor = null))
    }
}

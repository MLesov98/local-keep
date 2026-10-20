package com.localkeep.feature.itemedit

import com.localkeep.core.model.CardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What the card face shows where the embossed number goes.
 *
 * The security-relevant assertion is the negative one: masked means *no digits*, not "last four
 * shown". Card faces everywhere else in the world show the last four, and copying that convention
 * here would quietly widen what this screen discloses without the reveal gate — the number is a
 * gated secret, and the face reads the same `revealedFields` the row below it does.
 */
class CardNumberFaceTest {

    @Test
    fun `a masked visa shows sixteen bullets in four groups and no digits`() {
        val face = cardNumberFace("4242424242424242", CardBrand.VISA, revealed = false)
        assertEquals("•••• •••• •••• ••••", face)
        assertFalse(face.any(Char::isDigit), "a masked face must not leak a single digit")
    }

    @Test
    fun `a masked amex shows its own four-six-five shape`() {
        // The shape of the number is not the number, and it is what makes the face read as that
        // card rather than as a generic rectangle.
        assertEquals("•••• •••••• •••••", cardNumberFace("378282246310005", CardBrand.AMEX, revealed = false))
    }

    @Test
    fun `an empty card still shows a full-length placeholder`() {
        assertEquals("•••• •••• •••• ••••", cardNumberFace("", CardBrand.UNKNOWN, revealed = false))
    }

    @Test
    fun `revealing shows the grouped digits`() {
        assertEquals("4242 4242 4242 4242", cardNumberFace("4242424242424242", CardBrand.VISA, revealed = true))
        assertEquals("3782 822463 10005", cardNumberFace("378282246310005", CardBrand.AMEX, revealed = true))
    }

    @Test
    fun `a nineteen-digit number is spoken in the groups the screen draws`() {
        // The field and the strip regroup past the scheme in fours from the right; the face feeds
        // the strip's spoken description, so it must not say 4-4-4-4-3 while the screen shows 3-4-4-4-4.
        assertEquals("123 4567 8901 2345 6789", cardNumberFace("1234567890123456789", CardBrand.VISA, revealed = true))
    }

    @Test
    fun `a half-typed number groups as far as it goes`() {
        assertEquals("4242 42", cardNumberFace("424242", CardBrand.VISA, revealed = true))
    }

    @Test
    fun `a number longer than any scheme keeps its tail`() {
        // Loyalty and membership cards live in this type too and follow no scheme.
        assertEquals(
            "1234 5678 1234 5678 9012",
            cardNumberFace("123456781234567890 12", CardBrand.UNKNOWN, revealed = true),
        )
    }

    @Test
    fun `separators the user typed are not treated as digits`() {
        assertEquals("4242 4242 4242 4242", cardNumberFace("4242-4242-4242-4242", CardBrand.VISA, revealed = true))
    }
}

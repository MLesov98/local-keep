package com.localkeep.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules behind the card wizard's length hint.
 *
 * Two properties matter more than the table itself, and both are regressions this file exists to
 * prevent: nothing here may ever reject a number, and the length that auto-advances the wizard must
 * come from the same set the hint reads. Driving them from two different numbers is what let the
 * number step jump forward while the hint still said the number was short.
 */
class CardNumberValidatorTest {

    // Published test-card numbers, one per network, at the length each actually issues.
    private val visa16 = "4111111111111111"
    private val visa13 = "4222222222222"
    private val mastercard = "5555555555554444"
    private val amex = "378282246310005"
    private val discover = "6011111111111117"
    private val diners = "30569309025904"
    private val jcb = "3530111333300000"

    private fun brandOf(number: String) = CardBrands.detect(number)

    @Test
    fun `accepts every length a network actually issues`() {
        // A 13-digit Visa is legacy but real, and a 19-digit one ships today on co-branded cards.
        // Reducing Visa to "16" reported both as wrong.
        for (n in listOf(13, 16, 19)) {
            assertFalse(CardNumberValidator.isUnexpectedLength(CardBrand.VISA, n), "Visa $n")
        }
        assertFalse(CardNumberValidator.isUnexpectedLength(brandOf(visa16), visa16.length))
        assertFalse(CardNumberValidator.isUnexpectedLength(brandOf(visa13), visa13.length))
        assertFalse(CardNumberValidator.isUnexpectedLength(brandOf(mastercard), mastercard.length))
        assertFalse(CardNumberValidator.isUnexpectedLength(brandOf(amex), amex.length))
        assertFalse(CardNumberValidator.isUnexpectedLength(brandOf(discover), discover.length))
        assertFalse(CardNumberValidator.isUnexpectedLength(brandOf(diners), diners.length))
        assertFalse(CardNumberValidator.isUnexpectedLength(brandOf(jcb), jcb.length))
    }

    @Test
    fun `flags a length just outside the set`() {
        assertTrue(CardNumberValidator.isUnexpectedLength(CardBrand.VISA, 14))
        assertTrue(CardNumberValidator.isUnexpectedLength(CardBrand.VISA, 15))
        assertTrue(CardNumberValidator.isUnexpectedLength(CardBrand.MASTERCARD, 15))
        assertTrue(CardNumberValidator.isUnexpectedLength(CardBrand.MASTERCARD, 17))
        assertTrue(CardNumberValidator.isUnexpectedLength(CardBrand.AMEX, 16))
    }

    @Test
    fun `the reported four-digit number is flagged`() {
        // "1234" as typed in the wizard: detected as no network at all, so it says nothing; the same
        // four digits behind a Visa prefix are a length that network never issues.
        assertEquals(CardBrand.UNKNOWN, brandOf("1234"))
        assertFalse(CardNumberValidator.isUnexpectedLength(brandOf("1234"), 4))
        assertTrue(CardNumberValidator.isUnexpectedLength(CardBrand.VISA, 4))
    }

    @Test
    fun `says nothing about an unrecognised number`() {
        // Loyalty, membership and gift numbers follow no network scheme. Claiming one is the wrong
        // length is the false alarm CardBrands is documented to avoid, and it must not come back in
        // through this door.
        assertTrue(CardNumberValidator.acceptedLengths(CardBrand.UNKNOWN).isEmpty())
        for (n in 1..CardNumberValidator.MAX_DIGITS) {
            assertFalse(CardNumberValidator.isUnexpectedLength(CardBrand.UNKNOWN, n), "length $n")
        }
    }

    @Test
    fun `says nothing about an empty field`() {
        for (brand in CardBrand.entries) {
            assertFalse(CardNumberValidator.isUnexpectedLength(brand, 0), brand.name)
        }
    }

    @Test
    fun `no accepted length exceeds the cap the editor applies`() {
        for (brand in CardBrand.entries) {
            val over = CardNumberValidator.acceptedLengths(brand).filter { it > CardNumberValidator.MAX_DIGITS }
            assertTrue(over.isEmpty(), "$brand accepts $over, past the ${CardNumberValidator.MAX_DIGITS}-digit cap")
        }
    }

    @Test
    fun `auto-advance fires at a length the brand actually issues`() {
        // The property, not the number: whatever length moves the user on must be one the hint
        // considers correct, or the wizard advances off a number it is simultaneously complaining
        // about.
        for (brand in CardBrand.entries) {
            val at = CardNumberValidator.autoAdvanceLength(brand) ?: continue
            assertFalse(
                CardNumberValidator.isUnexpectedLength(brand, at),
                "$brand advances at $at but calls $at unexpected",
            )
        }
    }

    @Test
    fun `auto-advance never fires below a length the brand can still grow past`() {
        // A Discover number starting 6011 advanced at sixteen and spilled the last three digits of a
        // nineteen-digit one into Expiry. The property: no brand advances while a longer accepted
        // length exists, so the field has finished growing by the time it moves on.
        for (brand in CardBrand.entries) {
            val at = CardNumberValidator.autoAdvanceLength(brand) ?: continue
            val longer = CardNumberValidator.acceptedLengths(brand).filter { it > at }
            assertTrue(longer.isEmpty(), "$brand advances at $at but still issues $longer")
        }
    }

    @Test
    fun `a variable-length brand advances only at its longest length`() {
        assertEquals(19, CardNumberValidator.autoAdvanceLength(CardBrand.DISCOVER))
        assertEquals(19, CardNumberValidator.autoAdvanceLength(CardBrand.VISA))
        assertEquals(19, CardNumberValidator.autoAdvanceLength(CardBrand.UNIONPAY))
        assertEquals(19, CardNumberValidator.autoAdvanceLength(CardBrand.MAESTRO))
    }

    @Test
    fun `auto-advance keeps the fixed-length card hands-free`() {
        // Control: a brand that issues exactly one length is unaffected.
        assertEquals(16, CardNumberValidator.autoAdvanceLength(CardBrand.MASTERCARD))
        assertEquals(15, CardNumberValidator.autoAdvanceLength(CardBrand.AMEX))
    }

    @Test
    fun `auto-advance never fires on an unrecognised number`() {
        assertNull(CardNumberValidator.autoAdvanceLength(CardBrand.UNKNOWN))
    }

    @Test
    fun `cvv length follows the network`() {
        assertEquals(4, CardNumberValidator.cvvLength(CardBrand.AMEX))
        assertEquals(3, CardNumberValidator.cvvLength(CardBrand.VISA))
        assertTrue(CardNumberValidator.isUnexpectedCvvLength(CardBrand.VISA, 2))
        assertTrue(CardNumberValidator.isUnexpectedCvvLength(CardBrand.AMEX, 3))
        assertFalse(CardNumberValidator.isUnexpectedCvvLength(CardBrand.AMEX, 4))
    }

    @Test
    fun `a card with no cvv is never flagged`() {
        // Storing a card without its code is a legitimate thing to do, and the wizard says so.
        for (brand in CardBrand.entries) {
            assertFalse(CardNumberValidator.isUnexpectedCvvLength(brand, 0), brand.name)
        }
        assertFalse(CardNumberValidator.isUnexpectedCvvLength(CardBrand.UNKNOWN, 3))
        assertFalse(CardNumberValidator.isUnexpectedCvvLength(CardBrand.UNKNOWN, 4))
    }

    /**
     * The hole [CardNumberValidator.isImplausible] was added to close, reported from hands-on use
     * 2026-08-11: "entered 6 digits, proceed → still lets me proceed instead of telling me invalid
     * card." Six digits beginning with a digit no network claims detects as [CardBrand.UNKNOWN],
     * and [isUnexpectedLength] is false for every unrecognised number by construction — so the
     * wizard had nothing to say and said nothing.
     */
    @Test
    fun `a number too short for any network is flagged even when the brand is unknown`() {
        assertEquals(CardBrand.UNKNOWN, CardBrands.detect("123456"))
        assertFalse(
            CardNumberValidator.isUnexpectedLength(CardBrand.UNKNOWN, 6),
            "precondition: the old check is blind here, which is why isImplausible exists",
        )
        assertTrue(CardNumberValidator.isImplausible(CardBrand.UNKNOWN, 6))
        assertTrue(CardNumberValidator.isImplausible(CardBrand.UNKNOWN, 11))
    }

    @Test
    fun `an unrecognised number of a length some network issues is left alone`() {
        // Twelve is Maestro's shortest, so from twelve up "we do not recognise this" is the only
        // true statement available and this must not claim more than that. A long loyalty or gift
        // number passes through untouched.
        for (length in CardNumberValidator.MIN_DIGITS..CardNumberValidator.MAX_DIGITS) {
            assertFalse(
                CardNumberValidator.isImplausible(CardBrand.UNKNOWN, length),
                "an unrecognised $length-digit number was flagged",
            )
        }
    }

    @Test
    fun `a recognised brand is still judged against its own lengths`() {
        assertTrue(CardNumberValidator.isImplausible(CardBrand.VISA, 6))
        assertTrue(CardNumberValidator.isImplausible(CardBrand.VISA, 15))
        assertFalse(CardNumberValidator.isImplausible(CardBrand.VISA, 16))
        // Twelve is short, and legitimate for exactly one network. The floor must not override a
        // brand that actually issues it.
        assertFalse(CardNumberValidator.isImplausible(CardBrand.MAESTRO, 12))
    }

    @Test
    fun `an empty field is never implausible`() {
        for (brand in CardBrand.entries) {
            assertFalse(CardNumberValidator.isImplausible(brand, 0), brand.name)
        }
    }
}

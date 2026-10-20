package com.localkeep.autofill

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull

/**
 * What may be drawn on a suggestion row for a stored card.
 *
 * The row is rendered by the platform, in another app's window, and may be kept and redrawn after
 * the fill — so the rule is that nothing here ever returns a full PAN. These assertions are the
 * rule, written down: the tail case, the short-input case that must not fall through to showing
 * itself, and a negative that fails if anyone ever widens the tail.
 */
class CardMaskTest {

    @Test
    fun `a full number shows only its last four digits`() {
        assertEquals("•••• 4242", CardMask.tail("4242424242424242"))
    }

    @Test
    fun `formatting in the stored number does not change what is shown`() {
        assertEquals("•••• 4242", CardMask.tail("4242 4242 4242 4242"))
        assertEquals("•••• 4242", CardMask.tail("4242-4242-4242-4242"))
    }

    @Test
    fun `a short or malformed number is masked entirely rather than shown`() {
        // The failure this forbids: a half-typed or junk number falling through to render itself
        // because it was "too short to have a tail".
        assertEquals("••••", CardMask.tail("42"))
        assertEquals("••••", CardMask.tail(""))
        assertEquals("••••", CardMask.tail("not a card"))
    }

    @Test
    fun `no masked form ever contains more of the number than the last four`() {
        val pan = "4242424242421234"
        val shown = CardMask.tail(pan)
        assertFalse("the full number must never appear", shown.contains(pan))
        // The first twelve digits are the part that must not survive; the last four are permitted.
        assertFalse("no leading digits may appear", shown.contains(pan.take(12)))
        assertEquals("•••• 1234", shown)
    }

    @Test
    fun `an expiry needs both halves or it is not offered at all`() {
        assertEquals("03/27", CardMask.expiry(3, 2027))
        assertEquals("12/30", CardMask.expiry(12, 2030))
        // Null, not a partial string: half an expiry typed into a checkout is worse than an
        // untouched field, because the user has to notice it and undo it.
        assertNull(CardMask.expiry(null, 2027))
        assertNull(CardMask.expiry(3, null))
        assertNull("a month outside 1-12 is not an expiry", CardMask.expiry(13, 2027))
        assertNull(CardMask.expiry(0, 2027))
    }
}

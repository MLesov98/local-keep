package com.localkeep.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The card face's slot walk.
 *
 * Worth a test of its own rather than being left to the composable, because everything the face
 * *means* is decided here: which positions are filled, which are masked, where the group breaks
 * fall, and which single glyph is allowed to animate. A defect in any of those is a card that shows
 * the wrong number, and the composable around it draws whatever this returns.
 */
class LkCardNumberSlotsTest {

    private fun glyphs(slots: List<LkCardSlot>) = slots.joinToString("") { it.ch.toString() }

    @Test
    fun `an empty number is a full grid of placeholders`() {
        val slots = lkCardNumberSlots("")
        // 16 digits plus the three group breaks.
        assertEquals(19, slots.size)
        assertTrue(slots.none { it.filled }, "nothing is typed, so nothing is filled")
    }

    @Test
    fun `typed digits fill from the left and the rest stay placeholders`() {
        val slots = lkCardNumberSlots("453914")
        assertEquals(6, slots.count { it.filled })
        assertEquals("4539", glyphs(slots.take(4)))
        // The fifth slot is the group break, then the two typed digits of group two.
        assertEquals("14", glyphs(slots.drop(5).take(2)))
    }

    @Test
    fun `amex groups four six five`() {
        val slots = lkCardNumberSlots("378282246310005", LkCardDigitGroups.Amex)
        assertEquals(15, slots.count { it.filled })
        assertEquals(17, slots.size, "15 digits and two group breaks")
    }

    @Test
    fun `masking replaces glyphs without moving them`() {
        val open = lkCardNumberSlots("4539147384029301")
        val masked = lkCardNumberSlots("4539147384029301", maskedBefore = 16)
        assertEquals(open.size, masked.size, "a masked face is the same shape as an open one")
        assertEquals(
            open.map { it.filled },
            masked.map { it.filled },
            "masking changes what a slot shows, never whether it is there",
        )
        assertTrue(masked.filter { it.filled }.all { it.ch == '•' })
    }

    @Test
    fun `masking before an index leaves the tail readable`() {
        val slots = lkCardNumberSlots("4539147384029301", maskedBefore = 12)
        assertEquals("9301", glyphs(slots.takeLast(4)))
        assertTrue(slots.dropLast(4).filter { it.filled }.all { it.ch == '•' })
    }

    @Test
    fun `only the newest glyph lands`() {
        val slots = lkCardNumberSlots("45391", landingIndex = 4)
        assertEquals(1, slots.count { it.landing })
        assertTrue(slots.last { it.filled }.landing)
    }

    @Test
    fun `no glyph lands when nothing is being typed into`() {
        assertTrue(lkCardNumberSlots("45391").none { it.landing })
    }

    @Test
    fun `a landing index past the typed digits lands nothing`() {
        // Back-navigation and a re-render both pass an index the current value no longer reaches;
        // neither may animate a slot that is not there.
        assertTrue(lkCardNumberSlots("4539", landingIndex = 15).none { it.landing })
    }

    @Test
    fun `a number longer than any scheme keeps flowing`() {
        // A loyalty or membership number. Truncating it on the face would show a different value
        // from the one the vault holds.
        val slots = lkCardNumberSlots("12345678123456789012")
        assertEquals(20, slots.count { it.filled })
        assertEquals("9012", glyphs(slots.takeLast(4)))
    }

    @Test
    fun `digits past the last group are regrouped from the right`() {
        // QA 2026-10-03: a 19-digit number read 1234 1111 1111 1111111 on the face and
        // 1234 1111 1111 1111 111 in the number row beside it. The face takes the row's shape. Since
        // the masked-tail fix the overflow is regrouped in fours from the right (so the last four are
        // one group), and the face and the row still agree — see LkCardNumberOverflowGroupingTest.
        val face = glyphs(lkCardNumberSlots("1234111111111111111")).replace('\u2009', ' ')
        assertEquals("123 4111 1111 1111 1111", face)
    }

    @Test
    fun `group breaks are never counted as filled`() {
        val slots = lkCardNumberSlots("4539147384029301")
        val breaks = slots.filter { !it.ch.isDigit() && it.ch != '•' }
        assertEquals(3, breaks.size)
        assertTrue(breaks.none { it.filled })
        assertFalse(breaks.any { it.landing })
    }
}

/**
 * A masked card shows its last four, and those four must be one group. Left-aligned past the scheme's
 * length a 19-digit number read `4444 4444 4444 4444 333`, so the tail `•••1 234` straddled two
 * groups (card wizard, step 5).
 */
class LkCardNumberOverflowGroupingTest {

    private fun face(digits: String, maskedBefore: Int = 0) =
        lkCardNumberSlots(digits, maskedBefore = maskedBefore).joinToString("") { it.ch.toString() }
            .replace('\u2009', ' ')

    @Test
    fun `a nineteen digit number keeps its last four in one group`() {
        val shown = face("1234567890123456789", maskedBefore = 15)
        // 19 = 3 + 4*4: the odd digits lead, so the group at the end is the four that stay visible.
        assertEquals("••• •••• •••• •••• 6789", shown)
    }

    @Test
    fun `the number row groups the same way as the face`() {
        val digits = "1234567890123456789"
        val row = LkCardNumberTransformation().filter(androidx.compose.ui.text.AnnotatedString(digits)).text.text
        assertEquals("123 4567 8901 2345 6789", row)
        assertEquals(row, face(digits))
    }

    @Test
    fun `a sixteen digit card and an amex keep their familiar groups`() {
        assertEquals("4539 1473 8402 9301", face("4539147384029301"))
        assertEquals(
            "3782 822463 10005",
            lkCardNumberSlots("378282246310005", LkCardDigitGroups.Amex)
                .joinToString("") { it.ch.toString() }.replace('\u2009', ' '),
        )
    }

    @Test
    fun `a twenty digit run is five fours`() {
        assertEquals(listOf(4, 4, 4, 4, 4), lkOverflowGroupSizes(20))
        assertEquals(listOf(1, 4, 4, 4, 4), lkOverflowGroupSizes(17))
    }
}

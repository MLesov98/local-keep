package com.localkeep.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The spoken card number and the drawn one group alike.
 *
 * The strip's content description grouped a 19-digit number 4-4-4-4-3 while the face and the field
 * drew it 3-4-4-4-4 (the last four whole), so a screen-reader user was told digits in different
 * pairings than the ones in front of them. [LkCardDigitGroups.format] is the one place that decides.
 *
 * FAILS WITHOUT THE FIX: `format` does not exist.
 */
class LkCardDigitGroupsFormatTest {

    @Test
    fun aNineteenDigitNumberGroupsLikeTheFace() {
        assertEquals("123 4567 8901 2345 6789", LkCardDigitGroups.Default.format("1234567890123456789"))
    }

    @Test
    fun theSpokenGroupsAreTheDrawnGroups() {
        val digits = "1234567890123456789"
        // A gap slot is the unfilled one; a run of filled slots between gaps is one drawn group.
        val drawn = mutableListOf(0)
        lkCardNumberSlots(digits).forEach { if (it.filled) drawn[drawn.lastIndex]++ else drawn.add(0) }
        assertEquals(drawn, LkCardDigitGroups.Default.format(digits).split(' ').map { it.length })
    }

    @Test
    fun aSixteenDigitVisaKeepsItsShape() {
        assertEquals("4111 1111 1111 1111", LkCardDigitGroups.Default.format("4111111111111111"))
    }

    @Test
    fun anAmexKeepsFourSixFive() {
        assertEquals("3782 822463 10005", LkCardDigitGroups.Amex.format("378282246310005"))
    }

    @Test
    fun aPartTypedNumberIsGroupedAsFarAsItGoes() {
        assertEquals("4111 11", LkCardDigitGroups.Default.format("411111"))
    }
}

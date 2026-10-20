package com.localkeep.feature.itemedit

import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The card detail screen's "this card has expired" note.
 *
 * The wizard has always validated that the month is 1–12 and refused `13/30`, so the screen does
 * check expiry — it just never checked that the date had already gone by. A card entered as `01/20`
 * auto-advanced through the wizard, printed `EXPIRES 01 / 20` on the step headed "Check it over",
 * and showed the same on the saved item, in ordinary white, with nothing saying it had lapsed
 * (device sweep, 2026-09-11).
 *
 * Two rules are load-bearing and are what these cases pin:
 *
 *  - **A card is good through the end of its printed month.** December 2030 has not expired on any
 *    day in December 2030, so the comparison is by whole months and the current month is never past.
 *  - **Silence on anything unparseable.** These fields are free text as far as this function is
 *    concerned — the year is a String, and an import can carry whatever the export had — and a
 *    confidently wrong "expired" on a card the user can still use is worse than no note at all.
 */
class CardExpiryHasPassedTest {

    private val now = YearMonth.of(2026, 9)

    @Test
    fun `a month in the past has passed`() {
        assertTrue(cardExpiryHasPassed("01", "20", now))
        assertTrue(cardExpiryHasPassed("8", "26", now))
    }

    @Test
    fun `the current month has not passed - a card is good through its printed month`() {
        assertFalse(cardExpiryHasPassed("09", "26", now))
    }

    @Test
    fun `a month in the future has not passed`() {
        assertFalse(cardExpiryHasPassed("10", "26", now))
        assertFalse(cardExpiryHasPassed("12", "30", now))
    }

    /** Bitwarden writes four digits; a card prints two. Both are real inputs. */
    @Test
    fun `four-digit years are read as written`() {
        assertTrue(cardExpiryHasPassed("01", "2020", now))
        assertFalse(cardExpiryHasPassed("01", "2031", now))
    }

    @Test
    fun `an unparseable or absent expiry is never called expired`() {
        assertFalse(cardExpiryHasPassed("", "", now))
        assertFalse(cardExpiryHasPassed("01", "", now))
        assertFalse(cardExpiryHasPassed("", "20", now))
        assertFalse(cardExpiryHasPassed("abc", "20", now))
        assertFalse(cardExpiryHasPassed("01", "twenty", now))
    }

    /**
     * `13` is refused by the wizard, but an import is not the wizard: it can carry anything the
     * export had, and a month outside 1–12 is not a date this can reason about.
     */
    @Test
    fun `a month outside 1 to 12 is not called expired`() {
        assertFalse(cardExpiryHasPassed("13", "20", now))
        assertFalse(cardExpiryHasPassed("0", "20", now))
    }

    /** A three-digit year is neither of the two forms that occur, so it gets the silent answer. */
    @Test
    fun `a year that is neither two nor four digits is not called expired`() {
        assertFalse(cardExpiryHasPassed("01", "202", now))
    }

    @Test
    fun `whitespace around the stored values does not change the answer`() {
        assertTrue(cardExpiryHasPassed(" 01 ", " 20 ", now))
    }
}

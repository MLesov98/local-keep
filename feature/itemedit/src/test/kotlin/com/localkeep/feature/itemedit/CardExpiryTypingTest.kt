package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Typing an expiry into one `MM/YY` well.
 *
 * The first version of this padded the month on the way *out* of the state as well as on the way
 * in, so a lone "1" was rewritten to "01" before the second keystroke arrived and every subsequent
 * digit landed one place too far right: 11/29 was stored as 01/21, silently, with the wrong value
 * shown back on the review screen as though it had been read correctly. Found by driving the flow
 * on a device — the unit tests of the day had nothing to say about it, because the defect lived in
 * the loop between state and field and neither half was wrong on its own.
 *
 * These cases replay real keystroke sequences one character at a time, which is the only shape that
 * would have caught it.
 */
class CardExpiryTypingTest {

    /** Feeds [keystrokes] through the well the way a keyboard does, returning `month to year`. */
    private fun type(keystrokes: String): Pair<String, String> {
        var month = ""
        var year = ""
        for (k in keystrokes) {
            val digits = expiryDigitsFrom(month + year + k)
            month = digits.take(2)
            year = digits.drop(2)
        }
        return month to year
    }

    @Test
    fun `november twenty-nine survives being typed one digit at a time`() {
        assertEquals("11" to "29", type("1129"))
    }

    @Test
    fun `a single-digit month pads as soon as it cannot be anything else`() {
        // 7 cannot begin a two-digit month, so it is 07 and the next digit is the year's.
        assertEquals("07" to "25", type("725"))
    }

    @Test
    fun `a leading zero is left alone until the second digit arrives`() {
        assertEquals("0" to "", type("0"))
        assertEquals("07" to "", type("07"))
        assertEquals("07" to "26", type("0726"))
    }

    @Test
    fun `a leading one is not guessed at`() {
        // It could still become 10, 11 or 12. Padding it to 01 would move the caret's meaning.
        assertEquals("1" to "", type("1"))
        assertEquals("12" to "30", type("1230"))
    }

    @Test
    fun `nothing past four digits is accepted`() {
        assertEquals("11" to "29", type("112999"))
    }

    @Test
    fun `separators the user types are ignored rather than counted`() {
        assertEquals("11" to "29", type("11/29"))
    }

    @Test
    fun `an impossible month is passed through so validation can name it`() {
        // Not silently corrected: cardExpMonthInvalid is what tells the user, and quietly rewriting
        // 13 to something plausible would store a date they did not enter.
        assertEquals("13" to "", type("13"))
    }
}

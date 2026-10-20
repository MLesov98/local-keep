package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The card expiry rule, which the form shows inline and `toContent` enforces at save.
 *
 * Both read these two properties, so this is the one place the rule is written down. The case that
 * matters most is the non-numeric one: the old save-time guard was `month == null || month in 1..12`,
 * which let "O5" parse to null and take the same branch as an empty field — the card saved with no
 * expiry at all, and nothing told the user their typing had been dropped.
 */
class CardExpiryValidationTest {

    private fun state(month: String = "", year: String = "") =
        ItemEditUiState(cardExpMonth = month, cardExpYear = year)

    @Test
    fun `an empty expiry is valid — plenty of cards are filed without one`() {
        assertFalse(state().cardExpMonthInvalid)
        assertFalse(state().cardExpYearInvalid)
    }

    @Test
    fun `every real month is accepted`() {
        for (month in 1..12) {
            assertFalse(state(month = month.toString()).cardExpMonthInvalid, "month $month rejected")
        }
        assertFalse(state(month = "01").cardExpMonthInvalid)
    }

    @Test
    fun `months outside one to twelve are rejected`() {
        for (month in listOf("0", "13", "99", "-1")) {
            assertTrue(state(month = month).cardExpMonthInvalid, "month $month accepted")
        }
    }

    @Test
    fun `a month that is not a number is rejected rather than silently dropped`() {
        // Not "٥": Kotlin's toIntOrNull accepts Unicode digits, so an Arabic-Indic five really is a
        // five here. Leniency, not a hole — it parses to a month in range and saves as one.
        for (month in listOf("O5", "abc", "1o", "5.5")) {
            assertTrue(state(month = month).cardExpMonthInvalid, "month $month accepted")
        }
    }

    @Test
    fun `a year that is not a positive number is rejected`() {
        assertTrue(state(year = "0").cardExpYearInvalid)
        assertTrue(state(year = "-2030").cardExpYearInvalid)
        assertTrue(state(year = "twenty thirty").cardExpYearInvalid)
        assertFalse(state(year = "2030").cardExpYearInvalid)
        assertFalse(state(year = "30").cardExpYearInvalid)
    }

    @Test
    fun `surrounding whitespace does not make a valid value invalid`() {
        assertFalse(state(month = " 5 ").cardExpMonthInvalid)
        assertFalse(state(year = " 2030 ").cardExpYearInvalid)
    }

    @Test
    fun `whitespace alone reads as empty, not as a typo`() {
        assertFalse(state(month = "   ").cardExpMonthInvalid)
        assertFalse(state(year = "   ").cardExpYearInvalid)
    }

    /**
     * The case that was reported, 2026-08-11: "entered expiry 05/55 and it bugged, lets me proceed."
     *
     * The rule was `year <= 0`, so every positive integer the two-digit well could hold was accepted
     * — 55 read as 2055, twenty-nine years out, and the wizard advanced without a word. A form that
     * already refuses a month of 13 has no consistent reason to take that year.
     */
    @Test
    fun `a year decades beyond any issued card is rejected`() {
        assertTrue(cardExpiryYearInvalid("55", currentYear = 2026))
        assertTrue(cardExpiryYearInvalid("99", currentYear = 2026))
        assertTrue(cardExpiryYearInvalid("2055", currentYear = 2026))
    }

    @Test
    fun `two digits mean twenty-something, which is what the MM slash YY well prints`() {
        assertFalse(cardExpiryYearInvalid("30", currentYear = 2026))
        assertFalse(cardExpiryYearInvalid("2030", currentYear = 2026))
        // The boundary, stated both ways so moving MAX_CARD_YEARS_AHEAD cannot pass silently.
        assertFalse(cardExpiryYearInvalid("46", currentYear = 2026))
        assertTrue(cardExpiryYearInvalid("47", currentYear = 2026))
    }

    @Test
    fun `a year already past stays valid`() {
        // Deliberate: the number on an expired card is still what a refund or a dispute needs, and
        // refusing it would break a real use to catch a typo the far end already catches.
        assertFalse(cardExpiryYearInvalid("20", currentYear = 2026))
        assertFalse(cardExpiryYearInvalid("2001", currentYear = 2026))
    }

    @Test
    fun `a year that is too far ahead is told apart from one that is not a year`() {
        // The two need different messages. "Year is the four digits printed on the card" is true
        // advice for "twenty thirty" and a dead end for "55": following it gives 2055, which fails
        // again and says the same thing.
        assertTrue(cardExpiryYearTooFarAhead("55", currentYear = 2026))
        assertFalse(cardExpiryYearTooFarAhead("twenty thirty", currentYear = 2026))
        assertFalse(cardExpiryYearTooFarAhead("0", currentYear = 2026))
        assertFalse(cardExpiryYearTooFarAhead("", currentYear = 2026))
        assertFalse(cardExpiryYearTooFarAhead("30", currentYear = 2026))
    }

    /**
     * S3-09, 2026-10-02: three digits were taken as written, so "202" was the year 202. It raised
     * no error, was stored as typed, autofill printed it as `MM/02`, and the detail screen's expired
     * warning did not recognise it as a year. It must never save — and must still not be marked
     * while a four-digit year is being typed through it, for the reason the test below gives.
     */
    @Test
    fun `a three-digit year is unfinished, never saved, and not marked while typing`() {
        assertTrue(state(year = "202").cardExpYearUnfinished, "a three-digit year reads as a year")
        assertTrue(state(year = " 029 ").cardExpYearUnfinished)
        assertFalse(cardExpiryYearInvalid("203", currentYear = 2026), "marked on the way to 2030")
        // Controls: what the two wells actually produce is not unfinished.
        assertFalse(state(year = "30").cardExpYearUnfinished)
        assertFalse(state(year = "2030").cardExpYearUnfinished)
        assertFalse(state().cardExpYearUnfinished)
    }

    @Test
    fun `a four-digit year under a thousand is not a year`() {
        assertTrue(cardExpiryYearInvalid("0202", currentYear = 2026), "0202 read as the year 202")
        assertFalse(cardExpiryYearInvalid("2001", currentYear = 2026))
    }

    /** The refusal lives in the ViewModel's save gate, which a JVM cannot build — so read it. */
    @Test
    fun `save refuses an unfinished year`() {
        val viewModel = java.io.File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt")
            .readText()
        val gate = viewModel.substringAfter("private fun blockedBeforeTitle(", "")
            .substringBefore("\n    }\n")
        assertTrue(gate.isNotEmpty(), "blockedBeforeTitle moved; this test no longer reads it")
        assertTrue("snapshot.cardExpYearUnfinished" in gate, "save does not refuse a three-digit year")
    }

    @Test
    fun `the wizard can be typed through without the rule firing mid-keystroke`() {
        // "0", "5", "3", "0" -> the year field sees "" then "3" then "30". A rule that flagged the
        // single digit would paint an error under the user's caret while they were still typing.
        assertFalse(cardExpiryYearInvalid("", currentYear = 2026))
        assertFalse(cardExpiryYearInvalid("3", currentYear = 2026))
        assertFalse(cardExpiryYearInvalid("30", currentYear = 2026))
    }
}

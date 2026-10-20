package com.localkeep.core.health

import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * Settings → Breach list used to print the asset's raw vintage string, repo path and all. It now
 * prints a date parsed out of that string, so the parser is the thing standing between the user and
 * either a wrong freshness claim or a resurrected corpus filename.
 */
class BreachCorpusVintageTest {

    @Test
    fun `reads the date out of the vintage the shipped asset actually carries`() {
        // Copied character-for-character from the header of app/src/main/assets/breached-passwords.filter,
        // read on 2026-07-31. If a corpus rebuild changes the convention, this is the test that says so.
        assertEquals(
            YearMonth.of(2026, 7),
            BreachCorpusVintage.parseYearMonth("SecLists Pwdb_top-10000000, 2026-07"),
        )
    }

    @Test
    fun `the corpus size in the name is not mistaken for a date`() {
        // The regression that motivates the digit boundaries: a corpus named with a hyphen between
        // two digit runs must not read as a year. "top-10000000" is safe only because a letter
        // precedes the hyphen; "1000-0000" is the case that would slip through a laxer pattern.
        assertNull(BreachCorpusVintage.parseYearMonth("Pwdb_top_1000-0000"))
        assertNull(BreachCorpusVintage.parseYearMonth("corpus-10000000"))
    }

    @Test
    fun `rejects matches that are not a plausible calendar month`() {
        assertNull(BreachCorpusVintage.parseYearMonth("snapshot 2026-13"))
        assertNull(BreachCorpusVintage.parseYearMonth("snapshot 2026-00"))
        assertNull(BreachCorpusVintage.parseYearMonth("snapshot 1999-06"))
        assertNull(BreachCorpusVintage.parseYearMonth("snapshot 2101-06"))
    }

    @Test
    fun `takes the earliest date when a malformed vintage names two`() {
        // Conservative on purpose: a footnote that overstates how current the list is is the one
        // failure mode here that costs the user something.
        assertEquals(
            YearMonth.of(2025, 3),
            BreachCorpusVintage.parseYearMonth("built 2025-03, repacked 2026-07"),
        )
    }

    @Test
    fun `a vintage with no date at all yields null rather than a guess`() {
        // Drives the undated string variant. "not loaded" is BreachFilter.filterVintage()'s own
        // fallback, so it reaches here whenever the corpus failed to load.
        assertNull(BreachCorpusVintage.parseYearMonth("not loaded"))
        assertNull(BreachCorpusVintage.parseYearMonth(""))
        assertNull(BreachCorpusVintage.parseYearMonth("ad-hoc placeholder corpus"))
    }
}

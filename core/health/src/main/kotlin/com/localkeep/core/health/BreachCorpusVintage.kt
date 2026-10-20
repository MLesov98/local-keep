package com.localkeep.core.health

import java.time.YearMonth

/**
 * Turns the breach asset's maintainer-authored provenance string into a date a user can read.
 *
 * The asset header carries a freeform "vintage" written by whoever ran the offline builder, and the
 * shipped one reads `SecLists Pwdb_top-10000000, 2026-07`. That is the build pipeline's vocabulary:
 * an upstream repo path and a corpus filename, answering a question nobody asked ("which list did
 * you clone?") while burying the one that matters ("how current is it?") behind 27 characters of
 * noise. Every screen that shows provenance parses the date out with this and shows only that.
 *
 * Lives in `:core:health`, beside [BreachFilter] which owns the vintage, because two different
 * screens need it — the Security tab's footnote once did, and Settings → Breach list still does.
 * Pure `java.time`, no Android types, so it stays usable from this JVM-only module.
 */
object BreachCorpusVintage {

    /**
     * A four-digit year and two-digit month, anywhere in [vintage]. Null when there isn't one.
     *
     * Deliberately tolerant about what surrounds the date and strict about the date itself: the
     * vintage is prose, so the format of the rest will drift, but a match that is not a plausible
     * calendar month is a false positive rather than a date. The range check is what stops
     * `Pwdb_top-10000000` from being read as year 0000 month 00 if a future corpus name happens to
     * put a hyphen between digit runs — the current one does not match at all, because the
     * character before its hyphen is a letter.
     *
     * The *first* valid match wins rather than the last. A vintage naming two dates is already
     * malformed; taking the earlier one keeps the freshness claim conservative.
     */
    fun parseYearMonth(vintage: String): YearMonth? =
        DATE.findAll(vintage)
            .mapNotNull { match ->
                val (year, month) = match.destructured
                // toIntOrNull rather than toInt: the regex guarantees the digits, not that they fit
                // in an Int — a pathological input is a miss, never a crash on the Security tab.
                val y = year.toIntOrNull() ?: return@mapNotNull null
                val m = month.toIntOrNull() ?: return@mapNotNull null
                if (y in MIN_YEAR..MAX_YEAR && m in 1..12) YearMonth.of(y, m) else null
            }
            .firstOrNull()

    /**
     * Bounds on a believable corpus date.
     *
     * Not "any year": the point of the range is to reject digit runs that merely look like a date,
     * and a range wide enough to admit year 0 admits those too. The lower bound predates any breach
     * corpus that could ship here; the upper is far enough out that no build will reach it.
     */
    private const val MIN_YEAR = 2000
    private const val MAX_YEAR = 2100

    private val DATE = Regex("""(?<!\d)(\d{4})-(\d{2})(?!\d)""")
}

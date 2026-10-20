package com.localkeep.feature.health

import android.icu.math.BigDecimal
import android.icu.text.CompactDecimalFormat
import java.util.Locale

/**
 * How the Security tab says how big the breach corpus is.
 *
 * Vintage *parsing* moved to `com.localkeep.core.health.BreachCorpusVintage` when the dashboard
 * footnote stopped printing a date — the corpus owns its own provenance, and Settings is the only
 * screen that still shows it. What stays here is presentation, which needs Android's ICU and
 * therefore cannot live in the JVM-only `:core:health`.
 */
internal object BreachCorpusFootnote {

    /**
     * The corpus size as a person would say it: "9.9M", not "9,995,050".
     *
     * The exact integer was deliberate once — the reasoning being that rounding down reads as less
     * precise than the app really is. What it actually reads as is a machine printing an internal
     * counter. Seven significant figures is not information a user can act on; it is the shape of
     * generated text, on the one screen whose job is to sound trustworthy about security.
     *
     * **Rounds DOWN, always.** The entry count is already a lower bound (see
     * `BreachFilter.filterEntryCount`), and a size claim on this screen must never travel upward:
     * 9,995,050 becoming "10M" would be an overclaim, however small. Rounding down makes "9.9M"
     * the worst case, which understates and is therefore safe.
     *
     * `android.icu`, not `java.text`: Java 12's `NumberFormat.getCompactNumberInstance` was never
     * added to Android's `java.text`, and the platform equivalent is ICU's, present since API 24 —
     * comfortably below this app's minSdk of 28, so no API-level branch is needed. ICU also supplies
     * the unit per locale, so no language needs a translated "million": `de` gets "9,9 Mio.",
     * `fr` "9,9 M".
     */
    fun formatCorpusSize(entries: Long, locale: Locale): String =
        CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT).apply {
            roundingMode = BigDecimal.ROUND_DOWN
            maximumFractionDigits = 1
        }.format(entries)
}

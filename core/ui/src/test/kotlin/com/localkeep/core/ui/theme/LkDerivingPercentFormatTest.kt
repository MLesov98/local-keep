package com.localkeep.core.ui.theme

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `LkDerivingOverlay`'s KDF-progress percentage (and `HealthDashboardScreen`'s scan-progress
 * percentage, the same pattern) used to be `"${floor(progress * 100f).toInt()}%"` — a hardcoded
 * concatenation that ignores locale (digit glyphs, and the number/percent-sign order and
 * spacing, both vary by locale). [lkFlooredPercent] replaces both with
 * `android.icu.text.NumberFormat.getPercentInstance`, the same approach `BreachCorpusFootnote`
 * already uses for the corpus-size figure on the same screen family. Robolectric, like
 * `LkMonogramTileFontScaleTest`, because `android.icu` needs an Android runtime.
 */
@RunWith(RobolectricTestRunner::class)
class LkDerivingPercentFormatTest {

    @Test
    fun formatsAWholePercentageForEnglish() {
        assertEquals("60%", lkFlooredPercent(0.60f, Locale.US))
    }

    /**
     * The overlay's own rule, restated in `LkDeriving.kt`'s doc: 99% must not read 100% while the
     * derivation is still running. `ROUND_DOWN` on a fraction already in `0f..1f` is the floor.
     */
    @Test
    fun neverRoundsUp() {
        assertEquals("99%", lkFlooredPercent(0.999f, Locale.US))
    }

    /** The regression this test exists for: the old code produced identical text in every locale. */
    @Test
    fun isLocaleAware() {
        val us = lkFlooredPercent(0.60f, Locale.US)
        val fr = lkFlooredPercent(0.60f, Locale.FRANCE)
        assertNotEquals(
            us,
            fr,
            "the percentage rendered identically in en-US and fr-FR — it is not reading the " +
                "locale's own number/percent-sign conventions (fr-FR puts a narrow no-break space " +
                "before the % sign; en-US does not).",
        )
        assertTrue("%" in fr, "fr-FR output lost the percent sign entirely: $fr")
    }
}

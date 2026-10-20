package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The auto-lock presets must not hide their timings at a large font scale.
 *
 * All three were rendered as a single `LkChip` label, `"Relaxed · 5 minutes / 1 minute"`. `LkChip`
 * draws its label with `maxLines = 1` and `TextOverflow.Ellipsis` by construction — one line is
 * what makes a row of chips a row — so at `font_scale 2.0` two of the three presets ended in an
 * ellipsis and the one number the legend beneath them points at was unreadable (device,
 * 2026-09-20; filed twice, as 3A-F32 and 3C-6).
 *
 * The pill keeps the preset's single short word and the durations moved below it, where they wrap.
 * Asserted on the source, the same idiom as [DuressRemovalSurvivesEntitlementLossTest]:
 * `:feature:settings` has no Compose UI test dependency, so there is no way to compose this row at
 * a font scale and measure it.
 */
class AutoLockChipsDoNotTruncateTest {

    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** The auto-lock row alone: from its title to the Biometric row that follows it. */
    private val row: String =
        screen.substringAfter("R.string.settings_autolock_title")
            .substringBefore("R.string.settings_biometric_title")

    @Test
    fun `the slice is bounded`() {
        assertTrue(
            row.isNotEmpty() && row.length < screen.length,
            "the auto-lock row slice is unbounded, so the assertions below are measuring the " +
                "whole screen and cannot fail; length=${row.length} of ${screen.length}",
        )
        assertTrue(
            "LkChip(" in row,
            "the slice does not contain the control it is named for",
        )
    }

    @Test
    fun `the pill holds only the preset name`() {
        assertTrue(
            "LkChip(\n                                    text = stringResource(preset.labelRes)," in row,
            "the chip's label is built from more than the preset's own word again: whatever is " +
                "added to it is ellipsised at a large font scale, because LkChip's label is one " +
                "line by construction. Row: $row",
        )
    }

    @Test
    fun `the timings are rendered outside the pill, where they can wrap`() {
        assertTrue(
            "R.string.settings_autolock_chip_durations" in row,
            "the idle and background timings are no longer shown per preset at all — comparing " +
                "the three is what putting the numbers here was for. Row: $row",
        )
        val chipAt = row.indexOf("LkChip(")
        val durationsAt = row.indexOf("R.string.settings_autolock_chip_durations")
        assertTrue(
            durationsAt > chipAt,
            "the timings are drawn inside the pill rather than under it, so they ellipsise again",
        )
    }
}

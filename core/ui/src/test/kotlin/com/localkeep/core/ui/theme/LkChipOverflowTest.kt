package com.localkeep.core.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `LkChip`'s label (`LkButtons.kt`) sat inside a Row pinned to a fixed `.height(34.dp)` with
 * `maxLines = 1` and no `overflow` set — Compose's default is `TextOverflow.Clip`, so at a large
 * accessibility font scale (fontScale 2.0) the label's real line height exceeds the fixed pill and
 * clips with no defined behaviour. `LabelRow`, a few lines below in the same file, was given
 * `overflow = TextOverflow.Ellipsis` for exactly this reason (see `FontScaleTest.primaryButtonGrowsWithFontScale`).
 * The chip's pill height is deliberately kept fixed (`chipKeepsItsTouchTargetAtLargeFontScale`), so
 * the fix here is narrower than `LabelRow`'s — same overflow contract, no growth.
 *
 * Pure JVM `kotlin.test`, no Compose runtime — reads the source file directly, same idiom as
 * `LkIndicatorsCopyTest`.
 */
class LkChipOverflowTest {

    private val source: String = File("src/main/kotlin/com/localkeep/core/ui/theme/LkButtons.kt").readText()

    @Test
    fun chipLabelDeclaresAnOverflowContract() {
        val chipText = Regex("""Text\(text, style = LkText\.chip, color = content, maxLines = 1[^)]*\)""")
            .find(source)
            ?.value
            ?: error("LkChip's label Text(...) call not found in LkButtons.kt")
        assertTrue(
            "overflow = TextOverflow.Ellipsis" in chipText,
            "LkChip's label has no overflow contract: $chipText. Without it, Compose defaults to " +
                "TextOverflow.Clip, and at fontScale 2.0 the label's line height exceeds the chip's " +
                "fixed 34dp pill with undefined clip behaviour instead of a graceful ellipsis.",
        )
    }
}

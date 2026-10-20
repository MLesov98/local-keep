package com.localkeep.app.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A bottom tab grows with the font instead of overlapping itself.
 *
 * Found 2026-10-03, font scale 1.8: the tab was a fixed 52dp, and its icon, label and selection
 * underline outgrew it, so the underline ran into the label (screenshot at font scale 1.8). Pinned on the
 * source for the reason `GeneratorLiftsOverShellOverlaysTest` gives for its own: a JVM test cannot
 * lay out a composable at a font scale.
 */
class BottomTabGrowsWithTheFontTest {

    @Test
    fun theTabHasAMinimumHeightNotAFixedOne() {
        val file = File("src/main/kotlin/com/localkeep/app/navigation/AppShell.kt")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        val source = file.readText()
        val start = source.indexOf(".lkTag(\"tab-\${destination.route}\")")
        assertTrue(start >= 0, "the bottom tab is gone")
        val modifiers = source.substring(start, source.indexOf(".clickable(", start))

        // FAILS WITHOUT THE FIX: `.height(52.dp)`.
        assertTrue(
            ".heightIn(min = 52.dp)" in modifiers && ".height(52.dp)" !in modifiers,
            "the tab must be allowed to grow past 52dp. Found:\n$modifiers",
        )
    }
}

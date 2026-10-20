package com.localkeep.core.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the UX blueprint's newest cross-cutting rule: **colour is never the sole carrier of meaning**
 * (`uiux:31`, and the clause added alongside it on 2026-07-28).
 *
 * Selected and unselected drawer rows measured **1.21:1** against each other — far under any
 * perceptual threshold — so "where you are" was invisible to a sighted user with reduced colour
 * discrimination on all three surfaces that carry selection: the drawer row, the bottom-bar tab and
 * the filter chip. The accessibility half had already been handled (each spells ", selected" into
 * its label, verified through `uiautomator` rather than through a semantics property that did not
 * survive the trip); this is the visual half. Recorded as UX debt 6-1 and 6-2.
 *
 * This file covers the chip, which lives here. The drawer row and the bottom-bar tab live in
 * `:app` and are pinned by `SelectionCarrierTest` there — a unit test reaching across a module
 * boundary with `../..` is a test that breaks the first time anything moves.
 *
 * **What this test is, stated plainly.** It reads source, not pixels. Contrast between two rendered
 * states is a device question, and this repo has no harness that renders a composable at all — the
 * same known gap. What it does catch is the regression that actually
 * threatens this: someone simplifying a selection branch back down to a colour-only `if`. Each
 * assertion fails if its carrier is deleted.
 */
class SelectionCarrierTest {

    private fun source(path: String): String {
        val file = File(path)
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }

    @Test
    fun `a selected filter chip shows a check glyph, not only a tint`() {
        val chip = source("src/main/kotlin/com/localkeep/core/ui/theme/LkButtons.kt")
            .substringAfter("fun LkChip(")
            .substringBefore("\nfun ")
        assertTrue(
            chip.contains("if (selected) LkIcons.Check else icon"),
            "LkChip must swap in a check glyph when selected. Without it, selection is a 1.21:1 " +
                "tint and nothing else — the exact defect uiux:31 forbids.",
        )
    }

}

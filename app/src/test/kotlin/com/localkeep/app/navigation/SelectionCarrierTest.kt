package com.localkeep.app.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `:app` half of the UX blueprint's rule that **colour is never the sole carrier of meaning**
 * (`uiux:31`, plus the clause added alongside it on 2026-07-28). The filter chip's half is pinned by
 * the test of the same name in `:core:ui`.
 *
 * Selected and unselected drawer rows measured **1.21:1** against each other. The accessibility half
 * was already handled — each surface spells ", selected" into its label, established with
 * `uiautomator dump` after a `semantics { selected }` property turned out not to survive the trip —
 * but a sighted user with reduced colour discrimination had nothing at all. UX debt 6-1 and 6-2,
 * fixed as one decision: a rail on the drawer row, an indicator bar under the tab, a check glyph on
 * the chip.
 *
 * **This reads source, not pixels.** Contrast between two rendered states is a device question, and
 * this repo has no harness that renders a composable at all (a known gap).
 * What it catches is the regression that actually threatens these: someone simplifying a selection
 * branch back down to a colour-only `if`.
 */
class SelectionCarrierTest {

    private fun appShell(): String {
        val file = File("src/main/kotlin/com/localkeep/app/navigation/AppShell.kt")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }

    @Test
    fun `the selected drawer row draws a rail`() {
        val drawer = appShell().substringAfter("private fun DrawerLink(").substringBefore("\nprivate fun ")
        assertTrue(
            drawer.contains("if (selected) c.jade else Color.Transparent"),
            "the drawer's selected row must draw its rail — a tint-only branch is the 1.21:1 defect",
        )
        assertTrue(
            drawer.contains(".width(3.dp)"),
            "the rail must occupy its 3dp whether drawn or not, so selecting a row does not shift " +
                "the icon and label sideways",
        )
    }

    @Test
    fun `the selected bottom tab draws an indicator`() {
        val tab = appShell().substringAfter("private fun BottomTab(").substringBefore("\n/**")
        assertTrue(
            tab.contains(".height(2.dp)"),
            "the bottom tab's selected state must carry its indicator bar, not colour alone",
        )
        assertTrue(
            tab.contains("if (selected) c.jade else Color.Transparent"),
            "the indicator must be the thing that changes on selection",
        )
    }

}

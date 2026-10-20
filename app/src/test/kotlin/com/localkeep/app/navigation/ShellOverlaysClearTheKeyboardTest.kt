package com.localkeep.app.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shell's notices sit above the keyboard when it is up.
 *
 * Found 2026-10-03: trashing an item from a search posts "Moved to trash" with Undo, and the
 * shell placed it a fixed distance above the tab bar — under the keyboard the search had open. The
 * Undo was invisible, and a tap where it should have been typed a letter into the search instead.
 *
 * A source-level guard, as `GeneratorLiftsOverShellOverlaysTest` is for the same overlays: a JVM test
 * cannot see a window inset.
 */
class ShellOverlaysClearTheKeyboardTest {

    @Test
    fun theOverlayColumnIsPlacedAgainstTheKeyboardAsWellAsTheTabBar() {
        val file = File("src/main/kotlin/com/localkeep/app/navigation/AppShell.kt")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        val source = file.readText()
        val start = source.indexOf("LkNoticeHost()")
        assertTrue(start >= 0, "AppShell no longer mounts LkNoticeHost")
        // From the scaffold's content lambda to the notice host: where the overlays are placed.
        val placement = source.substring(source.lastIndexOf("innerPadding ->", start), start)

        // FAILS WITHOUT THE FIX: the column padded for the tab bar only.
        assertTrue(
            "WindowInsets.ime" in placement,
            "the shell overlays must be placed above the keyboard as well as the tab bar, or a notice " +
                "posted while typing lands under the IME. Found:\n$placement",
        )
    }
}

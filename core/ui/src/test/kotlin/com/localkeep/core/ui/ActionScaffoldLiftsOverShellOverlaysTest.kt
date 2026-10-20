package com.localkeep.core.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The action bar under a form lifts over the shell's floating overlays.
 *
 * Copying a password from the item editor mounts the clipboard countdown at the bottom of the
 * shell, and `LkActionScaffold` anchors Save at the bottom of the screen — so the card covered Save
 * for the whole countdown (found 2026-10-03). The vault list's FAB and the
 * generator already lift by [shellOverlayInset]; the scaffold every item editor, the file editor and
 * the unlock screen stand on did not.
 *
 * A source-level guard for the reason [com.localkeep.feature.generator] gives for its own: a JVM test
 * cannot observe a Compose padding, let alone an overlay drawn by another module.
 */
class ActionScaffoldLiftsOverShellOverlaysTest {

    @Test
    fun theActionColumnClearsTheShellOverlays() {
        val file = File("src/main/kotlin/com/localkeep/core/ui/theme/LkContainers.kt")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        val source = file.readText()
        val start = source.indexOf("fun LkActionScaffold(")
        assertTrue(start >= 0, "LkActionScaffold is gone")
        val body = source.substring(start, source.indexOf("\n}\n", start))

        // FAILS WITHOUT THE FIX: the scaffold padded for the IME and the navigation bar only.
        assertTrue(
            "shellOverlayInset()" in body,
            "LkActionScaffold's action column must lift by shellOverlayInset(), or the clipboard " +
                "countdown covers Save for the length of the countdown",
        )
    }
}

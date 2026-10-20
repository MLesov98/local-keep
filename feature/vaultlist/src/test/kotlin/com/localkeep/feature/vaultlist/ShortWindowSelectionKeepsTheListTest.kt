package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * In a short window, selecting does not cover the list it selects from.
 *
 * Found 2026-10-03, landscape: the header, search and tab bar leave the list about 300px, and the
 * floating Move / Tag / Trash bar — plus the room the list reserves for it — took all of it. No row
 * was visible or tappable while selecting (`agent4/landsel.png`). A landscape window is wide, so in
 * a short window the three actions ride in the selection header's own row instead, and the floating
 * bar is not drawn.
 *
 * Pinned on the source, in the style of [CarriedFolderTrashActionTest]: the composable cannot be
 * driven from a JVM test, and a short window cannot be asked for from one either.
 */
class ShortWindowSelectionKeepsTheListTest {

    private val source: String by lazy {
        val file = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        file.readText()
    }

    @Test
    fun `the floating bulk bar is drawn only when the window is not short`() {
        // FAILS WITHOUT THE FIX: the bar was drawn whenever anything was selected.
        assertTrue(
            Regex("""if \(state\.selecting && !shortWindow\) \{\s*BulkActionBar\(""").containsMatchIn(source),
            "the floating BulkActionBar must be gated on !shortWindow",
        )
    }

    @Test
    fun `a short window puts the bulk actions in the selection header`() {
        val header = source.substring(
            source.indexOf("if (state.selecting) {"),
            source.indexOf("BulkActionBar(", source.indexOf("if (state.selecting && !shortWindow)")),
        )
        assertTrue(
            "if (shortWindow)" in header && "HeaderBulkActions(" in header,
            "the selection header must carry the bulk actions when the window is short",
        )
    }
}

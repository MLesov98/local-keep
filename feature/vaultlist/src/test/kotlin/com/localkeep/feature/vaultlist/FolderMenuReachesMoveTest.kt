package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A folder created a moment ago must still be movable.
 *
 * 2C-F3, on a device 2026-09-20: the row of a just-created folder withholds its inline Move and
 * Rename controls for a beat (`FolderUiState.justCreatedId`), and `justCreatedId` is cleared only
 * by `startRenaming`, `startMoving` or `duplicate` — so scrolling and twenty seconds of waiting did
 * not bring them back; only leaving and re-entering the screen did. That would have cost nothing if
 * the long-press menu carried the full set, and it carried Rename, Duplicate and Delete but **not
 * Move**. So the one action a folder made in the wrong place needs was the one with no route to it.
 *
 * Asserted against the source because the menu is a `DropdownMenu` inside a `combinedClickable`
 * row, which needs a device to open; the claim here is which actions the menu offers, which the
 * source answers exactly. `FolderManagementRowLongClickAccessibilityTest` (androidTest) covers the
 * menu's behaviour on a device.
 */
class FolderMenuReachesMoveTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/FolderScreen.kt")

    private fun menuBlock(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val text = source.readText()
        val start = text.indexOf("DropdownMenu(expanded = menuExpanded")
        assertTrue(start >= 0, "the folder row no longer has a long-press menu")
        val end = text.indexOf("\n        }\n    }\n}", start)
        assertTrue(end > start, "could not find the end of the long-press menu")
        return text.substring(start, end)
    }

    @Test
    fun `the long-press menu offers Move`() {
        val menu = menuBlock()
        assertTrue(
            "vaultlist_folder_action_move)" in menu,
            "the menu must offer Move: a folder created a moment ago hides its inline Move control " +
                "and the menu is the only other route. Found:\n$menu",
        )
        assertTrue(
            "viewModel.startMoving(row.folder.id)" in menu,
            "the Move entry must actually start the move (which is also what clears justCreatedId)",
        )
    }

    /** The control: the three actions that were already there must not have been traded away. */
    @Test
    fun `it still offers rename, duplicate and delete`() {
        val menu = menuBlock()
        listOf("rename", "duplicate", "delete").forEach { action ->
            assertTrue(
                "vaultlist_folder_action_$action)" in menu,
                "the menu lost $action",
            )
        }
    }
}

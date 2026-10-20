package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Found 2026-10-02: a folder dragged onto the Trash dock after it had spring-loaded into
 * another folder was swallowed by the dock and not trashed.
 *
 * The screen resolved one trash action per *visible* folder and the dock looked the dragged folder up
 * in that map. Resting on a sibling for the spring-load opens the sibling with the payload still in
 * hand, so the list then shows the sibling's children and the carried folder is no longer a row: the
 * lookup found nothing, `?.invoke()` did nothing, and because the dock accepts every payload the drag
 * still ended in the "swallowed" animation. A drop that reads as a delete and is not one.
 *
 * [foldersWithTrashAction] is what the map is built from. The composable half cannot be driven from a
 * JVM test, so the wiring is pinned on the source as well, in the style of
 * [VaultListMainThreadWorkTest].
 */
class CarriedFolderTrashActionTest {

    private val a = Folder(UUID.randomUUID(), "A")
    private val b = Folder(UUID.randomUUID(), "B")
    private val inB = Folder(UUID.randomUUID(), "Inside B", parentId = b.id)

    @Test
    fun `the carried folder gets an action after the list has moved into a sibling`() {
        // The list is now showing B's children; A was carried out of the root list.
        val withActions = foldersWithTrashAction(
            visible = listOf(inB),
            all = listOf(a, b, inB),
            carriedFolderId = a.id,
        )

        // FAILS WITHOUT THE FIX: the map held `visible` only, so A had no action.
        assertEquals(setOf(inB.id, a.id), withActions.map { it.id }.toSet())
    }

    @Test
    fun `a carried folder that is already a row is not listed twice`() {
        val withActions = foldersWithTrashAction(
            visible = listOf(a, b),
            all = listOf(a, b, inB),
            carriedFolderId = a.id,
        )

        assertEquals(listOf(a.id, b.id), withActions.map { it.id })
    }

    @Test
    fun `nothing is carried, nothing is added`() {
        val visible = listOf(a, b)

        assertEquals(visible, foldersWithTrashAction(visible, listOf(a, b, inB), carriedFolderId = null))
    }

    @Test
    fun `a carried id that is not a folder of this vault adds nothing`() {
        val visible = listOf(a)

        assertEquals(visible, foldersWithTrashAction(visible, listOf(a, b), carriedFolderId = UUID.randomUUID()))
    }

    @Test
    fun `the screen builds its trash actions from the carried folder as well as the rows`() {
        val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val text = source.readText()
        // From the folder list it is built over (split out for S1-09's shared subtree index) to the
        // map itself.
        val start = text.indexOf("val trashActionFolders")
        assertTrue(start >= 0, "VaultListScreen no longer declares trashActionFolders")
        val declaration = text.substring(start, text.indexOf("val trashDraggedFolder", start))
        assertTrue(
            "foldersWithTrashAction(" in declaration && "carriedFolderId" in declaration &&
                "folderTrashActions: Map<UUID, () -> Unit> = trashActionFolders" in declaration,
            "folderTrashActions must be built through foldersWithTrashAction with the folder being " +
                "carried; built from state.childFolders alone, the Trash dock finds no action for a " +
                "folder that spring-loaded into a sibling and does nothing. Found:\n$declaration",
        )
    }
}

package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The selection header says why selected rows are off screen only when it knows.
 *
 * Device pass 2026-10-04: dragging a two-item selection out of the folder being browsed left the
 * header reading "2 selected. 2 hidden by filter" with no filter applied. The selection survives
 * the drop, and the rows are off screen because they now live in another folder. "Hidden by
 * filter" stays for the case it describes; anything the browse position hides gets the neutral
 * line. Which case applies is decided in `refresh` ([VaultListUiState.selectionOutsideBrowse]);
 * `BulkMoveAndFolderTrashUndoTest` drives that half against a real vault.
 */
class HiddenSelectionSubtitleTest {

    private val browsed = UUID.randomUUID()

    private fun item(title: String) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Note("x"),
    )

    @Test
    fun `a selection moved out of the browsed folder is not blamed on a filter`() {
        val left = item("Moved one")
        val alsoLeft = item("Moved two")
        val state = VaultListUiState(
            items = listOf(item("Still here")),
            folderStack = listOf(browsed),
            selectedIds = setOf(left.id, alsoLeft.id),
            selectionOutsideBrowse = true,
        )
        assertEquals(2, state.hiddenSelectedCount)
        assertEquals(
            R.plurals.vaultlist_selection_not_in_list_subtitle,
            state.hiddenSelectionSubtitle,
            "rows that left the folder were reported as hidden by a filter that is not on",
        )
    }

    /** The control: a filter that really is hiding the selection is still named. */
    @Test
    fun `a selection a filter is hiding still says so`() {
        val filteredOut = item("Not a favourite")
        val state = VaultListUiState(
            items = listOf(item("Favourite")),
            showFavoritesOnly = true,
            selectedIds = setOf(filteredOut.id),
        )
        assertEquals(R.plurals.vaultlist_selection_hidden_subtitle, state.hiddenSelectionSubtitle)
    }

    @Test
    fun `a selection that is all on screen has no hidden line`() {
        val shown = item("Shown")
        val state = VaultListUiState(
            items = listOf(shown),
            selectedIds = setOf(shown.id),
            selectionOutsideBrowse = true,
        )
        assertNull(state.hiddenSelectionSubtitle)
    }
}

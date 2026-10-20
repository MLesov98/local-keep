package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Favorites is a flat view of favourited items across the whole vault (spec §4.1), so a favourite
 * that lives in a folder is a row at the root like any other.
 *
 * 2A-F12, on a device 2026-09-20: the one favourite in the fixture sat in the **Work** folder, and
 * the header read `1 item · filter: favorites` over `No matching items` / "Nothing matches the
 * filters you have applied." Two halves had drifted apart — the folder *rows* already knew all four
 * cross-folder narrowings ([VaultListUiState.childFolders]) and hid themselves, while the folder
 * *axis* ([folderScopePredicate]) knew only about search and still let unfiled items through alone.
 * So the view had neither the favourite nor a folder to reach it by, and counted it anyway.
 *
 * Driven through the same two pieces `scanAndPublish` composes — the predicate for the rows,
 * [headerItemCount] for the number — in the style of [HeaderItemCountTest], so this fails for the
 * reason the screen would. The repo rule it pins is "user-facing counts must be true": the assertion
 * that matters is `count == rows.size`, not either number on its own.
 */
class FavoritesAreFlatAcrossFoldersTest {

    private val work = Folder(UUID.randomUUID(), "Work")

    private fun item(folderId: UUID?, favorite: Boolean) = VaultItem(
        id = UUID.randomUUID(),
        title = "item",
        folderId = folderId,
        favorite = favorite,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "u", password = "p"),
    )

    /** The 2A fixture in miniature: the only favourite is filed, everything else is not. */
    private val vault = listOf(
        item(folderId = work.id, favorite = true),
        item(folderId = work.id, favorite = false),
        item(folderId = null, favorite = false),
    )

    private fun rowsAndCount(state: VaultListUiState): Pair<List<VaultItem>, Int> {
        val narrowed = vault.filter { !state.showFavoritesOnly || it.favorite }
        val scoped = narrowed.filter(
            folderScopePredicate(state.folderStack.lastOrNull(), state.isFlatView, descendants = emptySet()),
        )
        return scoped to headerItemCount(state.folderStack.lastOrNull(), narrowed.size, scoped.size)
    }

    @Test
    fun `a favourite inside a folder is listed at the root under the Favorites filter`() {
        val state = VaultListUiState(folders = listOf(work), showFavoritesOnly = true)
        val (rows, count) = rowsAndCount(state)

        assertEquals(1, rows.size, "the favourite lives in Work and the flat view must still show it")
        assertEquals(
            rows.size,
            count,
            "the header counted $count over ${rows.size} rows — the count the user reads must be the " +
                "rows the user can see",
        )
        assertTrue(
            state.childFolders.isEmpty(),
            "Favorites still offers no folder rows; the items have to come through the axis instead",
        )
    }

    @Test
    fun `browsing the root without a filter still hides filed items`() {
        // The control. If this ever goes green alongside a change that makes the test above pass,
        // the fix has flattened the hierarchy instead of flattening one view of it.
        val state = VaultListUiState(folders = listOf(work))
        val (rows, count) = rowsAndCount(state)

        assertEquals(1, rows.size, "only the unfiled item is a root row while browsing")
        assertEquals(3, count, "the header still counts the whole vault at the root (F-31)")
        assertFalse(state.isFlatView, "plain browsing is not a flat view")
    }
}

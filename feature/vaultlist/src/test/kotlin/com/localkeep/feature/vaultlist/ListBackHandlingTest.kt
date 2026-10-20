package com.localkeep.feature.vaultlist

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What Back undoes on the vault list, and in what order.
 *
 * F-08 (device pass 2026-09-15): with a Favorites token on screen, `KEYCODE_BACK` left the app
 * outright — `mCurrentFocus` moved to another app — and relaunching dropped the filter anyway, so
 * the narrowing that was not worth clearing was also not worth keeping. A search was worse: the
 * first Back closed the IME and the second exited with the query still applied.
 *
 * [resolveListBack] is the one function the screen's single `BackHandler` goes through, so the
 * ordering is pinned here rather than through Compose — the same arrangement [FolderBackHandlingTest]
 * uses for the Folders screen.
 */
class ListBackHandlingTest {

    private val folder = UUID.randomUUID()
    private val item = UUID.randomUUID()

    @Test
    fun `nothing on screen means the key belongs to the system`() {
        assertNull(VaultListUiState().resolveListBack(initialFilter = null))
    }

    @Test
    fun `a search clears before anything else it was applied on top of`() {
        val state = VaultListUiState(
            searchQuery = "beta",
            showFavoritesOnly = true,
            folderStack = listOf(folder),
        )
        assertEquals(ListBackAction.ClearSearch, state.resolveListBack(initialFilter = null))
    }

    @Test
    fun `filter tokens clear next, before the folder the user walked into`() {
        val state = VaultListUiState(showFavoritesOnly = true, folderStack = listOf(folder))
        assertEquals(ListBackAction.ClearFilters, state.resolveListBack(initialFilter = null))
    }

    @Test
    fun `each token axis on its own is a narrowing Back can undo`() {
        listOf(
            VaultListUiState(showFavoritesOnly = true),
            VaultListUiState(typeFilter = ItemTypeFilter.NOTE),
            VaultListUiState(tagFilter = "work"),
            VaultListUiState(showHiddenOnly = true, revealSessionActive = true),
        ).forEach { state ->
            assertEquals(
                ListBackAction.ClearFilters,
                state.resolveListBack(initialFilter = null),
                "Back must clear $state before leaving the screen",
            )
        }
    }

    @Test
    fun `the folder is last, and then the key goes through`() {
        val inFolder = VaultListUiState(folderStack = listOf(folder))
        assertEquals(ListBackAction.LeaveFolder, inFolder.resolveListBack(initialFilter = null))
        assertNull(inFolder.copy(folderStack = emptyList()).resolveListBack(initialFilter = null))
    }

    @Test
    fun `the selection is the most recent thing and goes first`() {
        val state = VaultListUiState(
            selectedIds = setOf(item),
            searchQuery = "beta",
            showFavoritesOnly = true,
            folderStack = listOf(folder),
        )
        assertEquals(ListBackAction.ClearSelection, state.resolveListBack(initialFilter = null))
    }

    /**
     * Control, and the one case that must *not* move: on a per-type destination the filter is the
     * destination. Clearing it would strand the user on a "Logins" screen showing everything, which
     * is the reason `onEnteredList` resets to [initialFilter] rather than to null.
     */
    @Test
    fun `the destination's own type filter is not a narrowing Back may clear`() {
        val logins = VaultListUiState(typeFilter = ItemTypeFilter.LOGIN)
        assertNull(logins.resolveListBack(initialFilter = ItemTypeFilter.LOGIN))
        assertEquals(
            ListBackAction.ClearFilters,
            logins.copy(typeFilter = ItemTypeFilter.NOTE).resolveListBack(initialFilter = ItemTypeFilter.LOGIN),
            "a type the user picked on top of the destination's own is still a narrowing",
        )
    }
}

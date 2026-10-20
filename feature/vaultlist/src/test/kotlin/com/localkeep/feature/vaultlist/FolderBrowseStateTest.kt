package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the vault list means by "where am I" now that folders are containers.
 *
 * The three scope cases are the whole feature as far as this screen is concerned, and two of them
 * are counter-intuitive: the root hides filed items while browsing but shows them while searching.
 * Getting either backwards produces a list that looks like data loss, so they are pinned here
 * rather than left to the device pass.
 */
class FolderBrowseStateTest {

    private val work = Folder(UUID.randomUUID(), "Work")
    private val bank = Folder(UUID.randomUUID(), "Bank", parentId = work.id)
    private val personal = Folder(UUID.randomUUID(), "Personal")

    private fun item(folderId: UUID?) = VaultItem(
        id = UUID.randomUUID(),
        title = "item",
        folderId = folderId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "u", password = "p"),
    )

    // ---- scope predicate ----

    @Test
    fun `browsing the root lists only what is filed nowhere`() {
        val predicate = folderScopePredicate(scope = null, flatView = false, descendants = emptySet())
        assertTrue(predicate(item(null)))
        assertFalse(predicate(item(work.id)))
    }

    @Test
    fun `searching from the root stays global and matches filed items`() {
        val predicate = folderScopePredicate(scope = null, flatView = true, descendants = emptySet())
        assertTrue(predicate(item(null)))
        assertTrue(predicate(item(work.id)))
    }

    @Test
    fun `inside a folder the whole subtree matches`() {
        val predicate = folderScopePredicate(
            scope = work.id,
            flatView = false,
            descendants = setOf(work.id, bank.id),
        )
        assertTrue(predicate(item(work.id)))
        assertTrue(predicate(item(bank.id)), "a grandchild's item is not hidden by its parent folder")
        assertFalse(predicate(item(personal.id)))
        assertFalse(predicate(item(null)))
    }

    // ---- folder rows ----

    @Test
    fun `the rows are the browsed folder's direct children, name-sorted`() {
        val zebra = Folder(UUID.randomUUID(), "zebra", parentId = work.id)
        val apple = Folder(UUID.randomUUID(), "Apple", parentId = work.id)
        val state = VaultListUiState(
            folders = listOf(work, zebra, apple, bank, personal),
            folderStack = listOf(work.id),
        )
        assertEquals(listOf("Apple", "Bank", "zebra"), state.childFolders.map { it.name })
    }

    @Test
    fun `the root's rows are the root-level folders`() {
        val state = VaultListUiState(folders = listOf(work, bank, personal))
        assertEquals(listOf("Personal", "Work"), state.childFolders.map { it.name })
    }

    @Test
    fun `folder rows are hidden while a query is running`() {
        // They are navigation, not results. A folder row under a search would be claiming a match
        // the query does not have, and the search-empty state has to be able to win.
        val state = VaultListUiState(folders = listOf(work, personal), searchQuery = "bank")
        assertTrue(state.childFolders.isEmpty())
    }

    @Test
    fun `folder rows are hidden while Favorites is on`() {
        // Favorites is a flat, curated view of favourited *items*. A folder row is navigation, not
        // a favourite, so listing every root folder beside "0 items · filter: favorites" was the
        // list contradicting its own filter. With the rows gone and no favourites, the empty state
        // wins. Scoped to favourites: the type chips keep folders for navigation.
        val state = VaultListUiState(folders = listOf(work, personal), showFavoritesOnly = true)
        assertTrue(state.childFolders.isEmpty())
    }

    @Test
    fun `a type filter still shows folder rows`() {
        // The scope line above only removes folders under Favorites. A type chip narrows items but
        // leaves folders navigable — losing them here would be a regression this pins against.
        val state = VaultListUiState(folders = listOf(work, personal), typeFilter = ItemTypeFilter.LOGIN)
        assertTrue(state.childFolders.isNotEmpty())
    }

    @Test
    fun `a folder holding only subfolders is not the empty state`() {
        val state = VaultListUiState(
            folders = listOf(work, bank),
            folderStack = listOf(work.id),
            items = emptyList(),
        )
        assertTrue(state.hasOnlySubfolders)
    }

    @Test
    fun `the root is never the subfolders case, whatever it holds`() {
        // The line reads "No items in this folder" and at the root there is no folder to be in. A
        // vault whose items are all filed showed it on first unlock, over the folder strip, which
        // reads as the vault having lost them.
        val state = VaultListUiState(folders = listOf(work, personal), items = emptyList())
        assertTrue(state.childFolders.isNotEmpty())
        assertFalse(state.hasOnlySubfolders)
    }

    @Test
    fun `a folder holding nothing at all is not the subfolders case`() {
        val state = VaultListUiState(folders = listOf(work), folderStack = listOf(work.id))
        assertFalse(state.hasOnlySubfolders)
    }

    // ---- duplicate sibling names ----

    @Test
    fun `same-named siblings are disambiguated by their parent, and unique ones are not`() {
        // Legal at rest: two vaults merging by id produce exactly this, and renaming the user's own
        // data to avoid it would be worse than labelling it.
        val underWork = Folder(UUID.randomUUID(), "Bank", parentId = work.id)
        val alsoUnderWork = Folder(UUID.randomUUID(), "Bank", parentId = work.id)
        val state = VaultListUiState(
            folders = listOf(work, underWork, alsoUnderWork),
            folderStack = listOf(work.id),
        )
        assertEquals("Work", state.disambiguatorFor(underWork))

        val unique = VaultListUiState(folders = listOf(work, bank), folderStack = listOf(work.id))
        assertNull(unique.disambiguatorFor(bank))
    }

    // ---- the browse path carries the selection with it ----

    @Test
    fun `moving the browse path always clears the selection`() {
        // One rule, expressed once, because it was expressed four times and the fifth site — the
        // post-save reveal — did not get it. A selection that survives a move names rows the list
        // is no longer showing, and a bulk delete then reaches items nothing on screen names. That
        // is the failure D8 rules out, and the reveal reaches it: the selection survives both a tab
        // change and an item round-trip, so selecting inside a folder, saving a login from the
        // generator, and landing back at the root leaves a live selection pointing into the folder.
        val selection = setOf(UUID.randomUUID(), UUID.randomUUID())
        val inFolder = VaultListUiState(
            folders = listOf(work, bank),
            folderStack = listOf(work.id),
            selectedIds = selection,
        )

        assertTrue(inFolder.withBrowsePath(listOf(work.id, bank.id)).selectedIds.isEmpty())
        assertTrue(inFolder.withBrowsePath(emptyList()).selectedIds.isEmpty())
    }

    @Test
    fun `standing still keeps the selection`() {
        // Only a *move* clears it. A refresh that recomputes the same path must not drop a
        // selection the user is in the middle of building.
        val selection = setOf(UUID.randomUUID())
        val state = VaultListUiState(
            folders = listOf(work),
            folderStack = listOf(work.id),
            selectedIds = selection,
        )
        assertEquals(selection, state.withBrowsePath(listOf(work.id)).selectedIds)
    }

    // ---- transient narrowing ----

    @Test
    fun `being inside a folder counts as a transient narrowing`() {
        // Which is what makes a tab return pop back to the root, rather than leaving the user in a
        // folder they set up minutes ago looking at a third of their vault.
        assertTrue(VaultListUiState(folderStack = listOf(work.id)).hasTransientNarrowing(null))
        assertFalse(VaultListUiState().hasTransientNarrowing(null))
    }
}

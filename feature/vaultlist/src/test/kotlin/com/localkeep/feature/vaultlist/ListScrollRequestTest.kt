package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Where the list puts the viewport when the dataset under it changes.
 *
 * F-10 (device pass 2026-09-15), in two halves. Remove a type token from a list scrolled halfway and
 * the viewport stayed at whatever row now held the old index — folders, FAVORITES and ALL ITEMS all
 * above the fold (`shots/046-filter-removed-notop.png`). Save a new item and the list landed at an
 * arbitrary offset with the new row not shown (`shots/056-after-save-empty-login.png`).
 *
 * The two seams are pure and are what the screen's effects read: [narrowingSignature] decides when a
 * scan is a *new list* rather than the same one re-read, and [listRowIndexOfItem] converts a row's
 * place among the items into its place in the `LazyColumn` that also draws the folders.
 */
class ListScrollRequestTest {

    private val folder = UUID.randomUUID()

    private fun item(folderId: UUID? = null) = VaultItem(
        id = UUID.randomUUID(),
        title = "item",
        folderId = folderId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "u", password = "p"),
    )

    // ---- when the viewport must reset ----

    @Test
    fun `removing a filter token is a new list`() {
        val filtered = VaultListUiState(typeFilter = ItemTypeFilter.NOTE)
        assertNotEquals(
            filtered.narrowingSignature(),
            filtered.copy(typeFilter = null).narrowingSignature(),
        )
    }

    @Test
    fun `every narrowing axis counts, including the browse path`() {
        val base = VaultListUiState()
        listOf(
            base.copy(typeFilter = ItemTypeFilter.NOTE),
            base.copy(showFavoritesOnly = true),
            base.copy(tagFilter = "work"),
            base.copy(showHiddenOnly = true),
            base.copy(searchQuery = "beta"),
            base.copy(folderStack = listOf(folder)),
        ).forEach { changed ->
            assertNotEquals(
                base.narrowingSignature(),
                changed.narrowingSignature(),
                "$changed shows a different list and must put the viewport back at the top",
            )
        }
    }

    /**
     * Control, and the half that matters most: a save landing, or a background re-read, must not
     * throw away the place a reader had scrolled to. Only the narrowing is in the signature.
     */
    @Test
    fun `a scan that only changed the items leaves the viewport alone`() {
        val before = VaultListUiState(typeFilter = ItemTypeFilter.LOGIN, items = listOf(item()))
        val after = before.copy(
            items = before.items + item(),
            projectionVersion = before.projectionVersion + 1L,
            scopeItemCount = 2,
        )
        assertEquals(before.narrowingSignature(), after.narrowingSignature())
    }

    // ---- where a revealed row actually is ----

    @Test
    fun `the folder rows above the results shift every row index`() {
        assertEquals(
            3,
            listRowIndexOfItem(itemIndex = 0, foldersShown = 3, folderOverflowRow = false, noItemsRow = false),
            "the first item sits below three folder rows, not at index 0",
        )
    }

    @Test
    fun `the fold's own control counts as a row too`() {
        assertEquals(
            6,
            listRowIndexOfItem(itemIndex = 2, foldersShown = 3, folderOverflowRow = true, noItemsRow = false),
        )
    }

    /** Control: with nothing above the results the two indices are the same number. */
    @Test
    fun `a list with no folders needs no adjustment`() {
        assertEquals(
            7,
            listRowIndexOfItem(itemIndex = 7, foldersShown = 0, folderOverflowRow = false, noItemsRow = false),
        )
    }
}

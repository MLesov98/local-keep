package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which rows are *here*, and which are further down.
 *
 * A folder list has always shown its whole subtree, which is the right default — a parent reading
 * "nothing here" while holding forty items one level down sends people looking for data they still
 * have. What it never said was which of those rows actually lived in the folder being browsed: the
 * only cue was a trail on the row, so a row with no trail and a row with one sat in the same
 * undifferentiated run. Asked for on 2026-08-18 as a labelled section at the foot of the list.
 *
 * These pin the split itself. Where the label lands is a layout fact and is verified on a device.
 */
class SubfolderSectionTest {

    private val work = Folder(id = UUID.randomUUID(), name = "Work")
    private val invoices = Folder(id = UUID.randomUUID(), name = "Invoices", parentId = work.id)
    private val paid = Folder(id = UUID.randomUUID(), name = "Paid", parentId = invoices.id)
    private val tree = listOf(work, invoices, paid)

    private fun item(title: String, folder: Folder?, favorite: Boolean = false) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        content = Note("x"),
        folderId = folder?.id,
        favorite = favorite,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private val here = item("Payroll", work)
    private val oneDown = item("March", invoices)
    private val twoDown = item("February", paid)

    private fun browsingWork(
        query: String = "",
        favouritesOnly: Boolean = false,
        type: ItemTypeFilter? = null,
    ) = VaultListUiState(
        items = listOf(here, oneDown, twoDown),
        folders = tree,
        folderStack = listOf(work.id),
        searchQuery = query,
        showFavoritesOnly = favouritesOnly,
        typeFilter = type,
    )

    @Test
    fun `only the items in the browsed folder are above the break`() {
        assertEquals(listOf(here), browsingWork().directItems)
    }

    @Test
    fun `everything deeper in the subtree falls into the section, at any depth`() {
        // Two levels down as well as one: the section is "not here", not "one below here".
        assertEquals(listOf(oneDown, twoDown), browsingWork().nestedItems)
    }

    @Test
    fun `the rendered order is the browsed folder first, then the rest`() {
        assertEquals(listOf(here, oneDown, twoDown), browsingWork().orderedItems)
    }

    @Test
    fun `every item is rendered exactly once`() {
        // The split is a partition, not a filter. A row landing in neither group would vanish from a
        // screen whose whole job is to show it.
        val state = browsingWork()
        assertEquals(state.items.size, state.orderedItems.size)
        assertEquals(state.items.map { it.id }.toSet(), state.orderedItems.map { it.id }.toSet())
    }

    @Test
    fun `a search is not split`() {
        // Results are already a cross-folder answer; grouping them by depth sorts the search by
        // something the user did not ask about.
        val searching = browsingWork(query = "ar")
        assertEquals(searching.items, searching.directItems)
        assertTrue(searching.nestedItems.isEmpty())
    }

    @Test
    fun `a favourites or type filter is not split either`() {
        assertTrue(browsingWork(favouritesOnly = true).nestedItems.isEmpty())
        assertTrue(browsingWork(type = ItemTypeFilter.LOGIN).nestedItems.isEmpty())
    }

    @Test
    fun `at the root nothing is split`() {
        // The root lists unfiled items only, so there is no "further down" to separate out — and a
        // section label there would be naming a distinction the list does not draw.
        val root = VaultListUiState(items = listOf(here, oneDown), folders = tree)
        assertEquals(root.items, root.directItems)
        assertTrue(root.nestedItems.isEmpty())
    }

    @Test
    fun `a folder holding nothing of its own is all section`() {
        val state = VaultListUiState(
            items = listOf(oneDown, twoDown),
            folders = tree,
            folderStack = listOf(work.id),
        )
        assertTrue(state.directItems.isEmpty())
        assertEquals(listOf(oneDown, twoDown), state.nestedItems)
    }
}

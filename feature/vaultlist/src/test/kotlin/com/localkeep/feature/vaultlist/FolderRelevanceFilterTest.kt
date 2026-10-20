package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The vault list's two folder surfaces under a type filter.
 *
 * Measured on a device 2026-08-20 before the fix: with the "2FA" chip on and no 2FA item anywhere in
 * the vault, the header read "0 items · 4 folders" and the strip offered all four. Every one of them
 * was a dead end. These assertions fail against that behaviour and pass after it.
 */
class FolderRelevanceFilterTest {

    private val archive = Folder(UUID.randomUUID(), "Archive")
    private val receipts = Folder(UUID.randomUUID(), "Receipts")
    private val nested = Folder(UUID.randomUUID(), "Nested", parentId = receipts.id)
    private val folders = listOf(archive, receipts, nested)

    private fun state(relevant: Set<UUID>?) = VaultListUiState(
        folders = folders,
        typeFilter = relevant?.let { ItemTypeFilter.TOTP },
        relevantFolderIds = relevant,
    )

    @Test
    fun `no type filter leaves every root folder on the strip`() {
        assertEquals(listOf("Archive", "Receipts"), state(null).rootFolders.map { it.name }.sorted())
    }

    @Test
    fun `a type filter keeps only the folders the predicate admits`() {
        val s = state(setOf(receipts.id, nested.id))
        assertEquals(listOf("Receipts"), s.rootFolders.map { it.name })
    }

    @Test
    fun `a type filter matching nothing empties the strip rather than showing all of it`() {
        assertEquals(emptyList(), state(emptySet()).rootFolders)
    }

    @Test
    fun `child rows inside a folder obey the same set`() {
        val s = state(setOf(receipts.id, nested.id)).copy(folderStack = listOf(receipts.id))
        assertEquals(listOf("Nested"), s.childFolders.map { it.name })
        val pruned = state(setOf(receipts.id)).copy(folderStack = listOf(receipts.id))
        assertEquals(emptyList(), pruned.childFolders)
    }
}

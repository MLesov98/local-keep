package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The folder filter's state rules, which are what stop the strip stranding the user.
 *
 * Folders were write-only until 2026-07-31 — filing an item into one changed nothing any screen
 * showed. The strip above the list is the surface that fixed it, and the failure mode it introduces
 * is a filter pointing at a folder that is no longer there: it matches nothing, and the chip that
 * would explain why has gone with it, so the list reads as an empty vault.
 *
 * These cover [VaultListUiState] rather than the ViewModel because the ViewModel needs a repository
 * and an unlocked vault; the reconciliation itself is a pure function of state and is tested here.
 * The end-to-end path stays a device concern.
 */
class FolderFilterStateTest {

    private val finance = Folder(id = UUID.randomUUID(), name = "Finance")
    private val travel = Folder(id = UUID.randomUUID(), name = "Travel")

    @Test
    fun `activeFolder resolves the folder being browsed`() {
        val state = VaultListUiState(folders = listOf(finance, travel), folderStack = listOf(travel.id))
        assertEquals(travel, state.activeFolder)
    }

    @Test
    fun `activeFolder is null at the root`() {
        assertNull(VaultListUiState(folders = listOf(finance)).activeFolder)
    }

    @Test
    fun `activeFolder is null when the path points at a folder that is gone`() {
        // The state a delete leaves behind for the instant before loadFolders reconciles it. The
        // breadcrumb reads activeFolder, so it must not throw or claim a location here.
        val state = VaultListUiState(folders = listOf(finance), folderStack = listOf(travel.id))
        assertNull(state.activeFolder)
    }

    @Test
    fun `the breadcrumb is the resolvable part of the path, root first`() {
        val nested = travel.copy(parentId = finance.id)
        val state = VaultListUiState(
            folders = listOf(finance, nested),
            folderStack = listOf(finance.id, nested.id),
        )
        assertEquals(listOf(finance, nested), state.breadcrumb)
    }

    @Test
    fun `the default state browses the root`() {
        // Per-session by design — see the KDoc on folderStack. A default of anything but the root
        // would mean opening the vault to a fraction of it.
        assertEquals(emptyList(), VaultListUiState().folderStack)
    }
}

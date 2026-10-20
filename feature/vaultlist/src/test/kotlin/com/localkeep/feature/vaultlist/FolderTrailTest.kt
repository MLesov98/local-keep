package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a nested item actually lives, said on its row.
 *
 * A folder list shows its whole subtree, so browsing one folder surfaces items that live several
 * levels below it — and the row named only the folder immediately holding one. That name appears
 * nowhere else on the screen, so it identified a place the user could neither see nor reach.
 * Reported from a phone on 2026-08-18: *"the bottom one has an entry, when I enter the first folder
 * I see it but I don't see the path to it"*.
 */
class FolderTrailTest {

    private val ellipsis = "…"

    private val l1 = Folder(id = UUID.randomUUID(), name = "L1")
    private val l2 = Folder(id = UUID.randomUUID(), name = "L2", parentId = l1.id)
    private val l3 = Folder(id = UUID.randomUUID(), name = "L3", parentId = l2.id)
    private val l4 = Folder(id = UUID.randomUUID(), name = "L4", parentId = l3.id)
    private val l5 = Folder(id = UUID.randomUUID(), name = "L5", parentId = l4.id)
    private val tree = listOf(l1, l2, l3, l4, l5)

    private fun item(folder: Folder?) = VaultItem(
        id = UUID.randomUUID(),
        title = "Amazon",
        content = Note("x"),
        folderId = folder?.id,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun browsing(vararg stack: Folder) =
        VaultListUiState(folders = tree, folderStack = stack.map { it.id })

    @Test
    fun `an item one level down names that level`() {
        assertEquals("L2", browsing(l1).folderTrailFor(item(l2), ellipsis))
    }

    @Test
    fun `an item three levels down spells all three out`() {
        assertEquals("L2 / L3 / L4", browsing(l1).folderTrailFor(item(l4), ellipsis))
    }

    @Test
    fun `a longer path keeps the ends and elides the middle`() {
        // The two halves a person reads: the branch it went down, and the folder it is in.
        assertEquals("L2 / … / L5", browsing(l1).folderTrailFor(item(l5), ellipsis))
    }

    @Test
    fun `an item in the folder being browsed says nothing`() {
        // The breadcrumb above already names it; repeating it on every row spends the row's
        // narrowest column restating the screen's own title.
        assertNull(browsing(l3).folderTrailFor(item(l3), ellipsis))
    }

    @Test
    fun `at the root the trail is the whole path`() {
        // No crumbs there, so nothing else on screen says where the item is.
        assertEquals("L1 / … / L4", VaultListUiState(folders = tree).folderTrailFor(item(l4), ellipsis))
    }

    @Test
    fun `an item in no folder has no trail`() {
        assertNull(browsing(l1).folderTrailFor(item(null), ellipsis))
    }

    @Test
    fun `an item filed in a folder that is gone has no trail`() {
        // `pathOf` returns null for a dangling parent rather than a partial path — a trail that
        // silently drops a level would name the wrong place with full confidence.
        val orphan = Folder(id = UUID.randomUUID(), name = "Gone", parentId = UUID.randomUUID())
        val state = VaultListUiState(folders = listOf(orphan), folderStack = emptyList())
        assertNull(state.folderTrailFor(item(orphan), ellipsis))
    }
}

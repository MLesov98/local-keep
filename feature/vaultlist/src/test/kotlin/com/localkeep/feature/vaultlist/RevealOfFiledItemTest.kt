package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Found 2026-10-02: an item saved into a folder, from a list that was at the root, was
 * revealed into a list that did not contain it.
 *
 * `revealItem` answered "is the item in the browsed folder?" with *yes* at the root unconditionally,
 * and with *no, go to the root* anywhere else. The root lists only unfiled items
 * ([folderScopePredicate]), so for a filed item both answers landed on a list without the row. The
 * screen's reveal effect then waited for a row that never came, `revealedItemId` stayed set, and the
 * scroll-to-top effect — which stands down while a reveal is pending — never ran again.
 *
 * Both seams are pure and pinned here: [revealBrowsePath] decides where the list goes, and
 * [withUnresolvedRevealDropped] is what ends a reveal that still cannot be satisfied (an item the
 * projection conceals, a folder trashed between the save and the arrival).
 */
class RevealOfFiledItemTest {

    private val work = Folder(UUID.randomUUID(), "Work")
    private val bank = Folder(UUID.randomUUID(), "Bank", parentId = work.id)
    private val personal = Folder(UUID.randomUUID(), "Personal")

    private fun item(folderId: UUID? = null) = VaultItem(
        id = UUID.randomUUID(),
        title = "item",
        folderId = folderId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "u", password = "p"),
    )

    // ---- where the list goes ----

    @Test
    fun `an item filed in a folder, revealed from the root, takes the list into that folder`() {
        val path = revealBrowsePath(
            current = emptyList(),
            scopeDescendants = emptySet(),
            itemFolderId = work.id,
            itemFolderPath = listOf(work),
        )

        // FAILS WITHOUT THE FIX: the root was kept, and the root does not list a filed item.
        assertEquals(listOf(work.id), path)
    }

    @Test
    fun `a nested folder is reached by its whole path, root first`() {
        val path = revealBrowsePath(
            current = emptyList(),
            scopeDescendants = emptySet(),
            itemFolderId = bank.id,
            itemFolderPath = listOf(work, bank),
        )

        assertEquals(listOf(work.id, bank.id), path)
    }

    @Test
    fun `an item filed elsewhere than the browsed folder goes to its own folder, not the root`() {
        val path = revealBrowsePath(
            current = listOf(work.id),
            scopeDescendants = setOf(work.id, bank.id),
            itemFolderId = personal.id,
            itemFolderPath = listOf(personal),
        )

        // FAILS WITHOUT THE FIX: this popped to the root, which is no more a home for a filed item.
        assertEquals(listOf(personal.id), path)
    }

    @Test
    fun `an item already inside the browsed subtree keeps the path the user is on`() {
        val current = listOf(work.id)
        val path = revealBrowsePath(
            current = current,
            scopeDescendants = setOf(work.id, bank.id),
            itemFolderId = bank.id,
            itemFolderPath = listOf(work, bank),
        )

        // The round trip the FAB makes from inside a folder: it must come back to that folder, not
        // be re-pointed at the subfolder the item happens to be in.
        assertEquals(current, path)
    }

    @Test
    fun `an unfiled item is shown at the root, from wherever the list was`() {
        val path = revealBrowsePath(
            current = listOf(work.id, bank.id),
            scopeDescendants = setOf(bank.id),
            itemFolderId = null,
            itemFolderPath = null,
        )

        assertEquals(emptyList(), path)
    }

    @Test
    fun `a folder with no live path to it falls back to the root rather than a folder nobody can open`() {
        val trashedWork = work.copy(deletedAt = Instant.EPOCH)

        assertEquals(
            emptyList(),
            revealBrowsePath(emptyList(), emptySet(), bank.id, listOf(trashedWork, bank)),
        )
        assertEquals(
            emptyList(),
            revealBrowsePath(emptyList(), emptySet(), bank.id, itemFolderPath = null),
        )
    }

    // ---- a reveal that cannot be satisfied is ended ----

    @Test
    fun `a scan that started with the reveal and found no row ends it`() {
        val wanted = item(folderId = work.id)
        val state = VaultListUiState(items = listOf(item()), revealedItemId = wanted.id)

        // FAILS WITHOUT THE FIX: nothing ended it, so the id stayed set for good.
        assertNull(state.withUnresolvedRevealDropped(revealedAtScanStart = wanted.id).revealedItemId)
    }

    @Test
    fun `a scan that did find the row leaves the reveal for the screen to play`() {
        val wanted = item()
        val state = VaultListUiState(items = listOf(item(), wanted), revealedItemId = wanted.id)

        assertEquals(wanted.id, state.withUnresolvedRevealDropped(revealedAtScanStart = wanted.id).revealedItemId)
    }

    @Test
    fun `a reveal raised after the scan began is not the scan's to end`() {
        val raisedLater = UUID.randomUUID()
        val state = VaultListUiState(items = listOf(item()), revealedItemId = raisedLater)

        assertEquals(raisedLater, state.withUnresolvedRevealDropped(revealedAtScanStart = null).revealedItemId)
        assertEquals(
            raisedLater,
            state.withUnresolvedRevealDropped(revealedAtScanStart = UUID.randomUUID()).revealedItemId,
        )
    }
}

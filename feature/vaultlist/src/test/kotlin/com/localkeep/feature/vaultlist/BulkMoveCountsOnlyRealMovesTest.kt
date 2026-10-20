package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.LkNotices
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A bulk "Move to folder" counts the items it actually re-filed, and says "Nothing changed" when it
 * re-filed none.
 *
 * Device pass 2026-10-04 (foss release, API 35): two top-level items moved to "No folder" announced
 * "2 items removed from folder", and two items already in My Folder moved to My Folder announced
 * "2 items moved". The no-change notice existed and was never reached, because the transform
 * stamped `updatedAt` on every selected item, so [changedIds] saw every one of them as changed —
 * and the write bumped the timestamp of items nothing had happened to.
 *
 * [bulkTag][VaultListViewModel.bulkAddTag] and [bulkTrash][VaultListViewModel.bulkTrash] already
 * return the item untouched when there is nothing to do; this pins the move to the same rule.
 */
class BulkMoveCountsOnlyRealMovesTest {

    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val myFolder = UUID.randomUUID()
    private val noChangeNotice = "Nothing changed — every selected item was already that way"
    private val movedId = 0x7f120001

    private fun item(folderId: UUID?) = VaultItem(
        id = UUID.randomUUID(),
        title = "x",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Note("x"),
        folderId = folderId,
    )

    private fun noticeFor(items: List<VaultItem>, target: UUID?): LkNotices.Message {
        val changed = changedIds(items, items.mapTo(mutableSetOf()) { it.id }, bulkMoveTransform(target, now))
        return bulkNotice(changed.size, noChangeNotice) { n -> LkNotices.Message.Plural(movedId, n) }
    }

    @Test
    fun `moving top-level items to no folder changes nothing and says so`() {
        assertEquals(
            LkNotices.Message.Text(noChangeNotice),
            noticeFor(listOf(item(null), item(null)), target = null),
            "two items already at the top level were reported as removed from a folder",
        )
    }

    @Test
    fun `moving items into the folder they are already in changes nothing and says so`() {
        assertEquals(
            LkNotices.Message.Text(noChangeNotice),
            noticeFor(listOf(item(myFolder), item(myFolder)), target = myFolder),
            "two items already in the folder were reported as moved",
        )
    }

    @Test
    fun `a partial move counts only the items that moved`() {
        assertEquals(
            LkNotices.Message.Plural(movedId, 1),
            noticeFor(listOf(item(myFolder), item(null)), target = myFolder),
        )
    }

    /** The control: a real move is still a change, and still stamped. */
    @Test
    fun `an item that changes folder is re-filed and stamped`() {
        val moving = item(null)
        val moved = bulkMoveTransform(myFolder, now)(moving)
        assertEquals(myFolder, moved.folderId)
        assertEquals(now, moved.updatedAt)
    }

    @Test
    fun `an item already in place keeps its timestamp`() {
        val staying = item(myFolder)
        assertEquals(staying, bulkMoveTransform(myFolder, now)(staying))
    }
}

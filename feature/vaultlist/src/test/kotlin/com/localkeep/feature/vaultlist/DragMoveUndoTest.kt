package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Found 2026-10-02: Undo of a drag-move put items back into a folder that had been
 * trashed while the notice was still up.
 *
 * Drag X out of folder A into B; the "moved · Undo" notice stands for eight seconds; trash A from
 * the drawer's Folders screen (which posts no notice, so nothing replaces this one); tap Undo. The
 * undo filed X under A again — live, in a trashed folder, so absent from every list that browses and
 * from Trash. `bulkRestoreTrashed` already guards the same failure and says why; this is the same
 * rule for the other undo.
 *
 * Pure, because the undo's mutator body is just this per-item rule applied over the vault.
 */
class DragMoveUndoTest {

    private val trashedFolder = UUID.randomUUID()
    private val liveFolder = UUID.randomUUID()
    private val destination = UUID.randomUUID()
    private val undoneAt = Instant.parse("2026-10-02T12:00:00Z")

    private fun item(folderId: UUID?) = VaultItem(
        id = UUID.randomUUID(),
        title = "item",
        folderId = folderId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "u", password = "p"),
    )

    @Test
    fun `undo files an item back into the folder it came from while that folder is live`() {
        val moved = item(folderId = destination)

        val undone = moved.withDragMoveUndone(
            previous = mapOf(moved.id to liveFolder),
            liveFolders = setOf(liveFolder, destination),
            at = undoneAt,
        )

        assertEquals(liveFolder, undone.folderId)
        assertEquals(undoneAt, undone.updatedAt)
    }

    @Test
    fun `undo puts an item at the root when the folder it came from has been trashed since`() {
        val moved = item(folderId = destination)

        val undone = moved.withDragMoveUndone(
            previous = mapOf(moved.id to trashedFolder),
            liveFolders = setOf(destination),
            at = undoneAt,
        )

        // FAILS WITHOUT THE FIX: the item went back under the trashed folder, live and unreachable.
        assertEquals(null, undone.folderId)
        assertEquals(undoneAt, undone.updatedAt)
    }

    @Test
    fun `an item that came from the root goes back to the root, whatever folders are live`() {
        val moved = item(folderId = destination)

        val undone = moved.withDragMoveUndone(
            previous = mapOf(moved.id to null),
            liveFolders = emptySet(),
            at = undoneAt,
        )

        assertEquals(null, undone.folderId)
    }

    @Test
    fun `an item the drag never moved is returned untouched`() {
        val bystander = item(folderId = destination)

        val undone = bystander.withDragMoveUndone(
            previous = mapOf(UUID.randomUUID() to liveFolder),
            liveFolders = setOf(liveFolder, destination),
            at = undoneAt,
        )

        assertSame(bystander, undone)
    }

    @Test
    fun `an item already where undo would put it keeps its timestamp`() {
        val home = item(folderId = liveFolder)

        val undone = home.withDragMoveUndone(
            previous = mapOf(home.id to liveFolder),
            liveFolders = setOf(liveFolder),
            at = undoneAt,
        )

        assertSame(home, undone)
    }

    @Test
    fun `an item already at the root stays put when its old folder is gone`() {
        val atRoot = item(folderId = null)

        val undone = atRoot.withDragMoveUndone(
            previous = mapOf(atRoot.id to trashedFolder),
            liveFolders = emptySet(),
            at = undoneAt,
        )

        // The fallback lands on the root, which is where it already is: not a change, so no restamp.
        assertSame(atRoot, undone)
    }
}

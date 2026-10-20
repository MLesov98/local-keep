package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test (2026-08-31): the bulk-trash confirm dialog says "this can
 * be undone", but until this fix `bulkEdit` posted the notice with no undo action at all — the
 * only way back from a bulk trash was one restore per item through the Trash screen, each its own
 * full-vault re-encrypt (`TrashViewModel.restore`).
 *
 * The fix wires `VaultListViewModel.bulkTrash`'s notice to an undo that restores, in one commit,
 * exactly the ids [changedIds] says the trash transform actually touched — see
 * `VaultListViewModel.bulkRestoreTrashed`. [changedIds] is the part of that fix worth pinning at
 * this level: it is what stops the undo (or the on-screen count) from including ids a transform
 * left alone, matching how [BulkNoticeTest] pins `bulkNotice` without needing the ViewModel's
 * repository or coroutine — see `SelectionSurvivesFilterTest`'s note on why that boundary is
 * where the unit tests in this file stop.
 */
class BulkTrashUndoTest {

    private fun item(deletedAt: Instant? = null) = VaultItem(
        id = UUID.randomUUID(),
        title = "x",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Note("x"),
        deletedAt = deletedAt,
    )

    // Mirrors VaultListViewModel.bulkTrash's transform.
    private val trash: (VaultItem) -> VaultItem = { i ->
        if (i.deletedAt != null) i else i.copy(updatedAt = Instant.EPOCH, deletedAt = Instant.EPOCH)
    }

    // Mirrors VaultListViewModel.bulkRestoreTrashed's transform.
    private val restore: (VaultItem) -> VaultItem = { i ->
        if (i.deletedAt == null) i else i.copy(updatedAt = Instant.EPOCH, deletedAt = null)
    }

    @Test
    fun `the undo set is exactly what bulkTrash touched, not the raw selection`() {
        val live = item()
        val alreadyTrashed = item(deletedAt = Instant.EPOCH)
        val unselected = item()
        val touched = changedIds(
            items = listOf(live, alreadyTrashed, unselected),
            targetIds = setOf(live.id, alreadyTrashed.id),
            transform = trash,
        )

        // FAILS WITHOUT THE FIX: an undo built from the raw selection (setOf(live.id,
        // alreadyTrashed.id)) would restore alreadyTrashed too — un-trashing an item this bulk
        // trash never touched, and that was already correctly left alone by bulkEdit's own
        // already-trashed guard.
        assertEquals(setOf(live.id), touched)
    }

    @Test
    fun `an id nothing selected is never in the touched set`() {
        val selected = item()
        val notSelected = item()
        val touched = changedIds(
            items = listOf(selected, notSelected),
            targetIds = setOf(selected.id),
            transform = trash,
        )
        assertEquals(setOf(selected.id), touched)
    }

    @Test
    fun `restoring the touched set skips ids a per-item restore already reached`() {
        val trashedByThisAction = item(deletedAt = Instant.EPOCH)
        // A restore from the Trash screen beat the undo tap to this one.
        val restoredAlready = item()
        val touched = changedIds(
            items = listOf(trashedByThisAction, restoredAlready),
            targetIds = setOf(trashedByThisAction.id, restoredAlready.id),
            transform = restore,
        )

        // FAILS WITHOUT THE FIX: restoring blindly by id (rather than gating on deletedAt via the
        // transform, as bulkRestoreTrashed does) would report restoredAlready as touched too.
        assertEquals(setOf(trashedByThisAction.id), touched)
    }

    @Test
    fun `nothing selected means nothing touched`() {
        assertEquals(emptySet(), changedIds(items = listOf(item()), targetIds = emptySet(), transform = trash))
    }
}

package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the count line under the title counts, against a vault that has things filed in folders.
 *
 * F-31 (device pass 2026-09-15): the header counted [VaultListUiState.items], which at the root is
 * only what is filed nowhere, so a 58-item vault with 13 items in folders announced itself as
 * "45 items · 5 folders". It also made the import wizard look like a liar — the wizard reported 16
 * new items and the header moved 41 → 45. The principal's ruling on 2026-09-16 is that the root
 * counts the whole vault and a folder counts its own subtree.
 *
 * Driven through the same two pieces `scanAndPublish` composes — [folderScopePredicate] for the
 * rows, [headerItemCount] for the number — so the test fails for the reason the screen would,
 * rather than restating `headerItemCount`'s own `if`.
 */
class HeaderItemCountTest {

    private val personal = Folder(UUID.randomUUID(), "Personal")
    private val work = Folder(UUID.randomUUID(), "Work")
    private val receipts = Folder(UUID.randomUUID(), "Receipts", parentId = work.id)

    private fun item(folderId: UUID?) = VaultItem(
        id = UUID.randomUUID(),
        title = "item",
        folderId = folderId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "u", password = "p"),
    )

    /** 4 unfiled, 2 in Personal, 2 in Work, 1 in Work/Receipts — nine in all. */
    private val vault: List<VaultItem> = buildList {
        repeat(4) { add(item(null)) }
        repeat(2) { add(item(personal.id)) }
        repeat(2) { add(item(work.id)) }
        add(item(receipts.id))
    }

    private fun count(scope: UUID?, descendants: Set<UUID>, flatView: Boolean = false): Int {
        val scoped = vault.filter(folderScopePredicate(scope, flatView, descendants))
        return headerItemCount(scope, vault.size, scoped.size)
    }

    @Test
    fun `at the root the header counts the whole vault, not the unfiled rows`() {
        val rows = vault.filter(folderScopePredicate(null, flatView = false, descendants = emptySet()))
        assertEquals(4, rows.size, "the root still lists only unfiled items — that part must not change")
        assertEquals(
            9,
            count(scope = null, descendants = emptySet()),
            "the header must count every item the vault holds, filed or not (F-31)",
        )
    }

    @Test
    fun `inside a folder the header counts that folder's subtree`() {
        assertEquals(
            3,
            count(scope = work.id, descendants = setOf(work.id, receipts.id)),
            "Work holds two of its own plus one in Receipts",
        )
        assertEquals(2, count(scope = personal.id, descendants = setOf(personal.id)))
    }

    @Test
    fun `an empty folder still counts its subtree, not the vault`() {
        val empty = Folder(UUID.randomUUID(), "Empty")
        assertEquals(0, count(scope = empty.id, descendants = setOf(empty.id)))
    }

    /**
     * Control: searching from the root already saw the whole vault, and the fix must not change it.
     * If this and the root case were both wired to `narrowedCount` by accident, this still passes —
     * which is the point: it pins the behaviour that was already right.
     */
    @Test
    fun `searching from the root counts every match, as it always did`() {
        assertEquals(9, count(scope = null, descendants = emptySet(), flatView = true))
    }
}

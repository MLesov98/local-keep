package com.localkeep.core.vault

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The number in the Empty Trash confirmation must be the number Empty Trash destroys.
 *
 * That sentence is Tier A copy on the only irreversible control in the app, and it was wrong. The
 * screen derived it as `flat rows + each trashed-folder row's item count`, which is exact only while
 * every trashed item is represented by one of those two. Nested trashing breaks the premise:
 *
 *  - `VaultVisibility.trashedFolderRows` admits a folder only if its parent is not itself trashed,
 *    so a folder trashed *inside* an already-trashed parent gets no row.
 *  - `VaultRepository.trashFolder` retags only records with `deletedAt == null`, so that inner
 *    folder keeps its own `trashRootId` and its items keep `trashedWithFolderId` pointing at it —
 *    they are not re-homed onto the outer root.
 *  - `planTrashPurge(PurgeScope.AllTrash)` is a predicate over *everything* deleted, so it takes
 *    them anyway.
 *
 * Trash a folder, then trash its parent, then Empty Trash: the dialog said "This permanently removes
 * all 0 items" and five items were destroyed. Found 2026-09-03.
 *
 * The control is [`a flat trash still counts every item`]: it must stay green, or a change that
 * simply returned some larger number would read as a pass.
 */
class PurgeableTrashCountTest {

    private val now: Instant = Instant.parse("2026-09-03T09:00:00Z")

    private fun folder(
        name: String,
        parentId: UUID? = null,
        trashed: Boolean = false,
        trashRootId: UUID? = null,
    ) = Folder(
        id = UUID.randomUUID(),
        name = name,
        parentId = parentId,
        updatedAt = now,
        deletedAt = if (trashed) now else null,
        trashRootId = trashRootId,
    )

    private fun item(title: String, folderId: UUID? = null, trashedWith: UUID? = null, trashed: Boolean = false) =
        VaultItem(
            id = UUID.randomUUID(),
            title = title,
            folderId = folderId,
            createdAt = now,
            updatedAt = now,
            deletedAt = if (trashed || trashedWith != null) now else null,
            trashedWithFolderId = trashedWith,
            content = Note(body = "x"),
        )

    private fun project(vault: Vault) =
        VaultVisibility.project(vault, now = now, revealSessionActive = false)

    @Test
    fun `a folder trashed inside an already-trashed folder is still counted`() {
        // Bank was trashed first, so it keeps trashRootId = Bank and its items point at Bank.
        // Work was trashed afterwards and did not retag Bank, exactly as trashFolder behaves.
        val work = folder("Work", trashed = true, trashRootId = null)
        val bank = folder("Bank", parentId = work.id, trashed = true, trashRootId = null)
        val nested = (1..5).map { item("Statement $it", folderId = bank.id, trashedWith = bank.id) }

        val vault = Vault(
            items = nested,
            folders = listOf(work, bank),
            createdAt = now,
            modifiedAt = now,
        )

        val snapshot = project(vault)

        // The screen genuinely cannot show these: Bank is not a root, so it has no row, and Work's
        // row counts only items whose trashedWithFolderId is Work. Both of those stay true — the fix
        // is that the *count* no longer comes from them.
        assertEquals(1, snapshot.trashedFolderRows.size, "expected only Work to be a trash root")
        assertEquals(0, snapshot.trashedFolderRows.single().itemCount, "Work's row legitimately holds no items")
        assertEquals(0, snapshot.trashItems.size, "these items are not flat rows either")

        assertEquals(
            5,
            snapshot.purgeableTrashItemCount,
            "Empty Trash destroys these five, so the confirmation has to say five",
        )
    }

    @Test
    fun `a flat trash still counts every item`() {
        val vault = Vault(
            items = listOf(item("A", trashed = true), item("B", trashed = true), item("Live")),
            folders = emptyList(),
            createdAt = now,
            modifiedAt = now,
        )

        assertEquals(2, project(vault).purgeableTrashItemCount)
    }

    @Test
    fun `a single trashed folder's items are counted once, not twice`() {
        // The row can show them AND the count includes them; the risk in counting from the vault is
        // double-counting, not under-counting. Pinned so a future "add the rows back in" cannot pass.
        val work = folder("Work", trashed = true)
        val vault = Vault(
            items = (1..3).map { item("Doc $it", folderId = work.id, trashedWith = work.id) },
            folders = listOf(work),
            createdAt = now,
            modifiedAt = now,
        )

        val snapshot = project(vault)

        assertEquals(3, snapshot.trashedFolderRows.single().itemCount)
        assertEquals(3, snapshot.purgeableTrashItemCount)
    }

    @Test
    fun `live items are never counted`() {
        val vault = Vault(
            items = listOf(item("Live A"), item("Live B")),
            folders = emptyList(),
            createdAt = now,
            modifiedAt = now,
        )

        assertEquals(0, project(vault).purgeableTrashItemCount)
    }
}

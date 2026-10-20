package com.localkeep.core.vault

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where an individually-trashed item lands when it comes back.
 *
 * `FolderTrashTest.trashLeavesAnItemAlreadyInTheTrashAlone` pins the state that sets this up — an
 * item trashed on its own keeps its `folderId` and gets no `trashedWithFolderId`, so trashing its
 * folder afterwards leaves it untouched — but nothing followed it through to the restore. It came
 * back live, filed inside a folder the vault list cannot browse into, right after an "Item
 * restored" notice: gone, by every means the user has of looking for it.
 *
 * Unit rather than instrumented because the arithmetic is a pure function over the vault's two
 * lists; the repository's own restore is a one-line call to it.
 */
class RestoreFromTrashFolderTest {

    private val now: Instant = Instant.parse("2026-08-31T09:00:00Z")
    private val later: Instant = now.plusSeconds(60)

    private fun folder(name: String, parentId: UUID? = null, trashed: Boolean = false) =
        Folder(
            id = UUID.randomUUID(),
            name = name,
            parentId = parentId,
            deletedAt = if (trashed) now else null,
        )

    private fun trashedItem(folderId: UUID?) =
        VaultItem(
            id = UUID.randomUUID(),
            title = "Bank login",
            createdAt = now,
            updatedAt = now,
            folderId = folderId,
            content = Note("n"),
            deletedAt = now,
        )

    private fun vault(folders: List<Folder>, items: List<VaultItem>) =
        Vault(items = items, folders = folders, createdAt = now, modifiedAt = now)

    @Test
    fun restoringIntoATrashedFolderLandsAtTheNearestLiveAncestor() {
        val work = folder("Work")
        val bank = folder("Bank", parentId = work.id, trashed = true)
        val item = trashedItem(bank.id)

        val restored = vault(listOf(work, bank), listOf(item))
            .withItemRestoredFromTrash(item.id, later)
            .items
            .single()

        assertNull(restored.deletedAt)
        assertEquals(
            work.id,
            restored.folderId,
            "restoring into the trashed Bank puts a live item where nothing can show it",
        )
    }

    @Test
    fun restoringWhenNothingAboveItIsLiveLandsAtTheVaultRoot() {
        val work = folder("Work", trashed = true)
        val bank = folder("Bank", parentId = work.id, trashed = true)
        val item = trashedItem(bank.id)

        val restored = vault(listOf(work, bank), listOf(item))
            .withItemRestoredFromTrash(item.id, later)
            .items
            .single()

        assertNull(restored.deletedAt)
        assertNull(restored.folderId, "the root is the one destination that is always reachable")
    }

    @Test
    fun restoringIntoAFolderThisVaultNoLongerHasLandsAtTheVaultRoot() {
        val item = trashedItem(UUID.randomUUID())

        val restored = vault(emptyList(), listOf(item))
            .withItemRestoredFromTrash(item.id, later)
            .items
            .single()

        assertNull(restored.deletedAt)
        assertNull(restored.folderId)
    }

    @Test
    fun restoringIntoALiveFolderLeavesTheItemExactlyWhereItWas() {
        // The control. A fix that simply unfiled everything on the way out of the trash would pass
        // the three cases above and silently empty every folder the user restores from.
        val work = folder("Work")
        val bank = folder("Bank", parentId = work.id)
        val item = trashedItem(bank.id)

        val restored = vault(listOf(work, bank), listOf(item))
            .withItemRestoredFromTrash(item.id, later)
            .items
            .single()

        assertNull(restored.deletedAt)
        assertEquals(bank.id, restored.folderId)
    }
}

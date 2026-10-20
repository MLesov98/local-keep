package com.localkeep.core.vault

import com.localkeep.core.model.Folder
import com.localkeep.core.model.HiddenLease
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultVisibilityTest {
    private val now = Instant.parse("2026-08-18T12:00:00Z")
    private val future = now.plusSeconds(3600)
    private val past = now.minusSeconds(3600)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

    private fun item(
        n: Int,
        folderId: UUID? = null,
        deleted: Boolean = false,
        trashedWithFolderId: UUID? = null,
        hiddenUntil: Instant? = null,
    ) = VaultItem(
        id = id(n),
        title = "item$n",
        folderId = folderId,
        createdAt = now,
        updatedAt = now,
        content = Note("n"),
        deletedAt = if (deleted) now else null,
        trashedWithFolderId = trashedWithFolderId,
        hiddenUntil = hiddenUntil,
        hiddenUpdatedAt = if (hiddenUntil != null) now else null,
    )

    private fun folder(
        n: Int,
        parentId: UUID? = null,
        deleted: Boolean = false,
        hiddenUntil: Instant? = null,
    ) = Folder(
        id = id(n),
        name = "folder$n",
        parentId = parentId,
        deletedAt = if (deleted) now else null,
        trashRootId = if (deleted) id(n) else null,
        trashUpdatedAt = if (deleted) now else null,
        hiddenUntil = hiddenUntil,
        hiddenUpdatedAt = if (hiddenUntil != null) now else null,
    )

    private fun vault(items: List<VaultItem> = emptyList(), folders: List<Folder> = emptyList()) =
        Vault(items = items, folders = folders, createdAt = now, modifiedAt = now)

    @Test
    fun `a directly hidden item is concealed and absent from the live list`() {
        val v = vault(items = listOf(item(1), item(2, hiddenUntil = future)))
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        assertEquals(setOf(id(2)), snap.concealedItemIds)
        assertEquals(listOf(id(1)), snap.liveItems.map { it.id })
    }

    @Test
    fun `a hidden folder conceals its whole subtree`() {
        val v = vault(
            items = listOf(item(10, folderId = id(1)), item(11, folderId = id(2))),
            folders = listOf(folder(1, hiddenUntil = future), folder(2, parentId = id(1))),
        )
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        assertEquals(setOf(id(1), id(2)), snap.concealedFolderIds)
        assertEquals(setOf(id(10), id(11)), snap.concealedItemIds)
        assertTrue(snap.liveItems.isEmpty())
        assertTrue(snap.liveFolders.isEmpty())
    }

    @Test
    fun `an item moved out of a hidden folder is visible again`() {
        val v = vault(
            items = listOf(item(10, folderId = null), item(11, folderId = id(1))),
            folders = listOf(folder(1, hiddenUntil = future)),
        )
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        assertEquals(setOf(id(11)), snap.concealedItemIds)
        assertEquals(listOf(id(10)), snap.liveItems.map { it.id })
    }

    @Test
    fun `directly hidden sets exclude ancestry-only descendants`() {
        // folder 1 is directly hidden; item 11 lives in it (concealed by ancestry) and item 2 has its
        // own lease. Only the leased records are "directly hidden" — the row that may offer Stop hiding.
        val v = vault(
            items = listOf(item(11, folderId = id(1)), item(2, hiddenUntil = future)),
            folders = listOf(folder(1, hiddenUntil = future), folder(3, parentId = id(1))),
        )
        val snap = VaultVisibility.project(v, now, revealSessionActive = true)
        assertEquals(setOf(id(2), id(11)), snap.concealedItemIds)
        assertEquals(setOf(id(2)), snap.directlyHiddenItemIds, "item 11 is ancestry-only")
        assertEquals(setOf(id(1), id(3)), snap.concealedFolderIds)
        assertEquals(setOf(id(1)), snap.directlyHiddenFolderIds, "folder 3 is ancestry-only")
    }

    @Test
    fun `an indefinite lease conceals and never contributes a next expiry`() {
        val v = vault(items = listOf(item(1, hiddenUntil = HiddenLease.INDEFINITE)))
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        assertEquals(setOf(id(1)), snap.concealedItemIds)
        assertNull(snap.nextExpiry)
    }

    @Test
    fun `an expired lease is visible at exactly the boundary`() {
        val v = vault(items = listOf(item(1, hiddenUntil = now), item(2, hiddenUntil = past)))
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        assertTrue(snap.concealedItemIds.isEmpty(), "until == now and past are visible")
        assertEquals(listOf(id(1), id(2)), snap.liveItems.map { it.id })
    }

    @Test
    fun `revealed session shows concealed records but keeps the marker sets`() {
        val v = vault(
            items = listOf(item(10, folderId = id(1)), item(2, hiddenUntil = future)),
            folders = listOf(folder(1, hiddenUntil = future)),
        )
        val snap = VaultVisibility.project(v, now, revealSessionActive = true)
        assertEquals(setOf(id(2), id(10)), snap.concealedItemIds)
        assertEquals(setOf(id(1)), snap.concealedFolderIds)
        assertEquals(setOf(id(10), id(2)), snap.liveItems.map { it.id }.toSet())
        assertEquals(listOf(id(1)), snap.liveFolders.map { it.id })
    }

    @Test
    fun `trash and live are separated and concealed trash is withheld`() {
        val v = vault(
            items = listOf(
                item(1),
                item(2, deleted = true),
                item(3, deleted = true, hiddenUntil = future),
            ),
        )
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        assertEquals(listOf(id(1)), snap.liveItems.map { it.id })
        assertEquals(listOf(id(2)), snap.trashItems.map { it.id }, "concealed trashed item withheld")
        assertTrue(id(3) in snap.concealedItemIds)
    }

    @Test
    fun `an item trashed with a folder is not a standalone trash row`() {
        val v = vault(
            items = listOf(item(10, folderId = id(1), deleted = true, trashedWithFolderId = id(1))),
            folders = listOf(folder(1, deleted = true)),
        )
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        assertTrue(snap.trashItems.isEmpty(), "represented by the folder row")
        assertEquals(1, snap.trashedFolderRows.size)
        assertEquals(1, snap.trashedFolderRows.single().itemCount)
    }

    /**
     * The counts are tallied in one pass rather than re-scanned per root, and the root is the case
     * that breaks a careless tally: `trashFolder` writes the root's own id into its `trashRootId`,
     * so a tally keyed on `trashRootId` alone already contains the root and adding it again for the
     * `id == root.id` half of the predicate counts it twice.
     */
    @Test
    fun `a trash root counts itself once alongside its descendants`() {
        val root = folder(1, deleted = true)
        val child = folder(2, parentId = id(1), deleted = true).copy(trashRootId = id(1))
        val grandchild = folder(3, parentId = id(2), deleted = true).copy(trashRootId = id(1))
        val v = vault(
            items = listOf(
                item(10, folderId = id(1), deleted = true, trashedWithFolderId = id(1)),
                item(11, folderId = id(2), deleted = true, trashedWithFolderId = id(1)),
            ),
            folders = listOf(root, child, grandchild),
        )
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        val row = snap.trashedFolderRows.single()
        assertEquals(id(1), row.folder.id, "only the root gets a row")
        assertEquals(3, row.folderCount, "root plus two descendants, each counted once")
        assertEquals(2, row.itemCount)
    }

    @Test
    fun `the tallied counts still exclude concealed members`() {
        val root = folder(1, deleted = true)
        val child = folder(2, parentId = id(1), deleted = true, hiddenUntil = future)
            .copy(trashRootId = id(1))
        val v = vault(
            items = listOf(
                item(10, folderId = id(1), deleted = true, trashedWithFolderId = id(1)),
                item(11, folderId = id(1), deleted = true, trashedWithFolderId = id(1), hiddenUntil = future),
            ),
            folders = listOf(root, child),
        )
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        val row = snap.trashedFolderRows.single()
        assertEquals(1, row.folderCount, "the concealed subfolder must not be named by the count")
        assertEquals(1, row.itemCount, "the concealed item must not be named by the count")
    }

    @Test
    fun `next expiry is the nearest finite active lease`() {
        val soon = now.plusSeconds(60)
        val later = now.plusSeconds(600)
        val v = vault(
            items = listOf(item(1, hiddenUntil = later), item(2, hiddenUntil = HiddenLease.INDEFINITE)),
            folders = listOf(folder(3, hiddenUntil = soon)),
        )
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        assertEquals(soon, snap.nextExpiry)
    }

    @Test
    fun `an unrevealed session conceals as duress always forces`() {
        // Duress never sets revealSessionActive true (reveal is refused at the lock boundary), so a
        // decoy payload carrying hidden entries projects concealed exactly as this does.
        val v = vault(items = listOf(item(1, hiddenUntil = HiddenLease.INDEFINITE)))
        val snap = VaultVisibility.project(v, now, revealSessionActive = false)
        assertFalse(snap.liveItems.any { it.id == id(1) })
    }
}

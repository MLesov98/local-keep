package com.localkeep.core.vault

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What Folders > Duplicate copies (2026-10-02, S1-01 / S1-02 and their duplicates O4-02, O4-04).
 *
 * `duplicateFolder` cloned the *raw* subtree, and two kinds of record came along that the user
 * could not see:
 *
 *  - **Concealed ones.** An item made invisible inside a visible folder — or an invisible
 *    subfolder — was copied with its lease cleared, so one menu action outside a reveal session
 *    produced a permanently visible copy of every concealed entry under that folder. That is the
 *    whole of what Make-Invisible promises not to do; `VaultVisibility`'s contract is that a hidden
 *    record is absent from every user-facing read or none, and the copy was a read.
 *  - **Trashed ones.** An individually trashed item keeps its `folderId`, and a trashed subfolder
 *    is still a descendant, so both were cloned with their trash markers intact — still naming the
 *    *original* trash root. Trash grew phantom rows, and restoring or purging the original row
 *    also acted on the copy.
 *
 * The rule now is the projection's: a duplicate copies exactly what the user is looking at. In a
 * reveal session that includes the concealed records, and Decision 8 of the make-invisible plan
 * ("Duplicates start visible") still governs how those are copied — unchanged here.
 */
class DuplicateCopiesOnlyWhatIsVisibleTest {
    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private val future = now.plusSeconds(3600)
    private val past = now.minusSeconds(3600)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

    private fun item(
        n: Int,
        folderId: UUID?,
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

    private fun folder(n: Int, parentId: UUID? = null, trashRoot: UUID? = null, hiddenUntil: Instant? = null) =
        Folder(
            id = id(n),
            name = "folder$n",
            parentId = parentId,
            deletedAt = if (trashRoot != null) now else null,
            trashRootId = trashRoot,
            trashUpdatedAt = if (trashRoot != null) now else null,
            hiddenUntil = hiddenUntil,
            hiddenUpdatedAt = if (hiddenUntil != null) now else null,
        )

    private fun vault(items: List<VaultItem>, folders: List<Folder>) =
        Vault(items = items, folders = folders, createdAt = now, modifiedAt = now)

    private fun Vault.copied(rootId: UUID, revealed: Boolean = false) =
        assertNotNull(duplicableSubtree(rootId, now, revealSessionActive = revealed), "root should be duplicable")

    @Test
    fun `an item made invisible inside a visible folder is not copied`() {
        val v = vault(
            items = listOf(item(10, id(1)), item(11, id(1), hiddenUntil = future)),
            folders = listOf(folder(1)),
        )
        assertEquals(listOf(id(10)), v.copied(id(1)).items.map { it.id })
    }

    @Test
    fun `an invisible subfolder is not copied, nor anything under it`() {
        val v = vault(
            items = listOf(item(10, id(1)), item(11, id(2)), item(12, id(3))),
            folders = listOf(folder(1), folder(2, parentId = id(1), hiddenUntil = future), folder(3, parentId = id(2))),
        )
        val copied = v.copied(id(1))
        assertEquals(listOf(id(1)), copied.folders.map { it.id })
        assertEquals(listOf(id(10)), copied.items.map { it.id })
    }

    @Test
    fun `an individually trashed item is not copied`() {
        val v = vault(
            items = listOf(item(10, id(1)), item(11, id(1), deleted = true)),
            folders = listOf(folder(1)),
        )
        assertEquals(listOf(id(10)), v.copied(id(1)).items.map { it.id })
    }

    @Test
    fun `a trashed subfolder and the items that went with it are not copied`() {
        val v = vault(
            items = listOf(item(10, id(1)), item(11, id(2), deleted = true, trashedWithFolderId = id(2))),
            folders = listOf(folder(1), folder(2, parentId = id(1), trashRoot = id(2))),
        )
        val copied = v.copied(id(1))
        assertEquals(listOf(id(1)), copied.folders.map { it.id })
        assertEquals(listOf(id(10)), copied.items.map { it.id })
    }

    /** Every cloned folder's parent is cloned too, so the parent remap can never miss. */
    @Test
    fun `everything visible in the subtree is copied, nested folders included`() {
        val v = vault(
            items = listOf(item(10, id(1)), item(11, id(2)), item(12, id(3)), item(13, null)),
            folders = listOf(folder(1), folder(2, parentId = id(1)), folder(3, parentId = id(2)), folder(4)),
        )
        val copied = v.copied(id(1))
        assertEquals(setOf(id(1), id(2), id(3)), copied.folders.map { it.id }.toSet())
        assertEquals(setOf(id(10), id(11), id(12)), copied.items.map { it.id }.toSet())
        val clonedIds = copied.folders.map { it.id }.toSet()
        copied.folders.filter { it.id != id(1) }.forEach { assertNotNull(it.parentId?.takeIf { p -> p in clonedIds }) }
    }

    @Test
    fun `an expired lease no longer conceals, so that item is copied`() {
        val v = vault(items = listOf(item(10, id(1), hiddenUntil = past)), folders = listOf(folder(1)))
        assertEquals(listOf(id(10)), v.copied(id(1)).items.map { it.id })
    }

    /**
     * Inside a reveal session the user is looking at the concealed records, so they are copied —
     * and Decision 8 governs them from there. Trash stays out regardless.
     */
    @Test
    fun `a reveal session copies what it shows, and still never the trash`() {
        val v = vault(
            items = listOf(
                item(10, id(1), hiddenUntil = future),
                item(11, id(2)),
                item(12, id(1), deleted = true),
            ),
            folders = listOf(folder(1), folder(2, parentId = id(1), hiddenUntil = future)),
        )
        val copied = v.copied(id(1), revealed = true)
        assertEquals(setOf(id(1), id(2)), copied.folders.map { it.id }.toSet())
        assertEquals(setOf(id(10), id(11)), copied.items.map { it.id }.toSet())
    }

    @Test
    fun `a root the user cannot see cannot be duplicated`() {
        val v = vault(
            items = emptyList(),
            folders = listOf(folder(1, hiddenUntil = future), folder(2, trashRoot = id(2))),
        )
        assertNull(v.duplicableSubtree(id(1), now, revealSessionActive = false), "concealed root")
        assertNull(v.duplicableSubtree(id(2), now, revealSessionActive = true), "trashed root")
        assertNull(v.duplicableSubtree(id(99), now, revealSessionActive = true), "unknown root")
    }
}

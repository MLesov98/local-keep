package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The counts must match what the old folder-by-folder scan produced, including on the broken data.
 *
 * The replacement exists for cost, not for behaviour: the previous version asked
 * `folderDescendants` once per folder and then counted every item against that set, which is
 * `O(folders × items)` on the main dispatcher inside every list refresh. Behaviour changing here
 * would be a regression, so each case is asserted against `FolderTree.descendantIds` — the function
 * the old path used — rather than against a number typed by hand.
 */
class FolderSubtreeCountsTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun folder(name: String, parent: UUID? = null) =
        Folder(id = UUID.randomUUID(), name = name, parentId = parent)

    private fun item(folderId: UUID?) = VaultItem(
        id = UUID.randomUUID(),
        title = "item",
        folderId = folderId,
        createdAt = t0,
        updatedAt = t0,
        content = Login(),
    )

    /** What the old implementation computed, kept here as the oracle for every case below. */
    private fun byDescendantScan(folders: List<Folder>, items: List<VaultItem>): Map<UUID, Int> =
        folders.associate { folder ->
            val scope = FolderTree.descendantIds(folders, folder.id)
            folder.id to items.count { it.folderId in scope }
        }

    private fun assertMatchesOldScan(folders: List<Folder>, items: List<VaultItem>) {
        assertEquals(byDescendantScan(folders, items), FolderSubtreeCounts.of(folders, items))
    }

    @Test
    fun `a parent counts everything under it, not only its own items`() {
        val root = folder("Root")
        val child = folder("Child", root.id)
        val grandchild = folder("Grandchild", child.id)
        val folders = listOf(root, child, grandchild)
        val items = listOf(item(root.id), item(child.id), item(grandchild.id), item(grandchild.id))

        val counts = FolderSubtreeCounts.of(folders, items)

        assertEquals(4, counts[root.id])
        assertEquals(3, counts[child.id])
        assertEquals(2, counts[grandchild.id])
        assertMatchesOldScan(folders, items)
    }

    @Test
    fun `a folder holding nothing reports zero rather than being absent`() {
        val empty = folder("Empty")
        val counts = FolderSubtreeCounts.of(listOf(empty), emptyList())

        assertEquals(0, counts[empty.id])
    }

    @Test
    fun `an unfiled item and one naming a folder that is gone count towards nothing`() {
        val root = folder("Root")
        val folders = listOf(root)
        val items = listOf(item(root.id), item(null), item(UUID.randomUUID()))

        assertEquals(1, FolderSubtreeCounts.of(folders, items)[root.id])
        assertMatchesOldScan(folders, items)
    }

    @Test
    fun `siblings do not count each other`() {
        val root = folder("Root")
        val left = folder("Left", root.id)
        val right = folder("Right", root.id)
        val folders = listOf(root, left, right)
        val items = listOf(item(left.id), item(left.id), item(right.id))

        val counts = FolderSubtreeCounts.of(folders, items)

        assertEquals(3, counts[root.id])
        assertEquals(2, counts[left.id])
        assertEquals(1, counts[right.id])
        assertMatchesOldScan(folders, items)
    }

    /**
     * A cyclic folder list is rejected by `FolderTree.validate` but can still be sitting on disk,
     * and this runs on every refresh — so it has to terminate, and it has to terminate with the
     * same numbers the descendant scan produced, or repairing a broken tree would look like the
     * counts changing.
     */
    @Test
    fun `a cycle terminates and agrees with the descendant scan`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val folders = listOf(
            Folder(id = a, name = "A", parentId = b),
            Folder(id = b, name = "B", parentId = a),
        )
        val items = listOf(item(a))

        val counts = FolderSubtreeCounts.of(folders, items)

        assertEquals(1, counts[a])
        assertEquals(1, counts[b])
        assertMatchesOldScan(folders, items)
    }

    /**
     * The folder-management screen passes only *live* folders, so a live folder whose parent is in
     * the trash has an ancestor that is simply not in the list. The climb has to stop there — the
     * trashed parent gets no row, and inventing a count for it would be counting towards something
     * the user cannot see. The descendant scan behaved this way by accident (a trashed folder was
     * never a root it was asked about); this asserts it on purpose.
     */
    @Test
    fun `a live folder under a trashed parent counts, and the trashed parent gets nothing`() {
        val trashedParent = folder("Trashed")
        val liveChild = folder("Live", trashedParent.id)
        // As `load` builds it: `.live` has already removed the trashed parent.
        val liveOnly = listOf(liveChild)
        val items = listOf(item(liveChild.id), item(liveChild.id))

        val counts = FolderSubtreeCounts.of(liveOnly, items)

        assertEquals(2, counts[liveChild.id])
        assertEquals(null, counts[trashedParent.id])
        assertMatchesOldScan(liveOnly, items)
    }

    @Test
    fun `no folders means no counts`() {
        assertEquals(emptyMap(), FolderSubtreeCounts.of(emptyList(), listOf(item(UUID.randomUUID()))))
    }
}

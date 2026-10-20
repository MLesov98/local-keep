package com.localkeep.core.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two copies of a vault, edited apart, coming back together.
 *
 * The merge is where a hierarchy can be destroyed without anyone doing anything wrong: each copy is
 * internally valid, and the union of their folder edges is not. This is the whole of what
 * `VaultImporter.mergeInto` does to folders, tested off the Android side of it — the importer's own
 * job is one `mutate`, and every decision worth arguing about is here.
 */
class NestedMergeTreeTest {

    private val early = Instant.parse("2020-01-01T00:00:00Z")
    private val late = Instant.parse("2024-01-01T00:00:00Z")

    /** Exactly what the importer does to the folder list, so the assertions are about that. */
    private fun merge(existing: List<Folder>, incoming: List<Folder>): List<Folder> {
        val merged = LinkedHashMap<UUID, Folder>()
        existing.forEach { merged[it.id] = it }
        incoming.forEach { folder ->
            val current = merged[folder.id]
            merged[folder.id] = if (current == null) folder else FolderTree.mergeFolder(folder, current)
        }
        return FolderTree.normalize(merged.values.toList()).folders
    }

    @Test
    fun `a rename in one copy and a move in the other both survive`() {
        // The case a single timestamp cannot express. Whole-folder recency picks one copy and
        // discards the other's edit, even though both are things the user did.
        val id = UUID.randomUUID()
        val parent = Folder(UUID.randomUUID(), "Parent")
        val renamed = Folder(id, "Renamed", parentId = null, updatedAt = late, parentUpdatedAt = early)
        val moved = Folder(id, "Original", parentId = parent.id, updatedAt = early, parentUpdatedAt = late)

        val result = merge(listOf(parent, renamed), listOf(moved)).single { it.id == id }

        assertEquals("Renamed", result.name)
        assertEquals(parent.id, result.parentId)
    }

    @Test
    fun `an older copy cannot undo a newer rename or a newer move`() {
        val id = UUID.randomUUID()
        val parent = Folder(UUID.randomUUID(), "Parent")
        val current = Folder(id, "Current", parentId = parent.id, updatedAt = late, parentUpdatedAt = late)
        val stale = Folder(id, "Stale", parentId = null, updatedAt = early, parentUpdatedAt = early)

        val result = merge(listOf(parent, current), listOf(stale)).single { it.id == id }

        assertEquals("Current", result.name)
        assertEquals(parent.id, result.parentId)
    }

    @Test
    fun `an undated copy loses both fields, because unknown is not now`() {
        val id = UUID.randomUUID()
        val parent = Folder(UUID.randomUUID(), "Parent")
        val current = Folder(id, "Current", parentId = parent.id, updatedAt = late, parentUpdatedAt = late)
        val undated = Folder(id, "Ancient", parentId = null)

        val result = merge(listOf(parent, current), listOf(undated)).single { it.id == id }

        assertEquals("Current", result.name)
        assertEquals(parent.id, result.parentId)
    }

    @Test
    fun `both-null disagreement keeps the existing side, as it always did`() {
        val id = UUID.randomUUID()
        val parent = Folder(UUID.randomUUID(), "Parent")
        val existing = Folder(id, "Existing", parentId = null)
        val incoming = Folder(id, "Incoming", parentId = parent.id)

        val result = merge(listOf(parent, existing), listOf(incoming)).single { it.id == id }

        assertEquals("Existing", result.name)
        assertNull(result.parentId)
    }

    @Test
    fun `two valid vaults merging into a cycle are normalized rather than refused`() {
        // The failure that would otherwise brick an import: A's parent is B in one copy, B's parent
        // is A in the other, and each copy is perfectly valid on its own. Since every folder write
        // validates, an unnormalized merge would throw and there would be no way to finish
        // importing a backup that is not itself damaged.
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val existing = listOf(
            Folder(a, "A", parentId = null, parentUpdatedAt = early),
            Folder(b, "B", parentId = a, parentUpdatedAt = late),
        )
        val incoming = listOf(Folder(a, "A", parentId = b, parentUpdatedAt = late))

        val result = merge(existing, incoming)

        assertTrue(FolderTree.validate(result).isValid)
        assertEquals(2, result.size, "normalizing must not drop a folder to break the cycle")
    }

    @Test
    fun `a parent deleted in one copy leaves its children at the root, not dangling`() {
        val parent = UUID.randomUUID()
        val child = Folder(UUID.randomUUID(), "Child", parentId = parent, parentUpdatedAt = late)

        val result = merge(listOf(child), emptyList())

        assertTrue(FolderTree.validate(result).isValid)
        assertNull(result.single().parentId)
    }

    @Test
    fun `a merge that would nest past the limit is clamped rather than refused`() {
        val chain = mutableListOf(Folder(UUID.randomUUID(), "d1"))
        repeat(FolderTree.MAX_DEPTH) {
            chain += Folder(UUID.randomUUID(), "d${it + 2}", parentId = chain.last().id, parentUpdatedAt = late)
        }
        val result = merge(chain, emptyList())

        assertTrue(FolderTree.validate(result).isValid)
        assertEquals(chain.size, result.size)
    }

    @Test
    fun `same-named siblings from two vaults are kept, not silently renamed`() {
        // Legal at rest by design. Renaming one would be the merge editing the user's own data to
        // resolve something the UI can label instead.
        val parent = Folder(UUID.randomUUID(), "Parent")
        val mine = Folder(UUID.randomUUID(), "Work", parentId = parent.id)
        val theirs = Folder(UUID.randomUUID(), "Work", parentId = parent.id)

        val result = merge(listOf(parent, mine), listOf(theirs))

        assertEquals(2, result.count { it.name == "Work" })
    }

    @Test
    fun `promote stamps survive a re-import of the pre-delete backup`() {
        // Deleting a parent promotes its child and stamps parentUpdatedAt. Importing a backup taken
        // before that delete must not put the child back under a folder the user removed.
        val deletedParent = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val promoted = Folder(childId, "Child", parentId = null, parentUpdatedAt = late)
        val backup = listOf(
            Folder(deletedParent, "Parent", parentUpdatedAt = early),
            Folder(childId, "Child", parentId = deletedParent, parentUpdatedAt = early),
        )

        val result = merge(listOf(promoted), backup)

        assertNull(result.single { it.id == childId }.parentId)
        // The parent itself does come back, empty: folders merge by id and the backup still has it.
        // Union semantics, harmless, and the same as before folders nested.
        assertTrue(result.any { it.id == deletedParent })
    }
}

package com.localkeep.core.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The folder hierarchy's rules, tested away from Android and away from the vault: a tree defect is
 * a data-shape problem, and every one of them here is a shape a real vault can arrive in — a
 * restored snapshot, a merge of two independently-edited copies, an import from a tool with no
 * depth limit.
 */
class FolderTreeTest {

    private fun folder(
        name: String,
        parent: UUID? = null,
        id: UUID = UUID.randomUUID(),
        updatedAt: Instant? = null,
        parentUpdatedAt: Instant? = null,
    ) = Folder(id = id, name = name, parentId = parent, updatedAt = updatedAt, parentUpdatedAt = parentUpdatedAt)

    // ---- descendantIds ----

    @Test
    fun `descendantIds includes the root itself`() {
        val a = folder("A")
        assertEquals(setOf(a.id), FolderTree.descendantIds(listOf(a), a.id))
    }

    @Test
    fun `descendantIds walks the whole subtree and excludes sibling branches`() {
        val a = folder("A")
        val b = folder("B", parent = a.id)
        val c = folder("C", parent = b.id)
        val other = folder("Other")
        val folders = listOf(a, b, c, other)
        assertEquals(setOf(a.id, b.id, c.id), FolderTree.descendantIds(folders, a.id))
        assertEquals(setOf(b.id, c.id), FolderTree.descendantIds(folders, b.id))
    }

    @Test
    fun `descendantIds of an unknown id is empty`() {
        assertTrue(FolderTree.descendantIds(listOf(folder("A")), UUID.randomUUID()).isEmpty())
    }

    @Test
    fun `descendantIds terminates on a cycle`() {
        val a = folder("A")
        val b = folder("B", parent = a.id)
        val cyclicA = a.copy(parentId = b.id)
        assertEquals(setOf(a.id, b.id), FolderTree.descendantIds(listOf(cyclicA, b), a.id))
    }

    // ---- pathOf / depthOf ----

    @Test
    fun `pathOf is root-first`() {
        val a = folder("A")
        val b = folder("B", parent = a.id)
        val c = folder("C", parent = b.id)
        assertEquals(listOf("A", "B", "C"), FolderTree.pathOf(listOf(c, b, a), c.id)?.map { it.name })
    }

    @Test
    fun `pathOf an unknown id is null`() {
        assertNull(FolderTree.pathOf(listOf(folder("A")), UUID.randomUUID()))
    }

    @Test
    fun `depthOf counts root children as one`() {
        val a = folder("A")
        val b = folder("B", parent = a.id)
        assertEquals(1, FolderTree.depthOf(listOf(a, b), a.id))
        assertEquals(2, FolderTree.depthOf(listOf(a, b), b.id))
    }

    @Test
    fun `depthOf is bounded on a cycle rather than looping`() {
        val a = folder("A")
        val b = folder("B", parent = a.id)
        val cyclicA = a.copy(parentId = b.id)
        assertNull(FolderTree.depthOf(listOf(cyclicA, b), a.id))
    }

    // ---- wouldBeDescendant ----

    @Test
    fun `wouldBeDescendant catches self, direct and deep targets`() {
        val a = folder("A")
        val b = folder("B", parent = a.id)
        val c = folder("C", parent = b.id)
        val unrelated = folder("U")
        val folders = listOf(a, b, c, unrelated)
        assertTrue(FolderTree.wouldBeDescendant(folders, a.id, a.id))
        assertTrue(FolderTree.wouldBeDescendant(folders, a.id, b.id))
        assertTrue(FolderTree.wouldBeDescendant(folders, a.id, c.id))
        assertFalse(FolderTree.wouldBeDescendant(folders, a.id, unrelated.id))
    }

    // ---- validate ----

    @Test
    fun `validate accepts a tree at the maximum depth`() {
        val chain = mutableListOf(folder("d1"))
        repeat(FolderTree.MAX_DEPTH - 1) { chain += folder("d${it + 2}", parent = chain.last().id) }
        assertTrue(FolderTree.validate(chain).isValid)
    }

    @Test
    fun `validate rejects a two-node cycle`() {
        val a = folder("A")
        val b = folder("B", parent = a.id)
        val result = FolderTree.validate(listOf(a.copy(parentId = b.id), b))
        assertFalse(result.isValid)
    }

    @Test
    fun `validate rejects a three-node cycle`() {
        val a = folder("A")
        val b = folder("B", parent = a.id)
        val c = folder("C", parent = b.id)
        assertFalse(FolderTree.validate(listOf(a.copy(parentId = c.id), b, c)).isValid)
    }

    @Test
    fun `validate rejects a self-parent`() {
        val a = folder("A")
        assertFalse(FolderTree.validate(listOf(a.copy(parentId = a.id))).isValid)
    }

    @Test
    fun `validate rejects a dangling parent`() {
        assertFalse(FolderTree.validate(listOf(folder("A", parent = UUID.randomUUID()))).isValid)
    }

    @Test
    fun `validate rejects one level past the maximum depth`() {
        val chain = mutableListOf(folder("d1"))
        repeat(FolderTree.MAX_DEPTH) { chain += folder("d${it + 2}", parent = chain.last().id) }
        assertFalse(FolderTree.validate(chain).isValid)
    }

    @Test
    fun `validate does not treat duplicate sibling names as an error`() {
        val a = folder("A")
        assertTrue(FolderTree.validate(listOf(a, folder("Work", parent = a.id), folder("Work", parent = a.id))).isValid)
    }

    /** A folder list that counts how many times it is walked from the start. */
    private class CountingList(private val inner: List<Folder>) : List<Folder> by inner {
        var walks = 0
            private set

        override fun iterator(): Iterator<Folder> {
            walks += 1
            return inner.iterator()
        }
    }

    /**
     * 2026-10-02 (O4-05): `validate` measured each folder with [FolderTree.depthOf], and `depthOf`
     * rebuilds a map of the *whole* list per call — so validating F folders built F maps of F
     * entries, on every folder write (`VaultMutator.build`) and every tree-health check. A
     * 1,000-group import paid a million map entries per write. Counted as walks over the list
     * rather than timed, so the assertion is exact and cannot flake: the cost of one validation
     * must not grow a walk per folder.
     */
    @Test
    fun `subtreeSizes walks the folder list a fixed number of times, not once per root`() {
        fun walksFor(count: Int): Int {
            val root = folder("root")
            val siblings = List(count) { folder("F$it", parent = root.id) }
            val folders = CountingList(listOf(root) + siblings + siblings.map { folder("c", parent = it.id) })
            FolderTree.subtreeSizes(folders, siblings.map { it.id })
            return folders.walks
        }
        assertEquals(walksFor(10), walksFor(500))
    }

    @Test
    fun `subtreeSizes answers what descendantIds answers, root included, unknown as zero`() {
        val root = folder("root")
        val a = folder("a", parent = root.id)
        val b = folder("b", parent = root.id)
        val a1 = folder("a1", parent = a.id)
        val a2 = folder("a2", parent = a1.id)
        val folders = listOf(root, a, b, a1, a2)
        val unknown = UUID.randomUUID()
        val sizes = FolderTree.subtreeSizes(folders, listOf(root.id, a.id, b.id, a2.id, unknown))
        listOf(root, a, b, a2).forEach { f ->
            assertEquals(FolderTree.descendantIds(folders, f.id).size, sizes[f.id], f.name)
        }
        assertEquals(0, sizes[unknown])
    }

    @Test
    fun `validate walks the folder list a fixed number of times, not once per folder`() {
        fun walksFor(count: Int): Int {
            val root = folder("root")
            val folders = CountingList(listOf(root) + List(count) { folder("F$it", parent = root.id) })
            assertTrue(FolderTree.validate(folders).isValid)
            return folders.walks
        }
        assertEquals(walksFor(10), walksFor(500))
    }

    /**
     * The control for the test above: sharing the map must not change a single answer. Pinned
     * against the per-folder [FolderTree.depthOf] form it replaced, over every defect shape the
     * reasons describe — including a folder hanging off a broken chain, whose reason text is
     * whatever that form produced.
     */
    @Test
    fun `validate reports exactly what the per-folder depth walk reported`() {
        fun reference(folders: List<Folder>): FolderTree.ValidationResult {
            val byId = folders.associateBy { it.id }
            val reasons = mutableListOf<String>()
            folders.forEach { f ->
                val p = f.parentId
                when {
                    p == null -> Unit
                    p == f.id -> reasons += "folder ${f.id} is its own parent"
                    p !in byId -> reasons += "folder ${f.id} names a missing parent $p"
                }
            }
            folders.forEach { f ->
                val depth = FolderTree.depthOf(folders, f.id)
                when {
                    depth == null -> if (f.parentId in byId) reasons += "folder ${f.id} sits on a cycle"
                    depth > FolderTree.MAX_DEPTH -> reasons += "folder ${f.id} is $depth deep, past ${FolderTree.MAX_DEPTH}"
                }
            }
            folders.forEach { f ->
                val p = f.parentId ?: return@forEach
                if (f.deletedAt == null && byId[p]?.deletedAt != null) {
                    reasons += "folder ${f.id} is live under trashed parent $p"
                }
            }
            return if (reasons.isEmpty()) FolderTree.ValidationResult.Valid else FolderTree.ValidationResult.Invalid(reasons.distinct())
        }

        val a = folder("A")
        val b = folder("B", parent = a.id)
        val c = folder("C", parent = b.id)
        val tooDeep = mutableListOf(folder("d1"))
        repeat(FolderTree.MAX_DEPTH + 2) { tooDeep += folder("d${it + 2}", parent = tooDeep.last().id) }
        val dangling = folder("Dangling", parent = UUID.randomUUID())
        val underDangling = folder("Under", parent = dangling.id)
        val trashed = folder("Trashed").copy(deletedAt = Instant.EPOCH)
        val liveUnderTrashed = folder("Live", parent = trashed.id)
        val selfParent = folder("Self").let { it.copy(parentId = it.id) }
        val cycleHanger = folder("Hanger", parent = a.id)

        val shapes = listOf(
            listOf(a, b, c),
            listOf(a.copy(parentId = c.id), b, c, cycleHanger),
            tooDeep,
            listOf(dangling, underDangling),
            listOf(trashed, liveUnderTrashed),
            listOf(selfParent),
            listOf(a, b, c) + tooDeep + listOf(dangling, underDangling, selfParent, trashed, liveUnderTrashed),
        )
        shapes.forEach { shape -> assertEquals(reference(shape), FolderTree.validate(shape)) }
    }

    // ---- normalize ----

    @Test
    fun `normalize promotes a dangling folder to the root`() {
        val orphan = folder("Orphan", parent = UUID.randomUUID())
        val result = FolderTree.normalize(listOf(orphan))
        assertTrue(FolderTree.validate(result.folders).isValid)
        assertNull(result.folders.single().parentId)
        assertTrue(result.changed)
    }

    @Test
    fun `normalize breaks a cycle at its lowest-stamped member`() {
        val early = Instant.parse("2020-01-01T00:00:00Z")
        val late = Instant.parse("2024-01-01T00:00:00Z")
        val a = folder("A", parentUpdatedAt = early)
        val b = folder("B", parent = a.id, parentUpdatedAt = late)
        val cyclicA = a.copy(parentId = b.id)
        val result = FolderTree.normalize(listOf(cyclicA, b))
        assertTrue(FolderTree.validate(result.folders).isValid)
        assertNull(result.folders.first { it.id == a.id }.parentId)
        assertEquals(a.id, result.folders.first { it.id == b.id }.parentId)
    }

    @Test
    fun `normalize clamps a tree that is one level too deep`() {
        val chain = mutableListOf(folder("d1"))
        repeat(FolderTree.MAX_DEPTH) { chain += folder("d${it + 2}", parent = chain.last().id) }
        val result = FolderTree.normalize(chain)
        assertTrue(FolderTree.validate(result.folders).isValid)
    }

    @Test
    fun `normalize does not restamp a dropped parent, so a real move still outranks the repair`() {
        // Asked by a reviewer as "isn't the stale stamp a bug?" — it is the opposite. A repair is
        // not something the user did. Stamping it "now" would make it beat a genuine re-parent held
        // in another copy, so importing the backup that still has the missing folder would refuse
        // to put the child back. The old stamp keeps the real edit winning.
        val moved = Instant.parse("2024-01-01T00:00:00Z")
        val missing = UUID.randomUUID()
        val child = folder("Child", parent = missing, parentUpdatedAt = moved)

        val repaired = FolderTree.normalize(listOf(child)).folders.single()
        assertNull(repaired.parentId)
        assertEquals(moved, repaired.parentUpdatedAt, "the repair must not claim to be a move")

        // And the backup that still holds the parent restores it, rather than losing to the repair.
        val parent = folder("Parent", id = missing)
        val fromBackup = child.copy(parentUpdatedAt = moved.plusSeconds(1))
        val merged = FolderTree.mergeFolder(incoming = fromBackup, existing = repaired)
        assertEquals(missing, merged.parentId)
        assertTrue(FolderTree.validate(listOf(parent, merged)).isValid)
    }

    @Test
    fun `normalize leaves a healthy tree untouched and reports no change`() {
        val a = folder("A")
        val b = folder("B", parent = a.id)
        val result = FolderTree.normalize(listOf(a, b))
        assertFalse(result.changed)
        assertEquals(listOf(a, b), result.folders)
    }

    // ---- promotionPlan ----

    @Test
    fun `promotionPlan lifts direct children to the deleted folder's parent`() {
        val root = folder("Root")
        val mid = folder("Mid", parent = root.id)
        val leaf = folder("Leaf", parent = mid.id)
        val plan = FolderTree.promotionPlan(listOf(root, mid, leaf), mid.id)
        assertEquals(mapOf(leaf.id to root.id), plan)
    }

    @Test
    fun `promotionPlan lifts the children of a root folder to the root`() {
        val root = folder("Root")
        val child = folder("Child", parent = root.id)
        assertEquals(mapOf(child.id to null), FolderTree.promotionPlan(listOf(root, child), root.id))
    }

    @Test
    fun `promotionPlan of a leaf or an unknown id is empty`() {
        val root = folder("Root")
        assertTrue(FolderTree.promotionPlan(listOf(root), root.id).isEmpty())
        assertTrue(FolderTree.promotionPlan(listOf(root), UUID.randomUUID()).isEmpty())
    }

    // ---- heightOfSubtree / wouldExceedDepth ----

    /** A chain of [depth] folders, root first, each the child of the one before it. */
    private fun chain(depth: Int): List<Folder> {
        val ids = List(depth) { UUID.randomUUID() }
        return ids.mapIndexed { index, id ->
            Folder(id = id, name = "L${index + 1}", parentId = ids.getOrNull(index - 1))
        }
    }

    @Test
    fun `a childless folder has no height`() {
        val folders = chain(1)
        assertEquals(0, FolderTree.heightOfSubtree(folders, folders.single().id))
    }

    @Test
    fun `height counts the levels below a folder, not the folder itself`() {
        val folders = chain(3)
        assertEquals(2, FolderTree.heightOfSubtree(folders, folders[0].id))
        assertEquals(1, FolderTree.heightOfSubtree(folders, folders[1].id))
        assertEquals(0, FolderTree.heightOfSubtree(folders, folders[2].id))
    }

    @Test
    fun `height takes the deepest branch, not the first`() {
        val root = UUID.randomUUID()
        val shallow = UUID.randomUUID()
        val deep = UUID.randomUUID()
        val deeper = UUID.randomUUID()
        val folders = listOf(
            Folder(id = root, name = "Root"),
            Folder(id = shallow, name = "Shallow", parentId = root),
            Folder(id = deep, name = "Deep", parentId = root),
            Folder(id = deeper, name = "Deeper", parentId = deep),
        )
        assertEquals(2, FolderTree.heightOfSubtree(folders, root))
    }

    @Test
    fun `height of an unknown folder is zero`() {
        assertEquals(0, FolderTree.heightOfSubtree(chain(2), UUID.randomUUID()))
    }

    @Test
    fun `a childless folder may move onto a target at the last legal depth`() {
        // MAX_DEPTH - 1 deep, so the moved folder lands exactly at MAX_DEPTH. The boundary case
        // both ways round is the whole point of pinning this.
        val folders = chain(FolderTree.MAX_DEPTH - 1)
        val payload = Folder(id = UUID.randomUUID(), name = "Payload")
        val all = folders + payload
        assertFalse(FolderTree.wouldExceedDepth(all, payload.id, folders.last().id))
    }

    @Test
    fun `a childless folder may not move onto a target already at the limit`() {
        // The off-by-one the plan calls out: without the +1 this computes MAX_DEPTH + 0, which is
        // not > MAX_DEPTH, and the move is wrongly accepted. Dropping *into* the target makes the
        // target the new parent, so the payload lands one level below it.
        val folders = chain(FolderTree.MAX_DEPTH)
        val payload = Folder(id = UUID.randomUUID(), name = "Payload")
        val all = folders + payload
        assertTrue(FolderTree.wouldExceedDepth(all, payload.id, folders.last().id))
    }

    @Test
    fun `a folder carrying a subtree is measured by its whole height`() {
        // Two levels of payload onto a target at MAX_DEPTH - 2 puts the deepest leaf exactly at the
        // limit; one level deeper is over it.
        val payloadRoot = UUID.randomUUID()
        val payloadChild = UUID.randomUUID()
        val payload = listOf(
            Folder(id = payloadRoot, name = "P"),
            Folder(id = payloadChild, name = "PC", parentId = payloadRoot),
        )
        val fits = chain(FolderTree.MAX_DEPTH - 2) + payload
        assertFalse(FolderTree.wouldExceedDepth(fits, payloadRoot, fits.first { it.name == "L${FolderTree.MAX_DEPTH - 2}" }.id))

        val overflows = chain(FolderTree.MAX_DEPTH - 1) + payload
        assertTrue(
            FolderTree.wouldExceedDepth(
                overflows,
                payloadRoot,
                overflows.first { it.name == "L${FolderTree.MAX_DEPTH - 1}" }.id,
            ),
        )
    }

    @Test
    fun `moving to the root is measured from depth zero`() {
        // At root the subtree occupies depths 1..height + 1, so one exactly MAX_DEPTH tall fits and
        // is not the rejection case — a tree that tall is already legal sitting where it is.
        val payloadRoot = UUID.randomUUID()
        val exactlyFits = chainUnder(payloadRoot, FolderTree.MAX_DEPTH)
        assertFalse(FolderTree.wouldExceedDepth(exactlyFits, payloadRoot, null))

        val tooTall = chainUnder(payloadRoot, FolderTree.MAX_DEPTH + 1)
        assertTrue(FolderTree.wouldExceedDepth(tooTall, payloadRoot, null))
    }

    /** [rootId] plus [depth] - 1 descendants in a single chain, so the subtree is [depth] tall. */
    private fun chainUnder(rootId: UUID, depth: Int): List<Folder> {
        val ids = listOf(rootId) + List(depth - 1) { UUID.randomUUID() }
        return ids.mapIndexed { index, id ->
            Folder(id = id, name = "P$index", parentId = ids.getOrNull(index - 1))
        }
    }

    // ---- mergeFolder ----

    @Test
    fun `mergeFolder orders the name and the parent independently`() {
        val early = Instant.parse("2020-01-01T00:00:00Z")
        val late = Instant.parse("2024-01-01T00:00:00Z")
        val id = UUID.randomUUID()
        val newParent = UUID.randomUUID()
        val existing = Folder(id = id, name = "Renamed here", updatedAt = late, parentId = null, parentUpdatedAt = early)
        val incoming = Folder(id = id, name = "Old name", updatedAt = early, parentId = newParent, parentUpdatedAt = late)
        val merged = FolderTree.mergeFolder(incoming, existing)
        assertEquals("Renamed here", merged.name)
        assertEquals(newParent, merged.parentId)
        assertEquals(late, merged.updatedAt)
        assertEquals(late, merged.parentUpdatedAt)
    }

    @Test
    fun `mergeFolder lets a real stamp beat a null one`() {
        val at = Instant.parse("2024-01-01T00:00:00Z")
        val id = UUID.randomUUID()
        val existing = Folder(id = id, name = "Existing")
        val incoming = Folder(id = id, name = "Incoming", updatedAt = at)
        // A known time beats an unknown one whichever side it arrives on: the dated name survives
        // both directions, so an ancient undated export can never undo a rename.
        assertEquals("Incoming", FolderTree.mergeFolder(incoming, existing).name)
        assertEquals("Incoming", FolderTree.mergeFolder(existing, incoming).name)
    }

    @Test
    fun `mergeFolder does not let an old backup re-trash a folder the user restored`() {
        // Why deletedAt is not its own ordering key. The user trashes "Work", changes their mind
        // and restores it, then imports a backup taken while it was still in the trash. Restore
        // *clears* deletedAt, so ordering on that value would compare null against a real instant,
        // read null as "older than anything", and take the backup's side — putting the folder back
        // in the trash with no user action and nothing to indicate why.
        val trashedAt = Instant.parse("2024-01-01T00:00:00Z")
        val restoredAt = Instant.parse("2024-02-01T00:00:00Z")
        val id = UUID.randomUUID()
        val restored = Folder(id = id, name = "Work", trashUpdatedAt = restoredAt)
        val fromBackup = Folder(
            id = id,
            name = "Work",
            deletedAt = trashedAt,
            trashUpdatedAt = trashedAt,
            trashRootId = id,
        )

        val merged = FolderTree.mergeFolder(incoming = fromBackup, existing = restored)

        assertNull(merged.deletedAt, "the restore is newer and must win")
        assertNull(merged.trashRootId, "trashRootId travels with deletedAt or a restore leaves debris")
        assertEquals(restoredAt, merged.trashUpdatedAt)
    }

    @Test
    fun `mergeFolder takes a newer trash over an older restore`() {
        // The same rule in the other direction, so the test above cannot be satisfied by simply
        // never accepting an incoming deletedAt.
        val restoredAt = Instant.parse("2024-01-01T00:00:00Z")
        val trashedAt = Instant.parse("2024-02-01T00:00:00Z")
        val id = UUID.randomUUID()
        val existing = Folder(id = id, name = "Work", trashUpdatedAt = restoredAt)
        val incoming = Folder(
            id = id,
            name = "Work",
            deletedAt = trashedAt,
            trashUpdatedAt = trashedAt,
            trashRootId = id,
        )

        val merged = FolderTree.mergeFolder(incoming, existing)

        assertEquals(trashedAt, merged.deletedAt)
        assertEquals(id, merged.trashRootId)
        assertEquals(trashedAt, merged.trashUpdatedAt)
    }

    @Test
    fun `mergeFolder orders trash independently of a rename`() {
        // Third stamp, same contract as the first two: a rename on one side and a trash on the
        // other are separate edits, and merging must keep both rather than pick a winner.
        val early = Instant.parse("2020-01-01T00:00:00Z")
        val late = Instant.parse("2024-01-01T00:00:00Z")
        val id = UUID.randomUUID()
        val existing = Folder(id = id, name = "Renamed here", updatedAt = late, trashUpdatedAt = early)
        val incoming = Folder(
            id = id,
            name = "Old name",
            updatedAt = early,
            deletedAt = late,
            trashUpdatedAt = late,
            trashRootId = id,
        )

        val merged = FolderTree.mergeFolder(incoming, existing)

        assertEquals("Renamed here", merged.name)
        assertEquals(late, merged.deletedAt)
    }

    @Test
    fun `mergeFolder keeps the existing side when both stamps are null`() {
        val id = UUID.randomUUID()
        val parent = UUID.randomUUID()
        val existing = Folder(id = id, name = "Existing", parentId = null)
        val incoming = Folder(id = id, name = "Incoming", parentId = parent)
        val merged = FolderTree.mergeFolder(incoming, existing)
        assertEquals("Existing", merged.name)
        assertNull(merged.parentId)
    }

    // ---- trash containment ----
    //
    // `trashFolder` takes a whole subtree, so a live folder under a trashed one cannot be produced
    // by the app. A merge can: `mergeFolder` decides trash per record, so one copy's restore of a
    // child can land next to another copy's trashing of its parent. The result is reachable from
    // nowhere — hidden from the vault list because its parent is gone, absent from Trash because
    // Trash lists roots.

    @Test
    fun `validate rejects a live folder under a trashed parent`() {
        val stamp = Instant.parse("2026-01-01T00:00:00Z")
        val parent = folder("Parent").copy(deletedAt = stamp, trashUpdatedAt = stamp)
        val child = folder("Child", parent = parent.id)
        val result = FolderTree.validate(listOf(parent, child))
        assertTrue(result is FolderTree.ValidationResult.Invalid)
        assertTrue(
            result.reasons.any { it.contains("live under trashed parent") },
            "got: ${result.reasons}",
        )
    }

    @Test
    fun `validate accepts a fully trashed subtree`() {
        val stamp = Instant.parse("2026-01-01T00:00:00Z")
        val parent = folder("Parent").copy(deletedAt = stamp, trashUpdatedAt = stamp, trashRootId = null)
        val child = folder("Child", parent = parent.id)
            .copy(deletedAt = stamp, trashUpdatedAt = stamp, trashRootId = parent.id)
        assertEquals(FolderTree.ValidationResult.Valid, FolderTree.validate(listOf(parent, child)))
    }

    @Test
    fun `normalize trashes an orphan with its parent's own stamp, not now`() {
        // The inheritance is the assertion. A `now` stamp would outrank the genuine restore sitting
        // in another copy of the vault, and — worse — would put the orphan in no trash root at all,
        // leaving it trashed with nothing able to restore it.
        val stamp = Instant.parse("2026-01-01T00:00:00Z")
        val root = UUID.randomUUID()
        val parent = folder("Parent", id = root).copy(deletedAt = stamp, trashUpdatedAt = stamp)
        val child = folder("Child", parent = root)

        val result = FolderTree.normalize(listOf(parent, child))
        val repaired = result.folders.single { it.id == child.id }

        assertEquals(stamp, repaired.deletedAt)
        assertEquals(stamp, repaired.trashUpdatedAt)
        assertEquals(root, repaired.trashRootId)
        assertTrue(result.changes.any { it.contains("trashed with its parent") }, "got: ${result.changes}")
    }

    @Test
    fun `normalize propagates the trash down a whole live branch`() {
        val stamp = Instant.parse("2026-01-01T00:00:00Z")
        val root = UUID.randomUUID()
        val parent = folder("Parent", id = root).copy(deletedAt = stamp, trashUpdatedAt = stamp)
        val child = folder("Child", parent = root)
        val grandchild = folder("Grandchild", parent = child.id)

        val result = FolderTree.normalize(listOf(parent, child, grandchild))

        assertTrue(result.folders.all { it.deletedAt == stamp })
        assertEquals(setOf(root), result.folders.filter { it.id != root }.map { it.trashRootId }.toSet())
        assertEquals(FolderTree.ValidationResult.Valid, FolderTree.validate(result.folders))
    }

    @Test
    fun `normalize keeps a trashed folder's own root when it already has one`() {
        // The parent was trashed as part of someone else's subtree, so the orphan joins that root
        // rather than declaring the parent one.
        val stamp = Instant.parse("2026-01-01T00:00:00Z")
        val outerRoot = UUID.randomUUID()
        val parent = folder("Parent")
            .copy(deletedAt = stamp, trashUpdatedAt = stamp, trashRootId = outerRoot)
        val child = folder("Child", parent = parent.id)

        val repaired = FolderTree.normalize(listOf(parent, child)).folders.single { it.id == child.id }
        assertEquals(outerRoot, repaired.trashRootId)
    }

    @Test
    fun `normalize leaves a live folder alone once its trashed parent is dropped`() {
        // Order matters: step 1 drops the unknown parent, so by the time trash containment runs
        // this folder is a root and there is nothing to inherit from.
        val child = folder("Child", parent = UUID.randomUUID())
        val repaired = FolderTree.normalize(listOf(child)).folders.single()
        assertNull(repaired.parentId)
        assertNull(repaired.deletedAt)
    }
}

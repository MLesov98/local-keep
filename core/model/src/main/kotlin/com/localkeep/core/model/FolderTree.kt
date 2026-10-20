package com.localkeep.core.model

import java.time.Instant
import java.util.UUID

/**
 * The rules of the folder hierarchy, kept in one place and away from Android.
 *
 * Folders became containers rather than saved filters, which turns [Folder.parentId] from a field
 * nothing read into the shape of the whole vault list. That shape can be wrong in ways no single
 * write causes: two copies of a vault, each internally valid, can merge into a cycle; a restored
 * snapshot can carry a parent id whose folder is gone; an import from a tool with no depth limit
 * can nest deeper than anything here can render. So every rule is expressed twice — [validate]
 * asserts it, [normalize] repairs it — and the repair is what runs on data arriving from outside.
 *
 * Nothing here mutates its input; every function takes the folder list whole.
 */
object FolderTree {

    /**
     * How deep the tree may go, counting a root folder as depth 1.
     *
     * Every recursive walk in the app is bounded by this: subtree sets, breadcrumbs, the search
     * haystack's ancestor chain, and the picker's indentation. Eight is well past what fits on a
     * phone and still small enough that an O(n·depth) sweep on unlock costs nothing.
     */
    const val MAX_DEPTH = 8

    /** A tree's health. [Invalid] carries human-readable reasons for the repair prompt and tests. */
    sealed interface ValidationResult {
        val isValid: Boolean

        data object Valid : ValidationResult {
            override val isValid: Boolean = true
        }

        data class Invalid(val reasons: List<String>) : ValidationResult {
            override val isValid: Boolean = false
        }
    }

    /** [normalize]'s output: the repaired list, plus whether anything actually moved. */
    data class NormalizationResult(
        val folders: List<Folder>,
        val changes: List<String>,
    ) {
        val changed: Boolean get() = changes.isNotEmpty()
    }

    /**
     * Every folder in [rootId]'s subtree, including [rootId] itself, or empty if it is unknown.
     *
     * Self-inclusive because every caller wants "the scope the user is browsing", which is the
     * folder plus what is under it. Bounded by a visited set rather than by [MAX_DEPTH] so that a
     * cyclic list — which [validate] rejects but which can still be sitting on disk — terminates
     * here instead of hanging the list refresh.
     */
    fun descendantIds(folders: List<Folder>, rootId: UUID): Set<UUID> {
        if (folders.none { it.id == rootId }) return emptySet()
        val childrenOf = folders.groupBy { it.parentId }
        val seen = linkedSetOf(rootId)
        val queue = ArrayDeque(listOf(rootId))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            childrenOf[current].orEmpty().forEach { child ->
                if (seen.add(child.id)) queue += child.id
            }
        }
        return seen
    }

    /**
     * [descendantIds]' size for each of [rootIds], from one index of [folders] rather than one each.
     *
     * The vault list needs this for every folder row at a level, on every composition. Calling
     * [descendantIds] per row rebuilt the parent index each time, so a level of F folders cost F
     * walks of the whole list — seconds on a large import (QA S1-09, 2026-10-02).
     */
    fun subtreeSizes(folders: List<Folder>, rootIds: Collection<UUID>): Map<UUID, Int> {
        val childrenOf = folders.groupBy { it.parentId }
        val known = childrenOf.values.flatten().mapTo(HashSet()) { it.id }
        return rootIds.associateWith { rootId ->
            if (rootId !in known) return@associateWith 0
            val seen = linkedSetOf(rootId)
            val queue = ArrayDeque(listOf(rootId))
            while (queue.isNotEmpty()) {
                childrenOf[queue.removeFirst()].orEmpty().forEach { child ->
                    if (seen.add(child.id)) queue += child.id
                }
            }
            seen.size
        }
    }

    /**
     * The ancestor chain of [folderId], root first and ending with the folder itself; null if the
     * folder is unknown or its chain is broken (a dangling parent or a cycle).
     *
     * Null rather than a partial path: a breadcrumb built from half a chain would name a location
     * the item is not in.
     */
    fun pathOf(folders: List<Folder>, folderId: UUID): List<Folder>? {
        val byId = folders.associateBy { it.id }
        var current = byId[folderId] ?: return null
        val path = ArrayDeque(listOf(current))
        val seen = mutableSetOf(current.id)
        while (true) {
            val parentId = current.parentId ?: return path.toList()
            val parent = byId[parentId] ?: return null
            if (!seen.add(parent.id)) return null
            path.addFirst(parent)
            current = parent
        }
    }

    /** Depth of [folderId] with root folders at 1; null when [pathOf] has nothing to measure. */
    fun depthOf(folders: List<Folder>, folderId: UUID): Int? = pathOf(folders, folderId)?.size

    /** [depthOf] against a map the caller already holds — the same parent walk [pathOf] makes. */
    private fun depthIn(byId: Map<UUID, Folder>, folderId: UUID): Int? {
        var current = byId[folderId] ?: return null
        val seen = mutableSetOf(current.id)
        var depth = 1
        while (true) {
            val parentId = current.parentId ?: return depth
            val parent = byId[parentId] ?: return null
            if (!seen.add(parent.id)) return null
            depth += 1
            current = parent
        }
    }

    /**
     * How many levels sit *below* [rootId]: 0 for a childless folder, 1 for one with children but
     * no grandchildren. Zero for an unknown folder, which makes it the neutral value in
     * [wouldExceedDepth] rather than a second failure mode to handle.
     *
     * The counterpart to [depthOf]. Moving a folder needs both: where it is going, and how far the
     * thing being moved reaches downwards.
     */
    fun heightOfSubtree(folders: List<Folder>, rootId: UUID): Int {
        if (folders.none { it.id == rootId }) return 0
        val childrenOf = folders.groupBy { it.parentId }
        val seen = mutableSetOf(rootId)
        var level = listOf(rootId)
        var height = 0
        while (true) {
            // The visited set is load-bearing for the same reason it is in [descendantIds]: a
            // cyclic list is rejected by [validate] but can still be sitting on disk, and this must
            // terminate rather than climb forever.
            val next = level.flatMap { childrenOf[it].orEmpty() }.map { it.id }.filter(seen::add)
            if (next.isEmpty()) return height
            height += 1
            level = next
        }
    }

    /**
     * Whether re-parenting [childId] under [newParentId] — null meaning the root — would put any
     * part of its subtree past [MAX_DEPTH].
     *
     * The `+ 1` is the whole reason this is a function rather than an expression at each call site.
     * Dropping *into* a target makes that target the new parent, so the moved folder lands one level
     * below it; computing `depthOf(target) + heightOfSubtree(child)` instead accepts a childless
     * folder onto a target already at the limit. `createFolder` gets this right by writing the
     * comparison differently (`parentDepth < MAX_DEPTH`), and having two spellings of one rule is
     * how they drift apart.
     *
     * An unknown target rejects: a caller asking about a parent that is not there has a bigger
     * problem than depth, and the tree-shape guards report it with a better message.
     */
    fun wouldExceedDepth(folders: List<Folder>, childId: UUID, newParentId: UUID?): Boolean {
        val parentDepth = if (newParentId == null) 0 else depthOf(folders, newParentId) ?: return true
        return parentDepth + 1 + heightOfSubtree(folders, childId) > MAX_DEPTH
    }

    /**
     * Whether moving [childId] under [parentId] would put it inside its own subtree — the move that
     * detaches a whole branch from the vault. True for the identity case as well, so callers get
     * one guard rather than two.
     */
    fun wouldBeDescendant(folders: List<Folder>, childId: UUID, parentId: UUID): Boolean =
        parentId in descendantIds(folders, childId)

    /**
     * The invariants a stored folder list must hold. Duplicate sibling names are deliberately absent:
     * they are refused at the point of a create/rename/move, but two vaults merging by id can
     * legally produce them, and rejecting them here would turn a harmless naming collision into a
     * vault that cannot be written.
     */
    fun validate(folders: List<Folder>): ValidationResult {
        val byId = folders.associateBy { it.id }
        val reasons = mutableListOf<String>()
        folders.forEach { folder ->
            val parentId = folder.parentId
            when {
                parentId == null -> Unit
                parentId == folder.id -> reasons += "folder ${folder.id} is its own parent"
                parentId !in byId -> reasons += "folder ${folder.id} names a missing parent $parentId"
            }
        }
        // Against the map built above, not through [depthOf]: that rebuilds a map of the whole list
        // per call, so validation built F maps of F entries — on every folder write
        // (`VaultMutator.build`) and every tree-health check (2026-10-02). Same walk, same answers.
        folders.forEach { folder ->
            val depth = depthIn(byId, folder.id)
            when {
                depth == null ->
                    if (folder.parentId in byId) reasons += "folder ${folder.id} sits on a cycle"
                depth > MAX_DEPTH -> reasons += "folder ${folder.id} is $depth deep, past $MAX_DEPTH"
            }
        }
        // A live folder under a trashed one is reachable from nowhere: the vault list hides it
        // because its parent is gone, and Trash lists roots, not the live children of a trashed
        // branch. Only a merge produces it — trashFolder takes whole subtrees — but a merge is
        // exactly what this list has to survive.
        folders.forEach { folder ->
            val parentId = folder.parentId ?: return@forEach
            if (folder.deletedAt == null && byId[parentId]?.deletedAt != null) {
                reasons += "folder ${folder.id} is live under trashed parent $parentId"
            }
        }
        return if (reasons.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(reasons.distinct())
    }

    /**
     * Repairs every defect [validate] can report, in the order that makes each later pass see a
     * simpler tree: dangling parents drop to the root, cycles break at their least-recently-moved
     * member, and anything past [MAX_DEPTH] re-attaches at the deepest level that fits.
     *
     * This is the merge and restore path. A merge that threw on a defective tree would make an
     * import of a perfectly good backup impossible to complete, so the throw is reserved for
     * [validate] as a final assert on data this function has already been through.
     */
    fun normalize(folders: List<Folder>): NormalizationResult {
        val changes = mutableListOf<String>()
        var working = folders

        // 1. A parent that is not in the list, or is the folder itself, cannot be honoured at all.
        val known = working.map { it.id }.toSet()
        working = working.map { folder ->
            val parentId = folder.parentId
            if (parentId != null && (parentId == folder.id || parentId !in known)) {
                changes += "${folder.id}: unknown parent dropped"
                // `parentUpdatedAt` is deliberately **not** re-stamped, and that is load-bearing.
                // This is a repair, not a move the user made: stamping it "now" would make a
                // mechanical fix outrank a genuine re-parent sitting in another copy of the vault,
                // so importing the backup that still has the folder would no longer restore it.
                // Keeping the old stamp means the real edit still wins, which is the whole point of
                // ordering the parent separately from the name.
                //
                // The cost is that a merge can briefly reintroduce the dangling parent before this
                // pass runs again — which is safe because nothing writes a folder list without
                // going through it: `VaultMutator.build` validates every write, and the import
                // normalizes before that assert. The transient never reaches disk.
                folder.copy(parentId = null)
            } else {
                folder
            }
        }

        // 2. Cycles. Break at the member whose parent was set longest ago: it is the edge least
        //    likely to be the move the user just made, and an unstamped edge is older than any.
        while (true) {
            val cycle = findCycle(working) ?: break
            val victim = cycle.minWithOrNull(
                compareBy<Folder>({ it.parentUpdatedAt?.toEpochMilli() ?: Long.MIN_VALUE }, { it.id }),
            ) ?: break
            changes += "${victim.id}: cycle broken, promoted to the root"
            working = working.map { if (it.id == victim.id) it.copy(parentId = null) else it }
        }

        // 3. Depth. Top-down, so a re-attached folder's own children measure against its new depth.
        val byId = working.associateBy { it.id }.toMutableMap()
        val childrenOf = working.groupBy { it.parentId }
        val queue = ArrayDeque(childrenOf[null].orEmpty().map { it.id to listOf(it.id) })
        while (queue.isNotEmpty()) {
            val (id, path) = queue.removeFirst()
            var currentPath = path
            if (currentPath.size > MAX_DEPTH) {
                val newParent = currentPath[MAX_DEPTH - 2]
                byId[id] = byId.getValue(id).copy(parentId = newParent)
                currentPath = currentPath.take(MAX_DEPTH - 1) + id
                changes += "$id: clamped to depth $MAX_DEPTH"
            }
            childrenOf[id].orEmpty().forEach { queue += it.id to (currentPath + it.id) }
        }
        working = working.map { byId.getValue(it.id) }

        // 4. Trash containment, after the re-parenting above, because step 1 and step 3 both change
        //    who a folder's parent is and this rule reads the parent's trash state. Top-down, so a
        //    folder trashed here propagates to its own children in the same pass.
        //
        //    The markers are **inherited, never stamped `now`**, for the reason spelled out in step
        //    1: this is a mechanical repair, and a `now` stamp would outrank the genuine trash or
        //    restore sitting in another copy of the vault. Inheriting the parent's stamp also puts
        //    the orphan in the parent's trash root, so restoring that root brings it back too —
        //    stamping it would have left it trashed forever with nothing able to restore it.
        val trashRepaired = working.associateBy { it.id }.toMutableMap()
        val childrenById = working.groupBy { it.parentId }
        val seen = mutableSetOf<UUID>()
        val walk = ArrayDeque(childrenById[null].orEmpty().map { it.id })
        while (walk.isNotEmpty()) {
            val id = walk.removeFirst()
            if (!seen.add(id)) continue
            val folder = trashRepaired.getValue(id)
            val parent = folder.parentId?.let(trashRepaired::get)
            if (folder.deletedAt == null && parent?.deletedAt != null) {
                changes += "$id: trashed with its parent"
                trashRepaired[id] = folder.copy(
                    deletedAt = parent.deletedAt,
                    trashUpdatedAt = parent.trashUpdatedAt,
                    trashRootId = parent.trashRootId ?: parent.id,
                )
            }
            childrenById[id].orEmpty().forEach { walk += it.id }
        }
        working = working.map { trashRepaired.getValue(it.id) }

        return NormalizationResult(working, changes)
    }

    /** One cycle's members, or null when the list has none. */
    private fun findCycle(folders: List<Folder>): List<Folder>? {
        val byId = folders.associateBy { it.id }
        val settled = mutableSetOf<UUID>()
        folders.forEach { start ->
            if (start.id in settled) return@forEach
            val walked = LinkedHashMap<UUID, Folder>()
            var current: Folder? = start
            while (current != null && current.id !in settled) {
                if (walked.containsKey(current.id)) {
                    // The cycle is the tail of the walk from where it re-entered.
                    return walked.values.toList().dropWhile { it.id != current!!.id }
                }
                walked[current.id] = current
                current = current.parentId?.let(byId::get)
            }
            settled += walked.keys
        }
        return null
    }

    /**
     * Where each direct child of [deletedId] goes when that folder is deleted: one level up, into
     * the deleted folder's own parent (null for a root folder).
     *
     * Deleting a container must never delete what was in it — the same rule the item side already
     * follows by unfiling rather than deleting.
     */
    fun promotionPlan(folders: List<Folder>, deletedId: UUID): Map<UUID, UUID?> {
        val deleted = folders.firstOrNull { it.id == deletedId } ?: return emptyMap()
        return folders.filter { it.parentId == deletedId }.associate { it.id to deleted.parentId }
    }

    /**
     * Merges two copies of the same folder field by field: [Folder.updatedAt] orders the name,
     * [Folder.parentUpdatedAt] orders the parent.
     *
     * One stamp would force a choice between a rename in one copy and a move in the other, and
     * whole-folder recency would silently throw away whichever change lost. Both are real edits the
     * user made, and they are independent, so they are merged independently — the same philosophy
     * as [ItemMerge.preserveStructure] on the item side. Schema 4 adds a third pair on the same
     * terms: trashing or restoring a folder is ordered by [Folder.trashUpdatedAt], independently of
     * both the name and the parent.
     *
     * Null stamps follow [ItemMerge.isNewerFolder] exactly: null never wins, a known time beats an
     * unknown one, and both-null keeps the existing side so merging two pre-schema-2 vaults behaves
     * as it always did.
     */
    fun mergeFolder(incoming: Folder, existing: Folder): Folder {
        val takeName = isNewer(incoming.updatedAt, existing.updatedAt)
        val takeParent = isNewer(incoming.parentUpdatedAt, existing.parentUpdatedAt)
        // Trash state is the third independent edit, ordered on its own stamp rather than on
        // deletedAt: a restore clears deletedAt, and a cleared value cannot also be the thing that
        // decides recency. deletedAt and trashRootId move together as one decision — they describe
        // a single event, and a folder holding one without the other is either a trashed folder no
        // restore can find or debris left on a live one.
        val takeTrash = isNewer(incoming.trashUpdatedAt, existing.trashUpdatedAt)
        // The fourth independent edit (schema 5): concealment, ordered on its own stamp so hiding a
        // folder in one copy and renaming or moving it in another both survive. Null follows the same
        // rule as the other three — older than any known timestamp.
        val takeHidden = isNewer(incoming.hiddenUpdatedAt, existing.hiddenUpdatedAt)
        return existing.copy(
            name = if (takeName) incoming.name else existing.name,
            updatedAt = if (takeName) incoming.updatedAt else existing.updatedAt,
            parentId = if (takeParent) incoming.parentId else existing.parentId,
            parentUpdatedAt = if (takeParent) incoming.parentUpdatedAt else existing.parentUpdatedAt,
            deletedAt = if (takeTrash) incoming.deletedAt else existing.deletedAt,
            trashRootId = if (takeTrash) incoming.trashRootId else existing.trashRootId,
            trashUpdatedAt = if (takeTrash) incoming.trashUpdatedAt else existing.trashUpdatedAt,
            hiddenUntil = if (takeHidden) incoming.hiddenUntil else existing.hiddenUntil,
            hiddenUpdatedAt = if (takeHidden) incoming.hiddenUpdatedAt else existing.hiddenUpdatedAt,
        )
    }

    private fun isNewer(incoming: Instant?, existing: Instant?): Boolean {
        val incomingAt = incoming ?: return false
        val existingAt = existing ?: return true
        return incomingAt.isAfter(existingAt)
    }
}

package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import com.localkeep.core.model.VaultItem
import java.util.UUID

/**
 * How many items sit in each folder's whole subtree, in one pass over the items.
 *
 * This used to be a fold over folders that asked for each folder's descendant set and then counted
 * the items whose folder was in it — `O(folders × items)` set lookups, run on the main dispatcher
 * inside every list refresh: every save, every reveal, every lease expiry, and every debounced
 * search keystroke. A vault with 10,000 items and a few hundred folders spent millions of hash
 * probes there per refresh, and the list janked while typing.
 *
 * Climbing instead of descending inverts the cost: each item walks its own ancestor chain once, and
 * that chain is at most [com.localkeep.core.model.FolderTree.MAX_DEPTH] long, so the whole map is
 * `O(items × depth + folders)` with no per-folder scan at all.
 *
 * Cycle semantics are deliberately those of `FolderTree.descendantIds` — bounded by a visited set
 * rather than by a depth limit — because a cyclic folder list is rejected by `validate` but can
 * still be sitting on disk, and the two must produce the same counts for the same broken data.
 */
internal object FolderSubtreeCounts {

    /**
     * @param folders the folders to report on; an item filed under anything absent from this list
     *   contributes to nothing, which is what a dangling `folderId` should do.
     * @param items already filtered to what the caller intends to count — the visibility projection's
     *   live items, narrowed by the active type filter.
     */
    fun of(folders: List<Folder>, items: List<VaultItem>): Map<UUID, Int> {
        if (folders.isEmpty()) return emptyMap()
        val parentOf = HashMap<UUID, UUID?>(folders.size)
        val counts = HashMap<UUID, Int>(folders.size)
        for (folder in folders) {
            parentOf[folder.id] = folder.parentId
            counts[folder.id] = 0
        }
        // One set, cleared per item, rather than one allocated per item: this runs on the main
        // dispatcher and the item count is the large number here.
        val seen = HashSet<UUID>()
        for (item in items) {
            var current: UUID? = item.folderId ?: continue
            seen.clear()
            while (current != null && seen.add(current)) {
                val running = counts[current] ?: break
                counts[current] = running + 1
                current = parentOf[current]
            }
        }
        return counts
    }
}

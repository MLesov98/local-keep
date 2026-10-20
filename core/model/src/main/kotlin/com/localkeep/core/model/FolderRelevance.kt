package com.localkeep.core.model

import java.util.UUID

/**
 * Which folders hold, directly or anywhere below them, at least one item [matches] accepts.
 *
 * Two surfaces ask this question — the vault list's folder strip while a type chip is on, and the
 * item editor's folder picker — and they used to answer it the same way by not asking it at all.
 * With the "2FA" chip on and no 2FA item anywhere in the vault, the list still offered four folders
 * to walk into, each labelled with a count of things the filter had just excluded. A folder row is
 * navigation, not a result, and navigation that leads nowhere is worse than absent.
 *
 * Sharing one function is the point: two implementations of "relevant" drift, and the drift shows up
 * as a folder you can pick in the editor but cannot find in the list.
 */
object FolderRelevance {
    /**
     * The relevant set: every folder holding a match, plus every ancestor of one.
     *
     * Ancestors are included deliberately. The principal's example — *"if there's a 2FA somewhere in
     * some subfolder A-B-C-D say in C we need to show only C folder"* — contrasts the folder that
     * holds the item against the empty ones nested **inside** it, so D goes and C stays. It cannot
     * mean hiding A and B: both surfaces walk one level at a time, so a C with no visible parent is
     * a folder nothing can reach.
     *
     * The walk stops at the first id already in the set, because whatever added it walked the rest
     * of that chain already — so this is linear in the number of matching items plus the depth of
     * the tree, not quadratic, on a vault where most items share a handful of folders.
     */
    fun relevantFolderIds(
        items: List<VaultItem>,
        folders: List<Folder>,
        matches: (VaultItem) -> Boolean,
    ): Set<UUID> {
        val parentOf = folders.associate { it.id to it.parentId }
        val relevant = mutableSetOf<UUID>()
        items.asSequence().filter(matches).forEach { item ->
            var id = item.folderId
            while (id != null && relevant.add(id)) {
                id = parentOf[id]
            }
        }
        return relevant
    }
}

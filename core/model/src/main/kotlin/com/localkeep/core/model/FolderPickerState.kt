package com.localkeep.core.model

import com.localkeep.core.common.sortedByDisplayName
import java.util.UUID

/**
 * One row of the editor's folder picker: a folder, how deep to indent it, and whether it can open.
 *
 * @param disambiguateBy the parent's name, set only when a sibling in this same list shares this
 *   folder's name — legal at rest, since two vaults merging by id produce exactly that.
 */
data class FolderPickerRow(
    val folder: Folder,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val disambiguateBy: String? = null,
)

/**
 * The picker's shape, as a pure function of the folder list and what is expanded.
 *
 * A separate type because the feature modules cannot host Compose UI tests — every attempt hangs on
 * `ComposeNotIdleException` (an open issue) — so a hierarchy rendered only inside the composable
 * would be verifiable by hand and nothing else. Here the indentation, the depth cap, the name sort
 * and the sibling disambiguation are ordinary JVM tests, and the composable is left with drawing.
 *
 * **The expand/collapse and back-contract helpers came out on 2026-08-19.** `Item Flows.dc.html`
 * draws the chooser's tree flat and indented with no disclosure, so callers ask [rows] for every
 * folder and nothing was left calling `toggled`, `expandedFor` or `back`.
 *
 * **In `core:model` since 2026-08-19.** It lived in `:feature:itemedit` while that was the only
 * screen that filed anything; the file editor now files too, and `:feature:files` cannot depend on
 * `:feature:itemedit` — the dependency runs the other way. This is domain logic about a folder tree,
 * so it belongs beside [FolderTree] rather than in either screen. The drawing half went the other
 * direction, into `core:ui` as `LkFolderPicker`, which takes rows and knows nothing about vaults.
 */
object FolderPickerState {

    /**
     * The visible rows, depth-first and name-sorted within each level: a folder's children appear
     * directly beneath it, and only while it is expanded.
     */
    fun rows(folders: List<Folder>, expanded: Set<UUID>): List<FolderPickerRow> {
        val childrenOf = folders.groupBy { it.parentId }
        val rows = mutableListOf<FolderPickerRow>()

        fun walk(parentId: UUID?, depth: Int) {
            // Bounded by the tree's own limit rather than by trust: a cyclic folder list on disk
            // would otherwise recurse until the stack ran out, inside an editor the user is typing
            // into. The repair lives on the folder screen; here the job is only to not crash.
            if (depth > FolderTree.MAX_DEPTH) return
            val siblings = childrenOf[parentId].orEmpty().sortedByDisplayName { it.name }
            siblings.forEach { folder ->
                val children = childrenOf[folder.id].orEmpty()
                val isExpanded = folder.id in expanded
                rows += FolderPickerRow(
                    folder = folder,
                    depth = depth,
                    hasChildren = children.isNotEmpty(),
                    expanded = isExpanded,
                    disambiguateBy = folder
                        .takeIf { candidate -> siblings.count { it.name.equals(candidate.name, ignoreCase = true) } > 1 }
                        ?.parentId
                        ?.let { id -> folders.firstOrNull { it.id == id }?.name },
                )
                if (isExpanded) walk(folder.id, depth + 1)
            }
        }
        walk(null, 0)
        return rows
    }

    /**
     * The rows a *new folder* may be filed under: [rows] minus the folders already sitting at the
     * deepest level, because a child of one of those would be the ninth. Until 2026-09-14 the
     * new-folder screen offered every folder, and choosing an eighth-level one made "Create folder"
     * do nothing at all — the repository refused, and the refusal reached no screen. A destination
     * the tree cannot accept is not a destination.
     */
    fun parentCandidates(folders: List<Folder>, expanded: Set<UUID>): List<FolderPickerRow> =
        rows(folders, expanded).filter { it.depth + 1 < FolderTree.MAX_DEPTH }
}

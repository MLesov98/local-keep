package com.localkeep.core.model

import com.localkeep.core.common.displayNameComparator
import java.util.UUID

/** The list scope captured when a standalone file is opened. */
sealed interface MediaViewerScope {
    data object RootUnfiled : MediaViewerScope
    data class Folder(val id: UUID) : MediaViewerScope
    data object GlobalSearch : MediaViewerScope
}

/** A visible standalone file, with the folder path needed by the read-only viewer. */
data class MediaViewerEntry(
    val item: VaultItem,
    val file: File,
    val folderPath: List<String>,
)

/** Canonical row ordering shared by the list and the standalone viewer. */
fun orderVaultItems(
    items: List<VaultItem>,
    directFolderId: UUID? = null,
): List<VaultItem> = items.sortedWith(
    compareBy<VaultItem> { directFolderId != null && it.folderId != directFolderId }
        .thenByDescending { it.favorite }
        .thenBy(displayNameComparator()) { it.title }
        .thenBy { it.id.toString() },
)

/**
 * Projects the visible file rows without introducing a second ordering rule.
 *
 * [orderedItems] is already the list's rendered order. [visibleOrderedItemIds] is the launch
 * snapshot; when present, the current visibility projection is intersected with it in that exact
 * order so a stale route can never expose a newly visible or out-of-scope item.
 */
fun projectMediaViewerEntries(
    orderedItems: List<VaultItem>,
    folders: List<Folder>,
    scope: MediaViewerScope,
    visibleOrderedItemIds: List<UUID>? = null,
): List<MediaViewerEntry> {
    val folderIds = when (scope) {
        MediaViewerScope.RootUnfiled, MediaViewerScope.GlobalSearch -> null
        is MediaViewerScope.Folder -> FolderTree.descendantIds(folders, scope.id)
    }
    val orderedById = orderedItems.associateBy { it.id }
    val candidates = (visibleOrderedItemIds ?: orderedItems.map { it.id })
        .mapNotNull { orderedById[it] }
        .filter { item ->
            when (scope) {
                MediaViewerScope.RootUnfiled -> item.folderId == null
                MediaViewerScope.GlobalSearch -> true
                is MediaViewerScope.Folder -> item.folderId in folderIds.orEmpty()
            }
        }
        .mapNotNull { item ->
            val file = item.content as? File ?: return@mapNotNull null
            MediaViewerEntry(
                item = item,
                file = file,
                folderPath = item.folderId
                    ?.let { FolderTree.pathOf(folders, it).orEmpty() }
                    ?.map { it.name }
                    .orEmpty(),
            )
        }
    return candidates
}

package com.localkeep.core.model

import java.util.UUID

/** One-shot launch context for the internal standalone-file viewer route. */
data class MediaViewerFocusRequest(
    val scope: MediaViewerScope,
    val visibleOrderedItemIds: List<UUID>,
    val projectionVersion: Long,
)

/**
 * In-memory only navigation carrier. The route contains the item id; this object carries the
 * mutable list scope without persisting folder/search state or any secret material.
 */
object MediaViewerFocus {
    private val lock = Any()
    private val pending = mutableMapOf<UUID, MediaViewerFocusRequest>()

    fun request(viewerItemId: UUID, focus: MediaViewerFocusRequest) = synchronized(lock) {
        pending[viewerItemId] = focus
    }

    fun take(viewerItemId: UUID): MediaViewerFocusRequest? = synchronized(lock) {
        pending.remove(viewerItemId)
    }

    fun clear(viewerItemId: UUID) = synchronized(lock) {
        pending.remove(viewerItemId)
    }

    fun clear() = synchronized(lock) {
        pending.clear()
    }
}

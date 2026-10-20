package com.localkeep.core.ui.nav

import java.util.UUID

/**
 * Which folder the next *new* item should be filed into.
 *
 * The FAB adds into the folder the list is browsing. That has to survive a round trip through a
 * separate destination, and a folder id is not going into a route: routes are serialised into saved
 * instance state and written to disk, and a folder name is the user's own word for a group of their
 * credentials. Same reasoning, and the same shape, as `FolderFocus`.
 *
 * In `:core:ui` rather than beside that one because both ends need it: the vault list sets it and
 * the item and file editors consume it, and none of those modules depends on another.
 *
 * Two rules, and both exist because of a way this could poison a later add:
 *
 *  - **Only a route that consumes it may set it.** A pending nobody takes waits for whatever the
 *    user adds next — a file add from inside a folder followed by an item add at the root would
 *    silently file that item into the folder. That is why the file path did not set it while the
 *    files editor had no folder field; since 2026-10-04 `FILE_NEW` takes it as `ITEM_NEW` does,
 *    so both add paths set it.
 *  - **Only a *new* item consumes it.** Each editor's ViewModel serves its edit route too, so a
 *    [take] in its init would have re-filed an existing item the user opened next. It is taken in
 *    the composable, after construction, on the new-item routes alone.
 */
object PendingFolderSelection {

    @Volatile
    private var pending: UUID? = null

    /** Asks the next new-item editor to start in [folderId]. */
    fun request(folderId: UUID) {
        pending = folderId
    }

    /** Returns the requested folder and forgets it, or null if there is none. */
    fun take(): UUID? {
        val taken = pending
        pending = null
        return taken
    }

    /** Forgets any pending request — the editor was left without saving. */
    fun clear() {
        pending = null
    }
}

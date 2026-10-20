package com.localkeep.feature.vaultlist

import java.util.UUID

/**
 * Which folder the next vault list should open filtered to.
 *
 * The Folders screen is a management surface: tapping a folder there takes the user back to the
 * vault list, browsing inside it. A *path* rather than a single id, because folders are containers
 * now — landing three levels deep with an empty breadcrumb would leave no way back out except the
 * root. The path lives in [VaultListViewModel] rather than in the route, so navigating to HOME
 * cannot carry it.
 *
 * Held in memory rather than as a navigation argument, following [com.localkeep.feature.settings]'s
 * `SettingsFocus` and `PendingSecret`: a route string is serialised into saved instance state and
 * written to disk, and a folder name is the user's own word for a group of their credentials.
 *
 * Take-on-entry rather than [HomeFilter]'s live `SharedFlow`, and the difference is which failure
 * each avoids. HomeFilter's case is a list already composed behind an open drawer, which a replayed
 * value would reach too late; this one navigates to a list that has not composed yet, which a
 * non-replayed emission would miss entirely. [take] consumes it, so arriving at the list by any
 * other route leaves the narrowing exactly as the user left it.
 */
object FolderFocus {

    @Volatile
    private var pending: List<UUID>? = null

    /** Asks the next vault list to browse [path], root first. */
    fun request(path: List<UUID>) {
        pending = path.takeIf { it.isNotEmpty() }
    }

    /** Returns the requested path and forgets it, or null if there is none. */
    fun take(): List<UUID>? {
        val taken = pending
        pending = null
        return taken
    }
}

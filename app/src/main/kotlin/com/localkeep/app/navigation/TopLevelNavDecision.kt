package com.localkeep.app.navigation

/** How a top-level navigation should be performed; null means "already there". */
internal data class TopLevelNav(val saveAndRestore: Boolean)

/**
 * The drawer shares this path with the bottom bar, and most of its rows are *leaves* — Settings,
 * Trash, Import, Backup, Folders, Invisible items, Recovery Kit — not tabs. They must not take the
 * save/restore options either. When a tab tap pops the stack above HOME with saveState,
 * NavController files the saved deque under the destination of the *deepest* popped entry: leaving
 * [vault, settings, health_dashboard] via the Vault tab files that pair under Settings. The next
 * drawer→Settings tap then carried restoreState, found that mapping and reopened the health
 * dashboard on top of Settings. Only the three bottom-bar destinations are tabs.
 */
internal fun topLevelNavDecision(
    currentPattern: String?,
    target: String,
): TopLevelNav? {
    if (currentPattern == target) return null
    // Drawer leaves navigate plainly; only the bottom bar's three destinations are tabs, and only
    // a tab may save or restore a stack.
    val tab = target == AppNavGraph.HOME ||
        target == AppNavGraph.GENERATOR ||
        target == AppNavGraph.HEALTH_DASHBOARD
    return TopLevelNav(saveAndRestore = tab)
}

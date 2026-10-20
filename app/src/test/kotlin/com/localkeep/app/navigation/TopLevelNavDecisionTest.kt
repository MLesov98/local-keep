package com.localkeep.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tab-style navigation options (restoreState, launchSingleTop) are wrong for drawer leaves —
 * Settings, Trash, Import, Backup, Folders, Invisible items, Recovery Kit — which are not tabs and
 * must not save or restore a stack. These tests pin the decision, not the NavController.
 *
 * Used to also pin the per-type vault routes ("vault/{type}") that shared one destination and
 * needed a filled-route comparison to avoid the trap where restoreState resurrected the last-saved
 * filter (tapping Cards opened Passkeys, 2026-07-29). That destination was removed —
 * 2026-09-07 — when type filtering moved into the vault list's filter sheet, which changes
 * ViewModel state rather than navigating; see [AppNavGraph].
 *
 * Nothing replaces the filter cases: with `vault/{type}` gone, every remaining target is either a
 * bottom-bar tab or a drawer leaf, and both are pinned below. A case asserting that arriving at
 * HOME from a drawer leaf navigates *plainly* was written here during that removal and was simply
 * false — HOME is one of the three tabs, so it saves and restores like the others, which
 * `real top-level tabs keep the state-preserving behaviour` already states.
 */
class TopLevelNavDecisionTest {

    @Test
    fun `real top-level tabs keep the state-preserving behaviour`() {
        assertEquals(
            TopLevelNav(saveAndRestore = true),
            topLevelNavDecision(AppNavGraph.HOME, AppNavGraph.GENERATOR),
        )
        assertEquals(
            TopLevelNav(saveAndRestore = true),
            topLevelNavDecision(AppNavGraph.GENERATOR, AppNavGraph.HOME),
        )
    }

    @Test
    fun `tapping the tab you are on is still a no-op`() {
        assertNull(topLevelNavDecision(AppNavGraph.GENERATOR, AppNavGraph.GENERATOR))
    }

    /**
     * The drawer shares this decision with the bottom bar, but its rows are not all tabs. When a
     * tab tap saves the stack above HOME, NavController files the saved deque under the *deepest*
     * popped entry's destination — so leaving [vault, settings, health_dashboard] with the Vault
     * tab files that pair under Settings. The next drawer→Settings tap then carried restoreState
     * and reopened the health dashboard instead of Settings. A leaf must never restore.
     */
    @Test
    fun `a drawer leaf never saves or restores a stack`() {
        assertEquals(
            TopLevelNav(saveAndRestore = false),
            topLevelNavDecision(AppNavGraph.HOME, AppNavGraph.SETTINGS),
        )
        assertEquals(
            TopLevelNav(saveAndRestore = false),
            topLevelNavDecision(AppNavGraph.HOME, AppNavGraph.TRASH),
        )
        assertEquals(
            TopLevelNav(saveAndRestore = false),
            topLevelNavDecision(AppNavGraph.HOME, AppNavGraph.BACKUP),
        )
    }
}

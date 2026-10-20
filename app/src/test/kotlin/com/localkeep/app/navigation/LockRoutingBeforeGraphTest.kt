package com.localkeep.app.navigation

import com.localkeep.core.vault.LockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The lock-state collector must not navigate before the `NavHost` has a graph.
 *
 * `currentRoute == null` is what `navController.currentDestination?.route` reads before
 * `setGraph()`. The unfixed collector treated it as "not on the unlock screen", navigated, and
 * `popUpTo(navController.graph.id)` threw on cold start (2026-09-21, emulator-5558, API 36).
 */
class LockRoutingBeforeGraphTest {
    @Test
    fun `a locked vault with no graph yet asks for no navigation`() {
        assertNull(
            lockNavigation(currentRoute = null, state = LockState.Locked, hasVaultFiles = true),
            "before setGraph() there is nothing to pop; startRoute already lands on UNLOCK",
        )
    }

    @Test
    fun `a locked vault away from the unlock screen goes to UNLOCK`() {
        assertEquals(
            LockNavigation.TO_UNLOCK,
            lockNavigation(currentRoute = AppNavGraph.HOME, state = LockState.Locked, hasVaultFiles = true),
        )
    }

    @Test
    fun `a locked vault already on the unlock screen stays put`() {
        assertNull(lockNavigation(currentRoute = AppNavGraph.UNLOCK, state = LockState.Locked, hasVaultFiles = true))
    }

    @Test
    fun `a locked first run with no vault files does not bounce onboarding`() {
        assertNull(lockNavigation(currentRoute = AppNavGraph.ONBOARDING, state = LockState.Locked, hasVaultFiles = false))
    }
}

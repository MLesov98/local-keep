package com.localkeep.app.navigation

import androidx.navigation.NavController
import com.localkeep.core.vault.LockState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Where the lock-state collector in `MainActivity` has to send the NavController, if anywhere. */
internal enum class LockNavigation { TO_UNLOCK, TO_HOME }

/**
 * The routing half of `MainActivity`'s `lockState.collect`, pulled out so it can be tested against
 * the one input the Activity cannot control: whether the `NavHost` has set its graph yet.
 *
 * [currentRoute] is `navController.currentDestination?.route`. It is `null` for exactly one reason
 * in this app — every destination in [AppNavGraph] has a string route — and that reason is that
 * `NavHost` has not called `setGraph()` yet. The collector is launched from a `LaunchedEffect`
 * above the `NavHost` in the tree and `lockState` is a `StateFlow`, so its first emission can land
 * before the graph exists. Treating that `null` as "not on the unlock screen" and navigating made
 * `popUpTo(navController.graph.id)` throw `IllegalStateException: You must call setGraph() before
 * calling getGraph()` and killed the process on cold start — seen once in four launches on API 36
 * during the 2026-09-20 release sweep's final launch check, and never on the JVM.
 *
 * So `null` still decides nothing — but the collector no longer *passes* it: it asks
 * [awaitCurrentRoute], which waits for the graph. See there for why waiting is required.
 */
internal fun lockNavigation(
    currentRoute: String?,
    state: LockState,
    hasVaultFiles: Boolean,
): LockNavigation? {
    if (currentRoute == null) return null
    val onUnlockScreen = currentRoute == AppNavGraph.UNLOCK
    return when {
        state is LockState.Locked && hasVaultFiles && !onUnlockScreen -> LockNavigation.TO_UNLOCK
        state is LockState.Unlocked && onUnlockScreen -> LockNavigation.TO_HOME
        else -> null
    }
}

/**
 * The route the lock collector decides against: the current one, or — before `NavHost` has set
 * its graph — the first one the graph lands on.
 *
 * 2026-10-02 (O3-02/S5-02): the collector used to drop a pre-graph emission on the theory that
 * `startRoute` already answered it. It does not after process death, or after a configuration
 * change on an activity the background timer locked: `NavController` restores the saved back stack
 * whatever `startDestination` says, and a `Locked` StateFlow never re-emits. A restored vault route
 * then sat over a sealed vault with no way to Unlock (and a restored UNLOCK over an open one with
 * no way out). Waiting for the first destination keeps the decision instead of losing it.
 */
internal suspend fun NavController.awaitCurrentRoute(): String? =
    currentDestination?.route ?: currentBackStackEntryFlow.first().destination.route

/**
 * Keeps "locked means UNLOCK is the only reachable route" true after the lock collector has acted.
 *
 * The collector routes to UNLOCK once per `Locked` emission, and a `Locked` StateFlow does not
 * emit again — so anything that reaches the top of the stack afterwards used to stay there. A tab
 * tap landing in the lock transition did exactly that (6/6 on a device): the tab sat over UNLOCK,
 * every save failed with a causeless "try again", the drawer was dead, and only a force-stop got
 * the unlock screen back, losing whatever had been typed. `navigateTopLevel` now refuses that
 * particular tap; this is the backstop for every other path to the same state.
 *
 * Watches the back stack, not the lock: whenever the top destination changes while the vault is
 * [LockState.Locked] with vault files on disk, and it is neither UNLOCK nor first-run ONBOARDING
 * (which writes the vault before it opens it), [toUnlock] is called. [toUnlock] lands on UNLOCK, so
 * its own arrival decides nothing and cannot loop. The lock state is read first so an open vault
 * never pays for [hasVaultFiles], which touches the disk, on every navigation.
 */
internal suspend fun NavController.holdUnlockWhileLocked(
    lockState: StateFlow<LockState>,
    hasVaultFiles: () -> Boolean,
    toUnlock: () -> Unit,
) {
    currentBackStackEntryFlow.collect { entry ->
        val route = entry.destination.route
        if (lockState.value !is LockState.Locked || route == AppNavGraph.ONBOARDING) return@collect
        if (lockNavigation(route, LockState.Locked, hasVaultFiles()) == LockNavigation.TO_UNLOCK) {
            toUnlock()
        }
    }
}

package com.localkeep.app.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * A bottom-bar tap that lands just after the idle auto-lock must not put a tab above UNLOCK.
 *
 * Reproduced 6/6 on a device: the lock pops the graph to `[UNLOCK]`, the tab bar is still drawn for
 * the length of the lock transition, and a tap in that window ran `navigateTopLevel` against
 * `[UNLOCK]`. Its `popUpTo(HOME)` found no HOME ("Ignoring popBackStack to route vault"), so the tab
 * was pushed on top: `[UNLOCK, health_dashboard]` over a sealed vault, with the lock collector never
 * looking again because a `Locked` StateFlow does not re-emit. Saves failed, the drawer was dead,
 * and only a force-stop got the unlock screen back.
 *
 * Two layers, tested separately: the tab path refuses to leave UNLOCK, and the activity re-routes
 * to UNLOCK if anything else ever reaches the top of the stack while the vault is locked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TabTapAfterLockStaysLockedTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun controller(start: String) = NavHostController(activity).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        setLifecycleOwner(activity)
        setViewModelStore(activity.viewModelStore)
        graph = createGraph(startDestination = start) {
            composable(AppNavGraph.ONBOARDING) {}
            composable(AppNavGraph.UNLOCK) {}
            composable(AppNavGraph.HOME) {}
            composable(AppNavGraph.GENERATOR) {}
            composable(AppNavGraph.HEALTH_DASHBOARD) {}
            composable(AppNavGraph.SETTINGS) {}
        }
    }

    /** Exactly what `MainActivity`'s lock branch does to the back stack. */
    private fun NavHostController.lockToUnlock() =
        navigate(AppNavGraph.UNLOCK) { popUpTo(graph.id) { inclusive = true } }

    private fun NavHostController.routes() = currentBackStack.value.mapNotNull { it.destination.route }

    private val unlocked = LockState.Unlocked(
        dek = ByteArray(32),
        vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
    )

    @Test
    fun `a tab tap on the unlock screen goes nowhere`() {
        val nav = controller(AppNavGraph.HOME)
        nav.navigateTopLevel(AppNavGraph.GENERATOR)
        nav.lockToUnlock()
        assertEquals(listOf(AppNavGraph.UNLOCK), nav.routes(), "precondition: the lock left [UNLOCK]")

        nav.navigateTopLevel(AppNavGraph.HEALTH_DASHBOARD)

        assertEquals(
            AppNavGraph.UNLOCK,
            nav.currentDestination?.route,
            "a tab tap that lands after the lock pushed the tab over UNLOCK. Stack: ${nav.routes()}",
        )
    }

    @Test
    fun `an unlocked tab tap still switches tab`() {
        val nav = controller(AppNavGraph.HOME)
        nav.navigateTopLevel(AppNavGraph.HEALTH_DASHBOARD)
        assertEquals(AppNavGraph.HEALTH_DASHBOARD, nav.currentDestination?.route)
    }

    @Test
    fun `anything pushed over UNLOCK while locked is sent back to UNLOCK`() {
        val nav = controller(AppNavGraph.UNLOCK)
        val lock = MutableStateFlow<LockState>(LockState.Locked)
        var seals = 0
        scope.launch {
            nav.holdUnlockWhileLocked(lock, hasVaultFiles = { true }) {
                seals++
                nav.lockToUnlock()
            }
        }
        ShadowLooper.idleMainLooper()
        assertEquals(0, seals, "already on UNLOCK: nothing to re-route")

        // Any push, not just the tab path — the guard there is one layer, this is the other.
        nav.navigate(AppNavGraph.HEALTH_DASHBOARD)
        ShadowLooper.idleMainLooper()

        assertEquals(
            listOf(AppNavGraph.UNLOCK),
            nav.routes(),
            "a destination reached the top of the stack over a sealed vault and stayed there",
        )
        assertEquals(1, seals, "re-routed once, and its own arrival on UNLOCK must not re-trigger it")
    }

    @Test
    fun `an open vault navigates freely`() {
        val nav = controller(AppNavGraph.HOME)
        val lock = MutableStateFlow<LockState>(unlocked)
        var seals = 0
        scope.launch { nav.holdUnlockWhileLocked(lock, hasVaultFiles = { true }) { seals++ } }
        ShadowLooper.idleMainLooper()

        nav.navigate(AppNavGraph.SETTINGS)
        ShadowLooper.idleMainLooper()

        assertEquals(AppNavGraph.SETTINGS, nav.currentDestination?.route)
        assertEquals(0, seals)
    }

    @Test
    fun `first-run onboarding is never bounced`() {
        // Onboarding writes the vault before it opens it, so "locked with vault files" is briefly
        // true there; it is the one other route a sealed vault may sit on.
        val nav = controller(AppNavGraph.ONBOARDING)
        val lock = MutableStateFlow<LockState>(LockState.Locked)
        var seals = 0
        scope.launch { nav.holdUnlockWhileLocked(lock, hasVaultFiles = { true }) { seals++ } }
        ShadowLooper.idleMainLooper()

        assertEquals(AppNavGraph.ONBOARDING, nav.currentDestination?.route)
        assertEquals(0, seals)
    }
}

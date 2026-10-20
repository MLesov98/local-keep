package com.localkeep.app.navigation

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A lock decided before the `NavHost` has a graph must still be applied to the back stack the
 * graph restores (O3-02 / S5-02, 2026-10-02).
 *
 * The collector in `MainActivity` is launched above the `NavHost`, which is subcomposed inside the
 * Scaffold, so its first `lockState` emission can arrive before `setGraph()`. That emission used to
 * be dropped, on the theory that `startRoute` already answered the lock state. After process death
 * it does not: `NavController` restores the saved back stack whatever `startDestination` says, and
 * a `Locked` StateFlow never emits again. The user was left on a restored vault screen over a
 * sealed vault with no way to Unlock.
 *
 * Driven with a real `NavHostController` rather than a composed host, because the defect is an
 * ordering — the collector asks, *then* the graph arrives — and a composed host sets its graph
 * whenever composition gets to it. Here the order is fixed by the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LockRoutingRestoredStackTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun controller() = NavHostController(activity).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        setLifecycleOwner(activity)
        setViewModelStore(activity.viewModelStore)
    }

    private fun NavHostController.setTestGraph(start: String) {
        graph = createGraph(startDestination = start) {
            composable(AppNavGraph.UNLOCK) {}
            composable(AppNavGraph.HOME) {}
            composable(AppNavGraph.SETTINGS) {}
        }
    }

    /** A back stack saved while the vault was open: `[HOME, SETTINGS]`. */
    private fun savedVaultStack(): Bundle {
        val before = controller()
        before.setTestGraph(AppNavGraph.HOME)
        before.navigate(AppNavGraph.SETTINGS)
        return requireNotNull(before.saveState()) { "nothing to restore, so this proves nothing" }
    }

    @Test
    fun `a lock that beats the graph is applied to the restored back stack`() {
        val restored = controller()
        restored.restoreState(savedVaultStack())

        // The collector asks first, exactly as it can in MainActivity.
        val route = CoroutineScope(Dispatchers.Unconfined).async { restored.awaitCurrentRoute() }
        assertFalse(route.isCompleted, "no graph yet, so there is no route to decide against")

        // The new process is locked, so startRoute says UNLOCK — and the restore overrides it.
        restored.setTestGraph(AppNavGraph.UNLOCK)
        assertEquals(
            AppNavGraph.SETTINGS,
            restored.currentDestination?.route,
            "the restored stack must win over startDestination, or this test is not the defect",
        )

        val decision = lockNavigation(
            currentRoute = runBlocking { route.await() },
            state = LockState.Locked,
            hasVaultFiles = true,
        )
        assertEquals(
            LockNavigation.TO_UNLOCK,
            decision,
            "a sealed vault restored onto SETTINGS must be sent to UNLOCK; dropping the pre-graph " +
                "emission left it there for good, since Locked never re-emits",
        )
    }

    @Test
    fun `an open vault restored onto the unlock screen is moved off it`() {
        // The mirror case: UNLOCK restored while something else (autofill) opened the vault.
        val before = controller()
        before.setTestGraph(AppNavGraph.UNLOCK)
        val saved = requireNotNull(before.saveState())
        val restored = controller()
        restored.restoreState(saved)

        val route = CoroutineScope(Dispatchers.Unconfined).async { restored.awaitCurrentRoute() }
        restored.setTestGraph(AppNavGraph.HOME)

        assertEquals(
            LockNavigation.TO_HOME,
            lockNavigation(
                runBlocking { route.await() },
                LockState.Unlocked(
                    dek = ByteArray(32),
                    vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
                ),
                hasVaultFiles = true,
            ),
        )
    }

    /** The behaviour above is only the app's if the collector actually uses it. */
    @Test
    fun `the lock collector awaits the route instead of reading it`() {
        val source = File("src/main/kotlin/com/localkeep/app/MainActivity.kt").readText()
        val collector = source.substringAfter("lockManager.lockState.collect", "")
            .substringBefore("when (decision)", "")
        assertTrue(collector.isNotEmpty(), "the lock collector moved; update this test")
        assertTrue(
            "currentRoute = navController.awaitCurrentRoute()" in collector,
            "the collector decides on a route it reads before the graph may exist. Collector:\n$collector",
        )
    }
}

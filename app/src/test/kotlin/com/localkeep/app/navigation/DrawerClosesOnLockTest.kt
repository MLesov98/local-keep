package com.localkeep.app.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A lock with the command drawer open (O3-03, 2026-10-02).
 *
 * Nothing in the lock path touched the drawer. Pressing power with it open left it over the unlock
 * screen, where gestures are disabled so its scrim could not dismiss it, and its rows still
 * navigated: Settings and Backup were pushed above UNLOCK with the vault sealed, so whoever held
 * the phone could turn cloud backup back on or delete the biometric enrolment. In a decoy session
 * the rows it hides grew back the moment the session ended.
 *
 * Composed the way `DrawerOwnsBackOverNavHostTest` composes it — the real shell around a real
 * `NavHost` with stand-in destinations — and locked the way `MainActivity` locks: the vault flag
 * drops and the graph is popped to UNLOCK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1600dp-xhdpi")
class DrawerClosesOnLockTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private var vaultOpen by mutableStateOf(true)

    private fun showShell() {
        compose.setContent {
            LocalKeepTheme {
                val controller = rememberNavController()
                nav = controller
                AppShell(navController = controller, vaultOpen = vaultOpen) {
                    NavHost(navController = controller, startDestination = AppNavGraph.HOME) {
                        composable(AppNavGraph.HOME) { DrawerOpener("vault") }
                        // The unlock stand-in can raise the drawer too. The real one cannot; this is
                        // what lets the row guard be tested apart from the close.
                        composable(AppNavGraph.UNLOCK) { DrawerOpener("unlock") }
                        composable(AppNavGraph.SETTINGS) { Text("settings screen") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun DrawerOpener(label: String) {
        val open = LocalDrawerOpener.current
        Text(label, Modifier.testTag(OPEN_DRAWER).clickable { open() })
    }

    private fun openDrawer() {
        compose.onNodeWithTag(OPEN_DRAWER).performClick()
        compose.waitForIdle()
    }

    private fun lock() {
        vaultOpen = false
        compose.runOnUiThread {
            nav.navigate(AppNavGraph.UNLOCK) { popUpTo(nav.graph.id) { inclusive = true } }
        }
        compose.waitForIdle()
    }

    @Test
    fun `locking with the drawer open closes it`() {
        showShell()
        openDrawer()
        compose.onNodeWithTag(SETTINGS_ROW).assertIsDisplayed()

        lock()

        compose.onNodeWithTag(SETTINGS_ROW).assertIsNotDisplayed()
    }

    @Test
    fun `a drawer row goes nowhere while the vault is locked`() {
        showShell()
        lock()
        openDrawer()

        compose.onNodeWithTag(SETTINGS_ROW).performClick()
        compose.waitForIdle()

        assertEquals(
            AppNavGraph.UNLOCK,
            nav.currentDestination?.route,
            "a drawer row pushed a vault screen above the unlock screen with the vault sealed",
        )
    }

    @Test
    fun `the duress rows are withheld while the vault is locked`() {
        showShell()
        lock()
        openDrawer()

        // LocalDuressSession is false here, as it is the moment a decoy session locks.
        assertEquals(
            0,
            compose.onAllNodesWithTagCount(DURESS_ROW),
            "the drawer drew 'Duress / decoy' with no session open — in front of a coercer, the " +
                "frame a decoy session ends",
        )
    }

    /** Control: without it, a guard that refused every tap would pass the test above. */
    @Test
    fun `a drawer row still navigates while the vault is open`() {
        showShell()
        openDrawer()

        compose.onNodeWithTag(SETTINGS_ROW).performClick()
        compose.waitForIdle()

        assertEquals(AppNavGraph.SETTINGS, nav.currentDestination?.route)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(
        tag: String,
    ): Int = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private companion object {
        const val OPEN_DRAWER = "test-open-drawer"
        const val SETTINGS_ROW = "drawer-${AppNavGraph.SETTINGS}"
        const val DURESS_ROW = "drawer-settings-duress"
    }
}

package com.localkeep.app.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
 * With the command drawer open, the back press belongs to the drawer — not to the nav host
 * underneath it.
 *
 * This is 2A-F10's second life, and the reason it needed one. The first fix made
 * [drawerConsumesBack] correct for all four drawer states and left the handler registered once, at
 * the shell's own composition, with a changing `enabled`. `DrawerConsumesBackTest` went green on
 * the four enum pairs and the defect was unchanged on a device: Health tab, hamburger, BACK, and
 * the drawer stayed open over a Vault list the user had not asked for, 3 of 3.
 *
 * The state was never the problem. `OnBackPressedDispatcher` dispatches to the **last enabled
 * callback it was given**, and `NavHost` registers one from inside its own composition
 * (navigation-compose 2.9.8: `NavHostKt.NavHost` calls
 * `NavComposeUtils_androidKt.PredictiveBackHandler`). The nav host is a descendant of the shell, so
 * its callback always arrives after a callback the shell registers once — and outranks it forever,
 * whatever `enabled` says. Instrumented on an emulator 2026-09-21: the shell logged
 * `consumes=true cur=Open tgt=Open` on the very frame of the press, and its handler was never
 * called.
 *
 * So what has to be asserted is not a predicate over two enums but the **outcome of a real press
 * through a real dispatcher, with a real nav host composed inside the real shell** — which is the
 * only arrangement that reproduces the ordering. Composing the shell without a nav host, or
 * calling the handler directly, would pass against the broken code.
 *
 * What this test cannot prove: anything about the *gesture* path. Predictive back on API 35 picks
 * its target when the swipe starts rather than when it commits, and Robolectric drives neither the
 * animation nor the system's `OnBackInvokedDispatcher`. The mid-animation case is likewise out of
 * reach here — the drawer is settled by the time the rule is idle. Both were driven on a device
 * instead (2026-09-20).
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: the shell needs no Hilt graph, and instantiating the real @HiltAndroidApp one
// would drag a dependency graph into a test about a back callback. Tall window so the drawer sheet
// and the destination behind it both lay out.
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1600dp-xhdpi")
class DrawerOwnsBackOverNavHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    private fun showShellOnHealth() {
        compose.setContent {
            LocalKeepTheme {
                val controller = rememberNavController()
                nav = controller
                AppShell(navController = controller) {
                    NavHost(
                        navController = controller,
                        startDestination = AppNavGraph.HOME,
                    ) {
                        // Stand-ins for the real screens: this test is about which callback the
                        // dispatcher picks, and the real destinations bring Hilt view models that
                        // would decide nothing here.
                        composable(AppNavGraph.HOME) { Text("vault") }
                        composable(AppNavGraph.HEALTH_DASHBOARD) {
                            val openDrawer = LocalDrawerOpener.current
                            Text(
                                "health",
                                Modifier
                                    .testTag(OPEN_DRAWER)
                                    .clickable { openDrawer() },
                            )
                        }
                    }
                }
            }
        }
        compose.runOnUiThread { nav.navigate(AppNavGraph.HEALTH_DASHBOARD) }
        compose.waitForIdle()
        assertEquals(
            AppNavGraph.HEALTH_DASHBOARD,
            nav.currentDestination?.route,
            "the back stack must hold something for Back to pop, or this proves nothing",
        )
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `back with the drawer open does not navigate underneath it`() {
        showShellOnHealth()
        compose.onNodeWithTag(OPEN_DRAWER).performClick()
        compose.waitForIdle()

        pressBack()

        // The finding, in one line: on the broken build this is HOME, with the drawer still drawn
        // over it.
        assertEquals(
            AppNavGraph.HEALTH_DASHBOARD,
            nav.currentDestination?.route,
            "Back with the drawer open must not pop the destination behind the sheet",
        )
    }

    @Test
    fun `the drawer gives back away again once it has closed`() {
        showShellOnHealth()
        compose.onNodeWithTag(OPEN_DRAWER).performClick()
        compose.waitForIdle()

        // First press closes the drawer, second is an ordinary Back. Without this the fix could be
        // "the shell eats every press", which is a worse defect than the one being fixed.
        pressBack()
        pressBack()

        assertEquals(
            AppNavGraph.HOME,
            nav.currentDestination?.route,
            "a second Back, with the drawer gone, must reach the nav host",
        )
    }

    private companion object {
        const val OPEN_DRAWER = "test-open-drawer"
    }
}

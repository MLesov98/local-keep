package com.localkeep.app.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shell against real window insets, composed the way `MainActivity` frames it: a root column
 * that insets the system bars vertically only, the shell inside it.
 *
 * Two defects, both a system inset counted twice:
 *
 * - SCL-023. In landscape the content sat about 50dp further in than the tab bar. `Scaffold`'s
 *   content padding already carries the horizontal system bars, and the content box then applied
 *   [lkHorizontalWindowInsets] on top of it.
 * - S4-F-07. A notice posted while the keyboard is up was placed one navigation-bar height too
 *   high: the keyboard inset is the whole strip from the window's bottom edge, but the root column
 *   has already pushed the shell up by the navigation bar. A pinned action bar lifts by exactly the
 *   notice's height, so the extra offset is what slid the notice over the Save button.
 *
 * Robolectric raises no insets of its own, so they are dispatched to the composition the way
 * `RecoveryKitGateStaysAboveTheKeyboardTest` does it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellInsetsBehaviourTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(lkVerticalWindowInsets),
                ) {
                    val nav = rememberNavController()
                    AppShell(navController = nav) { innerPadding ->
                        ShellContentFrame(innerPadding) {
                            NavHost(navController = nav, startDestination = AppNavGraph.HOME) {
                                composable(AppNavGraph.HOME) {
                                    Box(Modifier.testTag("content-probe")) { BasicText("content") }
                                }
                            }
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun settle(ms: Long = 1_000) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun dispatch(insets: WindowInsetsCompat) {
        compose.runOnUiThread {
            fun deliver(v: android.view.View) {
                if (v.javaClass.name.contains("AndroidComposeView")) {
                    ViewCompat.dispatchApplyWindowInsets(v, insets)
                }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) deliver(v.getChildAt(i))
            }
            deliver(compose.activity.window.decorView)
        }
        settle()
    }

    @Test
    fun `content starts at the system bar inset, not twice it`() {
        show()
        val insetPx = 120
        dispatch(
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(insetPx, 0, 0, 0))
                .build(),
        )
        val left = compose.onNodeWithTag("content-probe").fetchSemanticsNode().positionInRoot.x
        // FAILS WITHOUT THE FIX: the inset is applied by the scaffold's padding and again by the
        // content box, so the probe starts at twice the inset.
        assertTrue(
            abs(left - insetPx) <= 1f,
            "content starts at ${left}px; one inset is ${insetPx}px",
        )
    }

    private fun insets(navPx: Int, imePx: Int) = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, navPx))
        // The keyboard inset runs from the window's bottom edge, navigation bar included.
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
        .setVisible(WindowInsetsCompat.Type.ime(), imePx > 0)
        .build()

    private fun noticeBottom(): Float =
        compose.onNodeWithText("notice-under-test").fetchSemanticsNode()
            .let { it.positionInRoot.y + it.size.height }

    @Test
    fun `a notice sits the same distance above the keyboard whatever the navigation bar is`() {
        show()
        val imePx = 900
        dispatch(insets(navPx = 0, imePx = imePx))
        compose.runOnUiThread { LkNotices.post("notice-under-test") }
        settle()
        val windowHeight = compose.activity.window.decorView.height
        val gapWithoutNavBar = (windowHeight - imePx) - noticeBottom()

        dispatch(insets(navPx = 72, imePx = imePx))
        val gapWithNavBar = (windowHeight - imePx) - noticeBottom()

        // FAILS WITHOUT THE FIX: with a 72px navigation bar the notice floats 72px higher, which is
        // what let a pinned bar lifted by the notice's height end up underneath it.
        assertTrue(
            abs(gapWithNavBar - gapWithoutNavBar) <= 1f,
            "gap above the keyboard: $gapWithoutNavBar px with no navigation bar, $gapWithNavBar px with one",
        )
    }
}

package com.localkeep.feature.vaultlist

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L-02: with the keyboard up, the vault list stopped 229px short of it.
 *
 * Device pass 2026-10-19, 720x1280 at 374dpi, font 2.0 (308x548dp): the list drew down to y=390
 * against a keyboard top of 619, so the focused search field was cut in half and nothing under it
 * could be seen (`REG-P1-search-ime.png`). The cause is double counting. The shell pads the screen
 * above its floating tab bar with a plain `padding(innerPadding)` — that consumes no window
 * insets — and the list then applied `imePadding()`, which takes the *whole* keyboard height off
 * the bottom of an area that had already given up the bar's slot. The blank band was the bar's slot
 * a second time (and, under the keyboard, the bar is not even on screen).
 *
 * ### What is composed
 *
 * [VaultListBody] inside a box that pads its bottom the way the shell does, with no inset
 * consumption, then a keyboard dispatched to the compose view by hand — Robolectric raises none,
 * the same way `ListBackLeavesTheKeyboardFirstTest` does it. The assertion is the one the user
 * sees: the list's bottom edge is the keyboard's top edge. Not above it, not below it.
 *
 * The controls keep the rule from being satisfied by a list that simply ignores the keyboard or
 * always shrinks by a fixed amount: with no keyboard the list ends where its parent does, and a
 * keyboard that stays below the parent's bottom edge costs the list nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w308dp-h548dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListEndsAtTheKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(shellBottom: Int) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                // The shell's `Box(Modifier.padding(innerPadding))`: padding, and nothing consumed.
                Box(Modifier.fillMaxSize().padding(bottom = shellBottom.dp)) {
                    VaultListBody(
                        listState = rememberLazyListState(),
                        onCrumbHeight = {},
                        header = { Text("Header") },
                        crumb = null,
                        notice = null,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.testTag(LIST),
                    ) {
                        items((0 until 40).toList()) { Text("Row $it") }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun raiseKeyboard(heightDp: Int) {
        val px = (heightDp * compose.density.density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread {
            fun deliver(v: android.view.View) {
                if (v.javaClass.name.contains("AndroidComposeView")) ViewCompat.dispatchApplyWindowInsets(v, insets)
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) deliver(v.getChildAt(i))
            }
            deliver(compose.activity.window.decorView)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun windowHeight() = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
    private fun listBottom() = compose.onNodeWithTag(LIST).fetchSemanticsNode().boundsInRoot.bottom
    private fun px(dp: Int) = dp * compose.density.density

    @Test
    fun theListEndsWhereTheKeyboardBeginsOverAShellPaddedBottom() {
        show(shellBottom = SHELL_BAR)
        raiseKeyboard(KEYBOARD)

        assertEquals(
            windowHeight() - px(KEYBOARD),
            listBottom(),
            1f,
            "the list ends at ${listBottom()}px against a keyboard top of ${windowHeight() - px(KEYBOARD)}px: " +
                "the keyboard was subtracted on top of the shell's own ${px(SHELL_BAR)}px bar slot (L-02)",
        )
    }

    @Test
    fun theListEndsAtTheKeyboardWhenTheParentPadsNothing() {
        show(shellBottom = 0)
        raiseKeyboard(KEYBOARD)

        assertEquals(windowHeight() - px(KEYBOARD), listBottom(), 1f)
    }

    /** Control: no keyboard, the list ends where its parent does. */
    @Test
    fun controlWithoutAKeyboardTheListEndsAtItsParent() {
        show(shellBottom = SHELL_BAR)

        assertEquals(windowHeight() - px(SHELL_BAR), listBottom(), 1f)
    }

    /** Control: a keyboard shorter than the bar's slot never reaches the list, so it costs it nothing. */
    @Test
    fun controlAKeyboardThatStaysBelowTheParentCostsNothing() {
        show(shellBottom = SHELL_BAR)
        raiseKeyboard(SHELL_BAR / 2)

        assertEquals(windowHeight() - px(SHELL_BAR), listBottom(), 1f)
    }

    private companion object {
        const val LIST = "list"

        /** About the floating tab bar's slot at this window: 229px at 2.0x is ~98dp. */
        const val SHELL_BAR = 98
        const val KEYBOARD = 165
    }
}

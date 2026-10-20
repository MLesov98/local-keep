package com.localkeep.autofill

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The unlock-to-save step with the keyboard up: the master-password well is whole and on screen,
 * and Continue is reachable.
 *
 * The step was a header and a well in a column that did not scroll, over a pinned Continue under
 * `imePadding()`. On a 308dp window at the largest display size (548dp tall, ~250dp of keyboard)
 * the pinned bar took ~90dp of the 298dp left, the header another ~100dp, and the well got what
 * remained — with no scroll to give it more. The shared keyboard-up rule (`LocalLkChromeUnpinned`)
 * now moves Continue to the end of a scrolling column while the keyboard leaves too little room.
 *
 * Asserted as geometry: the well keeps its natural height (the text node measures 41dp at 2.0 text; it
 * measured 0dp before), and it and Continue can each be brought above the
 * keyboard's top edge. The control is a 411x891 window with a 300dp keyboard, where Continue stays
 * pinned directly over the keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SaveConfirmUnlockKeyboardUnpinsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settle(ms: Long = 1_000) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                LkScreen {
                    SaveConfirmUnlockContent(
                        title = "Unlock LocalKeep",
                        subtitle = "Your vault is locked. Unlock it to store this login.",
                        passwordLabel = "Master password",
                        continueLabel = "Continue",
                        unlockingLabel = "Unlocking…",
                        errorMessage = "",
                        pending = false,
                        onSubmit = {},
                    )
                }
            }
        }
        settle()
    }

    private fun raiseKeyboard(heightPx: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightPx))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
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

    private fun scrollers() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    /**
     * Scrolls the screen's scroll region, if it has one, until [target]'s bottom edge is inside it.
     * See `LkFolderPickerKeyboardUnpinsTest.scrollIntoView` for why this is not `performScrollTo`.
     */
    private fun scrollIntoView(target: SemanticsNodeInteraction) {
        repeat(4) {
            if (scrollers().fetchSemanticsNodes().isEmpty()) return
            val s = scrollers()[0].fetchSemanticsNode()
            val t = target.fetchSemanticsNode()
            val dy = (t.positionOnScreen.y + t.size.height) - (s.positionOnScreen.y + s.size.height)
            if (dy <= 0f) return
            compose.runOnUiThread { s.config[SemanticsActions.ScrollBy].action!!.invoke(0f, dy) }
            settle(500)
        }
    }

    private fun bottomDp(node: SemanticsNode) = (node.positionOnScreen.y + node.size.height) / compose.density.density

    private fun keyboardTopDp(px: Int) = (compose.activity.window.decorView.height - px) / compose.density.density

    private fun continueButton() = compose.onNodeWithText("Continue")

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `the well and Continue are reachable above the keyboard on a 308dp window`() {
        show()
        raiseKeyboard(500)
        val keyboardTop = keyboardTopDp(500)
        val well = compose.onNode(hasSetTextAction())
        compose.runOnUiThread { well.fetchSemanticsNode().config[SemanticsActions.RequestFocus].action!!.invoke() }
        settle(1_000)

        // FAILS WITHOUT THE FIX: nothing scrolls, and the pinned bar and the header leave the well
        // crushed or off the screen.
        scrollIntoView(well)
        val w = well.fetchSemanticsNode()
        assertTrue(
            w.size.height / compose.density.density >= 40f &&
                w.positionOnScreen.y >= 0f &&
                bottomDp(w) <= keyboardTop + 1f,
            "the master-password well is ${w.size.height / compose.density.density}dp tall and spans " +
                "${w.positionOnScreen.y / compose.density.density}dp..${bottomDp(w)}dp with the keyboard " +
                "starting at ${keyboardTop}dp: it is crushed or under the keys, and nothing scrolls it into reach",
        )
        scrollIntoView(continueButton())
        val b = continueButton().fetchSemanticsNode()
        assertTrue(
            bottomDp(b) <= keyboardTop + 1f && b.positionOnScreen.y >= 0f,
            "Continue spans ${b.positionOnScreen.y / compose.density.density}dp..${bottomDp(b)}dp with the keyboard " +
                "starting at ${keyboardTop}dp",
        )
        // And the two are not stacked on top of each other: Continue sits below the well.
        assertTrue(b.positionOnScreen.y >= w.positionOnScreen.y + w.size.height - 1f, "Continue overlaps the well")
    }

    /** The control: with room to spare, Continue stays pinned directly over the keyboard. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tall window keeps Continue pinned over the keyboard`() {
        show()
        raiseKeyboard(900)
        val keyboardTop = keyboardTopDp(900)
        val gap = keyboardTop - bottomDp(continueButton().fetchSemanticsNode())
        assertTrue(gap in 0f..40f, "Continue sits ${gap}dp above the keyboard; with room to spare it must stay pinned over the keys")
    }
}

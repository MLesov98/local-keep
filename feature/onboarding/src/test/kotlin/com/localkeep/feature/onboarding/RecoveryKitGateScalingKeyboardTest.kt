package com.localkeep.feature.onboarding

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
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Recovery Kit gate at the smallest window and the largest display size, keyboard up
 * (308x548dp, 2.0 text, ~250dp of keyboard).
 *
 * Checked for the shared keyboard-up rule (`rememberLkKeyboardUnpinsChrome`) and found not to need
 * it: the header, the gate and the submit button are all children of ONE scroll column under
 * `imePadding()`, and the only pinned strip on the screen — `RecoveryKitDoneBar` — is composed only
 * once a kit has been saved, which cannot happen at the same time as the gate (`authorized` is
 * never reset). So there is no pinned chrome to give back, and this holds that: the scroll region
 * is all of the room above the keyboard, and the password well and the submit button can each be
 * brought above the keyboard's top edge.
 *
 * Insets are dispatched by hand because Robolectric raises no keyboard; see
 * `RecoveryKitGateStaysAboveTheKeyboardTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryKitGateScalingKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settle(ms: Long = 1_000) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun show() {
        val viewModel = mock<RecoveryKitViewModel>()
        whenever(viewModel.state).thenReturn(MutableStateFlow(RecoveryKitUiState()))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme { RecoveryKitScreen(viewModel = viewModel, onBack = {}) }
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

    private fun scroller(): SemanticsNode = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    )[0].fetchSemanticsNode()

    /** See `LkFolderPickerKeyboardUnpinsTest.scrollIntoView` for why this is not `performScrollTo`. */
    private fun scrollIntoView(target: SemanticsNodeInteraction) {
        repeat(4) {
            val s = scroller()
            val t = target.fetchSemanticsNode()
            val dy = (t.positionOnScreen.y + t.size.height) - (s.positionOnScreen.y + s.size.height)
            if (dy <= 0f) return
            compose.runOnUiThread { s.config[SemanticsActions.ScrollBy].action!!.invoke(0f, dy) }
            settle(500)
        }
    }

    private fun bottomDp(node: SemanticsNode) = (node.positionOnScreen.y + node.size.height) / compose.density.density

    @Test
    fun `the gate's well and submit are reachable and nothing is pinned around the scroll`() {
        show()
        raiseKeyboard(500)
        val keyboardTop = (compose.activity.window.decorView.height - 500) / compose.density.density
        val s = scroller()
        val viewportTop = s.positionOnScreen.y / compose.density.density
        val viewportBottom = bottomDp(s)
        assertTrue(
            viewportTop <= 1f && viewportBottom >= keyboardTop - 1f,
            "the scroll region spans ${viewportTop}dp..${viewportBottom}dp but the keyboard starts at ${keyboardTop}dp: " +
                "something is pinned around it",
        )
        val well = compose.onNode(hasSetTextAction())
        compose.runOnUiThread { well.fetchSemanticsNode().config[SemanticsActions.RequestFocus].action!!.invoke() }
        settle(1_000)
        scrollIntoView(well)
        assertTrue(bottomDp(well.fetchSemanticsNode()) <= keyboardTop + 1f, "the vault-password well ends below the keyboard's top edge")
        val submit = compose.onNodeWithText(compose.activity.getString(R.string.onboarding_recoverykit_gate_submit))
        scrollIntoView(submit)
        assertTrue(
            bottomDp(submit.fetchSemanticsNode()) <= keyboardTop + 1f,
            "the gate's submit ends ${bottomDp(submit.fetchSemanticsNode())}dp down, below the keyboard's top edge at ${keyboardTop}dp",
        )
    }
}

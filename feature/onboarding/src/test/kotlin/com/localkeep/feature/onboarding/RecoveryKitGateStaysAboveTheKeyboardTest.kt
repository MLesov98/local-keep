package com.localkeep.feature.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * The Recovery Kit gate's vault-password well is not left under the keyboard it opens.
 *
 * `MainActivity` is edge-to-edge and keeps the IME out of the shell's padding, so a screen that
 * should sit above the keyboard has to say so itself. This one's scroll column kept the full height
 * of the window with the keyboard up, and `LkTextField`'s bring-into-view had nothing to scroll
 * because the viewport never shrank — on a short window or at a large font scale the well that
 * guards the kit sat behind the keys.
 *
 * The keyboard is simulated by dispatching an IME inset to the composition, as
 * `PasswordStepImeScrollTest` does: Robolectric raises none. The view model is a mock with its
 * state stubbed — the screen reads one flow and calls back into it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h480dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryKitGateStaysAboveTheKeyboardTest {

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

    private val keyboardPx get() = (240 * compose.density.density).toInt()
    private val windowHeight get() = compose.activity.window.decorView.height
    private val keyboardTop get() = (windowHeight - keyboardPx).toFloat()

    private fun wellBottom(): Float =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().let { it.positionOnScreen.y + it.size.height }

    /**
     * Control: with no keyboard the well is on screen and ends below where the keyboard will start,
     * so the test below has something to lift.
     */
    @Test
    fun `without a keyboard the well is on screen and in the keyboard's zone`() {
        show()
        val bottom = wellBottom()
        assertTrue(
            bottom > keyboardTop && bottom <= windowHeight,
            "the well ends at ${bottom}px; the keyboard will start at ${keyboardTop}px of ${windowHeight}px, " +
                "so this window proves nothing",
        )
    }

    @Test
    fun `the focused gate well ends above the keyboard`() {
        show()
        raiseKeyboard(keyboardPx)
        compose.runOnUiThread {
            compose.onNode(hasSetTextAction()).fetchSemanticsNode()
                .config[SemanticsActions.RequestFocus].action!!.invoke()
        }
        settle(2_000)
        val bottom = wellBottom()
        assertTrue(
            bottom <= keyboardTop,
            "the well ends at ${bottom}px but the keyboard starts at ${keyboardTop}px, so the " +
                "vault password is being typed from under the keyboard",
        )
    }
}

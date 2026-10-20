package com.localkeep.feature.unlock

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The backup-password well on the pre-unlock restore is not left under the keyboard it opens.
 *
 * `MainActivity` is edge-to-edge and keeps the IME out of the shell's padding, so a screen that
 * should sit above the keyboard has to say so itself. This one's scroll column kept the full height
 * of the window with the keyboard up, and `LkTextField`'s bring-into-view had nothing to scroll
 * because the viewport never shrank. It is the screen of someone whose vault will not open, typing
 * the password of a backup they are about to replace it with.
 *
 * Measured in landscape (a 360dp-tall window with a 200dp keyboard): in portrait the well sits near
 * the top of the screen and clears any keyboard, so the defect only shows where the window is short.
 *
 * The keyboard is simulated by dispatching an IME inset to the composition, as
 * `PasswordStepImeScrollTest` does: Robolectric raises none. The view model is a mock — the screen
 * only calls back into it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreUnlockRestorePasswordStaysAboveTheKeyboardTest {

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
                PreUnlockRestoreScreen(
                    state = RestoreUiState(
                        phase = RestorePhase.PROVE,
                        selected = RestoreChoice.PickedFile("content://backup/vault"),
                    ),
                    viewModel = mock<UnlockViewModel>(),
                )
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

    private val keyboardPx get() = (200 * compose.density.density).toInt()
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
    fun `the focused password well ends above the keyboard`() {
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
                "backup password is being typed from under the keyboard",
        )
    }
}

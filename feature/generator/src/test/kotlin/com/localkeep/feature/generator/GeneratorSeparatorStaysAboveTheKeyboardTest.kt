package com.localkeep.feature.generator

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.model.Vault
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import java.time.Instant
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
 * The passphrase separator field is not left under the keyboard it opens.
 *
 * `MainActivity` is edge-to-edge and deliberately keeps the IME out of the shell's padding (see its
 * note on `safeDrawingPadding()`), so a screen that wants to sit above the keyboard has to say so
 * itself. This one did not: its scrolling column kept the full window height with the keyboard up,
 * and `LkTextField`'s bring-into-view had nothing to scroll because the viewport never shrank. The
 * Separator well is the last control on the screen, so tapping it put the keyboard directly over
 * the thing being typed into (device, S3-F-18).
 *
 * The keyboard is simulated by dispatching an IME inset to the composition, as
 * `PasswordStepImeScrollTest` does: Robolectric raises none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeneratorSeparatorStaysAboveTheKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun viewModel(): GeneratorViewModel {
        val lockManager: VaultLockManager = mock()
        whenever(lockManager.lockState).thenReturn(
            MutableStateFlow<LockState>(
                LockState.Unlocked(
                    dek = ByteArray(32),
                    vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
                ),
            ),
        )
        return GeneratorViewModel(lockManager)
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
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** Where the separator well ends before the keyboard, after it, and where the keyboard starts. */
    private class Measured(val beforeKeyboard: Float, val withKeyboard: Float, val keyboardTop: Float, val window: Int)

    private fun focusedWellAgainstKeyboard(passphrase: Boolean): Measured {
        val vm = viewModel()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme { GeneratorScreen(viewModel = vm) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        if (passphrase) {
            compose.runOnUiThread { vm.setMode(GeneratorUiState.Mode.PASSPHRASE) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        val field = compose.onNode(hasSetTextAction())
        // What the user does first: scroll the last control into view with the keyboard still down.
        // Driven through the semantics actions rather than `performScrollTo`/`performClick`, which
        // wait for idle — and this screen's aura never goes idle, so they never return.
        compose.runOnUiThread {
            val scroller = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy),
            )[0].fetchSemanticsNode()
            scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 100_000f)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        val before = field.fetchSemanticsNode().let { it.positionOnScreen.y + it.size.height }
        val keyboardPx = (300 * compose.density.density).toInt()
        raiseKeyboard(keyboardPx)
        compose.runOnUiThread {
            field.fetchSemanticsNode().config[SemanticsActions.RequestFocus].action!!.invoke()
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()

        val node = field.fetchSemanticsNode()
        val windowHeight = compose.activity.window.decorView.height
        return Measured(
            beforeKeyboard = before,
            withKeyboard = node.positionOnScreen.y + node.size.height,
            keyboardTop = (windowHeight - keyboardPx).toFloat(),
            window = windowHeight,
        )
    }

    @Test
    fun `the focused separator well ends above the keyboard`() {
        val m = focusedWellAgainstKeyboard(passphrase = true)
        assertTrue(
            m.withKeyboard <= m.keyboardTop,
            "the separator well ends at ${m.withKeyboard}px but the keyboard starts at " +
                "${m.keyboardTop}px, so the field being typed into is under the keyboard",
        )
    }

    /**
     * Control: scrolled to the end with no keyboard, the well is on screen and below where the
     * keyboard will start — so the test above has something to lift, and a layout that simply hid
     * the well or never scrolled to it could not pass it by accident.
     */
    @Test
    fun `without a keyboard the well is on screen and low enough to be covered by one`() {
        val m = focusedWellAgainstKeyboard(passphrase = true)
        assertTrue(m.beforeKeyboard <= m.window, "the well was not on screen before the keyboard: ${m.beforeKeyboard}px of ${m.window}px")
        assertTrue(
            m.beforeKeyboard > m.keyboardTop,
            "the well starts above where the keyboard will, so this window proves nothing: " +
                "${m.beforeKeyboard}px against ${m.keyboardTop}px",
        )
    }
}

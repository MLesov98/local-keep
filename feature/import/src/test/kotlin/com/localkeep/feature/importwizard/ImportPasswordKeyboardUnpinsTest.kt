package com.localkeep.feature.importwizard

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The import wizard's password step with the keyboard up: the password well is on screen and
 * "Open database" is reachable.
 *
 * The step is a pinned header, a scroll region and a pinned "Open database", all inside a column
 * with `imePadding()`. On a 308dp window at the largest display size (548dp tall, ~250dp of
 * keyboard) the header and the button take their share of the 298dp that are left and the well
 * shares what remains with the explanation above it. The shared keyboard-up rule
 * (`rememberLkKeyboardUnpinsChrome`) now lets the header and the button ride the scroll while the
 * keyboard leaves too little room.
 *
 * Asserted as geometry: the scroll region gets all the room above the keyboard, and the well and
 * the button can each be scrolled above the keyboard's top edge. The control is a 411x891 window,
 * where "Open database" stays pinned directly over the keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportPasswordKeyboardUnpinsTest {

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
                WizardFrame(step = ImportUiState.Step.PASSWORD) {
                    LkScreenHeader(
                        title = stringResource(R.string.import_screen_title),
                        subtitle = "Step 3 of 5",
                    )
                    PasswordStep(
                        state = ImportUiState(
                            step = ImportUiState.Step.PASSWORD,
                            fileName = "passwords.kdbx",
                            password = "hunter2",
                            passwordIsForDatabase = true,
                        ),
                        onPasswordChanged = {},
                        onSubmit = {},
                        onAddKeyFile = {},
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

    private fun keyboardTopDp(px: Int) = (compose.activity.window.decorView.height - px) / compose.density.density

    private fun open() = compose.onNodeWithText(compose.activity.getString(R.string.import_password_submit))

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `the well and Open database are reachable and the scroll gets all the room above the keyboard`() {
        show()
        raiseKeyboard(500)
        val keyboardTop = keyboardTopDp(500)
        val well = compose.onNode(hasSetTextAction())

        // FAILS WITHOUT THE FIX: the pinned header above and the pinned button below keep their share.
        val s = scroller()
        val viewportTop = s.positionOnScreen.y / compose.density.density
        val viewportBottom = (s.positionOnScreen.y + s.size.height) / compose.density.density
        assertTrue(
            viewportTop <= 1f && viewportBottom >= keyboardTop - 1f,
            "the scroll region spans ${viewportTop}dp..${viewportBottom}dp of a window whose keyboard starts " +
                "at ${keyboardTop}dp: the header and the button are pinned around it and take " +
                "${viewportTop + (keyboardTop - viewportBottom)}dp of the room the password is typed into",
        )
        scrollIntoView(well)
        assertTrue(bottomDp(well.fetchSemanticsNode()) <= keyboardTop + 1f, "the password well ends below the keyboard's top edge")
        scrollIntoView(open())
        assertTrue(
            bottomDp(open().fetchSemanticsNode()) <= keyboardTop + 1f,
            "Open database ends ${bottomDp(open().fetchSemanticsNode())}dp down, below the keyboard's top edge at ${keyboardTop}dp",
        )
    }

    /**
     * The control: with room to spare, today's arrangement stays — the header is pinned above the
     * scroll region (which therefore starts below it) and the button sits clear of the keyboard.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tall window keeps the header pinned above the scroll with the keyboard up`() {
        show()
        raiseKeyboard(900)
        val keyboardTop = keyboardTopDp(900)
        val viewportTop = scroller().positionOnScreen.y / compose.density.density
        assertTrue(
            viewportTop >= 46f,
            "the scroll region starts ${viewportTop}dp down the window; with room to spare the header is " +
                "pinned above it and must not have moved into the scroll",
        )
        assertTrue(bottomDp(open().fetchSemanticsNode()) <= keyboardTop + 1f, "Open database ends below the keyboard's top edge")
    }
}

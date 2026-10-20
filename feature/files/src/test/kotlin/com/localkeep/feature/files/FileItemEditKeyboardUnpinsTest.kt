package com.localkeep.feature.files

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
 * The File editor with the keyboard up: the title field being typed into and Save are both
 * reachable, and the scroll region gets everything above the keyboard.
 *
 * Save was pinned in `LkActionScaffold`'s action bar and the header rode the scroll, so on a 308dp
 * window at the largest display size (548dp tall, ~250dp of keyboard) the bar took ~90dp of the 298dp
 * that were left and the field being edited shared what remained with it. The shared keyboard-up
 * rule (`LocalLkChromeUnpinned`) now moves Save to the end of the scroll while the keyboard leaves
 * too little room, as `UnlockFrame` does.
 *
 * Asserted as geometry: the scroll region is as tall as the room above the keyboard (nothing pinned
 * takes a share of it), and the title field and Save can each be scrolled above the keyboard's top
 * edge. The control is a 411x891 window, where Save stays pinned directly over the keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileItemEditKeyboardUnpinsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settle(ms: Long = 1_000) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun show() {
        val viewModel = mock<FileItemEditViewModel>()
        whenever(viewModel.state).thenReturn(
            MutableStateFlow(
                FileItemEditUiState(
                    isNew = false,
                    title = "Passport scan",
                    filename = "passport.pdf",
                    mime = "application/pdf",
                    sizeBytes = 120_000L,
                    blobRef = "blob-1",
                ),
            ),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                FileItemEditScreen(
                    viewModel = viewModel,
                    itemId = null,
                    onManageAttachments = {},
                    onSaved = {},
                    onBack = {},
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

    private fun scroller(): SemanticsNode = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    )[0].fetchSemanticsNode()

    /**
     * Scrolls the screen's one scroll region until [target]'s bottom edge is inside it. The semantics
     * `ScrollBy` action rather than `performScrollTo`: with `autoAdvance = false` the latter waits on
     * a clock nothing advances.
     */
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

    private fun save() = compose.onNodeWithText(compose.activity.getString(R.string.files_edit_save))

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `the title field and Save are reachable and the scroll gets all the room above the keyboard`() {
        show()
        raiseKeyboard(500)
        val keyboardTop = keyboardTopDp(500)
        val field = compose.onNode(hasSetTextAction())
        compose.runOnUiThread { field.fetchSemanticsNode().config[SemanticsActions.RequestFocus].action!!.invoke() }
        settle(1_000)

        // FAILS WITHOUT THE FIX: Save's bar is pinned over the keys and takes its share of the 298dp.
        val s = scroller()
        val viewportBottom = (s.positionOnScreen.y + s.size.height) / compose.density.density
        assertTrue(
            viewportBottom >= keyboardTop - 1f,
            "the scroll region ends at ${viewportBottom}dp but the keyboard starts at ${keyboardTop}dp: " +
                "something pinned (Save's bar) is taking ${keyboardTop - viewportBottom}dp of the room the " +
                "title field is typed into",
        )
        scrollIntoView(field)
        assertTrue(bottomDp(field.fetchSemanticsNode()) <= keyboardTop + 1f, "the title field ends below the keyboard's top edge")
        scrollIntoView(save())
        assertTrue(
            bottomDp(save().fetchSemanticsNode()) <= keyboardTop + 1f,
            "Save ends ${bottomDp(save().fetchSemanticsNode())}dp down, below the keyboard's top edge at ${keyboardTop}dp",
        )
    }

    /** The control: with room to spare, Save stays pinned directly over the keyboard. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tall window keeps Save pinned over the keyboard`() {
        show()
        raiseKeyboard(900)
        val keyboardTop = keyboardTopDp(900)
        val gap = keyboardTop - bottomDp(save().fetchSemanticsNode())
        assertTrue(gap in 0f..40f, "Save sits ${gap}dp above the keyboard; with room to spare it must stay pinned over the keys")
    }
}

package com.localkeep.core.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.ui.R
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * New folder with the keyboard up must show the name field, Create and Cancel (SCL-001, blocker).
 *
 * The naming card opens with its field focused, so the keyboard is up from the first frame. The
 * picker pinned its header and its Create / Cancel block around the scroll and put `imePadding()`
 * on the lot, which left the scroll region 0dp on a 308dp-wide window at the largest display size
 * (548dp tall, ~250dp of keyboard): the field and both buttons were gone and nothing scrolled.
 *
 * Now the header and the buttons scroll with the list while the keyboard leaves too little room
 * (`rememberLkKeyboardUnpinsChrome`). Asserted as geometry: each of the three can be scrolled to,
 * and ends above the keyboard's top edge. The control is a 411x891 window with a 300dp keyboard,
 * where Create stays pinned directly over the keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkFolderPickerKeyboardUnpinsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                LkFolderPickerScreen(
                    rows = listOf(
                        LkFolderRow("a", "Personal", depth = 0),
                        LkFolderRow("b", "Work", depth = 0),
                    ),
                    selectedId = null,
                    onPick = {},
                    onBack = {},
                    newFolderName = "",
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
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

    /**
     * Scrolls the screen's one scroll region until [target]'s bottom edge is inside it.
     *
     * Not `performScrollTo`: with `autoAdvance = false` (the aura never goes idle) that waits on a
     * clock nothing advances and spins until the heap is gone. The semantics `ScrollBy` action is
     * the same gesture without the wait; the clock is advanced by hand.
     */
    private fun scrollIntoView(target: androidx.compose.ui.test.SemanticsNodeInteraction) {
        repeat(4) {
            val scroller = compose.onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange,
                ),
            )[0].fetchSemanticsNode()
            val t = target.fetchSemanticsNode()
            val dy = (t.positionOnScreen.y + t.size.height) - (scroller.positionOnScreen.y + scroller.size.height)
            if (dy <= 0f) return
            compose.runOnUiThread {
                scroller.config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy].action!!.invoke(0f, dy)
            }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    private fun bottomDp(node: SemanticsNode) =
        (node.positionOnScreen.y + node.size.height) / compose.density.density

    private fun keyboardTopDp(px: Int) = (compose.activity.window.decorView.height - px) / compose.density.density

    private fun above(what: String, node: SemanticsNode, keyboardTop: Float) = assertTrue(
        bottomDp(node) <= keyboardTop + 1f,
        "$what ends ${bottomDp(node)}dp down the window, below the keyboard's top edge at " +
            "${keyboardTop}dp — covered, and nothing scrolls it into reach",
    )

    private fun create() = compose.onNodeWithText(compose.activity.getString(R.string.core_ui_folder_create_ready_a11y))

    private fun cancel() = compose.onNodeWithText(compose.activity.getString(R.string.core_ui_folder_screen_cancel))

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `the name field and both buttons are reachable above the keyboard on a 308dp window`() {
        show()
        raiseKeyboard(500)
        val keyboardTop = keyboardTopDp(500)
        val field = compose.onNode(hasSetTextAction())

        // FAILS WITHOUT THE FIX: the scroll region was 0dp. Asserted before scrolling, because
        // `performScrollTo` into a 0dp viewport does not fail, it spins.
        val viewport = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )[0].fetchSemanticsNode().size.height / compose.density.density
        assertTrue(
            viewport >= 48f,
            "the scroll region is ${viewport}dp tall with the keyboard up, so the 48dp name field " +
                "cannot be shown at any scroll position (SCL-001)",
        )
        scrollIntoView(field)
        above("the New folder name field", field.fetchSemanticsNode(), keyboardTop)
        scrollIntoView(create())
        above("Create", create().fetchSemanticsNode(), keyboardTop)
        scrollIntoView(cancel())
        above("Cancel", cancel().fetchSemanticsNode(), keyboardTop)
    }

    /**
     * With the chrome unpinned, Create is on screen with the field — not one scroll further down.
     *
     * Measured on a 360x640 emulator, 2026-10-20: the card's reveal brought the card into view and
     * nothing else, so Create sat under the viewport's bottom edge directly beneath it. A tap on it
     * landed on nothing, the keyboard stayed up and no folder was made
     * (`LkFolderPickerFieldStaysAboveTheKeyboardAfterCreateTest`, which CI's Pixel-sized emulator
     * never reaches). Asserted without scrolling, because the defect is where the screen lands.
     */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun `the name field and Create are both on screen above the keyboard on a 360x640 window`() {
        show()
        raiseKeyboard(600)
        val keyboardTop = keyboardTopDp(600)
        val field = compose.onNode(hasSetTextAction())
        for ((what, node) in listOf("the New folder name field" to field, "Create" to create())) {
            val visible = node.getBoundsInRoot()
            assertTrue(visible.bottom > visible.top, "$what is entirely outside the list's viewport")
            assertEquals(node.getUnclippedBoundsInRoot(), visible, "$what is cut off by the list's viewport")
            above(what, node.fetchSemanticsNode(), keyboardTop)
        }
    }

    /** The control: with room to spare, Create stays pinned directly over the keyboard. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tall window keeps Create and Cancel pinned over the keyboard`() {
        show()
        raiseKeyboard(900)
        val keyboardTop = keyboardTopDp(900)
        above("Cancel", cancel().fetchSemanticsNode(), keyboardTop)
        assertTrue(
            keyboardTop - bottomDp(cancel().fetchSemanticsNode()) < 40f,
            "Cancel sits ${keyboardTop - bottomDp(cancel().fetchSemanticsNode())}dp above the keyboard; " +
                "with room to spare the pinned block must stay directly over the keys",
        )
    }
}

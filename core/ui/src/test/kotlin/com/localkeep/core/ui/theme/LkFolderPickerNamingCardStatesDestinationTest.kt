package com.localkeep.core.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The naming card says where the new folder will go, and the caption takes you to that row
 * (D3-F-01 remainder).
 *
 * ### What was wrong
 *
 * The vault list's New folder screen preselects the parent, but with the keyboard up the list opens
 * scrolled to its bottom — the naming card, which sits under every folder, wins the reveal — so the
 * selected parent row (D7 in the report, found ~16 swipes up) is off screen and the user cannot see
 * where the folder will be made.
 *
 * ### What is asserted
 *
 * In create mode (no `onCreateStart`, the card open from the start), at 308x548dp, 2.0 text, with a
 * 250dp keyboard: the card carries "Inside D7" (or "At the top level" for the root), the caption is
 * itself on screen above the keyboard, and activating it brings the selected row into the scroll
 * region. In pick mode — the item editors, where the selected row is the item's filing and not the
 * new folder's parent — the caption is absent: stating it there would be false.
 *
 * Robolectric raises no keyboard, so the inset is dispatched by hand, as in
 * `LkFolderPickerKeyboardUnpinsTest`. `autoAdvance = false` because the aura never goes idle, which
 * is also why the caption is activated through its semantics action rather than `performClick`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkFolderPickerNamingCardStatesDestinationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rows = (1..20).map { LkFolderRow("d$it", "D$it", depth = 0) }

    private fun show(selected: String?, createMode: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                LkFolderPickerScreen(
                    rows = rows,
                    selectedId = selected,
                    onPick = {},
                    onBack = {},
                    onCreateStart = if (createMode) null else ({}),
                    newFolderName = "",
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        raiseKeyboard(KEYBOARD_PX)
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

    private fun scroller(): SemanticsNode = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    )[0].fetchSemanticsNode()

    private fun scrollBy(dy: Float) {
        val s = scroller()
        compose.runOnUiThread { s.config[SemanticsActions.ScrollBy].action!!.invoke(0f, dy) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun insideViewport(node: SemanticsNode): Boolean {
        val s = scroller()
        val keyboardTop = compose.activity.window.decorView.height - KEYBOARD_PX
        val top = s.positionOnScreen.y
        val bottom = minOf(s.positionOnScreen.y + s.size.height, keyboardTop.toFloat())
        return node.positionOnScreen.y >= top - 1f && node.positionOnScreen.y + node.size.height <= bottom + 1f
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `the card says it is inside the selected parent, in view above the keyboard`() {
        show(selected = "d7", createMode = true)
        // FAILS WITHOUT THE FIX: no node says where the folder will go.
        val caption = compose.onNodeWithText("Inside D7").fetchSemanticsNode()
        assertTrue(insideViewport(caption), "the destination caption is not on screen above the keyboard")
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `with no parent selected the card says the top level`() {
        show(selected = null, createMode = true)
        compose.onNodeWithText("At the top level").fetchSemanticsNode()
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `activating the caption brings the selected row into view`() {
        show(selected = "d7", createMode = true)
        // The state the device report describes: the list at the card, the selected row far above.
        scrollBy(100_000f)
        assertTrue(
            !insideViewport(compose.onNodeWithText("D7").fetchSemanticsNode()),
            "setup: D7 must be off screen before the caption is used",
        )
        val caption = compose.onNodeWithText("Inside D7").fetchSemanticsNode()
        compose.runOnUiThread { caption.config[SemanticsActions.OnClick].action!!.invoke() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(
            insideViewport(compose.onNodeWithText("D7").fetchSemanticsNode()),
            "the selected row is still off screen after the caption was used",
        )
    }

    /** The control: in pick mode the selected row is the item's filing, so the caption would lie. */
    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `pick mode has no destination caption`() {
        show(selected = "d7", createMode = false)
        compose.onAllNodesWithText("Inside D7").assertCountEquals(0)
    }

    private companion object {
        /** 250dp at xhdpi (density 2). */
        const val KEYBOARD_PX = 500
    }
}

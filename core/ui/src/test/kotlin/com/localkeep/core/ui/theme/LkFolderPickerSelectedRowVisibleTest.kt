package com.localkeep.core.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * The folder the picker marks as chosen is on screen (D3-F-01, S2-F-04).
 *
 * The list scrolled to the naming card, to a created folder, to nothing else: a long tree opened at
 * its top whatever was selected, so the one row that says "this is the answer" could be a screen
 * away, and with the keyboard up it sat under it.
 *
 * FAILS WITHOUT THE FIX: the selected row's text is laid out below the scroll region (never scrolled
 * to) both before and after the keyboard rises.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkFolderPickerSelectedRowVisibleTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(selected: String?) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                LkFolderPickerScreen(
                    rows = (0 until 40).map { LkFolderRow("f$it", "Folder $it", depth = 0) },
                    selectedId = selected,
                    onPick = {},
                    onBack = {},
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

    private fun scroller(): SemanticsNode = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    )[0].fetchSemanticsNode()

    private fun assertInside(name: String, keyboardPx: Int) {
        val row = compose.onNodeWithText(name).fetchSemanticsNode()
        val top = scroller().positionOnScreen.y
        val bottom = minOf(
            scroller().positionOnScreen.y + scroller().size.height,
            (compose.activity.window.decorView.height - keyboardPx).toFloat(),
        )
        val rowTop = row.positionOnScreen.y
        val rowBottom = rowTop + row.size.height
        assertTrue(
            rowTop >= top - 1f && rowBottom <= bottom + 1f,
            "\"$name\" is at y=$rowTop..$rowBottom, outside the visible list region $top..$bottom",
        )
    }

    @Test
    fun aRowFarDownTheTreeIsOnScreenWhenItIsTheSelectedOne() {
        show(selected = "f30")
        assertInside("Folder 30", keyboardPx = 0)
    }

    @Test
    fun itStaysAboveTheKeyboardWhenTheKeyboardRises() {
        show(selected = "f30")
        raiseKeyboard(900)
        assertInside("Folder 30", keyboardPx = 900)
    }

    /** Control: nothing selected below the fold, so the list stays at its top. */
    @Test
    fun withTheTopLevelChosenTheListStaysAtItsTop() {
        show(selected = null)
        assertInside("Folder 0", keyboardPx = 0)
    }
}

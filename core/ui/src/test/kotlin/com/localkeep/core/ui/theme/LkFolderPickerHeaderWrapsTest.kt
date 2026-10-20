package com.localkeep.core.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
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
 * The folder picker's title must not be starved by the fold control beside it (SCL-018).
 *
 * In German "Alle einklappen" measured 189 of the 256dp the header row has at the 308dp floor. The
 * title sat in `weight(1f)` and took the 67dp left, so "Neuer Ordner" broke into four fragments.
 * The header is a flow now: the title keeps its natural width and the control moves to its own
 * line when they do not fit together. The control is a 411dp window, where they still share a line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkFolderPickerHeaderWrapsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                LkFolderPickerScreen(
                    rows = listOf(
                        LkFolderRow("a", "Personal", depth = 0, hasChildren = true),
                        LkFolderRow("b", "Work", depth = 1),
                    ),
                    selectedId = null,
                    onPick = {},
                    onBack = {},
                    onCollapseAll = {},
                    onExpandAll = {},
                    title = TITLE,
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun titleLines(): Int {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(TITLE).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action?.invoke(results)
        return results.single().lineCount
    }

    @Test
    @Config(qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `German at the 308dp floor keeps the title on one line`() {
        show()
        assertEquals(1, titleLines(), "the title is broken across lines beside the fold control")
    }

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xxhdpi")
    fun `a roomy window keeps the title and the control on one row`() {
        show()
        assertEquals(1, titleLines())
        val title = compose.onNodeWithText(TITLE).fetchSemanticsNode()
        val control = compose.onNodeWithText(
            compose.activity.getString(R.string.core_ui_folder_collapse_all),
        ).fetchSemanticsNode()
        assertTrue(
            kotlin.math.abs(title.positionOnScreen.y - control.positionOnScreen.y) < title.size.height,
            "with room to spare the control stays on the title's row",
        )
    }

    private companion object {
        const val TITLE = "Neuer Ordner"
    }
}

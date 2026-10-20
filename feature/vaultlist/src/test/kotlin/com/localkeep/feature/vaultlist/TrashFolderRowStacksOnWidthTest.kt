package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A trashed folder's row stacks like its item sibling does, and says what it holds (S-W4-F2).
 *
 * Device pass 2026-10-19 (w4.md), 308dp at font scale 2.0: Restore / Delete sat beside a ~112dp name
 * column, so "W4empty" broke as "W4emp/ty" and the count line was clipped to "1 folder,". The item
 * row ([TrashItemRowBody]) already drops its buttons beneath the title by [nameYields]; the folder
 * row did not. [rowWidth] is what the row content is given at 308dp, as in
 * [TrashItemRowStacksOnWidthTest]. [GraphicsMode.Mode.NATIVE] is what lets text wrap; under legacy
 * graphics every assertion here would pass against the squeezed row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrashFolderRowStacksOnWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private val name = "W4empty"
    private val summary = "12 folders, 120 items"

    private fun show(rowWidth: Dp) {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.width(rowWidth)) {
                    TrashFolderRowBody(
                        name = name,
                        summary = summary,
                        restoreLabel = RESTORE,
                        onRestore = {},
                        deleteLabel = DELETE,
                        onDelete = {},
                        enabled = true,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun bounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun theNameIsNotCutInsideAWordAndTheActionsDropBeneathItAtTwoTimesText() {
        show(rowWidth = 236.dp)
        val layout = layoutOf(name)
        assertTrue(
            labelBreaksOnlyBetweenWords(layout, maxLines = Int.MAX_VALUE),
            "'$name' was cut inside a word across ${layout.lineCount} lines in a ${layout.size.width}px column",
        )
        val delete = compose.onNodeWithContentDescription(DELETE).fetchSemanticsNode().boundsInRoot
        assertTrue(delete.top >= bounds(summary).bottom - 1f, "the actions did not drop beneath the name and summary")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun theSummaryWrapsInsteadOfBeingClippedAtTwoTimesText() {
        show(rowWidth = 236.dp)
        val layout = layoutOf(summary)
        // Not hasVisualOverflow: its width half compares the laid-out size with the paragraph's
        // max width and reads true for a balanced paragraph narrower than its constraint.
        val last = layout.lineCount - 1
        assertFalse(layout.didOverflowHeight, "the summary '$summary' lost lines to a maxLines cap (${layout.lineCount} drawn)")
        assertEquals(summary.length, layout.getLineEnd(last, visibleEnd = false), "the summary '$summary' ends early")
        assertTrue((0..last).none { layout.isLineEllipsized(it) }, "the summary '$summary' is ellipsized")
        assertTrue(layout.lineCount > 1, "the test row is too roomy to prove the summary wraps")
    }

    /** Control: a roomy row at the default size keeps the buttons beside the name. */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h640dp-xhdpi", fontScale = 1.0f)
    fun controlARowWithRoomKeepsItsActionsBesideTheName() {
        show(rowWidth = 339.dp)
        val delete = compose.onNodeWithContentDescription(DELETE).fetchSemanticsNode().boundsInRoot
        val nameBounds = bounds(name)
        assertTrue(delete.left >= nameBounds.right - 1f, "the delete button is not beside the name")
        assertTrue(
            delete.top < nameBounds.bottom && delete.bottom > nameBounds.top,
            "the delete button is on another line than the name",
        )
    }

    @Test
    @Config(sdk = [34])
    fun anEmptyFolderNamesOnlyItself() {
        var shown = ""
        compose.setContent { LocalKeepTheme { shown = trashFolderSummary(folderCount = 1, itemCount = 0) } }
        compose.waitForIdle()
        assertEquals("1 folder", shown)
    }

    /** Control: a folder that holds items keeps both counts. */
    @Test
    @Config(sdk = [34])
    fun controlAFolderWithItemsKeepsBothCounts() {
        var shown = ""
        compose.setContent { LocalKeepTheme { shown = trashFolderSummary(folderCount = 1, itemCount = 1) } }
        compose.waitForIdle()
        assertEquals("1 folder, 1 item", shown)
    }

    private companion object {
        const val RESTORE = "Restore W4empty and everything in it"
        const val DELETE = "Delete W4empty and everything in it forever"
    }
}

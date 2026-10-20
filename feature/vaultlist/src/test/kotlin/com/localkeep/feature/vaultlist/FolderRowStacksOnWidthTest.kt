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
 * A folder row's name keeps room to be read in, so its actions yield instead (SCL-025).
 *
 * Device pass 2026-10-19: at 308dp the indent (14dp a level), the glyph and three 48dp buttons left
 * the name 63dp at depth 1 and 36-49dp at depths 2 and 3, so "archive" came out "archi/ve". The name
 * has no line cap, so nothing was lost, but a word cut in half is not a name. Rendered through
 * [FolderRowBody], which takes labels and callbacks rather than the ViewModel.
 *
 * [rowWidth] is what the row content is given at 308dp: the 250dp that put a depth-1 name at 63dp.
 * [GraphicsMode.Mode.NATIVE] is what lets the name wrap; under legacy graphics every assertion here
 * would pass against the squeezed row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderRowStacksOnWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private val name = "Level one archive aa"

    private fun show(rowWidth: Dp, depth: Int, justCreated: Boolean = false) {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.width(rowWidth)) {
                    FolderRowBody(
                        name = name,
                        itemCount = 3,
                        depth = depth,
                        justCreated = justCreated,
                        moveLabel = MOVE,
                        renameLabel = RENAME,
                        deleteLabel = DELETE,
                        onMove = {},
                        onRename = {},
                        onDelete = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun nameLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun nameBounds() = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun deleteBounds() = compose.onNodeWithContentDescription(DELETE).fetchSemanticsNode().boundsInRoot

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun theNameIsNotCutInsideAWordAtDepthOneAtTwoTimesText() {
        show(rowWidth = 250.dp, depth = 1)
        val layout = nameLayout()
        assertTrue(
            labelBreaksOnlyBetweenWords(layout, maxLines = Int.MAX_VALUE),
            "'$name' was cut inside a word across ${layout.lineCount} lines in a ${layout.size.width}px column",
        )
        assertTrue(deleteBounds().top >= nameBounds().bottom - 1f, "the actions did not drop beneath the name")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 1.0f)
    fun theNameIsNotCutInsideAWordAtDepthThreeAtTheDefaultSize() {
        show(rowWidth = 250.dp, depth = 3)
        val layout = nameLayout()
        assertTrue(
            labelBreaksOnlyBetweenWords(layout, maxLines = Int.MAX_VALUE),
            "'$name' was cut inside a word across ${layout.lineCount} lines in a ${layout.size.width}px column",
        )
    }

    /** Control: a top-level folder with room keeps the single row, the actions beside the name. */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h640dp-xhdpi", fontScale = 1.0f)
    fun controlARowWithRoomKeepsItsActionsBesideTheName() {
        show(rowWidth = 320.dp, depth = 0)
        val name = nameBounds()
        val delete = deleteBounds()
        assertTrue(delete.left >= name.right - 1f, "the delete button is not beside the name")
        assertTrue(
            delete.top < name.bottom && delete.bottom > name.top,
            "the delete button is on another line than the name",
        )
    }

    /** Control: the stacked row still has all three actions, none dropped to make room. */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun controlAllThreeActionsSurviveStacking() {
        show(rowWidth = 250.dp, depth = 1)
        listOf(MOVE, RENAME, DELETE).forEach { label ->
            val b = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertTrue(b.width > 0f && b.height > 0f, "$label has no size")
        }
        assertEquals(deleteBounds().top, compose.onNodeWithContentDescription(MOVE).fetchSemanticsNode().boundsInRoot.top, 0.5f)
        assertFalse(deleteBounds().left < 0f)
    }

    private companion object {
        const val MOVE = "Move Level one archive aa"
        const val RENAME = "Rename Level one archive aa"
        const val DELETE = "Delete Level one archive aa"
    }
}

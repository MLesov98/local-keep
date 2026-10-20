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
import com.localkeep.core.ui.theme.lkMonogram
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A trashed item's title keeps room to be read in, and its monogram keeps a margin (SCL-027).
 *
 * Device pass 2026-10-19, 308dp at font scale 2.0: the tile, two gaps and two 48dp buttons left the
 * title 72dp, so "TrashMe" broke as "Trash/Me"; and the monogram plate, which takes the glyph's own
 * size once the glyph outgrows it, had a dp or two to spare. Rendered through [TrashItemRowBody],
 * which takes labels and callbacks rather than the ViewModel. [rowWidth] is what the row content is
 * given at 308dp (268dp of screen less the group row's 32dp padding).
 *
 * [GraphicsMode.Mode.NATIVE] is what lets the title wrap; under legacy graphics every assertion here
 * would pass against the squeezed row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrashItemRowStacksOnWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private val title = "TrashMe"

    private fun show(rowWidth: Dp) {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.width(rowWidth)) {
                    TrashItemRowBody(
                        title = title,
                        subtitle = "someone@example.com",
                        folderTrail = null,
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

    private fun titleLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun bounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun theTitleIsNotCutInsideAWordAtTwoTimesText() {
        show(rowWidth = 236.dp)
        val layout = titleLayout()
        assertTrue(
            labelBreaksOnlyBetweenWords(layout, maxLines = Int.MAX_VALUE),
            "'$title' was cut inside a word across ${layout.lineCount} lines in a ${layout.size.width}px column",
        )
        val delete = compose.onNodeWithContentDescription(DELETE).fetchSemanticsNode().boundsInRoot
        assertTrue(delete.top >= bounds(title).bottom - 1f, "the actions did not drop beneath the title")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun theMonogramKeepsAMarginInsideItsPlateAtTwoTimesText() {
        show(rowWidth = 236.dp)
        val glyph = compose.onNodeWithText(lkMonogram(title), useUnmergedTree = true).fetchSemanticsNode()
        val plate = glyph.parent!!.boundsInRoot
        val d = compose.density.density
        val margin = (plate.width - glyph.boundsInRoot.width) / 2f / d
        assertTrue(margin >= 3f, "the monogram has ${margin}dp each side inside its plate: it touches the edge")
    }

    /** Control: a roomy row at the default size keeps the buttons beside the title. */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h640dp-xhdpi", fontScale = 1.0f)
    fun controlARowWithRoomKeepsItsActionsBesideTheTitle() {
        show(rowWidth = 339.dp)
        val delete = compose.onNodeWithContentDescription(DELETE).fetchSemanticsNode().boundsInRoot
        val titleBounds = bounds(title)
        assertTrue(delete.left >= titleBounds.right - 1f, "the delete button is not beside the title")
        assertTrue(
            delete.top < titleBounds.bottom && delete.bottom > titleBounds.top,
            "the delete button is on another line than the title",
        )
    }

    private companion object {
        const val RESTORE = "Restore TrashMe"
        const val DELETE = "Delete TrashMe forever"
    }
}

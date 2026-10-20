package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.Card
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
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
 * A stacked card row under the FAB keeps its whole name and its whole number (S-L3).
 *
 * Device pass 2026-10-19, 308dp at font 2.0, Cards filter: `Expired Visa` drew as `Expire / d Visa`
 * and its number as `•••• 1…`. A card has no copy pill, so once stacked the overflow plate is
 * bottom-anchored on the *details'* last line; when the FAB covers the row the plate dodges left,
 * onto that line, and the dodge's reserve narrowed the whole details column to keep them apart. At
 * this width the column was left about 85dp: the title broke inside a word and the number lost its
 * last four digits - the part the row exists to show.
 *
 * The reserve belongs to the trailing-cluster line, not to the text. A pill-less stacked row has no
 * such line until the plate needs one, so the dodge gives the plate its own line beneath the
 * details instead of taking width from them.
 *
 * [GraphicsMode.Mode.NATIVE] is what lets text wrap; under legacy graphics every assertion would
 * pass against the broken row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackedCardRowKeepsItsTextUnderTheFabTest {

    @get:Rule
    val compose = createComposeRule()

    private var fab by mutableStateOf<Rect?>(null)

    private val card = VaultItem(
        id = ID,
        title = TITLE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Card(holder = "A Holder", number = "4111111111111111"),
    )

    private fun show() {
        fab = null
        compose.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = card,
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                    avoidanceBounds = { fab },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun overflow() = compose.onNodeWithTag("vaultlist-row-$ID-overflow", useUnmergedTree = true)

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun boundsOf(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInWindow

    /** A resting FAB drawn over the overflow plate: 56dp wide, 16dp from the window's right edge. */
    private fun coverTheOverflow(windowDp: Float) {
        val b = overflow().fetchSemanticsNode().boundsInWindow
        val d = compose.density.density
        fab = Rect((windowDp - 72f) * d, b.top - 20f, windowDp * d, b.bottom + 20f)
        compose.waitForIdle()
        // The dodge latches once and the row re-measures; let it settle.
        repeat(5) { compose.waitForIdle() }
    }

    private fun assertWholeWords(layout: TextLayoutResult) {
        for (line in 1 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val text = layout.layoutInput.text.text
            assertTrue(
                text[start - 1].isWhitespace(),
                "\"$text\" breaks inside a word: line $line starts at '${text[start]}' " +
                    "(${layout.lineCount} lines)",
            )
        }
    }

    private fun assertIntact(window: Float) {
        show()
        coverTheOverflow(window)
        val title = layoutOf(compose.onNodeWithText(TITLE, useUnmergedTree = true))
        assertWholeWords(title)
        assertTrue((0 until title.lineCount).none { title.isLineEllipsized(it) }, "the title is ellipsized")
        val number = layoutOf(compose.onNodeWithText(NUMBER, useUnmergedTree = true))
        assertEquals(1, number.lineCount, "the number wrapped")
        assertFalse(
            number.isLineEllipsized(0),
            "the number is ellipsized (\"${number.layoutInput.text.text}\"): its last four digits are gone",
        )
        // And the plate is still clear of the text it was dodged away from.
        val plate = boundsOf(overflow())
        for (node in listOf(compose.onNodeWithText(NUMBER, useUnmergedTree = true))) {
            val t = boundsOf(node)
            val w = minOf(plate.right, t.right) - maxOf(plate.left, t.left)
            val h = minOf(plate.bottom, t.bottom) - maxOf(plate.top, t.top)
            assertTrue(w <= 0f || h <= 0f, "the plate $plate overlaps the number $t")
        }
    }

    /** The finding: the narrowest window at the largest text, FAB resting on the row. */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 2.0f)
    fun theTitleAndTheNumberSurviveTheFabAt308dpAtFontScaleTwo() = assertIntact(308f)

    /** The control: a roomy window at the default size, same FAB, same row - unchanged and intact. */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h900dp-xhdpi", fontScale = 1.0f)
    fun theRowIsIntactAt411dpAtTheDefaultSize() = assertIntact(411f)

    /** The control that the dodge is not simply off: nothing covering the row, nothing changes. */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 2.0f)
    fun theRowIsIntactWithNoFab() {
        show()
        val title = layoutOf(compose.onNodeWithText(TITLE, useUnmergedTree = true))
        assertWholeWords(title)
        assertFalse(layoutOf(compose.onNodeWithText(NUMBER, useUnmergedTree = true)).isLineEllipsized(0))
    }

    private companion object {
        val ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
        const val TITLE = "Expired Visa"
        const val NUMBER = "•••• 1111"
    }
}

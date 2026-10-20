package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The recovery phrase's "Words" label survives beside "Paste all" and "Reveal" (SCL-014).
 *
 * The header was a `Row` with the label in `weight(1f)`: the two pills took their width first and
 * the label was handed the remainder, so at the 308dp floor and font scale 2.0 it shrank to a lone
 * "…" with no letter surviving (scaling pass 2026-10-19). The header is a `FlowRow` now, which moves
 * the pill that no longer fits onto its own line instead.
 *
 * Asserted on what the reader sees, not on the layout primitive: the label is not truncated
 * (`hasVisualOverflow`), and no two of the three controls share pixels. [GraphicsMode.Mode.NATIVE]
 * is what makes the text measure at a real width; under legacy graphics the label cannot truncate and
 * the test would pass against the broken row.
 *
 * The geometry is the device's: 308dp wide inside the item flow's own [LkFrame.horizontal] margin.
 *
 * FAILS WITHOUT THE FIX: with the `Row`, [theLabelIsNotTruncatedAtTheFloorWidth] reports
 * `hasVisualOverflow` on the label — the "…".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhraseWordsHeaderWrapsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showHeader() {
        compose.setContent {
            LocalKeepTheme {
                PhraseWordsHeader(
                    reveal = false,
                    onPasteAll = {},
                    onToggleReveal = {},
                    modifier = Modifier.fillMaxWidth().padding(horizontal = LkFrame.horizontal),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun boundsOf(node: SemanticsNodeInteraction): Rect {
        val b = node.fetchSemanticsNode().boundsInRoot
        val d = compose.density.density
        return Rect(b.left / d, b.top / d, b.right / d, b.bottom / d)
    }

    private fun label() = compose.onNodeWithText("WORDS", useUnmergedTree = true)
    private fun paste() = compose.onNodeWithText("Paste all", useUnmergedTree = true)
    private fun reveal() = compose.onNodeWithText("Reveal", useUnmergedTree = true)

    private fun assertDisjoint(an: String, a: Rect, bn: String, b: Rect) {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        assertTrue(w <= 0f || h <= 0f, "$an $a and $bn $b share ${w}x${h}dp.")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 2.0f)
    fun theLabelIsNotTruncatedAtTheFloorWidth() {
        showHeader()
        val results = mutableListOf<TextLayoutResult>()
        label().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action?.invoke(results)
        val layout = results.single()
        assertTrue(
            !layout.hasVisualOverflow,
            "the Words label is cut to \"${layout.layoutInput.text.text.take(layout.getLineEnd(0, true))}\" " +
                "(width ${boundsOf(label()).width}dp): no letter of the field's name survives.",
        )
        assertDisjoint("the label", boundsOf(label()), "Paste all", boundsOf(paste()))
        assertDisjoint("the label", boundsOf(label()), "Reveal", boundsOf(reveal()))
        assertDisjoint("Paste all", boundsOf(paste()), "Reveal", boundsOf(reveal()))
        val window = 308f
        assertTrue(
            boundsOf(reveal()).right <= window && boundsOf(paste()).right <= window,
            "a pill runs past the window edge: ${boundsOf(paste())} ${boundsOf(reveal())}",
        )
    }

    /**
     * The control: at 411dp and the default size everything fits on one line, label at the start and
     * the pills at the end, so the change is inert where the row had room.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h640dp-xhdpi", fontScale = 1.0f)
    fun theHeaderIsOneLineWithThePillsAtTheEndAtTheDefaultSize() {
        showHeader()
        val label = boundsOf(label())
        val paste = boundsOf(paste())
        val reveal = boundsOf(reveal())
        // One line: all three share a vertical band.
        assertTrue(
            label.top < reveal.bottom && paste.top < reveal.bottom && abs(paste.top - reveal.top) <= 1f,
            "the header is no longer one line at 411dp / 1.0: $label $paste $reveal",
        )
        assertTrue(abs(label.left - LkFrame.horizontal.value) <= 1f, "the label left edge moved: $label")
        assertTrue(
            abs(reveal.right - (411f - LkFrame.horizontal.value)) <= 12f,
            "the pills are no longer pushed to the end of the line: $reveal",
        )
    }
}

package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A field's label and its readout never draw on top of one another.
 *
 * F-2 (device pass, 2026-09-17): at 360dp and font scale 2.0 the secure note's label and its
 * character counter were rendered as one ruined string — en `SECURE NOTEcharacters` with a stray
 * `0` wrapped above it, es `NOTA SEGURAcaracteres`, pt `NOTA SEGURA0 caractere`, fr
 * `NOTE SÉCURISÉEe` over `0 caractèr`
 * (screenshots at 360dp and the largest font scale, 2026-09-17).
 *
 * The mechanism was a plain `Row`: it measures its children in order against what is left, so the
 * label took the width it wanted and the counter was handed the remainder and wrapped *inside* it,
 * mid-word — then `Alignment.Bottom` put the caption's last line on the label's line. [FlowRow]
 * measures each child whole and moves the one that does not fit onto its own line, so the pair
 * stacks exactly when it stops fitting and never before.
 *
 * ### Bounds, not `assertIsDisplayed`
 *
 * Two overlapping nodes both report as displayed — that is the whole trap [LkDialogBoundsTest]
 * records. What the finding is about is where the two rectangles are relative to each other, so
 * that is what is asserted, and [GraphicsMode.Mode.NATIVE] is what makes the text inside them
 * measure at a real width rather than at Robolectric's legacy stub.
 *
 * The geometry is the device's: 360dp wide, inset by the item flow's own [LkFrame.horizontal]
 * margin. Both matter — the two strings fit side by side in a wider window, so a test written on a
 * flagship would pass on the broken code.
 *
 * FAILS WITHOUT THE FIX: with the `Row`, [theLabelAndItsReadoutStackWhenTheyStopFitting] measured
 * the counter's top *above* the label's bottom — the two occupying the same band of pixels, which
 * is what the screenshots show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkFieldLabelRowWrapsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showNoteField() {
        compose.setContent {
            LocalKeepTheme {
                LkFormField(
                    value = "",
                    onValueChange = {},
                    label = NOTE_LABEL,
                    placeholder = NOTE_PLACEHOLDER,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LkFrame.horizontal),
                    singleLine = false,
                    minHeight = 56.dp,
                    // The item editor's own counter, style included: an 11sp mono caption is what
                    // decides whether the pair fits, so a plain `Text` here would test a narrower
                    // string than the one that failed.
                    labelTrailing = {
                        Text(
                            COUNTER,
                            style = LkText.monoCaption,
                            modifier = Modifier.testTag(COUNTER_TAG),
                        )
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    /** Top edge, in dp, of the tagged node. */
    private fun topOf(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().positionOnScreen.y / compose.density.density

    /** Left edge, in dp, of the tagged node. */
    private fun leftOf(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().positionOnScreen.x / compose.density.density

    /** Bottom edge, in dp, of the tagged node. */
    private fun bottomOf(tag: String): Float {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return (node.positionOnScreen.y + node.size.height) / compose.density.density
    }

    /**
     * The label is found by its rendered text rather than a tag: [LkFormField] gives no handle on
     * it, and tagging it would mean changing the production signature to suit a test.
     */
    private fun labelNode() = compose.onNode(
        androidx.compose.ui.test.hasText(NOTE_LABEL.uppercase()),
        useUnmergedTree = true,
    ).fetchSemanticsNode()

    private fun labelBottomDp(): Float =
        (labelNode().positionOnScreen.y + labelNode().size.height) / compose.density.density

    private fun labelRightDp(): Float =
        (labelNode().positionOnScreen.x + labelNode().size.width) / compose.density.density

    @Test
    @Config(fontScale = 2.0f)
    fun theLabelAndItsReadoutStackWhenTheyStopFitting() {
        showNoteField()
        val labelBottom = labelBottomDp()
        val counterTop = topOf(COUNTER_TAG)
        assertTrue(
            counterTop >= labelBottom - TOLERANCE_DP,
            "at font scale 2.0 the character counter starts at ${counterTop}dp, above the " +
                "label's bottom edge at ${labelBottom}dp — the two are drawn in the same band of " +
                "pixels, which is what renders as \"SECURE NOTEcharacters\".",
        )
        // And it wrapped to the start of its own line rather than being squeezed at the right edge.
        assertTrue(
            leftOf(COUNTER_TAG) <= labelNode().positionOnScreen.x / compose.density.density +
                TOLERANCE_DP,
            "the counter stacked but stayed indented; a wrapped readout belongs under the label " +
                "it reads out, not under the middle of the row.",
        )
    }

    /**
     * The control, and the half the comps care about: at the default size the two still sit on one
     * row, opposite each other. Stays green with or without the change — a fix that stacked them
     * always would be a different regression, not this fix.
     */
    @Test
    fun theDefaultTextSizeKeepsThemOnOneRow() {
        showNoteField()
        val counterTop = topOf(COUNTER_TAG)
        val counterBottom = bottomOf(COUNTER_TAG)
        val labelBottom = labelBottomDp()
        assertTrue(
            counterTop < labelBottom && counterBottom > labelBottom - labelNode().size.height,
            "at font scale 1.0 the counter (${counterTop}..${counterBottom}dp) left the label's " +
                "row (ending ${labelBottom}dp) — the comp draws them on one line.",
        )
        assertTrue(
            leftOf(COUNTER_TAG) > labelRightDp(),
            "the counter overlaps the label horizontally at the default text size.",
        )
    }

    private companion object {
        const val NOTE_LABEL = "Secure note"
        const val NOTE_PLACEHOLDER = "Too sensitive for a plain notes app."

        /** `itemedit_note_character_count`, zero — the state the screenshots were taken in. */
        const val COUNTER = "0 characters"
        const val COUNTER_TAG = "note-counter"
        const val TOLERANCE_DP = 0.5f
    }
}

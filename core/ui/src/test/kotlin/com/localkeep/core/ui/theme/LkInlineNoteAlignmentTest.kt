package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The glyph in an [LkInlineNote] sits on the centre of the note's first line of text.
 *
 * Reported from a phone, 2026-10-09 ("the green tick is not aligned with *Metadata removed.*").
 * The row was top-aligned, with a 14dp glyph (1dp of top padding on a 15dp box, so its centre was
 * 8dp down) beside a 21sp line box (14sp x 1.5, centre 10.5dp down): about 2.5dp high at font scale
 * 1.0, and worse at larger scales because the line grows in sp while the glyph stays in dp. The same
 * component draws about twenty-five notes, so every one of them carried it.
 *
 * ### Bounds, not a screenshot
 *
 * What is wrong is where two rectangles sit relative to each other, so that is what is asserted —
 * the geometry [LkFieldLabelRowWrapsTest] records. [GraphicsMode.Mode.NATIVE] makes the text measure
 * at a real height rather than at Robolectric's legacy stub.
 *
 * The second case is the control: a note that wraps must keep its glyph on line *one*, not on the
 * middle of the block, which is what a naive `Alignment.CenterVertically` on the row would do and
 * would pass the first case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkInlineNoteAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    private fun centre(top: Float, bottom: Float) = (top + bottom) / 2

    @Test
    fun theGlyphIsCentredOnAOneLineNote() {
        compose.setContent {
            LocalKeepTheme {
                LkInlineNote(ONE_LINE, modifier = Modifier.padding(horizontal = LkFrame.horizontal))
            }
        }
        val glyph = compose.onNodeWithTag(GLYPH_TAG).getBoundsInRoot()
        val text = compose.onNodeWithText(ONE_LINE).getBoundsInRoot()
        assertEquals(
            centre(text.top.value, text.bottom.value),
            centre(glyph.top.value, glyph.bottom.value),
            0.5f,
            "glyph $glyph against text $text",
        )
    }

    @Test
    fun aWrappedNoteKeepsTheGlyphOnItsFirstLine() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = LkFrame.horizontal)) { LkInlineNote(WRAPPING) }
            }
        }
        val glyph = compose.onNodeWithTag(GLYPH_TAG).getBoundsInRoot()
        val text = compose.onNodeWithText(WRAPPING).getBoundsInRoot()
        assertTrue(text.bottom.value - text.top.value > 40f, "the note must really wrap; text was $text")
        // The first line's box is one body line tall, starting at the text's top.
        val firstLineCentre = text.top.value + LkText.body.lineHeight.value / 2
        assertEquals(firstLineCentre, centre(glyph.top.value, glyph.bottom.value), 0.5f, "glyph $glyph against text $text")
    }

    /**
     * The same at the scaling pass's floor (SCL-028): 308dp wide at font scale 2.0, where the note
     * wraps over several lines and the glyph must still be on the first. The finding was filed
     * against the item editor's own note caption, which is a different component; this pins that
     * [LkInlineNote] is not the one that drifts.
     */
    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun aWrappedNoteKeepsTheGlyphOnItsFirstLineAtTheScalingFloor() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = LkFrame.horizontal)) { LkInlineNote(WRAPPING) }
            }
        }
        val glyph = compose.onNodeWithTag(GLYPH_TAG).getBoundsInRoot()
        val text = compose.onNodeWithText(WRAPPING).getBoundsInRoot()
        assertTrue(text.bottom.value - text.top.value > 80f, "the note must really wrap; text was $text")
        // The line box is the body line height at this density: font scaling is not linear for large
        // sizes, so it is converted rather than multiplied.
        val firstLine = with(compose.density) { LkText.body.lineHeight.toDp() }
        val firstLineCentre = text.top.value + firstLine.value / 2
        assertEquals(firstLineCentre, centre(glyph.top.value, glyph.bottom.value), 1f, "glyph $glyph against text $text")
    }

    private companion object {
        const val GLYPH_TAG = "inline-note-icon"
        const val ONE_LINE = "Metadata removed."
        const val WRAPPING =
            "This photo carries a location and a camera model, and none of it has been removed yet, " +
                "so keep your own copy."
    }
}

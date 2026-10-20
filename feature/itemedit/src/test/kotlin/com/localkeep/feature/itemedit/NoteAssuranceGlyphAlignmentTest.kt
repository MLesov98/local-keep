package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SCL-028: the glyph beside the note editor's searchability caption sits on the first line of the
 * caption, not hung from the top of the row.
 *
 * The row was top-aligned with a 16dp glyph padded 1dp from the top, so its centre was 9dp down while
 * the first caption line's centre moved with the font: about 8dp too high at 308dp / 2.0 text. The
 * same fix [com.localkeep.core.ui.theme.LkInlineNote] already carries (a one-line box, glyph centred
 * in it) applies; the glyph is tagged because the slot is one line tall whatever is inside it.
 *
 * Bounds, not a screenshot; NATIVE graphics so the text measures at a real height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteAssuranceGlyphAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = LkFrame.horizontal)) { NoteAssurance(written = true) }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun theGlyphIsCentredOnTheFirstLineOfAWrappedCaption() {
        show()
        val caption = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.itemedit_note_assurance)
        val glyph = compose.onNodeWithTag("note-assurance-icon").getBoundsInRoot()
        val text = compose.onNodeWithText(caption).getBoundsInRoot()
        assertTrue(text.bottom.value - text.top.value > 40f, "the caption must really wrap; text was $text")
        val firstLine = with(compose.density) { LkText.caption.lineHeight.toDp() }
        assertEquals(
            text.top.value + firstLine.value / 2,
            (glyph.top.value + glyph.bottom.value) / 2,
            1f,
            "glyph $glyph against text $text",
        )
    }
}

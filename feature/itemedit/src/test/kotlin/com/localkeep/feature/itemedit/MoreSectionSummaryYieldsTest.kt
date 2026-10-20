package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkSpace
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
 * A long summary on the "Folder, tags and more" row shortens itself; it never takes the row's name
 * or its chevron.
 *
 * O1-05, 2026-10-02: the summary was an unweighted `Text` with no line limit between a weighted
 * label and the chevron. Compose measures unweighted children first, so a long folder name — the
 * kind KeePass and Bitwarden imports bring — wrapped across the whole row and left the label and
 * the 18dp chevron zero wide. The only way into folder, tags, custom fields and reprompt read as a
 * grey block.
 *
 * Measured, not read: [MoreSectionHeadline] is the row's content as `MoreSection` composes it,
 * inside a row with the same arrangement. [GraphicsMode.Mode.NATIVE] for the reason
 * `CardNumberWellFitsAtFontScaleTwoTest` gives — without real text layout every overflow assertion
 * passes against broken code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoreSectionSummaryYieldsTest {

    @get:Rule
    val compose = createComposeRule()

    /** Both rows in one composition, identical but for the summary. */
    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                Column(Modifier.fillMaxWidth()) {
                    listOf(SHORT_ROW to SHORT, LONG_ROW to LONG).forEach { (tag, summary) ->
                        Row(
                            Modifier.width(ROW_WIDTH).testTag(tag),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
                        ) {
                            MoreSectionHeadline(LABEL, summary, chevronDegrees = 0f)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun bounds(text: String, row: String): Rect =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

    private fun rowBounds(row: String): Rect = compose.onNode(hasTestTag(row)).fetchSemanticsNode().boundsInRoot

    @Test
    fun `a long summary leaves the label its full width`() {
        show()
        val withShort = bounds(LABEL, SHORT_ROW).width
        val withLong = bounds(LABEL, LONG_ROW).width
        assertTrue(withShort > 0f, "control: the label has width beside a short summary")
        assertTrue(
            abs(withLong - withShort) < 1f,
            "beside a long summary the label is $withLong px wide, against $withShort px beside a " +
                "short one — the summary is taking the row's name away",
        )
    }

    @Test
    fun `a long summary leaves room for the chevron`() {
        show()
        val summary = bounds(LONG, LONG_ROW)
        val row = rowBounds(LONG_ROW)
        val chevron = 18f * compose.density.density
        assertTrue(
            summary.right <= row.right - chevron,
            "the summary ends at ${summary.right} px in a row ending at ${row.right} px, leaving " +
                "less than the chevron's ${chevron} px",
        )
    }

    private companion object {
        const val SHORT_ROW = "more-row-short-summary"
        const val LONG_ROW = "more-row-long-summary"
        const val LABEL = "Folder, tags and more"
        const val SHORT = "none set"
        const val LONG = "Imported from KeePass / Personal / Banking and finance · 3 tags · 2 fields · " +
            "favourite · reprompt"
        val ROW_WIDTH = 320.dp
    }
}

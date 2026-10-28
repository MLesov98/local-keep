package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkSpace
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
 * The "Folder, tags and more" row's value reads whole at every window and font scale the editor
 * supports, not as "no…" (device font-scale sweep, 2026-10-28).
 *
 * The summary used to sit beside the label in a weighted, one-line, ellipsising slot. At a 360dp
 * window and font scale 1.5 the label took most of the row and the value was handed what was left —
 * two letters and an ellipsis — so the only statement a closed section makes ("is anything in
 * here?") was unreadable on the screen that most phones are. [MoreSectionSummaryYieldsTest] pins the
 * other half: a *long* summary still yields to the label and the chevron.
 *
 * The row is [MoreSectionHeadline] in a row [ROW_CHROME] narrower than the window: the editor's
 * form gutters (2 x lg), the row's own padding (2 x md), the 18dp chevron and the gap before it.
 * [GraphicsMode.Mode.NATIVE] for the reason `CardNumberWellFitsAtFontScaleTwoTest` gives.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoreSectionSummaryReadsWholeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(windowDp: Int) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                Row(
                    Modifier.width((windowDp - ROW_CHROME).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
                ) {
                    MoreSectionHeadline("Folder, tags and more", "none set", chevronDegrees = 0f)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun assertSummaryReadsWhole() {
        val layout = mutableListOf<TextLayoutResult>()
        val node = compose.onNode(hasText("none set"), useUnmergedTree = true).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        val result = layout.single()
        assertFalse(result.hasVisualOverflow, "the value is ellipsised: ${result.layoutInput.text}")
        assertEquals(1, result.lineCount, "the value broke across lines mid-phrase")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.5f)
    fun `at 360dp and font 1_5 none set is not cut to two letters`() {
        show(360)
        assertSummaryReadsWhole()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h800dp-xxhdpi", fontScale = 1.5f)
    fun `at 411dp and font 1_5 none set reads whole`() {
        show(411)
        assertSummaryReadsWhole()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xxhdpi", fontScale = 2.0f)
    fun `at the 308dp floor and font 2 none set reads whole`() {
        show(308)
        assertSummaryReadsWhole()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h800dp-xxhdpi")
    fun `at font 1 the value still sits beside the label on one line`() {
        show(411)
        val label = compose.onNode(hasText("Folder, tags and more"), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val value = compose.onNode(hasText("none set"), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            value.top < label.bottom,
            "control: where it already fitted the value stays beside the label, not under it",
        )
    }

    private companion object {
        /** Gutters, row padding, chevron and the gap before it, in dp. */
        const val ROW_CHROME = 74
    }
}

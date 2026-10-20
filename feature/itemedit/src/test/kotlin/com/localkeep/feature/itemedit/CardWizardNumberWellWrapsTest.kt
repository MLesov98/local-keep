package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkCardDigitGroups
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.io.File
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
 * SCL-005 (2026-10-19): the card wizard's number well must show every digit at 308dp / font 2.0.
 *
 * The wizard's well kept `LkTextField`'s `singleLine = true` default after the editor's got
 * `singleLine = false` (2B-3), so at 411dp / 2.0 two of fifteen digits scrolled out of sight and at
 * 308dp six did. This composes the real [CardNumberTextField] -- the well [CardWizard] draws -- in a
 * 308dp column (the window less 16dp gutters, as the wizard lays it out) and reads the text layout:
 * nothing may overflow the width, and every line must break at a group gap, so the wave 3 regrouping
 * of 17-19 digit numbers survives wrapping.
 *
 * [GraphicsMode.Mode.NATIVE] is load-bearing: under legacy graphics there is no real text layout and
 * overflow assertions pass against broken code (see `CardNumberWellFitsAtFontScaleTwoTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardWizardNumberWellWrapsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun layoutOf(digits: String, windowDp: Int, groups: LkCardDigitGroups = LkCardDigitGroups.Default): TextLayoutResult {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.width(windowDp.dp).padding(horizontal = 16.dp)) {
                    CardNumberTextField(
                        value = digits,
                        onValueChange = {},
                        label = "Card number",
                        groups = groups,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action?.invoke(results)
        return results.single()
    }

    /** Every digit is drawn: no overflow, and each line break lands on a group gap. */
    private fun assertWholeNumberVisible(digits: String, layout: TextLayoutResult) {
        // The well is as wide as the layout's minimum width (fillMaxWidth); a single-line layout is
        // measured with unbounded width, so its own size says nothing -- the right edge of its
        // widest line against the well does.
        val well = layout.layoutInput.constraints.minWidth
        val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
        assertTrue(
            widest <= well + 0.5f,
            "a ${digits.length}-digit number runs to ${widest}px in a ${well}px well " +
                "(${layout.lineCount} line(s)); digits scroll out of sight",
        )
        val shown = layout.layoutInput.text.text
        assertEquals(digits, shown.filter(Char::isDigit), "the transformation changed the digits")
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            assertTrue(
                end == shown.length || shown[end - 1] == ' ',
                "line ${line + 1} of '$shown' breaks inside a digit group",
            )
        }
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xxhdpi", fontScale = 2.0f)
    fun `at 308dp and font 2 a 15 digit number shows every digit`() {
        val d = "378282246310005"
        assertWholeNumberVisible(d, layoutOf(d, 308, LkCardDigitGroups.Amex))
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xxhdpi", fontScale = 2.0f)
    fun `at 308dp and font 2 a 16 digit number shows every digit`() {
        val d = "4111111111111111"
        assertWholeNumberVisible(d, layoutOf(d, 308))
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xxhdpi", fontScale = 2.0f)
    fun `at 308dp and font 2 a 19 digit number keeps its regrouping and shows every digit`() {
        val d = "6212345678901234567"
        val layout = layoutOf(d, 308)
        assertWholeNumberVisible(d, layout)
        // Wave 3's regrouping: a 19-digit number is 3 then fours, so the last four stay together.
        assertEquals("621 2345 6789 0123 4567", layout.layoutInput.text.text)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `at 411dp and font 2 a 15 digit number shows every digit`() {
        val d = "378282246310005"
        assertWholeNumberVisible(d, layoutOf(d, 411, LkCardDigitGroups.Amex))
    }

    /** Control: where the number already fitted on one line, allowing the wrap changes nothing. */
    @Test
    @Config(qualifiers = "w411dp-h800dp-xxhdpi")
    fun `at 411dp and font 1 a 16 digit number is still one line`() {
        val d = "4111111111111111"
        val layout = layoutOf(d, 411)
        assertWholeNumberVisible(d, layout)
        assertEquals(1, layout.lineCount)
    }

    @Test
    fun `the editor and the wizard read the same single line decision`() {
        val dir = "src/main/kotlin/com/localkeep/feature/itemedit/"
        val screen = File(dir + "ItemEditScreen.kt").readText()
        val well = screen.substringAfter("viewModel::onCardNumberChanged,", "").substringBefore(")\n")
        assertTrue(well.isNotEmpty(), "the editor's card number well moved; this test no longer reads it")
        assertTrue("singleLine = CARD_NUMBER_WELL_SINGLE_LINE" in well, "the editor's well no longer reads the shared constant")
        assertTrue("singleLine = CARD_NUMBER_WELL_SINGLE_LINE" in File(dir + "CardNumberWell.kt").readText())
        assertFalse(CARD_NUMBER_WELL_SINGLE_LINE, "the card number well is single-line again")
        assertTrue("CardNumberTextField(" in File(dir + "CardWizard.kt").readText(), "the wizard no longer uses the shared well")
    }
}

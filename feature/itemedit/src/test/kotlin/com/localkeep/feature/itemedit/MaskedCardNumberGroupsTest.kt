package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkCardDigitGroups
import com.localkeep.core.ui.theme.LkCardNumberTransformation
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkPasswordField
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
 * R-L-04: a masked 19-digit card number wraps at the group gaps, not inside a run of bullets.
 *
 * The editor's well masked with a plain `•` per digit, so 19 contiguous bullets broke wherever the
 * line ran out and left the 19th alone on a second line, while the revealed number of the same
 * width broke between groups. The mask now carries the same gaps as the digits it hides
 * ([cardNumberMaskFor]), so the two wrap alike and the bullet count is still the digit count.
 *
 * The well is the real [LkPasswordField] the editor's SecretField draws, at 308dp / 2.0 text with
 * the two trailing glyph slots, and the number is the wizard's regrouped 19-digit shape.
 * [GraphicsMode.Mode.NATIVE] is load-bearing: legacy graphics has no real text layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskedCardNumberGroupsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun layoutOf(digits: String, revealed: Boolean = false): TextLayoutResult {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = LkFrame.horizontal)) {
                    LkPasswordField(
                        value = digits,
                        onValueChange = {},
                        label = "Card number",
                        singleLine = false,
                        leadingIcon = null,
                        revealed = revealed,
                        maskTransformation = cardNumberMaskFor(LkCardDigitGroups.Default),
                        revealedTransformation = LkCardNumberTransformation(LkCardDigitGroups.Default),
                        actions = {
                            Box(Modifier.size(48.dp))
                            Box(Modifier.size(48.dp))
                        },
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

    @Test
    fun `a masked 19 digit number breaks only between groups`() {
        val d = "6212345678901234567"
        val layout = layoutOf(d)
        val shown = layout.layoutInput.text.text
        assertTrue(layout.lineCount > 1, "the number must really wrap here; it is ${layout.lineCount} line(s): '$shown'")
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            assertTrue(end == shown.length || shown[end - 1] == ' ', "line ${line + 1} of '$shown' breaks inside a group")
        }
    }

    @Test
    fun `the mask hides every digit and keeps their count and gaps`() {
        val d = "6212345678901234567"
        val shown = layoutOf(d).layoutInput.text.text
        assertEquals(19, shown.count { it == '•' }, "'$shown'")
        assertTrue(shown.none { it.isDigit() }, "a digit leaked into the mask: '$shown'")
        assertEquals("••• •••• •••• •••• ••••", shown)
    }

    /** The wiring: the editor's card number well hands the grouped mask to the field, not the default bullets. */
    @Test
    fun `the editor's card number well passes the grouped mask`() {
        val src = java.io.File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
        val well = src.substringAfter("viewModel::onCardNumberChanged,", "").substringBefore("// Announced as well as drawn")
        assertTrue(well.isNotEmpty(), "the editor's card number well moved; this test no longer reads it")
        assertTrue("maskTransformation" in well && "cardNumberMaskFor" in well, "the card number well no longer passes the grouped mask")
    }

    /** R-L-06: revealed, the number shows the same 3-4-4-4-4 groups the mask does, and breaks only between them. */
    @Test
    fun `a revealed 19 digit number is grouped like the mask and breaks only between groups`() {
        val d = "6011000000000004123"
        val layout = layoutOf(d, revealed = true)
        val shown = layout.layoutInput.text.text
        assertEquals("601 1000 0000 0000 4123", shown)
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            assertTrue(end == shown.length || shown[end - 1] == ' ', "line ${line + 1} of '$shown' breaks inside a group")
        }
    }

    @Test
    fun `the editor's card number well passes the grouped revealed transformation`() {
        val src = java.io.File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
        val well = src.substringAfter("viewModel::onCardNumberChanged,", "").substringBefore("// Announced as well as drawn")
        assertTrue("revealedTransformation" in well && "LkCardNumberTransformation" in well, "the revealed card number is not grouped")
    }
}

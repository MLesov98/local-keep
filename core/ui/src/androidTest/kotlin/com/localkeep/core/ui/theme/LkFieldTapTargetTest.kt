package com.localkeep.core.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the whole of an [LkTextField] as its tap target, and the top of a multiline one as where
 * writing starts.
 *
 * The bug both halves come from: `BasicTextField` is only as tall as the text in it, and the well
 * around it has a *minimum* height much larger than that. Centring the one inside the other left a
 * band of upholstery at the top and bottom of every field in the app that looked like the field,
 * was drawn as the field, and ignored taps. Measured on a Pixel 8 emulator before the fix: a
 * password well drew 160px tall and only its middle 61px opened the keyboard. On the 200dp Note
 * field roughly seven eighths of the visible box was dead, which is why a note appeared to begin
 * halfway down the screen.
 *
 * Tested by tapping rather than by reading modifiers back, because the defect was invisible in the
 * hierarchy — the field was present, enabled and correctly labelled at every point tested here. A
 * near miss on a text field is also indistinguishable from an unresponsive app, which is what makes
 * this worth a regression test rather than a glance.
 */
@RunWith(AndroidJUnit4::class)
class LkFieldTapTargetTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val WELL = "well"
        const val LABEL = "Note"

        /** Comfortably outside the one line of text, in the band that used to be dead. */
        const val EDGE_INSET_PX = 6f
    }

    @Test
    fun tappingTheBottomOfATallFieldStartsEditing() {
        rule.setContent {
            LocalKeepTheme {
                LkTextField(
                    value = "",
                    onValueChange = {},
                    label = LABEL,
                    singleLine = false,
                    minHeight = 200.dp,
                    modifier = Modifier.testTag(WELL),
                )
            }
        }

        rule.onNodeWithContentDescription(LABEL).assertIsNotFocused()
        rule.onNodeWithTag(WELL).performTouchInput {
            click(Offset(centerX, height - EDGE_INSET_PX))
        }
        rule.onNodeWithContentDescription(LABEL).assertIsFocused()
    }

    @Test
    fun tappingTheTopEdgeOfAnOrdinaryFieldStartsEditing() {
        // The single-line case is the one that bit hardest, because it is every other field on
        // every other screen: unlock, search, title, username, the master password.
        rule.setContent {
            LocalKeepTheme {
                LkTextField(
                    value = "",
                    onValueChange = {},
                    label = LABEL,
                    modifier = Modifier.testTag(WELL),
                )
            }
        }

        rule.onNodeWithContentDescription(LABEL).assertIsNotFocused()
        rule.onNodeWithTag(WELL).performTouchInput {
            click(Offset(centerX, EDGE_INSET_PX))
        }
        rule.onNodeWithContentDescription(LABEL).assertIsFocused()
    }

    @Test
    fun aMultilineFieldPutsItsFirstLineAtTheTop() {
        rule.setContent {
            LocalKeepTheme {
                LkTextField(
                    value = "first line",
                    onValueChange = {},
                    label = LABEL,
                    singleLine = false,
                    minHeight = 200.dp,
                    modifier = Modifier.testTag(WELL),
                )
            }
        }

        val well = rule.onNodeWithTag(WELL).fetchSemanticsNode().boundsInRoot
        val text = rule.onNodeWithContentDescription(LABEL).fetchSemanticsNode().boundsInRoot
        val offsetIntoWell = text.top - well.top
        assertTrue(
            offsetIntoWell < well.height / 4f,
            "the first line starts ${offsetIntoWell}px into a ${well.height}px note field — it " +
                "should sit at the top, not be centred in the empty page below it",
        )
    }
}

package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.test.device.DeviceReadyRule
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the fix for a masked password field spelling its placeholder out letter by letter.
 *
 * The bug, as a user saw it: an empty master-password field with the eye toggle off read
 * `C h o o s e   s o m e t h i n g`, and became normal the moment anything was typed. The cause was
 * one line — [LkTextField] drew the placeholder in the field's own `textStyle`, and a masked
 * [LkPasswordField] sets that to `LkText.secretInput`, whose 0.16em tracking exists so a row of
 * bullets does not clump into a solid grey bar. Correct for bullets, nonsense for prose.
 *
 * Measured rather than asserted on the style, because the style is the thing that was wrong: a test
 * reading `placeholderStyle` would have passed against the broken version too, which had no such
 * parameter and simply used the one it was given. Width is what the user actually saw. It is also
 * close to the only observable — a placeholder is a sibling `Text` that never appears in a
 * `uiautomator` dump, and every window in this app carries `FLAG_SECURE`, so a device screenshot is
 * black.
 *
 * Both fields are composed together and compared to each other rather than to a fixed number. A
 * hardcoded width would be a different assertion on every screen density and font scale, and would
 * need rewriting the first time the design changed a font.
 */
@RunWith(AndroidJUnit4::class)
class LkPasswordPlaceholderTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        /** Short enough to fit on one line in every field here, so no width is clamped. */
        const val PROSE = "Type it once more"

        /** Antialiasing and rounding move this by a pixel or so; the bug moved it by dozens. */
        const val TOLERANCE_PX = 4f
    }

    /** Widths of every rendered copy of [PROSE], in pixels. */
    private fun placeholderWidths(): List<Float> =
        rule.onAllNodesWithText(PROSE)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot.width }

    @Test
    fun aMaskedFieldsPlaceholderIsTheSameWidthAsAnOrdinaryFieldsPlaceholder() {
        rule.setContent {
            LocalKeepTheme {
                Column {
                    LkTextField(value = "", onValueChange = {}, placeholder = PROSE, label = "plain")
                    LkPasswordField(
                        value = "",
                        onValueChange = {},
                        placeholder = PROSE,
                        label = "masked",
                        revealed = false,
                    )
                }
            }
        }

        val widths = placeholderWidths()
        assertEquals(2, widths.size, "expected both placeholders to render, got $widths")
        assertTrue(
            abs(widths[0] - widths[1]) <= TOLERANCE_PX,
            "the same text renders ${widths[0]}px wide in an ordinary field and ${widths[1]}px in " +
                "a masked one — the masked field is drawing prose in the secret style, which " +
                "spaces it out into individual letters",
        )
    }

    @Test
    fun theRevealToggleDoesNotChangeThePlaceholderEither() {
        // The reported symptom appeared on toggling the eye, so both sides of it are pinned. A
        // revealed field uses a monospace style for its value; the placeholder must not follow that
        // either, or the same text would visibly jump width as the toggle is tapped on an empty
        // field.
        rule.setContent {
            LocalKeepTheme {
                Column {
                    LkPasswordField(
                        value = "",
                        onValueChange = {},
                        placeholder = PROSE,
                        label = "revealed",
                        revealed = true,
                    )
                    LkPasswordField(
                        value = "",
                        onValueChange = {},
                        placeholder = PROSE,
                        label = "hidden",
                        revealed = false,
                    )
                }
            }
        }

        val widths = placeholderWidths()
        assertEquals(2, widths.size, "expected both placeholders to render, got $widths")
        assertTrue(
            abs(widths[0] - widths[1]) <= TOLERANCE_PX,
            "the placeholder changes width with the reveal toggle: ${widths[0]}px against ${widths[1]}px",
        )
    }

    @Test
    fun aPlaceholderIsNotDrawnOnceTheFieldHasAValue() {
        rule.setContent {
            LocalKeepTheme {
                LkPasswordField(
                    value = "hunter2",
                    onValueChange = {},
                    placeholder = PROSE,
                    label = "filled",
                    revealed = false,
                )
            }
        }

        // Not cosmetic: a placeholder still painted under a value would be a legible string sitting
        // behind the bullets of a real password.
        rule.onNodeWithText(PROSE).assertDoesNotExist()
    }
}

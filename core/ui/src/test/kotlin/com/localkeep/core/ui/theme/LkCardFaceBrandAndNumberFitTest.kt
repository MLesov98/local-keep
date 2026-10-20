package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The card face's brand label never sits on the chip, and its number row never loses its tail
 * (S-W4-F1, S-W4-L2).
 *
 * At 308dp and font scale 2.0 the two-line "AMERICAN / EXPRESS" was laid out across the whole face
 * width, so its first line started at the chip's x and the chip covered "AM". The same row of
 * per-glyph texts summed wider than the one-string measure the shrink was computed from (each glyph
 * rounds up to a whole pixel), so "•••• •••• •••• 1111" lost its last digits at the right edge.
 *
 * FAILS WITHOUT THE FIX: the brand's left edge is left of the chip's right edge; the last glyph's
 * right edge is past the number row's.
 * CONTROLS: at 411dp and font scale 1.0 the label is clear of the chip and the row ends inside.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkCardFaceBrandAndNumberFitTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(brand: String, digits: String, groups: LkCardDigitGroups, masked: Int) {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = LkFrame.horizontal).testTag("card")) {
                    LkCardPreview(
                        numberSlots = lkCardNumberSlots(digits, groups, maskedBefore = masked),
                        expiryDisplay = "11 / 28",
                        holder = "ALEX HOLDER",
                        brandLabel = brand,
                        expiryCaption = "Expires",
                        holderPlaceholder = "Cardholder",
                        contentDescription = "card",
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertBrandClearOfChip() {
        val chip = compose.onNodeWithTag("card-chip", useUnmergedTree = true).getBoundsInRoot()
        val brand = compose.onNodeWithTag("card-brand", useUnmergedTree = true).getBoundsInRoot()
        val number = compose.onNodeWithTag("card-number", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(
            brand.left.value >= chip.right.value - 0.5f,
            "the brand starts at ${brand.left} but the chip ends at ${chip.right}",
        )
        assertTrue(
            brand.bottom.value <= number.top.value + 0.5f,
            "the brand ends at ${brand.bottom} but the number row starts at ${number.top}",
        )
    }

    private fun assertTailInside() {
        val number = compose.onNodeWithTag("card-number", useUnmergedTree = true).getBoundsInRoot()
        val last = compose.onNodeWithTag("card-number-last", useUnmergedTree = true).getBoundsInRoot()
        // A glyph the row had no width left for is laid out at zero size, not hung over the edge, so
        // "ends inside" alone passes for a digit that is simply gone.
        assertTrue(last.right.value - last.left.value > 1f, "the last glyph is ${last.right.value - last.left.value}dp wide")
        assertTrue(
            last.right.value <= number.right.value + 0.5f,
            "the last glyph ends at ${last.right} but the number row ends at ${number.right}",
        )
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-374dpi", fontScale = 2.0f)
    fun `at the 308dp floor and large type the Amex label stays off the chip`() {
        show("American Express", "378282246310005", LkCardDigitGroups.Amex, masked = 11)
        assertBrandClearOfChip()
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-374dpi", fontScale = 2.0f)
    fun `at the 308dp floor and large type a Visa shows its last four`() {
        show("Visa", "4111111111111111", LkCardDigitGroups.Default, masked = 12)
        assertTailInside()
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-374dpi", fontScale = 2.0f)
    fun `at the 308dp floor and large type an Amex shows its last five`() {
        show("American Express", "378282246310005", LkCardDigitGroups.Amex, masked = 10)
        assertTailInside()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun `a roomy window keeps the label clear of the chip and the tail inside`() {
        show("American Express", "378282246310005", LkCardDigitGroups.Amex, masked = 10)
        assertBrandClearOfChip()
        assertTailInside()
    }
}

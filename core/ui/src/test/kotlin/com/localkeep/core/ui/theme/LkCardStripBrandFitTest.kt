package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
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
 * The wizard's card strip shows the network's name whenever the row has room for it (S5-F-08).
 *
 * A 15-digit Amex on a 411dp phone leaves about 54dp empty beside the chip and the number, but
 * "AMERICAN EXPRESS" needs 129 on one line, so the whole name was dropped and the strip ended in
 * empty space. A multi-word name may now take two lines when that is what fits. Where the digits are
 * at their floor and nothing fits (a 19-digit number) the name is still dropped first - that half is
 * by design.
 *
 * FAILS WITHOUT THE FIX: no "AMERICAN EXPRESS" node on the strip for the 15-digit Amex.
 * CONTROLS: a 16-digit Visa shows "VISA"; a 19-digit Visa shows no name; and the fit rule gives a
 * one-line name its line before it considers two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkCardStripBrandFitTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(digits: String, groups: LkCardDigitGroups, brand: String) {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = LkFrame.horizontal)) {
                    LkCardStrip(
                        numberSlots = lkCardNumberSlots(digits, groups),
                        contentDescription = "card",
                        brandLabel = brand,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun brandShown(text: String) =
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a fifteen digit Amex shows its name on two lines`() {
        show("378282246310005", LkCardDigitGroups.Amex, "American Express")
        assertTrue(brandShown("AMERICAN EXPRESS"), "the strip has room for the name and dropped it")
    }

    @Test
    fun `a sixteen digit Visa shows its name`() {
        show("4111111111111111", LkCardDigitGroups.Default, "Visa")
        assertTrue(brandShown("VISA"))
    }

    @Test
    fun `a nineteen digit number leaves no room and still drops the name`() {
        show("4111111111111111234", LkCardDigitGroups.Default, "Visa")
        assertFalse(brandShown("VISA"), "there is no room at 19 digits; the digits come first")
    }

    @Test
    fun `the fit rule gives one line before two and neither when nothing fits`() {
        val chip = 38.dp
        val gap = 16.dp
        // Room for the whole name beside a number at its floor.
        assertEquals(StripBrandFit.OneLine, lkStripBrandFit(100.dp, 327.dp, chip, gap, 64.dp, null))
        // Not for the whole name, but for its widest word.
        assertEquals(StripBrandFit.TwoLines, lkStripBrandFit(175.dp, 327.dp, chip, gap, 129.dp, 68.dp))
        // A single word that does not fit has no second line to take.
        assertEquals(StripBrandFit.None, lkStripBrandFit(237.dp, 327.dp, chip, gap, 64.dp, null))
        // Nor does a multi-word name when even its widest word does not fit.
        assertEquals(StripBrandFit.None, lkStripBrandFit(237.dp, 327.dp, chip, gap, 129.dp, 68.dp))
    }
}

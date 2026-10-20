package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The strength meter stacks when the bar would be crushed, at any font scale (SCL-012).
 *
 * The meter used to stack on font scale alone ([LkText.stackAboveFontScale]). Below that, the bar was
 * the only child with a weight and so the only child that absorbed a shortfall: on the Generator at
 * 308dp and font scale 1.0 the column beside the dice and copy buttons is about 150dp, the word and
 * "133 bits" take their intrinsic width first, and the four-band bar came out at nothing.
 *
 * The bar is `decorative()` and has no semantics node, so the observable is the one
 * [com.localkeep.core.ui.FontScaleTest] uses: if the bar has its own row the component is taller than
 * its text; if it is crushed beside the text, the two heights are equal.
 *
 * FAILS WITHOUT THE FIX: at 150dp and 1.0 the component was exactly as tall as its text.
 * CONTROL: at 360dp and 1.0 the bar still shares the line, which is the layout the comps specify.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkStrengthStacksOnWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private fun heights(width: Dp, fontScale: Float): Pair<Float, Float> {
        var density = 0f
        compose.setContent {
            val base = LocalDensity.current
            density = base.density
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                LocalKeepTheme {
                    Box(Modifier.width(width)) {
                        LkStrengthMeter(
                            strength = LkStrength.Excellent,
                            modifier = Modifier.testTag(TAG),
                            trailing = { Text(BITS, style = LkText.monoCaption) },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val whole = compose.onNodeWithTag(TAG).fetchSemanticsNode().size.height / density
        val text = compose.onNodeWithText(BITS).fetchSemanticsNode().size.height / density
        return whole to text
    }

    @Test
    fun theBarTakesItsOwnRowWhenTheWordsLeaveItNoRoomAtTheDefaultTextSize() {
        val (whole, text) = heights(150.dp, 1.0f)
        assertTrue(
            whole > text + 1f,
            "LkStrengthMeter was ${whole}dp tall against ${text}dp of text in a 150dp column at " +
                "font scale 1.0 - the same height, so the bar is still on the word's line and was " +
                "given whatever width the word and the bit count left over (zero on device).",
        )
    }

    @Test
    fun theBarKeepsTheWordsLineWhenThereIsRoomForIt() {
        val (whole, text) = heights(360.dp, 1.0f)
        assertTrue(
            whole <= text + 1f,
            "LkStrengthMeter stacked in a 360dp column at font scale 1.0 (${whole}dp against " +
                "${text}dp of text). A bar with room belongs on the word's line.",
        )
    }

    private companion object {
        const val TAG = "strength"
        const val BITS = "133 bits"
    }
}

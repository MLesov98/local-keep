package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rendered half of F-1: a placeholder that needs two lines gets a well two lines tall.
 *
 * [LkPlaceholderMaxLinesTest] pins the arithmetic. This one pins that the arithmetic reaches the
 * screen and that the well grows to meet it instead of ellipsizing inside a fixed box — "never an
 * ellipsis" is a statement about pixels, and only a measure pass can check it.
 *
 * ### How it can fail, and why it is relative
 *
 * A well has a `defaultMinSize` floor and its content on top, so an absolute height is a number
 * tuned to one font and would drift with any type change. The question the finding actually asks is
 * comparative: does a *long* placeholder take more room than a *short* one? Before the fix it could
 * not — both were one line, both landed on the same floor, and the long one silently lost its tail.
 *
 * ### The two settings that are load-bearing
 *
 * [GraphicsMode.Mode.NATIVE], for the reason [LkDialogBoundsTest] records in full: under
 * Robolectric's legacy graphics there is no real text layout, so nothing can wrap and every
 * assertion here would pass on the broken code.
 *
 * The geometry is the device repro's, not a convenient one. 360dp wide — the emulator ran
 * `wm density 480` on a 1080px panel for exactly this — inset by [LkFrame.horizontal], which is the
 * item flow's own margin and measures 26dp on each side in `shots/022-*`. A wider window absorbs
 * font scale 2.0 and the placeholder never wraps at all.
 *
 * FAILS WITHOUT THE FIX: the placeholder `Text` in `LkTextField` carried a literal `maxLines = 1`,
 * so the long and short wells measured the same height at every scale and
 * [aLongPlaceholderTakesASecondLineAtTheLargestScale] reported a difference of 0.0dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkPlaceholderGrowsWithFontScaleTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Both wells in one composition, because a `ComposeContentTestRule` accepts exactly one
     * `setContent` — and because rendering them side by side is what makes the comparison fair:
     * same window, same theme, same font scale, one string apart.
     */
    private fun showBothWells() {
        compose.setContent {
            LocalKeepTheme {
                Column {
                    for ((tag, placeholder) in listOf(SHORT to SHORT_PLACEHOLDER, LONG to NOTE_PLACEHOLDER)) {
                        LkFormField(
                            value = "",
                            onValueChange = {},
                            label = NOTE_LABEL,
                            placeholder = placeholder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = LkFrame.horizontal)
                                .testTag(tag),
                            singleLine = false,
                            // The item form's well, not the app-wide 48dp floor — see `BoxedField`.
                            minHeight = 56.dp,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Height in dp of the tagged field. */
    private fun heightOf(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().size.height / compose.density.density

    @Test
    @Config(fontScale = 2.0f)
    fun aLongPlaceholderTakesASecondLineAtTheLargestScale() {
        showBothWells()
        val short = heightOf(SHORT)
        val long = heightOf(LONG)
        assertTrue(
            long > short + ONE_LINE_AT_LEAST_DP,
            "at font scale 2.0 the secure note's placeholder still fits the same well as a " +
                "one-word one (${long}dp against ${short}dp). A sentence that does not fit one " +
                "line and is not given a second is an ellipsis — which is the finding.",
        )
    }

    /**
     * The control. At the default size both placeholders already fit one line, so the fix must cost
     * exactly nothing: the empty well is the height the comps draw, and adding a second line's worth
     * of air under every placeholder in the app would be a worse regression than the one being
     * fixed. Stays green with or without the change.
     */
    @Test
    fun theDefaultTextSizeIsUntouched() {
        showBothWells()
        val short = heightOf(SHORT)
        val long = heightOf(LONG)
        assertTrue(
            abs(long - short) < TOLERANCE_DP,
            "at font scale 1.0 a long placeholder changed the well's height (${long}dp against " +
                "${short}dp). Both fit one line here, so both wells are the same well.",
        )
    }

    private companion object {
        const val NOTE_LABEL = "Secure note"

        /** `itemedit_note_placeholder`, verbatim — the string the device pass cut. */
        const val NOTE_PLACEHOLDER = "Too sensitive for a plain notes app."

        /** A placeholder that fits one line at any scale this test uses. */
        const val SHORT_PLACEHOLDER = "Note"

        /**
         * A line of 16sp body text at font scale 2.0 is an effective 32sp. Asserting against half of
         * that keeps the test about "a second line arrived" rather than about a font metric.
         */
        const val ONE_LINE_AT_LEAST_DP = 16f

        const val TOLERANCE_DP = 0.5f
        const val SHORT = "short-placeholder-well"
        const val LONG = "long-placeholder-well"
    }
}

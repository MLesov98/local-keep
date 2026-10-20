package com.localkeep.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Suggest one" must never come to rest against the MASTER PASSWORD label.
 *
 * 2A-F4, on a device at font scale 2.0: `[68,1146][648,1209] text='MASTER PASSWORD'` beside
 * `[648,1065][1012,1209]`, the link's hit box — the two share an edge, with the link wrapped to two
 * lines. A heading and an action reading as one run of text, on the row where the action is an
 * *alternative* to typing rather than a description of the field below.
 *
 * The cause was `Arrangement.SpaceBetween`, which only distributes width that is left over: once
 * the two children fill the row there is nothing to distribute and they meet. A `spacedBy` token
 * cannot be squeezed away, and the weighted label gives up width instead.
 *
 * ### Why this measures a stand-in
 *
 * `PasswordStep` needs an `OnboardingViewModel`, which needs a vault. What is under test is one
 * row, so this is that row — the real `LkFieldLabel` with step 2's own string, the real
 * `SuggestLink`, the real arrangement — at the width and scale the defect was found on.
 * Both components are the real ones — `SuggestLink` is `internal` for exactly this — and so is the
 * arrangement; only the state plumbing around them is absent.
 *
 * `GraphicsMode.NATIVE`, because under legacy graphics there is no real text layout and a row that
 * cannot overflow passes every overlap assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PasswordLabelAndSuggestGapTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(LkSpace.md),
                ) {
                    LkFieldLabel(
                        stringResource(R.string.onboarding_password_field_label),
                        modifier = Modifier.weight(1f).testTag(LABEL),
                        maxLines = 2,
                    )
                    SuggestLink(onClick = {}, modifier = Modifier.testTag(LINK))
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun assertGap(case: String) {
        show()
        val d = compose.density.density
        val label = compose.onNodeWithTag(LABEL).fetchSemanticsNode()
        val link = compose.onNodeWithTag(LINK).fetchSemanticsNode()
        val gap = (link.positionOnScreen.x - (label.positionOnScreen.x + label.size.width)) / d
        assertTrue(
            gap >= LkSpace.md.value - TOLERANCE,
            "[$case] the label ends ${gap}dp before the Suggest link, against the ${LkSpace.md.value}dp " +
                "the row asks for — at 0 they abut and read as one run of text (2A-F4).",
        )
        assertTrue(
            label.size.width > 0,
            "[$case] the label measured no width at all, which is a different defect from this one",
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `the gap survives font scale 2 on a 360dp window`() = assertGap("360dp fontScale 2.0")

    /** The narrowest window the app supports, where there is least width to give up. */
    @Test
    @Config(qualifiers = "w309dp-h686dp-xxhdpi", fontScale = 2.0f)
    fun `the gap survives font scale 2 on the narrowest window`() = assertGap("309dp fontScale 2.0")

    /** Inert where the row always fitted. */
    @Test
    @Config(qualifiers = "w411dp-h823dp-xxhdpi")
    fun `the gap is there at font scale 1 too`() = assertGap("411dp fontScale 1.0")

    /**
     * The link's ink shares the label's baseline.
     *
     * The link needs a 48dp target, and centring its text in that box floated it ~17dp above the
     * label it sits beside: two items on one row, at two different heights. The text now rests on
     * the bottom of its target, so its bottom edge lands within a line-height difference of the
     * label's, which is what a shared baseline looks like to the eye.
     */
    @Test
    @Config(qualifiers = "w411dp-h823dp-xxhdpi")
    fun `the link text rests on the label's baseline`() {
        show()
        val d = compose.density.density
        val label = compose.onNodeWithTag(LABEL).fetchSemanticsNode()
        val ink = compose.onNodeWithTag(LINK_TEXT, useUnmergedTree = true).fetchSemanticsNode()
        val labelBottom = (label.positionOnScreen.y + label.size.height) / d
        val inkBottom = (ink.positionOnScreen.y + ink.size.height) / d
        assertTrue(
            kotlin.math.abs(labelBottom - inkBottom) <= BASELINE_TOLERANCE,
            "the link text ends at ${inkBottom}dp and the label at ${labelBottom}dp: the link floats " +
                "${labelBottom - inkBottom}dp off the label's line",
        )
    }

    private companion object {
        const val LINK_TEXT = "suggest-one-text"

        /** The two styles differ by a few sp of line height; a centred link is ~17dp off. */
        const val BASELINE_TOLERANCE = 4f
        const val LABEL = "master-password-label"
        const val LINK = "suggest-one"

        /** Rounding between dp and px at xxhdpi. */
        const val TOLERANCE = 0.5f
    }
}

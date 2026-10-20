package com.localkeep.feature.onboarding

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression test for 4-1 (triaged fix-on-sight, no verifier spent).
 *
 * The Recovery Kit step puts "Set this up later" in `StepColumn`'s `actions` block, and its inline
 * comment says why in so many words: `actions` is pinned outside the scroll, so the one control for
 * a user who cannot save the kit right now sits in front of the ceremony rather than behind it.
 *
 * `StepColumn` then grew a dynamic pin — `pinActions = imeVisible || scroll.maxValue == 0` — which
 * drops the pin the moment a step overflows, for a measured reason (`PasswordStepFieldOnScreenTest`:
 * a pinned block took 202dp off a 531dp region and laid the master-password field 104dp past the
 * bottom edge). That is right for a block holding Continue and wrong for a block holding the way
 * out, and the Recovery Kit step is the one that overflows: twenty-four monospace words, an optional
 * QR card, three save buttons and a footnote. So on exactly the step whose comment claims the link
 * cannot be scrolled away, it was.
 *
 * `alwaysPinActions` opts that step out. The trade is deliberate and stated at the parameter: this
 * step's content keeps whatever the block costs it, in exchange for a way out that is never below
 * the fold.
 *
 * Drives `StepColumn` directly with a stand-in for the step's content, for the reason
 * `PasswordStepFieldOnScreenTest` gives: `RecoveryStep` needs an `OnboardingViewModel`, which needs
 * a vault, and the frame is what is under test. The real deferral string is used, and the content is
 * sized to overflow — which is the precondition the whole finding rests on, asserted rather than
 * assumed. Same two load-bearing settings as that test: `GraphicsMode.NATIVE`, because Robolectric's
 * legacy graphics does no real text layout and every overflow assertion would pass against broken
 * code; and `autoAdvance = false`, because `LkScreen`'s aura never goes idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryDeferStaysPinnedTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(alwaysPin: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                StepColumn(
                    step = OnboardingUiState.Step.RECOVERY_KIT,
                    title = stringResource(R.string.onboarding_recovery_title),
                    body = stringResource(R.string.onboarding_recovery_body),
                    actions = {
                        LkPrimaryButton(
                            text = stringResource(R.string.onboarding_action_continue),
                            onClick = {},
                            // Disabled until the kit is saved, exactly as on the real step — which
                            // is why Continue is not the control this test is about.
                            enabled = false,
                        )
                        LkTextButton(
                            text = stringResource(R.string.onboarding_recovery_defer),
                            onClick = {},
                            modifier = Modifier.testTag(DEFER),
                        )
                    },
                    alwaysPinActions = alwaysPin,
                ) {
                    // Stands in for the mnemonic grid, the QR card, the three save buttons and the
                    // footnote. Only its height matters here, and it is what puts the step into the
                    // overflowing arrangement the finding is about.
                    repeat(CONTENT_LINES) { i -> Text("$i", color = LkTheme.colors.textMuted) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** The deferral link's bottom edge and the window's, both in dp, at first paint. */
    private class Frame(val deferBottom: Float, val windowHeight: Float, val scrollMax: Float)

    private fun measure(alwaysPin: Boolean): Frame {
        show(alwaysPin)
        val d = compose.density.density
        val root = compose.onRoot().fetchSemanticsNode()
        val scroller = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )[0].fetchSemanticsNode()
        val defer = compose.onNodeWithTag(DEFER).fetchSemanticsNode()
        return Frame(
            deferBottom = (defer.positionOnScreen.y + defer.size.height - root.positionOnScreen.y) / d,
            windowHeight = root.size.height / d,
            scrollMax = scroller.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() / d,
        )
    }

    /**
     * Production line under test: `alwaysPinActions = true` on `RecoveryStep`'s `StepColumn`, and
     * the `alwaysPinActions ||` term in `StepColumn`'s `pinActions`. Delete either and this test
     * goes red.
     */
    @Test
    @Config(qualifiers = "w411dp-h823dp-xxhdpi")
    fun `the deferral link is on screen at first paint when the step overflows`() {
        val f = measure(alwaysPin = true)
        assertTrue(
            f.scrollMax > 0f,
            "precondition: this content must overflow, or the pin would be kept anyway and the " +
                "test would prove nothing. scrollMax=${f.scrollMax}dp",
        )
        assertTrue(
            f.deferBottom <= f.windowHeight,
            "\"Set this up later\" ends ${f.deferBottom}dp down a ${f.windowHeight}dp window, so " +
                "at first paint it is off screen — the way out of the Recovery Kit ceremony is " +
                "behind the whole of the ceremony, which is the opposite of what putting it in " +
                "`actions` was for",
        )
    }

    /**
     * The control, and the assertion that held *before* the fix: with the flag off, the same content
     * puts the link off screen. Without this the test above could pass on a layout that simply
     * happened to fit, and `alwaysPinActions` would be decorative.
     */
    @Test
    @Config(qualifiers = "w411dp-h823dp-xxhdpi")
    fun `without the opt-out the same step scrolls its deferral link away`() {
        val f = measure(alwaysPin = false)
        assertTrue(
            f.scrollMax > 0f,
            "the control needs the same overflowing arrangement; scrollMax=${f.scrollMax}dp",
        )
        assertTrue(
            f.deferBottom > f.windowHeight,
            "the un-pinned arrangement is supposed to carry the action block down into the scroll. " +
                "It ended at ${f.deferBottom}dp of a ${f.windowHeight}dp window, so this control " +
                "no longer reproduces the defect and the test above proves nothing.",
        )
    }

    private companion object {
        const val DEFER = "recovery-defer"

        /**
         * Enough to overflow every supported window at font scale 1, so the two cases differ by the
         * flag and nothing else. The real step overflows on a phone with far less.
         */
        const val CONTENT_LINES = 60
    }
}

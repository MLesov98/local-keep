package com.localkeep.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
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
 * Onboarding step 2 must open with the master-password field on screen, on the narrowest window
 * this app supports, at the largest accessibility font scale.
 *
 * It is the one step in the app where "below the fold" and "does not exist" are the same thing.
 * Nothing else on that screen is interactive: Continue is disabled until a password is typed, and
 * a user who never finds the field never creates a vault — from inside an app they have not
 * finished setting up, where they cannot lower the font scale either.
 *
 * ### What was actually wrong, because it was not what it looked like
 *
 * On a 309dp window at font scale 2.0 the rendering shows a heading, three quarters of one
 * sentence, and a dead button; a `uiautomator` dump finds no edit box at all. That reads as the
 * form having been measured out of existence. It was not. Measured here on the pre-fix layout,
 * device-like window (609dp of usable height once the system bars are gone):
 *
 * ```
 * viewport=265dp scrollMax=206dp fieldTopInContent=369dp fieldH=72dp ctaTop=376dp
 * ```
 *
 * The field is composed, is 72dp tall, and sits 104dp past the bottom edge of a scroll region that
 * can move 206dp — reachable, but only by a blind gesture over a viewport whose entire visible
 * content is a paragraph. What ate the screen was the pinned action block: a Continue button and a
 * footnote that wraps to four lines at that scale, 202dp of a 531dp region, taken off the top of
 * the only space the form had. `Modifier.weight(1f)` hands the scroll region whatever the pinned
 * block leaves, so a footnote growing with the font scale silently shrinks the form's viewport.
 *
 * The fix pins that block only while it is free — see `StepColumn`. So the assertion here is the
 * one the user cares about and the one that was false: at first paint, with no gesture, is the
 * field on screen.
 *
 * ### Why this measures a stand-in for `PasswordStep`
 *
 * `PasswordStep` needs an `OnboardingViewModel`, which needs a vault. The frame is what is under
 * test, so this drives `StepColumn` directly with step 2's own strings and its own components —
 * the real title, the real body, a real `LkPasswordField`, a disabled `LkPrimaryButton` and the
 * Recovery Kit footnote. Every quantity that decides the outcome is therefore the real one; only
 * the state plumbing is absent.
 *
 * ### Two settings that are load-bearing, not boilerplate
 *
 * [GraphicsMode.Mode.NATIVE], for the reason `LkDialogBoundsTest` records in `:core:ui`: under
 * Robolectric's legacy graphics there is no real text layout, a 4,000-character body measures
 * 119dp, and every assertion about overflow passes against broken code.
 *
 * `autoAdvance = false`, because `LkScreen`'s aura breathes on a fourteen-second loop that never
 * ends. With the test clock running the frame never goes idle and Espresso throws
 * `AppNotIdleException` before a single assertion executes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PasswordStepFieldOnScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                StepColumn(
                    step = OnboardingUiState.Step.CREATE_PASSWORD,
                    title = stringResource(R.string.onboarding_password_title),
                    body = stringResource(R.string.onboarding_password_body),
                    actions = {
                        LkPrimaryButton(
                            text = stringResource(R.string.onboarding_action_continue),
                            onClick = {},
                            // Disabled is the state that matters: this is the only control on the
                            // screen and it cannot be enabled without reaching the field.
                            enabled = false,
                            modifier = Modifier.testTag(CTA),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(
                                stringResource(R.string.onboarding_password_recovery_kit_promise),
                                style = LkText.caption,
                                color = LkTheme.colors.textFaint,
                            )
                        }
                    },
                ) {
                    LkPasswordField(
                        value = "",
                        onValueChange = {},
                        label = stringResource(R.string.onboarding_password_field_label),
                        revealed = false,
                        onToggleReveal = {},
                        modifier = Modifier.testTag(FIELD),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** The one scroll region `StepColumn` owns, and the field's place in it — all in dp. */
    private class Frame(
        val viewportHeight: Float,
        val scrollMax: Float,
        val fieldTop: Float,
        val fieldHeight: Float,
    ) {
        val fieldBottom get() = fieldTop + fieldHeight
    }

    private fun measure(): Frame {
        show()
        val d = compose.density.density
        val scroller = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )[0].fetchSemanticsNode()
        val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
        val field = compose.onNodeWithTag(FIELD).fetchSemanticsNode()
        // Nothing has scrolled: every assertion below is about first paint, before any gesture.
        assertEquals(0f, range.value(), "the scroll region was not at rest")
        return Frame(
            viewportHeight = scroller.size.height / d,
            scrollMax = range.maxValue() / d,
            fieldTop = (field.positionOnScreen.y - scroller.positionOnScreen.y) / d,
            fieldHeight = field.size.height / d,
        )
    }

    private fun assertFieldIsOnScreen(case: String, f: Frame) {
        assertTrue(
            f.fieldHeight > 0f,
            "[$case] the password field measured ${f.fieldHeight}dp. It is composed with no height " +
                "at all, which is a different defect from the one this test is about.",
        )
        assertTrue(
            f.fieldBottom <= f.viewportHeight,
            "[$case] the master-password field is laid out ${f.fieldTop}dp down a " +
                "${f.viewportHeight}dp viewport and ends at ${f.fieldBottom}dp, so at first paint " +
                "none of it is on screen — the step opens on a heading and a Continue button that " +
                "cannot be enabled until the user blind-scrolls ${f.scrollMax}dp to a form nothing " +
                "says is there.",
        )
    }

    /**
     * The regression, on the window the defect was found on: 309dp wide, and 609dp of usable
     * height, which is a 686dp display once the status and navigation bars have taken their share.
     * Both accessibility sliders at maximum is the only way to reach it, and it is also the state
     * where a user has least room to recover from a dead end.
     */
    @Test
    @Config(qualifiers = "w309dp-h609dp-xxhdpi", fontScale = 2.0f)
    fun `the field is on screen at first paint on the narrowest window at font scale 2`() {
        assertFieldIsOnScreen("309x609 fontScale 2.0", measure())
    }

    /** The same width against the full display height, so the result does not hang on one inset guess. */
    @Test
    @Config(qualifiers = "w309dp-h686dp-xxhdpi", fontScale = 2.0f)
    fun `the field is on screen at first paint on a 309dp display at font scale 2`() {
        assertFieldIsOnScreen("309x686 fontScale 2.0", measure())
    }

    /**
     * Inert where the step already worked. Both of these fit without scrolling today, and the whole
     * argument for the fix is that a layout with slack to distribute puts the action block in the
     * same pixels either way — so `scrollMax` staying at zero is the assertion that the fix did not
     * quietly start scrolling screens that never needed to.
     */
    @Test
    @Config(qualifiers = "w309dp-h686dp-xxhdpi")
    fun `inert at font scale 1 on the narrowest window`() {
        val f = measure()
        assertFieldIsOnScreen("309x686 fontScale 1.0", f)
        assertEquals(0f, f.scrollMax, "font scale 1.0 on a 309dp window should still fit without scrolling")
    }

    @Test
    @Config(qualifiers = "w411dp-h823dp-xxhdpi", fontScale = 2.0f)
    fun `inert at font scale 2 on a 411dp window`() {
        val f = measure()
        assertFieldIsOnScreen("411x823 fontScale 2.0", f)
        assertEquals(0f, f.scrollMax, "font scale 2.0 on a 411dp window fits today and must keep fitting")
    }

    /**
     * The control. Every assertion above would pass just as well against a step rendering nothing
     * but a password field, which is the one arrangement that could never overflow — so this pins
     * that the heading and the action block really are on screen and really are step 2's.
     */
    @Test
    @Config(qualifiers = "w309dp-h609dp-xxhdpi", fontScale = 2.0f)
    fun `the frame under test really is the create-password step`() {
        show()
        compose.onNodeWithTag(CTA).assertExists()
        compose.onNode(hasText("Create your master password")).assertExists()
    }

    private companion object {
        const val FIELD = "master-password-field"
        const val CTA = "continue"
    }
}

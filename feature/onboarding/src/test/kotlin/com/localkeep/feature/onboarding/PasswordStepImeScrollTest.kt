package com.localkeep.feature.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkSpace
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
 * Onboarding step 2 with the keyboard up: the content region must keep the room the keyboard
 * leaves, instead of handing a second slice of it to the pinned action strip.
 *
 * ### The defect this pins
 *
 * `StepColumn` drops its pinned action strip once a step overflows — the whole argument is written
 * out beside the predicate, and `PasswordStepFieldOnScreenTest` measures the result. That predicate
 * used to carry a third term, `|| imeVisible`, which put the pin back the instant a user touched a
 * field. Step 2 is the only step in the flow whose entire purpose is typing, so the one state the
 * rule was suspended in was the one state the step is ever used in.
 *
 * What it cost, measured here on the pre-fix layout with a 233dp keyboard on a 309dp window at font
 * scale 2.0: **32dp** of content viewport. The master-password well is 72dp, so less than half of
 * one well could be on screen at any scroll position, above a Continue button that cannot be
 * enabled until two fields nothing shows have been filled. On a 360dp window at the same scale it
 * was 190dp against 357dp after the fix. The device report that opened this (`2A-F2`) read it as
 * the step refusing to scroll; it does scroll, and every field is reachable — but through a slot
 * too short to show the well the keyboard is typing into.
 *
 * ### What is asserted, and why it is geometry rather than a screenshot
 *
 * Two properties, both of which the user feels directly:
 *
 * 1. **The content region reaches the keyboard.** Its bottom edge lands at the top of the keyboard,
 *    give or take the frame's own bottom gutter — no strip in between. Before the fix the gap was
 *    the whole action block: 97dp at font scale 1.0, 199dp at 2.0, 233dp on the narrow window.
 * 2. **Nothing is unreachable.** Every child — the confirm well and the weak-password opt-out, both
 *    of which the device dump could not find — lies inside `viewport + scrollMax`.
 *
 * Plus the control in [`the strip stays pinned with no keyboard on a step that fits`]: the fix must
 * not have un-pinned the strip on steps that were never short of room.
 *
 * ### The stand-in, the insets, and the two Robolectric settings
 *
 * `PasswordStep` needs an `OnboardingViewModel`, which needs a vault; the frame is what is under
 * test, so this drives `StepColumn` directly with step 2's own strings and components — the same
 * approach, and the same reasoning, as `PasswordStepFieldOnScreenTest`. The one string that is not
 * a resource is zxcvbn's advice, which is library output in English by design (see `StrengthLine`);
 * it is reproduced verbatim from the device evidence so its wrapped height is the real one.
 *
 * Robolectric raises no keyboard, so the IME inset is dispatched to the `AndroidComposeView` by
 * hand. Dispatching to the decor view does not reach it — measured: `WindowInsets.ime.getBottom`
 * stayed 0 — so the view is found by walking the hierarchy.
 *
 * [GraphicsMode.Mode.NATIVE] because legacy graphics has no real text layout and every overflow
 * assertion would pass against broken code; `autoAdvance = false` because `LkScreen`'s aura never
 * goes idle. Both for the reasons `PasswordStepFieldOnScreenTest` records at length.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PasswordStepImeScrollTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
                    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                        LkFieldLabel(stringResource(R.string.onboarding_password_field_label))
                        LkPasswordField(
                            // Seven characters, the length the device repro typed.
                            value = "abc1234",
                            onValueChange = {},
                            label = stringResource(R.string.onboarding_password_field_label),
                            revealed = false,
                            onToggleReveal = {},
                            modifier = Modifier.testTag(FIELD),
                        )
                    }
                    Text(
                        stringResource(
                            R.string.onboarding_password_strength_summary,
                            stringResource(R.string.onboarding_password_strength_guessed_instantly),
                            ZXCVBN_ADVICE,
                        ),
                        style = LkText.caption,
                        color = LkTheme.colors.textMuted,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                        LkFieldLabel(stringResource(R.string.onboarding_password_confirm_label))
                        LkPasswordField(
                            value = "",
                            onValueChange = {},
                            placeholder = stringResource(R.string.onboarding_password_confirm_placeholder),
                            label = stringResource(R.string.onboarding_password_confirm_field_label),
                            revealed = false,
                            onToggleReveal = {},
                            modifier = Modifier.testTag(CONFIRM),
                        )
                    }
                    Text(
                        stringResource(R.string.onboarding_password_weak_consent_title),
                        style = LkText.body,
                        color = LkTheme.colors.textStrong,
                        modifier = Modifier.testTag(OPT_OUT),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** Raises a [heightPx]-tall keyboard on the composition, since Robolectric has no real one. */
    private fun raiseKeyboard(heightPx: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightPx))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread {
            fun deliver(v: android.view.View) {
                if (v.javaClass.name.contains("AndroidComposeView")) {
                    ViewCompat.dispatchApplyWindowInsets(v, insets)
                }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) deliver(v.getChildAt(i))
            }
            deliver(compose.activity.window.decorView)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** The one scroll region `StepColumn` owns, in dp, against the window it sits in. */
    private class Frame(
        val viewportHeight: Float,
        val scrollMax: Float,
        val scrollerBottom: Float,
        val windowHeight: Float,
        val keyboardHeight: Float,
    ) {
        /** The last point any content can be scrolled to. */
        val reach get() = viewportHeight + scrollMax

        /** Where the keyboard's top edge is, in the same screen coordinates as [scrollerBottom]. */
        val keyboardTop get() = windowHeight - keyboardHeight
    }

    private fun measure(keyboardPx: Int): Frame {
        show()
        raiseKeyboard(keyboardPx)
        val d = compose.density.density
        val scroller = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )[0].fetchSemanticsNode()
        val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
        // Nothing has scrolled: every assertion is about the layout the keyboard produces, before
        // any gesture and before focus has dragged anything into view.
        assertEquals(0f, range.value(), "the scroll region was not at rest")
        return Frame(
            viewportHeight = scroller.size.height / d,
            scrollMax = range.maxValue() / d,
            scrollerBottom = (scroller.positionOnScreen.y + scroller.size.height) / d,
            windowHeight = compose.activity.window.decorView.height / d,
            keyboardHeight = keyboardPx / d,
        )
    }

    /** How far below [Frame.scrollerBottom] a tagged child ends, in dp. */
    private fun bottomOf(tag: String, f: Frame): Float {
        val d = compose.density.density
        val scroller = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )[0].fetchSemanticsNode()
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return (node.positionOnScreen.y - scroller.positionOnScreen.y + node.size.height) / d
    }

    private fun assertKeyboardLeavesTheContentItsRoom(case: String, f: Frame) {
        assertTrue(
            f.scrollerBottom >= f.keyboardTop - FRAME_GUTTER,
            "[$case] with the keyboard up the content region ends ${f.keyboardTop - f.scrollerBottom}dp " +
                "above the keyboard, so something is standing between the form and the keys. It has " +
                "${f.viewportHeight}dp to show a ${FIELD_HEIGHT}dp password well, its confirmation and a " +
                "strength verdict in — the keyboard took its share of the window and the pinned action " +
                "strip took a second one.",
        )
        val fieldBottom = bottomOf(FIELD, f)
        assertTrue(
            f.viewportHeight >= FIELD_HEIGHT,
            "[$case] the content region is ${f.viewportHeight}dp tall, so the ${FIELD_HEIGHT}dp " +
                "master-password well the keyboard is typing into cannot be shown whole at any " +
                "scroll position (it ends ${fieldBottom}dp down the content).",
        )
        for (tag in listOf(CONFIRM, OPT_OUT, CTA)) {
            val bottom = bottomOf(tag, f)
            assertTrue(
                bottom <= f.reach,
                "[$case] '$tag' ends ${bottom}dp down a content column that can only be scrolled to " +
                    "${f.reach}dp, so it cannot be brought on screen at all.",
            )
        }
    }

    /**
     * The window the defect was reported on — a 360dp phone — at the three font scales that change
     * the arithmetic, with keyboards sized as that class of device draws them.
     */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun `the keyboard leaves the form its room on a 360dp window`() {
        assertKeyboardLeavesTheContentItsRoom("360x800 fontScale 1.0", measure(900))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun `the keyboard leaves the form its room at font scale 1_3`() {
        assertKeyboardLeavesTheContentItsRoom("360x800 fontScale 1.3", measure(1_100))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `the keyboard leaves the form its room at font scale 2`() {
        assertKeyboardLeavesTheContentItsRoom("360x800 fontScale 2.0", measure(900))
    }

    /**
     * The worst case, and the one that produced the 32dp reading: the narrowest window this app
     * supports at the largest accessibility font scale, which is also the state a user has least
     * room to recover from.
     */
    @Test
    @Config(qualifiers = "w309dp-h609dp-xxhdpi", fontScale = 2.0f)
    fun `the keyboard leaves the form its room on the narrowest window at font scale 2`() {
        assertKeyboardLeavesTheContentItsRoom("309x609 fontScale 2.0", measure(700))
    }

    /**
     * The control. Everything above would also pass against a frame that had simply stopped pinning
     * anything, ever — which would undo what the strip is for. With no keyboard on a window this
     * step fits in, the strip is still outside the scroll and the content still does not scroll.
     */
    @Test
    @Config(qualifiers = "w411dp-h823dp-xxhdpi")
    fun `the strip stays pinned with no keyboard on a step that fits`() {
        show()
        val d = compose.density.density
        val scroller = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )[0].fetchSemanticsNode()
        val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, range.maxValue() / d, "step 2 fits on a 411dp window and must not scroll")
        val cta = compose.onNodeWithTag(CTA).fetchSemanticsNode()
        assertTrue(
            cta.positionOnScreen.y >= scroller.positionOnScreen.y + scroller.size.height,
            "the Continue strip rode up inside the scroll on a step with room to spare",
        )
    }

    private companion object {
        const val FIELD = "master-password-field"
        const val CONFIRM = "confirm-password-field"
        const val OPT_OUT = "weak-password-opt-out"
        const val CTA = "continue"

        /** `LkTextField.minHeight` plus the well's own vertical padding, as measured. */
        const val FIELD_HEIGHT = 72f

        /**
         * The frame's own bottom padding, which legitimately sits between the content and the
         * keyboard. Anything beyond it is a second claim on the same room.
         */
        const val FRAME_GUTTER = 48f

        /** zxcvbn's advice for `abc1234`, verbatim from the device dump in `2A-F2`. */
        const val ZXCVBN_ADVICE =
            "Sequences like abc or 6543 are easy to guess. Add another word or two. " +
                "Uncommon words are better. Avoid sequences."
    }
}

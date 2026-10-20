package com.localkeep.feature.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Landscape onboarding, password step, keyboard up: the focused field must be on screen (SCL-006).
 *
 * `StepColumn` already let its action strip ride down inside the scroll once a step overflowed
 * (RG-43, `PasswordStepImeScrollTest`), but the progress rail, the 46dp above it, the gap under it
 * and the 32dp under the strip stayed pinned around the scroll. On a 640x360dp landscape window at
 * 2.0 text, with ~190dp of keyboard, those left the scroll region 0dp: the step whose whole purpose
 * is typing could only be typed blind. The shared rule (`rememberLkKeyboardUnpinsChrome`) now takes
 * the rail and both gutters into the scroll while the keyboard leaves too little room.
 *
 * Asserted as geometry, not a screenshot: the scroll region has room for the 72dp well, and the
 * well and the Continue strip can each be brought above the keyboard. The control is the same
 * frame on a 411x823 portrait window with the keyboard up, where the rail must stay pinned.
 *
 * Insets are dispatched by hand because Robolectric raises no keyboard; see
 * `PasswordStepImeScrollTest` for the settings and why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PasswordStepLandscapeImeTest {

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
                    },
                ) {
                    Column {
                        LkFieldLabel(stringResource(R.string.onboarding_password_field_label))
                        LkPasswordField(
                            value = "abc1234",
                            onValueChange = {},
                            label = stringResource(R.string.onboarding_password_field_label),
                            revealed = false,
                            onToggleReveal = {},
                            modifier = Modifier.testTag(FIELD),
                        )
                    }
                    Text(stringResource(R.string.onboarding_password_weak_consent_title))
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

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

    /**
     * Scrolls the screen's one scroll region until [target]'s bottom edge is inside it.
     *
     * Not `performScrollTo`: with `autoAdvance = false` (the aura never goes idle) that waits on a
     * clock nothing advances and spins until the heap is gone. The semantics `ScrollBy` action is
     * the same gesture without the wait; the clock is advanced by hand.
     */
    private fun scrollIntoView(target: androidx.compose.ui.test.SemanticsNodeInteraction) {
        repeat(4) {
            val scroller = compose.onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange,
                ),
            )[0].fetchSemanticsNode()
            val t = target.fetchSemanticsNode()
            val dy = (t.positionOnScreen.y + t.size.height) - (scroller.positionOnScreen.y + scroller.size.height)
            if (dy <= 0f) return
            compose.runOnUiThread {
                scroller.config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy].action!!.invoke(0f, dy)
            }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    private fun scroller() = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    )[0].fetchSemanticsNode()

    private fun bottomDp(tag: String): Float {
        val n = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return (n.positionOnScreen.y + n.size.height) / compose.density.density
    }

    /** 640x360dp landscape at xhdpi (density 2), 2.0 text, 190dp keyboard. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-xhdpi", fontScale = 2.0f)
    fun `landscape at 2_0 still has a scroll region the well fits in`() {
        show()
        raiseKeyboard(380)
        val keyboardTop = (compose.activity.window.decorView.height - 380) / compose.density.density
        val viewport = scroller().size.height / compose.density.density
        assertTrue(
            viewport >= FIELD_HEIGHT,
            "the scroll region is ${viewport}dp tall with the keyboard up, so the ${FIELD_HEIGHT}dp " +
                "master-password well cannot be shown whole at any scroll position — the step can " +
                "only be typed blind (SCL-006)",
        )
        scrollIntoView(compose.onNodeWithTag(FIELD))
        assertTrue(
            bottomDp(FIELD) <= keyboardTop + 1f,
            "the well ends ${bottomDp(FIELD)}dp down, below the keyboard's top edge at ${keyboardTop}dp",
        )
        scrollIntoView(compose.onNodeWithTag(CTA))
        assertTrue(
            bottomDp(CTA) <= keyboardTop + 1f,
            "Continue ends ${bottomDp(CTA)}dp down, below the keyboard's top edge at ${keyboardTop}dp",
        )
    }

    /**
     * The control: a tall portrait window with the keyboard up keeps today's arrangement — the
     * rail is outside the scroll, so the scroll region starts below it.
     */
    @Test
    @Config(qualifiers = "w411dp-h823dp-xxhdpi")
    fun `a tall window keeps the rail pinned above the scroll with the keyboard up`() {
        show()
        raiseKeyboard(900)
        val top = scroller().positionOnScreen.y / compose.density.density
        assertTrue(
            top >= 46f + 20f,
            "the scroll region starts ${top}dp down the window; with room to spare the 46dp gutter and " +
                "the progress rail are pinned above it and it must not have moved up to the top edge",
        )
    }

    private companion object {
        const val FIELD = "master-password-field"
        const val CTA = "continue"

        /** `LkTextField.minHeight` plus the well's own vertical padding, as `PasswordStepImeScrollTest` measures. */
        const val FIELD_HEIGHT = 72f
    }
}

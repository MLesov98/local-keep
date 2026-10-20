package com.localkeep.feature.unlock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkSecondaryButton
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
 * Unlock with the keyboard up must keep the password well on screen (SCL-003).
 *
 * ### What was wrong
 *
 * `UnlockFrame` pinned Unlock, Use Recovery Kit and the biometric button in `LkActionScaffold`'s
 * action bar unless the window was under 480dp tall. A 308dp-wide window at the largest display
 * size is 548dp tall, so it was "tall": with ~250dp of keyboard the pinned block took everything
 * above the keys and the scroll region held 0dp. Typing worked and Go submitted, which is why it was
 * first read as a harness failure — the user simply could not see the field or its error.
 *
 * ### What is asserted
 *
 * At 308x548dp, font scale 2.0, with a 250dp keyboard raised: the well can be scrolled to, ends
 * above the keyboard, and so do both buttons that were pinned (they now scroll). The control is a
 * 411x891dp portrait phone at 1.0 with a taller 300dp keyboard, where the arrangement is unchanged:
 * Unlock stays pinned just over the keys.
 *
 * Robolectric raises no keyboard, so the inset is dispatched to the `AndroidComposeView` by hand,
 * as `PasswordStepImeScrollTest` does. [GraphicsMode.Mode.NATIVE] for real text layout;
 * `autoAdvance = false` because the scaffold's aura never goes idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnlockKeyboardUnpinsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                UnlockFrame(
                    shortWindow = false,
                    scroll = rememberScrollState(),
                    hero = {
                        Column {
                            UnlockHero(subtitle = stringResource(R.string.unlock_subtitle), compact = false)
                        }
                    },
                    entry = {
                        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                            LkFieldLabel(stringResource(R.string.unlock_password_label))
                            LkPasswordField(
                                value = "",
                                onValueChange = {},
                                placeholder = stringResource(R.string.unlock_password_placeholder),
                                label = stringResource(R.string.unlock_password_label),
                                revealed = false,
                                onToggleReveal = {},
                                showCount = true,
                                modifier = Modifier.testTag(WELL),
                            )
                        }
                    },
                    actions = {
                        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            LkPrimaryButton(
                                text = stringResource(R.string.unlock_button),
                                onClick = {},
                                enabled = false,
                            )
                            LkSecondaryButton(
                                text = stringResource(R.string.unlock_use_recovery_kit_button),
                                onClick = {},
                            )
                            LkSecondaryButton(
                                text = stringResource(R.string.unlock_biometric_button),
                                icon = LkIcons.Fingerprint,
                                onClick = {},
                            )
                            Text(
                                stringResource(R.string.unlock_footer_disclaimer),
                                style = LkText.caption,
                                color = LkTheme.colors.textFaint,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                            )
                        }
                    },
                    overlay = {},
                )
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

    private fun bottomDp(node: SemanticsNode) =
        (node.positionOnScreen.y + node.size.height) / compose.density.density

    private fun keyboardTopDp(keyboardPx: Int) =
        (compose.activity.window.decorView.height - keyboardPx) / compose.density.density

    private fun assertAboveKeyboard(what: String, node: SemanticsNode, keyboardTop: Float) {
        assertTrue(
            bottomDp(node) <= keyboardTop + 1f,
            "$what ends ${bottomDp(node)}dp down the window, below the keyboard's top edge at " +
                "${keyboardTop}dp — it is covered, and the user is typing blind",
        )
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `the well and the buttons can be brought above the keyboard on a 308dp window`() {
        show()
        raiseKeyboard(KEYBOARD_PX)
        val keyboardTop = keyboardTopDp(KEYBOARD_PX)

        // FAILS WITHOUT THE FIX: the scroll region held 0dp, so there was nothing to scroll in. Asserted
        // before scrolling, because `performScrollTo` into a 0dp viewport does not fail, it spins.
        val viewport = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )[0].fetchSemanticsNode().size.height / compose.density.density
        assertTrue(
            viewport >= WELL_HEIGHT,
            "the scroll region is ${viewport}dp tall with the keyboard up, so the ${WELL_HEIGHT}dp " +
                "password well cannot be shown at any scroll position (SCL-003)",
        )
        scrollIntoView(compose.onNodeWithTag(WELL))
        assertAboveKeyboard("the master-password well", compose.onNodeWithTag(WELL).fetchSemanticsNode(), keyboardTop)
        scrollIntoView(compose.onNodeWithText(compose.activity.getString(R.string.unlock_button)))
        assertAboveKeyboard(
            "the Unlock button",
            compose.onNodeWithText(compose.activity.getString(R.string.unlock_button)).fetchSemanticsNode(),
            keyboardTop,
        )
    }

    /**
     * L-01: with the actions moved into the scroll, the empty action slot still carried its 10dp top
     * and 14dp bottom padding, so the scroll region stopped 24dp short of the keyboard (56px at the
     * emulator's density) and, in landscape, left a 136px viewport.
     *
     * FAILS WITHOUT THE FIX: the viewport's bottom edge sits 24dp above the keyboard's top edge.
     */
    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `the scroll region reaches the keyboard with no dead band above it`() {
        show()
        raiseKeyboard(KEYBOARD_PX)
        assertViewportMeetsKeyboard(KEYBOARD_PX)
    }

    @Test
    @Config(qualifiers = "w548dp-h308dp-xhdpi", fontScale = 2.0f)
    fun `landscape too`() {
        show()
        raiseKeyboard(KEYBOARD_PX_LANDSCAPE)
        assertViewportMeetsKeyboard(KEYBOARD_PX_LANDSCAPE)
    }

    private fun assertViewportMeetsKeyboard(keyboardPx: Int) {
        val scroller = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )[0].fetchSemanticsNode()
        val gap = keyboardTopDp(keyboardPx) - bottomDp(scroller)
        assertTrue(
            gap in -1f..2f,
            "the scroll region ends ${gap}dp above the keyboard: a dead band the field being typed " +
                "into cannot use (L-01)",
        )
    }

    /** The control: room to spare, so the pinned bar is still pinned just over the keys. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tall window keeps the buttons pinned over the keyboard`() {
        show()
        raiseKeyboard(KEYBOARD_PX_TALL)
        val keyboardTop = keyboardTopDp(KEYBOARD_PX_TALL)
        val unlock = compose.onNodeWithText(compose.activity.getString(R.string.unlock_footer_disclaimer))
            .fetchSemanticsNode()
        val well = compose.onNodeWithTag(WELL).fetchSemanticsNode()
        assertAboveKeyboard("the pinned footer", unlock, keyboardTop)
        assertAboveKeyboard("the well", well, keyboardTop)
        assertTrue(
            keyboardTop - bottomDp(unlock) < 40f,
            "The footer sits ${keyboardTop - bottomDp(unlock)}dp above the keyboard; with room to spare it " +
                "must stay pinned directly over the keys rather than ride down the scroll",
        )
    }

    private companion object {
        const val WELL = "unlock-well"
        const val WELL_HEIGHT = 48f

        /** 250dp at xhdpi (density 2). */
        const val KEYBOARD_PX = 500

        /** 124dp at xhdpi: ~40% of a 308dp-tall landscape window. */
        const val KEYBOARD_PX_LANDSCAPE = 250

        /** 300dp at xxhdpi (density 3). */
        const val KEYBOARD_PX_TALL = 900
    }
}

package com.localkeep.feature.unlock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkSecondaryButton
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
 * In landscape the unlock screen must show its masthead, its well and its buttons — none of them
 * sliced, all of them reachable.
 *
 * ### What was wrong
 *
 * On a short window the password well and all three buttons were pinned and the masthead was the
 * only thing left in the scroll region. On the device that region measured **28dp**: the pinned
 * block is a label, a 48dp well, Unlock, Use Recovery Kit, Unlock with biometrics and the footer
 * disclaimer, which is about 330dp of a 387dp landscape window. The masthead was drawn across the
 * 28dp that were left — the mark sliced horizontally, "Welcome back" clipped to 39px against 83px
 * in portrait, and no subtitle node in the `uiautomator` tree at all. Reported as 2A-F16
 * (2026-09-20), and reported *again* on re-verification after a first fix, because no arrangement
 * of a masthead is 28dp tall.
 *
 * The fix is that a short window pins nothing: the masthead, the well and the buttons are one
 * scroll, and `LkActionScaffold`'s action slot is called empty so its `imePadding()` still reserves
 * the keyboard's height. Scrolling is acceptable on a 387dp window; clipping is not.
 *
 * ### Why the first fix passed this test and failed the device
 *
 * This file used to rebuild the screen's arrangement by hand next to the real one. Its copy of the
 * pinned block was missing the third button ("Unlock with biometrics") and the disclaimer, so it
 * measured an 84dp scroll region where the device had 28dp and agreed that a 61dp compact masthead
 * fitted. A test that owns its own layout cannot fail when the layout is wrong. It now drives
 * [UnlockFrame] — the screen's real frame, extracted for exactly this reason — and hands it the
 * real masthead, the real well and the real three-button stack.
 *
 * ### The stand-in that remains
 *
 * `UnlockScreen` needs a Hilt-built `UnlockViewModel` and therefore a vault, so its *contents* are
 * still rebuilt here from the same components it uses. The arrangement, which is what decides the
 * outcome, is no longer a copy. `GraphicsMode.NATIVE` and `autoAdvance = false` for the reasons
 * `PasswordStepFieldOnScreenTest` records in `:feature:onboarding`: no real text layout under
 * legacy graphics, and `LkActionScaffold`'s aura never goes idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnlockHeroFitsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(shortWindow: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                UnlockFrame(
                    shortWindow = shortWindow,
                    scroll = rememberScrollState(),
                    hero = {
                        Column(Modifier.testTag(HERO)) {
                            UnlockHero(
                                subtitle = stringResource(R.string.unlock_subtitle),
                                compact = shortWindow,
                            )
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
                                icon = LkIcons.RecoveryKit,
                                onClick = {},
                            )
                            // The button the old hand-built copy left out. It is on screen whenever
                            // the device has biometrics enrolled, which is the common case and was
                            // the case on the device 2A-F16 was reported from.
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

    /** Where something sits in the one scroll region the screen owns, all in dp. */
    private class Frame(
        val viewport: Float,
        val scrollMax: Float,
        val scrollerTop: Float,
        val density: Float,
    ) {
        val reach get() = viewport + scrollMax

        fun top(node: SemanticsNode) = (node.positionOnScreen.y / density) - scrollerTop

        fun bottom(node: SemanticsNode) = top(node) + node.size.height / density
    }

    private fun measure(shortWindow: Boolean): Frame {
        show(shortWindow)
        val d = compose.density.density
        val scroller = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )[0].fetchSemanticsNode()
        val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
        return Frame(
            viewport = scroller.size.height / d,
            scrollMax = range.maxValue() / d,
            scrollerTop = scroller.positionOnScreen.y / d,
            density = d,
        )
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()

    private fun text(s: String) = compose.onNodeWithText(s).fetchSemanticsNode()

    /**
     * The window the defect was reported on: emulator-5558 rotated, 1080x2400 at 420dpi, which is
     * 914x387dp of window.
     */
    @Test
    @Config(qualifiers = "w914dp-h387dp-420dpi")
    fun `landscape shows the whole masthead without a gesture`() {
        val f = measure(shortWindow = true)
        val hero = node(HERO)
        assertTrue(
            f.bottom(hero) <= f.viewport,
            "the masthead ends ${f.bottom(hero)}dp down a ${f.viewport}dp region, so at rest the " +
                "landscape unlock screen shows a sliced mark and a clipped heading — 2A-F16.",
        )
        compose.onNodeWithText(welcomeTitle()).assertIsDisplayed()
        compose.onNodeWithText(subtitle()).assertIsDisplayed()
    }

    /**
     * And everything below it is reachable by scrolling, which is the price of showing the
     * masthead and is explicitly acceptable on a window this short.
     */
    @Test
    @Config(qualifiers = "w914dp-h387dp-420dpi")
    fun `landscape keeps the well and both buttons reachable`() {
        val f = measure(shortWindow = true)
        assertReachable(f, node(WELL), "the master-password well")
        assertReachable(f, text(unlockButton()), "the Unlock button")
        assertReachable(f, text(recoveryButton()), "the Use Recovery Kit button")
    }

    /** The same window at the largest font scale the app supports. */
    @Test
    @Config(qualifiers = "w914dp-h387dp-420dpi", fontScale = 2.0f)
    fun `landscape at font scale 2 still shows the masthead and reaches the buttons`() {
        val f = measure(shortWindow = true)
        val hero = node(HERO)
        assertTrue(
            f.bottom(hero) <= f.viewport,
            "at font scale 2 the masthead ends ${f.bottom(hero)}dp down a ${f.viewport}dp region",
        )
        assertReachable(f, node(WELL), "the master-password well")
        assertReachable(f, text(unlockButton()), "the Unlock button")
        assertReachable(f, text(recoveryButton()), "the Use Recovery Kit button")
    }

    /** A 360dp-tall landscape phone — the smallest short window the app is expected to meet. */
    @Test
    @Config(qualifiers = "w800dp-h360dp-xxhdpi")
    fun `a 360dp landscape window shows the whole masthead too`() {
        val f = measure(shortWindow = true)
        val hero = node(HERO)
        assertTrue(
            f.bottom(hero) <= f.viewport,
            "the masthead ends ${f.bottom(hero)}dp down a ${f.viewport}dp region",
        )
        assertReachable(f, text(unlockButton()), "the Unlock button")
    }

    private fun assertReachable(f: Frame, node: SemanticsNode, what: String) {
        assertTrue(
            f.bottom(node) <= f.reach + 1f,
            "$what ends ${f.bottom(node)}dp down a column that can only be scrolled to ${f.reach}dp",
        )
    }

    /**
     * The control. Portrait has always been fine and must stay untouched: the tall arrangement, the
     * 46/34dp gutter, the pinned buttons, and no scroll at all.
     */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun `portrait is unchanged`() {
        val f = measure(shortWindow = false)
        val hero = node(HERO)
        assertEquals(0f, f.scrollMax, "portrait fits and must not start scrolling")
        assertTrue(
            hero.size.height / f.density > 100f,
            "portrait measured a ${hero.size.height / f.density}dp masthead — that is the compact " +
                "arrangement, and portrait must keep the stacked one (52dp mark above the heading).",
        )
        assertTrue(
            f.bottom(hero) <= f.viewport,
            "the masthead ends ${f.bottom(hero)}dp down a ${f.viewport}dp region",
        )
        compose.onNodeWithText(welcomeTitle()).assertIsDisplayed()
        // Pinned, not scrolled: the buttons sit below the scroll region entirely.
        assertTrue(
            f.top(text(unlockButton())) >= f.viewport,
            "portrait must keep Unlock pinned below the scroll region, and it measured " +
                "${f.top(text(unlockButton()))}dp against a ${f.viewport}dp region",
        )
    }

    private fun welcomeTitle() = compose.activity.getString(R.string.unlock_welcome_title)

    private fun subtitle() = compose.activity.getString(R.string.unlock_subtitle)

    private fun unlockButton() = compose.activity.getString(R.string.unlock_button)

    private fun recoveryButton() =
        compose.activity.getString(R.string.unlock_use_recovery_kit_button)

    private companion object {
        const val HERO = "unlock-hero"
        const val WELL = "unlock-well"
    }
}

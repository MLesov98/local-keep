package com.localkeep.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LkChip
import com.localkeep.core.ui.theme.LkMonogramTile
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkStrength
import com.localkeep.core.ui.theme.LkStrengthMeter
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LkTintButton
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.ui.theme.lkMonogram
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The defect class that had no signal at all.
 *
 * "No `fontScale` test anywhere" was an open gap from the day it was found:
 * nothing in this repo rendered a composable at a non-default font scale, so **every dynamic-type
 * fix all week shipped without a regression test**, including the one this file now guards. A
 * defect class with no automated signal is one that comes back.
 *
 * It turned out not to need new machinery. `createComposeRule` and
 * `androidx.compose.ui.test.junit4` were already on this module's instrumented classpath for the
 * touch-target and overlay tests; nobody had pointed them at `LocalDensity`. Overriding
 * `Density(fontScale = …)` is all a font-scale test is.
 *
 * ### What it asserts, and why that shape
 *
 * Not "the text is not clipped" — Compose will happily report a laid-out node whose glyphs are
 * cut off, so that assertion passes on the broken code. What it asserts instead is the property the
 * fix actually established: **a button's height is a minimum, so it grows when the type does.** The
 * pre-fix code pinned `.height(56.dp)`, which is measurably constant across font scales; the fixed
 * code uses `defaultMinSize`, which is not.
 *
 * 2.0f rather than 1.3f deliberately — Android's accessibility settings reach 2.0, and a threshold
 * chosen just past the current behaviour is a threshold that passes by luck.
 */
@RunWith(AndroidJUnit4::class)
class FontScaleTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val compose = createComposeRule()

    /** Renders [content] at [fontScale] and returns the tagged node's height in dp. */
    private fun heightAt(fontScale: Float, content: @Composable () -> Unit): Float {
        var density = 0f
        compose.setContent {
            val base = LocalDensity.current
            density = base.density
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale),
            ) {
                LocalKeepTheme { Box(Modifier.width(320.dp)) { content() } }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(TAG).fetchSemanticsNode().size
        return bounds.height / density
    }

    @Test
    fun primaryButtonGrowsWithFontScale() {
        val large = heightAt(2.0f) {
            LkPrimaryButton(text = LONG_LABEL, onClick = {}, modifier = Modifier.testTag(TAG))
        }
        assertTrue(
            large > BASELINE_PRIMARY_DP,
            "LkPrimaryButton stayed at ${large}dp at fontScale 2.0. Its height must be a MINIMUM " +
                "(defaultMinSize), not a fixed .height(56.dp) — at a large accessibility font scale " +
                "a fixed height clips the label inside a box that refuses to grow. UX debt 5-1/5-3.",
        )
    }

    @Test
    fun secondaryButtonGrowsWithFontScale() {
        val large = heightAt(2.0f) {
            LkSecondaryButton(text = LONG_LABEL, onClick = {}, modifier = Modifier.testTag(TAG))
        }
        assertTrue(large > BASELINE_SECONDARY_DP, "LkSecondaryButton stayed at ${large}dp at fontScale 2.0")
    }

    @Test
    fun textButtonGrowsWithFontScale() {
        val large = heightAt(2.0f) {
            LkTextButton(text = LONG_LABEL, onClick = {}, modifier = Modifier.testTag(TAG))
        }
        assertTrue(large > BASELINE_TEXT_DP, "LkTextButton stayed at ${large}dp at fontScale 2.0")
    }

    @Test
    fun chipKeepsItsTouchTargetAtLargeFontScale() {
        // The chip deliberately KEEPS a fixed 34dp pill so a row of them stays one line — its own
        // comment explains that the fixed height is what minTouchTarget() is measured against, and
        // swapping it would silently drop the 48dp floor. So the assertion here is the opposite of
        // the ones above: the target must still clear 48dp, whatever the type does.
        val large = heightAt(2.0f) {
            LkChip(text = "Logins", selected = true, onClick = {}, modifier = Modifier.testTag(TAG))
        }
        assertTrue(
            large >= 48f,
            "the chip's touch target fell to ${large}dp at fontScale 2.0 — the 48dp floor is the " +
                "one thing its fixed pill height must not cost",
        )
    }

    /**
     * The gap the tests above never exercise: 2.0f proves these buttons GROW, but every real call
     * site that overrides [height] below 48dp (`VaultListScreen`'s empty-state actions at 40dp,
     * onboarding's secondary prompts at 46dp) was never checked at the other end of the scale. A
     * short label at 0.85 — Android's smallest system font size — cannot force a 40dp card anywhere
     * near 48dp, so if the floor came from the label growing, this is where that assumption breaks.
     * It doesn't: [minTouchTarget] on the outer node holds 48dp regardless of what the label does,
     * which is the property this asserts and the fixed-height read of the same call would have
     * failed. See `LkButtons.kt`'s doc on [LkSecondaryButton] for why the floor is a separate node
     * rather than a second `defaultMinSize` in the same chain.
     */
    @Test
    fun secondaryButtonTouchTargetHoldsTheFloorAtTheSmallestFontScale() {
        val target = heightAt(0.85f) {
            LkSecondaryButton(text = "Skip", onClick = {}, height = 40.dp, modifier = Modifier.testTag(TAG))
        }
        assertTrue(
            target >= 48f,
            "LkSecondaryButton's touch target was ${target}dp at fontScale 0.85 with height=40.dp — " +
                "below the 48dp floor. A short label at the smallest system font size cannot force " +
                "the card to grow, so the floor has to come from minTouchTarget() on the outer node.",
        )
    }

    /** Same gap, same fix, [LkTintButton]'s own 42dp override (`ItemEditScreen`'s paired actions). */
    @Test
    fun tintButtonTouchTargetHoldsTheFloorAtTheSmallestFontScale() {
        val target = heightAt(0.85f) {
            LkTintButton(
                text = "Copy",
                onClick = {},
                accent = LkTheme.colors.jade,
                height = 42.dp,
                modifier = Modifier.testTag(TAG),
            )
        }
        assertTrue(
            target >= 48f,
            "LkTintButton's touch target was ${target}dp at fontScale 0.85 with height=42.dp — below " +
                "the 48dp floor.",
        )
    }

    /**
     * Renders an [LkMonogramTile] for [title] at [fontScale], with the same glyph rendered bare
     * beside it in the tile's default style, and returns both sizes in dp: tile, then glyph.
     *
     * The bare glyph is the oracle. A plate that is a floor is at least as big as its glyph; a
     * plate that is a fixed size is not, once the glyph outgrows it — and only the glyph's own
     * measurement can say whether it has. The style is restated here rather than read off the
     * tile, so if [LkMonogramTile]'s default `textStyle` ever changes, change this too.
     */
    private fun tileAndGlyphAt(fontScale: Float, title: String): Pair<DpSize, DpSize> {
        var density = 0f
        compose.setContent {
            val base = LocalDensity.current
            density = base.density
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale),
            ) {
                LocalKeepTheme {
                    Row(Modifier.width(320.dp)) {
                        LkMonogramTile(title = title, modifier = Modifier.testTag(TAG))
                        Text(
                            lkMonogram(title),
                            style = LkText.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.testTag(GLYPH_TAG),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        fun sizeOf(tag: String): DpSize {
            val px = compose.onNodeWithTag(tag).fetchSemanticsNode().size
            return DpSize((px.width / density).dp, (px.height / density).dp)
        }
        return sizeOf(TAG) to sizeOf(GLYPH_TAG)
    }

    /**
     * The plate is a floor the glyph can push past, not a box the glyph is cut to fit.
     *
     * The first version of this test asserted that the plate's HEIGHT grows at fontScale 2.0 for
     * "Bank of Ireland", and that was never true: "BO" at 15sp semibold measures 35.4×33.9dp at 2.0
     * on this image (API 35, 2026-09-02), inside the 42dp plate on both axes, so a correct
     * `defaultMinSize` holds exactly 42dp there and the test was red against the very fix it
     * guards. The plate only has to move once the glyph is bigger than it. At 2.0 — the end of
     * Android's font-size slider — that happens on WIDTH for a wide monogram ("MM" is 46.9dp
     * across, 4.9dp past the plate) and on neither axis for a narrow one; from about 2.25 every
     * monogram outgrows the plate on both axes, which is why `LkMonogramTileFontScaleTest` under
     * `src/test` probes at 2.5.
     *
     * So this probes a wide monogram at 2.0, measures the bare glyph beside the tile, and asserts
     * the tile is never smaller than the glyph. `.size(42.dp)` fails it: the tile stays 41.9dp
     * wide under a 46.9dp glyph. The precondition is asserted too, not assumed — if a font change
     * ever fitted "MM" inside 42dp at 2.0, this says so instead of passing on a probe that stopped
     * probing, which is the failure mode this file exists to end.
     */
    @Test
    fun monogramTileGrowsWithFontScale() {
        val (tile, glyph) = tileAndGlyphAt(2.0f, WIDE_TITLE)
        val plate = BASELINE_MONOGRAM_DP.dp
        assertTrue(
            glyph.width > plate || glyph.height > plate,
            "the probe no longer probes: '${lkMonogram(WIDE_TITLE)}' at fontScale 2.0 measured " +
                "${glyph.width}×${glyph.height}, inside the $plate plate on both axes, so this test " +
                "cannot tell a floor from a fixed size. Pick a wider monogram or a larger scale.",
        )
        assertTrue(
            tile.width >= glyph.width && tile.height >= glyph.height,
            "LkMonogramTile was ${tile.width}×${tile.height} under a ${glyph.width}×${glyph.height} " +
                "glyph at fontScale 2.0. The plate must be a MINIMUM (defaultMinSize), not a fixed " +
                ".size(42.dp) — pinned, it clips its own glyph at a large accessibility font scale.",
        )
    }

    /**
     * The control for the test above — the default 42dp plate is the design's own size at the
     * default text scale, and this fix must not move it. The plate only grows once its glyph
     * genuinely needs the room.
     */
    @Test
    fun monogramTileStaysAtDesignSizeAtDefaultFontScale() {
        val default = heightAt(1.0f) {
            LkMonogramTile(title = "Bank of Ireland", modifier = Modifier.testTag(TAG))
        }
        assertTrue(
            default in (BASELINE_MONOGRAM_DP - 1f)..(BASELINE_MONOGRAM_DP + 1f),
            "LkMonogramTile was ${default}dp at fontScale 1.0, not the design's 42dp — this fix must " +
                "be inert at the default text size.",
        )
    }

    /**
     * Renders [content] at [fontScale] and returns the tagged node's height and the height of the
     * node carrying [caption], both in dp.
     *
     * [caption] is a literal this test supplies, never a string the component resolves from
     * resources. Matching on the verdict word ("Strong") was the first version and it failed on the
     * first device it met: the emulator was not in English, so the node did not exist and both
     * assertions errored instead of measuring anything.
     *
     * Two numbers rather than one because the interesting question about a row is whether it is
     * still a row. A component that wraps one of its children onto a second line is taller than the
     * child; one that squeezes that child to nothing is exactly as tall as its text and reports no
     * other difference at all.
     */
    private fun heightsAt(fontScale: Float, caption: String, content: @Composable () -> Unit): Pair<Float, Float> {
        var density = 0f
        compose.setContent {
            val base = LocalDensity.current
            density = base.density
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale),
            ) {
                LocalKeepTheme { Box(Modifier.width(320.dp)) { content() } }
            }
        }
        compose.waitForIdle()
        val whole = compose.onNodeWithTag(TAG).fetchSemanticsNode().size.height / density
        val text = compose.onNodeWithText(caption).fetchSemanticsNode().size.height / density
        return whole to text
    }

    /**
     * The strength meter must not be squeezed out of existence by its own caption.
     *
     * `LkStrengthMeter` puts the bar, the verdict word and the caller's trailing slot on one line,
     * and the bar is the only child with a weight — so the word and the bit count take their
     * intrinsic width first and the bar is whatever is left. That has no floor. Measured on the
     * Generator at font scale 2.0 the four-band bar came out 19dp wide against 107dp at 1.0.
     *
     * The bar is `.decorative()`, so it has no semantics node and cannot be measured directly.
     * What can be measured is whether it still shares the line: if it has been given its own row,
     * the component is taller than its text; if it is being crushed beside the text, the component
     * is exactly as tall as the text and there is no other observable difference. That is the whole
     * assertion, and it is why the trailing slot is filled here — an `LkStrengthMeter` with no
     * trailing content has room for the bar even at 2.0, so a test that omits it passes on the
     * broken code.
     */
    @Test
    fun strengthMeterTakesItsOwnLineAtLargeFontScale() {
        val (whole, text) = heightsAt(2.0f, BIT_COUNT) {
            LkStrengthMeter(
                strength = LkStrength.Strong,
                modifier = Modifier.testTag(TAG),
                trailing = { Text(BIT_COUNT, style = LkText.chip) },
            )
        }
        assertTrue(
            whole > text + 1f,
            "LkStrengthMeter was ${whole}dp tall at fontScale 2.0 against ${text}dp of text — the " +
                "same height, which means the bar is still on the word's line and is being given " +
                "whatever width the word and the bit count leave over. That was 19dp on device.",
        )
    }

    /**
     * The control for the test above, and the reason it is not simply "always stack".
     *
     * At the default text size the bar and the word share a line — that is the layout the comps
     * specify, and a fix that stacked unconditionally would satisfy the assertion above while
     * changing every screen that shows a password field.
     */
    @Test
    fun strengthMeterKeepsOneLineAtTheDefaultTextSize() {
        val (whole, text) = heightsAt(1.0f, BIT_COUNT) {
            LkStrengthMeter(
                strength = LkStrength.Strong,
                modifier = Modifier.testTag(TAG),
                trailing = { Text(BIT_COUNT, style = LkText.chip) },
            )
        }
        assertTrue(
            whole <= text + 1f,
            "LkStrengthMeter stacked at fontScale 1.0 (${whole}dp against ${text}dp of text). The " +
                "bar and the word belong on one line at the default text size.",
        )
    }

    private companion object {
        const val TAG = "font-scale-subject"
        const val GLYPH_TAG = "font-scale-glyph"

        /** Two wide capitals ("MM"): the monogram that outgrows the plate at fontScale 2.0 on device. */
        const val WIDE_TITLE = "Metro Mobile"

        /** What the Generator puts in the trailing slot, and the reason the bar runs out of room. */
        const val BIT_COUNT = "127 bits"

        /**
         * A real label from the app, long enough that one line at fontScale 2.0 genuinely will not
         * do. A short label like "Continue" fits inside 56dp even at 2.0, so a test using one
         * passes whether the height is a minimum or fixed — which is exactly the trap the first
         * version of this file fell into.
         */
        const val LONG_LABEL = "Continue with your Recovery Kit instead"

        // The resting heights, so "grew" means grew rather than "is at least as tall as itself".
        const val BASELINE_PRIMARY_DP = 56f
        const val BASELINE_SECONDARY_DP = 54f
        const val BASELINE_TEXT_DP = 48f

        /** [LkMonogramTile]'s own default `size` — the plate's resting diameter at fontScale 1.0. */
        const val BASELINE_MONOGRAM_DP = 42f
    }
}

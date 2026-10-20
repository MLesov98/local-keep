package com.localkeep.core.ui.theme

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.R

/**
 * How strong a secret is, in the only four words this app uses for it.
 *
 * **One vocabulary, deliberately.** The generator scored entropy in bits and named the result; the
 * login form, where passwords are actually typed, scored nothing at all; onboarding had a third
 * scale of its own for the master password. `Item Flows.dc.html` puts a meter in the password field
 * and states the rule plainly: *"Same four bands, same words, no new vocabulary."* A user who
 * learns that "Reasonable" is not good enough in one screen must not meet a different ladder in the
 * next.
 *
 * The bands themselves are unchanged from the generator's, which took them from the vault-health
 * analyser's boundaries — two screens disagreeing about what "weak" means would be worse than
 * either threshold being slightly off.
 */
enum class LkStrength(@param:StringRes val label: Int, val filledSegments: Int) {
    Weak(R.string.core_ui_strength_weak, 1),
    Reasonable(R.string.core_ui_strength_reasonable, 2),
    Strong(R.string.core_ui_strength_strong, 3),
    Excellent(R.string.core_ui_strength_excellent, 4),
    ;

    companion object {
        /**
         * For a value whose entropy is *known* — anything this app generated itself.
         *
         * 60 / 90 / 128 bits, the generator's own thresholds.
         */
        fun ofBits(bits: Int): LkStrength = when {
            bits < 60 -> Weak
            bits < 90 -> Reasonable
            bits < 128 -> Strong
            else -> Excellent
        }

        /**
         * For a value that was *typed*, from a zxcvbn 0–4 score.
         *
         * Entropy is the wrong measure here and the design comp gets this wrong: it scores a typed
         * password as `length × log2(72)`, which calls "aaaaaaaaaaaaaaaa" Strong. Length is only an
         * upper bound on strength, and a password manager telling someone their repeated-character
         * password is strong is worse than telling them nothing. zxcvbn's five scores fold onto
         * these four bands at the only place they can: its 0 ("guessed instantly") and 1 ("guessed
         * in seconds") are both Weak, because nothing separates them that a user should act on
         * differently.
         */
        fun ofScore(score: Int): LkStrength = when (score) {
            0, 1 -> Weak
            2 -> Reasonable
            3 -> Strong
            else -> Excellent
        }
    }
}

/** The band's colour. Not a property of the enum, because `core:ui` colours are theme-scoped. */
@Composable
fun LkStrength.tone(): Color {
    val c = LkTheme.colors
    return when (this) {
        LkStrength.Weak -> c.coral
        LkStrength.Reasonable -> c.amber
        LkStrength.Strong, LkStrength.Excellent -> c.jade
    }
}

/**
 * Four segments and a word, crossfading together.
 *
 * **The word is not decoration.** Colour is never the only signal in this app (UI/UX spec §2), and
 * here it carries the whole message for anyone who cannot separate coral from amber — which is the
 * pair this palette leans on hardest. The comp says the same thing from the other direction: *"The
 * bar and the word both move. Colour is never the only signal — the word carries it too."*
 *
 * [trailing] is for a value that explains the word rather than repeating it — the generator puts
 * its bit count there, so "Strong" is justified rather than asserted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LkStrengthMeter(
    strength: LkStrength,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tone = strength.tone()
    // Animated here as well as inside the segments so the word and the bar arrive together. Two
    // crossfades of different lengths on one verdict reads as the label lagging the bar.
    val toneColor by animateColorAsState(
        tone,
        lkTween(LkMotion.Crossfade, easing = LkMotion.Settle),
        label = "strength-tone",
    )
    val word = stringResource(strength.label)

    val meter: @Composable (Modifier) -> Unit = { m ->
        LkSegmentMeter(level = strength.filledSegments, tone = tone, segments = 4, height = 3.dp, modifier = m)
    }
    val verdict: @Composable () -> Unit = {
        Text(
            word,
            style = LkText.chip,
            color = toneColor,
            // Polite: the verdict changes on every keystroke, and an assertive region would talk
            // over the character the user just typed.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        trailing?.invoke()
    }

    // The bar and the word share a line until they cannot, and then they stack. Side by side, the
    // meter is the only child that absorbs a shortfall: the word and the bit count take their
    // intrinsic width first, and whatever is left over is the meter. That arithmetic has no floor.
    // Measured on the Generator at font scale 2.0, the four-band meter came out 19dp wide beside
    // "Strong 127 bits" where at 1.0 it is 107dp - and at 308dp and 1.0, the narrowest window the app
    // supports, it came out at nothing (SCL-012).
    //
    // Giving the meter a minimum instead does not work in a Row: a minimum it cannot be granted is
    // taken from the text, which pushes the word off the end, and the word is the half of this
    // component that a user who cannot separate coral from amber is reading. Nothing here may cost
    // the word. So the row is measured: it stacks when [LkText.stackAboveFontScale] is reached, and
    // also whenever the verdict's own width leaves the bar less than [MinMeterWidth] - which depends
    // on the language, the column the caller gives us and the size of the type, and so cannot be a
    // single font-scale number. At 360dp and 1.0 the bar still shares the line, as the comps specify.
    //
    // The verdict is a [FlowRow], so when the row is stacked and the word plus the caller's trailing
    // slot still do not fit the width, the trailing slot drops under the word whole instead of being
    // squeezed to a few pixels and wrapping a letter per line ("133/Bit/s", SCL-020).
    val forceStack = LocalDensity.current.fontScale >= LkText.stackAboveFontScale
    Layout(
        content = {
            meter(Modifier)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
                verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) { verdict() }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = LkSpace.sm.roundToPx()
        val minMeter = MinMeterWidth.roundToPx()
        val width = constraints.maxWidth
        val meterM = measurables[0]
        val verdictM = measurables[1]
        val natural = verdictM.maxIntrinsicWidth(Constraints.Infinity)
        val stacked = forceStack || natural + gap + minMeter > width
        if (stacked) {
            val verdictP = verdictM.measure(Constraints(maxWidth = width))
            val meterP = meterM.measure(Constraints.fixedWidth(width))
            val rowGap = LkSpace.xs.roundToPx()
            layout(width, verdictP.height + rowGap + meterP.height) {
                verdictP.placeRelative(0, 0)
                meterP.placeRelative(0, verdictP.height + rowGap)
            }
        } else {
            val verdictP = verdictM.measure(Constraints(maxWidth = width - gap - minMeter))
            val meterWidth = width - gap - verdictP.width
            val meterP = meterM.measure(Constraints.fixedWidth(meterWidth))
            val height = maxOf(verdictP.height, meterP.height)
            layout(width, height) {
                meterP.placeRelative(0, (height - meterP.height) / 2)
                verdictP.placeRelative(meterWidth + gap, (height - verdictP.height) / 2)
            }
        }
    }
}

/**
 * The narrowest the four-band bar may be before it stops sharing a line with the word: four bands of
 * about 14dp and three 5dp gaps. Below this it is a smear of stubs rather than a meter.
 */
private val MinMeterWidth = 72.dp

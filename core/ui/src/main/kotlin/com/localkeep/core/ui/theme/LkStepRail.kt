package com.localkeep.core.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * How much of a multi-step flow is left.
 *
 * The only thing on a stepped screen that says the sequence ends. Without it, a flow with no
 * visible end is where people abandon setting up a password manager — which is why onboarding grew
 * one first, and why anything else that asks for several screens in a row needs the same.
 *
 * Takes a position and a total rather than a step type, so it belongs to no one feature: onboarding
 * counts a variable number of steps depending on what the device supports, and the card flow counts
 * a fixed five. Both are a filled prefix and a count.
 *
 * The progress strings are resolved by the caller. This lives in `core/ui`, which owns no feature's
 * wording, and "2 of 7" is a sentence that has to be translatable per module.
 */
@Composable
fun LkStepRail(
    stepIndex: Int,
    stepCount: Int,
    progressText: String,
    progressDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
            repeat(stepCount) { i ->
                val fill by animateColorAsState(
                    targetValue = if (i <= stepIndex) c.jade else c.ring,
                    animationSpec = lkTween(LkMotion.Screen),
                    // Static, not "rail-$i": checkNoHardcodedUiStrings' animation-label exemption
                    // only recognizes a static lowercase-kebab token, and an interpolated one reads
                    // as a hardcoded user-facing literal to that gate's regex. This is a Layout
                    // Inspector debug tag, not user-facing text, so losing the per-segment index
                    // costs nothing real.
                    label = "rail-segment",
                )
                Box(Modifier.weight(1f).height(3.dp).background(fill, LkShape.xs))
            }
        }
        // "2 of 7", not "02 / 07". The zero-padded mono form was borrowed from the places in this
        // app that show real machine values — byte counts, entropy, countdowns — and on a setup
        // screen it read as a version string rather than as where you are in a sequence.
        Text(
            progressText,
            style = LkText.caption,
            color = c.textFaint,
            modifier = Modifier.semantics { contentDescription = progressDescription },
        )
    }
}

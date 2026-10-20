package com.localkeep.core.ui.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [LkMotion.Breathe] is the file's only symmetric curve, and symmetry is the whole reason it exists.
 *
 * The location chip's dot ran on [LkMotion.Standard] and read as a blink: a fifth of the way through
 * that curve the value has already travelled two thirds of its range, so a value animating out and
 * back flicks and then sits. This pins the property, not the control points — a future editor may
 * retune the bezier as long as it still breathes.
 */
class LkMotionBreatheTest {

    private fun sample(easing: androidx.compose.animation.core.Easing, t: Float) = easing.transform(t)

    @Test
    fun `Breathe is symmetric about its midpoint`() {
        var worst = 0f
        for (i in 0..50) {
            val t = i / 100f
            // f(t) + f(1-t) == 1 for a curve symmetric through (0.5, 0.5).
            worst = maxOf(worst, abs(sample(LkMotion.Breathe, t) + sample(LkMotion.Breathe, 1f - t) - 1f))
        }
        assertTrue(worst < 0.01f, "largest asymmetry was $worst")
    }

    @Test
    fun `Breathe eases in, where Standard does not`() {
        // The measurable difference at a fifth of the way through: the reason the dot flicked.
        val breathe = sample(LkMotion.Breathe, 0.2f)
        val standard = sample(LkMotion.Standard, 0.2f)
        assertTrue(breathe < 0.2f, "Breathe should still be accelerating at t=0.2, was $breathe")
        assertTrue(standard > 0.5f, "Standard should be past halfway at t=0.2, was $standard")
    }

    @Test
    fun `one wave per breath — the two periods are locked, so nothing beats`() {
        // The sparkle's other half was two loops at 2600ms and 3600ms drifting against each other,
        // repeating only every 46.8 seconds. The chip's wave and breath now share one constant.
        assertTrue(LkMotion.Breath == 1_800, "the comp's lcBreathe is 1.8s")
        assertTrue(LkMotion.Breath % 2 == 0, "a half-breath must be a whole number of milliseconds")
    }
}

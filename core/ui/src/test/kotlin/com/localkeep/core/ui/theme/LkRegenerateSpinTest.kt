package com.localkeep.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Regenerate glyph's rotation, which for months computed to zero.
 *
 * The old expression was `value.hashCode().toFloat() % 2f * 180f`. It looks fine and it is not:
 * `Float` has a 24-bit mantissa, so a String hash past 2^24 rounds onto a grid whose step is at
 * least 2, making the remainder exactly 0. Measured over the generator's real alphabets, 99.57% of
 * random-16 presses and 99.63% of 4-word presses produced no rotation.
 *
 * These are cheap assertions about arithmetic, and every one of them would have caught that.
 */
class LkRegenerateSpinTest {

    @Test
    fun everyPressTurnsExactlyOneHalfTurn() {
        // -0f, not 0f: `0 * -180f` is negative zero, which rotates identically but is a distinct
        // Float value and so is what an equality assertion has to name.
        assertEquals(-0f, lkRegenerateSpinDegrees(0))
        assertEquals(-180f, lkRegenerateSpinDegrees(1))
        assertEquals(-360f, lkRegenerateSpinDegrees(2))
        assertEquals(-540f, lkRegenerateSpinDegrees(3))
        assertEquals(-900f, lkRegenerateSpinDegrees(5))
    }

    /**
     * The regression the old code actually shipped: consecutive presses landing on the same angle,
     * so the glyph sat still exactly when the user needed to know the tap registered.
     */
    @Test
    fun consecutivePressesNeverLandOnTheSameAngle() {
        val angles = (0..24).map { lkRegenerateSpinDegrees(it) }
        assertEquals(angles.size, angles.toSet().size, "Two presses resolved to the same rotation.")
        angles.zipWithNext().forEach { (a, b) ->
            assertEquals(-180f, b - a, "Every step must be a half-turn, not a toggle.")
        }
    }

    /** Direction is fixed. The old expression picked it from a hash, so it sometimes ran backwards. */
    @Test
    fun rotationOnlyEverGoesOneWay() {
        assertTrue((1..50).all { lkRegenerateSpinDegrees(it) < lkRegenerateSpinDegrees(it - 1) })
    }

    /**
     * Keeps the reason alive. This reproduces the expression that shipped, so anyone tempted to
     * "simplify" the rotation back to a function of the value can see what that costs — and so the
     * claim in [lkRegenerateSpinDegrees]'s KDoc stays a measurement rather than a memory.
     */
    @Test
    fun theOldValueDerivedExpressionCollapsesToZero() {
        val samples = listOf(
            "K7mQ2xZp9RtLv4Wc", // random-16, the generator's default mode
            "correct-horse-battery-staple",
            "8Kd2qZr5NpXw3Bum",
            "amber-ridge-tundra-vault",
        )
        val stuck = samples.count { it.hashCode().toFloat() % 2f * 180f == 0f }
        assertEquals(
            samples.size,
            stuck,
            "The old expression is expected to produce no rotation for all of these. If this ever " +
                "fails, the arithmetic argument in lkRegenerateSpinDegrees needs re-checking, not " +
                "the test relaxing.",
        )
    }
}

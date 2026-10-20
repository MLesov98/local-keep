package com.localkeep.core.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The averaging premise, tested as the series of numbers an accelerometer actually produces.
 *
 * A device cannot be asked to be held perfectly still on cue, nor to be walked at a chosen pace, so
 * the thresholds are decided over synthetic series here and confirmed on hardware separately — the
 * tripod, held-still and walk-five-metres protocols. Both halves are needed: this file proves the
 * arithmetic, and only a phone can prove the numbers were the right ones.
 */
class StillnessMonitorTest {

    private fun feed(
        monitor: StillnessMonitor,
        seconds: Double,
        amplitude: Float,
        fromNanos: Long = 0L,
        hz: Int = 50,
    ): Long {
        val step = 1_000_000_000L / hz
        var at = fromNanos
        val samples = (seconds * hz).toInt()
        repeat(samples) { index ->
            // Alternating about gravity, so the mean is 9.81 and the deviation is the amplitude —
            // which is the quantity the monitor is judging and the only one that should matter.
            val sign = if (index % 2 == 0) 1f else -1f
            monitor.accept(at, GRAVITY + sign * amplitude)
            at += step
        }
        return at
    }

    @Test
    fun `a phone held still is not moving, and a walk is`() {
        // One to two orders of magnitude apart, which is the whole reason this replaced a Doppler
        // speed whose own noise was the same size as the threshold applied to it.
        val held = StillnessMonitor()
        feed(held, seconds = 2.0, amplitude = 0.05f)
        assertFalse(held.isMoving, "0.05 m/s² of hand tremor is a phone being held, not carried")

        val walking = StillnessMonitor()
        feed(walking, seconds = 2.0, amplitude = 2.0f)
        assertTrue(walking.isMoving)
    }

    @Test
    fun `only sustained motion discards the pool`() {
        // The false-positive guard, and the reason there are two verdicts rather than one. Shifting
        // a grip is not a reason to throw away ten seconds of converged samples; a step after a step
        // after a step is.
        val monitor = StillnessMonitor()
        val afterJolt = feed(monitor, seconds = 0.6, amplitude = 2.5f)
        assertTrue(monitor.isMoving, "the jolt is detected immediately")
        assertFalse(monitor.hasSustainedMotion, "but six tenths of a second is not a walk")

        feed(monitor, seconds = 2.0, amplitude = 2.5f, fromNanos = afterJolt)
        assertTrue(monitor.hasSustainedMotion)
    }

    @Test
    fun `standing still again clears both verdicts`() {
        // Otherwise one step at the start of a capture would discard every pool for the rest of it.
        val monitor = StillnessMonitor()
        val afterWalk = feed(monitor, seconds = 3.0, amplitude = 2.5f)
        assertTrue(monitor.hasSustainedMotion)

        feed(monitor, seconds = 2.0, amplitude = 0.04f, fromNanos = afterWalk)
        assertFalse(monitor.isMoving)
        assertFalse(monitor.hasSustainedMotion)
    }

    @Test
    fun `acknowledging the discard stops it happening on every epoch after`() {
        // The pool is cleared once per bout of motion, not once per fix. Without this a user who
        // walks for ten seconds clears the pool ten times and the capture never rebuilds one.
        val monitor = StillnessMonitor()
        val afterWalk = feed(monitor, seconds = 3.0, amplitude = 2.5f)
        assertTrue(monitor.hasSustainedMotion)
        monitor.acknowledgeSustainedMotion()
        assertFalse(monitor.hasSustainedMotion)

        // Still moving, so the instantaneous verdict stands and the epochs keep being downweighted;
        // it is only the discard that has been spent.
        feed(monitor, seconds = 0.5, amplitude = 2.5f, fromNanos = afterWalk)
        assertTrue(monitor.isMoving)
        assertFalse(monitor.hasSustainedMotion)
    }

    @Test
    fun `a window too short to have a shape does not claim one`() {
        // The same argument the accuracy series makes: two points are a line. The standing verdict
        // is left alone rather than guessed at, which for a fresh monitor means "not moving" and is
        // the safe direction — an unproven walk downweights nothing.
        val monitor = StillnessMonitor()
        monitor.accept(0L, GRAVITY + 3f)
        monitor.accept(20_000_000L, GRAVITY - 3f)
        assertFalse(monitor.isMoving)
    }

    @Test
    fun `a garbage reading is dropped rather than poisoning the window`() {
        val monitor = StillnessMonitor()
        feed(monitor, seconds = 2.0, amplitude = 0.05f)
        monitor.accept(2_100_000_000L, Float.NaN)
        assertFalse(monitor.isMoving, "one NaN must not make a still phone look like a walk")
    }

    private companion object {
        const val GRAVITY = 9.81f
    }
}

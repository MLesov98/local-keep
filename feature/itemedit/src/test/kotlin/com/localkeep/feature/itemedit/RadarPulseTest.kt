package com.localkeep.feature.itemedit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The location field's two loops, pinned where they can be measured.
 *
 * Both defects these guard were reported as one symptom — *"a weird sparkling that's happening
 * randomly"* — and neither is visible in a screenshot, which is why they survived four QA passes.
 */
class RadarPulseTest {

    @Test
    fun `the echo envelope starts and ends at zero, so a restart has no step`() {
        // The shipped envelope was `0.20 * (1 - progress)`: 0.20 at progress 0. Every 2.6s a ring
        // snapped into existence at full strength in the middle of the panel. That is the step.
        assertEquals(0f, echoAlpha(0f), 1e-6f)
        assertEquals(0f, echoAlpha(1f), 1e-6f)
    }

    @Test
    fun `the echo envelope peaks at thirty percent, as the comp draws it`() {
        val peak = echoAlpha(0.30f)
        assertTrue(peak > echoAlpha(0.15f), "rising into the peak")
        assertTrue(peak > echoAlpha(0.45f), "falling out of it")
        assertEquals(0.10f, peak, 1e-6f)
    }

    @Test
    fun `the echo envelope is continuous everywhere, including across the peak`() {
        // A curve with no jump: no two adjacent samples may differ by more than a smooth curve at
        // this step size can. 200 samples over [0,1] puts the bound well above the true maximum
        // slope (0.10/0.30 per unit = 0.00167 per step) and well below any real discontinuity.
        var worst = 0f
        var previous = echoAlpha(0f)
        for (i in 1..200) {
            val here = echoAlpha(i / 200f)
            worst = maxOf(worst, abs(here - previous))
            previous = here
        }
        assertTrue(worst < 0.01f, "largest single-step jump was $worst")
    }

    @Test
    fun `the pulse runs while a fix stream is open and stops the moment it is not`() {
        assertTrue(radarPulseRuns(RadarPhase.SEARCHING, reduceMotion = false))
        // REFINING is the one this fixes: the sweep stopped at the first fix, so the panel went
        // completely still while the sentence underneath still said "Still getting closer…".
        assertTrue(radarPulseRuns(RadarPhase.REFINING, reduceMotion = false))
        assertFalse(radarPulseRuns(RadarPhase.SETTLED, reduceMotion = false))
        assertFalse(radarPulseRuns(RadarPhase.CAPTURED, reduceMotion = false))
        assertFalse(radarPulseRuns(RadarPhase.IDLE, reduceMotion = false))
    }

    @Test
    fun `reduced motion stops it in every phase`() {
        RadarPhase.entries.forEach { phase ->
            assertFalse(radarPulseRuns(phase, reduceMotion = true), "$phase under reduced motion")
        }
    }
}

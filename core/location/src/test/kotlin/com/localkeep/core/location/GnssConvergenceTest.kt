package com.localkeep.core.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stopping rule, tested as what it is: a judgement about the shape of a curve.
 *
 * None of these cases is reachable by driving a device. Making a real receiver converge on cue, or
 * stall on cue, or report a second band on cue, is not something a test can arrange — which is
 * exactly why the decision is a pure function over a list of numbers and the Android side only
 * collects them.
 */
class GnssConvergenceTest {

    private fun seconds(value: Double) = (value * 1_000_000_000L).toLong()

    /** A series of readings one second apart, starting at t=0. */
    private fun series(vararg accuracies: Float) = accuracies.mapIndexed { index, accuracy ->
        GnssConvergence.AccuracySample(seconds(index.toDouble()), accuracy)
    }

    private fun plateaued(
        history: List<GnssConvergence.AccuracySample>,
        nowSeconds: Double = history.last().elapsedRealtimeNanos / 1e9,
        windowSeconds: Double = 8.0,
        deltaMeters: Float = 0.5f,
    ) = GnssConvergence.hasPlateaued(
        history = history,
        nowElapsedNanos = seconds(nowSeconds),
        windowNanos = seconds(windowSeconds),
        deltaMeters = deltaMeters,
    )

    @Test
    fun `a receiver still converging has not plateaued`() {
        // The case the old threshold rule got wrong. Accuracy has been inside any reasonable
        // threshold for seconds and is still falling by metres — stopping here saves a position the
        // next few seconds would have improved on.
        val improving = series(
            30f, 18f, 12f, 9f, 8f, 7f, 6f, 5f, 4.2f, 3.6f,
            3.2f, 2.9f, 2.6f, 2.4f, 2.2f, 2.0f, 1.9f, 1.8f, 1.7f, 1.6f,
        )
        assertFalse(plateaued(improving))
    }

    @Test
    fun `a receiver that has stopped improving has plateaued`() {
        // Best up to the cutoff eight seconds back: 3.7. Best overall: 3.5. Two tenths of a metre
        // bought by eight seconds of waiting, and the next eight will not buy more.
        val flattened = series(
            30f, 12f, 8f, 5f, 4.2f, 4.0f, 3.9f, 3.9f, 3.8f, 3.7f,
            3.7f, 3.7f, 3.65f, 3.6f, 3.6f, 3.6f, 3.55f, 3.5f, 3.5f, 3.5f,
        )
        assertTrue(plateaued(flattened))
    }

    @Test
    fun `a series too short to have a shape never claims one`() {
        // Two points are a line and every line has a slope. Three is the fewest from which
        // "it flattened" is distinguishable from "these two happened to be equal".
        assertFalse(plateaued(series(4f, 4f), nowSeconds = 20.0))
        assertTrue(GnssConvergence.MIN_SAMPLES_FOR_A_SLOPE == 3)
    }

    @Test
    fun `a series that does not span the window yet never claims a plateau`() {
        // Four identical readings over three seconds are not a plateau on an eight-second window —
        // they are a capture that has barely started. Nothing is older than the cutoff, so there is
        // no comparison to make, and the answer is "not yet" rather than a guess.
        val young = series(4f, 4f, 4f, 4f)
        assertFalse(plateaued(young, nowSeconds = 3.0))
        // The same readings once the window has actually elapsed, with the series extended so
        // something sits before the cutoff, do plateau.
        val matured = series(4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f)
        assertTrue(plateaued(matured, nowSeconds = 9.0))
    }

    @Test
    fun `a single late improvement keeps the capture open`() {
        // Flat for seconds and then a metre better. The delta is measured against the best seen
        // before the window, not against the previous reading, so one real improvement resets the
        // judgement rather than being averaged away by the flat run around it.
        val lateGain = series(
            9f, 6f, 5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f,
            5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f, 3.5f,
        )
        assertFalse(plateaued(lateGain))
    }

    @Test
    fun `one lucky epoch no longer latches the detector for the rest of the capture`() {
        // The defect this replaced, and it was a latch rather than a bad judgement. Both terms used
        // to be minima of nested prefixes, so `bestBeforeWindow` was never smaller than
        // `bestOverall` — and the moment the all-time-best sample was itself older than the cutoff
        // the two became the same number and the difference was exactly zero. Permanently
        // plateaued, escapable only by beating an all-time best by half a metre, which a receiver
        // that had produced that best by luck was never going to do.
        //
        // Below: one momentarily favourable geometry at t=5 reports 4 m, and the receiver is
        // otherwise genuinely converging through 12 m toward 7.5. At t=13 the fluke has just fallen
        // behind the cutoff. The old rule called this settled and stopped a capture that was still
        // improving by half a metre a second.
        val flukeThenConverging = series(
            30f, 25f, 20f, 16f, 13f, 4f, 12f, 11f, 10f, 9.5f, 9f, 8.5f, 8f, 7.5f,
        )
        assertFalse(
            plateaued(flukeThenConverging, nowSeconds = 13.0),
            "a receiver improving by half a metre a second has not plateaued, whatever one epoch " +
                "five seconds ago happened to claim",
        )
    }

    @Test
    fun `the same fluke does not stop a real plateau being recognised later`() {
        // The other half, and without it the change above could have been "never plateau", which
        // would pass that test and turn every capture into a forty-five second wait. Same series,
        // now genuinely flat at 7.5 m for twelve seconds.
        val settledAfterAFluke = series(
            30f, 25f, 20f, 16f, 13f, 4f, 12f, 11f, 10f, 9.5f, 9f, 8.5f, 8f, 7.5f,
            7.5f, 7.5f, 7.5f, 7.5f, 7.5f, 7.5f, 7.5f, 7.5f, 7.5f, 7.5f, 7.5f, 7.5f,
        )
        assertTrue(plateaued(settledAfterAFluke, nowSeconds = 25.0))
    }

    @Test
    fun `an armed second-band gate stands down only on evidence, never on a timer`() {
        // The gate blocks every early exit until four second-band signals are contributing. On a
        // partly obstructed sky it is entirely ordinary to see two and never a third, and the gate
        // is then armed, permanently unmet, and holding a settled capture to the deadline for a
        // correction that is not coming. Standing it down needs *both* halves: nothing has improved
        // and nothing new has been acquired. One of the two is not evidence, it is a stopwatch.
        val window = seconds(8.0)
        assertTrue(
            GnssConvergence.secondBandStalled(
                plateauHeldNanos = seconds(17.0),
                secondBandUnchangedNanos = seconds(17.0),
                windowNanos = window,
            ),
        )
        assertFalse(
            GnssConvergence.secondBandStalled(
                plateauHeldNanos = seconds(17.0),
                secondBandUnchangedNanos = seconds(5.0),
                windowNanos = window,
            ),
            "a second band still arriving is exactly what the gate exists to wait for",
        )
        assertFalse(
            GnssConvergence.secondBandStalled(
                plateauHeldNanos = seconds(5.0),
                secondBandUnchangedNanos = seconds(17.0),
                windowNanos = window,
            ),
            "a series still improving is not a capture with nothing left to gain",
        )
        assertFalse(
            GnssConvergence.secondBandStalled(
                plateauHeldNanos = 0L,
                secondBandUnchangedNanos = seconds(40.0),
                windowNanos = window,
            ),
            "a capture that has never plateaued cannot have stalled",
        )
        // Two whole windows, so a single window of quiet is never enough on its own.
        assertFalse(
            GnssConvergence.secondBandStalled(
                plateauHeldNanos = seconds(9.0),
                secondBandUnchangedNanos = seconds(9.0),
                windowNanos = window,
            ),
        )
    }

    @Test
    fun `the sample pool gate is a floor, not a ceiling`() {
        // Absolute, it disabled the early exit and the averaging together for every capture that
        // plateaued above it: no sample entered the pool, so the two-sample precondition on stopping
        // early could never be met, and with nothing to combine a single unaveraged epoch was saved.
        assertTrue(
            samplePoolCeiling(settleAccuracyMeters = 10f, bestAccuracyMeters = 14f) > 14f,
            "a receiver plateauing at 14 m has to be able to average its own 14 m readings",
        )
        // A receiver doing well keeps the tight pool it always had.
        assertEquals(10f, samplePoolCeiling(settleAccuracyMeters = 10f, bestAccuracyMeters = 3f))
        assertEquals(10f, samplePoolCeiling(settleAccuracyMeters = 10f, bestAccuracyMeters = 6.6f))
        // And it only ever loosens as the receiver's own estimate loosens.
        assertTrue(
            samplePoolCeiling(10f, 20f) > samplePoolCeiling(10f, 14f),
        )
    }

    @Test
    fun `only a second-band signal counts toward the dual-frequency gate`() {
        // Everything a receiver's primary signals sit on, and everything the second band covers.
        // The bracket is wide because nothing lives between the two groups; a tight tolerance
        // around each nominal frequency would be a table that goes stale as constellations add
        // signals.
        assertTrue(GnssConvergence.isSecondaryBand(1_176_450_000f), "GPS L5 / Galileo E5a / BeiDou B2a")
        assertTrue(GnssConvergence.isSecondaryBand(1_207_140_000f), "Galileo E5b / BeiDou B2I")
        assertTrue(GnssConvergence.isSecondaryBand(1_202_025_000f), "GLONASS L3")
        assertTrue(GnssConvergence.isSecondaryBand(1_227_600_000f), "GPS L2")
        assertFalse(GnssConvergence.isSecondaryBand(1_575_420_000f), "GPS L1 / Galileo E1 is primary")
        assertFalse(GnssConvergence.isSecondaryBand(1_602_000_000f), "GLONASS L1 is primary")
        assertFalse(GnssConvergence.isSecondaryBand(0f))
    }

    @Test
    fun `a device that reports no frequencies passes the gate rather than failing it`() {
        // The distinction the whole gate rests on. A device that cannot answer the question is not
        // failing the check — holding a capture open waiting for evidence that will never arrive is
        // how a quality gate becomes a hang, and this feature already shipped one wait a user
        // called unusable.
        assertTrue(
            GnssConvergence.secondBandReady(
                anyCarrierFrequencyReported = false,
                dualBandSatellitesInFix = 0,
                requiredSatellites = 4,
            ),
        )
    }

    @Test
    fun `once frequencies are reported the gate means what it says`() {
        assertFalse(
            GnssConvergence.secondBandReady(
                anyCarrierFrequencyReported = true,
                dualBandSatellitesInFix = 3,
                requiredSatellites = 4,
            ),
            "three second-band signals is not a dual-frequency solution standing on its own",
        )
        assertTrue(
            GnssConvergence.secondBandReady(
                anyCarrierFrequencyReported = true,
                dualBandSatellitesInFix = 4,
                requiredSatellites = 4,
            ),
        )
    }
}

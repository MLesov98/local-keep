package com.localkeep.core.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The geometry of a solution, tested as arithmetic over two arrays of degrees.
 *
 * Nothing here is reachable by driving a device: a test cannot ask the constellation to stand in a
 * chosen arrangement, and the arrangements that matter most — every satellite on one side of a
 * street, every elevation reported as zero by a device that does not measure it — are precisely the
 * ones nobody can arrange. That is the whole reason this is a pure function and the Android side
 * only reads the callback.
 */
class GnssGeometryTest {

    private fun sky(vararg pairs: Pair<Float, Float>) =
        pairs.map { GnssGeometry.SkyPosition(azimuthDegrees = it.first, elevationDegrees = it.second) }

    @Test
    fun `a constellation spread around the sky scores near one`() {
        // Four satellites at the four bearings and four different elevations. The figure is the one
        // an independent least-squares solve of the same geometry gives; it is pinned so a change to
        // the sign convention on the line-of-sight vector, or to which two elements of the
        // covariance are summed, cannot pass unnoticed.
        val hdop = requireNotNull(horizontal(sky(0f to 30f, 90f to 50f, 180f to 40f, 270f to 60f)))
        assertEquals(1.577, hdop, 0.005)
    }

    @Test
    fun `four satellites at one elevation cannot be solved, and saying so is the right answer`() {
        // Not a contrived input: it is what a device that does not really measure elevation reports
        // when it fills the field with a constant. With every satellite at the same height the
        // altitude column and the receiver-clock column of the geometry matrix are proportional, so
        // the two are indistinguishable and the normal matrix is exactly singular. Inventing a large
        // number here would be inventing a penalty; the honest answer is that this device has not
        // told the app anything, and the weighting falls back to the satellite count.
        assertNull(horizontal(sky(0f to 45f, 90f to 45f, 180f to 45f, 270f to 45f)))
    }

    @Test
    fun `satellites crowded into one sector are far worse than the same number spread out`() {
        // The case the satellite count could never see, and the reason this file exists. Same four
        // satellites, same four elevations; one set spans the sky and one spans a street.
        val spread = requireNotNull(horizontal(sky(0f to 30f, 90f to 50f, 180f to 40f, 270f to 60f)))
        val crowded = requireNotNull(horizontal(sky(0f to 30f, 20f to 50f, 40f to 40f, 60f to 60f)))
        assertTrue(
            crowded > spread * 3,
            "a 60-degree sector scored $crowded against $spread for an open sky",
        )
    }

    @Test
    fun `more satellites on one side is worse geometry than fewer all round`() {
        // Stated as the comparison the weighting actually makes. Ten in the eastern half of the sky
        // is a common street and scores as excellent by count; six well distributed is the better
        // solution and used to be penalised for being six.
        val sixAllRound = requireNotNull(
            horizontal(sky(0f to 60f, 60f to 30f, 120f to 45f, 180f to 55f, 240f to 25f, 300f to 40f)),
        )
        val tenOneSide = requireNotNull(
            horizontal(
                sky(
                    10f to 20f, 25f to 35f, 40f to 50f, 55f to 30f, 70f to 45f,
                    85f to 25f, 100f to 55f, 115f to 40f, 130f to 30f, 145f to 20f,
                ),
            ),
        )
        assertTrue(
            tenOneSide > sixAllRound,
            "ten stacked satellites scored $tenOneSide, six spread scored $sixAllRound",
        )
    }

    @Test
    fun `a device that cannot answer the question is told nothing rather than guessed at`() {
        // Presence of the API is not presence of the data. A device filling elevation with zero, or
        // reporting fewer than the four ranges a solution needs, must leave the weighting exactly as
        // it behaved before geometry was ever read — which is what a null does.
        assertNull(horizontal(sky(0f to 45f, 90f to 45f, 180f to 45f)), "three is not a solution")
        assertNull(
            horizontal(sky(0f to 0f, 90f to 0f, 180f to 0f, 270f to 0f)),
            "all-zero elevations are an unfilled field, not a constellation on the horizon",
        )
        assertNull(
            horizontal(sky(30f to 45f, 30f to 45f, 30f to 45f, 30f to 45f)),
            "four satellites in the same place make the normal matrix singular",
        )
        assertNull(horizontal(emptyList()))
        assertNull(
            horizontal(sky(0f to Float.NaN, 90f to 45f, 180f to 45f, 270f to 45f)),
            "a non-finite reading is dropped, and dropping it leaves too few",
        )
    }

    @Test
    fun `the low-elevation fraction counts only what the receiver is standing on`() {
        assertEquals(0.5f, GnssGeometry.lowElevationFraction(sky(5f to 10f, 90f to 8f, 180f to 40f, 270f to 60f)))
        assertEquals(0f, GnssGeometry.lowElevationFraction(sky(0f to 40f, 90f to 60f)))
        assertEquals(1f, GnssGeometry.lowElevationFraction(sky(0f to 4f, 90f to 14.9f)))
        // A device reporting no elevations at all reports no fraction, rather than a fraction of one.
        assertEquals(0f, GnssGeometry.lowElevationFraction(sky(0f to 0f, 90f to 0f)))
        assertEquals(0f, GnssGeometry.lowElevationFraction(emptyList()))
    }

    @Test
    fun `the widest empty wedge of sky is measured around the circle, not along the list`() {
        // The wrap is the whole difficulty: satellites at 10 and 350 degrees are twenty degrees
        // apart, not three hundred and forty, and a naive sort-and-subtract gets the answer exactly
        // backwards for the arrangement that matters most.
        assertEquals(90.0, requireNotNull(GnssGeometry.widestAzimuthGapDegrees(sky(0f to 40f, 90f to 40f, 180f to 40f, 270f to 40f))), 0.001)
        assertEquals(330.0, requireNotNull(GnssGeometry.widestAzimuthGapDegrees(sky(0f to 40f, 10f to 40f, 20f to 40f, 30f to 40f))), 0.001)
        // Negative and out-of-range bearings are normalised rather than rejected; some devices
        // report -90 where others report 270.
        assertEquals(90.0, requireNotNull(GnssGeometry.widestAzimuthGapDegrees(sky(-90f to 40f, 0f to 40f, 90f to 40f, 180f to 40f))), 0.001)
        assertNull(GnssGeometry.widestAzimuthGapDegrees(sky(0f to 40f, 90f to 40f, 180f to 40f)))
        assertNull(
            GnssGeometry.widestAzimuthGapDegrees(sky(0f to 40f, 0f to 40f, 0f to 40f, 0f to 40f)),
            "one constant repeated is an unfilled field, not four satellites in a line",
        )
    }

    @Test
    fun `blockage inflation widens a claim only when there is a reason to`() {
        // A ramp with two anchors and a cap, and it must be inert at both ends: an open sky and a
        // device that reports nothing both have to leave the figure exactly as the cluster computed
        // it, or this becomes a systematic pessimism applied to every capture on some hardware.
        assertEquals(1f, GnssGeometry.blockageInflation(null))
        assertEquals(1f, GnssGeometry.blockageInflation(90.0))
        assertEquals(1f, GnssGeometry.blockageInflation(120.0))
        assertTrue(GnssGeometry.blockageInflation(160.0) > 1f)
        assertTrue(GnssGeometry.blockageInflation(200.0) > GnssGeometry.blockageInflation(160.0))
        // Capped rather than extrapolated past the point the ramp was argued for.
        assertEquals(
            GnssGeometry.blockageInflation(200.0),
            GnssGeometry.blockageInflation(359.0),
            1e-6f,
        )
        // Never narrows, whatever it is handed.
        assertTrue(GnssGeometry.blockageInflation(-40.0) >= 1f)
    }

    @Test
    fun `a satellite tracked on two bands is one satellite, not two`() {
        // `GnssStatus` enumerates *signals*. A dual-band satellite appears twice, with the same
        // azimuth and elevation and a different carrier frequency — and counting those as two
        // satellites was wrong in three ways at once, every one of which got *worse* on better
        // hardware. Four satellites, three of them on L1 and L5.
        val signals = listOf(
            signal(1, 0f, 70f, 44f, L1), signal(1, 0f, 70f, 40f, L5),
            signal(2, 90f, 40f, 42f, L1), signal(2, 90f, 40f, 38f, L5),
            signal(3, 190f, 55f, 41f, L1), signal(3, 190f, 55f, 37f, L5),
            signal(4, 280f, 25f, 39f, L1),
        )
        val summary = GnssGeometry.summarise(signals)
        assertEquals(4, summary.satellites.size, "seven signals from four satellites")
        assertEquals(3, summary.dualBandSatellites, "three of them carry both bands")
        assertTrue(summary.anyCarrierFrequencyReported)
    }

    @Test
    fun `duplicated satellites used to flatter the geometry, and no longer can`() {
        // The defect that mattered most, because it silently *reduced* the geometry penalty. Feeding
        // the same line of sight twice conditions the normal matrix better than the sky actually is,
        // so HDOP came out lower than the truth — and lower by more the more dual-band satellites the
        // phone was tracking, which is to say most on the best hardware.
        val distinct = sky(0f to 70f, 90f to 40f, 190f to 55f, 280f to 25f)
        val honest = requireNotNull(horizontal(distinct))
        val flattered = requireNotNull(horizontal(distinct + distinct.take(3)))
        assertTrue(
            flattered < honest,
            "the control: duplicates really do flatter the geometry ($flattered vs $honest)",
        )
        // And the summary is what the provider now feeds it, so the duplicates never arrive.
        val summarised = GnssGeometry.summarise(
            distinct.mapIndexed { index, position -> signal(index, position.azimuthDegrees, position.elevationDegrees, 42f, L1) } +
                distinct.take(3).mapIndexed { index, position -> signal(index, position.azimuthDegrees, position.elevationDegrees, 38f, L5) },
        )
        assertEquals(honest, requireNotNull(horizontal(summarised.satellites)), 1e-9)
    }

    @Test
    fun `four second-band signals are not a dual-frequency solution`() {
        // What the gate used to accept. Ionospheric delay is removed by comparing *one satellite's*
        // range on two frequencies, so four L5 signals from satellites whose L1 is not in the fix buy
        // exactly none of it — and the gate that exists to wait for that correction was satisfied.
        val fourL5FromDifferentSatellites = listOf(
            signal(1, 0f, 70f, 40f, L5), signal(2, 90f, 40f, 38f, L5),
            signal(3, 190f, 55f, 37f, L5), signal(4, 280f, 25f, 36f, L5),
            signal(5, 45f, 60f, 44f, L1), signal(6, 135f, 35f, 43f, L1),
        )
        val summary = GnssGeometry.summarise(fourL5FromDifferentSatellites)
        assertEquals(0, summary.dualBandSatellites, "no satellite here is tracked on both bands")
        assertEquals(6, summary.satellites.size)
        assertFalse(
            GnssConvergence.secondBandReady(
                anyCarrierFrequencyReported = summary.anyCarrierFrequencyReported,
                dualBandSatellitesInFix = summary.dualBandSatellites,
                requiredSatellites = 4,
            ),
        )
    }

    @Test
    fun `the mean carrier-to-noise is taken per satellite, not per signal`() {
        // An L5 signal is a few dB weaker than its own L1, so averaging over signals dragged the mean
        // down for the dual-band satellites that are the better ones to be tracking — penalising a
        // receiver for doing the right thing.
        val summary = GnssGeometry.summarise(
            listOf(signal(1, 0f, 70f, 44f, L1), signal(1, 0f, 70f, 30f, L5)),
        )
        assertEquals(44f, summary.meanCn0DbHz, 0.01f, "the satellite's best signal, not the average")
        // A device reporting no usable carrier-to-noise at all reports none, rather than zero-padding.
        assertEquals(0f, GnssGeometry.summarise(listOf(signal(1, 0f, 70f, 0f, L1))).meanCn0DbHz)
        assertEquals(0, GnssGeometry.summarise(emptyList()).satellites.size)
    }

    private fun signal(key: Int, azimuth: Float, elevation: Float, cn0: Float, frequency: Float?) =
        GnssGeometry.SignalReport(
            satelliteKey = key,
            sky = GnssGeometry.SkyPosition(azimuth, elevation),
            cn0DbHz = cn0,
            carrierFrequencyHz = frequency,
        )

    private fun horizontal(sky: List<GnssGeometry.SkyPosition>) = GnssGeometry.horizontalDop(sky)

    private companion object {
        /** GPS L1 / Galileo E1, and GPS L5 / Galileo E5a — the two brackets the module splits on. */
        const val L1 = 1_575_420_000f
        const val L5 = 1_176_450_000f
    }
}

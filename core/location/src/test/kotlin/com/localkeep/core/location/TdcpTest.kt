package com.localkeep.core.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The carrier-phase solver, tested by giving it a displacement it is supposed to find again.
 *
 * This is the strongest verification available for [Tdcp] without a rail and a tape measure: the
 * observations are *synthesised from* a known displacement using the model the solver inverts, so a
 * sign error, a transposed axis or a misplaced clock column cannot survive. What it does not prove
 * is that a real chipset's `getAccumulatedDeltaRangeMeters` means what the documentation says on any
 * particular device — that needs the availability census and a physical one-metre slide, and neither
 * has been done. Until they have, the on-device path is guarded by the gates asserted below and
 * falls back to [StillnessMonitor] whenever any of them refuses.
 */
class TdcpTest {

    /** A spread of satellites at eight bearings and eight elevations, as an open sky looks. */
    private val constellation = listOf(
        0f to 70f, 45f to 30f, 100f to 55f, 160f to 25f,
        215f to 45f, 270f to 35f, 310f to 60f, 20f to 20f,
    ).mapIndexed { index, (azimuth, elevation) ->
        index to GnssGeometry.SkyPosition(azimuthDegrees = azimuth, elevationDegrees = elevation)
    }

    /**
     * Build the pair of epochs a receiver would produce if it moved by exactly this much.
     *
     * `ΔADR = −e · Δx + c·Δt`, with an arbitrary starting phase per satellite so the test also
     * proves the ambiguity really does cancel in the difference rather than being conveniently zero.
     */
    private fun epochs(
        east: Double,
        north: Double,
        up: Double,
        clockMeters: Double = 12.5,
        corrupt: Map<Int, Double> = emptyMap(),
        satellites: Int = constellation.size,
    ): Pair<List<Tdcp.Observation>, List<Tdcp.Observation>> {
        val before = ArrayList<Tdcp.Observation>()
        val after = ArrayList<Tdcp.Observation>()
        constellation.take(satellites).forEach { (key, sky) ->
            val row = GnssGeometry.designRow(sky)
            val delta = row[0] * east + row[1] * north + row[2] * up + row[3] * clockMeters
            val start = 20_000_000.0 + key * 137.0
            before += Tdcp.Observation(key, start, sky)
            after += Tdcp.Observation(key, start + delta + (corrupt[key] ?: 0.0), sky)
        }
        return before to after
    }

    @Test
    fun `a known displacement is recovered from the phase difference`() {
        val (before, after) = epochs(east = 0.7, north = -0.4, up = 0.1)
        val moved = assertNotNull(Tdcp.displacementBetween(before, after))
        assertEquals(0.7, moved.eastMeters, 1e-6)
        assertEquals(-0.4, moved.northMeters, 1e-6)
        assertEquals(0.1, moved.upMeters, 1e-6)
        assertEquals(0.0, moved.residualMeters, 1e-9)
        assertEquals(8, moved.satellites)
    }

    @Test
    fun `the receiver clock is solved for rather than assumed away`() {
        // Clock drift enters every observation identically and is far larger than the motion — tens
        // of metres against tenths. A solver that ignored it would read the whole clock term as
        // vertical displacement, which is the classic way to get this wrong and look plausible.
        val still = Tdcp.displacementBetween(
            epochs(east = 0.0, north = 0.0, up = 0.0, clockMeters = 43.9).first,
            epochs(east = 0.0, north = 0.0, up = 0.0, clockMeters = 43.9).second,
        )
        val moved = assertNotNull(still)
        assertEquals(0.0, moved.horizontalMeters, 1e-6)
        assertEquals(0.0, moved.upMeters, 1e-6)
    }

    @Test
    fun `gross corruption is refused outright`() {
        // A satellite whose phase counter reset without setting the flag that says so. Several
        // wavelengths of error cannot be explained by any displacement, so it shows up in the
        // residual and the whole epoch is discarded rather than partly believed.
        val (before, after) = epochs(east = 0.7, north = -0.4, up = 0.1, corrupt = mapOf(3 to 2.0))
        assertNull(Tdcp.displacementBetween(before, after))
    }

    @Test
    fun `a single undetected cycle slip is not caught, and this is what it costs`() {
        // The measured limit of the residual gate, recorded rather than glossed. One 19 cm
        // wavelength on one satellite of eight fits well enough to pass — 3.6 cm of residual against
        // a 5 cm bar — so the epoch is accepted. What that buys the attacker is 8 cm of error, an
        // order of magnitude below the metre-level scatter this whole pipeline is fighting.
        //
        // The gate is not tightened to catch it: at that point clean epochs from a noisy consumer
        // receiver start being rejected, and the trade is a real loss for an imaginary gain. The
        // per-satellite state flags are the first line of defence; this is the second.
        val (before, after) = epochs(east = 0.7, north = -0.4, up = 0.1, corrupt = mapOf(3 to 0.19))
        val moved = assertNotNull(
            Tdcp.displacementBetween(before, after),
            "if this now returns null the residual bar moved; check clean epochs still pass",
        )
        val error = Math.hypot(moved.eastMeters - 0.7, moved.northMeters - (-0.4))
        assertTrue(error < 0.15, "one slipped cycle moved the answer by $error m")
        assertTrue(error > 0.01, "if this is zero the fixture is not corrupting anything")
    }

    @Test
    fun `fewer than four common satellites is not a solution`() {
        val (before, after) = epochs(east = 0.5, north = 0.5, up = 0.0, satellites = 3)
        assertNull(Tdcp.displacementBetween(before, after))
        // And satellites present in only one of the two epochs cannot be differenced at all, so a
        // constellation that turned over completely between epochs is refused rather than joined
        // against the wrong satellites.
        val (justEnough, _) = epochs(east = 0.5, north = 0.5, up = 0.0, satellites = 4)
        val (_, disjoint) = epochs(east = 0.5, north = 0.5, up = 0.0)
        assertNotNull(Tdcp.displacementBetween(justEnough, disjoint.take(4)))
        assertNull(Tdcp.displacementBetween(justEnough, disjoint.drop(4)))
    }

    @Test
    fun `an epoch nobody could have produced by standing still is refused`() {
        // Two metres in one second is a run. The motion path owns that case — it clears the pool —
        // and the alignment must never quietly absorb it into a correction, which would turn a walk
        // into a position that looks converged.
        val (before, after) = epochs(east = 3.0, north = 2.0, up = 0.0)
        assertNull(Tdcp.displacementBetween(before, after))
    }

    @Test
    fun `alignment gives up rather than clamping once the drift stops being drift`() {
        assertTrue(Tdcp.alignmentStillApplies(0.0))
        assertTrue(Tdcp.alignmentStillApplies(2.5))
        assertFalse(Tdcp.alignmentStillApplies(9.0))
    }

    @Test
    fun `a satellite on the horizon is dropped before it can distort the geometry`() {
        // Elevation at or below the usable floor is what a device reports when it does not measure
        // elevation at all, and a row built from it points nowhere useful. Dropping it here means
        // the count falls below four and the epoch is refused, rather than a solve that is
        // confidently wrong.
        val sky = constellation.take(4).map { (key, position) ->
            key to GnssGeometry.SkyPosition(position.azimuthDegrees, elevationDegrees = 0f)
        }
        val before = sky.map { (key, position) -> Tdcp.Observation(key, 1000.0, position) }
        val after = sky.map { (key, position) -> Tdcp.Observation(key, 1000.5, position) }
        assertNull(Tdcp.displacementBetween(before, after))
    }
}

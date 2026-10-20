package com.localkeep.feature.itemedit

import androidx.compose.ui.unit.dp
import com.localkeep.core.location.CaptureConvergence
import com.localkeep.core.model.Coordinate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The field's phase is a pure function of three flags a Compose test in this module cannot easily
 * drive together — see `CoordinateFormatTest`'s note on why that test class holds only pure logic.
 */
class LocationCaptureFieldTest {

    @Test
    fun `nothing captured and nothing running is idle`() {
        assertEquals(
            RadarPhase.IDLE,
            radarPhaseFor(capturing = false, hasFix = false, convergence = CaptureConvergence.SEARCHING),
        )
    }

    @Test
    fun `a capture with no reading yet is searching, regardless of a stale convergence value`() {
        // Convergence resets to SEARCHING between captures, but a superseded value lingering here
        // must not be read as a settle that never happened.
        assertEquals(
            RadarPhase.SEARCHING,
            radarPhaseFor(capturing = true, hasFix = false, convergence = CaptureConvergence.SETTLED),
        )
    }

    @Test
    fun `a reading that has landed but is still falling is refining`() {
        assertEquals(
            RadarPhase.REFINING,
            radarPhaseFor(capturing = true, hasFix = true, convergence = CaptureConvergence.IMPROVING),
        )
    }

    @Test
    fun `a reading that has stopped improving is settled, while the capture is still open`() {
        assertEquals(
            RadarPhase.SETTLED,
            radarPhaseFor(capturing = true, hasFix = true, convergence = CaptureConvergence.SETTLED),
        )
    }

    @Test
    fun `once the capture ends with a reading, the field reads as captured no matter what convergence says`() {
        assertEquals(
            RadarPhase.CAPTURED,
            radarPhaseFor(capturing = false, hasFix = true, convergence = CaptureConvergence.SEARCHING),
        )
    }

    // --- The cloud is the accuracy, not the phase ------------------------------------------------

    @Test
    fun `two readings of different accuracy draw different clouds while refining`() {
        // Before 2026-09-02 both of these were 46dp: a 90 m and an 11 m reading drew the same
        // picture for as long as both were improving, so the panel's motion said nothing about how
        // close the reading was. The principal read it as decoration, which is what it was.
        val loose = cloudSpreadFor(RadarPhase.REFINING, accuracyMeters = 90f)
        val tight = cloudSpreadFor(RadarPhase.REFINING, accuracyMeters = 11f)
        assertTrue(loose > tight, "90 m drew $loose, 11 m drew $tight")
    }

    @Test
    fun `the cloud shrinks monotonically as the accuracy falls`() {
        val series = listOf(100f, 60f, 40f, 25f, 15f, 10f, 8f, 5f, 3f)
        val spreads = series.map { cloudSpreadFor(RadarPhase.REFINING, it) }
        spreads.zipWithNext().forEachIndexed { i, (wider, narrower) ->
            assertTrue(wider >= narrower, "${series[i]} m → $wider then ${series[i + 1]} m → $narrower")
        }
    }

    @Test
    fun `the cloud's two poles are the capture's own target and ceiling`() {
        // 3 m is FixRequest.targetAccuracyMeters (good enough to stop), 100 m is maxAccuracyMeters
        // (the worst this feature will call a coordinate). Anything past either pole clamps.
        val tight = cloudSpreadFor(RadarPhase.SETTLED, 3f)
        val wide = cloudSpreadFor(RadarPhase.REFINING, 100f)
        assertEquals(tight, cloudSpreadFor(RadarPhase.SETTLED, 0.5f))
        assertEquals(wide, cloudSpreadFor(RadarPhase.REFINING, 400f))
        assertEquals(wide, cloudSpreadFor(RadarPhase.SEARCHING, null), "searching has nothing to draw but the wide pole")
        assertTrue(wide > tight)
    }

    @Test
    fun `idle draws nothing and a reading without an accuracy falls back to a constant`() {
        assertEquals(0.dp, cloudSpreadFor(RadarPhase.IDLE, 5f))
        // No radius invented for a fix that reported none: the old per-phase constants survive
        // for exactly this case.
        assertEquals(cloudSpreadFor(RadarPhase.REFINING, null), cloudSpreadFor(RadarPhase.REFINING, Float.NaN))
        assertEquals(cloudSpreadFor(RadarPhase.CAPTURED, null), cloudSpreadFor(RadarPhase.SETTLED, null))
    }

    // --- What the live line says ------------------------------------------------------------------

    private fun fix(accuracy: Float?) = Coordinate(
        latitude = 42.1351,
        longitude = 24.7451,
        accuracyMeters = accuracy,
        capturedAt = Instant.EPOCH,
    )

    @Test
    fun `no reading is searching`() {
        assertEquals(CaptureReadout.Searching, captureReadoutFor(null, CaptureConvergence.SEARCHING))
    }

    @Test
    fun `a reading that landed before any satellite answered is network-only, not improving`() {
        // The case the phase model could not express: a wifi/cell position is in the form and the
        // old live line said "Still getting closer…" over it. It is not a satellite reading and no
        // metre figure may be printed for it.
        assertEquals(CaptureReadout.NetworkOnly, captureReadoutFor(fix(40f), CaptureConvergence.SEARCHING))
    }

    @Test
    fun `an improving satellite reading carries its band, not a figure`() {
        // 37 m used to arrive here as Improving(40) — a rounded metre figure the live line printed.
        // It is a band now and nothing else; CoordinateFormat.AccuracyBand carries the reasoning.
        assertEquals(
            CaptureReadout.Improving(AccuracyBand.STREET),
            captureReadoutFor(fix(37f), CaptureConvergence.IMPROVING),
        )
        assertEquals(
            CaptureReadout.Improving(AccuracyBand.AREA),
            captureReadoutFor(fix(80f), CaptureConvergence.IMPROVING),
        )
        // An unknown error is not a small one: no band is guessed for a fix that reported none.
        assertEquals(
            CaptureReadout.Improving(null),
            captureReadoutFor(fix(null), CaptureConvergence.IMPROVING),
        )
    }

    @Test
    fun `a settled reading carries its band and reads as finished`() {
        assertEquals(
            CaptureReadout.Settled(AccuracyBand.PRECISE),
            captureReadoutFor(fix(7.6f), CaptureConvergence.SETTLED),
        )
        assertEquals(
            CaptureReadout.Settled(AccuracyBand.STREET),
            captureReadoutFor(fix(20f), CaptureConvergence.SETTLED),
        )
    }

    @Test
    fun `the readout says nothing about accuracy before satellites answer`() {
        // NetworkOnly carries no band for the same reason it never carried a figure: a wifi/cell
        // estimate describes a database's confidence in an access point, not the phone's distance
        // from it, so calling it "street level" would be a claim the reading cannot support.
        val networkOnly = captureReadoutFor(fix(20f), CaptureConvergence.SEARCHING)
        assertEquals(CaptureReadout.NetworkOnly, networkOnly)
    }

    @Test
    fun `a fix carried over from a stopped capture with no reading at all falls back to idle`() {
        // Not reachable from the real ViewModel today (a stop with no fix clears hasFix too), but
        // the function must still answer something sane rather than fall through a `when`.
        assertEquals(
            RadarPhase.IDLE,
            radarPhaseFor(capturing = false, hasFix = false, convergence = CaptureConvergence.IMPROVING),
        )
    }
}

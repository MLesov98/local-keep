package com.localkeep.feature.itemedit

import com.localkeep.core.location.CaptureConvergence
import com.localkeep.core.model.Coordinate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When "Use this reading" is allowed to appear.
 *
 * Reported 2026-08-20: *"'Use this reading' is in yellow but it never actually says that it's not
 * ready and the button immediately appears."* It did — the label flipped on the first satellite
 * epoch, offering a reading nobody would keep.
 */
class CoordinateOfferTest {

    private fun fix(accuracy: Float?) = Coordinate(
        latitude = 42.1351,
        longitude = 24.7451,
        accuracyMeters = accuracy,
        capturedAt = java.time.Instant.EPOCH,
    )

    @Test
    fun `nothing is offered before a fix exists`() {
        assertFalse(readingIsOfferable(null, CaptureConvergence.IMPROVING, trackingFloorPassed = true))
    }

    @Test
    fun `a first rough epoch is not an offer`() {
        // The whole of 5.5: a 40m reading two seconds in is not something to keep, and the button
        // used to say "Use this reading" over exactly that.
        assertFalse(readingIsOfferable(fix(40f), CaptureConvergence.IMPROVING, trackingFloorPassed = false))
    }

    @Test
    fun `a close reading is offerable immediately`() {
        assertTrue(readingIsOfferable(fix(5f), CaptureConvergence.IMPROVING, trackingFloorPassed = false))
    }

    @Test
    fun `a settled series is offerable whatever the band`() {
        assertTrue(readingIsOfferable(fix(40f), CaptureConvergence.SETTLED, trackingFloorPassed = false))
    }

    @Test
    fun `past the tracking floor, a rough reading becomes offerable`() {
        // The ordinary indoor finish: the policy would now take its own early exit, so offering the
        // user the same decision is no longer offering one the capture is unwilling to make.
        assertTrue(readingIsOfferable(fix(40f), CaptureConvergence.IMPROVING, trackingFloorPassed = true))
    }

    @Test
    fun `the tracking floor is the shipped FixRequest value, not a number chosen here`() {
        assertTrue(MIN_TRACKING_MILLIS > 0, "a floor of zero would make the gate a no-op")
        assertTrue(MIN_TRACKING_MILLIS == com.localkeep.core.location.FixRequest().minTrackingMillis)
    }
}

package com.localkeep.core.location

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The combiner works on a cluster that straddles the antimeridian.
 *
 * Red before the fix. `project` took a plain `fix.longitude - best.longitude`, which reads two
 * samples a few metres apart across 180° — 179.9999 and −179.9999 — as ~360 degrees of separation,
 * about 40,000 km once multiplied by metres-per-degree. Every sample but the tightest then fell
 * outside the admission radius, the cluster collapsed to one, and `combineFixes` silently returned
 * `best` alone: no combination, no averaging, and nothing anywhere saying so.
 *
 * The file's own comment claimed the opposite — "no pole or meridian case to reason about — the
 * cluster spans metres by construction". That is true of the cluster and false of the arithmetic
 * that finds it, which is exactly the kind of comment worth testing rather than believing.
 *
 * Longitude 180 is not an exotic input for a password manager that stores places: it runs through
 * Fiji, Kiribati and the Chukotka coast, and Kiribati straddles it on purpose.
 */
class FixCombinerAntimeridianTest {

    @Test
    fun `samples either side of 180 degrees still combine`() {
        val samples = straddlingCluster()
        val best = samples.minByOrNull { it.accuracyMeters }!!

        val combined = assertNotNull(
            combineFixes(samples = samples, maxRadiusMeters = 50.0),
            "the combiner returned nothing for a perfectly ordinary tight cluster",
        )

        // The fixture is deliberately lopsided: the TIGHTEST sample is the lone one east of 180,
        // and the other three sit a couple of metres west of it. Under the naive subtraction those
        // three project ~38,000 km away, the admission radius keeps none of them, the cluster
        // collapses to one, and `combineFixes` returns `best` itself — which is what this asserts
        // against. A symmetric fixture would NOT catch it: the combiner would still find two
        // same-side samples to average and hand back a fresh object that merely ignored half the
        // readings.
        assertNotSame(
            best, combined,
            "combineFixes degraded to the single tightest sample: the three readings across the " +
                "antimeridian were projected tens of thousands of km away and rejected",
        )
        assertTrue(
            combined.longitude in -180.0..180.0,
            "combined longitude ${combined.longitude} is outside the valid range",
        )
        // Three of the four readings are west of 180, so the combined point belongs there too.
        // Under the bug the answer sat on the eastern side, at `best` alone.
        assertTrue(
            combined.longitude < -179.99,
            "combined longitude ${combined.longitude} sits on the wrong side of the antimeridian " +
                "— the western readings were dropped",
        )
    }

    /**
     * The control, and it must stay green while the test above goes red.
     *
     * Wrapping the delta must not disturb the ordinary case. If a naive `> 180` guard were written
     * the wrong way round it would corrupt every cluster on the planet while making the
     * antimeridian one pass, so an away-from-the-wrap cluster is asserted to still combine.
     */
    @Test
    fun `an ordinary cluster far from the wrap is unaffected`() {
        val samples = listOf(
            fixAt(latitude = 42.0, longitude = 12.0, accuracy = 5f),
            fixAt(latitude = 42.00002, longitude = 12.00002, accuracy = 6f),
            fixAt(latitude = 41.99998, longitude = 11.99998, accuracy = 7f),
        )

        val combined = assertNotNull(combineFixes(samples, maxRadiusMeters = 50.0))

        assertTrue(
            combined.longitude in 11.9999..12.0001 && combined.latitude in 41.9999..42.0001,
            "an ordinary cluster moved: ${combined.latitude}, ${combined.longitude}",
        )
    }

    /**
     * Lopsided on purpose — see the assertion above. One tight reading east of 180, three looser
     * ones a couple of metres west of it. Spacing is ~2m, well inside the 7.5m admission radius
     * that a 5m best accuracy produces, so every sample belongs in the cluster on the merits.
     */
    private fun straddlingCluster() = listOf(
        fixAt(latitude = -16.5, longitude = 179.999995, accuracy = 5f),
        fixAt(latitude = -16.5, longitude = -179.999985, accuracy = 6f),
        fixAt(latitude = -16.5, longitude = -179.999975, accuracy = 7f),
        fixAt(latitude = -16.5, longitude = -179.999965, accuracy = 8f),
    )

    private fun fixAt(
        latitude: Double,
        longitude: Double,
        accuracy: Float,
    ) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        capturedAt = Instant.EPOCH,
        accuracyMeters = accuracy,
        elapsedRealtimeNanos = 1_000_000_000L,
        weight = 1f,
    )
}

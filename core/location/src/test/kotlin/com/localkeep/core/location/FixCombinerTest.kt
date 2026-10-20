package com.localkeep.core.location

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The estimator, tested where it lives.
 *
 * Seven of these cases used to sit inside `LocationFixPolicyTest`, which made
 * `LocationFixPolicy`'s own claim to be "the only part of the capture with real unit tests" quietly
 * false and left anyone looking for the combiner's coverage reading a file named for something else.
 * The move is the whole of that fix; the cases themselves are the originals, changed only where the
 * behaviour they pin genuinely changed.
 *
 * Everything here is pure JVM. `combineFixes`, `distanceMeters` and [EpochQuality.weightFor] take
 * only lists, doubles and floats — no Android type appears in any signature — which is the same
 * property that let the policy be tested at all, and none of these cases is reachable by driving a
 * device: no test can ask a real receiver for a cluster with one reflection in it.
 */
class FixCombinerTest {

    // ---------------------------------------------------------------------------------------
    // The admission gate and the two estimators
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the admission radius scales with the best fix instead of sitting at eight metres`() {
        // A 3 m best admits neighbours to 4.5 m, not to 8 m. The old fixed gate was several times
        // the precision being aimed at, so a sample near its boundary on the far side of the truth
        // moved the mean by roughly a metre and a half — the gate admitting the very error the
        // averaging was there to remove.
        val best = fixAt(accuracy = 3f, northMeters = 0.0)
        val distant = fixAt(accuracy = 4f, northMeters = 6.0)
        val averaged = combineFixes(listOf(best, distant), maxRadiusMeters = 8.0)
        assertSame(best, averaged, "6 m is outside a 3 m fix's 4.5 m radius and must not be averaged in")

        // The same pair with a poorer best, where 6 m genuinely is within the scatter the receiver
        // is admitting to, is averaged — the gate is relative, not merely tighter.
        val looseBest = fixAt(accuracy = 8f, northMeters = 0.0)
        val nearby = fixAt(accuracy = 9f, northMeters = 6.0)
        val merged = requireNotNull(combineFixes(listOf(looseBest, nearby), maxRadiusMeters = 8.0))
        assertEquals(3.0, metersNorthOf(merged), 0.05, "the mean of 0 m and 6 m is 3 m")
    }

    @Test
    fun `the ceiling still binds when the receiver is wildly optimistic about a loose fix`() {
        // A 90 m fix would otherwise admit neighbours out to 135 m. The ceiling is what keeps a
        // "cluster" from spanning a neighbourhood.
        val best = fixAt(accuracy = 90f, northMeters = 0.0)
        val far = fixAt(accuracy = 95f, northMeters = 20.0)
        assertSame(best, combineFixes(listOf(best, far), maxRadiusMeters = 8.0))
    }

    @Test
    fun `one reflected sample cannot drag the result the way it drags a mean`() {
        // The reason the combination is a geometric median and not an average. A mean is moved by an
        // outlier at distance D by exactly D/n — with five samples and one 5.5 m multipath reading,
        // more than a metre of pure error with no way for the mean to notice. The median needs half
        // the set to be wrong before it moves anywhere.
        val tight = listOf(0.0, 0.2, -0.2, 0.1).map { fixAt(accuracy = 4f, northMeters = it) }
        val reflected = fixAt(accuracy = 4f, northMeters = 5.5)
        val combined = requireNotNull(combineFixes(tight + reflected, maxRadiusMeters = 8.0))

        val mean = (tight.map { metersNorthOf(it) } + 5.5).average()
        assertTrue(mean > 1.0, "the mean of this set really is dragged, or the test proves nothing")
        assertTrue(
            metersNorthOf(combined) < 0.5,
            "the combined position sat ${metersNorthOf(combined)} m north; the tight cluster is at 0",
        )
        // The claim still widens to cover the sample that disagreed. Resisting an outlier when
        // choosing the position is not the same as pretending it was not seen.
        assertTrue(combined.accuracyMeters >= 4f)
    }

    @Test
    fun `identical readings do not break the median solver`() {
        // The classical Weiszfeld degeneracy: the iterate lands exactly on a sample point and the
        // update divides by zero. Six readings at the same place is not a contrived input — it is
        // what a good receiver standing still produces at the resolution of a double.
        val same = List(6) { fixAt(accuracy = 3f, northMeters = 0.0) }
        val combined = requireNotNull(combineFixes(same, maxRadiusMeters = 8.0))
        assertEquals(0.0, metersNorthOf(combined), 0.001)
        assertTrue(combined.accuracyMeters.isFinite())
        assertEquals(3f, combined.accuracyMeters, 0.001f)
    }

    @Test
    fun `the solver always terminates somewhere inside the cluster it was given`() {
        // The second documented degeneracy: running out of iterations. A genuine 64-step exhaustion
        // is not constructible at these cluster sizes — Weiszfeld converges linearly and a dozen
        // points a few metres apart are inside the epsilon in under fifteen steps — so what is
        // asserted is the property the iteration cap exists to guarantee rather than a staged
        // failure of it. Two opposed clumps of equal weight is the slowest configuration there is,
        // and every point of it is a place the answer is allowed to be.
        val opposed = List(3) { fixAt(accuracy = 4f, northMeters = -5.5) } +
            List(3) { fixAt(accuracy = 4f, northMeters = 5.5) }
        val combined = requireNotNull(combineFixes(opposed, maxRadiusMeters = 8.0))
        assertTrue(combined.accuracyMeters.isFinite())
        assertTrue(
            metersNorthOf(combined) in -5.5..5.5,
            "the answer left the hull of its own samples: ${metersNorthOf(combined)} m",
        )
    }

    @Test
    fun `the estimator is blended across the boundary instead of switched at it`() {
        // Four samples used to be a mean and five a geometric median, so a capture that happened to
        // admit a fifth sample changed *estimator* and could return a position metres away for a
        // reason that was purely about the count — and the count is decided by an admission gate,
        // not by the ground. The blend leaves both ends exactly where they were and halves the jump.
        val four = List(3) { fixAt(accuracy = 4f, northMeters = 0.0) } +
            fixAt(accuracy = 4f, northMeters = 5.0)
        val five = List(4) { fixAt(accuracy = 4f, northMeters = 0.0) } +
            fixAt(accuracy = 4f, northMeters = 5.0)
        val atFour = metersNorthOf(requireNotNull(combineFixes(four, maxRadiusMeters = 8.0)))
        val atFive = metersNorthOf(requireNotNull(combineFixes(five, maxRadiusMeters = 8.0)))

        // The control, and without it this test proves nothing: the *unblended* answers really are
        // far apart, so the number below is a reduction and not an accident of the fixture.
        val meanOfFour = four.map { metersNorthOf(it) }.average()
        assertTrue(meanOfFour > 1.2, "the four-sample mean really does sit ${meanOfFour} m north")
        assertTrue(
            kotlin.math.abs(atFive - atFour) < meanOfFour / 1.6,
            "one more sample moved the saved position by ${kotlin.math.abs(atFive - atFour)} m",
        )
        // And the ends are untouched: five or more is still a pure geometric median.
        assertEquals(0.0, atFive, 0.05)
    }

    // ---------------------------------------------------------------------------------------
    // The reported accuracy
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the reported accuracy describes the average, not the best sample`() {
        // Three samples 7 m north of the best one pull the result away from it. Reporting the best
        // sample's own figure beside that position would be describing a point estimate while
        // returning a combination of several — two different quantities, and the smaller of them.
        val samples = listOf(fixAt(accuracy = 5f, northMeters = 0.0)) +
            List(3) { fixAt(accuracy = 5f, northMeters = 7.0) }
        val averaged = requireNotNull(combineFixes(samples, maxRadiusMeters = 8.0))
        // Halfway between the mean at 5.25 m and the median at 7 m, because four samples sit in the
        // middle of the blend. This assertion read 5.25 while four samples were a pure mean.
        assertEquals(6.125, metersNorthOf(averaged), 0.05)

        // Where the cluster genuinely disagrees by more than the receiver claimed, the claim widens.
        // Seven samples spanning eleven metres: the dispersion is the honest figure and it is larger
        // than any single sample's own.
        val spread = listOf(fixAt(accuracy = 4f, northMeters = 0.0)) +
            List(3) { fixAt(accuracy = 4.5f, northMeters = 5.5) } +
            List(3) { fixAt(accuracy = 4.5f, northMeters = -5.5) }
        val widened = requireNotNull(combineFixes(spread, maxRadiusMeters = 8.0))
        assertTrue(
            widened.accuracyMeters > 5f,
            "samples that disagree by more than the best one claimed must widen the claim, not " +
                "leave it at the tightest number in the set; got ${widened.accuracyMeters}",
        )
    }

    @Test
    fun `agreeing samples keep the best fix's own accuracy`() {
        // The other direction, and the reason the rule is a max rather than a replacement: when the
        // cluster is tight the dispersion is smaller than the receiver's own estimate, and the app
        // has no evidence for claiming better than the receiver does. Averaging improves the
        // position and never improves the claim.
        val samples = listOf(
            fixAt(accuracy = 4f, northMeters = 0.0),
            fixAt(accuracy = 4.5f, northMeters = 1.0),
            fixAt(accuracy = 4.2f, northMeters = -1.0),
        )
        val averaged = requireNotNull(combineFixes(samples, maxRadiusMeters = 8.0))
        assertEquals(4f, averaged.accuracyMeters, 0.001f)
    }

    @Test
    fun `one admitted outlier no longer defines the error bar by itself`() {
        // The defect this replaced: the reported figure was the distance to the *furthest* sample, a
        // statistic with breakdown point zero. So the single multipath reading the geometric median
        // was chosen to ignore was setting the claim single-handedly — a robust estimate described
        // by a non-robust error bar. A 68% radius of the cluster is not moved by one member.
        val tight = List(11) { index ->
            fixAt(accuracy = 4f, northMeters = if (index % 2 == 0) 0.1 else -0.1)
        }
        val outlier = fixAt(accuracy = 4f, northMeters = 5.5)

        val clean = requireNotNull(combineFixes(tight, maxRadiusMeters = 8.0))
        val contaminated = requireNotNull(combineFixes(tight + outlier, maxRadiusMeters = 8.0))

        // The control: the outlier really is inside the admission radius, so it really is in the
        // cluster and really would have set the old figure.
        assertTrue(
            5.5 <= 4.0 * 1.5,
            "the fixture must admit the outlier or the old behaviour was never reproduced",
        )
        assertTrue(
            contaminated.accuracyMeters - clean.accuracyMeters < 0.5f,
            "one admitted outlier moved the claim from ${clean.accuracyMeters} to " +
                "${contaminated.accuracyMeters}; it used to move it to 5.5",
        )
    }

    @Test
    fun `a one-sided sky widens the claim and never moves the position`() {
        // The one input that comes from outside the cluster. Scatter between samples is evidence
        // about random error and blind to a systematic one: against a building every epoch is wrong
        // in the same direction, so the samples agree beautifully with each other and with nothing
        // else. This is where that detection is spent — on reporting worse, correctly.
        val samples = List(6) { index -> fixAt(accuracy = 4f, northMeters = index * 0.1) }
        val open = requireNotNull(combineFixes(samples, maxRadiusMeters = 8.0, biasInflation = 1f))
        val blocked = requireNotNull(
            combineFixes(
                samples = samples,
                maxRadiusMeters = 8.0,
                biasInflation = GnssGeometry.blockageInflation(220.0),
            ),
        )
        assertEquals(metersNorthOf(open), metersNorthOf(blocked), 1e-9, "the position must not move")
        assertTrue(
            blocked.accuracyMeters > open.accuracyMeters,
            "a bias averaging cannot remove has to widen the claim",
        )
        // Never narrows it, whatever it is handed.
        assertEquals(
            open.accuracyMeters,
            requireNotNull(combineFixes(samples, 8.0, biasInflation = 0.2f)).accuracyMeters,
            0.001f,
        )
    }

    // ---------------------------------------------------------------------------------------
    // The weights, and their journey into the combination
    // ---------------------------------------------------------------------------------------

    @Test
    fun `each thing that argues against a reading lowers its weight on its own`() {
        // One factor at a time against a clean baseline. Comparing a good epoch with a bad one that
        // differs on three axes would keep the ordering — and this test green — with any single
        // penalty deleted, which is a test of the sum rather than of the parts.
        val clean = EpochQuality.Epoch(meanCn0DbHz = 42f, usedInFixCount = 14, dualBandSatellites = 6)
        val baseline = EpochQuality.weightFor(4f, clean)

        assertTrue(
            EpochQuality.weightFor(4f, clean.copy(meanCn0DbHz = 24f)) < baseline,
            "weak signals are the ones most likely to be reflections",
        )
        assertTrue(
            EpochQuality.weightFor(4f, clean.copy(usedInFixCount = 5)) < baseline,
            "a solution near the minimum satellite count has no redundancy to notice a bad range",
        )
        assertTrue(
            EpochQuality.weightFor(4f, clean.copy(dualBandSatellites = 0)) < baseline,
            "single-frequency means ionospheric delay is modelled rather than measured",
        )
        assertTrue(
            EpochQuality.weightFor(4f, clean.copy(hasSpeed = true, speedMetersPerSecond = 1.4f)) < baseline,
            "a static capture reporting a walking speed is reporting a bad epoch",
        )
        assertTrue(
            EpochQuality.weightFor(4f, clean.copy(horizontalDop = 4.0)) < baseline,
            "a canyon's geometry multiplies the ranging error however clean the ranges are",
        )
        assertTrue(
            EpochQuality.weightFor(4f, clean.copy(lowElevationFraction = 1f)) < baseline,
            "a solution leaning on satellites at the horizon is worse than its count suggests",
        )
        // Speed is judged even with no satellite detail, because it comes from the fix rather than
        // from the constellation.
        assertTrue(
            EpochQuality.weightFor(4f, EpochQuality.Epoch(hasSpeed = true, speedMetersPerSecond = 1.4f)) <
                EpochQuality.weightFor(4f, EpochQuality.Epoch()),
        )
        // An absent speed is never read as zero, so it neither helps nor hurts.
        assertEquals(
            EpochQuality.weightFor(4f, clean),
            EpochQuality.weightFor(4f, clean.copy(hasSpeed = false, speedMetersPerSecond = 9f)),
            0.0001f,
        )
        // A device that reports no satellite detail at all is not penalised for it — it gets the
        // plain accuracy weight, which is what an unweighted combination would have used.
        assertEquals(1f / 16f, EpochQuality.weightFor(4f, EpochQuality.Epoch()), 0.0001f)
        assertEquals(1f / 16f, baseline, 0.0001f, "a clean epoch earns no bonus, only no penalty")
        // An unusable accuracy earns no vote rather than an infinite one.
        assertEquals(0f, EpochQuality.weightFor(0f, EpochQuality.Epoch()))
        assertEquals(0f, EpochQuality.weightFor(Float.NaN, EpochQuality.Epoch()))
    }

    @Test
    fun `the ordering holds across accuracies, which is the only place it could fail`() {
        // The property the coarse penalties exist to guarantee, and the one no test used to assert:
        // every `weightFor` case above holds the accuracy at 4 m and varies only the epoch, so a
        // scheme that inverted the ranking between a flattering bad epoch and an honest good one
        // would pass all of them. A 5 m claim from a weak, six-satellite, single-band solution is
        // exactly the optimism the inflation exists to correct, and it must rank below a clean 9 m.
        val flattering = EpochQuality.weightFor(
            5f,
            EpochQuality.Epoch(meanCn0DbHz = 24f, usedInFixCount = 5, dualBandSatellites = 0),
        )
        val honest = EpochQuality.weightFor(
            9f,
            EpochQuality.Epoch(meanCn0DbHz = 42f, usedInFixCount = 14, dualBandSatellites = 6),
        )
        assertTrue(honest > flattering, "the clean 9 m epoch must outrank the penalised 5 m one")
    }

    @Test
    fun `the weight is continuous across the boundary it used to jump at`() {
        // Two epochs seconds apart with a mean carrier-to-noise of 34.9 and 35.1 dB-Hz used to differ
        // in weight by 69% across a threshold at which nothing physical happens. In a marginal
        // environment, where the mean sits near that number and wanders across it, the stepped form
        // injected pure threshold-location noise exactly where the weighting was meant to help most.
        fun weightAt(cn0: Float) = EpochQuality.weightFor(
            4f,
            EpochQuality.Epoch(meanCn0DbHz = cn0, usedInFixCount = 14, dualBandSatellites = 6),
        )
        val below = weightAt(34.9f)
        val above = weightAt(35.1f)
        assertTrue(
            kotlin.math.abs(above - below) / weightAt(35f) < 0.05f,
            "two tenths of a decibel moved the weight by " +
                "${kotlin.math.abs(above - below) / weightAt(35f) * 100}%",
        )
        // The endpoints are unchanged, which is what makes this a ramp between two values the code
        // already committed to rather than a new claim about anything.
        assertEquals(1f / ((4f * 1.3f) * (4f * 1.3f)), weightAt(30f), 1e-6f)
        assertEquals(1f / 16f, weightAt(40f), 1e-6f)
    }

    @Test
    fun `geometry replaces the satellite count where the device reports it`() {
        // The inversion this fixes. Ten satellites all on one side of a street is one of the most
        // common environments a phone captures a position in, and by count alone it scores as the
        // *better* of the two epochs below. HDOP is the other multiplicative term in
        // `error ~ HDOP x ranging error`, and the count was standing in for it.
        val spreadOut = EpochQuality.Epoch(meanCn0DbHz = 42f, usedInFixCount = 6, dualBandSatellites = 6)
        val allOneSide = EpochQuality.Epoch(meanCn0DbHz = 42f, usedInFixCount = 10, dualBandSatellites = 6)

        // The control: by count, the canyon epoch wins. This is the behaviour being replaced.
        assertTrue(
            EpochQuality.weightFor(5f, allOneSide) > EpochQuality.weightFor(5f, spreadOut),
            "without geometry the count really does prefer the canyon, or this test proves nothing",
        )
        assertTrue(
            EpochQuality.weightFor(5f, spreadOut.copy(horizontalDop = 1.2)) >
                EpochQuality.weightFor(5f, allOneSide.copy(horizontalDop = 5.0)),
            "with geometry the six well-spread satellites must outrank the ten stacked ones",
        )
        // A device that reports no azimuths gets exactly what it got before geometry was read.
        assertEquals(
            EpochQuality.weightFor(5f, spreadOut),
            EpochQuality.weightFor(5f, spreadOut.copy(horizontalDop = null)),
            1e-9f,
        )
        // Inflation only: a textbook constellation is left alone rather than rewarded.
        assertEquals(
            EpochQuality.weightFor(5f, spreadOut.copy(horizontalDop = 1.2)),
            EpochQuality.weightFor(5f, spreadOut.copy(horizontalDop = 0.4)),
            1e-9f,
        )
    }

    @Test
    fun `the accelerometer overrules the fix's own idea of standing still`() {
        // `location.speed` is a Doppler estimate carrying 0.1-0.5 m/s of its own noise, so the half-
        // metre threshold sat *inside* the noise floor of the quantity it thresholded: a stationary
        // phone crosses it now and then and a slow shuffle never does. Where a real measurement of
        // motion exists it decides, in both directions.
        val walking = EpochQuality.Epoch(
            meanCn0DbHz = 42f,
            usedInFixCount = 14,
            dualBandSatellites = 6,
            hasSpeed = true,
            speedMetersPerSecond = 1.4f,
        )
        val still = walking.copy(hasSpeed = true, speedMetersPerSecond = 0f)

        assertEquals(
            EpochQuality.weightFor(4f, still),
            EpochQuality.weightFor(4f, walking.copy(isMoving = false)),
            1e-9f,
            "a noisy Doppler reading must not outvote an accelerometer that says the phone is still",
        )
        assertTrue(
            EpochQuality.weightFor(4f, still.copy(isMoving = true)) < EpochQuality.weightFor(4f, still),
            "and motion the Doppler estimate missed entirely must still be penalised",
        )
        // No accelerometer on the device leaves the old test in charge, unchanged.
        assertEquals(
            EpochQuality.weightFor(4f, walking),
            EpochQuality.weightFor(4f, walking.copy(isMoving = null)),
            1e-9f,
        )
    }

    @Test
    fun `a weight really reaches the combination, and a broken one does not drop its sample`() {
        // The wiring no test used to cross: every combination case ran at the default weight of 1,
        // so the whole weight path through `combineFixes` — including the fallback that exists so a
        // bad weight cannot silently drop a sample from one estimator and not the other — was
        // unexercised.
        // Same self-reported accuracy on both, so the admission radius treats them alike and the
        // only thing separating them is the weight the satellite evidence earned them.
        val light = fixAt(accuracy = 4f, northMeters = 0.0, weight = 0.0625f)
        val heavy = fixAt(accuracy = 4f, northMeters = 5.0, weight = 0.25f)
        val combined = requireNotNull(combineFixes(listOf(light, heavy), maxRadiusMeters = 8.0))
        // Four times the weight pulls the answer four fifths of the way across, against the 2.5 m
        // an unweighted midpoint would give.
        assertEquals(4.0, metersNorthOf(combined), 0.05)

        // A non-finite weight falls back to the accuracy-only weight rather than being dropped. Both
        // samples here carry the same accuracy, so the fallback reproduces the uniform case exactly.
        val uniform = listOf(
            fixAt(accuracy = 4f, northMeters = 0.0, weight = 0.0625f),
            fixAt(accuracy = 4f, northMeters = 5.0, weight = 0.0625f),
        )
        val broken = listOf(uniform[0], uniform[1].copy(weight = Float.NaN))
        val expected = metersNorthOf(requireNotNull(combineFixes(uniform, maxRadiusMeters = 8.0)))
        val actual = metersNorthOf(requireNotNull(combineFixes(broken, maxRadiusMeters = 8.0)))
        assertEquals(expected, actual, 0.001)
        // And the control that gives that assertion meaning: had the sample been dropped instead of
        // rescued, the answer would have been the surviving sample's own position.
        assertNotEquals(0.0, actual, "a dropped sample would have left the answer at 0 m")
    }

    @Test
    fun `a corroborated sample is averaged but never lends its identity to the result`() {
        // A fused reading that a satellite fix vouched for is worth averaging — it is a second
        // independent producer and roughly doubles what reaches the combination. What it must never
        // do is *become* the answer: if it happens to self-report the tightest number, the saved fix
        // would describe itself as the grade of a provider that may have answered from wifi, for a
        // position several satellite epochs contributed to.
        val satellite = fixAt(accuracy = 5f, northMeters = 0.0)
        val corroborated = fixAt(accuracy = 2f, northMeters = 1.0)
            .copy(grade = LocationFixPolicy.ProviderGrade.CORROBORATED)
        val combined = requireNotNull(
            combineFixes(listOf(satellite, corroborated), maxRadiusMeters = 8.0),
        )
        assertEquals(
            LocationFixPolicy.ProviderGrade.PRECISION,
            combined.grade,
            "the tightest sample was the corroborated one and it must not hand over its grade",
        )
        // It was still averaged: the answer is not sitting on the satellite fix alone.
        assertTrue(metersNorthOf(combined) > 0.05)
    }

    @Test
    fun `a pooled sample can be carried forward into a later epoch's frame`() {
        // What the carrier-phase alignment does to the pool. A sample taken before the receiver
        // drifted is a reading of where the user *was*; shifted by the measured displacement it
        // becomes a reading of where they are, which is what makes averaging it legitimate at all.
        val origin = fixAt(accuracy = 4f, northMeters = 0.0)
        val shifted = origin.shiftedBy(eastMeters = 3.0, northMeters = 4.0)
        assertEquals(5.0, distanceMeters(shifted, origin), 0.01)
        assertEquals(4.0, metersNorthOf(shifted), 0.01)
        // Everything that is not position is untouched — the accuracy it claimed and the instant it
        // was taken still describe the reading, not the correction.
        assertEquals(origin.accuracyMeters, shifted.accuracyMeters)
        assertEquals(origin.elapsedRealtimeNanos, shifted.elapsedRealtimeNanos)
        // A no-op shift and a garbage shift both leave the fix exactly alone.
        assertSame(origin, origin.shiftedBy(0.0, 0.0))
        assertSame(origin, origin.shiftedBy(Double.NaN, 1.0))
    }

    // ---------------------------------------------------------------------------------------
    // The distance function everything above stands on
    // ---------------------------------------------------------------------------------------

    @Test
    fun `distance scales longitude by the cosine of the latitude`() {
        // Every fixture in this file used to vary only latitude, so the one term of the
        // equirectangular approximation that can actually be wrong — the `cos(meanLat)` on the
        // longitude axis — was never asserted, and `distanceMeters` was never called directly at
        // all. At 42 degrees north a degree of longitude is about three quarters of a degree of
        // latitude, and getting that backwards would distort every cluster east-west.
        val origin = fixAt(accuracy = 4f, northMeters = 0.0)
        assertEquals(10.0, distanceMeters(fixAt(accuracy = 4f, eastMeters = 10.0), origin), 0.02)
        assertEquals(10.0, distanceMeters(fixAt(accuracy = 4f, northMeters = 10.0), origin), 0.02)
        assertEquals(
            0.0,
            distanceMeters(
                fixAt(accuracy = 4f, northMeters = 3.0, eastMeters = 4.0),
                origin,
            ) - 5.0,
            0.02,
        )

        // The control: the same *degree* offset is a shorter distance east than north, which is the
        // cosine term doing its job. Without it these two would be equal.
        val oneDegreeNorth = origin.copy(latitude = origin.latitude + 0.0001)
        val oneDegreeEast = origin.copy(longitude = origin.longitude + 0.0001)
        assertTrue(
            distanceMeters(oneDegreeEast, origin) < distanceMeters(oneDegreeNorth, origin) * 0.8,
            "the longitude axis is not being scaled by the latitude at all",
        )
    }

    /** A fix offset from a base point by whole metres, on either axis, with a settable weight. */
    // ---------------------------------------------------------------------------------------
    // The two terms a long occupation adds to the claim
    // ---------------------------------------------------------------------------------------

    /**
     * A pool of [count] readings drifting steadily [driftMeters] north across the window, centred
     * on the tightest reading rather than starting from it.
     *
     * The centring is not cosmetic, and getting it wrong is what this fixture was built wrong as
     * the first time. The admission radius is `best.accuracy × 1.5` measured *from the best sample*,
     * so a drift that starts at `best` and runs one way is capped at 1.5 × accuracy of total span,
     * which puts the spread between its thirds at about 1.0 × accuracy — permanently under the
     * floor the claim already has, and the block term could never bind. A converged capture's
     * tightest epoch sits somewhere inside the series, not at its first second, and then the span
     * reaches twice that and the statistic has something to say. The bound is worth knowing: this
     * term only speaks when the capture wandered by more than the receiver's own error bar.
     */
    private fun drifting(count: Int, driftMeters: Double): List<LocationFix> {
        val middle = count / 2
        return (0 until count).map { index ->
            val progress = index.toDouble() / (count - 1) - 0.5
            fixAt(
                // The tightest reading is the middle one, which is where a converging series puts
                // it, and which is what decides where the admission radius is centred.
                accuracy = if (index == middle) 3f else 3.2f,
                // A little scatter on top, so the drift is not the only thing in the series and the
                // dispersion statistic has something ordinary to measure.
                northMeters = progress * driftMeters + Math.sin(index * 2.1) * 0.15,
                eastMeters = Math.cos(index * 1.7) * 0.15,
            ).copy(elapsedRealtimeNanos = 1_000_000_000L + index * 1_000_000_000L)
        }
    }

    @Test
    fun `a slow drift is caught by the block spread that the dispersion statistic misses`() {
        // The case this term exists for. Thirty readings sliding eight metres north across the
        // window: every sample is inside the admission radius, they are all "the same place" as far
        // as the cluster is concerned, and the scatter about the centre is far smaller than the
        // distance between where the capture started and where it ended. A uniform spread over
        // ±4 m has an r68 near 2.5 m — comfortably under the 5.3 m the capture actually wandered,
        // so without this term the claim would be tighter than the movement inside it.
        val drift = drifting(count = 30, driftMeters = 8.0)
        val combined = requireNotNull(combineFixes(drift, maxRadiusMeters = 8.0))

        // The control, and the whole test rests on it: the same series with the drift taken out
        // reports materially tighter. If these two come back equal the block term is doing nothing.
        val stable = drifting(count = 30, driftMeters = 0.0)
        val flat = requireNotNull(combineFixes(stable, maxRadiusMeters = 8.0))
        assertTrue(
            combined.accuracyMeters > flat.accuracyMeters + 1f,
            "the drifting capture reported ${combined.accuracyMeters} m against the stable " +
                "capture's ${flat.accuracyMeters} m — the drift left no mark on the claim",
        )
        // The second control: it is the *block* term binding and not the dispersion quietly
        // growing. The claim has to exceed what the scatter about the combined point reports, which
        // is the figure this capture would have carried before the term existed.
        val withoutBlocks = maxOf(
            drift.minOf { it.accuracyMeters }.toDouble(),
            // r68 of a uniform ±4 m spread, which is what the old statistic would have seen.
            8.0 / Math.sqrt(12.0) * 1.0713,
        )
        assertTrue(
            combined.accuracyMeters > withoutBlocks,
            "reported ${combined.accuracyMeters} m where the within-window statistic alone would " +
                "have said $withoutBlocks m — the block term is not binding",
        )
    }

    @Test
    fun `the block spread stays silent on a capture too short to have thirds`() {
        // The guard that keeps this from inflating every ordinary capture. Twelve readings split
        // three ways is four apiece, and three medians of four noisy points disagree from nothing
        // but noise — a statistic that would report drift on a receiver sitting perfectly still.
        val short = drifting(count = 12, driftMeters = 2.0)
        assertNull(
            interBlockSpreadMeters(
                cluster = short,
                points = short.map { doubleArrayOf(0.0, metersNorthOf(it)) },
                weights = short.map { 1.0 },
            ),
            "twelve samples is below the floor and must produce no evidence rather than weak evidence",
        )
        // The floor is on the count, not on the drift: one more than the floor does answer.
        val long = drifting(count = 15, driftMeters = 2.0)
        assertNotNull(
            interBlockSpreadMeters(
                cluster = long,
                points = long.map { doubleArrayOf(0.0, metersNorthOf(it)) },
                weights = long.map { 1.0 },
            ),
        )
    }

    @Test
    fun `blocks are cut by arrival time rather than by list order`() {
        // The property that makes the statistic mean what it says. Shuffling the pool must not
        // change the answer: the failure being looked for is contiguous *in time*, and a partition
        // that followed insertion order would spread a late excursion across all three blocks and
        // report agreement. The pool really is reordered in practice — alignment rewrites entries
        // in place and the admission ceiling prunes from the middle.
        val ordered = drifting(count = 30, driftMeters = 2.0)
        val shuffled = ordered.reversed()
        fun spreadOf(pool: List<LocationFix>) = interBlockSpreadMeters(
            cluster = pool,
            points = pool.map { doubleArrayOf(0.0, metersNorthOf(it)) },
            weights = pool.map { 1.0 },
        )
        assertEquals(
            requireNotNull(spreadOf(ordered)),
            requireNotNull(spreadOf(shuffled)),
            0.001,
        )
    }

    @Test
    fun `the alignment's own error widens the claim it made smaller`() {
        // Carrier-phase alignment slides the pooled samples onto one frame, so it tightens the very
        // dispersion the claim is read from. Feeding its residual back in is what stops that being
        // a free reduction. In quadrature, because it is an independent error of the same kind.
        val tight = List(6) { fixAt(accuracy = 4f, northMeters = it * 0.1) }
        val unaligned = requireNotNull(combineFixes(tight, maxRadiusMeters = 8.0))
        val aligned = requireNotNull(
            combineFixes(tight, maxRadiusMeters = 8.0, alignmentUncertaintyMeters = 6.0),
        )
        assertTrue(
            aligned.accuracyMeters > unaligned.accuracyMeters,
            "an alignment uncertainty of 6 m left the claim at ${aligned.accuracyMeters} m",
        )
        // Only ever wider. A negative or non-finite budget must not be able to shrink anything.
        assertEquals(
            unaligned.accuracyMeters,
            requireNotNull(
                combineFixes(tight, maxRadiusMeters = 8.0, alignmentUncertaintyMeters = -3.0),
            ).accuracyMeters,
        )
        assertEquals(
            unaligned.accuracyMeters,
            requireNotNull(
                combineFixes(tight, maxRadiusMeters = 8.0, alignmentUncertaintyMeters = Double.NaN),
            ).accuracyMeters,
        )
    }

    @Test
    fun `a whole long-occupation pool combines inside a 1 Hz budget`() {
        // The combination moved from once per capture to once per epoch, so that it is the thing a
        // Stop preserves. That is only defensible if it is cheap, and "sixty-four iterations over
        // four hundred points is obviously fine" is an assertion — this measures it.
        //
        // The budget is deliberately loose. A 1 Hz callback has a thousand milliseconds and this
        // needs to stay in the noise beside the frame it shares a looper with; five is two orders
        // of magnitude under the interval and still tight enough to catch an accidental quadratic.
        // A CI machine under load is the reason it is not tighter, and the reason the measurement
        // takes the best of several runs rather than the first, which pays for JIT warm-up.
        val pool = (0 until 400).map { index ->
            // A realistic pool, not four hundred copies of one point: a slow drift with scatter on
            // top, which is what a stationary receiver actually produces and what makes Weiszfeld
            // iterate rather than converge on the first step.
            fixAt(
                accuracy = 3f + (index % 7) * 0.25f,
                northMeters = index * 0.004 + Math.sin(index * 0.7) * 0.8,
                eastMeters = index * 0.002 + Math.cos(index * 1.1) * 0.8,
                weight = 1f / (2f + (index % 5)),
            )
        }
        assertNotNull(combineFixes(pool, maxRadiusMeters = 8.0), "the fixture has to actually cluster")

        var best = Long.MAX_VALUE
        repeat(12) {
            val started = System.nanoTime()
            combineFixes(pool, maxRadiusMeters = 8.0)
            best = minOf(best, System.nanoTime() - started)
        }
        val millis = best / 1_000_000.0
        assertTrue(millis < 5.0, "combining a 400-sample pool took $millis ms, which a 1 Hz " +
            "callback cannot absorb — check whether the estimator went quadratic in the pool size")
    }

    // ---------------------------------------------------------------------------------------
    // What the gate discards, and what it then claims about what is left
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the minority that contains the tightest reading does not get to be the answer`() {
        // Measured on the emulator 2026-08-20 before it was reasoned about: across eight captures
        // at 5 m/axis scatter, the admission gate kept a median of 50% of a twelve-reading pool,
        // and in two of them it kept *two and three* — then reported a dispersion of 0.00 m,
        // because three readings selected for agreeing with one reading do agree with it. The
        // claim fell through to the receiver's own floor, which is the only reason the app did not
        // announce centimetre precision on three samples.
        //
        // The mechanism is circularity, and it is worth stating precisely because it is not
        // obvious: the radius is measured *from the tightest reading*, so the cluster is the set of
        // readings that agree with one reading, and the dispersion then measures how well that set
        // agrees with itself. Nothing in that loop consults the other nine.
        //
        // Here the loop is closed deliberately. Nine readings sit together at 14 m north — the
        // majority, and where a receiver that has converged actually is. Three sit at the origin,
        // and one of those three self-reports the tightest accuracy in the pool, which a reflected
        // signal is entirely capable of doing. The gate centres on it, admits its two neighbours,
        // discards the nine, and hands back the minority.
        val minority = listOf(
            fixAt(accuracy = 4f, northMeters = 0.0),
            fixAt(accuracy = 5f, northMeters = 0.4),
            fixAt(accuracy = 5f, northMeters = -0.4),
        )
        val majority = (0 until 9).map { index ->
            fixAt(accuracy = 5f, northMeters = 14.0 + Math.sin(index * 2.1) * 0.4)
        }
        val samples = minority + majority

        var terms: ClaimTerms? = null
        val combined = requireNotNull(
            combineFixes(samples, maxRadiusMeters = 8.0, decomposition = { terms = it }),
        )
        val north = metersNorthOf(combined)
        val claim = requireNotNull(terms)

        // Three quarters of the evidence was thrown away.
        assertTrue(
            claim.clusterSize >= MIN_USEFUL_CLUSTER,
            "the gate kept ${claim.clusterSize} of ${claim.sampleCount} readings, below the " +
                "$MIN_USEFUL_CLUSTER the estimator needs before it is a geometric median at all",
        )
        // And the answer followed the nine, not the three.
        assertTrue(
            north > 7.0,
            "nine readings at 14 m and three at 0 m must not average to $north m",
        )
        // And it rejected the three rather than merely outvoting them. This is the assertion that
        // separates the fix from the defect, and it took a wrong first draft to find: the draft
        // demanded a *large* dispersion, on the reasoning that a pool spanning 14 m cannot honestly
        // report a sub-metre error bar. That is wrong, and wrong in a way worth leaving written
        // down. A rejected outlier is not uncertainty — it is a reading the estimator decided was
        // not a measurement of this place, exactly as in the reflection case below, and inflating
        // the claim to cover it would make every honest capture that saw one reflection report a
        // worse number than it earned. What made the old behaviour a defect was never the small
        // dispersion by itself; it was that the survivors had been chosen for agreeing with one
        // reading. Choose them by majority instead and the same small number is earned.
        assertEquals(
            9,
            claim.clusterSize,
            "the nine that agree are the measurement and the three are not",
        )
    }

    @Test
    fun `a lone reflection is still rejected rather than averaged in`() {
        // The other half of the same statement, and the reason the gate is not simply deleted.
        // Eleven readings agree within a metre; one sits 30 m away, which is what a single
        // reflected epoch looks like. That one must not move the answer, and the guarantee cannot
        // be "the geometric median will absorb it" alone — the median is only asked for once there
        // are enough samples, and a short capture may not have them.
        val agreeing = (0 until 11).map { index ->
            fixAt(accuracy = 5f, northMeters = Math.sin(index * 2.1) * 0.5)
        }
        val reflection = fixAt(accuracy = 5f, northMeters = 30.0)

        val combined = requireNotNull(combineFixes(agreeing + reflection, maxRadiusMeters = 8.0))
        assertTrue(
            Math.abs(metersNorthOf(combined)) < 1.0,
            "one reading at 30 m moved eleven that agreed within a metre to " +
                "${metersNorthOf(combined)} m",
        )
    }

    private fun fixAt(
        accuracy: Float,
        northMeters: Double = 0.0,
        eastMeters: Double = 0.0,
        weight: Float = 1f,
    ) = LocationFix(
        latitude = BASE_LATITUDE + northMeters / METERS_PER_DEGREE_LATITUDE,
        longitude = BASE_LONGITUDE + eastMeters / METERS_PER_DEGREE_LONGITUDE,
        capturedAt = Instant.EPOCH,
        accuracyMeters = accuracy,
        elapsedRealtimeNanos = 1_000_000_000L,
        weight = weight,
    )

    private fun metersNorthOf(fix: LocationFix) =
        (fix.latitude - BASE_LATITUDE) * METERS_PER_DEGREE_LATITUDE

    private companion object {
        /**
         * The cluster size at which the combiner is a geometric median rather than a blend back
         * toward the mean. Written out rather than imported because `FixCombiner`'s own constant is
         * file-private, and because a shared constant would let both sides of the assertion move
         * together — if the estimator's threshold changes, this test should have to be re-argued.
         */
        const val MIN_USEFUL_CLUSTER = 5

        const val BASE_LATITUDE = 42.135_098
        const val BASE_LONGITUDE = 24.745_129

        /**
         * One degree of latitude in metres on the sphere [distanceMeters] uses. Written out rather
         * than imported so the tests state their own geometry — a shared constant would let both
         * sides of an assertion drift together.
         */
        const val METERS_PER_DEGREE_LATITUDE = 6_371_000.0 * Math.PI / 180.0

        /** And the same for longitude at [BASE_LATITUDE], which is the term the fixture had missed. */
        val METERS_PER_DEGREE_LONGITUDE =
            METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(BASE_LATITUDE))
    }

    @Test
    fun `the geometry penalty saturates, and that is a decision rather than an oversight`() {
        // Listed as an open question in the second GPS measurement round: beyond an HDOP of about three every
        // epoch weighs the same on geometry, whether the reading is 3 or 30. Nothing said so, and an
        // undocumented cap is indistinguishable from a cap somebody forgot to remove.
        //
        // It is deliberate, and the reason is in MAX_GEOMETRY_PENALTY's own KDoc: every epoch in one
        // capture is standing under the same sky, so an uncapped term would not down-rank the worst
        // epoch relative to its neighbours — it would delete it, and then delete the capture. What
        // the cap costs is the ability to distinguish bad geometry from catastrophic geometry, which
        // is the price of not having a single canyon epoch decide the answer by disappearing.
        val nominal = EpochQuality.Epoch(
            usedInFixCount = 8,
            horizontalDop = GnssGeometry.NOMINAL_HDOP,
        )
        val bad = nominal.copy(horizontalDop = GnssGeometry.NOMINAL_HDOP * 3.0)
        val catastrophic = nominal.copy(horizontalDop = GnssGeometry.NOMINAL_HDOP * 30.0)

        val badWeight = EpochQuality.weightFor(5f, bad)
        val catastrophicWeight = EpochQuality.weightFor(5f, catastrophic)
        assertEquals(
            badWeight,
            catastrophicWeight,
            absoluteTolerance = 1e-9f,
            message = "HDOP 3.6 and HDOP 36 weigh the same; the geometry term is capped by design",
        )

        // The cap is a cap on σ, so the weight ratio is its square. Pinned as a number rather than
        // an inequality: a change that quietly moved the ceiling would still satisfy "worse than
        // nominal" and would go unnoticed by any looser assertion.
        val nominalWeight = EpochQuality.weightFor(5f, nominal)
        assertEquals(
            1.0 / (2.5 * 2.5),
            (catastrophicWeight / nominalWeight).toDouble(),
            absoluteTolerance = 1e-6,
            message = "the worst geometry weighs 1/2.5² of nominal, never zero",
        )

        // The control. Below the ceiling the term is live, not flat — without this the test above
        // would pass just as well against a geometry term that had been removed entirely.
        val slightlyOff = nominal.copy(horizontalDop = GnssGeometry.NOMINAL_HDOP * 1.5)
        assertTrue(
            EpochQuality.weightFor(5f, slightlyOff) < nominalWeight,
            "a mildly worse geometry must still weigh less than a nominal one",
        )
        assertTrue(
            EpochQuality.weightFor(5f, slightlyOff) > badWeight,
            "and more than a saturated one, or the ramp between them does nothing",
        )
    }

    @Test
    fun `the reported terms are the terms that produced the claim`() {
        // Everything the field measurement concludes rests on this. `ClaimTerms` is read to decide
        // which of the three terms bound, and therefore which constant is worth arguing about; if
        // the reported terms and the saved claim were ever computed from different quantities, the
        // analysis would be confidently wrong in a way no amount of field data would reveal —
        // consistent, plausible, and about a number the app never actually saved.
        //
        // So the assertion is not "the terms look sensible", it is the identity itself: the claim
        // must be reconstructible from the terms alone, by the rule the KDoc states.
        var terms: ClaimTerms? = null
        val combined = combineFixes(
            samples = drifting(count = 21, driftMeters = 6.0),
            maxRadiusMeters = 8.0,
            biasInflation = 1.3f,
            alignmentUncertaintyMeters = 1.5,
            decomposition = { terms = it },
        )
        assertNotNull(combined, "twenty-one clustered samples must combine")
        val reported = assertNotNull(terms, "a combination must report its terms")

        val scatter = Math.hypot(reported.dispersionMeters, reported.alignmentMeters)
        val rebuilt = maxOf(
            reported.receiverMeters,
            scatter,
            reported.driftMeters ?: 0.0,
        ) * reported.biasInflation
        assertEquals(
            rebuilt.toFloat(),
            combined.accuracyMeters,
            absoluteTolerance = 1e-4f,
            message = "the saved claim must be exactly max(receiver, scatter, drift) x inflation " +
                "over the reported terms; if it is not, every conclusion drawn from a field run " +
                "is drawn from numbers that did not produce the answer",
        )

        // The alignment budget passed in must be the one reported, or the field data cannot tell a
        // capture that was genuinely still from one whose alignment was working hard to look still.
        assertEquals(
            1.5,
            reported.alignmentMeters,
            absoluteTolerance = 1e-9,
            message = "the alignment budget must be reported as given",
        )
        // And the drift term must be present on a pool this long, or the one statistic a long
        // capture uniquely makes available is silently absent from every trace.
        assertNotNull(
            reported.driftMeters,
            "twenty-one samples fill three blocks of five; the drift term must have been computed",
        )
    }

    @Test
    fun `nothing is decomposed when nothing was combined`() {
        // The control for the test above, and a real contract: `claim` is read as "this capture
        // averaged, and here is why its number is what it is". A single unaveraged epoch has no
        // decomposition, and reporting a stale or invented one would make the analysis count
        // captures that never combined as if they had.
        var called = false
        combineFixes(
            samples = listOf(fixAt(accuracy = 4f)),
            maxRadiusMeters = 8.0,
            decomposition = { called = true },
        )
        assertFalse(called, "a lone sample is returned as-is and has no terms to report")
    }

    @Test
    fun `an evidence-free epoch weighs exactly one over sigma squared`() {
        // A contract, relied on by the warm-up seeding in AndroidCurrentLocationProvider: a reading
        // taken while the receiver was merely warming has no C/N0, no geometry and no band count
        // attached, because `warmUp` registers no status callback. It must therefore enter the
        // combination at the plain accuracy weight — the same thing a device that reports no
        // satellite detail at all gets, which this module's KDoc already calls the honest
        // degradation.
        //
        // Pinned as an identity rather than an inequality because the seeding computes it through
        // this function on purpose. If an evidence-free epoch ever stops meaning 1/sigma^2, the
        // seeded samples silently change weight and nothing else would say so.
        for (accuracy in floatArrayOf(1f, 3f, 5f, 12.5f)) {
            assertEquals(
                1.0 / (accuracy * accuracy).toDouble(),
                EpochQuality.weightFor(accuracy, EpochQuality.Epoch()).toDouble(),
                absoluteTolerance = 1e-9,
                message = "an epoch with nothing attached must weigh 1/sigma^2 at $accuracy m",
            )
        }
    }

    @Test
    fun `a sample carrying the default weight would outvote properly weighted ones`() {
        // The trap this guards, written as a live assertion because it is invisible on an emulator
        // and expensive in the field.
        //
        // `LocationFix.weight` defaults to 1f, and `combineFixes` honours any stored weight that is
        // positive and finite. A five-metre epoch weighed properly comes in at 0.04. So one sample
        // that kept the default sits in the combination at twenty-five times the authority of a real
        // epoch — and the warm-up seeding adds up to fifteen of them at once, all from before the
        // user pressed anything.
        val properly = (0 until 8).map {
            fixAt(accuracy = 5f, northMeters = 0.0, weight = 1f / 25f)
        }
        val strayDefault = fixAt(accuracy = 5f, northMeters = 6.0)
        assertEquals(
            1f,
            strayDefault.weight,
            "LocationFix still defaults its weight to 1f; this test is about what that costs",
        )

        val skewed = assertNotNull(combineFixes(properly + strayDefault, maxRadiusMeters = 8.0))
        val corrected = assertNotNull(
            combineFixes(
                properly + strayDefault.copy(weight = 1f / 25f),
                maxRadiusMeters = 8.0,
            ),
        )
        assertTrue(
            metersNorthOf(skewed) > metersNorthOf(corrected) + 1.0,
            "one default-weighted outlier must visibly drag the answer further than a correctly " +
                "weighted one; it dragged it to ${metersNorthOf(skewed)} m versus " +
                "${metersNorthOf(corrected)} m",
        )
    }

    @Test
    fun `an unpenalised weight is the largest one a reading of that accuracy can have`() {
        // Why the warm-up seeding weighs its readings with the current epoch's sky rather than with
        // an empty one. Every term in `weightFor` inflates sigma and none reduces it, so an
        // evidence-free epoch is not a neutral weight — it is the *maximum* weight available at that
        // accuracy. Seeding with it would have made the pre-tap readings the only samples in the
        // pool immune to weak signal, bad geometry and single-band operation, and they would have
        // outweighed real epochs precisely when the penalties were doing the most work.
        val evidenceFree = EpochQuality.weightFor(5f, EpochQuality.Epoch())
        val poorSky = EpochQuality.Epoch(
            usedInFixCount = 5,
            meanCn0DbHz = 18f,
            dualBandSatellites = 0,
            horizontalDop = GnssGeometry.NOMINAL_HDOP * 4.0,
            lowElevationFraction = 0.8f,
        )
        val penalised = EpochQuality.weightFor(5f, poorSky)
        assertTrue(
            penalised < evidenceFree,
            "a bad sky must weigh less than no information at all, or the penalties do nothing",
        )
        // Not a token difference: this is the size of the bias the seeding would have carried.
        assertTrue(
            evidenceFree > penalised * 4f,
            "the gap is large — evidence-free $evidenceFree against penalised $penalised",
        )
        // And a good sky is not rewarded past the unpenalised weight, which is what makes
        // "unpenalised" the ceiling rather than the middle.
        val goodSky = EpochQuality.Epoch(
            usedInFixCount = 20,
            meanCn0DbHz = 45f,
            dualBandSatellites = 8,
            horizontalDop = GnssGeometry.NOMINAL_HDOP,
            lowElevationFraction = 0f,
        )
        assertEquals(
            evidenceFree,
            EpochQuality.weightFor(5f, goodSky),
            absoluteTolerance = 1e-9f,
            message = "weighting is inflation only; a perfect sky reaches the ceiling, never past it",
        )
    }

    @Test
    fun `the saved fix is never stamped with a reading from before the tap`() {
        // The defect: warm-up seeding puts PRECISION readings from before the tap into the pool, and
        // `combineFixes` takes the saved fix's identity — its `capturedAt`, which the item editor
        // stores on the item — from the tightest PRECISION reading in the cluster. A warm receiver
        // has had longer to converge than a capture two seconds old, so the tightest reading is very
        // often one of the seeded ones, and the saved coordinate would carry a timestamp from before
        // the user pressed anything.
        //
        // That is exactly what LocationFixPolicy's cached-fix rule refuses, reached by a route the
        // rule does not watch: `reject` judges incoming readings, and this is the combination on the
        // way out.
        val tap = 50_000_000_000L
        val beforeTap = fixAt(accuracy = 2f, northMeters = 0.3).copy(
            capturedAt = Instant.ofEpochMilli(1_000L),
            elapsedRealtimeNanos = tap - 5_000_000_000L,
        )
        val afterTap = fixAt(accuracy = 6f).copy(
            capturedAt = Instant.ofEpochMilli(9_000L),
            elapsedRealtimeNanos = tap + 1_000_000_000L,
        )
        val alsoAfter = fixAt(accuracy = 6.5f, northMeters = 0.2).copy(
            capturedAt = Instant.ofEpochMilli(10_000L),
            elapsedRealtimeNanos = tap + 2_000_000_000L,
        )

        val combined = assertNotNull(
            combineFixes(
                samples = listOf(beforeTap, afterTap, alsoAfter),
                maxRadiusMeters = 8.0,
                anchorNotBeforeNanos = tap,
            ),
        )
        assertEquals(
            Instant.ofEpochMilli(9_000L),
            combined.capturedAt,
            "the saved fix must be stamped with a reading taken after the tap, not the tightest one",
        )
        assertTrue(
            combined.elapsedRealtimeNanos >= tap,
            "and its monotonic stamp must not predate the request either",
        )

        // The control: without the restriction the tightest reading still wins, so the assertion
        // above is reading the parameter rather than an accident of this fixture's ordering.
        val unrestricted = assertNotNull(
            combineFixes(listOf(beforeTap, afterTap, alsoAfter), maxRadiusMeters = 8.0),
        )
        assertEquals(
            Instant.ofEpochMilli(1_000L),
            unrestricted.capturedAt,
            "unrestricted, the pre-tap reading is the tightest and would have been the anchor",
        )

        // And the position is unaffected: only the identity is restricted, never the combination.
        assertEquals(
            metersNorthOf(unrestricted),
            metersNorthOf(combined),
            absoluteTolerance = 1e-9,
            message = "restricting the anchor must not move the answer",
        )
    }

    @Test
    fun `a tight pre-tap reading cannot make the saved claim more optimistic`() {
        // The third instance of the same shape, and the one that would have been hardest to notice:
        // it makes the app *sound better*, so nothing about it looks like a bug.
        //
        // The claim is floored by the tightest reading in the pool. In a seeded capture that is very
        // often a pre-tap one, because a warm receiver has had longer to converge than a capture two
        // seconds old. A 2 m warm-up reading would drop the floor to 2 m on an answer whose post-tap
        // evidence only supports 6 m — a number the user reads, stored beside a coordinate, better
        // than anything measured since they asked.
        val tap = 50_000_000_000L
        val tightBeforeTap = fixAt(accuracy = 2f, northMeters = 0.2).copy(
            elapsedRealtimeNanos = tap - 4_000_000_000L,
        )
        val afterTap = fixAt(accuracy = 6f).copy(elapsedRealtimeNanos = tap + 1_000_000_000L)
        val alsoAfter = fixAt(accuracy = 6.5f, northMeters = 0.1).copy(
            elapsedRealtimeNanos = tap + 2_000_000_000L,
        )
        val samples = listOf(tightBeforeTap, afterTap, alsoAfter)

        val restricted = assertNotNull(
            combineFixes(samples, maxRadiusMeters = 8.0, anchorNotBeforeNanos = tap),
        )
        assertEquals(
            6f,
            restricted.accuracyMeters,
            absoluteTolerance = 1e-4f,
            message = "the floor must come from the tightest reading taken after the tap",
        )

        // The control. Unrestricted, the pre-tap reading sets the floor and the claim is a third of
        // the size — which is what the shipped code would have saved.
        val unrestricted = assertNotNull(combineFixes(samples, maxRadiusMeters = 8.0))
        assertTrue(
            unrestricted.accuracyMeters < restricted.accuracyMeters,
            "unrestricted, the pre-tap reading would have lowered the claim to " +
                "${unrestricted.accuracyMeters} m against ${restricted.accuracyMeters} m",
        )

        // And for an ordinary capture — every sample after the tap — the restriction changes
        // nothing at all, which is what makes this safe to apply unconditionally.
        val ordinary = listOf(afterTap, alsoAfter)
        assertEquals(
            assertNotNull(combineFixes(ordinary, maxRadiusMeters = 8.0)).accuracyMeters,
            assertNotNull(
                combineFixes(ordinary, maxRadiusMeters = 8.0, anchorNotBeforeNanos = tap),
            ).accuracyMeters,
            absoluteTolerance = 1e-6f,
            message = "with no pre-tap readings in the pool this must be a no-op",
        )
    }

    @Test
    fun `a pre-tap reading is never returned as the saved answer`() {
        // The fourth and worst instance of the seeding shape, and the one that walks past every
        // other guard: `combineFixes` returns `best` outright when the cluster is too small to
        // combine, and that return happens *before* the anchor and floor restrictions apply.
        //
        // It is not a remote case, it is a self-arranging one. A tight pre-tap reading is both the
        // centre of the admission radius and the thing that scales it, so the tighter it is the
        // smaller the radius it sits at the middle of — and the likelier the post-tap readings are
        // to fall outside it, leaving a cluster of one. The saved coordinate is then a raw pre-tap
        // reading with its own timestamp.
        val tap = 50_000_000_000L
        val tightBeforeTap = fixAt(accuracy = 1f).copy(
            capturedAt = Instant.ofEpochMilli(1_000L),
            elapsedRealtimeNanos = tap - 6_000_000_000L,
        )
        // Eight metres away: well outside 1 m x 1.5, so the pre-tap reading's cluster is itself
        // alone, and comfortably inside 7 m x 1.5 once a post-tap reading is the centre.
        val afterTap = fixAt(accuracy = 7f, northMeters = 8.0).copy(
            capturedAt = Instant.ofEpochMilli(9_000L),
            elapsedRealtimeNanos = tap + 1_000_000_000L,
        )
        val alsoAfter = fixAt(accuracy = 7.5f, northMeters = 8.4).copy(
            capturedAt = Instant.ofEpochMilli(10_000L),
            elapsedRealtimeNanos = tap + 2_000_000_000L,
        )
        val samples = listOf(tightBeforeTap, afterTap, alsoAfter)

        val restricted = assertNotNull(
            combineFixes(samples, maxRadiusMeters = 20.0, anchorNotBeforeNanos = tap),
        )
        assertTrue(
            restricted.elapsedRealtimeNanos >= tap,
            "the saved answer must never be a reading taken before the request for it",
        )
        assertTrue(
            metersNorthOf(restricted) > 4.0,
            "and it must be near the post-tap readings, not at the pre-tap one; it was at " +
                "${metersNorthOf(restricted)} m",
        )

        // The control, and it is the shipped behaviour without the restriction: the pre-tap reading
        // is the tightest, so it centres a 1.5 m radius that excludes both post-tap readings, the
        // cluster is a single sample, and it is returned as-is.
        val unrestricted = assertNotNull(combineFixes(samples, maxRadiusMeters = 20.0))
        assertEquals(
            Instant.ofEpochMilli(1_000L),
            unrestricted.capturedAt,
            "unrestricted, the raw pre-tap reading is handed back as the saved answer",
        )
        assertEquals(
            1f,
            unrestricted.accuracyMeters,
            absoluteTolerance = 1e-6f,
            message = "with its own optimistic accuracy attached",
        )
    }
}

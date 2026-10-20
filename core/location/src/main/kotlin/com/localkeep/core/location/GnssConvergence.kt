package com.localkeep.core.location

/**
 * When a capture has stopped getting better, and whether the receiver is using more than one band.
 *
 * Kept apart from [LocationFixPolicy], which answers a different question. That one decides what is
 * *admissible* — is this number a measurement at all. This one decides when to *stop*, which is a
 * judgement about a series rather than about a reading, and mixing the two would let a decision
 * about patience quietly become a decision about acceptance.
 *
 * Pure, for the same reason as its neighbour: every interesting case is a shape of a curve over
 * time, and there is no way to make a real GNSS receiver produce a chosen one on demand.
 */
object GnssConvergence {

    /** One accuracy reading and the monotonic instant it arrived. */
    data class AccuracySample(val elapsedRealtimeNanos: Long, val accuracyMeters: Float)

    /**
     * Whether the receiver has stopped improving, which is the signal the old code did not have.
     *
     * The capture used to stop the moment accuracy crossed a threshold, and that is the wrong
     * question. A GNSS solution keeps improving for tens of seconds after it first looks acceptable:
     * the filter's covariance shrinks as epochs accumulate, second-band signals are acquired several
     * seconds behind the first, and satellites that were low on the horizon — where the signal has
     * travelled further through the atmosphere and bounced off more — climb or are replaced. First
     * crossing a line says the curve passed a height; it says nothing about whether the curve is
     * still falling. Stopping there means stopping *because* it got good enough to notice, which is
     * roughly the earliest moment it was still worth waiting.
     *
     * So the test is on the slope, not the value: compare the *level* the series held one window
     * ago against the *best* it has reached since. If the difference is under [deltaMeters], the
     * last window bought less than half a metre and the next one will not buy more.
     *
     * ### Why the two terms are different statistics
     *
     * This used to be the best reading before the window against the best reading overall, and both
     * being minima of nested prefixes gave it a latch it could not get out of. `bestBeforeWindow` is
     * never smaller than `bestOverall`, so once the all-time-best sample was itself older than the
     * cutoff both terms became that same number and the difference was exactly zero — permanently
     * plateaued, escapable only by a reading that beat an all-time best by half a metre. If that
     * best was one momentarily favourable geometry, the escape never came, and a receiver still
     * genuinely converging was declared settled on the strength of a single lucky epoch. That is the
     * same fluke [FixRequest.minSettleSamples] exists to distrust, and both gates missed it together
     * because both were reading a minimum.
     *
     * So the earlier window is summarised by its **median**, which a single optimistic sample cannot
     * move, and the recent window by its **best**, which a single genuine improvement can. The
     * asymmetry is the point and it matches the physics: accuracy can only be flattered by a fluke
     * downward, so the reference level must be robust while the thing being asked — has it fallen
     * since — must stay sensitive.
     *
     * ### The guards, all of which say "no plateau" rather than guessing
     *
     *  - **Fewer than three readings at all.** Two points are a line and every line has a slope;
     *    three is the minimum from which "it flattened" is distinguishable from "it happened to be
     *    flat between these two".
     *  - **Nothing inside the earlier window.** The series does not yet span the interval being
     *    asked about, so there is no comparison to make. A capture that started four seconds ago has
     *    not plateaued; it has barely started.
     *  - **Fewer than three readings in the recent window.** The earlier window only supplies a
     *    reference level, so one sample there is enough; the flattening itself has to be *visible*,
     *    and it is not visible in one or two readings.
     *
     * All three fall through to the deadline, which is the backstop for every path here.
     */
    fun hasPlateaued(
        history: List<AccuracySample>,
        nowElapsedNanos: Long,
        windowNanos: Long,
        deltaMeters: Float,
    ): Boolean {
        if (history.size < MIN_SAMPLES_FOR_A_SLOPE) return false
        val cutoff = nowElapsedNanos - windowNanos
        // Two adjacent windows of the same length rather than a prefix and the whole series, so the
        // reference level is the receiver's recent past and not everything it has ever reported.
        val earlier = history.filter { it.elapsedRealtimeNanos in (cutoff - windowNanos)..cutoff }
        if (earlier.isEmpty()) return false
        val recent = history.filter { it.elapsedRealtimeNanos > cutoff }
        if (recent.size < MIN_SAMPLES_FOR_A_SLOPE) return false
        val earlierLevel = medianAccuracy(earlier)
        val recentBest = recent.minOf { it.accuracyMeters }
        return earlierLevel - recentBest < deltaMeters
    }

    /** Breakdown point one half, which is the entire reason the earlier window is summarised by it. */
    private fun medianAccuracy(samples: List<AccuracySample>): Float {
        val sorted = samples.map { it.accuracyMeters }.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    /**
     * Whether a satellite signal is on a band other than the primary one.
     *
     * The single largest correctable error left in a consumer fix is ionospheric delay — the
     * atmosphere slows the signal by a few metres at night and well over ten at midday. A receiver
     * tracking the same satellite on two frequencies can compute that delay directly and remove
     * nearly all of it, because the delay depends on frequency and almost nothing else does. A
     * receiver on one frequency has to use a broadcast model that corrects roughly half.
     *
     * The chipset does this itself and exposes no switch. What the app can do is *wait*: the second
     * band is acquired several seconds behind the first, so a capture that settles early settles on
     * the single-frequency solution and inherits an error that would have gone away on its own.
     *
     * Classified by band rather than by exact frequency because there are a dozen relevant signals
     * across four constellations and matching each one is a table that goes stale. The split is
     * unambiguous: every primary signal (GPS L1, Galileo E1, BeiDou B1, GLONASS L1) sits between
     * 1559 and 1610 MHz, and every secondary one this matters for (L5, E5a, E5b, B2, L2, GLONASS L3)
     * sits between 1164 and 1240. Nothing lives in between, so a wide bracket is more robust than a
     * tight tolerance around each nominal value, not less.
     */
    fun isSecondaryBand(carrierFrequencyHz: Float): Boolean =
        carrierFrequencyHz in SECONDARY_BAND_LOW_HZ..SECONDARY_BAND_HIGH_HZ

    /**
     * Whether the second-band gate is satisfied, or has nothing to say.
     *
     * The distinction is the whole design. A device that never reports a carrier frequency for any
     * satellite is not failing the gate — it is a device the gate cannot be asked about, and holding
     * a capture open waiting for evidence that will never arrive is how a quality check becomes a
     * hang. So the gate is *unavailable* rather than *unmet*, and the capture proceeds on the
     * plateau detector alone.
     *
     * Once any satellite has reported a frequency the gate is armed and means what it says: wait
     * until enough signals on a second band are actually contributing to the fix. The deadline still
     * ends the capture either way, so an armed gate that is never met costs the user the rest of the
     * window, not the reading.
     */
    fun secondBandReady(
        anyCarrierFrequencyReported: Boolean,
        dualBandSatellitesInFix: Int,
        requiredSatellites: Int,
    ): Boolean = !anyCarrierFrequencyReported || dualBandSatellitesInFix >= requiredSatellites

    /**
     * Whether an armed second-band gate has demonstrably stopped making progress and should stand
     * down.
     *
     * The gate's own argument is that the second band arrives several seconds behind the first, so a
     * capture that settles early inherits an ionospheric error that would have gone away on its own.
     * That argument is about a correction that is *coming*. It says nothing about one that is not:
     * on an L5-capable phone with a partly obstructed sky it is entirely ordinary to see two second-
     * band signals contributing and never a third, and the gate is then armed, permanently unmet,
     * and blocking every early exit for the rest of the window. The commentary above accepts that
     * cost. This answers it instead, in the one case where the evidence is unambiguous.
     *
     * Deliberately **not** a bare timeout, which would discard the gate's argument rather than
     * answer it. Two things must both hold, for two whole plateau windows: the accuracy series has
     * not improved, and the number of second-band signals in the fix has not risen. Sixteen seconds
     * of neither is direct evidence that the correction is not arriving — a receiver that was about
     * to acquire another L5 signal would have moved one of those two numbers.
     *
     * Both durations are measured from the moment the condition last *changed*, so a single new
     * second-band signal or a single half-metre improvement resets the clock and the gate goes back
     * to waiting, which is what it should do.
     */
    fun secondBandStalled(
        plateauHeldNanos: Long,
        secondBandUnchangedNanos: Long,
        windowNanos: Long,
    ): Boolean {
        val required = windowNanos * STALLED_GATE_WINDOWS
        return plateauHeldNanos >= required && secondBandUnchangedNanos >= required
    }

    /** Three: the fewest readings from which a flattening curve is distinguishable from a flat pair. */
    const val MIN_SAMPLES_FOR_A_SLOPE = 3

    /**
     * Two plateau windows of no progress on either axis before the second-band gate stands down.
     * One window is the length of the question the plateau detector already asks; two is the
     * smallest number that means "and it stayed that way", which is what turns a reading into
     * evidence.
     */
    const val STALLED_GATE_WINDOWS = 2L

    private const val SECONDARY_BAND_LOW_HZ = 1_100_000_000f
    private const val SECONDARY_BAND_HIGH_HZ = 1_300_000_000f
}

package com.localkeep.core.location

/**
 * What the sky looked like, from the azimuth and elevation the status callback already reports.
 *
 * Horizontal error is roughly `HDOP × UERE`. Everything else this capture measures — carrier-to-noise,
 * band count, speed — speaks to UERE, the per-satellite ranging error. HDOP is the *other*
 * multiplicative term, and until this file existed it was approximated by counting satellites. A
 * count and a geometry are not the same quantity and they disagree in a common, important way: ten
 * satellites all on one side of a street score as excellent by count and are the worst geometry the
 * receiver will see all day.
 *
 * Nothing here needs ephemeris, a network, a new permission or a `GnssMeasurement`. Azimuth and
 * elevation are *outputs* of the orbit computation the platform has already done, handed back on a
 * callback the capture registers anyway. That is also the boundary of what is reachable this way —
 * an ionosphere-free combination needs the ranges themselves and is not obtainable from here.
 *
 * Pure, and for the same reason as its two neighbours: no test can make a real constellation stand
 * in a chosen arrangement, so the decision is arithmetic over two arrays and the Android side only
 * collects them.
 */
object GnssGeometry {

    /** One satellite's line of sight, in degrees, as `GnssStatus` reports it. */
    data class SkyPosition(val azimuthDegrees: Float, val elevationDegrees: Float)

    /**
     * One *signal*, which is what `GnssStatus` actually enumerates.
     *
     * The distinction this type exists to force: a satellite tracked on two frequencies appears
     * **twice** in that array, with the same azimuth and elevation and a different carrier frequency.
     * Treating those entries as two satellites is wrong in three separate ways at once, and every one
     * of them gets *worse* on a better phone — which is the kind of bug that survives review, because
     * the good hardware is where you would look for confirmation that it works.
     *
     * @param carrierFrequencyHz `null` when the device did not report one, which is a gap in the data
     *   rather than a satellite on no frequency.
     */
    data class SignalReport(
        val satelliteKey: Int,
        val sky: SkyPosition,
        val cn0DbHz: Float,
        val carrierFrequencyHz: Float?,
    )

    /** The constellation, counted once per satellite. */
    data class ConstellationSummary(
        /** One entry per distinct satellite contributing to the fix. */
        val satellites: List<SkyPosition>,
        val meanCn0DbHz: Float,
        /**
         * Satellites tracked on a primary **and** a secondary band.
         *
         * The number the dual-frequency gate should have been asking for all along. Ionospheric
         * delay is removed by comparing one satellite's range on two frequencies, so four second-band
         * *signals* from four satellites whose primary band is not in the fix buys none of it. This
         * counts the pairs.
         */
        val dualBandSatellites: Int,
        /** Whether any signal reported a carrier frequency, which is what arms the gate at all. */
        val anyCarrierFrequencyReported: Boolean,
    )

    /**
     * Collapse a per-signal array into a per-satellite view.
     *
     * Three defects fixed in one place, all from counting signals as satellites:
     *
     *  - **Geometry.** A dual-band satellite contributed its line-of-sight row to `GᵀG` twice, which
     *    conditions the matrix better than the sky actually is and makes [horizontalDop] report a
     *    *lower* HDOP than the truth. The penalty that geometry term exists to apply was being
     *    quietly reduced, and reduced most on the phones tracking the most dual-band satellites.
     *  - **Redundancy.** `usedInFixCount` was a signal count, so a receiver on eight satellites
     *    tracking six of them on two bands reported fourteen and cleared every redundancy threshold
     *    it should not have.
     *  - **The dual-frequency gate.** See [ConstellationSummary.dualBandSatellites].
     *
     * The mean carrier-to-noise is taken over satellites rather than signals for the same reason: an
     * L5 signal is typically a few dB weaker than its own L1, so counting both dragged the mean down
     * for the dual-band satellites that are the *better* ones to be tracking.
     */
    fun summarise(signals: List<SignalReport>): ConstellationSummary {
        if (signals.isEmpty()) {
            return ConstellationSummary(emptyList(), 0f, 0, false)
        }
        val sky = LinkedHashMap<Int, SkyPosition>()
        val best = HashMap<Int, Float>()
        val primary = HashSet<Int>()
        val secondary = HashSet<Int>()
        var reported = false
        signals.forEach { signal ->
            sky.putIfAbsent(signal.satelliteKey, signal.sky)
            if (signal.cn0DbHz.isFinite() && signal.cn0DbHz > 0f) {
                val current = best[signal.satelliteKey]
                if (current == null || signal.cn0DbHz > current) best[signal.satelliteKey] = signal.cn0DbHz
            }
            val frequency = signal.carrierFrequencyHz ?: return@forEach
            reported = true
            if (isSecondaryBand(frequency)) secondary += signal.satelliteKey
            else primary += signal.satelliteKey
        }
        return ConstellationSummary(
            satellites = sky.values.toList(),
            meanCn0DbHz = if (best.isEmpty()) 0f else best.values.sum() / best.size,
            dualBandSatellites = secondary.count { it in primary },
            anyCarrierFrequencyReported = reported,
        )
    }

    /** Shared with [GnssConvergence.isSecondaryBand] so one bracket decides for the whole module. */
    private fun isSecondaryBand(carrierFrequencyHz: Float) =
        GnssConvergence.isSecondaryBand(carrierFrequencyHz)

    /**
     * Horizontal dilution of precision, or `null` when the constellation cannot answer.
     *
     * Each satellite contributes a unit line-of-sight vector `e = (cos el · sin az, cos el · cos az,
     * sin el)` in local east/north/up. The geometry matrix `G` has rows `(-eₑ, -eₙ, -eᵤ, 1)` — the
     * fourth column being the receiver clock, which is solved for alongside position and is why four
     * satellites are the minimum rather than three. `Q = (GᵀG)⁻¹` is the covariance of the solution
     * in units of ranging error, and `HDOP = √(Q₀₀ + Q₁₁)` is its horizontal part.
     *
     * `null` rather than a large number in every case where the answer would be invented: fewer than
     * four satellites, a singular `GᵀG` (every satellite at the same elevation and azimuth, or a
     * device filling both fields with zero), or a non-finite result. A device that answers `null`
     * gets exactly the weighting it got before this existed — see [EpochQuality.weightFor], which
     * keeps the satellite-count term as the fallback rather than deleting it.
     */
    fun horizontalDop(sky: List<SkyPosition>): Double? {
        val usable = sky.filter {
            it.azimuthDegrees.isFinite() && it.elevationDegrees.isFinite() &&
                it.elevationDegrees > MINIMUM_USABLE_ELEVATION_DEGREES
        }
        if (usable.size < MINIMUM_SATELLITES_FOR_A_SOLUTION) return null

        // GᵀG accumulated directly. It is 4×4 and symmetric, so the whole normal-equation matrix is
        // built in one pass over the constellation rather than by forming G and multiplying.
        val normal = Array(4) { DoubleArray(4) }
        usable.forEach { satellite ->
            val row = designRow(satellite)
            for (i in 0 until 4) for (j in 0 until 4) normal[i][j] += row[i] * row[j]
        }

        val inverse = invert4x4(normal) ?: return null
        val hdop = Math.sqrt(inverse[0][0] + inverse[1][1])
        return if (hdop.isFinite() && hdop > 0.0) hdop else null
    }

    /**
     * The fraction of the used constellation sitting below [LOW_ELEVATION_DEGREES].
     *
     * A signal from just above the horizon travels through the most atmosphere and has had the most
     * opportunity to bounce off something on the way. A solution leaning on those is worse than its
     * satellite count suggests. This is a downweight only — the app cannot remove a satellite from
     * the chipset's own solution and must not behave as though it had.
     */
    fun lowElevationFraction(sky: List<SkyPosition>): Float {
        val measured = sky.filter { it.elevationDegrees.isFinite() && it.elevationDegrees > 0f }
        if (measured.isEmpty()) return 0f
        return measured.count { it.elevationDegrees < LOW_ELEVATION_DEGREES }.toFloat() / measured.size
    }

    /**
     * The largest empty wedge of sky between two used satellites, in degrees, or `null` if unknowable.
     *
     * This is the one thing in the capture that detects a *bias* rather than noise. Averaging cancels
     * error that changes sign between samples; a signal bouncing off the same wall for forty seconds
     * is wrong in the same direction every time, and no number of samples touches it. A receiver
     * whose used satellites all lie in one half of the sky is against a building or a cliff, and its
     * horizontal error has a systematic component pointing away from the blockage.
     *
     * A gap of 180° means the used set spans a half-plane at best. Below [OPEN_SKY_MAX_GAP_DEGREES]
     * the sky is open enough that no inflation is applied, and the answer is used to make the app
     * report *worse* — correctly — rather than to improve anything.
     */
    fun widestAzimuthGapDegrees(sky: List<SkyPosition>): Double? {
        val azimuths = sky
            .filter { it.azimuthDegrees.isFinite() && it.elevationDegrees.isFinite() }
            .map { ((it.azimuthDegrees.toDouble() % 360.0) + 360.0) % 360.0 }
            .sorted()
        if (azimuths.size < MINIMUM_SATELLITES_FOR_A_SOLUTION) return null
        // Every satellite reporting the same bearing is a device filling the field with a constant,
        // not a constellation stacked in one direction. Saying nothing is the honest answer.
        if (azimuths.last() - azimuths.first() < 1e-6) return null

        var widest = azimuths.first() + 360.0 - azimuths.last()
        for (index in 1 until azimuths.size) {
            widest = maxOf(widest, azimuths[index] - azimuths[index - 1])
        }
        return widest
    }

    /**
     * How much to inflate a combined reading's accuracy because the sky was one-sided.
     *
     * Ramped rather than stepped, and capped, for the same reason the weight penalties are ramped:
     * nothing here is calibrated against measured error, so the two endpoints are the whole claim and
     * a curve between them invents no third number. `1f` — no inflation — whenever the gap is
     * unknown or the sky is open, so a device that reports no azimuths behaves exactly as it did
     * before this existed.
     */
    fun blockageInflation(widestGapDegrees: Double?): Float {
        val gap = widestGapDegrees ?: return 1f
        if (gap <= OPEN_SKY_MAX_GAP_DEGREES) return 1f
        val severity = ((gap - OPEN_SKY_MAX_GAP_DEGREES) /
            (BLOCKED_SKY_GAP_DEGREES - OPEN_SKY_MAX_GAP_DEGREES)).coerceIn(0.0, 1.0)
        return (1.0 + severity * (MAX_BLOCKAGE_INFLATION - 1.0)).toFloat()
    }

    /**
     * The unit line-of-sight vector to a satellite, in local east/north/up.
     *
     * Shared rather than duplicated because [Tdcp] needs exactly the same vectors [horizontalDop]
     * builds: dilution of precision and time-differenced carrier phase are two questions asked of
     * one geometry, and two copies of this would be two chances to get a sign wrong in only one of
     * them.
     */
    fun lineOfSight(satellite: SkyPosition): DoubleArray {
        val elevation = Math.toRadians(satellite.elevationDegrees.toDouble())
        val azimuth = Math.toRadians(satellite.azimuthDegrees.toDouble())
        val cosElevation = Math.cos(elevation)
        return doubleArrayOf(
            cosElevation * Math.sin(azimuth),
            cosElevation * Math.cos(azimuth),
            Math.sin(elevation),
        )
    }

    /**
     * Least squares for the four unknowns every GNSS problem in this module has: three of
     * displacement or position, and the receiver clock.
     *
     * `null` on a singular geometry or a non-finite result, which the callers turn into "this device
     * has not told us anything" rather than into an error.
     */
    fun solveFourUnknowns(rows: List<DoubleArray>, observations: List<Double>): DoubleArray? {
        if (rows.size < MINIMUM_SATELLITES_FOR_A_SOLUTION || rows.size != observations.size) return null
        val normal = Array(4) { DoubleArray(4) }
        val projected = DoubleArray(4)
        rows.forEachIndexed { index, row ->
            for (i in 0 until 4) {
                projected[i] += row[i] * observations[index]
                for (j in 0 until 4) normal[i][j] += row[i] * row[j]
            }
        }
        val inverse = invert4x4(normal) ?: return null
        val solution = DoubleArray(4)
        for (i in 0 until 4) {
            var total = 0.0
            for (j in 0 until 4) total += inverse[i][j] * projected[j]
            solution[i] = total
        }
        return if (solution.all { it.isFinite() }) solution else null
    }

    /**
     * Gauss-Jordan with partial pivoting on a 4×4. Written out rather than pulled in: this is the
     * only matrix in the module, and a dependency for sixty lines of arithmetic would be a worse
     * trade than the arithmetic.
     *
     * `null` on a singular or near-singular matrix, which is the ordinary answer for a degenerate
     * constellation rather than an error — the caller turns it into "no HDOP available" and the
     * weighting falls back.
     */
    private fun invert4x4(source: Array<DoubleArray>): Array<DoubleArray>? {
        val a = Array(4) { row -> DoubleArray(8) }
        for (row in 0 until 4) {
            for (column in 0 until 4) a[row][column] = source[row][column]
            a[row][4 + row] = 1.0
        }
        for (column in 0 until 4) {
            var pivot = column
            for (row in column + 1 until 4) {
                if (Math.abs(a[row][column]) > Math.abs(a[pivot][column])) pivot = row
            }
            if (Math.abs(a[pivot][column]) < SINGULAR_PIVOT_EPSILON) return null
            val swap = a[column]; a[column] = a[pivot]; a[pivot] = swap
            val divisor = a[column][column]
            for (index in 0 until 8) a[column][index] /= divisor
            for (row in 0 until 4) {
                if (row == column) continue
                val factor = a[row][column]
                if (factor == 0.0) continue
                for (index in 0 until 8) a[row][index] -= factor * a[column][index]
            }
        }
        val inverse = Array(4) { row -> DoubleArray(4) { column -> a[row][4 + column] } }
        if (inverse.any { row -> row.any { !it.isFinite() } }) return null
        return inverse
    }

    /**
     * One row of the geometry matrix: the negated line of sight, then a one for the receiver clock.
     *
     * The clock column is why four satellites are the minimum rather than three, and the negation is
     * the sign convention both callers depend on — a range to a satellite *shortens* as the receiver
     * moves toward it.
     */
    fun designRow(satellite: SkyPosition): DoubleArray {
        val unit = lineOfSight(satellite)
        return doubleArrayOf(-unit[0], -unit[1], -unit[2], 1.0)
    }

    /** Four unknowns — three of position and the receiver clock — so four ranges at minimum. */
    const val MINIMUM_SATELLITES_FOR_A_SOLUTION = 4

    /**
     * Below this a reported elevation is treated as absent rather than as a satellite on the horizon.
     * Devices that do not measure elevation fill the field with zero, and a constellation of zeroes
     * makes `GᵀG` singular — which [horizontalDop] would catch anyway, but rejecting it here says
     * why.
     */
    const val MINIMUM_USABLE_ELEVATION_DEGREES = 0.5f

    /** The usual receiver mask. Below it, atmosphere and multipath dominate the ranging error. */
    const val LOW_ELEVATION_DEGREES = 15f

    /**
     * A well-distributed constellation. Used as the divisor so a good-geometry epoch is left alone
     * rather than rewarded — [EpochQuality.weightFor] inflates σ and never reduces it.
     */
    const val NOMINAL_HDOP = 1.2

    /** Wider than a quadrant of empty sky is the first sign of something standing in the way. */
    private const val OPEN_SKY_MAX_GAP_DEGREES = 120.0

    /** Half the sky missing. Beyond this the inflation is capped rather than extrapolated. */
    private const val BLOCKED_SKY_GAP_DEGREES = 200.0

    /** [JUDG], uncalibrated: a third wider, not a claim about how large the bias actually is. */
    private const val MAX_BLOCKAGE_INFLATION = 1.35

    private const val SINGULAR_PIVOT_EPSILON = 1e-9
}

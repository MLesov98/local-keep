package com.localkeep.core.location

/**
 * How a cluster of stationary readings becomes the one position that gets saved.
 *
 * A GNSS receiver standing still does not report the same place twice: multipath, atmospheric delay
 * and satellite geometry move it around by a few metres between samples. Much of that is random,
 * and combining several readings cancels the random part — it is how a fixed point is surveyed, and
 * it costs nothing here because the capture is already listening to the stream it needs.
 *
 * What it cannot cancel is a *bias*. A signal bouncing off the same wall for forty seconds is
 * wrong in the same direction every time, and no number of samples fixes that. Everything below is
 * built for the first problem and makes no claim about the second.
 */

/** How much a reading is trusted, as an inverse-variance weight. See [weightFor]. */
object EpochQuality {

    /**
     * What the receiver was doing when a reading was produced, beyond the accuracy it self-reported.
     *
     * `getAccuracy()` is one scalar and it is known to read optimistically exactly where it matters
     * most — a reflected signal can be tracked cleanly and with low residual, so the chipset is
     * confident about a range that bounced off a building. These fields are the cheap evidence that
     * disagrees with it, all of them already arriving on a callback this capture registers anyway.
     *
     * @param meanCn0DbHz average carrier-to-noise density across the satellites actually used. Weak
     *   signals are the ones most likely to be reflections or to be tracked badly.
     * @param usedInFixCount how many satellites contributed. A solution standing on the minimum is
     *   a solution with no redundancy and no way to notice one bad range.
     * @param dualBandSatellites how many satellites were tracked on a primary *and* a secondary
     *   band, which is what lets the receiver remove ionospheric delay rather than model it. Not a
     *   count of second-band signals: removing the delay means comparing one satellite's range on two
     *   frequencies, so four second-band signals from satellites whose primary band is not in the fix
     *   buy none of it. See [GnssGeometry.summarise].
     * @param speedMetersPerSecond what the receiver thinks the user's speed is. A capture that is
     *   meant to be someone standing still, reporting motion, is reporting a bad epoch — either the
     *   hand moved or the solution is unstable, and both are reasons to trust it less.
     * @param hasSpeed whether that figure was reported at all, kept separate so an absent value is
     *   never read as zero.
     * @param horizontalDop the geometry of the solution, from [GnssGeometry.horizontalDop]. This is
     *   the other multiplicative term in `error ≈ HDOP × UERE`, and everything else on this object
     *   speaks only to UERE. `null` on a device that reports no azimuth or elevation, which falls
     *   the weighting back to [usedInFixCount] exactly as it behaved before geometry was read.
     * @param lowElevationFraction how much of the used constellation is scraping the horizon, where
     *   the signal has crossed the most atmosphere and bounced off the most things.
     * @param isMoving the accelerometer's verdict on whether the phone is being held still, which is
     *   the premise the whole combination rests on. `null` on a device with no accelerometer, which
     *   falls back to [speedMetersPerSecond] — a Doppler estimate whose own noise is the same size
     *   as the threshold applied to it, and the weakest link in the chain until this field existed.
     */
    data class Epoch(
        val meanCn0DbHz: Float = 0f,
        val usedInFixCount: Int = 0,
        val dualBandSatellites: Int = 0,
        val speedMetersPerSecond: Float = 0f,
        val hasSpeed: Boolean = false,
        val horizontalDop: Double? = null,
        val lowElevationFraction: Float = 0f,
        val isMoving: Boolean? = null,
    )

    /**
     * The weight a reading gets in the combination, as `1 / σ²`.
     *
     * σ starts as the receiver's own accuracy and is *inflated* by each thing that argues against
     * it. Only inflated, never reduced: this is a correction for known optimism, and a factor that
     * could make σ smaller would be the app claiming to know better than the chipset about the
     * chipset's own residuals, which it does not.
     *
     * The penalties are deliberately coarse — a fifth to a half. Nothing here is calibrated against
     * measured error (see the measurement protocol that has not been run yet), so a *fitted* curve
     * would be false precision dressed as sophistication. What they have to get right is the
     * *ordering* — a weak, single-band, six-satellite epoch must rank below a strong, dual-band,
     * twelve-satellite one — and for that, coarse is enough.
     *
     * They are nonetheless ramps rather than steps, which is not the same thing as added
     * sophistication and makes no new claim. Each ramp has exactly the two endpoints the constants
     * already assert and interpolates between them instead of jumping. Stepping meant that two
     * epochs seconds apart with mean carrier-to-noise of 34.9 and 35.1 dB-Hz differed in weight by
     * 69% across a boundary at which nothing physical happens, and in a marginal environment — where
     * the mean sits near the threshold and oscillates across it — that injected pure threshold-
     * location noise exactly where the weighting was supposed to help most.
     *
     * Where geometry is available it *replaces* the satellite count rather than ramping it. A count
     * is the wrong variable, not a badly-thresholded one: ten satellites in one half of the sky
     * score as excellent by count and are the worst geometry of the day. See [GnssGeometry].
     *
     * An epoch with no evidence attached comes back with weight `1 / accuracy²`, which is exactly
     * what a plain accuracy-weighted combination would use. That is the honest degradation: a device
     * that reports no satellite detail is not penalised for it, it simply gets no refinement.
     */
    fun weightFor(accuracyMeters: Float, epoch: Epoch): Float {
        if (!accuracyMeters.isFinite() || accuracyMeters <= 0f) return 0f
        var sigma = accuracyMeters
        if (epoch.usedInFixCount > 0) {
            if (epoch.meanCn0DbHz > 0f) {
                sigma *= ramp(
                    value = epoch.meanCn0DbHz,
                    worst = WEAK_SIGNAL_CN0_DB_HZ - CN0_RAMP_HALF_WIDTH_DB,
                    best = WEAK_SIGNAL_CN0_DB_HZ + CN0_RAMP_HALF_WIDTH_DB,
                    worstPenalty = WEAK_SIGNAL_PENALTY,
                )
            }
            val dop = epoch.horizontalDop
            if (dop != null) {
                // The quantity the count was standing in for. Inflation only, normalised so a
                // well-distributed constellation is left alone rather than rewarded, and capped so a
                // pathological geometry cannot drive one epoch's weight to nothing at all.
                sigma *= (dop / GnssGeometry.NOMINAL_HDOP)
                    .coerceIn(1.0, MAX_GEOMETRY_PENALTY.toDouble())
                    .toFloat()
            } else {
                sigma *= ramp(
                    value = epoch.usedInFixCount.toFloat(),
                    worst = GnssGeometry.MINIMUM_SATELLITES_FOR_A_SOLUTION.toFloat(),
                    best = REDUNDANT_SATELLITE_COUNT.toFloat(),
                    worstPenalty = FEW_SATELLITES_PENALTY,
                )
            }
            sigma *= ramp(
                value = epoch.dualBandSatellites.toFloat(),
                worst = 0f,
                best = DUAL_BAND_SATELLITES.toFloat(),
                worstPenalty = SINGLE_BAND_PENALTY,
            )
            // Low satellites have crossed the most atmosphere and bounced off the most things. A
            // fraction rather than a count, because it is the solution's *reliance* on them that
            // matters and four low ones out of twenty is not four out of five.
            sigma *= 1f + epoch.lowElevationFraction.coerceIn(0f, 1f) * (LOW_ELEVATION_PENALTY - 1f)
        }
        // Applied whether or not satellite detail arrived: a capture that is meant to be someone
        // standing still, and is not, is a bad epoch regardless of what the constellation looked
        // like. The accelerometer answers this directly where it exists; the fix's own speed is the
        // fallback, and a poor one — its noise is the same size as the threshold applied to it.
        val moving = epoch.isMoving
            ?: (epoch.hasSpeed && epoch.speedMetersPerSecond > STATIC_SPEED_LIMIT_M_S)
        if (moving) sigma *= MOVING_PENALTY
        return 1f / (sigma * sigma)
    }

    /**
     * A penalty that falls linearly from [worstPenalty] at [worst] to none at [best], flat outside.
     *
     * Two anchor points and a straight line between them. It commits to nothing the stepped form did
     * not already commit to; it just stops pretending the transition happens all at once at a
     * boundary no physical process knows about.
     */
    private fun ramp(value: Float, worst: Float, best: Float, worstPenalty: Float): Float {
        if (best == worst) return if (value < best) worstPenalty else 1f
        val severity = ((best - value) / (best - worst)).coerceIn(0f, 1f)
        return 1f + severity * (worstPenalty - 1f)
    }

    /** Below this, a signal is more likely a reflection or a bad track than a clean range. */
    private const val WEAK_SIGNAL_CN0_DB_HZ = 35f
    private const val WEAK_SIGNAL_PENALTY = 1.3f

    /**
     * The ramp spans 30–40 dB-Hz — the old threshold plus and minus five. Not a measurement: it is
     * the smallest interval that plainly brackets the boundary, so the penalty arrives gradually
     * across the region where a marginal receiver's mean actually wanders.
     */
    private const val CN0_RAMP_HALF_WIDTH_DB = 5f

    /** Enough satellites that one bad range is outvoted rather than absorbed. */
    private const val REDUNDANT_SATELLITE_COUNT = 8
    private const val FEW_SATELLITES_PENALTY = 1.2f

    /**
     * The ceiling on the geometry term. An HDOP of six is a real reading in a canyon and would
     * otherwise inflate σ fivefold, which does not down-rank that epoch so much as delete it — and
     * every other epoch in the same capture is standing under the same sky.
     */
    private const val MAX_GEOMETRY_PENALTY = 2.5f

    /** Applied in full only when every used satellite is below the mask, which is a roof, not a sky. */
    private const val LOW_ELEVATION_PENALTY = 1.3f

    /** Four, matching what a position solution needs, so the second band stands on its own. */
    private const val DUAL_BAND_SATELLITES = 4
    private const val SINGLE_BAND_PENALTY = 1.25f

    /** Half a metre per second is a walk, not a person standing still holding a phone. */
    private const val STATIC_SPEED_LIMIT_M_S = 0.5f
    private const val MOVING_PENALTY = 1.5f
}

/**
 * The separate terms behind one combined fix's accuracy claim, handed out only when a caller asks.
 *
 * The claim itself is a single number — the worst of three — and that is the right thing to save.
 * It is the wrong thing to *tune against*, because every disagreement about this module's constants
 * is really a disagreement about which term should have been binding. Recording the terms is what
 * turns "the claim looks pessimistic" into a question with an answer: which one won, how often, and
 * did the winner predict the error that a repeat capture actually showed.
 *
 * Handed out through a callback rather than by widening the return type, so the shipped call sites
 * are untouched and nothing is computed for a caller that did not ask. Every field is a scalar in
 * metres; none of them is a position.
 */
data class ClaimTerms(
    /** How many samples survived the admission radius and were actually combined. */
    val clusterSize: Int,
    /** How many were offered. The gap between this and [clusterSize] is what admission rejected. */
    val sampleCount: Int,
    val admissionRadiusMeters: Double,
    /** The tightest sample's own self-reported accuracy — the floor the claim can never go below. */
    val receiverMeters: Double,
    /** The weighted 68% radius of the cluster about the combined point. */
    val dispersionMeters: Double,
    /** How wrong the carrier-phase alignment applied to that cluster may itself be. */
    val alignmentMeters: Double,
    /** Spread between the early/middle/late thirds, or `null` when the capture was too short to ask. */
    val driftMeters: Double?,
    val biasInflation: Float,
)

/**
 * Combines the clustered readings into the one position that is saved.
 *
 * ### The admission radius scales with the fix, and used to not
 *
 * [maxRadiusMeters] is a ceiling, not the radius. The radius actually applied is
 * `min(maxRadiusMeters, best.accuracy * 1.5)`, and the fixed 8 m it replaces was itself capping the
 * accuracy of the result: a gate meant to reject outliers has to be small relative to the precision
 * being aimed at, and 8 m is several times it. With five samples, one admitted at the old boundary
 * on the far side of the truth from `best` drags the mean by up to 8/5 ≈ 1.6 m — the gate letting in
 * the error the combination was there to remove. Scaled, a 3 m best admits neighbours out to 4.5 m
 * and a 10 m best out to 8 m, so the gate is always tight relative to what the receiver currently
 * believes it can do.
 *
 * Someone who taps capture and then walks is the other thing this stops: without a radius, the
 * result would be the midpoint of the walk, which is a place they never stood.
 *
 * ### ...and it is no longer measured from the tightest reading
 *
 * The width above and the *centre* the width is measured from are two different questions, and this
 * code answered both with `best` until 2026-08-20. That made the gate circular: the cluster was the
 * set of readings agreeing with one reading, and [ClaimTerms.dispersionMeters] then measured how
 * well that set agreed with itself. A reflected epoch can self-report the tightest accuracy in a
 * pool, and when it did, the minority around it took the centre and the converged majority was
 * discarded — the estimator returning the outliers and calling them precise. Under synthetic
 * contamination at 37% the two centrings differ by 57.82 m against 0.82 m, and mean-versus-median
 * inside the gate makes no difference at all once the centre is captured, because both are then
 * averaging the wrong set.
 *
 * The centre is now the weighted geometric median of *every* sample — the same estimator, and the
 * same 0.5 breakdown, applied one level up — while the width stays scaled to the tightest reading's
 * own accuracy, which is the receiver's statement about how far apart two honest readings should
 * be. Below [MIN_SAMPLES_FOR_GEOMETRIC_MEDIAN] the old centre is kept, because there is no majority
 * to defer to and "is the second reading inside the first one's error bar" is the only question two
 * samples can answer.
 *
 * ### Why a geometric median rather than a mean
 *
 * A single bad reading at distance `D` moves a mean of `n` samples by exactly `D/n` — with a dozen
 * samples and one 8 m multipath outlier, two thirds of a metre of pure error, every time, with no
 * way for the mean to notice. The geometric median is the point minimising total distance to all
 * samples, and half the set has to be wrong before it moves anywhere. One contaminated epoch in
 * twelve leaves it essentially where it was.
 *
 * It is solved by Weiszfeld iteration in a local tangent plane, which is safe here because the whole
 * cluster spans metres. Two degeneracies are handled rather than hoped away: the iterate landing
 * exactly on a sample point, where the update divides by zero and which is smoothed; and failure to
 * converge inside a fixed number of steps, where the best iterate so far is returned instead of
 * looping. Below [MIN_SAMPLES_FOR_GEOMETRIC_MEDIAN] the breakdown property means nothing and the
 * iteration can oscillate between near-coincident points, so a weighted mean carries more of the
 * answer the smaller the cluster is.
 *
 * ### Why the two estimators are blended rather than switched between
 *
 * Switching outright at exactly five samples meant a capture that admitted a fifth sample changed
 * *estimator*, and a mean and a geometric median differ by an amount bounded only by the cluster
 * radius. Two captures at the same spot in the same conditions, one admitting four samples and one
 * five, could return positions metres apart for a reason that was purely about the count — and the
 * count is decided by an admission gate, not by the ground. Blending over [BLEND_FROM_SAMPLES] to
 * [MIN_SAMPLES_FOR_GEOMETRIC_MEDIAN] halves the largest jump and leaves both ends exactly as they
 * were: two or three samples are still a mean, five or more are still a pure geometric median, and
 * the common case — a full pool of twelve — is untouched.
 *
 * ### The reported accuracy describes the result, not the best sample
 *
 * The returned accuracy is `max(best.accuracy, r68)`, where `r68` is the weighted radius containing
 * about 68% of the cluster around the combined position. Deliberately **not** `best.accuracy` alone:
 * that number describes a single point estimate while the position returned is a combination of
 * several, and putting one beside the other is a claim the code cannot support.
 *
 * And deliberately no longer the distance to the *furthest* sample, which is what it used to be.
 * Two things were wrong with that. It is a 100% radius of the cluster set beside `getAccuracy()`,
 * which is documented and behaves as a **68%** radius — two different quantities compared as though
 * they were one, and on a clean twelve-sample cluster with no outlier at all the maximum sits near
 * the 92nd percentile and inflates the saved figure by roughly half again. Worse, a maximum has
 * breakdown point **zero**, so the single admitted multipath sample the geometric median was chosen
 * to ignore was setting the error bar single-handedly: the estimate was robust and the claim
 * describing it was not. Since every cluster point lies within `best.accuracy × 1.5` of `best` and
 * the combination lies inside their hull, that bounded the reported figure at three times what the
 * receiver said — enough to render a 4 m capture as a rough one.
 *
 * And deliberately **not** divided by √n. That reduction assumes independent draws, and GNSS error
 * over a few tens of seconds is not independent: multipath is a static reflection geometry for a
 * stationary receiver, ionospheric delay varies over minutes, and consecutive fixes share almost the
 * same satellites. The errors are heavily autocorrelated, so the true reduction sits somewhere
 * between 1 and √n with no way to measure which from inside the app. Claiming √n would be claiming
 * the best case as though it were the only case.
 *
 * Both choices point the same way: combining improves the *position* and never improves the
 * *claim*. That asymmetry is the whole point, and the correction above sharpens the claim without
 * softening it: `r68` is a dispersion statistic of the same order as the thing it is compared
 * against, it is still floored by the best sample's own figure, and nothing anywhere divides by √n.
 *
 * ### And the bias that no statistic here can see
 *
 * [biasInflation] is the one input that comes from outside the cluster. Everything above treats the
 * scatter *between* samples as the evidence, which is exactly right for random error and blind to a
 * systematic one: a receiver against a building is wrong in the same direction in every epoch, so
 * the samples agree beautifully with each other and with nothing else. [GnssGeometry] can detect
 * that case from the azimuths of the used satellites, and this is where that detection is spent —
 * on making the app report *worse*, correctly. It multiplies the reported accuracy and never the
 * position, because a bias whose size is unknown is a reason to widen a claim and not a correction
 * that can be applied.
 *
 * ### The bias the cluster *can* see, once the capture is long enough
 *
 * There is one systematic error this can catch by itself, and only a long occupation makes it
 * visible: a contaminant that is **contiguous in time**. The breakdown-point argument for the
 * geometric median assumes the bad samples are an arbitrary scattered minority. A car that parks
 * beside the user and stays there for the last two minutes is not scattered — it is the most recent
 * forty percent of the pool, agreeing with itself, and past half the window the median follows it
 * exactly as the worked case above shows a lone outlier cannot. `r68` is silent about this by
 * construction: the contaminated samples agree, so the dispersion is *small*.
 *
 * [interBlockSpreadMeters] is the answer, and it is the one statistic here that is not a rearrangement
 * of the others. Split the cluster into thirds *by arrival time*, take each third's own combination,
 * and measure how far apart those three answers are. A stationary receiver in a stable environment
 * puts them on top of each other; one whose environment changed halfway through separates them, and
 * the separation is a direct measurement of a bias that every within-window statistic is blind to.
 * It is required to be quiet on a short capture — five samples per block minimum — because three
 * blocks of two readings disagree from pure noise and would inflate every capture for nothing.
 *
 * Its reach is bounded, and the bound is worth stating because it was discovered by a test rather
 * than reasoned out in advance. The claim is already floored by the best sample's own accuracy, and
 * the cluster is already bounded by `best.accuracy × 1.5` measured from that sample. So a capture
 * that drifts *away* from its tightest epoch can span at most 1.5 accuracies, putting the spread
 * between its thirds at about one — permanently under the floor, and this term silent. It binds only
 * when the tightest epoch sits inside the wander rather than at its start, where the span reaches
 * roughly twice the accuracy. In plain terms: **this speaks when the capture moved by more than the
 * receiver's own error bar, and stays quiet when it did not.** That is the right threshold, but it
 * is a consequence of two other constants rather than a decision, and if either moves this term's
 * usefulness moves with it.
 *
 * @param biasInflation ≥ 1, from [GnssGeometry.blockageInflation]. `1f` means either an open sky or
 *   a device that reports no azimuths, and both leave the figure exactly as the cluster computed it.
 * @param alignmentUncertaintyMeters how wrong the carrier-phase alignment applied to these samples
 *   may itself be, as the root of the summed squared residuals of the corrections. Zero when no
 *   alignment ran, which is most devices. It is added in quadrature with the dispersion rather than
 *   multiplied, because it is an independent error of the same kind — and it is here at all because
 *   alignment slides the samples together and so *reduces* the dispersion by construction. Without
 *   this term, the single operation that makes the reported number smaller was the only one
 *   contributing nothing to it.
 */
fun combineFixes(
    samples: List<LocationFix>,
    maxRadiusMeters: Double,
    biasInflation: Float = 1f,
    alignmentUncertaintyMeters: Double = 0.0,
    anchorNotBeforeNanos: Long? = null,
    /**
     * How many of the tightest reading's own accuracies wide the admission radius is. Defaults to
     * the shipped 1.5; exists so a field run can compare it against a wider one on the same sky.
     *
     * Every argument for changing it is currently synthetic — in the field-measurement simulations
     * an ungated geometric median beat the gated mean in every contamination regime tested,
     * and where the tight gate was also shown to *shrink the reported dispersion*, making the claim
     * sound better than the readings support. Synthetic evidence is not grounds for moving a
     * constant that decides what every saved coordinate is made of, so the constant has not moved.
     * A parameter is not a change of behaviour; it is what turns an observation into an experiment.
     */
    admissionRadiusMultiple: Double = RADIUS_ACCURACY_MULTIPLE,
    decomposition: ((ClaimTerms) -> Unit)? = null,
): LocationFix? {
    // The reading everything about the *answer* is derived from, and it must come from after the
    // tap whenever the caller has told us when that was.
    //
    // **This is a boundary guard, not the mechanism.** As of the restructuring on 2026-08-20 the
    // shipped provider never puts a pre-tap reading into the pool at all, so the filter below
    // selects everything and changes nothing. It is kept because this is a public function that
    // takes an arbitrary list, because the property it enforces is one a saved coordinate in a
    // password manager should not depend on a caller remembering, and because the construction that
    // makes it redundant is exactly the kind of thing a later change reverses without noticing.
    //
    // A reader looking for what actually keeps pre-tap readings out of a saved answer should look at
    // the seeding block in AndroidCurrentLocationProvider, which does not add them, rather than at
    // this.
    //
    // `best` is not merely the accuracy floor. It is the origin of the local plane, the centre the
    // admission radius is measured from, the scale that radius is computed at, **and** the value
    // returned outright when the cluster turns out too small to combine. In a seeded capture the
    // tightest reading in the pool is very often a pre-tap one — a warm receiver has had longer to
    // converge — and each of those four roles then goes wrong in a different way. The worst is the
    // last: a tight pre-tap reading also *narrows* the radius it is the centre of, which makes a
    // one-sample cluster more likely, and then `return best` hands back that pre-tap reading as the
    // saved answer, untouched, with its own timestamp — past every guard written elsewhere for
    // exactly that, because those guard the combination and this returns before it.
    //
    // Restricting it here fixes all four at once and is a no-op for every ordinary capture, where
    // every sample is post-tap by construction. Pre-tap readings still contribute as pooled samples;
    // they simply do not get to define what the answer is.
    val best = anchorNotBeforeNanos
        ?.let { floor -> samples.filter { it.elapsedRealtimeNanos >= floor } }
        ?.minByOrNull { it.accuracyMeters }
        ?: samples.minByOrNull { it.accuracyMeters }
        ?: return null
    val multiple = admissionRadiusMultiple.takeIf { it.isFinite() && it > 0.0 }
        ?: RADIUS_ACCURACY_MULTIPLE
    val radius = minOf(maxRadiusMeters, best.accuracyMeters.toDouble() * multiple)

    // Metres east and north of the best sample. Every computation below happens in this plane and
    // only the result is converted back, so no trigonometry is repeated per iteration and the
    // cluster spans metres by construction.
    //
    // "By construction" is true of the *cluster*, not of the arithmetic that finds it. A plain
    // `fix.longitude - best.longitude` reads two samples a few metres apart across the antimeridian
    // — 179.9999 and -179.9999 — as ~360 degrees, roughly 40,000 km once projected, so every sample
    // after the first fell outside the admission radius and the combiner silently degraded to
    // returning `best` alone. Wrapping the delta into [-180, 180] is the whole fix; the latitude
    // axis needs none, because latitude does not wrap.
    val metersPerDegreeLatitude = EARTH_RADIUS_METERS * Math.PI / 180.0
    val metersPerDegreeLongitude = metersPerDegreeLatitude * Math.cos(Math.toRadians(best.latitude))
    fun wrapDegrees(delta: Double): Double = when {
        delta > 180.0 -> delta - 360.0
        delta < -180.0 -> delta + 360.0
        else -> delta
    }
    fun project(fix: LocationFix) = doubleArrayOf(
        wrapDegrees(fix.longitude - best.longitude) * metersPerDegreeLongitude,
        (fix.latitude - best.latitude) * metersPerDegreeLatitude,
    )
    // A non-positive or non-finite weight would silently drop a sample from one estimator and not
    // the other. Falling back to the accuracy-only weight keeps every admitted sample in the
    // combination, which is what "admitted" already promised.
    fun weigh(fix: LocationFix): Double {
        val weight = fix.weight
        return if (weight.isFinite() && weight > 0f) weight.toDouble()
        else 1.0 / (fix.accuracyMeters.toDouble() * fix.accuracyMeters.toDouble()).coerceAtLeast(1e-6)
    }

    // ### What the radius is measured *from*, which is not the same question as how wide it is
    //
    // It used to be measured from `best`, and that made the gate circular: the cluster was the set
    // of readings agreeing with one reading, and [ClaimTerms.dispersionMeters] then measured how
    // well that set agreed with itself. Nothing in the loop consulted the readings it had dropped.
    //
    // The failure is not theoretical and it is not symmetric. Measured on the emulator 2026-08-20
    // at 5 m/axis scatter: across eight captures the gate kept a median of 50% of a twelve-reading
    // pool, and in two of them it kept **two and three** — reporting a dispersion of 0.00 m, which
    // is three readings selected for agreeing with one reading agreeing with it. Only the receiver
    // floor stopped the app announcing centimetre precision on three samples. A reflected epoch can
    // self-report the tightest accuracy in a pool, and when it does, the minority around it takes
    // the gate's centre and the converged majority is discarded.
    //
    // So the centre is the weighted geometric median of *every* sample — breakdown 0.5, so it sits
    // with the majority unless more than half the pool has moved — while the radius stays scaled to
    // the tightest reading's own accuracy, which is the receiver's statement about how far apart
    // two honest readings should be. The two roles were only ever conflated for convenience.
    //
    // Below [MIN_SAMPLES_FOR_GEOMETRIC_MEDIAN] there is no majority to defer to and a median over
    // three points says nothing a mean does not, so the old centre is kept: with two readings, "is
    // the second one within the first one's error bar" is the only question available, and the
    // answer must stay `best`.
    val gateCentre = if (samples.size >= MIN_SAMPLES_FOR_GEOMETRIC_MEDIAN) {
        geometricMedian(samples.map(::project), samples.map(::weigh))
    } else {
        doubleArrayOf(0.0, 0.0)
    }
    val cluster = samples.filter { fix ->
        val point = project(fix)
        Math.hypot(point[0] - gateCentre[0], point[1] - gateCentre[1]) <= radius
    }
    if (cluster.size < 2) return best

    val points = cluster.map(::project)
    val weights = cluster.map(::weigh)

    val mean = weightedMean(points, weights)
    val medianShare = ((cluster.size - BLEND_FROM_SAMPLES).toDouble() /
        (MIN_SAMPLES_FOR_GEOMETRIC_MEDIAN - BLEND_FROM_SAMPLES)).coerceIn(0.0, 1.0)
    val combined = when (medianShare) {
        0.0 -> mean
        1.0 -> geometricMedian(points, weights)
        else -> {
            val median = geometricMedian(points, weights)
            doubleArrayOf(
                mean[0] + (median[0] - mean[0]) * medianShare,
                mean[1] + (median[1] - mean[1]) * medianShare,
            )
        }
    }

    val latitude = best.latitude + combined[1] / metersPerDegreeLatitude
    // Wrapped back into [-180, 180] on the way out: a cluster straddling the antimeridian can put
    // the combined point a fraction of a degree past the wrap, and a stored longitude of 180.0001
    // is not a coordinate any map will accept.
    val longitude = wrapDegrees(best.longitude + combined[0] / metersPerDegreeLongitude)
    val dispersion = radius68(points, weights, combined)
    // The identity of the result — its grade and its wall-clock stamp — comes from the tightest
    // *satellite* sample rather than from the tightest sample outright. Without this, a corroborated
    // fused reading that happened to self-report the smallest number would hand its own grade to a
    // position that several satellite epochs contributed to, and the saved fix would describe itself
    // as something it is not. The geometry and the accuracy floor still come from `best`, which is
    // the tightest reading of any kind and is what both of those quantities are about.
    val precise = cluster.filter { it.grade == LocationFixPolicy.ProviderGrade.PRECISION }
    // ...and, where the caller says so, from a reading taken at or after the tap.
    //
    // Without this the identity could be inherited from a *pre-tap* reading, and the saved
    // coordinate would carry a `capturedAt` from before the user pressed anything — the item's
    // timestamp claiming a measurement that predates the request for it. That is precisely what
    // [LocationFixPolicy]'s cached-fix rule refuses, arriving by a route the rule does not watch:
    // seeded warm-up readings are `PRECISION` and one of them can easily be the tightest in the
    // cluster, since a warm receiver has had longer to converge than a capture two seconds old.
    //
    // Only the *identity* is restricted. Every seeded reading still contributes its position and its
    // weight to the combination — the argument for pooling them is untouched, and only the question
    // of which reading the saved fix is stamped with changes.
    val anchor = anchorNotBeforeNanos
        ?.let { floor -> precise.filter { it.elapsedRealtimeNanos >= floor } }
        ?.minByOrNull { it.accuracyMeters }
        ?: precise.minByOrNull { it.accuracyMeters }
        ?: best
    // Three terms, and the claim is the worst of them, never a blend. Each answers a question the
    // others cannot: the receiver's own figure floors it, the within-window dispersion widened by
    // the alignment's own error covers random scatter, and the between-block spread covers a
    // systematic change the window agreed about. Taking a maximum rather than combining them is the
    // same discipline as everywhere else here — only inflated, never reduced.
    val alignment = alignmentUncertaintyMeters.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    val scatter = Math.sqrt(dispersion * dispersion + alignment * alignment)
    val interBlock = interBlockSpreadMeters(cluster, points, weights)
    val drift = interBlock ?: 0.0
    val claim = maxOf(best.accuracyMeters.toDouble(), scatter, drift)
    decomposition?.invoke(
        ClaimTerms(
            clusterSize = cluster.size,
            sampleCount = samples.size,
            admissionRadiusMeters = radius,
            receiverMeters = best.accuracyMeters.toDouble(),
            dispersionMeters = dispersion,
            alignmentMeters = alignment,
            driftMeters = interBlock,
            biasInflation = biasInflation.coerceAtLeast(1f),
        ),
    )
    return anchor.copy(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = claim.toFloat() * biasInflation.coerceAtLeast(1f),
    )
}

/**
 * How far apart the answers from the early, middle and late thirds of a capture are.
 *
 * `null` — meaning "no evidence either way", never "no problem" — whenever the cluster is too short
 * to ask. Three blocks of fewer than [MIN_SAMPLES_PER_BLOCK] readings disagree from ordinary epoch
 * noise, so a short capture would be inflated by a statistic measuring nothing, which is why the
 * ordinary forty-five-second capture never reaches this and the long occupation always does.
 *
 * Blocks are cut by arrival time and are contiguous, because the failure this is looking for is
 * contiguous: an environment that changed partway through. A random partition would spread that
 * change evenly across all three blocks and report agreement.
 *
 * @param cluster the admitted samples, in arbitrary order — sorted here rather than at the call site
 *   so the contract is a property of this function.
 * @param points the same samples as metres east/north, index-aligned with [cluster].
 */
internal fun interBlockSpreadMeters(
    cluster: List<LocationFix>,
    points: List<DoubleArray>,
    weights: List<Double>,
): Double? {
    if (cluster.size < BLOCKS_FOR_DRIFT * MIN_SAMPLES_PER_BLOCK) return null
    val order = cluster.indices.sortedBy { cluster[it].elapsedRealtimeNanos }
    val blockSize = order.size / BLOCKS_FOR_DRIFT
    val centres = (0 until BLOCKS_FOR_DRIFT).map { block ->
        // The last block takes the remainder, so no sample is silently dropped by integer division.
        val from = block * blockSize
        val to = if (block == BLOCKS_FOR_DRIFT - 1) order.size else from + blockSize
        val indices = order.subList(from, to)
        geometricMedian(indices.map { points[it] }, indices.map { weights[it] })
    }
    var widest = 0.0
    for (i in centres.indices) {
        for (j in i + 1 until centres.size) {
            widest = maxOf(widest, Math.hypot(centres[i][0] - centres[j][0], centres[i][1] - centres[j][1]))
        }
    }
    return widest.takeIf { it.isFinite() }
}

/**
 * The weighted radius about [center] containing about 68% of the cluster.
 *
 * Computed as the weighted RMS radius scaled by [RAYLEIGH_68_OVER_RMS] rather than as an empirical
 * quantile. Both were available and the RMS form is the better one here for two reasons: an
 * empirical percentile of twelve points is a coarse, jumpy statistic that moves by a whole sample
 * every time the pool gains one, and the RMS form uses the same inverse-variance weights the
 * position was computed with, so a sample the estimator half-ignored does not get a full vote in the
 * error bar. The scale factor is a closed form for a circular-normal cluster, not a fit: the radial
 * distribution is Rayleigh, its RMS radius is `σ√2` and its 68th percentile is `σ√(2 ln(1/0.32))`,
 * whose ratio is the constant below and depends on nothing measured.
 */
private fun radius68(
    points: List<DoubleArray>,
    weights: List<Double>,
    center: DoubleArray,
): Double {
    var weighted = 0.0
    var total = 0.0
    points.indices.forEach { index ->
        val dx = points[index][0] - center[0]
        val dy = points[index][1] - center[1]
        val weight = weights[index]
        weighted += weight * (dx * dx + dy * dy)
        total += weight
    }
    if (total <= 0.0 || !weighted.isFinite()) {
        return points.maxOfOrNull { Math.hypot(it[0] - center[0], it[1] - center[1]) } ?: 0.0
    }
    return Math.sqrt(weighted / total) * RAYLEIGH_68_OVER_RMS
}

private fun weightedMean(points: List<DoubleArray>, weights: List<Double>): DoubleArray {
    val total = weights.sum()
    if (total <= 0.0) {
        return doubleArrayOf(points.sumOf { it[0] } / points.size, points.sumOf { it[1] } / points.size)
    }
    return doubleArrayOf(
        points.indices.sumOf { points[it][0] * weights[it] } / total,
        points.indices.sumOf { points[it][1] * weights[it] } / total,
    )
}

/**
 * Weiszfeld iteration for the weighted geometric median.
 *
 * Starts from the weighted mean, then repeatedly moves toward whichever samples are nearest,
 * weighting each by `w / distance`. The smoothing floor on that distance is what stops the update
 * exploding when the iterate lands on a sample point — the classical degeneracy, and the one that
 * actually happens when several readings are identical to the metre.
 */
private fun geometricMedian(points: List<DoubleArray>, weights: List<Double>): DoubleArray {
    var current = weightedMean(points, weights)
    repeat(MAX_WEISZFELD_ITERATIONS) {
        var numeratorX = 0.0
        var numeratorY = 0.0
        var denominator = 0.0
        points.indices.forEach { index ->
            val dx = points[index][0] - current[0]
            val dy = points[index][1] - current[1]
            val distance = Math.hypot(dx, dy).coerceAtLeast(COINCIDENT_POINT_EPSILON_METERS)
            val share = weights[index] / distance
            numeratorX += points[index][0] * share
            numeratorY += points[index][1] * share
            denominator += share
        }
        if (denominator <= 0.0) return current
        val next = doubleArrayOf(numeratorX / denominator, numeratorY / denominator)
        val moved = Math.hypot(next[0] - current[0], next[1] - current[1])
        current = next
        // Converged well below anything the receiver could resolve. Returning the best iterate on
        // running out of steps rather than looping is deliberate: a slow-converging cloud still has
        // a usable answer, and it is always closer to the median than the mean it started from.
        if (moved < CONVERGENCE_EPSILON_METERS) return current
    }
    return current
}

/**
 * How good a reading has to be to be worth averaging, given the best this receiver has managed.
 *
 * The same 1.5× the clustering gate applies, one level up, and for the same argument: a gate meant
 * to reject outliers has to be defined relative to the precision currently being achieved, or it is
 * either rejecting the whole cluster or admitting the error it exists to remove. That argument was
 * made here for the *spatial* gate and never carried to the *quality* one, which stayed absolute.
 *
 * Absolute, it disabled the early exit and the averaging together for a whole class of ordinary
 * captures. A receiver plateauing anywhere above [settleAccuracyMeters] — a courtyard, a car park
 * with one open side, a phone at an angle in a pocket — put no sample in the pool at all, so the
 * two-sample precondition on stopping early could never be met and the capture ran to its deadline
 * every time; and with the pool empty the combination had nothing to combine and a single
 * unaveraged epoch was saved instead. Averaging switched itself off exactly where it was most
 * needed.
 *
 * [settleAccuracyMeters] survives as the *floor*, so a receiver doing well keeps a tight pool and
 * only a receiver doing badly widens one. Nothing here can admit a reading
 * [LocationFixPolicy.reject] already refused.
 */
fun samplePoolCeiling(settleAccuracyMeters: Float, bestAccuracyMeters: Float): Float =
    maxOf(settleAccuracyMeters, bestAccuracyMeters * RADIUS_ACCURACY_MULTIPLE.toFloat())

/**
 * How far outside the receiver's own accuracy estimate a neighbouring sample may sit and still be
 * treated as the same place. 1.5× is a judgement, not a measurement: tight enough that the gate
 * cannot admit the error it exists to reject, loose enough that ordinary sample-to-sample scatter
 * — which is roughly the size of the accuracy figure — is not thrown away as an outlier.
 */
private const val RADIUS_ACCURACY_MULTIPLE = 1.5

/** Below five, being resistant to a minority of bad points is a property with nothing to protect. */
private const val MIN_SAMPLES_FOR_GEOMETRIC_MEDIAN = 5

/**
 * Where the geometric median starts being blended in. Three, because two points have no median
 * worth the name, and because spreading the transition over three cluster sizes rather than one
 * leaves both endpoints untouched while halving the largest step the saved coordinate can take.
 */
private const val BLEND_FROM_SAMPLES = 3

/**
 * `√(2 ln(1/0.32)) / √2` — the 68th-percentile radius of a circular-normal cluster over its RMS
 * radius. Closed form, no measurement in it.
 */
private const val RAYLEIGH_68_OVER_RMS = 1.0713

/**
 * Thirds. Two blocks cannot distinguish a drift from a step, and four over an ordinary capture leaves
 * too few readings in each to say anything; three is the smallest split that shows a direction.
 */
private const val BLOCKS_FOR_DRIFT = 3

/**
 * Below this a block's own median is noise, and the spread between three of them measures the noise
 * rather than the environment. Five is what keeps this statistic silent on the short capture, where
 * it would have nothing to report and every opportunity to inflate.
 */
private const val MIN_SAMPLES_PER_BLOCK = 5

private const val MAX_WEISZFELD_ITERATIONS = 64
private const val COINCIDENT_POINT_EPSILON_METERS = 1e-6
private const val CONVERGENCE_EPSILON_METERS = 1e-4

/**
 * Metres between two fixes, by equirectangular approximation.
 *
 * Exact enough by a wide margin: over the tens of metres this ever compares, the error against the
 * haversine formula is far below a millimetre, and the cheaper form has no trigonometric edge case
 * at the poles to reason about. It is a clustering shortcut only — anything measuring true error
 * against a surveyed point needs a proper geodesic.
 */
internal fun distanceMeters(a: LocationFix, b: LocationFix): Double {
    val meanLatRad = Math.toRadians((a.latitude + b.latitude) / 2)
    val dLat = Math.toRadians(a.latitude - b.latitude) * EARTH_RADIUS_METERS
    val dLon = Math.toRadians(a.longitude - b.longitude) * EARTH_RADIUS_METERS * Math.cos(meanLatRad)
    return Math.hypot(dLat, dLon)
}

/**
 * The same fix, moved by a displacement in the local tangent plane.
 *
 * Used to carry a pooled sample forward into the current epoch's frame once [Tdcp] has measured how
 * far the receiver drifted since it was taken. Over the metres this ever moves anything, the
 * equirectangular conversion is exact to well below a millimetre — the same approximation
 * [distanceMeters] justifies, applied in the other direction.
 */
internal fun LocationFix.shiftedBy(eastMeters: Double, northMeters: Double): LocationFix {
    if (eastMeters == 0.0 && northMeters == 0.0) return this
    if (!eastMeters.isFinite() || !northMeters.isFinite()) return this
    val metersPerDegreeLatitude = EARTH_RADIUS_METERS * Math.PI / 180.0
    val metersPerDegreeLongitude = metersPerDegreeLatitude * Math.cos(Math.toRadians(latitude))
    if (metersPerDegreeLongitude == 0.0) return this
    return copy(
        latitude = latitude + northMeters / metersPerDegreeLatitude,
        longitude = longitude + eastMeters / metersPerDegreeLongitude,
    )
}

/**
 * Metres east of [origin], signed. The other half of [distanceMeters], which throws the direction
 * away — and direction is exactly what a trace of a drifting series needs to be readable.
 */
internal fun eastOf(fix: LocationFix, origin: LocationFix): Double {
    val meanLatRad = Math.toRadians((fix.latitude + origin.latitude) / 2)
    return Math.toRadians(fix.longitude - origin.longitude) * EARTH_RADIUS_METERS *
        Math.cos(meanLatRad)
}

/** Metres north of [origin], signed. */
internal fun northOf(fix: LocationFix, origin: LocationFix): Double =
    Math.toRadians(fix.latitude - origin.latitude) * EARTH_RADIUS_METERS

private const val EARTH_RADIUS_METERS = 6_371_000.0

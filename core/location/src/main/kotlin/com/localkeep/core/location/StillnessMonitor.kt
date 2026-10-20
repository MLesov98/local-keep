package com.localkeep.core.location

/**
 * Whether the phone is being held still, from the accelerometer rather than from the fix.
 *
 * The capture's whole averaging premise is that the receiver is not moving: several readings of one
 * place combine into a tighter estimate of that place, and several readings of a walk combine into a
 * point the user never stood on. Until this existed the premise was tested with `location.speed`,
 * which is a Doppler estimate carrying roughly 0.1–0.5 m/s of its own noise — so the half-metre
 * threshold sat *inside* the noise floor of the quantity it was thresholding. A stationary phone
 * crosses it now and then, and a slow shuffle never does.
 *
 * The accelerometer separates the two cases by one to two orders of magnitude instead: a phone held
 * still shows a standard deviation of roughly 0.02–0.1 m/s² about gravity, and walking shows 1–3.
 * [MOVING_SIGMA_METERS_PER_SECOND_SQUARED] sits between those bands and well outside hand tremor;
 * it is a judgement, not a measurement, and the device protocol that would calibrate it is in the
 * review's §5.
 *
 * Two verdicts, deliberately distinct. [isMoving] is the instantaneous one and only downweights an
 * epoch. [hasSustainedMotion] is the one that discards the pool, because a walked epoch is not a
 * noisy reading of the right place — it is a clean reading of a different place, and no weight
 * fixes a sample that is simply somewhere else.
 *
 * Pure, and holding only numbers: the `SensorManager` registration lives with the rest of the
 * platform glue, and every interesting case here is a shape of a series no test could ask a real
 * phone to produce.
 */
class StillnessMonitor(
    private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    private val sustainedNanos: Long = DEFAULT_SUSTAINED_NANOS,
) {

    private val timestamps = ArrayDeque<Long>()
    private val magnitudes = ArrayDeque<Float>()
    private var movingSinceNanos: Long? = null

    /** True while the last window's variation is above the walking threshold. */
    var isMoving: Boolean = false
        private set

    /**
     * True once motion has been continuous for [sustainedNanos].
     *
     * The delay is the false-positive guard: a single jolt as the user shifts their grip is not a
     * reason to throw away ten seconds of converged samples, and a step is not a jolt.
     */
    var hasSustainedMotion: Boolean = false
        private set

    /** One accelerometer reading, as the magnitude of the raw vector — gravity included. */
    fun accept(elapsedRealtimeNanos: Long, magnitudeMetersPerSecondSquared: Float) {
        if (!magnitudeMetersPerSecondSquared.isFinite()) return
        timestamps.addLast(elapsedRealtimeNanos)
        magnitudes.addLast(magnitudeMetersPerSecondSquared)
        val cutoff = elapsedRealtimeNanos - windowNanos
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeFirst()
            magnitudes.removeFirst()
        }
        // Fewer than three readings is not a window, it is two points and a slope — the same
        // argument [GnssConvergence.MIN_SAMPLES_FOR_A_SLOPE] makes about accuracy series. The
        // standing verdict is left alone rather than guessed at.
        if (magnitudes.size < MIN_SAMPLES_FOR_A_VERDICT) return

        val mean = magnitudes.sum() / magnitudes.size
        val variance = magnitudes.sumOf { value ->
            val deviation = (value - mean).toDouble()
            deviation * deviation
        } / magnitudes.size
        isMoving = Math.sqrt(variance) > MOVING_SIGMA_METERS_PER_SECOND_SQUARED

        if (!isMoving) {
            movingSinceNanos = null
            hasSustainedMotion = false
            return
        }
        val since = movingSinceNanos ?: elapsedRealtimeNanos.also { movingSinceNanos = it }
        hasSustainedMotion = elapsedRealtimeNanos - since >= sustainedNanos
    }

    /** Called after the pool has been discarded, so one walk does not discard it on every epoch. */
    fun acknowledgeSustainedMotion() {
        hasSustainedMotion = false
        movingSinceNanos = null
    }

    companion object {
        /**
         * [JUDG], uncalibrated. Between a held phone's 0.02–0.1 m/s² and a walk's 1–3, and chosen
         * nearer the quiet end because the cost of missing a walk (a coordinate at a place nobody
         * stood) is worse than the cost of downweighting a fidget.
         */
        const val MOVING_SIGMA_METERS_PER_SECOND_SQUARED = 0.35

        /** One second of history: long enough to contain a footfall, short enough to react. */
        const val DEFAULT_WINDOW_NANOS = 1_000_000_000L

        /** A second and a half of continuous motion is walking, not a grip change. */
        const val DEFAULT_SUSTAINED_NANOS = 1_500_000_000L

        private const val MIN_SAMPLES_FOR_A_VERDICT = 3
    }
}

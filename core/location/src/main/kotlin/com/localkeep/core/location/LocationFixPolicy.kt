package com.localkeep.core.location

/**
 * Whether a fix the platform handed back is one this app is willing to call "current".
 *
 * A pure function over numbers, with no Android type in its signature, because it is the part of
 * this feature most worth testing and the part hardest to test through `LocationManager`. The
 * Android implementation converts a `Location` into these arguments and asks; nothing else decides.
 *
 * The rule that matters, and the reason a plain age check is not enough:
 *
 * `LocationManager` will hand back a fix the receiver produced *before* the user tapped anything —
 * that is what `getLastKnownLocation` is, and `getCurrentLocation` is documented as being allowed to
 * return a recent one rather than waiting. A ten-second age window would accept it: a fix taken nine
 * seconds ago is nine seconds old whether the tap came before it or after. So the reference point is
 * the *tap*, not now: [fixElapsedRealtimeNanos] must be at or after [requestStartedElapsedNanos].
 * A one-second-old cached fix from before the tap fails, and that is deliberate — the user pressed a
 * button that says "save current location", and the answer has to be a measurement of the moment
 * they pressed it.
 *
 * Both stamps are `SystemClock.elapsedRealtimeNanos`, never wall-clock. Wall-clock moves: an NTP
 * correction or a manual time change mid-capture would make a fresh fix look like it came from
 * tomorrow, or last week.
 */
object LocationFixPolicy {

    /** Why a fix was refused, or null if it was accepted. */
    fun reject(
        request: FixRequest,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        hasAccuracy: Boolean,
        isFromMockProvider: Boolean,
        requestStartedElapsedNanos: Long,
        fixElapsedRealtimeNanos: Long,
        nowElapsedNanos: Long,
    ): LocationFailure? {
        // Release builds refuse a synthetic fix outright. The emulator's default 0,0 is otherwise a
        // perfectly acceptable coordinate by every other rule here, and "Null Island" saved into a
        // user's vault is indistinguishable from a real place they went.
        if (isFromMockProvider && !request.allowMockProvider) return LocationFailure.UNAVAILABLE

        if (!latitude.isFinite() || !longitude.isFinite()) return LocationFailure.UNAVAILABLE
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return LocationFailure.UNAVAILABLE

        // A fix with no accuracy is not a precise fix, whatever its coordinates say. Refused rather
        // than saved with a null, because this app promises the user a measurement and cannot
        // describe one whose error bars are unknown.
        if (!hasAccuracy || accuracyMeters == null) return LocationFailure.UNAVAILABLE
        if (!accuracyMeters.isFinite() || accuracyMeters < 0f) return LocationFailure.UNAVAILABLE
        if (accuracyMeters > request.maxAccuracyMeters) return LocationFailure.UNAVAILABLE

        // The cached-fix rejection. See the class comment: this is why the tap is the reference
        // point and `now` is not.
        if (fixElapsedRealtimeNanos < requestStartedElapsedNanos) return LocationFailure.UNAVAILABLE

        val ageNanos = nowElapsedNanos - fixElapsedRealtimeNanos
        // Negative means the fix claims to be from the future on a monotonic clock, which is not a
        // stale reading — it is a receiver or an emulator producing a stamp that cannot be true.
        if (ageNanos < 0L) return LocationFailure.UNAVAILABLE
        if (ageNanos > request.maxAgeMillis * NANOS_PER_MILLI) return LocationFailure.UNAVAILABLE

        return null
    }

    /** Convenience for the accepted case. */
    fun accepts(
        request: FixRequest,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        hasAccuracy: Boolean,
        isFromMockProvider: Boolean,
        requestStartedElapsedNanos: Long,
        fixElapsedRealtimeNanos: Long,
        nowElapsedNanos: Long,
    ): Boolean = reject(
        request,
        latitude,
        longitude,
        accuracyMeters,
        hasAccuracy,
        isFromMockProvider,
        requestStartedElapsedNanos,
        fixElapsedRealtimeNanos,
        nowElapsedNanos,
    ) == null

    /**
     * Which estimator produced a fix — the distinction [reject] deliberately cannot make.
     *
     * [reject] answers "is this number usable at all", and the answer is the same for every
     * provider: finite, in range, fresh, not synthetic, inside the 100 m floor. What it cannot
     * answer is *what kind of measurement this is*, and that turned out to be the most expensive
     * omission in the feature.
     *
     * The failure it caused: a wifi-derived fix reporting 8 m accuracy satisfies every rule in
     * [reject], so it could become the best fix of a capture, arm the settle countdown, and enter
     * the averaging pool — a network position saved and displayed as though a satellite receiver
     * had converged on it. The self-reported accuracy of a wifi fix describes the database's
     * confidence in an access point's location, which is a different quantity to a GNSS receiver's
     * residual, and there is no bound on how far apart the two can be.
     *
     * So the grade is carried on the fix and the state machine reads it: only [PRECISION] can
     * settle a capture or be averaged. [FALLBACK] still exists, still passes [reject], and is still
     * offered to the user — see [FixRequest.fallbackAfterMillis], which is what keeps this from
     * regressing indoors, where a satellite fix is often never coming at all.
     *
     * Classified by provider name rather than by a capability query because no API reports the
     * lineage of a `Location`. `FUSED_PROVIDER` is graded [FALLBACK] despite usually being
     * GNSS-driven: usually is not a guarantee, it can answer from wifi alone with no way to tell
     * from the outside, and grading it optimistically would leave the same hole in a narrower form.
     *
     * That last sentence conflates two things, and [CORROBORATED] is the separation. *No API reports
     * lineage* is true and will stay true. *There is no way to tell from the outside* is not: every
     * provider is registered at once, so the capture already holds a satellite fix from the same
     * few seconds, and a fused fix can be compared against it. That comparison is not an inference
     * about a provider — it is a measurement checked against another measurement.
     */
    enum class ProviderGrade {
        /** A satellite fix. Only these may settle a capture. */
        PRECISION,

        /** Wifi, cell, or a fusion that may be either. Shown to the user, never averaged. */
        FALLBACK,

        /**
         * A non-satellite fix that a concurrent satellite fix vouches for. See [isCorroborated].
         *
         * Granted exactly one privilege — entry to the averaging pool — and nothing else. It cannot
         * settle a capture, cannot count toward [FixRequest.minSettleSamples], cannot enter the
         * accuracy history the plateau detector reads, and cannot become the fix whose grade is
         * saved. The hole [gradeOf] was built to close therefore stays structurally shut: settling
         * still requires a satellite epoch, and corroboration itself requires a satellite epoch to
         * corroborate *against*, so a capture that has never seen a satellite has nothing here.
         *
         * What it buys is sample count. Two producing providers instead of one is roughly twice the
         * readings reaching the combination, and on the OEM platforms whose fused output is filtered
         * more heavily than raw `gps`, it is also the better measurement — which today's grading
         * discards in favour of the worse one.
         */
        CORROBORATED,
    }

    /**
     * Whether a non-satellite fix agrees with a satellite fix closely enough to be averaged.
     *
     * Both conditions are deliberately strict, and both are about the *satellite* fix rather than
     * about the candidate. It must be recent — a wifi answer and a GNSS answer taken a minute apart
     * are not two readings of one moment — and it must lie inside the satellite fix's own error bar.
     * That second test is the load-bearing one: a fused fix forty metres from the concurrent `gps`
     * fix is a wifi lookup, and the app can *see* that it is, without knowing anything about how the
     * platform produced it. A fix inside the satellite fix's own claimed radius is, by that fix's own
     * account, the same place.
     *
     * Note what is deliberately not here: nothing consults the candidate's self-reported accuracy.
     * That number describes a database's confidence in an access point and is the quantity that
     * caused the original defect; using it to decide whether to trust the fix would reintroduce it.
     */
    fun isCorroborated(
        candidateElapsedNanos: Long,
        referenceElapsedNanos: Long,
        separationMeters: Double,
        referenceAccuracyMeters: Float,
        windowNanos: Long,
    ): Boolean {
        if (!separationMeters.isFinite() || !referenceAccuracyMeters.isFinite()) return false
        if (referenceAccuracyMeters <= 0f) return false
        val age = Math.abs(candidateElapsedNanos - referenceElapsedNanos)
        if (age > windowNanos) return false
        return separationMeters <= referenceAccuracyMeters.toDouble()
    }

    /**
     * The grade of a provider by name.
     *
     * String literals rather than `LocationManager.GPS_PROVIDER`, which would drag an Android type
     * into the one file in this module that has none — the reason it is the only part of the
     * capture with real unit tests. The constants are frozen platform values, not a moving target.
     */
    fun gradeOf(provider: String?): ProviderGrade =
        if (provider == GPS_PROVIDER_NAME) ProviderGrade.PRECISION else ProviderGrade.FALLBACK

    /**
     * Whether the consolation timer firing should *end* the capture, or merely pass.
     *
     * A predicate rather than three conditions inline in a `postDelayed`, because this is the one
     * early ending in the capture that does not go through the early-exit gate, and it silently
     * disagreed with that gate for as long as both existed. A `preciseReading` — whose entire
     * contract is that it does not stop early — was ended at sixty seconds by this timer with a
     * network estimate, measured on a phone with no sky view on 2026-08-20. Pulled out here so the
     * disagreement is a testable statement instead of a branch inside a lambda inside a callback.
     *
     * Waiting costs the user nothing they did not ask for: the deadline path saves the same
     * fallback if no satellite fix ever arrives, so the choice is between this answer now and the
     * same-or-better answer at the deadline. For the ordinary forty-five-second capture, where the
     * user asked for speed, "now" is right and this returns true.
     */
    fun consolationEndsCapture(
        earlyExitAllowed: Boolean,
        hasPrecisionFix: Boolean,
        hasFallback: Boolean,
    ): Boolean = earlyExitAllowed && !hasPrecisionFix && hasFallback

    /**
     * Whether one pre-tap warm-up reading may join a capture's pool.
     *
     * The narrowest possible narrowing of the cached-fix rule at the top of this file, written here
     * rather than inline so it can be argued with and tested rather than read. Four conditions, and
     * each one blocks a distinct way the rule could be broken:
     *
     * - **[separationMeters] within [admissionRadiusMeters]** — the only one that is evidence rather
     *   than bookkeeping. It is a positive demonstration that the receiver was in the same place as
     *   the capture's first satellite fix, and it is what makes the whole thing defensible: a user
     *   who walked while filling in the form produces warm-up readings tens of metres away and every
     *   one of them fails here.
     * - **not newer than the tap** — a reading from after the tap is an ordinary sample and must go
     *   through the ordinary path, not this one.
     * - **not older than [maxAgeMillis] before it** — the staleness bound the tap comparison
     *   provides for post-tap fixes. Without it a reading could sit in the buffer through a long
     *   form-filling session.
     * - **[accuracyMeters] within [settleAccuracyMeters]** — a loose reading is no more welcome for
     *   being early.
     *
     * What this deliberately does *not* do is decide what may be **saved**. [reject] is untouched
     * and still refuses every pre-tap fix outright; this decides only what may be *averaged*, and
     * the capture separately requires its settle floor to be met by post-tap readings.
     */
    fun admitsWarmUpFix(
        separationMeters: Double,
        admissionRadiusMeters: Double,
        accuracyMeters: Float,
        settleAccuracyMeters: Float,
        fixElapsedRealtimeNanos: Long,
        firstPrecisionElapsedNanos: Long,
        maxAgeMillis: Long,
    ): Boolean {
        if (!separationMeters.isFinite() || separationMeters > admissionRadiusMeters) return false
        if (!accuracyMeters.isFinite() || accuracyMeters > settleAccuracyMeters) return false
        if (fixElapsedRealtimeNanos > firstPrecisionElapsedNanos) return false
        val ageNanos = firstPrecisionElapsedNanos - fixElapsedRealtimeNanos
        return ageNanos <= maxAgeMillis * NANOS_PER_MILLI
    }

    const val NANOS_PER_MILLI: Long = 1_000_000L

    /** `LocationManager.GPS_PROVIDER`, by value. See [gradeOf]. */
    const val GPS_PROVIDER_NAME: String = "gps"
}

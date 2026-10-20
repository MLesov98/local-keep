package com.localkeep.core.location

import java.time.Instant

/**
 * One GNSS fix, taken because the user asked for it.
 *
 * Not [com.localkeep.core.model.Coordinate]: this is the transient thing a capture produces, and it
 * lives outside the model on purpose. A fix that is never saved must never have been a vault type,
 * or the boundary between "held for a few seconds in a ViewModel" and "encrypted on disk" stops
 * being visible in the code.
 *
 * [capturedAt] is wall-clock, for display. [elapsedRealtimeNanos] is the monotonic stamp the
 * freshness rule is decided on — see [LocationFixPolicy], which explains why the two cannot be the
 * same number. [grade] is what kind of measurement this is, which decides whether it may settle a
 * capture or be combined at all.
 *
 * [weight] is how much this reading is trusted relative to its neighbours — see
 * [EpochQuality.weightFor]. It defaults to 1 so a fix built without satellite detail combines
 * exactly as it would have before the weighting existed, rather than being penalised for what the
 * device did not report.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val capturedAt: Instant,
    val accuracyMeters: Float,
    val elapsedRealtimeNanos: Long,
    val grade: LocationFixPolicy.ProviderGrade = LocationFixPolicy.ProviderGrade.PRECISION,
    val weight: Float = 1f,
) {
    /**
     * Redacted for the same reason [com.localkeep.core.model.Coordinate.toString] is, and arguably a
     * more urgent one: this value passes through a coroutine, a `Result`, a ViewModel state object
     * and whatever a failure path decides to log, all before anything has decided to keep it.
     */
    override fun toString(): String = "LocationFix(redacted)"
}

/**
 * Why a capture produced no fix.
 *
 * Structured rather than a message, because this module has no business resolving strings and a
 * ViewModel has no business holding them. Each case is a different sentence *and* a different way
 * out for the user, which is why they are not collapsed into one "failed".
 */
enum class LocationFailure {
    /** Foreground location was refused, or was never granted. */
    PERMISSION_DENIED,

    /**
     * Android 12+ granted approximate access when precise was asked for.
     *
     * Its own case, not a flavour of [PERMISSION_DENIED]: the user said yes. Saving the area-level
     * value they consented to would be the app quietly relabelling "roughly here" as a coordinate,
     * which is the one outcome this feature must not produce.
     */
    APPROXIMATE_ONLY,

    /** Location is switched off, or the GPS provider is not enabled. */
    PROVIDER_DISABLED,

    /** The provider exists, was asked, and produced nothing usable — a null fix, or an unusable one. */
    UNAVAILABLE,

    /** No acceptable fix inside [FixRequest.timeoutMillis]. Indoors, mostly. */
    TIMED_OUT,

    /** The editor went away, the vault locked, or the user asked again. Never shown as an error. */
    CANCELLED,
}

/** What a capture returned. Structured outcomes, never user-facing text. */
sealed interface FixOutcome {
    data class Success(val fix: LocationFix) : FixOutcome
    data class Failure(val reason: LocationFailure) : FixOutcome
}

/**
 * The knobs a capture is run with. Defaults are the shipped contract; tests vary them.
 *
 * @param timeoutMillis the whole window, and the number the progress ring is drawn against. 45 s,
 *   raised from the plan's proposed 20 s after the first real device run on 2026-08-19: 20 s is
 *   enough for a warm receiver with assistance data, and not enough for a cold GNSS start with the
 *   phone's internet off — Android downloads ephemeris over the network, and without it a first fix
 *   takes minutes rather than seconds. The plan called 20 s "subject to device testing"; this is
 *   that measurement. The app itself needs no network for any of it: `foss` has no INTERNET
 *   permission, so what changes offline is the *system's* assistance data, not anything here.
 *
 *   Reaching it is now an ordinary ending rather than a failure. A capture that has been tracking
 *   satellites for forty-five seconds has a good answer; refusing to save it because a plateau was
 *   never formally detected would be discarding the reading the user waited for.
 * @param minTrackingMillis how long the receiver must have been tracking before *any* early exit is
 *   allowed. This is the floor under the whole convergence idea: a solution that looks good at eight
 *   seconds usually is not, and the accuracy figure it reports at that point is the filter's
 *   optimism rather than its convergence.
 *
 *   Measured from the first satellite epoch, not from the tap — the two are the same thing only on a
 *   receiver that answers instantly. Twelve rather than the original twenty because that change made
 *   the number mean what this paragraph says: anchored to the tap it was satisfied by the *first*
 *   fix of any cold start and cost a warm one ten seconds of pure waiting, so it was simultaneously
 *   too weak where it mattered and too strong where it did not. Twelve seconds of genuine tracking
 *   is a stronger guarantee than twenty seconds of wall clock ever was, and it sits above the ~8 s
 *   floor below which [GnssConvergence.hasPlateaued] cannot fire at all.
 * @param plateauWindowMillis how far back to look when asking whether the receiver is still
 *   improving. See [GnssConvergence.hasPlateaued] — the question this replaced was "has accuracy
 *   crossed a line", and that question stops a capture at roughly the earliest moment waiting was
 *   still worthwhile.
 * @param plateauDeltaMeters how little improvement over that window counts as none. Half a metre:
 *   below that the next window will not buy the user anything they could notice, and above it the
 *   curve is still falling and the capture is stopping early for no reason.
 * @param settleAccuracyMeters how good a satellite fix has to be before it is worth averaging. Not
 *   a stopping rule any more — it is the admission gate on the sample pool, keeping the wide early
 *   fixes a receiver emits while it is still locking out of the cluster it eventually settles into.
 *
 *   The *floor* under that gate rather than the whole of it. The gate actually applied is
 *   `max(settleAccuracyMeters, best × 1.5)`, so a receiver that never gets inside ten metres still
 *   builds a pool from its own best readings. Absolute, this number silently disabled both the early
 *   exit and the averaging for every capture that plateaued above it — see the admission gate in
 *   `AndroidCurrentLocationProvider`, which explains what that cost.
 * @param targetAccuracyMeters good enough to stop early, once [minTrackingMillis] has passed and the
 *   band gate is satisfied. 3 m is what a modern dual-frequency (L1+L5) phone reaches outdoors with
 *   a clear view, and nobody is served by spending another twenty seconds improving on it. Indoors
 *   it is unreachable by any phone, and the UI says so rather than letting the user wait for it.
 * @param minSettleSamples how many satellite samples must agree before an early exit is allowed.
 *   Two, not one: [targetAccuracyMeters] is a *self-reported* figure from a single epoch, and a
 *   momentarily favourable satellite geometry produces one that reverts on the next reading. Ending
 *   the capture on it means saving an unaveraged sample and calling it converged — which contradicts
 *   the existence of [averageSamples] a few lines down.
 * @param minDualBandSatellites how many satellites must be tracked on both bands before
 *   an early exit is allowed. Four, matching the minimum a position solution needs, so the gate is
 *   asking whether the dual-frequency solution is actually standing on its own. See
 *   [GnssConvergence.secondBandReady] for why a device that reports no frequencies at all passes
 *   this rather than failing it.
 * @param averageSamples how many of the best satellite fixes to average into the saved position. A
 *   stationary receiver's error is largely random between samples, so the mean of several is
 *   materially tighter than any one of them — the standard way to survey a fixed point, and free
 *   here because the capture is already listening. Twelve rather than the original five now that
 *   the window is long enough to produce them and the admission radius is tight enough that more
 *   samples sharpen the cluster instead of diluting it.
 *
 *   Worth knowing when reading any error claim built on this number: twelve consecutive one-second
 *   epochs are not twelve independent draws. GNSS error splits into a receiver-noise term that
 *   decorrelates in about a second and slowly-varying terms — multipath geometry, residual
 *   ionospheric and tropospheric delay, orbit and clock error — whose decorrelation times run to
 *   minutes. Averaging cancels the first and sees a single draw of the second, so the effective
 *   sample size is of order two to four. That is the strongest available argument for the √n refusal
 *   in [combineFixes], and the reason no reduction of that kind is applied under any other name: the
 *   number to use is not measurable from inside this app today.
 * @param averageRadiusMeters the *ceiling* on how far apart two samples may be and still be treated
 *   as the same place. The radius actually applied scales with the receiver's own accuracy estimate
 *   — see [averageFixes], which explains why a fixed radius was capping the result.
 * @param maxAccuracyMeters the worst horizontal accuracy this feature will call a coordinate — the
 *   floor accepted when the window runs out, not the thing it aims for.
 * @param maxAgeMillis how old an otherwise acceptable fix may be at the moment it is accepted. This
 *   bounds the gap between "the receiver produced it" and "we took it", and is *not* what stops a
 *   cached fix being used — see [LocationFixPolicy].
 * @param fallbackAfterMillis how long a capture waits for a satellite before a wifi or cell fix is
 *   allowed to end it. This is the pressure valve on [LocationFixPolicy.ProviderGrade]: without it,
 *   barring non-satellite fixes from settling would mean an indoor capture waits the full window
 *   and returns nothing, which is exactly the behaviour a device run already rejected as unusable.
 *   With it, GNSS gets the first thirty seconds uncontested and the network answer is the
 *   consolation prize rather than the default. A network fix that ends a capture this way reports
 *   its own accuracy, so the user is told it is a rough reading rather than shown one dressed as a
 *   converged satellite fix.
 * @param allowMockProvider whether a synthetic fix (`emu geo fix`, a mock provider app) counts.
 *   True only in debug/instrumented builds; a release build must reject one, or the emulator's
 *   0,0 becomes a coordinate a user could save.
 * @param earlyExitAllowed whether the capture may end before [timeoutMillis] because it looks
 *   converged. True for the ordinary capture, where the user is waiting and a settled series means
 *   the wait has stopped buying anything.
 *
 *   False for [preciseReading], and the reason is the single most important sentence in this file.
 *   Every early-exit gate is a judgement about the *receiver's own accuracy estimate* — a formal
 *   covariance that flattens once the filter has enough epochs, typically inside half a minute. The
 *   quantity a long occupation actually improves is not that number: it is the error left after
 *   averaging out slowly-varying terms whose decorrelation times run to minutes, and which the
 *   receiver's estimate never sees at all. Those two curves flatten at wildly different times, so
 *   letting the plateau detector end a five-minute capture would stop it at roughly ninety seconds
 *   and deliver a reading that is not measurably better than a forty-five-second one. If the user
 *   asked for a long occupation, the length *is* the mechanism, and nothing may shortcut it except
 *   the user themselves.
 */
data class FixRequest(
    val timeoutMillis: Long = 45_000L,
    val minTrackingMillis: Long = 12_000L,
    val plateauWindowMillis: Long = 8_000L,
    val plateauDeltaMeters: Float = 0.5f,
    val settleAccuracyMeters: Float = 10f,
    val targetAccuracyMeters: Float = 3f,
    val minSettleSamples: Int = 2,
    val minDualBandSatellites: Int = 4,
    val averageSamples: Int = 12,
    val averageRadiusMeters: Double = 8.0,
    val maxAccuracyMeters: Float = 100f,
    val maxAgeMillis: Long = 10_000L,
    val fallbackAfterMillis: Long = 30_000L,
    val allowMockProvider: Boolean = false,
    val earlyExitAllowed: Boolean = true,
    /**
     * Whether fixes the receiver produced during warm-up, before the tap, may join the averaging
     * pool. **Off by default, and off in the shipped wiring** — this exists to be measured, not to
     * be assumed.
     *
     * The case for it. Warm-up runs for as long as the coordinate field is on screen, delivering
     * fixes to a listener that discards them unread; at the tap the pool is empty and the capture
     * spends its tracking floor re-measuring a place it was already measuring. Somebody standing
     * still filling in a form has produced twenty perfectly good readings of exactly the point they
     * are about to save, and every one of them is thrown away.
     *
     * The case against it, which is why the default is off. [LocationFixPolicy] refuses any fix
     * older than the tap, deliberately: `getLastKnownLocation` will hand back a fix from an hour ago
     * and another city, and "save current location" has to mean the moment the button was pressed.
     * That rule is right about provenance and this flag does not weaken it — a warm-up fix is
     * admitted only if it lands **inside the admission radius of the first post-tap satellite fix**,
     * which is a positive proof that the receiver was in the same place, not merely a claim that the
     * fix was recent. A user who walked while filling in the form produces warm-up fixes tens of
     * metres away and every one of them fails that gate.
     *
     * What is left after that gate is a residual bias, not a wrong answer: someone who drifted two
     * metres contributes samples from two metres away, exactly as they would by drifting two metres
     * *during* an ordinary capture — an exposure this module already accepts and already mitigates
     * with carrier-phase alignment. Whether the speed it buys is worth that is a measurement, and
     * the field measurements are where it gets made. The harness runs it both ways.
     */
    val poolWarmUpFixes: Boolean = false,
    /**
     * How wide the averaging pool's admission gate is, as a multiple of the tightest reading's own
     * accuracy. `null` keeps the combiner's shipped 1.5.
     *
     * Present so a field run can put the gate itself on trial. Three synthetic results point the
     * same way — the gate discards half the readings, a geometric median with no gate beats it under
     * every contamination level tested, and the subset it keeps is the one that *agrees*, so the
     * dispersion it reports is smaller than the readings actually span and the claim comes out more
     * confident than the data supports. None of that is field evidence, and the shipped default is
     * unchanged because of it.
     */
    val admissionRadiusMultiple: Double? = null,
) {
    companion object {

        /**
         * The opt-in long occupation: stand still for five minutes and average the whole of it.
         *
         * ### Why length buys anything, and the part of the story that is not true
         *
         * A stationary receiver's horizontal error is not one quantity. It is a fast term —
         * receiver noise and the tracking loops — that decorrelates in about a second, and slow
         * terms that do not. Averaging removes the fast term almost immediately; twelve consecutive
         * one-second epochs are close to a *single* draw of the slow one, which is why
         * [averageSamples] carries the warning it does and why [combineFixes] refuses to divide by
         * √n.
         *
         * The tempting next sentence — that over five minutes the satellites move, the reflection
         * paths change and the bias averages out too — is **mostly false**, and it was written here
         * before it was checked. Multipath is a function of the satellite-to-reflector-to-antenna
         * geometry, and that geometry repeats on a *sidereal day*: it is why sidereal filtering
         * (subtracting yesterday's residual at the same sidereal time) is the standard multipath
         * mitigation in precise GPS, and it means that within one occupation the multipath
         * contribution is close to a slowly-drifting bias rather than something an average removes.
         * Standing still for five minutes does not fix standing next to a wall, and nothing in this
         * app does.
         *
         * What is left is still worth having, and it is measured rather than argued. Zangenehnejad
         * et al. (GPS Solutions, 2019) occupied static points with standalone code-only smartphone
         * positioning — the same regime as this capture, no corrections, no network — and report
         * σ ≈ 4.1 m north / 2.7 m east / 7.0 m up over a **ten-minute** session, with a
         * twenty-minute session giving **no improvement at all** over the ten. So the curve for this
         * exact use case falls steeply early, flattens by about ten minutes, and is done. Forty-five
         * seconds sits on the steep front of it; five minutes takes most of what is available.
         *
         * Five rather than ten is a deliberate trade of the last part of the curve against five more
         * minutes of a person standing still holding a phone, and it is the assumption in this file
         * most worth revisiting with a real measurement. The Stop control ends the capture at any
         * moment, so the cost of being wrong in the long direction is bounded by the user's patience
         * rather than by this constant.
         *
         * ### The three parameters that make it real rather than decorative
         *
         * [averageSamples] is the load-bearing one. The pool is a ring: `while (samples.size >
         * averageSamples) samples.removeFirst()`. At the shipped twelve, a five-minute capture
         * averages the last twelve seconds of it and throws four minutes away — the capture would
         * be five minutes long and no more accurate than a short one, which is worse than not
         * offering it. Four hundred holds a 1 Hz stream for the whole window with headroom, and
         * costs a few tens of kilobytes.
         *
         * [earlyExitAllowed] is `false`, for the reason written on the parameter itself.
         *
         * [plateauWindowMillis] is widened because it now only feeds the *label* on screen. Over a
         * five-minute series, "settled" judged against eight seconds would be true almost from the
         * start and would tell the user their remaining four minutes are pointless — which is the
         * opposite of what this mode is for.
         *
         * ### What it does not claim
         *
         * The accuracy figure this returns is still a dispersion radius and still is not divided by
         * √n. A longer capture makes the *position* better; whether it lets the app report a
         * smaller number honestly depends on how correlated the series turns out to be, which
         * nothing here has measured on real hardware yet. Until it has, the reported figure stays
         * conservative and the improvement is real but unclaimed. Widening a claim on the strength
         * of an argument is exactly the move this module refuses everywhere else.
         */
        fun preciseReading(allowMockProvider: Boolean = false): FixRequest = FixRequest(
            timeoutMillis = PRECISE_TIMEOUT_MILLIS,
            // Never reached: with the early exit off, nothing consults the tracking floor. Kept
            // consistent with the window rather than left at twelve, so a future reader who wires
            // an exit back in does not inherit a floor that silently permits one at twelve seconds.
            minTrackingMillis = PRECISE_TIMEOUT_MILLIS,
            plateauWindowMillis = 20_000L,
            // Enough for the whole window at 1 Hz, with room for a receiver that reports faster.
            averageSamples = 400,
            // A network estimate still cannot settle this capture; it may only be the consolation
            // if no satellite ever answers. Later than the ordinary thirty seconds because a user
            // who asked for five minutes has said what they are willing to wait for.
            fallbackAfterMillis = 60_000L,
            allowMockProvider = allowMockProvider,
            earlyExitAllowed = false,
        )

        /** Five minutes. Past this the curve is flat enough that the wait is the larger cost. */
        const val PRECISE_TIMEOUT_MILLIS = 300_000L
    }
}

/**
 * How a running capture is behaving, as far as anyone deciding whether to wait needs to know.
 *
 * Deliberately not a number. The user's question is never "how many metres" — it is "is this still
 * getting better, or is this it?", and that is a judgement about the *series*, which no single
 * reading can answer. [GnssConvergence.hasPlateaued] already computes it to decide when to stop;
 * this is the same answer surfaced instead of discarded, so the screen can say the thing the user
 * is actually weighing.
 */
enum class CaptureConvergence {
    /** No satellite fix has arrived. Whatever is on screen came from wifi or cell, or from nothing. */
    SEARCHING,

    /**
     * A satellite fix exists and the series is still falling.
     *
     * Also the honest answer while a network fallback is the only reading: the capture has not
     * finished looking, so "this is as good as it gets" would be a claim the code cannot make yet.
     */
    IMPROVING,

    /** A satellite fix exists and the last window bought nothing. Waiting longer will not either. */
    SETTLED,
}

/**
 * Takes one fresh fix, or says why it could not.
 *
 * Suspending and cancellable: cancelling the coroutine must cancel the platform request and remove
 * whatever listener it registered, in every terminal path. The caller owns the question of *which*
 * capture is still wanted (generation, item identity, unlock epoch); this owns only the platform.
 */
fun interface CurrentLocationProvider {
    /**
     * @param onInterim called on the main thread each time a *better* acceptable fix arrives, before
     *   the capture finishes. This is what lets the UI show the accuracy tightening instead of
     *   presenting a spinner and then a number. Without it the user has no way to tell "still 60 m,
     *   nothing is improving, take it" from "45 m and falling, wait a moment" — and indoors those
     *   two situations look identical and want opposite decisions.
     * @param onConvergence called on the main thread whenever the answer to "is this still getting
     *   better" *changes*. A separate callback rather than a field on [LocationFix], because the
     *   moment that matters most is the one where nothing arrives: indoors the series flattens and
     *   then the receiver simply stops producing better fixes, so the settle would never be
     *   published if it rode along with an improvement. Fired on change only — a listener that had
     *   to deduplicate a per-epoch stream would be reimplementing this here.
     */
    suspend fun currentFix(
        request: FixRequest,
        onInterim: (LocationFix) -> Unit,
        onConvergence: (CaptureConvergence) -> Unit,
    ): FixOutcome

    /**
     * Hold the receiver awake without taking a reading, until cancelled or until [timeoutMillis].
     *
     * Total capture time is `time-to-first-fix + convergence`. Every stopping rule in this module
     * attacks the second term; the first is the larger and far more variable one, and **no amount of
     * concurrency inside the app touches it** — a receiver with no valid ephemeris must demodulate it
     * from the satellite signal at fifty bits per second, which is tens of seconds of physics. A
     * network download is the only shortcut and this app has refused it deliberately (see the
     * `checkNoLocationAiding` note in `AndroidCurrentLocationProvider`). What is left is to have
     * already paid that cost before the user asks: hot, 1–2 s; warm, 5–10 s; cold, 30–60 s.
     *
     * **This produces no result and cannot.** Every fix it receives is discarded unread, and the
     * freshness rule is untouched: `startedAt` is still taken at the tap, and
     * [LocationFixPolicy.reject] still refuses anything stamped before it. A warm receiver is not a
     * cached position — it is a warm oscillator and a valid ephemeris set, which is a property of
     * the hardware and not an answer about where anyone is.
     *
     * Costs, both real. The GNSS engine draws far more than this comment used to claim: Karki and
     * Won isolated it with a Monsoon power monitor across two phones and measured **232 mJ per
     * position update on a single-frequency device and 318 mJ on a dual-frequency one**, which at
     * 1 Hz is 232–318 mW — six to ten times the "30–60 mW" that stood here until it was checked
     * (arXiv:1910.13041, § IV). Half a minute of warm-up is therefore about 9 J, or 2.5 mWh; against
     * a phone battery of order 15 Wh that is under two hundredths of a percent, so the conclusion
     * the wrong number supported happens to survive being corrected. It is written out because a
     * plausible invented figure that reaches the right answer is the most durable kind of error.
     * And the system's
     * location indicator appears while it runs, which in an app of this kind is the more serious of
     * the two: it has to be true that the user is looking at something whose whole purpose is a
     * location, or this is the app locating them unasked. The caller owns that judgement.
     *
     * Default no-op, so a provider that has nothing to warm — a fake, a test double — inherits the
     * behaviour that existed before this did, and the interface stays single-abstract-method.
     */
    suspend fun warmUp(
        timeoutMillis: Long,
        allowMockProvider: Boolean = false,
        /**
         * Whether to keep the readings the receiver produces while warming, for a later capture
         * that asks for them via [FixRequest.poolWarmUpFixes].
         *
         * **Off by default, and off in the shipped wiring**, which is the point rather than a
         * detail: with `poolWarmUpFixes` also off, nothing in the app would ever read the buffer,
         * and an unread buffer of positions accumulating in a password manager for thirty seconds
         * every time a coordinate field appears is a cost with no benefit attached. Off here means
         * the shipped build behaves exactly as it did before any of this existed.
         */
        retainFixes: Boolean = false,
    ) = Unit
}

/** The ordinary call: default knobs, no interest in the intermediate fixes. */
suspend fun CurrentLocationProvider.currentFix(request: FixRequest = FixRequest()): FixOutcome =
    currentFix(request, onInterim = {}, onConvergence = {})

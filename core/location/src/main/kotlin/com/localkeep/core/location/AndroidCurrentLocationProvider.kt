package com.localkeep.core.location

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.GnssMeasurement
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * The platform half of a capture: one GNSS fix from `LocationManager`, or a structured reason there
 * was none.
 *
 * Uses the framework provider rather than Google Play services deliberately. The `foss` flavour's
 * whole claim is that nothing in it reaches Google, and `play-services-location` is already on
 * `checkNoNetworkingDeps`' blocklist — the Play flavour drags that artifact in transitively through
 * billing, which is exactly why this module must be provably independent of it rather than merely
 * intended to be. This is an architectural constraint of the product, not a claim that the framework
 * provider is more accurate.
 *
 * Providers are named explicitly rather than resolved through `getBestProvider`. "Best" is a
 * selection abstraction that moves between Android versions, and this capture has its own opinion
 * about which answer is worth what — see [LocationFixPolicy.ProviderGrade].
 *
 * ### What is deliberately not here
 *
 * `LocationManager.sendExtraCommand` is the obvious lever for a slow cold start, and it is not
 * used. The commands that would help ("force_xtra_injection" and the PSDS equivalents) ask the
 * *platform* to download predicted ephemeris — the app opens no socket, holds no INTERNET
 * permission, and nothing in the merged manifest changes, so no existing guardrail would notice.
 * It would still be a capture causing network traffic on the user's behalf, which is what the
 * no-network claim actually means. "delete_aiding_data" is worse in the other direction: it forces
 * a cold start by discarding a valid almanac, making time-to-first-fix far longer.
 *
 * A future reader will rediscover this API, so `checkNoLocationAiding` fails the build on any call
 * site rather than leaving the decision to whoever reads this comment.
 */
class AndroidCurrentLocationProvider(
    private val context: Context,
    private val clock: MonotonicClock = MonotonicClock.System,
    /**
     * Where to send a record of what each capture did, or `null` to record nothing.
     *
     * Null by default and null in the shipped wiring. Every number in this module's tuning is
     * arithmetic or judgement rather than measurement, and [CaptureTrace] is the instrument that
     * would change that — but it is opt-in by construction rather than by a flag, so a release build
     * has no branch that could accidentally become true. See [CaptureTrace] for why it holds no
     * coordinates.
     */
    private val trace: CaptureTrace.Sink? = null,
) : CurrentLocationProvider {

    /**
     * The first satellite fix this instance ever saw, held only so that separate captures can be
     * differenced against each other. Never written anywhere, and never set at all without a sink.
     *
     * Instance-scoped rather than capture-scoped on purpose: a capture's own origin makes its
     * internal series readable and makes two captures incomparable, and comparing two captures of
     * one spot is the only way to measure error without a surveyed mark. See
     * [CaptureTrace.resultEastMeters], which is also where the privacy cost of the longer-lived
     * anchor is set out.
     */
    private var seriesAnchor: LocationFix? = null

    /**
     * When the receiver last began warming, and when that warming stopped — so a capture can record
     * how long the hardware had been tracking before the tap.
     *
     * Recorded rather than acted on. The tracking floor is currently counted from the capture's own
     * first satellite epoch, which means a receiver that has been tracking continuously for thirty
     * seconds behind an open coordinate field still pays twelve more before it may settle. If the
     * filter converged during the warm-up, that is twelve seconds of pure waiting on the one screen
     * where the user is standing outside holding a phone — and if it did not, the floor is doing
     * exactly its job. Nothing in this module currently knows which, and this is what lets a field
     * run answer it: warm and cold captures, same place, same minute, compared on how quickly each
     * reached its own final answer.
     */
    private var warmingStartedAtNanos: Long? = null
    private var warmingEndedAtNanos: Long? = null

    /**
     * Satellite fixes the receiver produced while warming, kept for a capture that asks for them.
     *
     * Bounded, because a coordinate field can stay open indefinitely and this must not become a
     * growing record of where the phone has been. Oldest are dropped first: the useful ones are the
     * recent ones, and a fix from four minutes ago is the exact thing
     * [LocationFixPolicy]'s cached-fix rule exists to refuse.
     *
     * Main looper only, like everything else here — `warmUp` registers its listener on
     * `Looper.getMainLooper()` and the capture reads this from the same looper, which is why there
     * is no synchronisation and must not be any.
     */
    private val warmUpFixes = ArrayDeque<LocationFix>()

    override suspend fun currentFix(
        request: FixRequest,
        onInterim: (LocationFix) -> Unit,
        onConvergence: (CaptureConvergence) -> Unit,
    ): FixOutcome {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return FixOutcome.Failure(LocationFailure.UNAVAILABLE)

        // A capture that will never seed has no business leaving a buffer of positions behind it,
        // and in the shipped configuration that is every capture — which makes this the line that
        // actually bounds how long `warmUpFixes` lives in practice.
        if (!request.poolWarmUpFixes) warmUpFixes.clear()

        val fine = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        // Separated because the two are different sentences and different ways out. Android 12+ can
        // grant coarse when both were requested, and the user experienced that as saying yes — so
        // telling them "permission denied" would be false, and silently saving the area-level value
        // would be worse.
        if (!fine) {
            return FixOutcome.Failure(
                if (coarse) LocationFailure.APPROXIMATE_ONLY else LocationFailure.PERMISSION_DENIED,
            )
        }

        val providers = manager.usableProviders()
        if (providers.isEmpty()) return FixOutcome.Failure(LocationFailure.PROVIDER_DISABLED)

        // Taken before anything is requested. This is the reference point the freshness rule is
        // measured against, and a stamp read after the provider answered would accept the very
        // cached fix the rule exists to reject. See [LocationFixPolicy].
        val startedAt = clock.elapsedRealtimeNanos()

        return try {
            withTimeout(request.timeoutMillis + DEADLINE_GRACE_MILLIS) {
                awaitBestFix(manager, providers, request, startedAt, onInterim, onConvergence)
            }
        } catch (_: TimeoutCancellationException) {
            // A backstop, not the deadline. The window itself closes inside `awaitBestFix`, which
            // publishes whatever it has; reaching here means that scheduled ending did not run at
            // all — a wedged main looper — and the honest answer for a capture that produced no
            // reading is the same one an empty window gives.
            FixOutcome.Failure(LocationFailure.TIMED_OUT)
        } catch (_: SecurityException) {
            // The permission was revoked between the check above and the call. Rare, and the honest
            // answer is the same one the check gives.
            FixOutcome.Failure(LocationFailure.PERMISSION_DENIED)
        }
    }

    /**
     * Listens for the whole window and keeps the *best* fix, rather than taking the first usable one.
     *
     * This is the shape the feature actually needs, and getting it wrong is what the first device
     * run caught. A GNSS receiver does not produce one answer: it produces a stream that starts
     * coarse and tightens as it locks more satellites. `getCurrentLocation` — which this used, on
     * API 30+ — hands back the *first* of those, and with a 100 m acceptance bound that meant a
     * 47 m first fix was saved and the refinements arriving a few seconds later were thrown away.
     * Measured on a real phone 2026-08-19; the saved pin sat about 40 m from where the user was
     * standing, which for "where I parked" is the wrong side of a building.
     *
     * ### Why it now stops on a slope rather than a threshold
     *
     * The first correction to that defect was to keep listening and stop once accuracy crossed a
     * line. Better, and still the wrong question — see [GnssConvergence.hasPlateaued]. Crossing a
     * line says the curve reached a height, not that it stopped falling, and a GNSS solution keeps
     * falling for tens of seconds after it first looks good. So a capture now ends early only when
     * all of these hold at once:
     *
     *  - it has been tracking for at least [FixRequest.minTrackingMillis];
     *  - at least [FixRequest.minSettleSamples] satellite samples agree, so the decision is never
     *    made on one lucky epoch;
     *  - the second-band gate is satisfied or unavailable ([GnssConvergence.secondBandReady]), so a
     *    capture does not settle on the single-frequency solution seconds before the dual-frequency
     *    one arrives; and
     *  - either the accuracy has plateaued, or it is already inside
     *    [FixRequest.targetAccuracyMeters] and there is nothing left worth waiting for.
     *
     * ### And why the deadline is an ordinary ending
     *
     * Everything above is an *early* exit. The deadline is the normal one, and reaching it publishes
     * the best cluster rather than failing — a capture that tracked satellites for forty-five
     * seconds has an answer, and discarding it because no plateau was formally detected would throw
     * away exactly the reading the user waited for. It is saved with its own accuracy attached, so a
     * mediocre one is *labelled* mediocre rather than hidden. The only path that stores nothing is
     * the one where nothing usable ever arrived at all.
     *
     * [FixRequest.fallbackAfterMillis] covers the third case: no satellite fix at all, which is what
     * indoors looks like. Thirty seconds in, the best network estimate ends the capture instead.
     *
     * Every candidate still goes through the full policy — same freshness rule, same rejection of a
     * cached or synthetic fix — and then through [LocationFixPolicy.gradeOf], which is what keeps a
     * wifi estimate out of the average no matter how confident it sounds.
     */
    // Lint cannot see the guard: `currentFix` refuses before this is reached, and the check is
    // behind an extension function on Context, which its dataflow does not follow. The permission is
    // also caught as a SecurityException there, which is the second remedy lint's own message names.
    // Suppressed here rather than annotated @RequiresPermission, which would push the requirement
    // out through the interface onto every caller and make the ViewModel — which deliberately knows
    // nothing about permissions — the place it has to be silenced instead.
    @SuppressLint("MissingPermission")
    private suspend fun awaitBestFix(
        manager: LocationManager,
        providers: List<String>,
        request: FixRequest,
        startedAt: Long,
        onInterim: (LocationFix) -> Unit,
        onConvergence: (CaptureConvergence) -> Unit,
    ): FixOutcome = suspendCancellableCoroutine { continuation ->
        // The best satellite fix, and separately the best of everything else. Two variables rather
        // than one because they are not comparable: a wifi estimate reporting 8 m and a GNSS fix
        // reporting 8 m are different claims about different things, and letting the smaller number
        // win was the defect that let a network position be saved as a converged satellite one.
        var best: LocationFix? = null
        var fallback: LocationFix? = null
        // The tightest satellite samples seen, newest last, for the stationary average. A ring
        // bounded by [FixRequest.averageSamples]; what actually keeps an early acquiring sample out
        // is the admission ceiling below, which is re-applied to the whole pool every epoch.
        //
        // The bound is therefore a memory bound and a *policy* one, and the two want opposite
        // values. For the ordinary capture, twelve is the policy: the window is short enough that
        // the ring never reaches back past the cluster. For [FixRequest.preciseReading] it must
        // span the entire occupation, because a ring of twelve over five minutes averages the last
        // twelve seconds and discards the four minutes that were the point of waiting.
        val samples = ArrayDeque<LocationFix>()
        // Every satellite accuracy reading with its arrival time, which is what the plateau test
        // reads. Deliberately not the same list as `samples`: that one is filtered to what is worth
        // averaging, and a shape can only be seen in the unfiltered series.
        val accuracyHistory = mutableListOf<GnssConvergence.AccuracySample>()
        val handler = android.os.Handler(Looper.getMainLooper())

        // Everything from here down is main-looper-only, and it is worth saying why nothing has been
        // moved off it, because the arithmetic looks heavier than it is and a future reader will be
        // tempted. Per epoch, at 1 Hz: two windowed medians over at most a few dozen floats, a 4x4
        // inversion for the geometry, and a second one for the carrier phase. That is order tens of
        // microseconds against a sixteen-millisecond frame.
        //
        // Weiszfeld is the one that now runs per epoch rather than once per capture, because the
        // interim has to be the combination — see the call below for why. Its worst case is the
        // long occupation: sixty-four iterations over a four-hundred-sample pool. That is bounded
        // rather than assumed; `FixCombinerTest` measures it and fails if it leaves the budget a
        // 1 Hz callback can afford.
        //
        // The cost of moving any of it is not the dispatch — it is that `samples`, `best`,
        // `accuracyHistory` and every counter below become shared state and need a lock, and the
        // invariant that makes this whole block readable stops being true. Measured against a gain
        // of zero, that is a bad trade. It was considered and rejected deliberately.
        //
        // Second-band state, written by the status callback and read by the settle test. Both run on
        // the main looper, which is why neither needs synchronisation and why that is worth saying
        // out loud rather than leaving to be rediscovered.
        var anyCarrierFrequencyReported = false
        var dualBandSatellitesInFix = 0
        // The rest of what the status stream says about an epoch, kept so a reading can be weighted
        // by more than the one optimistic scalar the fix carries. Written and read on the main
        // looper, like everything else here.
        var epochQuality = EpochQuality.Epoch()
        // The widest empty wedge of sky between two used satellites, which is the only evidence in
        // this capture about *bias* rather than scatter. Read once at the end, to widen the claim.
        var widestAzimuthGapDegrees: Double? = null
        // When the receiver first produced a satellite fix, as opposed to when the user tapped. The
        // tracking floor is measured from here — see [mayStopEarly].
        var firstPrecisionNanos: Long? = null
        // The most second-band signals ever seen contributing, and when that number last rose. Both
        // feed the armed-gate give-up test; a rise resets the clock, so the gate only stands down
        // while the constellation is demonstrably delivering nothing new.
        var secondBandPeak = 0
        var secondBandPeakAtNanos = clock.elapsedRealtimeNanos()
        // When the series was first judged flat, cleared whenever it stops being flat. The other
        // half of the same give-up test.
        var plateauSinceNanos: Long? = null
        // Azimuth and elevation per satellite, keyed so the measurement stream can join against it.
        // Written by the status callback, read by the measurement callback, both on the main looper.
        var skyByKey: Map<Int, GnssGeometry.SkyPosition> = emptyMap()
        // Carrier-phase state. `phaseObservations` is the previous epoch, `phaseUsable` is the
        // availability verdict — many chipsets report ADR_STATE_UNKNOWN forever and that is an
        // ordinary outcome, not a fault — and the cumulative pair is how far the receiver has
        // drifted since the pool was started, which is what decides when alignment stops applying.
        var phaseObservations: List<Tdcp.Observation> = emptyList()
        var phaseUsable = true
        var phaseSatellitesSeen = 0
        // The first satellite fix of the capture, kept only as the origin every traced offset is
        // measured from. Never written anywhere.
        var traceOrigin: LocationFix? = null
        var cumulativeEastMeters = 0.0
        var cumulativeNorthMeters = 0.0
        // The summed squared residual of every alignment correction currently applied to the pool.
        // Its square root is how wrong the alignment itself may be, which widens the saved claim —
        // see where it accumulates for why leaving it out made the one operation that tightens the
        // cluster the one operation absent from the number describing it.
        var alignmentVarianceMeters2 = 0.0
        // The trace, built only when something asked for one. `traceEpochs` stays empty and every
        // line below is a null check away from doing nothing at all in the shipped configuration.
        val traceEpochs = if (trace != null) ArrayList<CaptureTrace.Epoch>() else null
        // What the last alignment did, held for one epoch so the trace can record it beside the
        // reading it was applied to. Cleared after each read, so an epoch with no alignment records
        // a null rather than inheriting the previous epoch's correction.
        var pendingAlignmentShift: Double? = null
        var pendingAlignmentResidual: Double? = null
        var lastClaimTerms: ClaimTerms? = null
        // The raw-stream census. Counters rather than retained measurements, so a five-minute
        // occupation at roughly thirty signals per second costs the same as a one-second one.
        // Every one of these is dead weight in the shipped configuration, where `trace` is null and
        // the block that increments them is never entered.
        // How many warm-up fixes this capture took, and how many it was offered. Both, because the
        // gap between them is the whole safety argument: a capture that was offered twenty and took
        // twenty tells a very different story from one that was offered twenty and took three.
        var warmUpFixesTrusted = 0
        var oldestTrustedWarmUpNanos: Long? = null
        // How many readings the ring cap threw away over the whole capture.
        //
        // Recorded rather than tuned. `averageSamples` is twelve, and a seeded capture is offered
        // more than that before its first post-tap epoch has finished — measured at fifteen admitted
        // against a cap of twelve — so three readings of the right place are discarded at the moment
        // they arrive. Whether that costs anything depends on how correlated they are, which is a
        // question about multipath decorrelation over tens of seconds and is exactly the kind of
        // thing this project has been guessing at. The counter is here so the field run can answer
        // it; the constant is not being moved on the strength of the arithmetic.
        var poolCapEvictions = 0
        // The oldest seeded reading, or null when nothing was seeded. See `mayStopEarly`.
        var trackingFloorAnchorNanos: Long? = null
        var warmUpFixesOffered = 0
        var poolResetsFromDrift = 0
        var poolResetsFromMotion = 0
        var censusEvents = 0
        var censusMeasurements = 0
        var censusAdrValid = 0
        var censusAdrCycleSlip = 0
        var censusAdrReset = 0
        var censusAdrUnusable = 0
        var censusMultipathDetected = 0
        var censusMultipathNotDetected = 0
        var censusMultipathUnknown = 0
        var censusBasebandCn0 = 0
        var censusAdrUncertaintySum = 0.0
        var censusAdrUncertaintyCount = 0
        var censusRateUncertaintySum = 0.0
        var censusRateUncertaintyCount = 0
        var censusSvTimeUncertaintySum = 0.0
        var censusSvTimeUncertaintyCount = 0
        var combinedFromPool = false
        var traceEmitted = false
        // What the screen is told, and the last thing it was told. Held here rather than derived on
        // the far side because the inputs — the accuracy series, the request's own window and delta
        // — all live in this scope and none of them is worth exporting just so somebody else can ask
        // the same question again. Main looper only, like everything else in this block.
        var convergence = CaptureConvergence.SEARCHING

        val bandWatch = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                // One entry per *signal*, which is not one entry per satellite — see
                // [GnssGeometry.summarise], which is where that distinction is enforced and where
                // the three separate defects it used to cause are written out.
                val signals = ArrayList<GnssGeometry.SignalReport>(status.satelliteCount)
                val keyed = HashMap<Int, GnssGeometry.SkyPosition>(status.satelliteCount)
                for (index in 0 until status.satelliteCount) {
                    // `usedInFix` matters more than visibility: a satellite the receiver can hear but
                    // is not using contributes nothing to the position and must not count toward a
                    // gate that claims the dual-frequency solution is standing on its own — nor
                    // toward the geometry of a solution it is not part of.
                    if (!status.usedInFix(index)) continue
                    // Constellation as well as satellite number: a GPS SV 12 and a Galileo SV 12 are
                    // different satellites, and treating them as one — whether when differencing
                    // carrier phase or when counting bands — produces a confident answer that is
                    // nonsense.
                    val key = status.getConstellationType(index) * CONSTELLATION_KEY_STRIDE +
                        status.getSvid(index)
                    // Azimuth and elevation are the platform's own orbit computation handed back.
                    // Reading them needs no ephemeris, no network and no new API level — they have
                    // been on this object since API 24 and were simply never looked at.
                    val position = GnssGeometry.SkyPosition(
                        azimuthDegrees = status.getAzimuthDegrees(index),
                        elevationDegrees = status.getElevationDegrees(index),
                    )
                    keyed[key] = position
                    signals += GnssGeometry.SignalReport(
                        satelliteKey = key,
                        sky = position,
                        // No `has` accessor exists for this one, unlike the carrier frequency, so
                        // an implausible value is the only signal that it was not really measured.
                        cn0DbHz = status.getCn0DbHz(index),
                        carrierFrequencyHz = if (status.hasCarrierFrequencyHz(index)) {
                            status.getCarrierFrequencyHz(index)
                        } else {
                            null
                        },
                    )
                }
                val summary = GnssGeometry.summarise(signals)
                // Latched, never cleared. One reported frequency proves the device can answer the
                // question, and a later epoch where nothing happens to report one is a gap in the
                // data rather than evidence the capability vanished.
                if (summary.anyCarrierFrequencyReported) anyCarrierFrequencyReported = true
                dualBandSatellitesInFix = summary.dualBandSatellites
                if (summary.dualBandSatellites > secondBandPeak) {
                    secondBandPeak = summary.dualBandSatellites
                    secondBandPeakAtNanos = clock.elapsedRealtimeNanos()
                }
                // Latched the same way and for the same reason: a device that answered the geometry
                // question once can answer it, and an epoch that happens not to is a gap.
                GnssGeometry.widestAzimuthGapDegrees(summary.satellites)?.let {
                    widestAzimuthGapDegrees = it
                }
                epochQuality = EpochQuality.Epoch(
                    meanCn0DbHz = summary.meanCn0DbHz,
                    usedInFixCount = summary.satellites.size,
                    dualBandSatellites = summary.dualBandSatellites,
                    horizontalDop = GnssGeometry.horizontalDop(summary.satellites),
                    lowElevationFraction = GnssGeometry.lowElevationFraction(summary.satellites),
                )
                skyByKey = keyed
            }
        }

        // Registered to hold the receiver in full-tracking mode — that alone is worth the
        // registration, and was the entire reason for it — and now also read, because the epochs
        // arriving on it carry the integrated carrier phase. See [Tdcp] for what that can and
        // cannot produce; in short, never an absolute position, but centimetre-level displacement
        // between one second and the next, which is the only instrument in this capture that can
        // see a user drifting slowly.
        fun countRawStream(event: GnssMeasurementsEvent) {
            censusEvents++
            for (measurement in event.measurements) {
                censusMeasurements++
                val state = measurement.accumulatedDeltaRangeState
                val adrValid = state and GnssMeasurement.ADR_STATE_VALID != 0
                when {
                    !adrValid -> censusAdrUnusable++
                    state and GnssMeasurement.ADR_STATE_CYCLE_SLIP != 0 -> censusAdrCycleSlip++
                    state and GnssMeasurement.ADR_STATE_RESET != 0 -> censusAdrReset++
                    else -> censusAdrValid++
                }
                when (measurement.multipathIndicator) {
                    GnssMeasurement.MULTIPATH_INDICATOR_DETECTED -> censusMultipathDetected++
                    GnssMeasurement.MULTIPATH_INDICATOR_NOT_DETECTED -> censusMultipathNotDetected++
                    else -> censusMultipathUnknown++
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    measurement.hasBasebandCn0DbHz()
                ) {
                    censusBasebandCn0++
                }
                // Gated on ADR validity, not merely on being a finite positive number. An invalid
                // ADR carries a sentinel uncertainty rather than a missing one — the emulator
                // returned 4.9e37 on 2026-08-20, which is finite, positive, and meaningless, and it
                // dragged the mean this census exists to report into nonsense. A quantity that is
                // only defined when a flag is set has to be read behind that flag.
                if (adrValid) {
                    measurement.accumulatedDeltaRangeUncertaintyMeters.let {
                        if (it.isFinite() && it > 0.0 && it < ADR_UNCERTAINTY_SANITY_METERS) {
                            censusAdrUncertaintySum += it
                            censusAdrUncertaintyCount++
                        }
                    }
                }
                measurement.pseudorangeRateUncertaintyMetersPerSecond.let {
                    if (it.isFinite() && it > 0.0) {
                        censusRateUncertaintySum += it
                        censusRateUncertaintyCount++
                    }
                }
                measurement.receivedSvTimeUncertaintyNanos.let {
                    if (it > 0L) {
                        censusSvTimeUncertaintySum += it.toDouble()
                        censusSvTimeUncertaintyCount++
                    }
                }
            }
        }

        val fullTracking = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                // Counted before the phase check, deliberately. A device with no usable carrier
                // phase returns here on every event, and it is precisely that device whose stream
                // needs describing — the whole point of the census is to find out what is available
                // when the thing this callback was read for is not.
                // Both counters live inside countRawStream, so they are trace-gated like the rest
                // of the census (opt-in by construction — see the `trace` KDoc above), not counted
                // unconditionally: a release build (trace == null) never runs this line at all.
                // There is no separate "empty-sky" early exit either — CaptureTrace.Exit.EMPTY below
                // is recorded on a plain full-timeout ending.
                if (trace != null) countRawStream(event)
                if (!phaseUsable) return
                val observations = event.measurements.mapNotNull { measurement ->
                    val state = measurement.accumulatedDeltaRangeState
                    // All three flags matter and they mean different things. Without VALID the
                    // number is not a phase measurement at all; RESET and CYCLE_SLIP each mean the
                    // integer ambiguity changed between epochs, which is exactly the thing the
                    // difference relies on *not* having happened.
                    if (state and GnssMeasurement.ADR_STATE_VALID == 0) return@mapNotNull null
                    if (state and GnssMeasurement.ADR_STATE_CYCLE_SLIP != 0) return@mapNotNull null
                    if (state and GnssMeasurement.ADR_STATE_RESET != 0) return@mapNotNull null
                    val key = measurement.constellationType * CONSTELLATION_KEY_STRIDE +
                        measurement.svid
                    val sky = skyByKey[key] ?: return@mapNotNull null
                    Tdcp.Observation(
                        satelliteKey = key,
                        accumulatedDeltaRangeMeters = measurement.accumulatedDeltaRangeMeters,
                        sky = sky,
                    )
                }
                phaseSatellitesSeen = maxOf(phaseSatellitesSeen, observations.size)

                // The availability census, run once and inline. A large fraction of consumer devices
                // report ADR_STATE_UNKNOWN on every measurement forever, and holding anything open
                // waiting for carrier phase on one of them is the failure the second-band gate's
                // unavailable-versus-unmet design already avoids. So the path switches itself off
                // and never runs again for this capture; [StillnessMonitor] is the fallback and it
                // is exactly what ran before this existed.
                if (clock.elapsedRealtimeNanos() - startedAt > PHASE_CENSUS_NANOS &&
                    phaseSatellitesSeen < GnssGeometry.MINIMUM_SATELLITES_FOR_A_SOLUTION
                ) {
                    phaseUsable = false
                    phaseObservations = emptyList()
                    return
                }

                val previous = phaseObservations
                phaseObservations = observations
                val moved = Tdcp.displacementBetween(previous, observations) ?: return

                cumulativeEastMeters += moved.eastMeters
                cumulativeNorthMeters += moved.northMeters
                if (!Tdcp.alignmentStillApplies(
                        Math.hypot(cumulativeEastMeters, cumulativeNorthMeters),
                    )
                ) {
                    // Past the drift budget the user has moved rather than drifted, and a pool of
                    // readings from where they used to be is not a noisy measurement of where they
                    // are. Discard it and start again from here, exactly as sustained motion does.
                    samples.clear()
                    cumulativeEastMeters = 0.0
                    cumulativeNorthMeters = 0.0
                    alignmentVarianceMeters2 = 0.0
                    trackingFloorAnchorNanos = null
                    poolResetsFromDrift++
                    return
                }
                // The correction's own error, carried rather than discarded — and this is a fix to
                // a real defect, not a refinement.
                //
                // Alignment *by construction* tightens the cluster it is applied to: it slides every
                // pooled sample onto the current epoch's frame, and the accuracy this capture
                // reports is the dispersion of exactly those shifted points. So the one operation
                // that makes the number smaller was the one operation contributing nothing to it.
                // A user genuinely drifting a couple of metres, under per-epoch phase noise sitting
                // near the residual gate, got a cluster that looked like a converged repeated
                // measurement and a saved figure that agreed — a statement about the alignment's
                // confidence wearing the position's name.
                //
                // Each solve's residual is independent of its neighbours' (different epoch pair,
                // different noise draw), so the variances add and the budget is a random walk. It is
                // reset wherever the pool is, because a correction cannot be wrong about samples
                // that no longer exist.
                alignmentVarianceMeters2 += moved.residualMeters * moved.residualMeters
                if (traceEpochs != null) {
                    pendingAlignmentShift = Math.hypot(moved.eastMeters, moved.northMeters)
                    pendingAlignmentResidual = moved.residualMeters
                }
                // Carry every pooled sample forward into this epoch's frame. A slowly drifting
                // series then becomes a repeated measurement of one place instead of a smear, which
                // is the whole accuracy argument for reading this stream at all.
                for (index in samples.indices) {
                    samples[index] = samples[index].shiftedBy(moved.eastMeters, moved.northMeters)
                }
            }
        }

        // Whether the phone is being held still, which is the premise the averaging rests on and
        // which the fix's own Doppler speed cannot actually answer — see [StillnessMonitor]. No
        // permission is involved at any API level, and a device with no accelerometer leaves
        // `stillness` null, which falls the weighting back to exactly what it did before.
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = runCatching { sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
            .getOrNull()
        val stillness = if (accelerometer != null) StillnessMonitor() else null
        val motionWatch = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val monitor = stillness ?: return
                val values = event.values ?: return
                if (values.size < 3) return
                // Raw magnitude, gravity included. The monitor looks at how much it *varies*, and
                // subtracting a gravity estimate would add a filter's own lag to a question that is
                // answered perfectly well without one.
                val magnitude = Math.sqrt(
                    (values[0].toDouble() * values[0] +
                        values[1].toDouble() * values[1] +
                        values[2].toDouble() * values[2]),
                ).toFloat()
                monitor.accept(event.timestamp, magnitude)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // A leaked sensor listener is the same class of bug the full-tracking teardown warns about,
        // so it is released in all four terminal paths below rather than in the three that share a
        // helper. Cheap to call twice; expensive to miss once.
        val releaseMotionWatch = { runCatching { sensors?.unregisterListener(motionWatch) }; Unit }

        val listener = object : LocationListener {

            /**
             * The position actually saved: the clustered good samples combined, or the single best
             * fix when there is only one. See [combineFixes] — a stationary receiver's error is
             * largely random between samples, so a combination of several is tighter than any of
             * them, and a geometric median stays tight even when one of them is a reflection.
             */
            fun combine(): LocationFix? = combineFixes(
                samples = samples.toList(),
                maxRadiusMeters = request.averageRadiusMeters,
                // The cluster's own scatter cannot see a one-sided sky: against a building every
                // epoch is wrong in the same direction, so the samples agree with each other and
                // with nothing else. This is the only term that widens the claim for a reason
                // the samples themselves do not contain.
                biasInflation = GnssGeometry.blockageInflation(widestAzimuthGapDegrees),
                alignmentUncertaintyMeters = Math.sqrt(alignmentVarianceMeters2),
                // The saved fix's identity — its timestamp and its grade — must come from a reading
                // taken after the user asked, whatever else is in the pool.
                anchorNotBeforeNanos = startedAt,
                admissionRadiusMultiple = request.admissionRadiusMultiple
                    ?: RADIUS_ACCURACY_MULTIPLE_DEFAULT,
                decomposition = if (trace != null) ({ terms -> lastClaimTerms = terms }) else null,
            )

            fun settled(): LocationFix {
                val combined = combine()
                // Recorded rather than inferred: a capture that reaches here with an empty pool
                // saved one unaveraged epoch, and that used to be common enough to be the largest
                // defect in the module. Whether it still happens is a question for the trace.
                // Written only on a terminal path, so an interim combination cannot leave a stale
                // answer behind on a capture that ends without ever reaching this.
                combinedFromPool = combined != null
                return combined ?: best!!
            }

            /**
             * The frame to express a saved answer in, latching it from this answer if nothing has
             * yet.
             *
             * Latched here rather than only on the satellite path because a capture that never got
             * a satellite fix still saved *something*, and a run of those is a measurable thing: how
             * far apart do the platform's own fallback estimates for one unmoving phone land? That
             * is a real question about a real user scenario — saving a location indoors — and with
             * the anchor latching only on a satellite epoch, every such run recorded `null` and the
             * question could not be asked at all.
             *
             * Grade does not matter to an origin. It is an arbitrary unwritten point either way.
             */
            fun anchoredAgainst(saved: LocationFix?): LocationFix? {
                if (saved == null) return null
                seriesAnchor?.let { return it }
                seriesAnchor = saved
                return saved
            }

            /** One record of the whole capture, emitted once, on whichever path ended it. */
            fun emitTrace(exit: CaptureTrace.Exit, saved: LocationFix?) {
                val sink = trace ?: return
                if (traceEmitted) return
                traceEmitted = true
                sink.record(
                    CaptureTrace(
                        timeToFirstPrecisionMillis = firstPrecisionNanos
                            ?.let { (it - startedAt) / LocationFixPolicy.NANOS_PER_MILLI },
                        durationMillis = (clock.elapsedRealtimeNanos() - startedAt) /
                            LocationFixPolicy.NANOS_PER_MILLI,
                        exit = exit,
                        poolSizeAtExit = samples.size,
                        precisionSamplesAtExit = samples.count {
                            it.grade == LocationFixPolicy.ProviderGrade.PRECISION
                        },
                        combinedFromPool = combinedFromPool,
                        reportedAccuracyMeters = saved?.accuracyMeters,
                        widestAzimuthGapDegrees = widestAzimuthGapDegrees,
                        carrierPhaseUsable = phaseUsable,
                        resultEastMeters = anchoredAgainst(saved)?.let { eastOf(saved!!, it) },
                        resultNorthMeters = anchoredAgainst(saved)?.let { northOf(saved!!, it) },
                        resultEastFromCaptureOriginMeters = traceOrigin?.let { origin ->
                            saved?.let { eastOf(it, origin) }
                        },
                        resultNorthFromCaptureOriginMeters = traceOrigin?.let { origin ->
                            saved?.let { northOf(it, origin) }
                        },
                        alignmentUncertaintyMeters = Math.sqrt(alignmentVarianceMeters2),
                        claim = lastClaimTerms.takeIf { combinedFromPool },
                        warmMillisBeforeCapture = warmMillisBefore(startedAt),
                        warmUpFixesOffered = warmUpFixesOffered,
                        warmUpFixesTrusted = warmUpFixesTrusted,
                        poolCapEvictions = poolCapEvictions,
                        poolResetsFromDrift = poolResetsFromDrift,
                        poolResetsFromMotion = poolResetsFromMotion,
                        rawStream = CaptureTrace.RawStreamCensus(
                            events = censusEvents,
                            measurements = censusMeasurements,
                            adrValid = censusAdrValid,
                            adrCycleSlip = censusAdrCycleSlip,
                            adrReset = censusAdrReset,
                            adrUnusable = censusAdrUnusable,
                            multipathDetected = censusMultipathDetected,
                            multipathNotDetected = censusMultipathNotDetected,
                            multipathUnknown = censusMultipathUnknown,
                            basebandCn0Reported = censusBasebandCn0,
                            meanAdrUncertaintyMeters = censusAdrUncertaintySum
                                .takeIf { censusAdrUncertaintyCount > 0 }
                                ?.div(censusAdrUncertaintyCount),
                            meanPseudorangeRateUncertaintyMps = censusRateUncertaintySum
                                .takeIf { censusRateUncertaintyCount > 0 }
                                ?.div(censusRateUncertaintyCount),
                            meanReceivedSvTimeUncertaintyNanos = censusSvTimeUncertaintySum
                                .takeIf { censusSvTimeUncertaintyCount > 0 }
                                ?.div(censusSvTimeUncertaintyCount),
                        ),
                        epochs = traceEpochs?.toList().orEmpty(),
                    ),
                )
            }

            /**
             * End the capture with the network estimate, from either of the two paths that reach
             * that ending — the timer, and the empty-sky verdict.
             *
             * Shared rather than duplicated because it is the one terminal path that does *not*
             * clear the handler's callbacks, and a second copy of a teardown with a deliberate
             * omission in it is how the omission stops being deliberate.
             */
            fun finishWithFallback(consolation: LocationFix) {
                emitTrace(CaptureTrace.Exit.CONSOLATION, consolation)
                runCatching { manager.removeUpdates(this) }
                runCatching { manager.unregisterGnssStatusCallback(bandWatch) }
                runCatching { manager.unregisterGnssMeasurementsCallback(fullTracking) }
                releaseMotionWatch()
                continuation.resumeOnce(FixOutcome.Success(consolation))
            }

            fun finish(fix: LocationFix, exit: CaptureTrace.Exit) {
                emitTrace(exit, fix)
                // Guarded like every other unregistration here, and for a sharper reason: this
                // runs inside a `Handler`-posted callback, outside the suspend function's own
                // stack, so a throw is not caught by the enclosing `withTimeout` — it reaches the
                // main thread's default handler. An unregistration that fails must not take the
                // rest of the teardown, and the continuation resume, down with it.
                runCatching { manager.removeUpdates(this) }
                runCatching { manager.unregisterGnssStatusCallback(bandWatch) }
                runCatching { manager.unregisterGnssMeasurementsCallback(fullTracking) }
                releaseMotionWatch()
                handler.removeCallbacksAndMessages(null)
                continuation.resumeOnce(FixOutcome.Success(fix))
            }

            /**
             * Whether an *early* exit is allowed. The deadline is not subject to any of this.
             *
             * The tracking floor is measured from the first satellite epoch, not from the tap, and
             * that used to be the other way round. Anchored to the tap it was inverted with respect
             * to its own purpose: on a cold start whose first fix arrives at twenty-five seconds it
             * was already satisfied *by that first fix*, so the floor protected nothing at exactly
             * the moment it was written for; on a warm start whose first fix arrives at two seconds
             * it charged the user eighteen seconds of pure waiting after every other gate had
             * passed. A filter's covariance shrinks with epochs processed, not with wall time since
             * a button press, and acquisition time — which the app cannot influence and which varies
             * by two orders of magnitude between a hot and a cold receiver — is not convergence
             * time. Measured from the right instant the floor became a real guarantee, which is why
             * [FixRequest.minTrackingMillis] could then be lowered rather than raised.
             */
            fun mayStopEarly(candidate: LocationFix): Boolean {
                // A long occupation is not a short one that got lucky. Every test below reads the
                // receiver's self-reported accuracy, and that number stops falling long before the
                // error a long capture is actually reducing does — see
                // [FixRequest.earlyExitAllowed]. Checked first because it is not a tuning knob: no
                // combination of the gates underneath it may end this capture.
                if (!request.earlyExitAllowed) return false
                // Satellite samples only. A corroborated fused reading is worth averaging and is
                // not worth *stopping* on, and counting it here would let a capture settle on one
                // satellite epoch plus one fused one — which is the single-lucky-epoch exit
                // [FixRequest.minSettleSamples] exists to forbid, wearing a second sample.
                // Counted *post-tap only*.
                //
                // Belt-and-braces since the 2026-08-20 restructuring: the pool now holds nothing but
                // post-tap readings, so this filter selects all of them and the count is unchanged.
                // It mattered a great deal in the design where pre-tap readings were pooled, and it
                // is kept for the same reason the combiner's anchor restriction is — the thing that
                // makes it redundant is a construction, and constructions get changed.
                //
                // The hazard it was written for: readings that predate the button satisfying the
                // settle floor, so that "save current location" is answered almost entirely from
                // before the user asked.
                val settleSamples = samples.count {
                    it.grade == LocationFixPolicy.ProviderGrade.PRECISION &&
                        it.elapsedRealtimeNanos >= startedAt
                }
                if (settleSamples < request.minSettleSamples) return false
                // The floor counts from the oldest reading actually in hand, which is the first
                // post-tap epoch unless the pool was seeded from a continuously-warm receiver.
                //
                // Without this, seeding buys nothing at all. Measured on 2026-08-20: a seeded
                // capture entered its first epoch with sixteen readings of the right place already
                // pooled and still ran 15.7 s — identical to a cold one — because the floor was
                // counting wall time since the tap rather than epochs processed. The floor's own
                // argument is that a filter's covariance shrinks with epochs processed, and a
                // seeded epoch was processed; charging for it twice is charging for the calendar.
                //
                // Only reachable behind [FixRequest.poolWarmUpFixes], and only for samples that
                // already passed the admission gate — a seeded sample is one the capture has
                // positive evidence came from this place, which is a stronger claim than the
                // ordinary floor makes about any epoch it counts.
                val trackingSince = trackingFloorAnchorNanos ?: firstPrecisionNanos ?: return false
                val trackedNanos = candidate.elapsedRealtimeNanos - trackingSince
                if (trackedNanos < request.minTrackingMillis * LocationFixPolicy.NANOS_PER_MILLI) {
                    return false
                }
                if (!GnssConvergence.secondBandReady(
                        anyCarrierFrequencyReported = anyCarrierFrequencyReported,
                        dualBandSatellitesInFix = dualBandSatellitesInFix,
                        requiredSatellites = request.minDualBandSatellites,
                    ) &&
                    !GnssConvergence.secondBandStalled(
                        plateauHeldNanos = plateauSinceNanos
                            ?.let { candidate.elapsedRealtimeNanos - it }
                            ?: 0L,
                        secondBandUnchangedNanos =
                            clock.elapsedRealtimeNanos() - secondBandPeakAtNanos,
                        windowNanos = request.plateauWindowMillis *
                            LocationFixPolicy.NANOS_PER_MILLI,
                    )
                ) {
                    return false
                }
                if (candidate.accuracyMeters <= request.targetAccuracyMeters) return true
                return GnssConvergence.hasPlateaued(
                    history = accuracyHistory,
                    nowElapsedNanos = candidate.elapsedRealtimeNanos,
                    windowNanos = request.plateauWindowMillis * LocationFixPolicy.NANOS_PER_MILLI,
                    deltaMeters = request.plateauDeltaMeters,
                )
            }

            /**
             * Re-answers "is this still getting better" and tells the caller when the answer moved.
             *
             * Called after every accepted epoch rather than only after an improvement, which is the
             * whole point: the settle is the moment the improvements *stop*, so a signal that rode
             * along with one could never carry it. Change-only, so a forty-five second capture
             * emitting an epoch a second does not become forty-five identical state writes.
             */
            fun publishConvergence(nowElapsedNanos: Long) {
                val next = when {
                    best == null -> CaptureConvergence.SEARCHING
                    GnssConvergence.hasPlateaued(
                        history = accuracyHistory,
                        nowElapsedNanos = nowElapsedNanos,
                        windowNanos = request.plateauWindowMillis *
                            LocationFixPolicy.NANOS_PER_MILLI,
                        deltaMeters = request.plateauDeltaMeters,
                    ) -> CaptureConvergence.SETTLED
                    else -> CaptureConvergence.IMPROVING
                }
                // Recorded whether or not the published answer moved, because the give-up test in
                // [mayStopEarly] asks *how long* the series has been flat, not whether the screen
                // was told about it. Cleared the moment it stops being flat, so a single genuine
                // improvement puts the second-band gate back to waiting.
                plateauSinceNanos = if (next == CaptureConvergence.SETTLED) {
                    plateauSinceNanos ?: nowElapsedNanos
                } else {
                    null
                }
                if (next == convergence) return
                convergence = next
                onConvergence(next)
            }

            override fun onLocationChanged(location: Location) {
                val evaluated = (evaluate(location, request, startedAt) as? FixOutcome.Success)?.fix
                    ?: return
                // Weighted here rather than inside `evaluate`, because the weight depends on the
                // satellite state at this moment — which belongs to the capture, not to the
                // conversion of one `Location` into numbers.
                val candidate = evaluated.copy(
                    weight = EpochQuality.weightFor(
                        accuracyMeters = evaluated.accuracyMeters,
                        epoch = epochQuality.copy(
                            speedMetersPerSecond = if (location.hasSpeed()) location.speed else 0f,
                            hasSpeed = location.hasSpeed(),
                            isMoving = stillness?.isMoving,
                        ),
                    ),
                )
                if (candidate.grade != LocationFixPolicy.ProviderGrade.PRECISION) {
                    if (fallback == null || candidate.accuracyMeters < fallback!!.accuracyMeters) {
                        fallback = candidate
                        // Shown only while there is no satellite fix to show instead. Once GNSS has
                        // answered, replacing its number with a coarser network one would read as
                        // the capture getting *worse* while it is in fact getting better.
                        if (best == null) onInterim(candidate)
                    }
                    // A fused fix that lands inside the concurrent satellite fix's own error bar,
                    // within a plateau window of it, has been checked against an independent
                    // satellite measurement this capture already holds — see
                    // [LocationFixPolicy.isCorroborated]. It joins the pool and does nothing else:
                    // it cannot settle the capture, cannot count toward the settle-sample floor,
                    // and cannot enter the accuracy history below. Everything that could turn a
                    // network answer into a converged one still requires a satellite epoch.
                    val reference = best
                    if (reference != null &&
                        LocationFixPolicy.isCorroborated(
                            candidateElapsedNanos = candidate.elapsedRealtimeNanos,
                            referenceElapsedNanos = reference.elapsedRealtimeNanos,
                            separationMeters = distanceMeters(candidate, reference),
                            referenceAccuracyMeters = reference.accuracyMeters,
                            windowNanos = request.plateauWindowMillis *
                                LocationFixPolicy.NANOS_PER_MILLI,
                        )
                    ) {
                        val ceiling = samplePoolCeiling(
                            settleAccuracyMeters = request.settleAccuracyMeters,
                            bestAccuracyMeters = reference.accuracyMeters,
                        )
                        if (candidate.accuracyMeters <= ceiling) {
                            samples.addLast(
                                candidate.copy(
                                    grade = LocationFixPolicy.ProviderGrade.CORROBORATED,
                                ),
                            )
                            while (samples.size > request.averageSamples) {
                                samples.removeFirst()
                                poolCapEvictions++
                            }
                        }
                    }
                    // Deliberately no convergence update. A network estimate is one lookup, not a
                    // series, and calling it settled would tell the user to stop waiting for the
                    // satellite fix this capture is still chasing.
                    return
                }

                // The receiver is now tracking satellites, which is the instant the tracking floor
                // is measured from. Latched on the first PRECISION epoch and never moved again.
                if (firstPrecisionNanos == null) firstPrecisionNanos = candidate.elapsedRealtimeNanos

                accuracyHistory += GnssConvergence.AccuracySample(
                    elapsedRealtimeNanos = candidate.elapsedRealtimeNanos,
                    accuracyMeters = candidate.accuracyMeters,
                )
                // Hygiene, explicitly not speed — see the note on the main-looper cost below. The
                // bound is three plateau windows rather than the two [GnssConvergence.hasPlateaued]
                // reads, so the older of its two windows always has its full span available and a
                // sample sitting exactly on the boundary is never the one that gets trimmed.
                val historyFloor = candidate.elapsedRealtimeNanos -
                    HISTORY_WINDOWS_KEPT * request.plateauWindowMillis *
                    LocationFixPolicy.NANOS_PER_MILLI
                accuracyHistory.removeAll { it.elapsedRealtimeNanos < historyFloor }
                // A walked sample is not a noisy reading of the right place, it is a clean reading
                // of a different one, and no weight repairs a sample that is simply somewhere else.
                // So sustained motion discards the pool outright rather than downweighting it, and
                // the monitor is acknowledged so one walk does not discard it on every epoch after.
                if (stillness?.hasSustainedMotion == true) {
                    samples.clear()
                    // The alignment budget belongs to the pool, so it goes with it. Kept here as
                    // well as on the drift path because the pool has two ways to be emptied and a
                    // budget that outlived its samples would widen the next capture's claim for
                    // corrections applied to readings that were thrown away.
                    alignmentVarianceMeters2 = 0.0
                    // The credit belongs to the seeded samples, so it goes when they do. A capture
                    // that discarded its pool because the phone moved must not still be claiming it
                    // has been tracking this place since before the tap.
                    trackingFloorAnchorNanos = null
                    poolResetsFromMotion++
                    stillness.acknowledgeSustainedMotion()
                }
                // Relative to what this receiver has actually shown it can do, rather than to a
                // fixed ten metres. What the absolute gate cost — a dead early exit and averaging
                // switched off in the captures that needed it most — is written out at
                // [samplePoolCeiling], which is where the decision now lives so it can be tested.
                // Seeding, once, against the first satellite fix of the capture.
                //
                // This is the only place the pre-tap rule is narrowed, and it is narrowed by
                // *evidence* rather than by exemption: a warm-up fix joins only if it sits inside
                // the same admission radius any post-tap sample would have to pass, measured against
                // this candidate. That is a positive demonstration that the receiver was in the
                // same place — a fix from where the user was walking twenty seconds ago is tens of
                // metres away and fails it. See [FixRequest.poolWarmUpFixes] for why the default is
                // off and what residual exposure remains after the gate.
                if (request.poolWarmUpFixes &&
                    firstPrecisionNanos == candidate.elapsedRealtimeNanos &&
                    // Continuity, not merely recency. Without this a capture that did no warm-up of
                    // its own would still be seeded from the *previous* capture's warm-up, which is
                    // both wrong and — worse — would quietly destroy the warm-versus-cold comparison
                    // this whole flag exists to be judged by, by making every cold capture warm.
                    warmMillisBefore(startedAt) != null
                ) {
                    val radius = minOf(
                        request.averageRadiusMeters,
                        candidate.accuracyMeters.toDouble() * WARM_UP_ADMISSION_MULTIPLE,
                    )
                    warmUpFixes.forEach { warm ->
                        warmUpFixesOffered++
                        if (!LocationFixPolicy.admitsWarmUpFix(
                                separationMeters = distanceMeters(warm, candidate),
                                admissionRadiusMeters = radius,
                                accuracyMeters = warm.accuracyMeters,
                                settleAccuracyMeters = request.settleAccuracyMeters,
                                fixElapsedRealtimeNanos = warm.elapsedRealtimeNanos,
                                firstPrecisionElapsedNanos = candidate.elapsedRealtimeNanos,
                                maxAgeMillis = WARM_UP_FIX_MAX_AGE_MILLIS,
                            )
                        ) {
                            return@forEach
                        }
                        // Deliberately *not* a position sample.
                        //
                        // Pooling these was the original design and it earned nothing: measured on
                        // 2026-08-20, a capture entering its first epoch with sixteen pre-tap
                        // readings already pooled ran 15.7 s against a cold capture's 16.0 s. The
                        // whole speed win came from the two gate credits, and every one of the four
                        // correctness defects that design cost came from a pre-tap reading being
                        // treated as a position — the weight it carried, the identity it lent the
                        // saved fix, the accuracy floor it lowered, and the plane origin it became.
                        // All four are now impossible rather than guarded against, because
                        // `combineFixes` never sees one.
                        //
                        // What is kept is the evidence, which is the half that was doing the work: a
                        // reading that passed the admission gate proves this receiver was already
                        // tracking *this place*, and that fact answers both gates without any of it
                        // reaching the saved coordinate.
                        //
                        // `hasPlateaued` asks whether the receiver's reported accuracy has stopped
                        // improving, and a reading from four seconds before the tap answers that as
                        // well as one from four seconds after. It carries no position, so none of
                        // the hazards above apply to it.
                        accuracyHistory += GnssConvergence.AccuracySample(
                            elapsedRealtimeNanos = warm.elapsedRealtimeNanos,
                            accuracyMeters = warm.accuracyMeters,
                        )
                        warmUpFixesTrusted++
                        oldestTrustedWarmUpNanos = minOf(
                            oldestTrustedWarmUpNanos ?: warm.elapsedRealtimeNanos,
                            warm.elapsedRealtimeNanos,
                        )
                    }
                    // The floor credit, from the oldest reading that passed the gate. Nothing can
                    // evict it out from under the capture now, because nothing holds it but this —
                    // which is the second bug this restructuring removes rather than fixes.
                    trackingFloorAnchorNanos = oldestTrustedWarmUpNanos
                    // Consumed. Held no longer than the one moment they are used, which for a buffer
                    // of positions inside a password manager is the only defensible lifetime.
                    //
                    // This line existed, was destroyed by a later edit to the loop above it, and was
                    // found by reading the block as written rather than as remembered — after a
                    // document had already been published claiming the buffer was cleared here.
                    warmUpFixes.clear()
                }
                val bestSoFar =
                    minOf(best?.accuracyMeters ?: candidate.accuracyMeters, candidate.accuracyMeters)
                val poolCeiling = samplePoolCeiling(request.settleAccuracyMeters, bestSoFar)
                if (candidate.accuracyMeters <= poolCeiling) {
                    // The ceiling tightens as the receiver improves, and a sample admitted while it
                    // was loose has no business outvoting the cluster it settled into.
                    samples.retainAll { it.accuracyMeters <= poolCeiling }
                    samples.addLast(candidate)
                    while (samples.size > request.averageSamples) {
                        samples.removeFirst()
                        poolCapEvictions++
                    }
                }
                if (best == null || candidate.accuracyMeters < best!!.accuracyMeters) {
                    best = candidate
                }
                // The combination, every epoch, rather than the best single fix on improvement.
                //
                // The interim is what survives a Stop, and the old form quietly made Stop mean
                // "discard the averaging" — the form held one unaveraged epoch while the pool that
                // would have been saved a second later sat in this closure. On a forty-five-second
                // capture that cost a little; on a five-minute occupation it costs the entire
                // feature, since stopping at four minutes is the ordinary way that mode ends.
                //
                // Firing on every epoch rather than on an improvement, because after the first
                // minute `best` stops improving and the pool does not: gated on improvement, a long
                // capture would freeze the saved answer at its first minute and keep averaging into
                // a value nobody could ever receive. The invariant now is the simple one — the form
                // holds exactly what would be saved if the capture ended on this epoch.
                //
                // No jitter argument against it any more: the metres left the screen when
                // `CaptureConvergence` took over that job, and the accuracy this publishes is
                // `max(best, dispersion) × inflation`, which is never *better* than the single fix
                // it replaced. Worse and true beats better and unearned.
                onInterim(combine() ?: candidate)
                publishConvergence(candidate.elapsedRealtimeNanos)

                // Offsets from this capture's own first satellite fix, never coordinates. See
                // [CaptureTrace] for why that distinction is not negotiable.
                traceEpochs?.add(
                    CaptureTrace.Epoch(
                        sinceFirstPrecisionMillis = (candidate.elapsedRealtimeNanos -
                            (firstPrecisionNanos ?: candidate.elapsedRealtimeNanos)) /
                            LocationFixPolicy.NANOS_PER_MILLI,
                        accuracyMeters = candidate.accuracyMeters,
                        eastMeters = eastOf(candidate, traceOrigin ?: candidate),
                        northMeters = northOf(candidate, traceOrigin ?: candidate),
                        usedInFixCount = epochQuality.usedInFixCount,
                        meanCn0DbHz = epochQuality.meanCn0DbHz,
                        dualBandSatellites = epochQuality.dualBandSatellites,
                        horizontalDop = epochQuality.horizontalDop,
                        lowElevationFraction = epochQuality.lowElevationFraction,
                        admittedToPool = candidate.accuracyMeters <= poolCeiling,
                        convergence = convergence,
                        alignmentShiftMeters = pendingAlignmentShift,
                        alignmentResidualMeters = pendingAlignmentResidual,
                        isMoving = stillness?.isMoving,
                    ),
                )
                pendingAlignmentShift = null
                pendingAlignmentResidual = null
                if (traceOrigin == null) traceOrigin = candidate
                if (trace != null && seriesAnchor == null) seriesAnchor = candidate

                if (mayStopEarly(candidate)) finish(settled(), CaptureTrace.Exit.SETTLED_EARLY)
            }

            // Present because the API-28 interface declares them abstract on some OEM builds, and a
            // provider being switched off mid-capture is a real thing rather than a formality.
            @Deprecated("Required by the API-28 LocationListener contract")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                // Deliberately not a terminal event any more. With several providers registered,
                // GPS being switched off mid-capture says nothing about whether the fused one is
                // still answering — and ending here would turn "one radio went quiet" into a
                // failure for a capture that was about to succeed. The window closing is the only
                // thing that ends an unproductive capture; `usableProviders` already refused the
                // case where nothing at all is on.
            }

            /** The deadline. Publishes whatever the window produced, in order of what it is worth. */
            fun deadline() {
                when {
                    best != null -> finish(settled(), CaptureTrace.Exit.DEADLINE)
                    fallback != null -> finish(fallback!!, CaptureTrace.Exit.CONSOLATION)
                    else -> {
                        emitTrace(CaptureTrace.Exit.EMPTY, null)
                        runCatching { manager.removeUpdates(this) }
                        runCatching { manager.unregisterGnssStatusCallback(bandWatch) }
                        runCatching { manager.unregisterGnssMeasurementsCallback(fullTracking) }
                        releaseMotionWatch()
                        handler.removeCallbacksAndMessages(null)
                        continuation.resumeOnce(FixOutcome.Failure(LocationFailure.TIMED_OUT))
                    }
                }
            }
        }

        // Runs on cancellation of any kind — Back, a lock, a superseding retry, or the outer
        // backstop — and it is the only place a listener registered by a coroutine that no longer
        // exists can be removed.
        continuation.invokeOnCancellation {
            listener.emitTrace(CaptureTrace.Exit.CANCELLED, null)
            handler.removeCallbacksAndMessages(null)
            runCatching { manager.removeUpdates(listener) }
            runCatching { manager.unregisterGnssStatusCallback(bandWatch) }
            runCatching { manager.unregisterGnssMeasurementsCallback(fullTracking) }
            releaseMotionWatch()
        }
        // Full tracking, on API 31+ only, and the one thing here that costs real power.
        //
        // Chipsets duty-cycle the GNSS engine once they have a fix: tracking is dropped and
        // reacquired, which resets carrier phase and re-injects acquisition noise into a solution
        // that had settled. `setFullTracking` is the app-visible request to stop doing that for
        // the lifetime of this callback.
        //
        // The measurements themselves are discarded — nothing here reads an epoch. Registering is
        // the entire point, because it is the registration and not the data that changes the
        // chipset's behaviour. Kept honest by the teardown: this is released in every terminal
        // path, and a leaked full-tracking registration is a battery bug rather than a rounding
        // error. The window is foreground, user-initiated and bounded at forty-five seconds.
        //
        // Issued *before* the position request rather than after it. It is an ordering correction
        // and not a latency fix — the difference is microseconds of main-looper work and no user
        // will ever see it — but it is the correct order: the mode this asks for is the mode the
        // first epochs should already be produced in, and asking afterwards leaves a window in
        // which the very first fixes are the duty-cycled ones.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                manager.registerGnssMeasurementsCallback(
                    GnssMeasurementRequest.Builder().setFullTracking(true).build(),
                    { runnable -> handler.post(runnable) },
                    fullTracking,
                )
            }
        }
        // Best-effort. A device that refuses the status stream simply leaves the band gate
        // unavailable, which [GnssConvergence.secondBandReady] treats as "nothing to say" rather
        // than as a failure — the capture is not held open waiting for evidence that cannot come.
        // The geometry terms degrade the same way: no status stream means no azimuths, which means
        // no HDOP and no blockage inflation, which is exactly the weighting this had before either
        // existed.
        runCatching { manager.registerGnssStatusCallback(bandWatch, handler) }
        // On the same main looper as everything else, so the stillness monitor needs no lock. At
        // roughly sixteen events a second doing one square root and a running variance, the cost is
        // far below the receiver already running beside it; the risk here is the leak, not the draw.
        if (accelerometer != null) {
            runCatching {
                sensors?.registerListener(
                    motionWatch,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_UI,
                    handler,
                )
            }
        }
        // Registered before the deadline can matter, and torn down in every terminal path above: a
        // listener that outlives its coroutine is a GNSS receiver left running behind a screen the
        // user has already left.
        val registered = providers.count { provider ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Says explicitly what the classic call could only imply: highest accuracy, no
                    // minimum interval, no batching delay. It cannot make any single reading better
                    // — its whole effect is on how many reach the combiner, and a combination is
                    // only as good as the number of samples it got to see. On a foreground request
                    // the platform is usually not throttling anyway, so this buys certainty rather
                    // than metres, which is why it is cheap and low down the list.
                    manager.requestLocationUpdates(
                        provider,
                        LocationRequest.Builder(MIN_UPDATE_INTERVAL_MILLIS)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MILLIS)
                            .setMaxUpdateDelayMillis(0L)
                            .build(),
                        { runnable -> handler.post(runnable) },
                        listener,
                    )
                } else {
                    manager.requestLocationUpdates(
                        provider,
                        MIN_UPDATE_INTERVAL_MILLIS,
                        0f,
                        listener,
                        Looper.getMainLooper(),
                    )
                }
            }.isSuccess
        }
        if (registered == 0) {
            runCatching { manager.removeUpdates(listener) }
            // The two callbacks and the sensor are now registered *before* this point, so the
            // no-provider bail has to release them itself rather than leaving them to a terminal
            // path it never reaches.
            runCatching { manager.unregisterGnssStatusCallback(bandWatch) }
            runCatching { manager.unregisterGnssMeasurementsCallback(fullTracking) }
            releaseMotionWatch()
            continuation.resumeOnce(FixOutcome.Failure(LocationFailure.UNAVAILABLE))
            return@suspendCancellableCoroutine
        }

        // The consolation path, armed once at the start. Barring network fixes from settling a
        // capture is what stops a wifi estimate being saved as a satellite one — but on its own it
        // would mean an indoor capture, where a satellite fix is often never coming, waits the full
        // window and returns nothing. That exact behaviour was rejected on a device already. So
        // GNSS gets the first thirty seconds uncontested, and after that the best network estimate
        // ends the capture, carrying its own accuracy so the user is told it is rough.
        handler.postDelayed(
            {
                val consolation = fallback
                // A capture that switched early exits off does not get ended by this either, and
                // that was a real defect rather than a refinement of one.
                //
                // Every other early ending in this file goes through `mayStopEarly`, which
                // `earlyExitAllowed` turns off in one line. This path does not, so the five-minute
                // occupation a user explicitly asked for ended at sixty seconds with a network
                // estimate — measured on a phone with no sky view on 2026-08-20, where a
                // `preciseReading` returned a 5 m FALLBACK fix after 60.0 s. The request's whole
                // contract is "do not stop early", and the one code path that ignored it was the
                // one that fires exactly when the capture is hardest and the user's patience was
                // most deliberate.
                //
                // Nothing is lost by waiting: the deadline path already saves the fallback when no
                // satellite fix ever arrived, so the outcome is the same answer at the deadline
                // instead of a worse-informed one early.
                if (LocationFixPolicy.consolationEndsCapture(
                        earlyExitAllowed = request.earlyExitAllowed,
                        hasPrecisionFix = best != null,
                        hasFallback = consolation != null,
                    ) && consolation != null
                ) {
                    listener.finishWithFallback(consolation)
                }
            },
            request.fallbackAfterMillis,
        )
        // The window closing, as a scheduled ending rather than a thrown one. It has to be here and
        // not in the enclosing `withTimeout`: cancellation unwinds the coroutine instead of
        // returning through it, so a capture whose deadline lived out there could only ever fail —
        // which is what made a forty-five-second wait produce nothing on a device.
        handler.postDelayed(listener::deadline, request.timeoutMillis)
    }

    // Same reasoning as `awaitBestFix`: the permission is checked immediately above through an
    // extension function lint's dataflow does not follow, and a revocation between the check and
    // the call arrives as a SecurityException, which is caught.
    @SuppressLint("MissingPermission")
    override suspend fun warmUp(
        timeoutMillis: Long,
        allowMockProvider: Boolean,
        retainFixes: Boolean,
    ) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        // Never prompts and never escalates. Warming is a thing done for a user who has already
        // granted precise location; a caller that has not asked yet gets nothing, silently, and the
        // tap-time flow is exactly what it always was.
        if (!context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val provider = LocationManager.GPS_PROVIDER
        if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) return

        warmingStartedAtNanos = clock.elapsedRealtimeNanos()
        warmingEndedAtNanos = null
        // A warm-up session's fixes belong to that session. Carrying them across would mean a
        // capture minutes later being seeded from a place the user may have left, with only the age
        // gate standing between that and a wrong saved coordinate.
        warmUpFixes.clear()
        try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation: CancellableContinuation<Unit> ->
                    // Deliberately spare next to a real capture: one provider, a modest interval
                    // rather than zero, no status callback and no full-tracking request. The point
                    // is the receiver's oscillator and ephemeris, not a stream — asking for the
                    // expensive extras here would spend power on data nothing reads.
                    val listener = object : LocationListener {
                        // Retained rather than discarded, and retained *unjudged* — no policy runs
                        // here. Every one of these fails [LocationFixPolicy] by construction, because
                        // every one of them predates a tap that has not happened yet, and that rule
                        // is not being weakened: a capture that wants these admits them only against
                        // its own first satellite fix. See [FixRequest.poolWarmUpFixes].
                        //
                        // Only satellite fixes. A network estimate from before the tap is a cached
                        // wifi answer with none of the properties that make this defensible, and
                        // pooling one would be the cached-fix rule broken rather than narrowed.
                        override fun onLocationChanged(location: Location) {
                            if (!retainFixes) return
                            if (!location.hasAccuracy()) return
                            if (LocationFixPolicy.gradeOf(location.provider) !=
                                LocationFixPolicy.ProviderGrade.PRECISION
                            ) {
                                return
                            }
                            // Synthetic fixes are refused here on exactly the terms a capture
                            // refuses them, so an emulator can exercise this path and a phone with a
                            // mock-location app cannot quietly seed a real capture.
                            if (location.isSynthetic() && !allowMockProvider) return
                            warmUpFixes.addLast(
                                LocationFix(
                                    latitude = location.latitude,
                                    longitude = location.longitude,
                                    capturedAt = Instant.ofEpochMilli(location.time),
                                    accuracyMeters = location.accuracy,
                                    elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                                    grade = LocationFixPolicy.ProviderGrade.PRECISION,
                                ),
                            )
                            while (warmUpFixes.size > MAX_RETAINED_WARM_UP_FIXES) {
                                warmUpFixes.removeFirst()
                            }
                        }

                        @Deprecated("Required by the API-28 LocationListener contract")
                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: android.os.Bundle?,
                        ) = Unit

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    // The only teardown path there is, and it covers all of them: the timeout below
                    // cancels, and so does the caller leaving the screen. A warm-up that outlived
                    // its coroutine would be a GNSS receiver running behind a screen nobody is
                    // looking at, which is precisely the thing this must never become.
                    continuation.invokeOnCancellation {
                        runCatching { manager.removeUpdates(listener) }
                    }
                    val registered = runCatching {
                        manager.requestLocationUpdates(
                            provider,
                            WARM_UP_INTERVAL_MILLIS,
                            0f,
                            listener,
                            Looper.getMainLooper(),
                        )
                    }.isSuccess
                    if (!registered && continuation.isActive) continuation.resume(Unit)
                }
            }
        } catch (_: TimeoutCancellationException) {
            // The cap doing its job. Not a failure and not reported anywhere: nothing was being
            // waited for, so there is no outcome to return.
        } catch (_: SecurityException) {
            // Revoked between the check and the call. Nothing to clean up that cancellation has not
            // already handled.
        } finally {
            // In a `finally` because every way out of the block above — the cap firing, the caller
            // leaving the screen, a revoked permission — ends the tracking just as surely as a
            // clean return does, and a warm-up recorded as still running would credit a capture
            // with a receiver that had already been released.
            warmingEndedAtNanos = clock.elapsedRealtimeNanos()
        }
    }

    /**
     * How long the receiver had been continuously warm when this capture began, or `null` when it
     * was not warm at all.
     *
     * `null` rather than zero for a cold start, because "no warm-up" and "a warm-up of zero length"
     * are different facts and the second one cannot happen. A warm-up that ended more than
     * [WARM_UP_CONTINUITY_MILLIS] before the tap does not count: the hardware stays warm for a
     * while after the request is dropped, but not indefinitely, and a gate credited against a
     * receiver that has since gone back to sleep would be the fastest possible way to ship a
     * confidently wrong position.
     */
    private fun warmMillisBefore(captureStartedAtNanos: Long): Long? {
        val started = warmingStartedAtNanos ?: return null
        val ended = warmingEndedAtNanos ?: captureStartedAtNanos
        val gapMillis = (captureStartedAtNanos - ended) / LocationFixPolicy.NANOS_PER_MILLI
        if (gapMillis > WARM_UP_CONTINUITY_MILLIS) return null
        val warmMillis = (ended - started) / LocationFixPolicy.NANOS_PER_MILLI
        return warmMillis.takeIf { it > 0 }
    }

    /** Turns a platform `Location` into the numbers [LocationFixPolicy] decides on, and asks it. */
    private fun evaluate(location: Location?, request: FixRequest, startedAt: Long): FixOutcome {
        if (location == null) return FixOutcome.Failure(LocationFailure.UNAVAILABLE)
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val rejection = LocationFixPolicy.reject(
            request = request,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = accuracy,
            hasAccuracy = location.hasAccuracy(),
            isFromMockProvider = location.isSynthetic(),
            requestStartedElapsedNanos = startedAt,
            fixElapsedRealtimeNanos = location.elapsedRealtimeNanos,
            nowElapsedNanos = clock.elapsedRealtimeNanos(),
        )
        if (rejection != null) return FixOutcome.Failure(rejection)
        return FixOutcome.Success(
            LocationFix(
                latitude = location.latitude,
                longitude = location.longitude,
                // Wall-clock, derived from the fix's own time, because this is the value shown to a
                // human. The monotonic stamp beside it is what the freshness rule used, and the two
                // are kept separate rather than one being computed from the other.
                capturedAt = Instant.ofEpochMilli(location.time),
                accuracyMeters = requireNotNull(accuracy),
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                grade = LocationFixPolicy.gradeOf(location.provider),
            ),
        )
    }

    private companion object {
        /**
         * Zero, meaning "as fast as the provider will produce them". The capture is bounded by the
         * timeout and every terminal path removes the listener, so this is a one-shot capture
         * expressed through a streaming API — not a stream.
         */
        const val MIN_UPDATE_INTERVAL_MILLIS = 0L

        /**
         * How long past its own deadline the capture is given before the coroutine's timeout kills
         * it. Two seconds is enough for a main-looper message to be delivered under load and short
         * enough that a genuinely wedged capture does not hold the screen; the backstop firing at
         * all means the scheduled ending never ran, which is a bug rather than a slow device.
         */
        const val DEADLINE_GRACE_MILLIS = 2_000L

        /**
         * Multiplier that keeps a constellation's satellite numbers from colliding with another's.
         * `GnssStatus.CONSTELLATION_*` are small integers and svids run to a few hundred, so a
         * thousand separates them with room to spare.
         */
        const val CONSTELLATION_KEY_STRIDE = 1_000

        /**
         * A carrier-phase uncertainty above this is a sentinel, not a measurement.
         *
         * Trace-only, and generous on purpose: a real ADR uncertainty is centimetres and the values
         * being excluded are around 1e37, so anywhere between the two works and nothing turns on
         * where exactly it sits. It exists because "finite and positive" is not the same test as
         * "reported", and reading a field that is only defined when a flag is set without checking
         * the flag is how a census ends up averaging a sentinel.
         */
        const val ADR_UNCERTAINTY_SANITY_METERS = 1_000.0

        /**
         * How long after a warm-up ends the receiver is still treated as warm. Two seconds — long
         * enough to cover the gap between the view model dropping the speculative request and the
         * real one registering, short enough that a screen left and returned to counts as cold.
         */
        const val WARM_UP_CONTINUITY_MILLIS = 2_000L

        /** Mirrors the combiner's own default, so `null` on a request means "leave it alone". */
        const val RADIUS_ACCURACY_MULTIPLE_DEFAULT = 1.5

        /**
         * How many warm-up fixes are kept. Thirty seconds at 1 Hz, which is longer than any tracking
         * floor and short enough that the buffer never becomes a location history.
         */
        const val MAX_RETAINED_WARM_UP_FIXES = 30

        /**
         * How far back a warm-up fix may have been produced and still be offered to a capture.
         *
         * This is a *staleness* bound, doing the job the cached-fix rule's tap comparison does for
         * post-tap fixes. The admission radius is what proves the receiver was in the same place;
         * this is what stops a fix that has been sitting in the buffer through a long form-filling
         * session from being offered at all.
         */
        const val WARM_UP_FIX_MAX_AGE_MILLIS = 20_000L

        /**
         * The admission multiple for warm-up fixes — the same 1.5 the ordinary pool uses, named
         * separately so tightening one does not silently tighten the other. They answer different
         * questions: the pool's radius rejects an outlying epoch, this one rejects a place.
         */
        const val WARM_UP_ADMISSION_MULTIPLE = 1.5

        /**
         * How long the carrier-phase stream is given to prove it exists before the whole path is
         * switched off for the capture. Five seconds is several epochs — long enough that a slow
         * first delivery is not mistaken for absence, short enough that a device which is never
         * going to answer wastes nothing.
         */
        const val PHASE_CENSUS_NANOS = 5_000_000_000L

        /** Three plateau windows: the two the detector compares, plus one so neither is clipped. */
        const val HISTORY_WINDOWS_KEPT = 3L

        /**
         * One second, not zero, for the warm-up. A capture asks for everything the provider will
         * give because every epoch reaches the combiner; a warm-up reads none of them, so the
         * fastest rate would be pure power for no purpose. What holds the receiver awake is having
         * an active request at all, not how often it answers.
         */
        const val WARM_UP_INTERVAL_MILLIS = 1_000L
    }
}

/**
 * Every enabled provider worth asking, best first.
 *
 * This list is the fix for the complaint that ended the first day of testing: indoors, with the
 * phone offline, a GPS-only capture waited the whole window and then failed. That was not bad luck —
 * it is what GNSS does under a roof with no assistance data, and no amount of waiting was going to
 * change it.
 *
 * The original restriction to `GPS_PROVIDER` came from a real constraint applied one step too far.
 * The constraint is that `foss` must not depend on Google Play Services, and it still does not:
 * everything here is `android.location`, part of the OS, and `checkNoNetworkingDeps` still proves
 * `com.google.android.gms:play-services-location` never reaches this module. `FUSED_PROVIDER` is the
 * *platform's* fusion of GNSS, wifi and cell — not Google's — and confusing the two cost a feature
 * that worked in a field and not in a kitchen.
 *
 * All of them are registered at once rather than tried in turn: they answer on wildly different
 * timescales (wifi in about a second, a cold GNSS in minutes), so asking in sequence would mean
 * waiting out the slow one before starting the fast one.
 *
 * What registering all of them does *not* mean is that all of them count equally. Every candidate
 * passes the same [LocationFixPolicy] floor, and then [LocationFixPolicy.gradeOf] decides what it
 * is allowed to do — only `gps` may settle a capture or enter the average. Registering a provider
 * and trusting its output are two separate decisions here, and collapsing them was the bug.
 */
private fun LocationManager.usableProviders(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    add(LocationManager.NETWORK_PROVIDER)
    add(LocationManager.GPS_PROVIDER)
}.filter { provider ->
    // `isProviderEnabled` throws for a provider this device does not have, which is the ordinary
    // case for FUSED on some OEM builds rather than an error.
    runCatching { isProviderEnabled(provider) }.getOrDefault(false)
}.distinct()

private fun Context.isGranted(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
private fun Location.isSynthetic(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider

/** Resumes only if nothing has yet, so a late platform callback after a timeout is inert. */
private fun CancellableContinuation<FixOutcome>.resumeOnce(outcome: FixOutcome) {
    if (isActive) resume(outcome)
}

/**
 * `SystemClock.elapsedRealtimeNanos`, behind an interface so the freshness rule can be tested.
 *
 * Injectable because every interesting case here is a relationship between three instants — the tap,
 * the fix, and now — and `Thread.sleep` is not a way to write those down.
 */
fun interface MonotonicClock {
    fun elapsedRealtimeNanos(): Long

    companion object {
        val System = MonotonicClock { SystemClock.elapsedRealtimeNanos() }
    }
}

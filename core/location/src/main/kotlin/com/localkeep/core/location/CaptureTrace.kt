package com.localkeep.core.location

/**
 * What one capture did, recorded so the tuning in this module can stop being argued and start being
 * measured.
 *
 * Every number in the review that produced this pipeline is either arithmetic from the code or a
 * judgement, because nothing here has ever been compared against a surveyed point. This is the
 * instrument that changes that. It answers, from real captures on real hardware: how long the
 * receiver took to produce its first satellite fix and how that splits into hot, warm and cold; how
 * often a capture leaves early and by which gate; whether the plateau detector fires where a human
 * looking at the series would say it should; whether HDOP predicts the eventual accuracy better than
 * a satellite count does; how correlated consecutive epochs actually are, which is the one input
 * that would justify ever dividing by anything.
 *
 * ### It records no position, and that is deliberate
 *
 * There is no latitude and no longitude here, anywhere, and none may be added. Every position is an
 * **offset in metres from the capture's own first satellite fix** — enough for every question above,
 * and useless to anyone who obtains the file. In a password manager, a debug artefact that
 * accumulates the places its user has been is a worse defect than anything it could help diagnose,
 * and "it is only in debug builds" is not an argument that survives someone shipping a debug build.
 *
 * The one measurement this cannot support is the comparison against a surveyed mark, which needs an
 * absolute anchor. That number is on screen at the end of the capture, and an operator standing on
 * the mark can read it — which is a fair trade for never writing it down.
 *
 * ### It is off unless something switches it on
 *
 * [CurrentLocationProvider] implementations take a sink and default it to `null`. No sink, no
 * recording, no branch that can accidentally become true — the release wiring simply never passes
 * one.
 */
data class CaptureTrace(
    /** How long from the tap to the first satellite epoch. `null` if none ever arrived. */
    val timeToFirstPrecisionMillis: Long?,
    /** Wall time from the tap to the capture ending, however it ended. */
    val durationMillis: Long,
    val exit: Exit,
    /** How many readings were in the averaging pool when it ended. Zero means nothing was averaged. */
    val poolSizeAtExit: Int,
    /** How many of those were satellite readings rather than corroborated ones. */
    val precisionSamplesAtExit: Int,
    /** False when the pool was empty and a single unaveraged epoch was saved instead. */
    val combinedFromPool: Boolean,
    /** The accuracy figure actually saved, after dispersion and any blockage inflation. */
    val reportedAccuracyMeters: Float?,
    /** The widest empty wedge of sky seen, which is the bias signal. `null` if never measurable. */
    val widestAzimuthGapDegrees: Double?,
    /** Whether carrier phase was usable on this device at all — the availability census, per capture. */
    val carrierPhaseUsable: Boolean,
    /**
     * Where this capture's saved answer landed, in metres east of the *series* anchor — the first
     * satellite fix this provider instance ever saw, not this capture's own.
     *
     * This is the field that makes the instrument able to answer anything. Everything else here
     * describes a capture's opinion of itself; only a shared frame lets two captures of the same
     * spot be differenced, and that difference is the only error measurement available without a
     * surveyed mark. With it, twenty repeats give a scatter whose 50th and 95th percentiles are
     * real numbers, and the question the whole module turns on — does the claim predict the error —
     * stops being a matter of opinion. `null` before any satellite fix, and on a provider that was
     * never given a sink.
     *
     * It is still not a position. The anchor is an arbitrary unrecorded point and is never written.
     * What it does add over the per-capture origin is *relative* movement between captures in one
     * process, so a run of captures taken while walking would describe the shape of the walk
     * without saying where it happened. That is a real increase in exposure over the per-capture
     * origin, and it is accepted only because the sink stays absent from every shipped
     * configuration — see the class KDoc. It does not license writing a coordinate.
     */
    val resultEastMeters: Double?,
    /** Metres north of the series anchor. See [resultEastMeters]. */
    val resultNorthMeters: Double?,
    /**
     * The same saved answer, in **this capture's own frame** — metres east and north of
     * [Epoch.eastMeters]'s origin, which is the capture's first satellite fix.
     *
     * Both frames are needed and they answer different questions. The series anchor lets separate
     * captures be differenced, which is how repeatability is measured. This one puts the answer in
     * the same frame as the epoch series, which is the only way to ask what the estimator did with
     * the readings it was given — whether admitting a subset and taking a weighted geometric median
     * beat simply averaging them.
     *
     * Added after an analysis silently mixed the two and produced five identical numbers, which was
     * the frames disagreeing rather than a result. Neither is a coordinate; both are offsets from an
     * arbitrary unrecorded point.
     */
    val resultEastFromCaptureOriginMeters: Double?,
    val resultNorthFromCaptureOriginMeters: Double?,
    /**
     * How wrong the carrier-phase alignment applied to the pool may itself be, at the moment the
     * capture ended. Zero when no alignment was applied, and reset whenever the pool was cleared.
     */
    val alignmentUncertaintyMeters: Double,
    /**
     * The terms behind the saved claim, or `null` when the capture saved a single unaveraged epoch
     * and there was no combination to decompose.
     */
    val claim: ClaimTerms?,
    /** What the raw measurement stream actually offered on this device. Never `null`; may be empty. */
    val rawStream: RawStreamCensus,
    /**
     * How many times the averaging pool was thrown away because the phone had moved, split by which
     * instrument said so — carrier phase drifting past its budget, or the accelerometer seeing
     * sustained motion.
     *
     * The field measurement rests entirely on the phone having stayed in one place, and a run where
     * it did not measures the walk. Nothing in the rest of this record can tell those two runs
     * apart: a capture that moved still reports a tight cluster, a confident claim and a perfectly
     * ordinary epoch series, because the pool was reset and then re-converged somewhere else. These
     * two counters are the only evidence that the premise held.
     */
    /**
     * How long the receiver had been continuously tracking when the tap arrived, or `null` if it
     * was cold.
     *
     * The variable behind the largest single speed question left. The tracking floor is counted
     * from this capture's first satellite epoch, so a receiver already warm from an open coordinate
     * field pays the floor twice over — once while warming, once after the tap. Whether that second
     * payment buys anything is a measurement, and this is the column it is measured against.
     */
    val warmMillisBeforeCapture: Long?,
    /**
     * How many pre-tap warm-up readings were considered, and how many passed the admission gate.
     *
     * None of them becomes a position sample. What a trusted reading buys is the evidence that this
     * receiver was already tracking this place, which answers the tracking floor and the convergence
     * history without any of it reaching the saved coordinate.
     *
     * The gap between the two is the safety argument for [FixRequest.poolWarmUpFixes] made
     * measurable. Offered-many-taken-few is the gate working — the receiver had moved, or the
     * warm-up fixes were loose. Offered-many-taken-all on a capture that then disagreed with its
     * neighbours is the failure mode, and it is the reason both numbers are here rather than just
     * the one that sounds good.
     */
    val warmUpFixesOffered: Int,
    val warmUpFixesTrusted: Int,
    /**
     * How many readings the averaging ring discarded because it was full.
     *
     * Non-zero means `averageSamples` is the thing deciding how much of the capture the answer is
     * made of, rather than how much the capture managed to gather. That is a defensible setting and
     * an undocumented one, and it binds hardest exactly where seeding helps most: a seeded capture
     * arrives at its first epoch already over the cap.
     */
    val poolCapEvictions: Int,
    val poolResetsFromDrift: Int,
    val poolResetsFromMotion: Int,
    val epochs: List<Epoch>,
) {

    /**
     * A count of what the `GnssMeasurement` stream contained, per capture.
     *
     * This exists because four separate proposals for this module — multipath-aware weighting, a
     * Doppler velocity solve, uncertainty-weighted phase, and baseband C/N0 divergence — all rest on
     * fields that the Android API defines and that a given chipset may simply never populate.
     * `getMultipathIndicator()` returning `UNKNOWN` forever is documented, ordinary, and common
     * enough that building a weighting on it first and discovering that second would be the wrong
     * order of work. None of these counts changes any behaviour; they decide which of those
     * proposals is worth writing at all.
     *
     * Means rather than medians throughout, so this costs a running sum per field and no retained
     * per-measurement storage over a five-minute occupation.
     */
    data class RawStreamCensus(
        val events: Int = 0,
        val measurements: Int = 0,
        /** ADR state census — why carrier phase succeeds or fails here, rather than only whether. */
        val adrValid: Int = 0,
        val adrCycleSlip: Int = 0,
        val adrReset: Int = 0,
        val adrUnusable: Int = 0,
        val multipathDetected: Int = 0,
        val multipathNotDetected: Int = 0,
        val multipathUnknown: Int = 0,
        /** How many measurements reported a baseband C/N0 at all. Zero below API 30 by definition. */
        val basebandCn0Reported: Int = 0,
        val meanAdrUncertaintyMeters: Double? = null,
        val meanPseudorangeRateUncertaintyMps: Double? = null,
        val meanReceivedSvTimeUncertaintyNanos: Double? = null,
    )
    /** One satellite reading, with everything that was known about the sky when it arrived. */
    data class Epoch(
        /** Milliseconds since the first satellite epoch of this capture. */
        val sinceFirstPrecisionMillis: Long,
        val accuracyMeters: Float,
        /** Metres east of the first satellite fix. Never a coordinate. */
        val eastMeters: Double,
        /** Metres north of the first satellite fix. Never a coordinate. */
        val northMeters: Double,
        val usedInFixCount: Int,
        val meanCn0DbHz: Float,
        val dualBandSatellites: Int,
        val horizontalDop: Double?,
        val lowElevationFraction: Float,
        val admittedToPool: Boolean,
        val convergence: CaptureConvergence,
        /**
         * How far the carrier-phase alignment moved the pool at this epoch, and how much of that
         * motion the phase observations disagreed about. `null` whenever alignment did not apply —
         * no phase, too few satellites in common, or a pool that had just been cleared.
         *
         * Recorded because the alignment is the one operation here that *tightens* the dispersion
         * the claim is read from. A capture whose residuals were large all the way through has a
         * cluster that looks better than the evidence supports, and without this field there is no
         * way to tell that capture apart from one that was genuinely still.
         */
        val alignmentShiftMeters: Double?,
        val alignmentResidualMeters: Double?,
        /** The accelerometer's verdict at this epoch, or `null` on a device with no accelerometer. */
        val isMoving: Boolean?,
    )

    /** How a capture ended, which is the single most useful field here. */
    enum class Exit {
        /** An early exit: every gate passed. This is the one the speed work is trying to reach. */
        SETTLED_EARLY,

        /** The window ran out with a satellite fix in hand. Ordinary, but not what was aimed for. */
        DEADLINE,

        /** No satellite fix ever arrived and a network estimate ended it instead. */
        CONSOLATION,

        /** Nothing usable at all. */
        EMPTY,

        /** The editor went away, the vault locked, or the user asked again. */
        CANCELLED,
    }

    /**
     * Where a finished trace goes.
     *
     * An interface rather than a file path so the decision about *whether anything is written at
     * all* belongs to whoever builds the provider, and so a test can hold traces in memory. The
     * implementation that writes to disk lives outside this module, next to the wiring that knows
     * which build it is in.
     */
    fun interface Sink {
        /** Called once per capture, on the main looper, after the outcome has been decided. */
        fun record(trace: CaptureTrace)
    }
}

package com.localkeep.core.location

/**
 * How far the receiver moved between two epochs, from the carrier phase the capture already asks for.
 *
 * The measurement stream was registered and thrown away. Registering it is what stops the chipset
 * duty-cycling, and that was the whole reason — but the epochs arriving on it carry
 * `getAccumulatedDeltaRangeMeters()`, the integrated carrier phase, which is the change in range to
 * a satellite since lock at *millimetre* precision. It carries an unknown integer number of whole
 * wavelengths with it, and that ambiguity is unknowable without a base station, so this can never
 * produce an absolute position and any use of it that claims to is wrong.
 *
 * What it can produce is the *difference* between consecutive epochs, in which the ambiguity cancels
 * exactly as long as no cycle slip occurred:
 *
 * ```
 * ΔADRᵢ = −eᵢ · Δx + c·Δt
 * ```
 *
 * `eᵢ` is the line of sight, which [GnssGeometry] already builds from azimuth and elevation with no
 * ephemeris involved. Four satellites make this an over-determined linear system in four unknowns —
 * three of displacement and the receiver's clock drift — and the answer is centimetre-level
 * displacement between one second and the next.
 *
 * ### What it is used for here, and what it is not
 *
 * Two things, both conservative. It proves the phone is *standing still* far better than an
 * accelerometer can, because an accelerometer measures acceleration and is blind to smooth
 * translation — someone drifting three metres over thirty seconds produces no accelerometer
 * signature worth the name and no Doppler signature above noise, and every one of their samples
 * stays inside the admission radius. And it lets the pool be *aligned*: each pooled sample is
 * carried forward into the current epoch's frame, so a slowly drifting series becomes a genuine
 * repeated measurement of one point instead of a smear.
 *
 * It is not used to correct the position by more than the guards below allow, and it is not used at
 * all on a device that does not deliver valid phase — which is many of them. `ADR_STATE_UNKNOWN` on
 * every measurement forever is an ordinary outcome, not a fault, and the fallback is exactly the
 * behaviour that existed before this file: [StillnessMonitor] and nothing else.
 *
 * Pure. The Android side reads the callback and hands over doubles.
 */
object Tdcp {

    /**
     * One satellite's phase observation for one epoch.
     *
     * [satelliteKey] must be stable across epochs for the same satellite and distinct between
     * constellations — a GPS SV 12 and a Galileo SV 12 are different satellites and differencing one
     * against the other produces confident nonsense.
     */
    data class Observation(
        val satelliteKey: Int,
        val accumulatedDeltaRangeMeters: Double,
        val sky: GnssGeometry.SkyPosition,
    )

    /**
     * Displacement in the local tangent plane, plus the residual that says whether to believe it.
     *
     * [residualMeters] is the RMS of what the least-squares fit could not explain. It is the single
     * most useful number here: an unflagged cycle slip — a satellite quietly losing and regaining
     * lock between epochs — moves one observation by a whole number of 19 cm wavelengths, which no
     * displacement of the receiver can account for, and it shows up here as metres of residual
     * against the centimetres a clean epoch produces.
     */
    data class Displacement(
        val eastMeters: Double,
        val northMeters: Double,
        val upMeters: Double,
        val residualMeters: Double,
        val satellites: Int,
    ) {
        val horizontalMeters: Double get() = Math.hypot(eastMeters, northMeters)
    }

    /**
     * The displacement between two epochs, or `null` when it cannot be trusted.
     *
     * Every rejection below is deliberately a `null` rather than a degraded answer. A wrong
     * displacement is worse than none at all: it does not merely fail to improve the position, it
     * actively moves the coordinate the user is going to save.
     */
    fun displacementBetween(
        previous: List<Observation>,
        current: List<Observation>,
    ): Displacement? {
        if (previous.isEmpty() || current.isEmpty()) return null
        val before = previous.associateBy { it.satelliteKey }
        val rows = ArrayList<DoubleArray>()
        val deltas = ArrayList<Double>()
        current.forEach { now ->
            val then = before[now.satelliteKey] ?: return@forEach
            val delta = now.accumulatedDeltaRangeMeters - then.accumulatedDeltaRangeMeters
            if (!delta.isFinite() || Math.abs(delta) > MAX_PLAUSIBLE_RANGE_CHANGE_METERS) return@forEach
            if (!now.sky.azimuthDegrees.isFinite() || !now.sky.elevationDegrees.isFinite()) return@forEach
            if (now.sky.elevationDegrees <= GnssGeometry.MINIMUM_USABLE_ELEVATION_DEGREES) return@forEach
            rows += GnssGeometry.designRow(now.sky)
            deltas += delta
        }
        if (rows.size < GnssGeometry.MINIMUM_SATELLITES_FOR_A_SOLUTION) return null

        val solution = GnssGeometry.solveFourUnknowns(rows, deltas) ?: return null
        var squared = 0.0
        rows.forEachIndexed { index, row ->
            var predicted = 0.0
            for (i in 0 until 4) predicted += row[i] * solution[i]
            val residual = deltas[index] - predicted
            squared += residual * residual
        }
        val residual = Math.sqrt(squared / rows.size)
        if (!residual.isFinite()) return null

        val displacement = Displacement(
            eastMeters = solution[0],
            northMeters = solution[1],
            upMeters = solution[2],
            residualMeters = residual,
            satellites = rows.size,
        )
        return if (isTrustworthy(displacement)) displacement else null
    }

    /**
     * Whether a solved displacement is worth acting on.
     *
     * Two independent tests, because they catch different failures. A large residual means the
     * observations disagree with each other and something slipped. A large displacement means either
     * the phone really is moving — in which case the motion path owns it and the alignment must not
     * silently absorb a walk — or the solve is wrong. Neither is a case for applying a correction.
     */
    fun isTrustworthy(displacement: Displacement): Boolean =
        displacement.satellites >= GnssGeometry.MINIMUM_SATELLITES_FOR_A_SOLUTION &&
            displacement.residualMeters <= MAX_RESIDUAL_METERS &&
            displacement.horizontalMeters <= MAX_EPOCH_DISPLACEMENT_METERS

    /**
     * How far the receiver may drift in total before the alignment stops being applied.
     *
     * Alignment exists for the slow drift nothing else can see — a metre or two over half a minute.
     * Past [MAX_CUMULATIVE_ALIGNMENT_METERS] the honest reading is that the user is somewhere else
     * now, which is a question for the motion path and not something to be quietly corrected away.
     * Disabling rather than clamping, because a clamped correction is a wrong correction that has
     * been made to look reasonable.
     */
    fun alignmentStillApplies(cumulativeHorizontalMeters: Double): Boolean =
        cumulativeHorizontalMeters <= MAX_CUMULATIVE_ALIGNMENT_METERS

    /**
     * The maximum plausible change in range to one satellite between two epochs.
     *
     * Satellites move fast — several hundred metres a second of range rate is ordinary — so this is
     * generous by design. It is not the displacement test; it exists only to drop an observation
     * whose phase counter reset, which shows up as an absurd jump rather than a large one.
     */
    private const val MAX_PLAUSIBLE_RANGE_CHANGE_METERS = 5_000.0

    /**
     * How much of the observations the fit is allowed to fail to explain.
     *
     * A clean carrier-phase epoch fits to millimetres, and a consumer chipset's phase noise is
     * centimetres, so five of them is loose enough not to reject good data and tight enough to catch
     * an observation that has lost its integer.
     *
     * **Its limit, measured rather than assumed.** A *single* undetected cycle slip — one 19 cm
     * wavelength on one satellite of eight — produces a residual of about 3.6 cm, which passes this
     * gate. It is not tightened to catch that, because doing so would start rejecting clean epochs
     * on a noisy receiver, and because the damage does not warrant it: that slip moves the solved
     * displacement by about 8 cm, an order of magnitude below the metre-level errors everything else
     * in this capture is fighting. What this gate is for is *gross* corruption — several cycles, or
     * a satellite whose counter reset without saying so — where the residual runs to tenths of a
     * metre and the displacement would be wrong by enough to matter. The per-satellite state flags
     * are the first line of defence and this is the second, not a replacement for them.
     */
    private const val MAX_RESIDUAL_METERS = 0.05

    /** Two metres in one second is a run, not the drift this correction is for. */
    private const val MAX_EPOCH_DISPLACEMENT_METERS = 2.0

    /** [JUDG], uncalibrated: past five metres the user has moved, they have not drifted. */
    private const val MAX_CUMULATIVE_ALIGNMENT_METERS = 5.0
}

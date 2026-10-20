package com.localkeep.core.location

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs real captures against the real sky and writes down what happened.
 *
 * This is not a test. Nothing here asserts, because there is nothing yet to assert against — every
 * constant in this module is arithmetic or judgement, and the point of the harness is to produce
 * the first numbers that were neither. It is written as an instrumented test only because that is
 * the shortest path to a real receiver with real permissions on a real phone.
 *
 * ### It refuses to run unless asked
 *
 * `assumeTrue` on an instrumentation argument, not `@Ignore`, because `@Ignore` would also make it
 * unrunnable. An ordinary `connectedAndroidTest` — CI's, or one aimed at this module by accident —
 * skips it in milliseconds. Nothing schedules it.
 *
 * ```
 * ./gradlew :core:location:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.gpsField=run \
 *   -Pandroid.testInstrumentationRunnerArguments.shortCount=6 \
 *   -Pandroid.testInstrumentationRunnerArguments.longCount=3
 * ```
 *
 * ### What it is measuring, and why this shape
 *
 * Captures alternate short, long, short, long. Interleaving matters more than it looks: satellite
 * geometry, ionospheric activity and whatever is parked next to the building all change over the
 * hour the run takes, so a block of shorts followed by a block of longs would compare two arms that
 * saw different skies and report the difference as an effect of capture length.
 *
 * Every capture's answer is written as an offset from the series anchor — see
 * [CaptureTrace.resultEastMeters] — so the answers can be differenced against each other. That
 * difference is the measurement. Without a surveyed mark there is no absolute error available, but
 * the two questions that actually decide this module's future do not need one: whether a long
 * capture lands closer to the others than a short one does, and whether the accuracy each capture
 * claims predicts how far it actually landed from its neighbours.
 *
 * The phone must be **still and outdoors, in one place, for the whole run**. A run where it moved
 * measures the walk.
 */
@RunWith(AndroidJUnit4::class)
class GpsFieldHarness {

    @Test
    fun capturesTheSkyAsItIs() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "set -e gpsField run to arm the field harness",
            args.getString("gpsField") == "run",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shortCount = args.getString("shortCount")?.toIntOrNull() ?: 6
        val longCount = args.getString("longCount")?.toIntOrNull() ?: 3
        val gapMillis = (args.getString("gapSeconds")?.toLongOrNull() ?: 5L) * 1_000L
        val label = args.getString("label") ?: "run"
        // Which comparison this run is. Crossing every factor at once would need more captures than
        // a sitting outdoors is worth, so a run answers one question properly rather than three
        // badly.
        //
        //   seed        (default) cold / warm / warm-and-seeded, on the ordinary window
        //   consolation the ordinary thirty-second consolation against a shorter one, alternating
        //               capture by capture
        val mode = args.getString("mode") ?: MODE_SEED
        val wideMultiple = args.getString("wideMultiple")?.toDoubleOrNull() ?: 4.0
        // How many post-tap readings a *seeded* capture must gather before it may settle.
        //
        // The shipped floor is two, and at two a seeded capture averages two or three readings where
        // a cold one averages twelve — measured 2026-08-20 as four times faster and 2.8 times worse,
        // with both arms reporting the same accuracy. This is the knob that buys the averaging back,
        // and sweeping it says what a second of extra waiting is worth.
        val seedSettleSamples = args.getString("seedSettleSamples")?.toIntOrNull()
        val shortConsolationMillis =
            args.getString("consolationSeconds")?.toLongOrNull()?.times(1_000L) ?: 20_000L

        // ### Which capture gets which arm, and why it stopped being `index % 2`
        //
        // Every A/B here used to put arm B on the odd captures. That is the obvious way to
        // interleave, it keeps the arms balanced by construction, and on 2026-08-20 it was measured
        // to be **wrong** — not subtly, and not in a direction anyone would guess.
        //
        // The test was a sham: twenty captures, both arms set to the *same* admission multiple, so
        // the two were byte-identical configurations (applied radius 7.5 m in all twenty, no pool
        // resets, one exit reason, durations inside half a second of each other). Split them by the
        // same parity every real experiment used and the "arms" differed by a factor of **3.47** in
        // scatter, at a permutation p of **0.002** — a larger apparent effect, and a smaller
        // p-value, than the real admission A/B it was a control for. Both runs' dispersions land in
        // the same two places (2.71 / 2.05 vs 2.75 / 2.07), which is the fingerprint: the number
        // belongs to the interleave slot, not to the configuration.
        //
        // The cause is aliasing. `tools/gps-emulator-noise.py` pushes fixes on a fixed seed at a
        // fixed cadence, and a capture schedule of a fixed length plus a fixed gap samples that
        // stream at a fixed phase — so odd captures see systematically different noise from even
        // ones. Permutation testing cannot rescue this: it assumes the labels are exchangeable, and
        // parity is exactly the thing that is not.
        //
        // The fix is a randomised block design, not a shuffle, and the difference is not pedantry.
        // A free shuffle over six captures was tried first and produced `narrow narrow narrow wide
        // wide wide` — perfectly balanced, decorrelated from parity, and **blocked**, which
        // reintroduces the exact confound the interleaving exists to remove: the two arms no longer
        // share the same stretch of sky, so the hour is free to masquerade as the effect. Trading
        // one confound for a worse one is not progress.
        //
        // So the randomisation happens *within* each consecutive pair. Captures 0 and 1 are one A
        // and one B in a coin-flipped order, then 2 and 3, and so on. The arms stay adjacent in
        // time — never more than one capture apart, which is stronger than the old alternation
        // guaranteed — the split stays exactly balanced, and which slot a given arm occupies is now
        // decided by the seed rather than fixed for the whole run.
        //
        // The seed is fixed so a run is reproducible, and settable (`-e armSeed`) so a suspicious
        // result can be re-run against a different draw. That is now the first thing to try on any
        // effect this harness reports.
        // The seed is scrambled before use, which is not decoration. `java.util.Random` seeded with
        // adjacent values produces correlated first outputs, and `nextBoolean()` consumes a single
        // bit — so `armSeed 7` and `armSeed 8` returned the *identical* eight-capture draw, which
        // reads exactly like the flag not being wired up. Twenty minutes went into debugging the
        // shell before the generator was suspected. Knuth's LCG constants decorrelate the small,
        // adjacent integers a human actually types.
        val armSeed = args.getString("armSeed")?.toLongOrNull() ?: 20_260_820L
        val armRandom = java.util.Random(
            armSeed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L,
        )
        val armIsB = MutableList(shortCount + longCount) { false }.apply {
            for (pair in indices step 2) {
                val bFirst = armRandom.nextBoolean()
                this[pair] = bFirst
                // An odd total leaves a final capture with no partner. Give it an arm anyway rather
                // than dropping it: a lone unpaired capture is worth less than a paired one, but it
                // is not worth nothing, and silently excluding it would misreport n.
                if (pair + 1 in indices) this[pair + 1] = !bFirst
            }
        }

        val traces = ArrayList<Pair<String, CaptureTrace>>()
        val provider = AndroidCurrentLocationProvider(
            context = context,
            trace = { trace -> traces.add(pendingKind to trace) },
        )

        val output = File(context.getExternalFilesDir(null), "gps-field").apply { mkdirs() }
        val file = File(output, "$label.jsonl")
        file.writeText("")

        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localkeep:gps-field")
        wakeLock.acquire(TOTAL_BUDGET_MILLIS)
        try {
            for ((index, kind) in schedule(shortCount, longCount).withIndex()) {
                pendingKind = kind
                curve.clear()
                // Alternating warm and cold, which is the controlled half of this run.
                //
                // In the app the receiver is already warming whenever the coordinate field is on
                // screen, so the shipped common case is a warm one — but the tracking floor is
                // counted from the capture's own first satellite epoch either way, and pays no
                // attention to a receiver that has been tracking for half a minute already. Half
                // the captures here warm first and half do not, so the two can be compared on the
                // only thing that matters: how quickly each reached its own final answer.
                // In consolation mode nothing is warmed: warming is the seeding experiment's
                // factor, and leaving it on would cross two factors in a run sized for one.
                val warm = mode == MODE_SEED && !armIsB[index]
                if (warm) {
                    runBlocking {
                        provider.warmUp(
                            WARM_UP_MILLIS,
                            allowMockProvider = true,
                            retainFixes = true,
                        )
                    }
                }
                // Warm captures alternate again on whether they pool the warm-up's own fixes, so
                // the run holds three arms rather than two: cold, warm, and warm-and-seeded. Only
                // the last two are comparable on the question the flag raises — whether a capture
                // that starts with twenty readings of the right place is finished sooner than one
                // that starts with none.
                // Never on a long capture, and that is methodology rather than caution.
                //
                // The long occupation is the *reference* every short capture is scored against — it
                // is the closest thing to a surveyed mark this run has. Seeding it would mean the
                // reference and the thing being measured share a mechanism, so any bias the seeding
                // carries would move both and cancel out of the comparison. The one arm that must
                // stay untouched is the one the answers are judged by.
                val seeded = mode == MODE_SEED && kind == SHORT && warm &&
                    (0 until index).count { !armIsB[it] } % 2 == 0
                // In consolation mode every short capture alternates the delay instead, and the
                // arms are interleaved capture by capture rather than run against run.
                //
                // This is a correction to a measurement made earlier the same day rather than a new
                // idea: a fifteen-second consolation was compared against a thirty-second one by
                // running twelve of each in separate blocks forty minutes apart, and the fifteen
                // second arm was three times worse. That is very likely real — the claim and the
                // scatter moved together — but nothing in that design could separate it from the
                // hour changing, and saying so afterwards is not the same as being able to rule it
                // out. Alternating within one run can.
                val shortConsolation = mode == MODE_CONSOLATION && armIsB[index]
                // The admission gate on trial: every other capture widens it, on the same sky,
                // minutes apart. `averageRadiusMeters` has to move with the multiple or the eight
                // metre ceiling silently caps the wide arm back to the narrow one — producing a
                // clean null result that meant nothing at all.
                val wideAdmission = mode == MODE_ADMISSION && armIsB[index]
                val request = when (kind) {
                    SHORT -> FixRequest(
                        allowMockProvider = true,
                        poolWarmUpFixes = seeded,
                        fallbackAfterMillis = if (shortConsolation) {
                            shortConsolationMillis
                        } else {
                            FixRequest().fallbackAfterMillis
                        },
                        averageRadiusMeters = if (wideAdmission) {
                            FixRequest().averageRadiusMeters * wideMultiple
                        } else {
                            FixRequest().averageRadiusMeters
                        },
                        admissionRadiusMultiple = wideMultiple.takeIf { wideAdmission },
                        minSettleSamples = if (seeded && seedSettleSamples != null) {
                            seedSettleSamples
                        } else {
                            FixRequest().minSettleSamples
                        },
                    )
                    else -> FixRequest.preciseReading(allowMockProvider = true)
                }
                val startedAt = System.currentTimeMillis()
                val outcome = runBlocking {
                    provider.currentFix(
                        request,
                        // The estimator's own running answer, once per epoch, in the run's frame.
                        //
                        // This is what makes a long capture measure itself. Every five-minute
                        // occupation contains a forty-five-second one, so the curve holds what this
                        // module would have saved at any moment of it — computed by the real
                        // combiner, on the same satellites, in the same second. Comparing those two
                        // points is a paired comparison; comparing a separate short capture with a
                        // separate long one is not, and no amount of interleaving fully removes the
                        // sky that changed between them.
                        onInterim = { fix ->
                            val anchor = runAnchor ?: fix.also { runAnchor = it }
                            curve.add(
                                Sample(
                                    atMillis = System.currentTimeMillis() - startedAt,
                                    accuracyMeters = fix.accuracyMeters,
                                    // `internal`, and reachable here because androidTest compiles
                                    // against this module rather than against its published API.
                                    eastMeters = eastOf(fix, anchor),
                                    northMeters = northOf(fix, anchor),
                                ),
                            )
                        },
                        onConvergence = {},
                    )
                }
                val trace = traces.lastOrNull()?.second
                file.appendText(
                    line(label, index, kind, outcome, trace, curve.toList(), request) + "\n",
                )
                Log.i(
                    TAG,
                    "capture $index ($kind) in ${System.currentTimeMillis() - startedAt} ms -> " +
                        summarise(outcome, trace),
                )
                Thread.sleep(gapMillis)
            }
        } finally {
            wakeLock.release()
        }
        Log.i(TAG, "wrote ${traces.size} traces to ${file.absolutePath}")
    }

    /**
     * Short, long, short, long — the longer arm spread evenly through the shorter one rather than
     * appended to it, so both arms see the same hour of sky. See the class KDoc.
     */
    private fun schedule(shortCount: Int, longCount: Int): List<String> {
        val out = ArrayList<String>(shortCount + longCount)
        var shortsLeft = shortCount
        var longsLeft = longCount
        while (shortsLeft > 0 || longsLeft > 0) {
            if (shortsLeft > 0) {
                out.add(SHORT)
                shortsLeft--
            }
            // Longs are the scarce ones, so they are placed by ratio rather than one-for-one: with
            // six shorts and two longs a strict alternation would finish the longs in the first
            // third of the run and leave the last third measuring only one arm.
            if (longsLeft > 0 && (shortsLeft == 0 || out.size % spacing(shortCount, longCount) == 0)) {
                out.add(LONG)
                longsLeft--
            }
        }
        return out
    }

    private fun spacing(shortCount: Int, longCount: Int): Int =
        if (longCount <= 0) Int.MAX_VALUE else maxOf(1, shortCount / longCount)

    private fun summarise(outcome: FixOutcome, trace: CaptureTrace?): String = when (outcome) {
        is FixOutcome.Success -> "acc=${outcome.fix.accuracyMeters}m grade=${outcome.fix.grade} " +
            "exit=${trace?.exit} pool=${trace?.poolSizeAtExit} " +
            "east=${trace?.resultEastMeters} north=${trace?.resultNorthMeters}"
        is FixOutcome.Failure -> "FAILED ${outcome.reason}"
    }

    /**
     * One JSON object per capture, hand-written rather than pulled through a serialization
     * dependency this module does not otherwise have. Appended after every capture so that a run
     * cut short — a dead battery, a cancelled instrumentation — still yields everything up to that
     * point, which for a run measured in hours is not a nicety.
     */
    /** One point on a capture's running answer. Offsets, never a position — see [CaptureTrace]. */
    private data class Sample(
        val atMillis: Long,
        val accuracyMeters: Float,
        val eastMeters: Double,
        val northMeters: Double,
    )

    private fun line(
        label: String,
        index: Int,
        kind: String,
        outcome: FixOutcome,
        trace: CaptureTrace?,
        curve: List<Sample>,
        request: FixRequest,
    ): String {
        val fields = ArrayList<String>()
        fields.add("\"label\":${quote(label)}")
        fields.add("\"index\":$index")
        fields.add("\"kind\":${quote(kind)}")
        // Recorded rather than inferred from the label, so a mixed run stays readable and an arm
        // can never be attributed to the wrong setting.
        fields.add("\"consolationMillis\":${request.fallbackAfterMillis}")
        fields.add("\"seedRequested\":${request.poolWarmUpFixes}")
        fields.add("\"admissionMultiple\":${request.admissionRadiusMultiple ?: 1.5}")
        fields.add("\"minSettle\":${request.minSettleSamples}")
        fields.add("\"radiusCeiling\":${request.averageRadiusMeters}")
        fields.add(
            when (outcome) {
                is FixOutcome.Success -> "\"ok\":true,\"grade\":${quote(outcome.fix.grade.name)}," +
                    "\"savedAccuracy\":${outcome.fix.accuracyMeters}"
                is FixOutcome.Failure -> "\"ok\":false,\"failure\":${quote(outcome.reason.name)}"
            },
        )
        if (trace != null) {
            fields.add("\"exit\":${quote(trace.exit.name)}")
            fields.add("\"ttff\":${trace.timeToFirstPrecisionMillis}")
            fields.add("\"duration\":${trace.durationMillis}")
            fields.add("\"pool\":${trace.poolSizeAtExit}")
            fields.add("\"precisionSamples\":${trace.precisionSamplesAtExit}")
            fields.add("\"combined\":${trace.combinedFromPool}")
            fields.add("\"gap\":${trace.widestAzimuthGapDegrees}")
            fields.add("\"phase\":${trace.carrierPhaseUsable}")
            fields.add("\"east\":${trace.resultEastMeters}")
            fields.add("\"north\":${trace.resultNorthMeters}")
            fields.add("\"eastLocal\":${trace.resultEastFromCaptureOriginMeters}")
            fields.add("\"northLocal\":${trace.resultNorthFromCaptureOriginMeters}")
            fields.add("\"alignment\":${trace.alignmentUncertaintyMeters}")
            fields.add("\"warmBefore\":${trace.warmMillisBeforeCapture}")
            fields.add("\"seedOffered\":${trace.warmUpFixesOffered}")
            fields.add("\"seedTaken\":${trace.warmUpFixesTrusted}")
            fields.add("\"capEvictions\":${trace.poolCapEvictions}")
            fields.add("\"resetDrift\":${trace.poolResetsFromDrift}")
            fields.add("\"resetMotion\":${trace.poolResetsFromMotion}")
            trace.claim?.let { c ->
                fields.add(
                    "\"claim\":{\"cluster\":${c.clusterSize},\"samples\":${c.sampleCount}," +
                        "\"radius\":${c.admissionRadiusMeters},\"receiver\":${c.receiverMeters}," +
                        "\"dispersion\":${c.dispersionMeters},\"align\":${c.alignmentMeters}," +
                        "\"drift\":${c.driftMeters},\"inflation\":${c.biasInflation}}",
                )
            }
            val raw = trace.rawStream
            fields.add(
                "\"raw\":{\"events\":${raw.events},\"measurements\":${raw.measurements}," +
                    "\"adrValid\":${raw.adrValid},\"adrSlip\":${raw.adrCycleSlip}," +
                    "\"adrReset\":${raw.adrReset},\"adrUnusable\":${raw.adrUnusable}," +
                    "\"mpDetected\":${raw.multipathDetected}," +
                    "\"mpNotDetected\":${raw.multipathNotDetected}," +
                    "\"mpUnknown\":${raw.multipathUnknown}," +
                    "\"baseband\":${raw.basebandCn0Reported}," +
                    "\"adrSigma\":${raw.meanAdrUncertaintyMeters}," +
                    "\"rateSigma\":${raw.meanPseudorangeRateUncertaintyMps}," +
                    "\"svTimeSigma\":${raw.meanReceivedSvTimeUncertaintyNanos}}",
            )
            fields.add("\"epochs\":[" + trace.epochs.joinToString(",") { epoch(it) } + "]")
        }
        if (curve.isNotEmpty()) {
            fields.add(
                "\"curve\":[" + curve.joinToString(",") {
                    "[${it.atMillis},${it.accuracyMeters},${it.eastMeters},${it.northMeters}]"
                } + "]",
            )
        }
        return "{" + fields.joinToString(",") + "}"
    }

    private fun epoch(e: CaptureTrace.Epoch): String =
        "{\"t\":${e.sinceFirstPrecisionMillis},\"acc\":${e.accuracyMeters}," +
            "\"e\":${e.eastMeters},\"n\":${e.northMeters},\"used\":${e.usedInFixCount}," +
            "\"cn0\":${e.meanCn0DbHz},\"dual\":${e.dualBandSatellites},\"hdop\":${e.horizontalDop}," +
            "\"lowEl\":${e.lowElevationFraction},\"admitted\":${e.admittedToPool}," +
            "\"conv\":\"${e.convergence.name}\",\"shift\":${e.alignmentShiftMeters}," +
            "\"resid\":${e.alignmentResidualMeters},\"moving\":${e.isMoving}}"

    private fun quote(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""

    /**
     * Which arm the capture now running belongs to. The sink is called from inside `currentFix` and
     * is handed only a [CaptureTrace], which by design knows nothing about why it was taken.
     */
    private var pendingKind: String = SHORT

    /**
     * The whole run's frame for interim answers, and the running curve for the capture in progress.
     *
     * Run-scoped rather than capture-scoped for the same reason the provider's series anchor is:
     * two curves in two different frames cannot be compared, and comparing them is the point.
     */
    private var runAnchor: LocationFix? = null
    private val curve = ArrayList<Sample>()

    private companion object {
        const val TAG = "GpsFieldHarness"
        const val SHORT = "short"
        const val MODE_SEED = "seed"
        const val MODE_CONSOLATION = "consolation"

        /**
         * Alternates the averaging pool's admission gate between the shipped width and a wider one.
         *
         * The one experiment here whose hypothesis came from a *synthetic* result rather than from
         * reading the code, which makes interleaving matter more rather than less: it is the
         * arrangement most likely to be measuring what it hopes to find.
         */
        const val MODE_ADMISSION = "admission"
        const val LONG = "long"

        /** Two hours, which no sane schedule reaches; the lock exists to survive doze, not to time. */
        const val TOTAL_BUDGET_MILLIS = 2L * 60L * 60L * 1_000L

        /**
         * Long enough for a cold receiver to acquire, so a "warm" capture in this run really is
         * warm rather than merely preceded by a request. Matches what the item editor spends when
         * the coordinate field appears.
         */
        const val WARM_UP_MILLIS = 30_000L
    }
}

package com.localkeep.core.location

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The acceptance rule, tested where it can be: three monotonic instants and a handful of numbers.
 *
 * Every case here is one the device can produce and none of them is reachable by driving the UI —
 * a cached fix, a mocked provider, an accuracy of 400 m, a stamp from the future. That is the whole
 * reason the rule is a pure function instead of a few `if`s inside the `LocationManager` callback.
 */
class LocationFixPolicyTest {

    private val request = FixRequest()

    /** Nanoseconds, so the arithmetic in the tests reads in the units the policy works in. */
    private fun ms(value: Long) = value * LocationFixPolicy.NANOS_PER_MILLI

    private val tap = ms(1_000_000)

    private fun reject(
        latitude: Double = 42.135_098,
        longitude: Double = 24.745_129,
        accuracyMeters: Float? = 8.5f,
        hasAccuracy: Boolean = true,
        isFromMockProvider: Boolean = false,
        request: FixRequest = this.request,
        fixAt: Long = tap + ms(2_000),
        now: Long = tap + ms(2_100),
    ) = LocationFixPolicy.reject(
        request = request,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        hasAccuracy = hasAccuracy,
        isFromMockProvider = isFromMockProvider,
        requestStartedElapsedNanos = tap,
        fixElapsedRealtimeNanos = fixAt,
        nowElapsedNanos = now,
    )

    @Test
    fun `a fresh precise fix taken after the tap is accepted`() {
        assertNull(reject())
        assertTrue(
            LocationFixPolicy.accepts(
                request = request,
                latitude = 0.0,
                longitude = 0.0,
                accuracyMeters = 0f,
                hasAccuracy = true,
                isFromMockProvider = false,
                requestStartedElapsedNanos = tap,
                fixElapsedRealtimeNanos = tap,
                nowElapsedNanos = tap,
            ),
            "a fix produced at the instant of the tap is the freshest one there is",
        )
    }

    @Test
    fun `a cached fix from before the tap is refused however recent it is`() {
        // The case a plain age window cannot catch, and the one the feature's honesty rests on.
        // One second old, well inside maxAgeMillis, and still wrong: the user pressed a button that
        // says "save current location", so the answer has to be a measurement of that moment.
        assertEquals(LocationFailure.UNAVAILABLE, reject(fixAt = tap - ms(1_000), now = tap + ms(100)))
        // Right up to the boundary. One nanosecond before the tap is before the tap.
        assertEquals(LocationFailure.UNAVAILABLE, reject(fixAt = tap - 1, now = tap + ms(100)))
        assertNull(reject(fixAt = tap, now = tap + ms(100)))
    }

    @Test
    fun `an otherwise good fix that sat too long before we took it is refused`() {
        assertNull(reject(fixAt = tap + ms(1_000), now = tap + ms(1_000) + ms(10_000)))
        assertEquals(
            LocationFailure.UNAVAILABLE,
            reject(fixAt = tap + ms(1_000), now = tap + ms(1_000) + ms(10_001)),
        )
    }

    @Test
    fun `a stamp from the future is a broken receiver, not a stale reading`() {
        assertEquals(LocationFailure.UNAVAILABLE, reject(fixAt = tap + ms(5_000), now = tap + ms(1_000)))
    }

    @Test
    fun `a fix with no accuracy is not a precise fix`() {
        // Refused rather than saved with a null accuracy. The app promises the user a measurement,
        // and it cannot describe one whose error bars are unknown.
        assertEquals(LocationFailure.UNAVAILABLE, reject(hasAccuracy = false, accuracyMeters = null))
        assertEquals(LocationFailure.UNAVAILABLE, reject(hasAccuracy = true, accuracyMeters = null))
        assertEquals(LocationFailure.UNAVAILABLE, reject(accuracyMeters = -1f))
        assertEquals(LocationFailure.UNAVAILABLE, reject(accuracyMeters = Float.NaN))
    }

    @Test
    fun `accuracy worse than the bound is refused, and the bound itself is not`() {
        assertNull(reject(accuracyMeters = 100f))
        assertEquals(LocationFailure.UNAVAILABLE, reject(accuracyMeters = 100.01f))
    }

    @Test
    fun `coordinates outside the earth are refused`() {
        assertEquals(LocationFailure.UNAVAILABLE, reject(latitude = Double.NaN))
        assertEquals(LocationFailure.UNAVAILABLE, reject(longitude = Double.POSITIVE_INFINITY))
        assertEquals(LocationFailure.UNAVAILABLE, reject(latitude = 90.1))
        assertEquals(LocationFailure.UNAVAILABLE, reject(longitude = -180.1))
        assertNull(reject(latitude = -90.0, longitude = 180.0))
    }

    @Test
    fun `a synthetic fix is refused unless the build explicitly allows one`() {
        // The emulator's default is 0,0 with a perfect accuracy, which passes every other rule here.
        // "Null Island" in a user's vault is indistinguishable from a real place they went.
        assertEquals(LocationFailure.UNAVAILABLE, reject(isFromMockProvider = true))
        assertNull(reject(isFromMockProvider = true, request = FixRequest(allowMockProvider = true)))
    }

    @Test
    fun `the shipped capture contract is what the device runs settled on`() {
        // Pinned because every number here was changed by measurement or by an audit rather than by
        // taste, and a silent revert would reintroduce a defect that reached a user's hands: a
        // 20-second window a cold GNSS start with the phone offline cannot meet; a first-fix
        // acceptance that saved a 47 m position while better ones were seconds away; a settle
        // threshold loose enough that a wifi answer ended the capture before the satellites replied.
        //
        // `minTrackingMillis` moved 20_000 -> 12_000 when the instant it is measured from moved from
        // the tap to the first satellite epoch. Twelve seconds of real tracking is a stronger floor
        // than twenty seconds of wall clock, which on a cold start was satisfied by the very first
        // fix the receiver ever produced. Lowering it without that change would be a plain
        // regression, which is why the two are pinned together here.
        val shipped = FixRequest()
        assertEquals(45_000L, shipped.timeoutMillis)
        assertEquals(12_000L, shipped.minTrackingMillis)
        assertEquals(8_000L, shipped.plateauWindowMillis)
        assertEquals(0.5f, shipped.plateauDeltaMeters)
        assertEquals(10f, shipped.settleAccuracyMeters)
        assertEquals(3f, shipped.targetAccuracyMeters)
        assertEquals(2, shipped.minSettleSamples)
        assertEquals(4, shipped.minDualBandSatellites)
        assertEquals(12, shipped.averageSamples)
        assertEquals(8.0, shipped.averageRadiusMeters)
        assertEquals(100f, shipped.maxAccuracyMeters)
        assertEquals(10_000L, shipped.maxAgeMillis)
        assertEquals(30_000L, shipped.fallbackAfterMillis)
        assertFalse(shipped.allowMockProvider, "a release build must never accept a synthetic fix")
        assertTrue(
            shipped.earlyExitAllowed,
            "the ordinary capture is the one the user is waiting on, and every gate above exists " +
                "precisely so it can end as soon as waiting stops buying anything",
        )
        assertTrue(
            shipped.targetAccuracyMeters < shipped.settleAccuracyMeters,
            "stopping immediately has to be a stricter bar than being worth averaging, or the " +
                "sample pool is never the thing that decides",
        )
        assertTrue(
            shipped.settleAccuracyMeters < shipped.maxAccuracyMeters,
            "the settle threshold is what the capture aims for and the max is the floor it will " +
                "accept; equal or inverted, the whole keep-refining behaviour collapses",
        )
        assertTrue(
            shipped.minSettleSamples >= 2,
            "an early exit on one sample is a lucky epoch reported as a converged average",
        )
        assertTrue(
            shipped.minTrackingMillis + shipped.plateauWindowMillis < shipped.timeoutMillis,
            "a plateau that cannot be observed before the deadline is not a convergence gate, it " +
                "is a fixed 45-second wait wearing one",
        )
        assertTrue(
            shipped.fallbackAfterMillis < shipped.timeoutMillis,
            "a consolation that fires at or after the deadline never fires, which is the indoor " +
                "failure it exists to prevent",
        )
        assertTrue(
            shipped.fallbackAfterMillis > shipped.minTrackingMillis,
            "the network answer is the consolation prize; handing it out before the satellites " +
                "have had their uncontested window makes it the default",
        )
        assertTrue(
            shipped.minTrackingMillis > shipped.plateauWindowMillis,
            "the tracking floor has to outlast the window a plateau is judged over, or it is not " +
                "a floor under the convergence idea at all — the plateau test would already have " +
                "been free to fire before it",
        )
    }

    @Test
    fun `the long occupation actually averages the window it makes the user wait through`() {
        // This test exists because the obvious way to build a five-minute capture produces a
        // five-minute capture that is no more accurate than a short one, and nothing on screen
        // would say so. The sample pool is a ring bounded by `averageSamples`; at the shipped
        // twelve, a three-hundred-second occupation averages its last twelve seconds and discards
        // the rest. Length is the entire mechanism here, so a ring that cannot hold the length is
        // not a smaller version of the feature — it is the feature switched off, silently.
        val precise = FixRequest.preciseReading()
        assertEquals(FixRequest.PRECISE_TIMEOUT_MILLIS, precise.timeoutMillis)
        assertTrue(
            precise.timeoutMillis > FixRequest().timeoutMillis * 4,
            "if the long capture is not several times the ordinary one there is no decorrelation " +
                "to buy and the whole mode is a longer wait for the same answer",
        )
        assertTrue(
            precise.averageSamples.toLong() * NOMINAL_EPOCH_MILLIS >= precise.timeoutMillis,
            "the pool must span the whole occupation at 1 Hz; ${precise.averageSamples} samples " +
                "covers ${precise.averageSamples * NOMINAL_EPOCH_MILLIS} ms of a " +
                "${precise.timeoutMillis} ms window",
        )
        assertFalse(
            precise.earlyExitAllowed,
            "every early-exit gate reads the receiver's self-reported accuracy, which flattens " +
                "long before the error a long occupation reduces does — leaving them armed ends " +
                "this capture early and delivers nothing the short one would not have",
        )
        assertFalse(precise.allowMockProvider, "a release build must never accept a synthetic fix")
        assertTrue(
            precise.fallbackAfterMillis < precise.timeoutMillis,
            "a consolation that fires at or after the deadline never fires at all",
        )
        assertTrue(
            precise.fallbackAfterMillis > FixRequest().fallbackAfterMillis,
            "someone who asked for five minutes has said what they are willing to wait for; " +
                "offering them the network answer on the short capture's schedule is impatience " +
                "the user did not express",
        )
        assertTrue(
            FixRequest.preciseReading(allowMockProvider = true).allowMockProvider,
            "the debug/instrumented path has to be reachable or none of this is testable on an " +
                "emulator",
        )
    }

    /**
     * One second. Android's `LocationRequest` asks for zero and a GNSS chipset answers at 1 Hz
     * anyway, so this is the rate the pool has to be sized against — and being wrong in the
     * conservative direction only wastes a few kilobytes.
     */
    private val NOMINAL_EPOCH_MILLIS = 1_000L

    @Test
    fun `the floor still governs what may be settled for`() {
        // The target only decides when to stop early. Anything the window eventually settles for
        // still passes the same policy, so a 47 m fix remains acceptable-at-the-deadline and a
        // 101 m one remains refused however long the receiver had.
        assertNull(reject(accuracyMeters = 47f))
        assertEquals(LocationFailure.UNAVAILABLE, reject(accuracyMeters = 101f))
    }

    @Test
    fun `a fix never prints its position`() {
        val fix = LocationFix(42.135_098, 24.745_129, Instant.EPOCH, 8.5f, tap)
        assertEquals("LocationFix(redacted)", fix.toString())
        assertFalse("42.135" in fix.toString())
    }

    @Test
    fun `a fused fix is admitted only when a satellite fix vouches for it`() {
        // The narrow crack deliberately left in the grading. `gradeOf` still calls every non-`gps`
        // provider FALLBACK, because no API reports lineage and none ever will — but "no way to tell
        // from the outside" was never true: the capture registers every provider at once, so it
        // already holds a satellite fix from the same few seconds and can *check*. This is not an
        // inference about a provider, it is a measurement compared against another measurement.
        val window = ms(8_000)

        // Two metres from a satellite fix that claims six. By that fix's own account, the same place.
        assertTrue(
            LocationFixPolicy.isCorroborated(
                candidateElapsedNanos = tap + ms(3_000),
                referenceElapsedNanos = tap + ms(2_500),
                separationMeters = 2.0,
                referenceAccuracyMeters = 6f,
                windowNanos = window,
            ),
        )
        // Forty metres away is a wifi lookup, and the app can see that it is.
        assertFalse(
            LocationFixPolicy.isCorroborated(
                candidateElapsedNanos = tap + ms(3_000),
                referenceElapsedNanos = tap + ms(2_500),
                separationMeters = 40.0,
                referenceAccuracyMeters = 6f,
                windowNanos = window,
            ),
        )
        // Agreeing with a satellite fix from half a minute ago is not agreeing about this moment.
        assertFalse(
            LocationFixPolicy.isCorroborated(
                candidateElapsedNanos = tap + ms(40_000),
                referenceElapsedNanos = tap + ms(2_000),
                separationMeters = 1.0,
                referenceAccuracyMeters = 6f,
                windowNanos = window,
            ),
        )
        // A reference with no usable accuracy vouches for nothing; there is no bar to clear.
        assertFalse(
            LocationFixPolicy.isCorroborated(
                candidateElapsedNanos = tap,
                referenceElapsedNanos = tap,
                separationMeters = 0.5,
                referenceAccuracyMeters = 0f,
                windowNanos = window,
            ),
        )
        assertFalse(
            LocationFixPolicy.isCorroborated(
                candidateElapsedNanos = tap,
                referenceElapsedNanos = tap,
                separationMeters = Double.NaN,
                referenceAccuracyMeters = 6f,
                windowNanos = window,
            ),
        )
        // The boundary is the reference's own claimed radius, inclusive: a fix on it is, by that
        // fix's account, still the same place.
        assertTrue(
            LocationFixPolicy.isCorroborated(tap, tap, 6.0, 6f, window),
        )
        assertFalse(
            LocationFixPolicy.isCorroborated(tap, tap, 6.001, 6f, window),
        )
    }

    @Test
    fun `corroboration never turns a fused fix into a satellite one`() {
        // The grade a corroborated fix carries is its own, and `gradeOf` is untouched: promotion is
        // something the capture does at runtime to one reading it has checked, not a reclassification
        // of a provider. Everything that could end a capture still asks for PRECISION by name.
        assertEquals(LocationFixPolicy.ProviderGrade.FALLBACK, LocationFixPolicy.gradeOf("fused"))
        assertTrue(
            LocationFixPolicy.ProviderGrade.CORROBORATED != LocationFixPolicy.ProviderGrade.PRECISION,
        )
    }

    @Test
    fun `only the satellite provider is precision grade`() {
        // The rule that stops a wifi estimate settling a capture. Everything here passes `reject`
        // identically; the grade is the only thing separating "a measurement of where you are" from
        // "a lookup of which access point you can hear".
        assertEquals(LocationFixPolicy.ProviderGrade.PRECISION, LocationFixPolicy.gradeOf("gps"))
        assertEquals(LocationFixPolicy.ProviderGrade.FALLBACK, LocationFixPolicy.gradeOf("network"))
        // Graded down despite usually being satellite-driven: it can answer from wifi alone and
        // nothing in the API says which happened, so trusting it would leave the same hole.
        assertEquals(LocationFixPolicy.ProviderGrade.FALLBACK, LocationFixPolicy.gradeOf("fused"))
        assertEquals(LocationFixPolicy.ProviderGrade.FALLBACK, LocationFixPolicy.gradeOf("passive"))
        assertEquals(LocationFixPolicy.ProviderGrade.FALLBACK, LocationFixPolicy.gradeOf(null))
    }

    @Test
    fun `the consolation timer never ends a capture that switched early exits off`() {
        // The defect this pins, measured rather than reasoned: on a phone with no sky view a
        // `preciseReading` — five minutes, every early-exit gate off — was ended at 60.0 s by the
        // consolation timer with a 5 m network estimate. Every other early ending in the capture
        // consults `earlyExitAllowed`; this one did not, and it fires exactly when the capture is
        // hardest and the user's five-minute choice was most deliberate.
        assertFalse(
            LocationFixPolicy.consolationEndsCapture(
                earlyExitAllowed = false,
                hasPrecisionFix = false,
                hasFallback = true,
            ),
            "a long occupation must run to its deadline; the deadline path saves the same fallback",
        )
        // The control, and the reason this is a predicate rather than a constant: the ordinary
        // capture still ends here, because a user who asked for speed indoors should not be made to
        // wait out the whole window for an answer that is not coming.
        assertTrue(
            LocationFixPolicy.consolationEndsCapture(
                earlyExitAllowed = true,
                hasPrecisionFix = false,
                hasFallback = true,
            ),
            "the ordinary capture must still be endable by its consolation",
        )
        // A satellite fix in hand outranks a network estimate whatever the request said.
        assertFalse(
            LocationFixPolicy.consolationEndsCapture(
                earlyExitAllowed = true,
                hasPrecisionFix = true,
                hasFallback = true,
            ),
            "a capture holding a satellite fix must never be ended by a network estimate",
        )
        assertFalse(
            LocationFixPolicy.consolationEndsCapture(
                earlyExitAllowed = true,
                hasPrecisionFix = false,
                hasFallback = false,
            ),
            "with nothing to offer there is nothing to end the capture with",
        )
    }

    @Test
    fun `pooling warm-up fixes is off unless a request asks for it`() {
        // The shipped contract, pinned. `poolWarmUpFixes` narrows the cached-fix rule — the one rule
        // in this module whose whole job is to stop `getLastKnownLocation` handing back an hour-old
        // position from another city — and a narrowing that arrives by default is a narrowing nobody
        // decided on. It exists to be measured against the ordinary behaviour, which means the
        // ordinary behaviour has to still be the default for the comparison to mean anything.
        assertFalse(
            FixRequest().poolWarmUpFixes,
            "the ordinary capture must not pool pre-tap fixes",
        )
        assertFalse(
            FixRequest.preciseReading().poolWarmUpFixes,
            "nor may the long occupation, which has time it does not need to save",
        )
        assertTrue(
            FixRequest(poolWarmUpFixes = true).poolWarmUpFixes,
            "and a caller that asks for it must get it, or the measurement cannot be run",
        )
    }

    @Test
    fun `the cached-fix rule itself is unchanged`() {
        // The control for the test above, and the more important half of it. Whatever a request
        // says about pooling, `reject` must go on refusing a fix that predates the tap: seeding is
        // an admission decision made inside the capture against evidence, never a relaxation of the
        // rule that decides what may be *saved*.
        val tap = 5_000_000_000L
        assertEquals(
            LocationFailure.UNAVAILABLE,
            LocationFixPolicy.reject(
                request = FixRequest(poolWarmUpFixes = true),
                latitude = 42.0,
                longitude = 24.0,
                accuracyMeters = 3f,
                hasAccuracy = true,
                isFromMockProvider = false,
                requestStartedElapsedNanos = tap,
                fixElapsedRealtimeNanos = tap - 1_000_000_000L,
                nowElapsedNanos = tap + 1_000_000_000L,
            ),
            "a fix from before the tap must still be refused as a saved answer",
        )
    }

    @Test
    fun `a warm-up reading is admitted only on proof the receiver had not moved`() {
        val tap = 100_000_000_000L
        fun admits(
            separation: Double = 2.0,
            radius: Double = 4.5,
            accuracy: Float = 4f,
            settle: Float = 10f,
            ageMillis: Long = 3_000L,
        ) = LocationFixPolicy.admitsWarmUpFix(
            separationMeters = separation,
            admissionRadiusMeters = radius,
            accuracyMeters = accuracy,
            settleAccuracyMeters = settle,
            fixElapsedRealtimeNanos = tap - ageMillis * LocationFixPolicy.NANOS_PER_MILLI,
            firstPrecisionElapsedNanos = tap,
            maxAgeMillis = 20_000L,
        )

        assertTrue(admits(), "a close, recent, tight pre-tap reading is exactly what this admits")

        // The condition that carries the whole argument. Everything else here is bookkeeping; this
        // is the one that turns "a fix from before the tap" into "a fix from *this place*", and a
        // user who walked while filling in the form fails it by tens of metres.
        assertFalse(
            admits(separation = 4.6),
            "a reading outside the admission radius is a reading of somewhere else",
        )
        assertFalse(
            admits(separation = Double.NaN),
            "an unmeasurable separation is not evidence of anything, least of all of standing still",
        )

        // Staleness, doing the job the tap comparison does for post-tap fixes.
        assertFalse(
            admits(ageMillis = 20_001L),
            "a reading older than the age bound may have sat in the buffer through a whole form",
        )
        assertTrue(admits(ageMillis = 20_000L), "and the bound itself is inclusive")

        // A loose reading is no more welcome for being early.
        assertFalse(admits(accuracy = 10.1f), "a reading outside the settle accuracy is refused")

        // A post-tap reading belongs on the ordinary path. Admitting it here would double-count it.
        assertFalse(
            LocationFixPolicy.admitsWarmUpFix(
                separationMeters = 0.5,
                admissionRadiusMeters = 4.5,
                accuracyMeters = 4f,
                settleAccuracyMeters = 10f,
                fixElapsedRealtimeNanos = tap + 1L,
                firstPrecisionElapsedNanos = tap,
                maxAgeMillis = 20_000L,
            ),
            "a reading from after the tap is an ordinary sample, not a seeded one",
        )
    }

    @Test
    fun `the ordinary capture averages its last twelve seconds, not its window`() {
        // Not an assertion that this is right. An assertion that it is *deliberate*, because it is
        // the single largest open question about this module's accuracy and until 2026-08-20 nothing
        // stated it anywhere.
        //
        // `averageSamples` is a ring: the newest N readings are kept and the rest discarded. At
        // twelve, and roughly one epoch a second, the saved answer is made from the last twelve
        // seconds of a forty-five-second capture however long the user waited. Measured on the
        // emulator the same day: an ordinary sixteen-second capture gathered twenty-one readings and
        // **discarded nine of them**.
        //
        // The long occupation does not work this way — `preciseReading` sets four hundred precisely
        // so its ring spans its window, and the test above asserts exactly that. So the two request
        // kinds disagree about whether the pool should cover the capture, and only one of them has
        // ever had the question put to it.
        //
        // This plausibly explains a measurement this project already has and had attributed
        // elsewhere: a five-minute capture produced the same answer as a forty-five-second one. Part
        // of that is multipath failing to decorrelate within a session, which the literature
        // supports. Part of it may simply be that the estimator never averaged more than the last
        // twelve seconds either way.
        //
        // `CaptureTrace.poolCapEvictions` now counts what the ring throws away, so the field run can
        // decide this instead of the arithmetic. Until then the constant does not move, and this
        // test fails if it does — which is the point: a change here is a change to what every saved
        // coordinate is made of, and it should be argued for rather than tidied in.
        val ordinary = FixRequest()
        val ringCoverageMillis = ordinary.averageSamples.toLong() * NOMINAL_EPOCH_MILLIS
        assertTrue(
            ringCoverageMillis < ordinary.timeoutMillis,
            "this test exists to document that the ring is shorter than the window; if that has " +
                "changed, the reasoning above needs rewriting rather than the assertion flipping",
        )
        assertEquals(
            12,
            ordinary.averageSamples,
            "pinned so the open question cannot be closed silently in either direction",
        )
        // And the asymmetry itself, stated rather than left to be noticed.
        assertTrue(
            FixRequest.preciseReading().averageSamples.toLong() * NOMINAL_EPOCH_MILLIS >=
                FixRequest.preciseReading().timeoutMillis,
            "the long occupation's ring does span its window, which is what makes this an " +
                "inconsistency rather than a policy",
        )
    }
}

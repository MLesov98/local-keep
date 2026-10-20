package com.localkeep.feature.itemedit

import com.localkeep.core.location.CaptureConvergence
import com.localkeep.core.location.FixRequest
import com.localkeep.core.model.Coordinate
import java.util.Locale

/**
 * How a saved position is written on screen.
 *
 * ### The number of decimals is itself an accuracy claim
 *
 * In signed decimal degrees each place is worth about a tenth of the one before it: 3 dp is ~111 m,
 * 4 dp ~11 m, 5 dp ~1.1 m, 6 dp ~0.11 m at the equator. Printing six decimals beside a caption that
 * admits to metres is the screen contradicting itself — the digits assert a centimetre and the words
 * assert a few metres, and the digits are the part people believe.
 *
 * So the count is derived from the record's own accuracy rather than fixed: enough places that
 * rounding is comfortably finer than the stated error, and not so many that the tail of the number
 * is noise dressed as measurement. Five is the ceiling, because six would only be honest for a
 * sub-metre fix and no consumer receiver this app will ever run on delivers one.
 *
 * Keyed off *that record's* accuracy, never a constant, so a rough old reading and a good new one
 * render differently — which is the point. The stored value keeps full `Double` precision either
 * way; this only decides what is rendered.
 *
 * ### Locale
 *
 * `Locale.ROOT`, deliberately, and this is the one place in the app where a number is *not*
 * localized. A decimal comma is correct French and is also the separator between the two halves of
 * a coordinate pair in every mapping convention there is — "42,135098, 24,745129" is a string a
 * person cannot parse and a paste target will read as four numbers. The value is copied to the
 * clipboard exactly as rendered, so what is shown has to be what any other tool will accept.
 *
 * The stored value is a pair of `Double`s and is never derived from this text. Display formatting
 * and persistence are separate on purpose: a value that round-tripped through its own rendering
 * would lose whatever the rendering did not carry.
 */
internal fun Coordinate.displayValue(): String {
    val places = displayDecimals()
    return String.format(Locale.ROOT, "%.${places}f, %.${places}f", latitude, longitude)
}

/**
 * How many decimal places this record has earned.
 *
 * The thresholds are the degree-to-metre conversion read backwards: a place is worth showing when
 * its value is at least a factor of ten finer than the error being claimed, so 5 dp (~1.1 m) stays
 * honest down to roughly 10 m, 4 dp (~11 m) down to roughly 100 m, and below that the fix is barely
 * a location at all. A record with no accuracy at all gets the middle answer rather than the
 * generous one: an unknown error is not a small one.
 */
private fun Coordinate.displayDecimals(): Int {
    val accuracy = accuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: return 4
    return when {
        accuracy >= 100f -> 3
        accuracy >= 10f -> 4
        else -> 5
    }
}

/**
 * How closely this record places the user — in words, and only in words.
 *
 * The figure a receiver reports is its own estimate of its own error, and indoors it is both large
 * and optimistic: the wifi and cell answer, as [com.localkeep.core.location.LocationFixPolicy]
 * spells out, describes a database's confidence in where an access point is and puts no bound at
 * all on how far that is from the phone.
 *
 * **The reason the number is gone is not that it is unreliable. It is that it is discouraging.**
 * Decided by the principal 2026-09-03, after a metre figure had been printed here for a day:
 * *"Not so many people know that most GPS coordinates are 20 m etc. It's bad UX — a user that
 * doesn't know these things sees 20 m and it will cause a bad feeling."* Twenty metres is a normal,
 * good urban fix. A reader who knows that reads "20 m" as success; everyone else reads it as the
 * app failing at something, and there is nothing they can do about it either way. The cost is
 * accepted with open eyes: nothing on screen now tells a user how precise a saved location is, and
 * that was raised before the decision was reaffirmed. The stored [Coordinate.accuracyMeters] is
 * untouched at full precision — this is a rendering decision and it is reversible.
 *
 * ### Three bands, and why the boundaries are where they are
 *
 * The 2026-08-19 version of this had two, split at [FixRequest.settleAccuracyMeters], on the
 * argument that it was the only threshold the repo could defend. That was sound about *policy* and
 * wrong about *copy*: it put an ordinary 20 m reading in the same bucket as a bad 90 m one and then
 * forced the word "rough" onto it, which is precisely the discouragement the change above exists to
 * remove. The word now carries the entire claim, so each word has to stay true across the whole of
 * its own band — and that is a requirement two bands cannot meet.
 *
 * - [PRECISE] ends at [FixRequest.settleAccuracyMeters]. Not invented here: it is the radius the
 *   capture itself treats as one place, argued where it is declared.
 * - [STREET] ends at [STREET_ACCURACY_METERS]. "Street level" is the phrase mapping apps have
 *   already taught people, it is *true* at 20 m, and it names an ordinary outcome rather than a
 *   failure. This cut is a copy boundary and nothing else — it is chosen so the phrase stays honest
 *   at the top of its band, and no behaviour anywhere keys on it.
 * - [AREA] is everything past that, up to the [FixRequest.maxAccuracyMeters] the capture will
 *   accept at all. "Approximate" is neutral where "rough" was pejorative, and says the same thing.
 *
 * Null when the fix carried no accuracy at all — an unknown error is not a small one, and the
 * caption says nothing rather than guessing.
 */
internal enum class AccuracyBand {
    /** Inside the radius the capture treats as one place. */
    PRECISE,

    /** Good enough to name the street. The ordinary outcome of an urban capture. */
    STREET,

    /** Admissible, and no more than that. */
    AREA,
}

internal fun Coordinate.displayAccuracyBand(): AccuracyBand? =
    accuracyMeters
        ?.takeIf { it.isFinite() && it >= 0f }
        ?.let {
            when {
                it <= SETTLE_ACCURACY_METERS -> AccuracyBand.PRECISE
                it <= STREET_ACCURACY_METERS -> AccuracyBand.STREET
                else -> AccuracyBand.AREA
            }
        }

/**
 * The top of [AccuracyBand.STREET], and the one threshold in this file that is not lifted from
 * [FixRequest].
 *
 * Said plainly because the alternative is a reader assuming it came from the receiver policy: this
 * is a *copy* boundary. It is the widest reading at which the words "street level" are still true —
 * past about 50 m a fix no longer distinguishes one street from the next, and the phrase would be
 * making a claim the reading does not support. Nothing behavioural depends on it: the offer gate
 * and the indoor explanation both key on [AccuracyBand.PRECISE], which is the [FixRequest]
 * threshold, so moving this number changes a word on screen and nothing else.
 */
private const val STREET_ACCURACY_METERS = 50f

/**
 * Whether an indoor explanation is the likely reason a capture stopped where it did.
 *
 * Not a guess about walls — the app cannot see them. It is the only inference the data supports: a
 * satellite series that has flattened *and* flattened somewhere loose is what being under a roof
 * looks like from here, and it is the one situation in which "this is as good as it gets" needs a
 * reason attached or it reads as the app malfunctioning. A capture that settles tight has nothing
 * to explain, and a capture still improving has not settled on anything yet — which is why the
 * hint used to be wrong on every outdoor capture and during the first seconds of every indoor one.
 *
 * "Somewhere loose" is *anything wider than [AccuracyBand.PRECISE]*, which is the same set of
 * readings this fired on when there were two bands — [AccuracyBand.STREET] and [AccuracyBand.AREA]
 * together are exactly the old `ROUGH`. Written as `!= PRECISE` rather than as a pair of cases on
 * purpose: the condition is "outside the settle radius", a [FixRequest] threshold, and it must not
 * start following the copy boundary if that boundary ever moves.
 */
internal fun indoorExplanationApplies(
    convergence: CaptureConvergence,
    fix: Coordinate?,
): Boolean = convergence == CaptureConvergence.SETTLED &&
    fix?.displayAccuracyBand()?.let { it != AccuracyBand.PRECISE } == true

/**
 * The admission radius, lifted from the shipped [FixRequest] rather than restated.
 *
 * Read once at class-init: a `FixRequest()` per caption would allocate on every recomposition to
 * read a compile-time constant.
 */
private val SETTLE_ACCURACY_METERS: Float = FixRequest().settleAccuracyMeters

/**
 * Whether the running capture has produced something worth offering to keep.
 *
 * This replaces "a fix exists" as the gate on "Use this reading", and the difference is the whole of
 * the principal's 5.5: the offer used to appear the instant the first satellite epoch landed, in
 * amber, on a reading nobody would want — *"it never actually says that it's not ready and the
 * button immediately appears"*. Now the offer waits until one of three things is true, and every
 * threshold is lifted from [FixRequest] rather than invented:
 *
 * - the reading is inside the radius the capture itself treats as one place ([AccuracyBand.PRECISE],
 *   i.e. within `FixRequest().settleAccuracyMeters`);
 * - the series has stopped improving, so waiting buys nothing whatever the band;
 * - the tracking floor has passed. Below `FixRequest().minTrackingMillis` the capture will not take
 *   its own early exit, so offering the user one would be offering a decision the policy is not yet
 *   willing to make. Above it — the ordinary indoor case — it is how a capture finishes.
 *
 * Monotonic within one capture: none of the three ever un-sets, which is what lets the caller give
 * the caption a one-shot entrance.
 */
internal fun readingIsOfferable(
    fix: Coordinate?,
    convergence: CaptureConvergence,
    trackingFloorPassed: Boolean,
): Boolean = fix != null && (
    fix.displayAccuracyBand() == AccuracyBand.PRECISE ||
        convergence == CaptureConvergence.SETTLED ||
        trackingFloorPassed
    )

/** The tracking floor, lifted from the shipped [FixRequest] rather than restated. */
internal val MIN_TRACKING_MILLIS: Long = FixRequest().minTrackingMillis

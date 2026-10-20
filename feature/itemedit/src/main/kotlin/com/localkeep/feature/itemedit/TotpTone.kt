package com.localkeep.feature.itemedit

/**
 * How much time is left on a TOTP code, as a decision rather than a number.
 *
 * Two screens draw the same countdown — the item detail card and the editor's live preview — and
 * until 2026-08-10 each carried its own copy of the thresholds. They had already drifted once in
 * spirit (the same numbers written twice is a drift waiting to happen), and the boundary is a design
 * decision, not an implementation detail of either screen.
 *
 * The thresholds are **absolute seconds, deliberately not a fraction of the period**. They describe
 * how long it takes a person to read six digits and type them into another app, and that does not
 * get longer because the code happens to have a 60-second period.
 */
enum class TotpTone {
    /** Plenty of time. Jade. */
    CALM,

    /** Enough time if you start now. Amber. */
    NEAR,

    /** Probably not enough time; wait for the next one. Coral. */
    EXPIRING,
}

/**
 * [seconds] is the remaining whole seconds as `TotpGenerator.remainingSeconds` reports it — a value
 * in `1..period`, ticking once per second.
 */
fun totpToneFor(seconds: Int): TotpTone = when {
    seconds <= 4 -> TotpTone.EXPIRING
    seconds <= 7 -> TotpTone.NEAR
    else -> TotpTone.CALM
}

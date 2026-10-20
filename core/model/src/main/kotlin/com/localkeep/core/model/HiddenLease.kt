package com.localkeep.core.model

import java.time.Duration
import java.time.Instant

/**
 * The lease vocabulary for presentation concealment: how a hidden [VaultItem] or [Folder] records
 * *until when* it stays hidden, and the one rule that turns a stored instant into a visible/hidden
 * decision.
 *
 * A lease is a plain wall-clock [Instant] held in `hiddenUntil`. Concealment is a presentation
 * projection over a *decrypted* vault, not encryption — see the design's global constraints — so this
 * deliberately holds no key material and makes no claim against a backup, forensic access, or a
 * device whose clock the user can move. Duress remains the coercion feature.
 */
object HiddenLease {
    /**
     * "Hidden with no end date." A real instant rather than a second null-flag encoding so that
     * `hiddenUntil` has exactly one meaning — the moment concealment ends — and the merge and
     * normalization rules that order two leases never have to special-case a "forever" value.
     *
     * 9999-12-31T23:59:59Z is far past any lease a user can set ([MAX_CUSTOM] caps those) yet well
     * inside [Instant]'s range and, at whole-second precision, survives the millisecond round-trip
     * `InstantSerializer` performs without drift.
     */
    val INDEFINITE: Instant = Instant.parse("9999-12-31T23:59:59Z")

    /**
     * The longest finite lease a user may set: 3,653 days (ten years, leap days included). A ceiling
     * at all because a custom duration is built from a number the user types, and unbounded checked
     * arithmetic on it is an overflow the UI would have to guard anyway; ten years because a longer
     * intent is what [INDEFINITE] is for.
     */
    val MAX_CUSTOM: Duration = Duration.ofDays(3_653)

    /**
     * Whether a lease of [until] is still concealing at [now].
     *
     * Hidden while `until > now`; visible exactly when `until == now` — the boundary belongs to the
     * visible side, so an expiry is observed the instant it arrives rather than one tick later. A
     * null [until] is "never hidden" and is always visible.
     */
    fun isHidden(until: Instant?, now: Instant): Boolean {
        if (until == null) return false
        return until.isAfter(now)
    }

    /**
     * The lease instant for a custom "[amount] [unit]" duration from [now], or null if the input is
     * not a usable lease — a non-positive amount, an arithmetic overflow, or a total past
     * [MAX_CUSTOM]. Decision 9: a custom duration is a positive integer plus Minutes/Hours/Days,
     * converted with exact checked arithmetic and no date picker.
     *
     * Returning null rather than throwing keeps the caller a plain validity check; the UI turns a null
     * into an inline "enter a shorter duration" error and commits nothing.
     */
    fun customLeaseOrNull(now: Instant, amount: Long, unit: HiddenDurationUnit): Instant? {
        if (amount <= 0) return null
        val total = try {
            unit.unit.multipliedBy(amount)
        } catch (overflow: ArithmeticException) {
            return null
        }
        if (total > MAX_CUSTOM) return null
        return try {
            now.plus(total)
        } catch (overflow: ArithmeticException) {
            return null
        } catch (tooLarge: java.time.DateTimeException) {
            return null
        }
    }
}

/** The units a custom hidden-duration entry accepts. No smaller than a minute, no date picker. */
enum class HiddenDurationUnit(val unit: java.time.Duration) {
    MINUTES(java.time.Duration.ofMinutes(1)),
    HOURS(java.time.Duration.ofHours(1)),
    DAYS(java.time.Duration.ofDays(1)),
}

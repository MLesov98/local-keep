package com.localkeep.feature.unlock

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * How often the lockout sentence is recomputed. Matches `ClipboardCountdown`, the app's other
 * visible timer: fine enough that the number never appears to skip, coarse enough to be free.
 */
internal const val RETRY_TICK_MS = 250L

/**
 * The whole seconds left on a lockout, emitted until the wait is over and then a final `0`.
 *
 * The screen used to render a number computed once, in the handler that caught
 * `VaultLockedException`, and never again — so "Try again in 5 seconds" outlived its own window and
 * the only way to learn the wait had ended was to guess and retype, which a wrong guess extends.
 *
 * [nowMs] is `SystemClock.elapsedRealtime` because that is the clock
 * `VaultLockManager.checkThrottle` sets the deadline on; a wall clock would drift against the very
 * deadline this describes. It is a parameter, and this is a free function rather than something
 * inside the coroutine that uses it, so the ticking can be driven by a fake clock without a
 * Context, a Keystore and a real vault — the same reason `biometricErrorIsUserChoice` was pulled
 * out.
 *
 * Presentation only. Whether an attempt is allowed remains `checkThrottle`'s decision alone: the
 * last emission clears the sentence, it does not grant anything.
 */
internal fun retryCountdown(
    retryAfterMs: Long,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
    tickMs: Long = RETRY_TICK_MS,
): Flow<Int> = flow {
    val deadlineMs = nowMs() + retryAfterMs
    while (true) {
        val remainingMs = (deadlineMs - nowMs()).coerceAtLeast(0L)
        // Ceiling, so the sentence says "1 second" for the whole of the last one rather than
        // reading "0 seconds" while the throttle is still refusing attempts.
        emit(((remainingMs + 999) / 1_000).toInt())
        if (remainingMs == 0L) return@flow
        // The last sleep lands exactly on the deadline instead of a tick past it.
        delay(minOf(tickMs, remainingMs))
    }
}

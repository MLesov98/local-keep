package com.localkeep.core.vault

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the *single* nearest-expiry refresh timer for the whole app, and only while at least one
 * foreground screen wants it.
 *
 * Each screen calls [acquire] from its `repeatOnLifecycle(STARTED)` and [release] when it stops, so
 * the timer runs exactly when something is on screen to see a lease expire and never from a
 * back-stacked collector. When it fires it calls [onExpiry] — which recomputes the shared
 * presentation — and reschedules to the next boundary. A lock, which cancels the scope this launches
 * on and drops the projection to Locked, is what stops it; [release] to zero also cancels it.
 *
 * Lifecycle-free by construction: it takes plain `acquire`/`release` calls rather than an Android
 * `Lifecycle`, so `:core:vault` keeps no UI dependency and the screens own the lifecycle binding.
 */
class ForegroundVisibilityCoordinator(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val nextExpiry: () -> java.time.Instant?,
    private val onExpiry: () -> Unit,
) {
    private val monitor = Any()
    private var refCount = 0
    private var timerJob: Job? = null

    /** A foreground screen begins observing. The first acquirer starts the timer. */
    fun acquire() {
        synchronized(monitor) {
            refCount++
            if (refCount == 1) rescheduleLocked()
        }
    }

    /** A foreground screen stops observing. The last release cancels the timer. */
    fun release() {
        synchronized(monitor) {
            if (refCount > 0) refCount--
            if (refCount == 0) {
                timerJob?.cancel()
                timerJob = null
            }
        }
    }

    /** Recompute the boundary — call after a save/reveal/hide changes the nearest expiry. */
    fun reschedule() {
        synchronized(monitor) { rescheduleLocked() }
    }

    private fun rescheduleLocked() {
        timerJob?.cancel()
        timerJob = null
        if (refCount <= 0) return
        val expiry = nextExpiry() ?: return
        val delayMs = Duration.between(clock.instant(), expiry).toMillis().coerceAtLeast(0)
        timerJob = scope.launch {
            delay(delayMs)
            onExpiry()
            // The projection this expiry uncovered may itself have exposed the next-nearest lease.
            reschedule()
        }
    }
}

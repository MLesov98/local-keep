package com.localkeep.feature.unlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The number the lockout sentence shows actually falls.
 *
 * The defect this guards was a value frozen at the instant `VaultLockedException` was caught:
 * "Try again in 5 seconds" was still on screen ten seconds later. Driven here by the virtual clock
 * so the assertion is about the sequence of numbers, not about how long the test takes.
 */
class RetryCountdownTickTest {

    @Test
    fun `the seconds fall as the clock advances`() = runTest {
        val clock = testScheduler
        val seen = retryCountdown(
            retryAfterMs = 5_000,
            nowMs = { clock.currentTime },
            tickMs = RETRY_TICK_MS,
        ).toList()

        assertTrue(seen.first() == 5, "the first value is the full wait; found ${seen.first()}")
        assertTrue(
            seen.distinct().size > 1,
            "the countdown never moved off ${seen.first()} — this is the frozen sentence the " +
                "device pass watched outlive its own window. Saw: ${seen.distinct()}",
        )
        assertEquals(
            listOf(5, 4, 3, 2, 1, 0),
            seen.distinct(),
            "every whole second should be shown once, in order, ending at zero",
        )
        seen.zipWithNext { a, b ->
            assertTrue(b <= a, "the countdown went backwards: $a then $b")
        }
    }

    /**
     * Ceiling, not truncation: the last whole second still reads "1 second" while the throttle is
     * still refusing attempts. Zero is what ends the sentence, and it arrives exactly on the
     * deadline rather than a tick past it.
     */
    @Test
    fun `it reaches zero exactly at the deadline`() = runTest {
        val clock = testScheduler
        var last = -1
        retryCountdown(retryAfterMs = 1_500, nowMs = { clock.currentTime }, tickMs = RETRY_TICK_MS)
            .collect { last = it }
        assertEquals(0, last, "the countdown must end at zero so the sentence clears")
        assertEquals(
            1_500L,
            clock.currentTime,
            "the final tick must land on the deadline, not past it",
        )
    }

    /** A wait that has already expired says so at once instead of ticking from a stale number. */
    @Test
    fun `an expired lockout emits zero immediately`() = runTest {
        val clock = testScheduler
        val seen = retryCountdown(retryAfterMs = 0, nowMs = { clock.currentTime }).toList()
        assertEquals(listOf(0), seen)
        assertEquals(0L, clock.currentTime)
    }
}

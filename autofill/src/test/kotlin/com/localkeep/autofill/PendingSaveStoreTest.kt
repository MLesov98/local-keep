package com.localkeep.autofill

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PendingSaveStoreTest {
    /**
     * The store's monotonic clock, driven by hand.
     *
     * `SystemClock.elapsedRealtime()` is not mocked in plain JVM unit tests, so the production
     * default throws here; and a TTL is only testable at all on a clock the test can move. Starts
     * at a plausible uptime rather than 0 so nothing accidentally passes because both sides are
     * zero.
     */
    private var elapsedRealtimeMs = 30_000L
    private lateinit var previousClock: () -> Long

    @Before
    fun installClock() {
        previousClock = PendingSaveStore.elapsedRealtimeMs
        PendingSaveStore.elapsedRealtimeMs = { elapsedRealtimeMs }
    }

    @After
    fun restoreClock() {
        PendingSaveStore.elapsedRealtimeMs = previousClock
    }

    @Test
    fun consumesProposalOnlyOnce() {
        val password = "temporary".toCharArray()
        val token = PendingSaveStore.put(
            PendingSaveStore.Entry(
                username = "user",
                password = password,
                webDomain = "example.com",
                packageName = "com.example.browser",
                existingItemId = null,
            ),
        )

        assertNotNull(PendingSaveStore.consume(token))
        assertNull(PendingSaveStore.consume(token))
        password.fill('\u0000')
    }

    @Test
    fun discardWipesPassword() {
        val password = "temporary".toCharArray()
        val token = PendingSaveStore.put(
            PendingSaveStore.Entry(
                username = "user",
                password = password,
                webDomain = null,
                packageName = "com.example",
                existingItemId = null,
            ),
        )

        PendingSaveStore.discard(token)

        assertNull(PendingSaveStore.peek(token))
        assertArrayEquals(CharArray(password.size), password)
    }

    /**
     * The TTL has to run without anyone calling back in.
     *
     * `put` mints a token for a PendingIntent the platform may never launch — the user dismisses the
     * save bar and that is the end of it. The store used to purge only from `put`/`peek`/`consume`,
     * so in exactly that case nothing ever ran and the password `CharArray` stayed in the process
     * for its whole life. This fails against the old store: with no further calls, nothing wipes it.
     */
    @Test
    fun expiredProposalIsWipedWithNoFurtherCalls() {
        val previousDelay = PendingSaveStore.sweepDelayMs
        PendingSaveStore.sweepDelayMs = 50
        try {
            val password = "temporary".toCharArray()
            PendingSaveStore.put(
                PendingSaveStore.Entry(
                    username = "user",
                    password = password,
                    webDomain = "example.com",
                    packageName = null,
                    existingItemId = null,
                    // Already past the TTL, so the scheduled sweep has something to find.
                    createdAtMs = elapsedRealtimeMs - 200_000L,
                ),
            )

            // Deliberately no peek/consume in the loop — a read would run the old in-line purge and
            // the test would pass against the very code it exists to catch.
            val deadline = System.currentTimeMillis() + 5_000L
            while (!password.all { it.code == 0 } && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }

            assertArrayEquals(CharArray(password.size), password)
        } finally {
            PendingSaveStore.sweepDelayMs = previousDelay
        }
    }

    /**
     * The TTL is a duration, so it has to be measured on a clock that only ever advances at the
     * rate time passes.
     *
     * Here a proposal is stamped 30 seconds after boot and read 5 seconds later — comfortably
     * inside the two-minute window, so it must still be there. Measured against the wall clock
     * instead, the very same entry reads as older than the epoch and is purged on the spot, and
     * the user who then taps the platform save bar reaches SaveConfirmActivity's expired dead end
     * with the password already wiped. That is the same arithmetic, on a larger scale, that a
     * forward NTP correction performs on a live proposal.
     *
     * The stamp is written out rather than left to the default so the test states the timebase it
     * means: it goes red the moment either half of the comparison — the stamp or the `now` — goes
     * back to `System.currentTimeMillis()`.
     */
    @Test
    fun freshProposalSurvivesTheWallClockRunningAheadOfIt() {
        val password = "temporary".toCharArray()
        val token = PendingSaveStore.put(
            PendingSaveStore.Entry(
                username = "user",
                password = password,
                webDomain = "example.com",
                packageName = null,
                existingItemId = null,
                createdAtMs = elapsedRealtimeMs,
            ),
        )

        elapsedRealtimeMs += 5_000L

        assertNotNull("a five-second-old save proposal was expired", PendingSaveStore.peek(token))
        PendingSaveStore.discard(token)
        assertArrayEquals(CharArray(password.size), password)
    }
}

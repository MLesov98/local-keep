package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The half of the unlock backoff that [UnlockThrottlePersistenceTest] says it does not cover.
 *
 * That test exercises [UnlockThrottle] over a fake store, and its own KDoc names the gap: the
 * `SharedPreferences` adapter ([PrefsThrottleStore]) needs a `Context`, this module's unit tests
 * have no Robolectric, "so its two-key read/write is not covered here and this test does not claim
 * it is." This is that claim, made on a device.
 *
 * What a process death crosses is the file, not the object. So every assertion below reads through a
 * *second* [PrefsThrottleStore] built from scratch — the next process, minus the fork.
 *
 * The clock is injected so a five-minute lockout can be waited out without waiting.
 */
@RunWith(AndroidJUnit4::class)
class UnlockThrottleStoreInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private var now = 1_700_000_000_000L

    /** A fresh adapter over the same file — the seam the next process comes back through. */
    private fun store(): ThrottleStore = PrefsThrottleStore(context)

    private fun throttle(store: ThrottleStore) = UnlockThrottle(
        store = store,
        maxFailedAttempts = MAX_FAILED_ATTEMPTS,
        maxBackoffMs = MAX_BACKOFF_MS,
        wallClockMs = { now },
    )

    @BeforeTest
    @AfterTest
    fun clearTheFile() {
        store().clear()
    }

    @Test
    fun aLockoutArmedInOneProcessIsStillArmedWhenTheFileIsReadBack() {
        val first = throttle(store())
        repeat(MAX_FAILED_ATTEMPTS) { first.recordFailure(KNOWLEDGE) }
        assertTrue(first.remainingLockoutMs(KNOWLEDGE) > 0L, "five wrong guesses must arm a lockout")

        // The process dies here. Nothing carries over but `unlock_throttle.xml`.
        val next = throttle(store())
        assertTrue(
            next.remainingLockoutMs(KNOWLEDGE) > 0L,
            "a force-stop must not clear the lockout — it was the whole cost of guessing",
        )
    }

    /**
     * Both keys, not just the deadline. The attempt count is what keeps the backoff doubling, and a
     * store that wrote only the deadline would look correct for exactly one lockout and then restart
     * the exponent at one second on every subsequent process.
     */
    @Test
    fun theAttemptCountSurvivesTooSoTheBackoffContinuesInsteadOfRestarting() {
        val first = throttle(store())
        repeat(MAX_FAILED_ATTEMPTS + 2) { first.recordFailure(KNOWLEDGE) }
        val armed = first.remainingLockoutMs(KNOWLEDGE)

        // Serve the lockout, then die: the deadline is spent but the count must not be.
        now += armed + 1
        val next = throttle(store())
        assertEquals(0L, next.remainingLockoutMs(KNOWLEDGE), "the lockout it served must expire")

        next.recordFailure(KNOWLEDGE)
        assertEquals(
            armed * 2,
            next.remainingLockoutMs(KNOWLEDGE),
            "the next lockout must continue the exponent, not restart it at one second",
        )
    }

    /** Per credential on disk as well as in memory — see the fake-store test for why that matters. */
    @Test
    fun theTwoCredentialsKeepSeparateRecordsInTheFile() {
        val first = throttle(store())
        repeat(MAX_FAILED_ATTEMPTS + 3) { first.recordFailure(BIOMETRIC) }

        val next = throttle(store())
        assertTrue(next.remainingLockoutMs(BIOMETRIC) > 0L)
        assertEquals(
            0L,
            next.remainingLockoutMs(KNOWLEDGE),
            "a cancelled fingerprint prompt must not lock out the master password",
        )
    }

    /**
     * The control. A successful unlock clears the file, and the next process must read back nothing
     * — otherwise persisting the backoff turns it into a lockout its owner cannot shed.
     */
    @Test
    fun aSuccessfulUnlockClearsTheFileAndTheNextProcessReadsBackNothing() {
        val first = throttle(store())
        repeat(MAX_FAILED_ATTEMPTS) { first.recordFailure(KNOWLEDGE) }

        first.reset()

        assertTrue(store().read().isEmpty(), "the persisted counters must go with the reset")
        assertEquals(0L, throttle(store()).remainingLockoutMs(KNOWLEDGE))
    }

    private companion object {
        /** The names [VaultLockManager] passes: `Credential.KNOWLEDGE.name` and `BIOMETRIC.name`. */
        const val KNOWLEDGE = "KNOWLEDGE"
        const val BIOMETRIC = "BIOMETRIC"

        /** Mirrors `VaultLockManager`'s own constants, whose companion is private to that class. */
        const val MAX_FAILED_ATTEMPTS = 5
        const val MAX_BACKOFF_MS = 300_000L
    }
}

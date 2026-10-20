package com.localkeep.core.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The unlock backoff must outlive the process that armed it.
 *
 * It did not. `VaultLockManager` held the failure count and the lockout deadline in two
 * `mutableMapOf`s and nowhere else, so `am force-stop com.localkeep.foss` — or the system
 * reclaiming the process, or the user swiping the app off the recents list — put both back to zero.
 * Five wrong guesses, force-stop, five more, indefinitely: the throttle cost a patient attacker one
 * gesture every five attempts and bought the vault nothing.
 *
 * What is exercised here is [UnlockThrottle] over a store that survives the object, which is
 * exactly the seam a process death crosses — a second instance reading the same store *is* the next
 * process, minus the Android `SharedPreferences` adapter. That adapter ([PrefsThrottleStore]) needs
 * a `Context` and therefore a device; this module's unit tests have no Robolectric (see
 * `core/vault/build.gradle.kts`), so its two-key read/write is not covered here and this test does
 * not claim it is.
 *
 * The clock is injected rather than read, so a lockout can be waited out without waiting.
 */
class UnlockThrottlePersistenceTest {

    /** The store, minus the file. Outlives the [UnlockThrottle] over it, which is the whole point. */
    private class FakeStore : ThrottleStore {
        val records = mutableMapOf<String, ThrottleRecord>()
        var clears = 0

        override fun read(): Map<String, ThrottleRecord> = records.toMap()

        override fun write(credential: String, record: ThrottleRecord) {
            records[credential] = record
        }

        override fun clear() {
            clears++
            records.clear()
        }
    }

    private val store = FakeStore()
    private var now = 1_700_000_000_000L

    private fun throttle() = UnlockThrottle(
        store = store,
        maxFailedAttempts = MAX_FAILED_ATTEMPTS,
        maxBackoffMs = MAX_BACKOFF_MS,
        wallClockMs = { now },
    )

    @Test
    fun `a lockout armed in one process is still armed in the next`() {
        val first = throttle()
        repeat(MAX_FAILED_ATTEMPTS) { first.recordFailure(KNOWLEDGE) }
        assertTrue(first.remainingLockoutMs(KNOWLEDGE) > 0L, "five wrong guesses must arm a lockout")

        // The process dies here. Nothing carries over but the store.
        val second = throttle()
        assertTrue(
            second.remainingLockoutMs(KNOWLEDGE) > 0L,
            "a force-stop must not clear the lockout — it was the whole cost of guessing",
        )
    }

    @Test
    fun `the failure count survives too, so the backoff keeps doubling instead of restarting`() {
        val first = throttle()
        repeat(MAX_FAILED_ATTEMPTS + 2) { first.recordFailure(KNOWLEDGE) }
        val armed = first.remainingLockoutMs(KNOWLEDGE)

        // Wait the lockout out, then die: the deadline is spent but the count must not be.
        now += armed + 1
        val second = throttle()
        assertEquals(0L, second.remainingLockoutMs(KNOWLEDGE), "the lockout it served must expire")

        second.recordFailure(KNOWLEDGE)
        assertEquals(
            armed * 2,
            second.remainingLockoutMs(KNOWLEDGE),
            "the next lockout must continue the exponent, not restart it at one second",
        )
    }

    /**
     * The control. A successful unlock is the legitimate user proving they are present, and it has
     * always cleared the backoff; persisting it must not turn that into a lockout nobody can shed.
     */
    @Test
    fun `a successful unlock clears the backoff in this process and the next`() {
        val first = throttle()
        repeat(MAX_FAILED_ATTEMPTS) { first.recordFailure(KNOWLEDGE) }
        first.reset()

        assertEquals(0L, first.remainingLockoutMs(KNOWLEDGE))
        assertEquals(1, store.clears)
        assertTrue(store.records.isEmpty(), "the persisted counters must go with it")
        assertEquals(0L, throttle().remainingLockoutMs(KNOWLEDGE))
    }

    /**
     * The second control. The two credentials keep separate counters — a cancelled fingerprint
     * prompt is not a guess at the master password, and arming the password's backoff with one is
     * how a user gets locked out of their own vault by a sensor they never typed into.
     */
    @Test
    fun `biometric failures do not lock out the password`() {
        val throttle = throttle()
        repeat(MAX_FAILED_ATTEMPTS + 3) { throttle.recordFailure(BIOMETRIC) }

        assertTrue(throttle.remainingLockoutMs(BIOMETRIC) > 0L)
        assertEquals(0L, throttle.remainingLockoutMs(KNOWLEDGE))
    }

    /**
     * A persisted deadline is wall-clock, and a wall clock can be moved. Backwards is the direction
     * that matters: a year of "remaining" lockout would be a permanent one, and it would fall on
     * the vault's owner — an attacker simply moves the clock the other way, which costs them the
     * same single force-stop the throttle already could not prevent.
     */
    @Test
    fun `a clock moved backwards cannot extend a lockout past the maximum`() {
        val throttle = throttle()
        repeat(MAX_FAILED_ATTEMPTS) { throttle.recordFailure(KNOWLEDGE) }

        now -= 365L * 24 * 60 * 60 * 1_000
        assertEquals(MAX_BACKOFF_MS, throttle.remainingLockoutMs(KNOWLEDGE))
    }

    /** Failures that arm the longest lockout this throttle can legitimately arm: 1 s doubled up to the cap. */
    private fun armMaxLockout(throttle: UnlockThrottle) {
        repeat(MAX_FAILED_ATTEMPTS + 9) { throttle.recordFailure(KNOWLEDGE) }
        assertEquals(MAX_BACKOFF_MS, throttle.remainingLockoutMs(KNOWLEDGE), "setup: the lockout must sit at the cap")
    }

    /**
     * The clamp above only bounds what a *read* reports. Reading `deadline - now` and clamping it each
     * time left the stored deadline a year out, so after serving the full five minutes the next read
     * was "five minutes" again, for as long as the clock stayed behind the original deadline.
     */
    @Test
    fun `a lockout armed before the clock moved back still ends within the maximum`() {
        val throttle = throttle()
        armMaxLockout(throttle)
        val armedAt = now

        now = armedAt - DAY_MS
        assertTrue(throttle.remainingLockoutMs(KNOWLEDGE) <= MAX_BACKOFF_MS)

        now += MAX_BACKOFF_MS + 1
        assertEquals(0L, throttle.remainingLockoutMs(KNOWLEDGE), "the full countdown must end the lockout")
    }

    @Test
    fun `reading a lockout the clock jump inflated rewrites the persisted deadline`() {
        val throttle = throttle()
        armMaxLockout(throttle)
        val attemptsBefore = store.records.getValue(KNOWLEDGE).attempts

        now -= DAY_MS
        throttle.remainingLockoutMs(KNOWLEDGE)

        val persisted = store.records.getValue(KNOWLEDGE)
        assertEquals(now + MAX_BACKOFF_MS, persisted.lockoutUntilEpochMs, "the deadline must be re-based onto the new clock")
        assertEquals(attemptsBefore, persisted.attempts, "re-basing a deadline must not reset or change the count")

        // And the next process sees the re-based deadline, not the old one.
        now += MAX_BACKOFF_MS + 1
        assertEquals(0L, throttle().remainingLockoutMs(KNOWLEDGE))
    }

    /** A store that cannot be written must not turn a read into a crash — the vault would not open. */
    @Test
    fun `a store that fails the re-base write still answers the clamped remainder`() {
        val failing = object : ThrottleStore {
            val inner = FakeStore()
            override fun read() = inner.read()
            override fun write(credential: String, record: ThrottleRecord) {
                if (inner.records[credential]?.lockoutUntilEpochMs?.let { it > now + MAX_BACKOFF_MS } == true) {
                    error("disk full")
                }
                inner.write(credential, record)
            }
            override fun clear() = inner.clear()
        }
        val throttle = UnlockThrottle(failing, MAX_FAILED_ATTEMPTS, MAX_BACKOFF_MS) { now }
        repeat(MAX_FAILED_ATTEMPTS + 9) { throttle.recordFailure(KNOWLEDGE) }

        now -= DAY_MS
        assertEquals(MAX_BACKOFF_MS, throttle.remainingLockoutMs(KNOWLEDGE))
    }

    /** Control: with no clock jump a lockout counts down and ends exactly at its deadline, untouched. */
    @Test
    fun `a lockout without a clock jump counts down and ends exactly, and is not rewritten`() {
        val throttle = throttle()
        armMaxLockout(throttle)
        val deadline = store.records.getValue(KNOWLEDGE).lockoutUntilEpochMs

        now += 100_000L
        assertEquals(200_000L, throttle.remainingLockoutMs(KNOWLEDGE))
        assertEquals(deadline, store.records.getValue(KNOWLEDGE).lockoutUntilEpochMs)

        now = deadline - 1
        assertEquals(1L, throttle.remainingLockoutMs(KNOWLEDGE))
        now = deadline
        assertEquals(0L, throttle.remainingLockoutMs(KNOWLEDGE))
    }

    /** Control: a clock moved forward ends the lockout early, as it always has; that is not this fix's to close. */
    @Test
    fun `a clock moved forward ends the lockout early`() {
        val throttle = throttle()
        armMaxLockout(throttle)

        now += DAY_MS
        assertEquals(0L, throttle.remainingLockoutMs(KNOWLEDGE))
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1_000

        /** The names [VaultLockManager] passes: `Credential.KNOWLEDGE.name` and `BIOMETRIC.name`. */
        const val KNOWLEDGE = "KNOWLEDGE"
        const val BIOMETRIC = "BIOMETRIC"

        /** Mirrors `VaultLockManager`'s own constants, whose companion is private to that class. */
        const val MAX_FAILED_ATTEMPTS = 5
        const val MAX_BACKOFF_MS = 300_000L
    }
}

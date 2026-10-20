package com.localkeep.autofill

import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Keeps submitted credentials out of Activity intents and consumes each save proposal once. */
internal object PendingSaveStore {
    private const val TTL_MS = 120_000L

    /**
     * The window a save captured against a *locked* vault gets instead, because that save cannot
     * complete until an unlock happens and the unlock is not this screen's to perform.
     *
     * Longer than [TTL_MS] because the ordinary two minutes measure "how long a confirmation may
     * sit in front of the user", and this measures "how long until the user next opens LocalKeep
     * and types their master password" — which includes walking back to the app, a mistyped
     * attempt, and Argon2 itself. Still short, and deliberately so: the entry holds a plaintext
     * password in this process's heap, so the queue is a hand-off, not a store. Nothing persists
     * it — process death drops it, which is the correct outcome for a secret with nowhere safe to
     * live outside the vault.
     */
    private const val QUEUED_TTL_MS = 300_000L

    /**
     * The clock the TTL is measured on. Monotonic: `elapsedRealtime` counts from boot and keeps
     * counting through sleep, and nothing — NTP, a timezone-less RTC fixup, the user — can move it.
     *
     * The TTL is a *duration*, and a duration taken as the difference of two `currentTimeMillis`
     * readings is only as trustworthy as the calendar between them. A forward correction while a
     * save bar was up expired a live proposal early, so the user who then tapped it reached
     * [SaveConfirmActivity]'s expired dead end with the password already wiped. A backward
     * correction did the opposite and left the entry unpurged until the next call came in.
     *
     * A `var` only so a test can drive it: `SystemClock` is not mocked in plain JVM unit tests, and
     * a clock the test cannot move is a clock the test cannot move *wrongly*.
     */
    internal var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }

    data class Entry(
        val username: String,
        val password: CharArray,
        val webDomain: String?,
        val packageName: String?,
        val existingItemId: UUID?,
        /**
         * Set instead of [username]/[password] when the captured form was a checkout rather than a
         * sign-in. Mutually exclusive with them in practice — see `SaveHandler.extractCard`, which
         * only runs when no login was found.
         */
        val card: Card? = null,
        /**
         * Captured while the vault was locked, so it is waiting for the next unlock to be offered.
         *
         * The platform calls `onSaveRequest` only after the user has already accepted its own
         * "Save password to LocalKeep?" sheet, so every entry here is a save the user said yes to.
         * When the vault is locked at that moment, Android can refuse the confirmation's
         * PendingIntent as a background activity launch (`BAL_BLOCK`, measured on API 35,
         * 2026-09-17) — nothing runs, nothing is shown, and the credential used to be wiped by the
         * TTL with no message anywhere. Flagged entries survive to [awaitingUnlock] so the next
         * unlock can offer them from the foreground, where the launch is allowed.
         */
        val awaitingUnlock: Boolean = false,
        /** Elapsed realtime, never wall clock — see [elapsedRealtimeMs]. */
        val createdAtMs: Long = elapsedRealtimeMs(),
        /** Fixed at construction so [touch] and `copy` cannot silently shorten a queued entry. */
        val ttlMs: Long = if (awaitingUnlock) QUEUED_TTL_MS else TTL_MS,
    )

    /**
     * A payment card captured from a checkout form.
     *
     * The number and the security code are `CharArray`, exactly like the login password above and
     * for the same reason: a `String` cannot be overwritten, so a PAN held as one stays legible in
     * the heap until the GC happens to reclaim it, and a heap dump is not a hypothetical thing to
     * defend against for a password manager. Holder and expiry are ordinary strings — they are
     * printed on the outside of the card.
     */
    data class Card(
        val number: CharArray,
        val securityCode: CharArray,
        val holder: String,
        val expiry: String,
    ) {
        fun wipe() {
            number.fill('\u0000')
            securityCode.fill('\u0000')
        }

        // Hand-written because the generated ones compare CharArray by identity, which would make
        // two equal cards unequal and one card unequal to itself after a copy. Nothing depends on
        // this today; it is here so nothing can quietly depend on the broken version later.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Card) return false
            return number.contentEquals(other.number) &&
                securityCode.contentEquals(other.securityCode) &&
                holder == other.holder &&
                expiry == other.expiry
        }

        override fun hashCode(): Int {
            var result = number.contentHashCode()
            result = 31 * result + securityCode.contentHashCode()
            result = 31 * result + holder.hashCode()
            result = 31 * result + expiry.hashCode()
            return result
        }
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /**
     * Runs the TTL on its own, instead of only when the next call happens to come in.
     *
     * The store used to purge from `put`/`peek`/`consume`, which is a sweep that requires traffic —
     * and the case that needs it is exactly the case with no traffic. `put` mints a token for a
     * PendingIntent the platform may simply never launch: the user dismisses the save bar, or the
     * app that triggered it goes away. Nothing then calls back in, and the password `CharArray` sits
     * in the process for as long as the process lives.
     *
     * Daemon thread, so it never holds the process open by itself.
     */
    private val sweeper: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "pending-save-sweep").apply { isDaemon = true }
        }
    }

    fun put(entry: Entry): UUID {
        purgeExpired()
        val token = UUID.randomUUID()
        entries[token] = entry
        scheduleSweep()
        return token
    }

    /**
     * A second past the deadline, so a sweep cannot race the entry's own expiry check.
     *
     * A `var` only so a test can shorten it. Waiting out two real minutes is not a test, and the
     * alternative — no test — is what let the unswept entry ship in the first place.
     */
    internal var sweepDelayMs: Long = TTL_MS + 1_000L

    private fun scheduleSweep() {
        sweeper.schedule(::sweep, sweepDelayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Purges, then re-arms while anything is still waiting.
     *
     * One sweep per `put` was enough while every entry shared one deadline. A save queued for the
     * next unlock outlives it: the sweep scheduled at the ordinary deadline finds the queued entry
     * still fresh, does nothing, and — being the last one anybody scheduled — leaves the plaintext
     * password in the process until some unrelated call happens to come in. That is precisely the
     * leak the sweeper was added to close, reopened by the longer window.
     */
    private fun sweep() {
        purgeExpired()
        if (entries.isNotEmpty()) scheduleSweep()
    }

    fun peek(token: UUID): Entry? {
        purgeExpired()
        return entries[token]
    }

    /**
     * Restarts the TTL for [token]. Called when the save detours through an unlock: typing a master
     * password can easily outlast the ordinary window, and expiring mid-unlock would drop the very
     * credential the detour exists to preserve. Keeps the short TTL for the common path.
     */
    fun touch(token: UUID) {
        val extended = entries.computeIfPresent(token) { _, entry ->
            entry.copy(createdAtMs = elapsedRealtimeMs())
        }
        // Push the sweep out with the deadline. Without this the scheduled purge fires while the
        // extended entry is still fresh, finds nothing to do, and nothing schedules another one.
        if (extended != null) scheduleSweep()
    }

    fun consume(token: UUID): Entry? {
        purgeExpired()
        return entries.remove(token)
    }

    /**
     * The oldest save still waiting for an unlock, or null when nothing is.
     *
     * Oldest first because it is the one closest to expiring, and because offering saves in the
     * order they were captured is the order the user made them in.
     */
    fun awaitingUnlock(): UUID? {
        purgeExpired()
        return entries.entries
            .filter { (_, entry) -> entry.awaitingUnlock }
            .minByOrNull { (_, entry) -> entry.createdAtMs }
            ?.key
    }

    /**
     * Drops every save waiting for an unlock, wiping its secrets.
     *
     * The duress session's answer. A decoy vault exists to be shown to someone who must believe it
     * is the whole of it, and a capture made by the real user would both land in the wrong vault and
     * announce — by name, on screen — that a real one was in use minutes ago.
     */
    fun discardAwaitingUnlock() {
        entries.entries
            .filter { (_, entry) -> entry.awaitingUnlock }
            .map { (token, _) -> token }
            .forEach(::discard)
    }

    fun discard(token: UUID) {
        entries.remove(token)?.let { entry ->
            entry.password.fill('\u0000')
            // The card goes with it. A dismissed save used to wipe only the password, which was
            // complete while a login was the only thing an entry could hold; a captured PAN left
            // behind here would outlive the prompt the user just said no to.
            entry.card?.wipe()
        }
    }

    private fun purgeExpired(nowMs: Long = elapsedRealtimeMs()) {
        entries.entries.removeIf { (_, entry) ->
            val expired = nowMs - entry.createdAtMs > entry.ttlMs
            if (expired) {
                entry.password.fill('\u0000')
                entry.card?.wipe()
            }
            expired
        }
    }
}

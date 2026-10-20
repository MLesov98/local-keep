package com.localkeep.core.vault

import android.content.Context

/**
 * One credential's backoff, as it is written down.
 *
 * [lockoutUntilEpochMs] is **wall clock**, not `elapsedRealtime`. The point of persisting it is to
 * survive a process death, and `elapsedRealtime` restarts at zero on every reboot — a deadline
 * recorded in it would be read back as "already expired" by exactly the person who force-stopped
 * and rebooted to get past it.
 */
internal data class ThrottleRecord(
    val attempts: Int,
    /** Wall-clock instant the lockout ends, or 0 when no lockout is armed. */
    val lockoutUntilEpochMs: Long,
)

/** Where [UnlockThrottle] keeps its counters between processes. */
internal interface ThrottleStore {
    fun read(): Map<String, ThrottleRecord>

    fun write(credential: String, record: ThrottleRecord)

    fun clear()
}

/**
 * The on-disk half, in its own `SharedPreferences` file.
 *
 * Its own file rather than [LocalKeepSettings]: nothing here is a setting, and the two have
 * opposite lifetimes — one is the user's configuration, the other is a few counters that a
 * successful unlock erases.
 *
 * Nothing secret is stored. A failure count and a deadline say only that someone typed a wrong
 * password, which is not information the vault was keeping.
 *
 * `commit()`, not `apply()`. `apply()` hands the write to a background thread, and the event this
 * store exists to survive — a force-stop moments after the fifth wrong guess — is precisely the one
 * that kills that thread before it lands. The writes are two primitives on a file with four keys in
 * it, and every caller is already off the main thread inside an unlock.
 */
internal class PrefsThrottleStore(context: Context) : ThrottleStore {
    private val appContext = context.applicationContext ?: context

    // Lazy so constructing a VaultLockManager does no disk I/O: the manager is built at process
    // start, and the first read happens inside an unlock, on Dispatchers.Default.
    private val prefs by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun read(): Map<String, ThrottleRecord> = buildMap {
        for (key in prefs.all.keys) {
            if (!key.endsWith(ATTEMPTS_SUFFIX)) continue
            val credential = key.removeSuffix(ATTEMPTS_SUFFIX)
            val attempts = prefs.getInt(key, 0)
            if (attempts <= 0) continue
            put(
                credential,
                ThrottleRecord(
                    attempts = attempts,
                    lockoutUntilEpochMs = prefs.getLong(credential + UNTIL_SUFFIX, 0L),
                ),
            )
        }
    }

    override fun write(credential: String, record: ThrottleRecord) {
        prefs.edit()
            .putInt(credential + ATTEMPTS_SUFFIX, record.attempts)
            .putLong(credential + UNTIL_SUFFIX, record.lockoutUntilEpochMs)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val FILE = "unlock_throttle"
        const val ATTEMPTS_SUFFIX = "_attempts"
        const val UNTIL_SUFFIX = "_until_epoch_ms"
    }
}

/**
 * The failed-attempt backoff, and the only thing that remembers it across a process.
 *
 * It used to be two `mutableMapOf`s on [VaultLockManager] and nothing else, so `am force-stop` —
 * or the system reclaiming the process, or the user swiping the app away — reset the counter and
 * the deadline together. Five guesses, force-stop, five more guesses, at no cost: the throttle was
 * an inconvenience to whoever left the app running and no obstacle at all to anyone who did not.
 *
 * The counters are kept per credential kind; see [VaultLockManager]'s own note for why a cancelled
 * fingerprint prompt must not arm the master password's backoff.
 *
 * A wall clock can be moved backwards, so a deadline further out than [maxBackoffMs] — the longest
 * lockout this class can legitimately arm — can only mean the clock moved. [remainingLockoutMs]
 * re-bases such a deadline to `now + maxBackoffMs` and writes that back. Clamping only what a read
 * reports is not enough: the stored deadline stayed a year out, so every read after the full
 * countdown said "five minutes" again, and the lockout lasted until the clock caught up. Without
 * the re-base, setting the device clock back would turn a five-minute lockout into a permanent one,
 * which locks out the vault's owner and nobody else. The attempt count is left alone, so the
 * exponent keeps climbing.
 */
internal class UnlockThrottle(
    private val store: ThrottleStore,
    private val maxFailedAttempts: Int,
    private val maxBackoffMs: Long,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    // Lazy for the same reason the prefs file is, and tolerant of a store that cannot be read: a
    // throttle that throws on construction would make the vault unopenable, which is a far worse
    // failure than a backoff that starts from zero.
    private val records: MutableMap<String, ThrottleRecord> by lazy {
        runCatching { store.read().toMutableMap() }.getOrDefault(mutableMapOf())
    }

    /**
     * How much longer this credential is locked out, or 0 when an attempt may be made now.
     *
     * A read that finds a deadline beyond [maxBackoffMs] has found a clock that moved backwards, and
     * persists the corrected deadline. The write is best-effort like [recordFailure]'s: if it fails
     * the in-memory record is still re-based, so this process ends the lockout on time and a store
     * that cannot be written never makes the vault unopenable.
     */
    fun remainingLockoutMs(credential: String): Long {
        val record = records[credential] ?: return 0L
        val now = wallClockMs()
        val remaining = record.lockoutUntilEpochMs - now
        if (remaining > maxBackoffMs) {
            val rebased = record.copy(lockoutUntilEpochMs = now + maxBackoffMs)
            records[credential] = rebased
            runCatching { store.write(credential, rebased) }
            return maxBackoffMs
        }
        return remaining.coerceAtLeast(0L)
    }

    fun recordFailure(credential: String) {
        val attempts = (records[credential]?.attempts ?: 0) + 1
        val lockoutUntilEpochMs = if (attempts >= maxFailedAttempts) {
            val exponent = (attempts - maxFailedAttempts).coerceAtMost(18)
            wallClockMs() + minOf(1_000L shl exponent, maxBackoffMs)
        } else {
            0L
        }
        val record = ThrottleRecord(attempts, lockoutUntilEpochMs)
        records[credential] = record
        runCatching { store.write(credential, record) }
    }

    /** Clears every credential's backoff — see [VaultLockManager.resetThrottle] for why all of them. */
    fun reset() {
        records.clear()
        runCatching { store.clear() }
    }
}

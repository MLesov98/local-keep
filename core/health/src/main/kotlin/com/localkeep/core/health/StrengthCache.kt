package com.localkeep.core.health

import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Weak/not-weak verdicts already computed for a password, carried across scans inside one unlocked
 * session.
 *
 * ### Why this exists
 *
 * The strength pass is what the Health scan waits on — zxcvbn's matching is superlinear in length
 * and runs in the low milliseconds per password, so a vault whose every row carries its own
 * password spends minutes there (1B-F6: 221.8 s at 12 148 items, measured on device 2026-09-20).
 * [SecurityDashboard] already scores each *distinct* password once per scan. What it did not do was
 * remember: the screen re-scans on every vault commit, so editing one login re-scored the other
 * twelve thousand, and the verdict is a pure function of the password, so all but one of those
 * measurements had the answer already.
 *
 * ### Why the key is not the password
 *
 * [SecurityDashboard] used to carry a comment refusing exactly this, and the refusal was about
 * *lifetime*, not about caching: a map keyed by plaintext — or by a bare hash, which is a salt and
 * a hash sitting next to each other and therefore an offline-crackable artefact — would survive the
 * one state (locked) in which nothing password-derived is supposed to exist.
 *
 * So the key is an HMAC-SHA-256 tag under a key drawn from [SecureRandom] when the cache is first
 * used, held only in memory, never written anywhere, and **wiped by [forget]**. After a wipe the
 * remaining tags are keyed under bytes that no longer exist, which is what makes them inert rather
 * than merely stale. `feature/health` calls [forget] the moment the vault reports itself locked.
 *
 * Verdicts are booleans, so a surviving entry is never *wrong* — a password's score does not change
 * — only longer-lived than it should be. That is the whole property being protected here.
 */
class StrengthCache(private val random: SecureRandom = SecureRandom()) {

    /**
     * Guards [sessionKey], [generation] and every write to the verdict map. The map is concurrent on
     * its own for *reads*: `analyze` can be called from more than one screen's scan at a time, and
     * the expensive work (the `score` callback) must not be serialised behind a lock the way it
     * would be if the whole call sat inside one.
     */
    private val keyLock = Any()

    private var sessionKey: ByteArray? = null

    /**
     * Bumped by [forget]. A scan reads it once, when it starts, and passes it back on every
     * [getOrPut]; a call carrying an older number belongs to a scan that began before the last lock.
     */
    private var generation = 0L

    private val verdicts = ConcurrentHashMap<String, Boolean>()

    /** Entries currently held. For tests and for nothing else. */
    internal val size: Int get() = verdicts.size

    /** Whether a session key currently exists. For tests and for nothing else. */
    internal val holdsKey: Boolean get() = synchronized(keyLock) { sessionKey != null }

    /** The generation a scan beginning now belongs to - see [getOrPut]. */
    fun generation(): Long = synchronized(keyLock) { generation }

    /**
     * [score] this password, unless this session already has. Nothing about [password] is retained
     * except the verdict and a tag that cannot be reversed without [sessionKey].
     */
    fun getOrPut(password: String, score: (String) -> Boolean): Boolean =
        getOrPut(password, generation(), score)

    /**
     * As above, for a scan that read [generation] when it began.
     *
     * A scan that outlives a lock is the normal case on a big vault (the first one takes minutes),
     * and [forget] only wipes what exists at that instant: the next call here would draw a fresh key
     * and keep tagging every remaining password until the scan's own coroutine was cancelled, leaving
     * a live key and a tag per password in a vault that reports itself locked (2026-10-02). So a call
     * from an older generation still gets its answer - the scan is not told it failed - but neither
     * draws a key nor stores anything.
     */
    fun getOrPut(password: String, generation: Long, score: (String) -> Boolean): Boolean {
        val tag = tag(password, generation) ?: return score(password)
        verdicts[tag]?.let { return it }
        val verdict = score(password)
        // Under the lock, so a forget() landing during `score` clears the map *before* this check
        // fails rather than after this store succeeds.
        synchronized(keyLock) {
            if (this.generation == generation) verdicts[tag] = verdict
        }
        return verdict
    }

    /**
     * Drops every verdict and wipes the key they were keyed under.
     *
     * Called when the vault locks. Wiping the key is the part that matters: it is what stops a tag
     * left in a heap dump from being checkable against a password guess.
     */
    fun forget() {
        synchronized(keyLock) {
            generation++
            verdicts.clear()
            sessionKey?.let { Arrays.fill(it, 0) }
            sessionKey = null
        }
    }

    /** Null when [generation] is no longer current: the key it would need has been wiped. */
    private fun tag(password: String, generation: Long): String? {
        // The bytes are a second copy of the password and are wiped as soon as the MAC has eaten
        // them. The `String` itself cannot be — it belongs to the decrypted vault in memory, which
        // is where it already lived.
        val bytes = password.toByteArray(Charsets.UTF_8)
        val digest = try {
            synchronized(keyLock) {
                if (this.generation != generation) return null
                val mac = Mac.getInstance(MAC_ALGORITHM)
                mac.init(SecretKeySpec(key(), MAC_ALGORITHM))
                mac.doFinal(bytes)
            }
        } finally {
            Arrays.fill(bytes, 0)
        }
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun key(): ByteArray = synchronized(keyLock) {
        sessionKey ?: ByteArray(KEY_BYTES).also {
            random.nextBytes(it)
            sessionKey = it
        }
    }

    private companion object {
        const val MAC_ALGORITHM = "HmacSHA256"
        const val KEY_BYTES = 32
    }
}

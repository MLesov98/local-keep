package com.localkeep.autofill

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived correlation for username-first, password-second login flows.
 *
 * **[TTL_MS] is the only expiry, and entries deliberately outlive a vault lock.** That looks wrong
 * and is not: autofill runs while LocalKeep is backgrounded, so the background grace — five seconds
 * on Aggressive, thirty on Balanced — is always shorter than [TTL_MS]. A two-page login therefore
 * locks *between* the two fills; the user unlocks in place, `AuthActivity` re-enters the response,
 * and this cache is what narrows the second page's suggestion to the item they already picked.
 * Clearing on lock would defeat the cache in precisely the case it exists for. Eviction of an entry
 * nobody re-queries is opportunistic — swept from inside `remember()`, see [sweepExpired] — not
 * bounded to a fixed capacity; this is a process-lifetime cache of tiny records, not a large one.
 *
 * Nothing secret is stored to begin with: the key is `packageName|webDomain` and the value is an
 * item UUID and a timestamp. A duress session intersects to nothing, so it cannot correlate across
 * vaults either.
 */
object AutofillSessionCache {
    internal const val TTL_MS = 90_000L

    data class Entry(
        val itemId: UUID,
        val createdAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun remember(
        packageName: String?,
        webDomain: String?,
        itemId: UUID,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        entries[key(packageName, webDomain)] = Entry(itemId, nowMs)
        // Opportunistic GC: recall() only ever evicts the one key it was asked about, so a key
        // that is never re-queried — a two-page login abandoned after the first page — would
        // otherwise sit here for the process's lifetime. Piggybacking on remember() costs nothing
        // extra per fill and bounds the map to roughly what's been touched inside the last TTL.
        sweepExpired(nowMs)
    }

    private fun sweepExpired(nowMs: Long) {
        entries.entries.removeIf { (_, entry) -> nowMs - entry.createdAtMs > TTL_MS }
    }

    fun recall(
        packageName: String?,
        webDomain: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Entry? {
        val key = key(packageName, webDomain)
        val entry = entries[key] ?: return null
        if (nowMs - entry.createdAtMs > TTL_MS) {
            entries.remove(key, entry)
            return null
        }
        return entry
    }

    /** Test-only reset. Production eviction is [TTL_MS]-based expiry only — see the class KDoc. */
    internal fun clearAll() {
        entries.clear()
    }

    /** Test-only visibility into the bound [sweepExpired] keeps on process-lifetime growth. */
    internal val size: Int get() = entries.size

    private fun key(packageName: String?, webDomain: String?): String =
        "${packageName.orEmpty()}|${webDomain.orEmpty().lowercase()}"
}

package com.localkeep.core.model

import java.time.Instant

/**
 * The one place that decides how many previous passwords a [Login] keeps.
 *
 * Until 2026-07-31 there was no such place. Every autofill-captured password change appended to
 * [Login.passwordHistory] without a bound, a Bitwarden export carried the user's entire history
 * across on import, and nothing in the app ever displayed or deleted any of it. A password manager
 * that silently accumulates every password its user has ever had — in the vault, in every backup,
 * in every export — is the opposite of the claim this app makes about knowing what it retains.
 *
 * Both halves of the fix live here. [append] is the only way a new entry is added, and [trim] is
 * applied to anything arriving from outside, so the bound holds no matter which door the data came
 * through.
 */
object PasswordHistory {

    /**
     * How many previous passwords are kept, newest first.
     *
     * Five, not "all": the reason to keep any at all is to recognise a password you are about to
     * reuse and to recover from a rotation you did by mistake, and both of those needs are days
     * old, not years. Every entry past the useful ones is a live secret with no purpose sitting in
     * a file that gets backed up.
     */
    const val MAX_ENTRIES = 5

    /**
     * [existing] plus [previousPassword] as of [changedAt], newest first, bounded to [MAX_ENTRIES].
     *
     * Blank passwords are not recorded — there is nothing to remember, and an empty entry in a list
     * of secrets reads as data loss rather than as an item that never had a password.
     */
    fun append(
        existing: List<PasswordHistoryEntry>,
        previousPassword: String,
        changedAt: Instant,
    ): List<PasswordHistoryEntry> {
        if (previousPassword.isEmpty()) return trim(existing)
        return trim(listOf(PasswordHistoryEntry(previousPassword, changedAt)) + existing)
    }

    /**
     * Keeps the [MAX_ENTRIES] newest entries.
     *
     * Sorts before cutting rather than assuming the caller's order: history arriving from an import
     * is in whatever order that vendor wrote it, and taking the first five of an oldest-first list
     * would throw away exactly the entries worth keeping. Sorting is stable, so entries sharing a
     * timestamp — which a bulk import gives them — keep their original relative order.
     */
    fun trim(entries: List<PasswordHistoryEntry>): List<PasswordHistoryEntry> =
        if (entries.size <= MAX_ENTRIES && entries.isSortedNewestFirst()) {
            entries
        } else {
            entries.sortedByDescending(PasswordHistoryEntry::changedAt).take(MAX_ENTRIES)
        }

    private fun List<PasswordHistoryEntry>.isSortedNewestFirst(): Boolean =
        asSequence().zipWithNext().all { (a, b) -> !a.changedAt.isBefore(b.changedAt) }
}

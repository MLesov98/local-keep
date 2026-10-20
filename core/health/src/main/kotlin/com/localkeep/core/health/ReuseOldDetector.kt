package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Reused-password and stale-password detection over Login items in the vault.
 * Operates on plain in-memory [VaultItem]s (already decrypted by [VaultRepository] —
 * this module never touches the encrypted file or the DEK).
 */
object ReuseOldDetector {

    private fun loginPassword(item: VaultItem): String? {
        val content = item.content
        return if (content is Login && content.password.isNotEmpty()) content.password else null
    }

    /**
     * Groups non-trashed Login items by identical password. Only groups with 2+ items
     * are returned (i.e. the password is actually reused). Map key = the shared password
     * value — held only transiently in memory for this scan, never logged or persisted.
     *
     * Reuse is one password across several *accounts*. [sameAccount] is [DuplicateDetector]'s
     * output: items in one of its groups are copies of a single account, so a password whose
     * members are all copies of one account is not reused (2026-10-02) - it is a duplicate, which
     * the dashboard reports separately with the right advice. Without it, two copies of one account
     * were both listed as Reused and the user was told to rotate a password instead of deleting a copy.
     */
    fun findReused(
        items: List<VaultItem>,
        sameAccount: Collection<List<VaultItem>> = emptyList(),
    ): Map<String, List<VaultItem>> {
        val copyGroupsOf = HashMap<UUID, MutableList<Int>>()
        sameAccount.forEachIndexed { index, copies ->
            copies.forEach { copyGroupsOf.getOrPut(it.id) { mutableListOf() } += index }
        }
        return items.asSequence()
            .filter { !it.isDeleted }
            .mapNotNull { item -> loginPassword(item)?.let { pw -> pw to item } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 2 && accountsIn(it, copyGroupsOf) >= 2 }
    }

    /** How many distinct accounts [group] spans, counting items that share a duplicate group as one. */
    private fun accountsIn(group: List<VaultItem>, copyGroupsOf: Map<UUID, List<Int>>): Int {
        if (copyGroupsOf.isEmpty()) return group.size
        val parent = IntArray(group.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        val firstMemberOf = HashMap<Int, Int>()
        group.forEachIndexed { i, item ->
            copyGroupsOf[item.id]?.forEach { copyGroup ->
                val first = firstMemberOf.getOrPut(copyGroup) { i }
                parent[root(i)] = root(first)
            }
        }
        return group.indices.map(::root).toSet().size
    }

    /**
     * Non-trashed Login items whose PASSWORD was last set/changed more than [thresholdDays] days
     * ago. Uses [Login.passwordChangedAt] (bumped only when the password changes); falls back to
     * [VaultItem.updatedAt] for items written before that field existed. Using `updatedAt` alone
     * would reset the age on any metadata edit (rename, tag) and under-flag genuinely stale
     * passwords.
     */
    fun findOld(
        items: List<VaultItem>,
        thresholdDays: Int = 90,
        now: Instant = Instant.now(),
    ): List<VaultItem> {
        val cutoff = now.minus(thresholdDays.toLong(), ChronoUnit.DAYS)
        return items.filter { item ->
            if (item.isDeleted) return@filter false
            val login = item.content as? Login ?: return@filter false
            if (login.password.isEmpty()) return@filter false
            val changedAt = login.passwordChangedAt ?: item.updatedAt
            changedAt.isBefore(cutoff)
        }
    }
}

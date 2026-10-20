package com.localkeep.core.vault

import com.localkeep.core.model.VaultItem

/**
 * Whether copying existing records — Folders > Duplicate — would take a free vault past one of its
 * creation allowances.
 *
 * Duplicating a folder is creation: every copy is a new record with a new id. Until 2026-10-02 it
 * was the one creation path no allowance reached, so a free vault's seven files, one card and one
 * identity doubled with each tap, in both flavours. It lives here rather than in the one
 * ViewModel that asks it for the reason [AttachmentQuota] gives — a limit written down twice
 * eventually disagrees with itself — and it adds nothing to either quota: it asks each one the
 * question it already answers, with `adding` set to what the copy would hold.
 *
 * ### The invariant, unchanged
 *
 * > **The cap limits creation. It never limits import, and it never limits display.**
 *
 * So a kind the copy does not add to is never asked about. A vault imported with twenty cards is a
 * supported state, and duplicating a folder of logins in it creates no card; asking
 * `exceedsFreeLimit(20, adding = 0)` would answer "over" and refuse a copy that spends nothing.
 */
object DuplicateAllowance {

    /** Pure, so the arithmetic is provable without a vault: [copies] against what is already held. */
    fun exceeded(
        copies: List<VaultItem>,
        filesUsed: Int,
        cardsUsed: Int,
        identitiesUsed: Int,
        isPro: Boolean,
    ): Boolean {
        if (isPro) return false
        val files = AttachmentQuota.used(copies)
        val cards = ProTypeQuota.used(copies, ProTypeQuota.Kind.CARD)
        val identities = ProTypeQuota.used(copies, ProTypeQuota.Kind.IDENTITY)
        val filesOver = files > 0 &&
            AttachmentQuota.exceedsFreeLimit(filesUsed, isPro = false, adding = files)
        val cardsOver = cards > 0 &&
            ProTypeQuota.exceedsFreeLimit(cardsUsed, isPro = false, adding = cards)
        val identitiesOver = identities > 0 &&
            ProTypeQuota.exceedsFreeLimit(identitiesUsed, isPro = false, adding = identities)
        return filesOver || cardsOver || identitiesOver
    }

    /**
     * The same question about [repository], counted on the quotas' own terms — live records,
     * concealed ones included — through their own repository overloads, so this adds no caller to
     * `HiddenChokepointTest`'s allowlist.
     */
    fun exceeded(repository: VaultRepository, copies: List<VaultItem>, isPro: Boolean): Boolean {
        if (isPro || copies.isEmpty()) return false
        val held = ProTypeQuota.usedFromRepository(repository)
        return exceeded(
            copies = copies,
            filesUsed = AttachmentQuota.used(repository),
            cardsUsed = held[ProTypeQuota.Kind.CARD] ?: 0,
            identitiesUsed = held[ProTypeQuota.Kind.IDENTITY] ?: 0,
            isPro = false,
        )
    }
}

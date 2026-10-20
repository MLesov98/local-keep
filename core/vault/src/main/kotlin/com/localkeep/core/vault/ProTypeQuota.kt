package com.localkeep.core.vault

import com.localkeep.core.model.Card
import com.localkeep.core.model.Identity
import com.localkeep.core.model.VaultItem

/**
 * How many Cards and Identity documents the free tier gets.
 *
 * These two were a binary gate until 2026-09-03: free meant **zero** cards and zero identity
 * documents, and `foss` — where `isPro` is permanently false (spec §N2) — therefore never stored
 * one, with no way to buy the ability. The upgrade prompt was selling something the user had never
 * seen work, which is the same failure the file quota was created to fix a month earlier.
 *
 * One each, not three. The 2026-07-29 tiers-and-pricing decision argued the free
 * tier down to zero and the reversal does not throw that reasoning away — it keeps the ceiling
 * deliberately low. One is a taste; it proves the feature exists and works on the user's own data,
 * and it still leaves the second card as a real reason to pay.
 *
 * Separate from [AttachmentQuota] rather than folded into it, and separate *per kind* rather than
 * one shared counter. Files, photos and videos share a counter because they are one thing to the
 * person paying — an encrypted blob. A card and a passport are not one thing, and a shared counter
 * would mean storing a card silently spent the identity allowance, which no sentence in any of the
 * five catalogues could explain.
 *
 * ### The invariant
 *
 * > **The cap limits creation. It never limits import, and it never limits display.**
 *
 * Identical to [AttachmentQuota]'s, and it is the reason this object exposes [used] rather than a
 * clamped count: a vault that arrives from KDBX carrying twenty cards keeps all twenty — listed,
 * searchable, openable, editable — and simply cannot add a twenty-first. A downgrade behaves the
 * same way. Silently dropping over-cap items is data loss in a password manager, and hiding them is
 * losing them with the added insult that the user believes the data is safe.
 */
object ProTypeQuota {

    /** Cards a free vault may create, and separately, identity documents. Pro is unbounded. */
    const val FREE_LIMIT = 1

    /** The two item types this quota governs. */
    enum class Kind { CARD, IDENTITY }

    private fun VaultItem.isOfKind(kind: Kind): Boolean = when (kind) {
        Kind.CARD -> content is Card
        Kind.IDENTITY -> content is Identity
    }

    /**
     * How many of [kind] the vault holds.
     *
     * Reports the truth rather than clamping to [FREE_LIMIT] — an imported or downgraded vault can
     * legitimately sit far above the cap, and a counter that lied about it would make the refusal
     * dialog name a number the user could not reconcile with what is on screen.
     */
    fun used(items: List<VaultItem>, kind: Kind): Int = items.count { it.isOfKind(kind) }

    /** Whether creating [adding] more of that kind would go past what this tier allows. */
    fun exceedsFreeLimit(used: Int, isPro: Boolean, adding: Int = 1): Boolean =
        !isPro && used + adding > FREE_LIMIT

    /**
     * How many stored records of that kind have to go before one more will fit.
     *
     * Same shape and same reason as [AttachmentQuota.mustRemove]: [used] can legitimately sit above
     * [FREE_LIMIT] because import never charges against the cap, so "delete one to make room" is
     * only true at exactly the cap. Someone holding twenty imported cards who is told to delete one
     * does so, watches nothing change, and has been given a false instruction by an app that knew
     * the real number. Never below one — the only caller is a refusal, and a refusal that asks for
     * zero deletions describes no way forward.
     */
    fun mustRemove(used: Int, adding: Int = 1): Int =
        (used + adding - FREE_LIMIT).coerceAtLeast(1)

    /**
     * [used] for both kinds at once, counted from a repository on the same terms as
     * [AttachmentQuota]: live records only, concealed ones included.
     *
     * Concealed items are charged because otherwise hiding a card would buy the allowance back, and
     * the cap could be walked past indefinitely by hiding everything already stored. Trashed ones
     * are not charged — a deleted card is a slot the user has genuinely given up.
     *
     * Both counts from one walk, and the only place in the app outside `AttachmentQuota` that reads
     * the raw inventory for a quota — see `HiddenChokepointTest`, whose allowlist this is on. The
     * ViewModel deliberately calls this rather than reading the inventory itself: two callers would
     * have been two entries on that list, and the second one would have had no justification the
     * first did not already cover.
     */
    fun usedFromRepository(repository: VaultRepository): Map<Kind, Int> =
        repository.getCompleteStorageInventory().allItems
            .filterNot(VaultItem::isDeleted)
            .let { live -> Kind.entries.associateWith { kind -> used(live, kind) } }
}

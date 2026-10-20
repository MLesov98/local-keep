package com.localkeep.core.vault

import com.localkeep.core.model.File
import com.localkeep.core.model.VaultItem

/**
 * How much encrypted storage the free tier gets.
 *
 * Files & media used to be a binary gate: free meant **zero** attachments, zero File items, and a
 * Pro badge on a feature the user had therefore never once seen working. The upgrade prompt was
 * selling something abstract. A small quota lets someone put a passport scan in the vault, watch it
 * encrypt, and then decide — which is a different question from "would you pay for a thing you have
 * never used".
 *
 * One counter for everything, deliberately. Files, photos and videos all consume the same seven
 * slots because that is the sentence the upgrade prompt has to say out loud, and "7 files and
 * photos" survives being read once where "3 documents and 5 photos, counted separately" does not.
 *
 * It lives in `:core:vault` rather than beside either caller because three modules need the same
 * answer — `:feature:files` enforces it at the two add paths, `:feature:vaultlist` needs it to
 * decide whether the add sheet opens a form or an upgrade prompt — and a limit that is written down
 * twice is a limit that will eventually disagree with itself.
 *
 * ### The invariant
 *
 * > **The cap limits creation. It never limits import, and it never limits display.**
 *
 * A free user importing a vault carrying thirty attachments gets all thirty — readable, editable,
 * searchable — and simply cannot add a thirty-first. Silently dropping over-cap items is data loss
 * in a password manager, and hiding them is the same as losing them with the added insult that the
 * user believes the data is safe. So [exceedsFreeLimit] is asked at the *add* paths and nowhere
 * else: it must never become a filter on a list, and [used] can legitimately return a number far
 * larger than [FREE_LIMIT].
 */
object AttachmentQuota {

    /**
     * Attachments a free vault may create. Pro is unbounded.
     *
     * Five until 2026-09-03, raised to seven on the principal's instruction: the free tier was
     * reading as a teaser rather than something usable. Deliberately a small step — the cap still
     * has to be reachable for the upgrade prompt to mean anything.
     *
     * Nothing outside this file states the number. Every sentence that shows it takes it as a
     * format argument (`files_error_quota_exceeded`, `vaultlist_quota_blocked_*`), which is why
     * moving it costs one line here and no translation work in any of the five catalogues.
     */
    const val FREE_LIMIT = 7

    /**
     * Everything currently occupying a slot: attachments hanging off any item, plus every vault
     * item that *is* a file.
     *
     * Both count, because to the person paying they are the same thing — a photo stored on its own
     * and a photo attached to a login are one encrypted blob each, and a quota that noticed only
     * one of them would be trivially sidestepped without the user ever meaning to.
     */
    fun used(items: List<VaultItem>): Int =
        items.sumOf { item ->
            item.attachments.size + if (item.content is File) 1 else 0
        }

    /**
     * Whether adding [adding] more would go past what this tier allows.
     *
     * Returns false for Pro without reading the vault at all — the count is an O(items) walk and
     * there is nothing to compare it against.
     */
    fun exceedsFreeLimit(items: List<VaultItem>, isPro: Boolean, adding: Int = 1): Boolean =
        !isPro && used(items) + adding > FREE_LIMIT

    /** The same question asked of a count already in hand, so a caller showing the number and the
     * verdict together walks the vault once rather than twice. */
    fun exceedsFreeLimit(used: Int, isPro: Boolean, adding: Int = 1): Boolean =
        !isPro && used + adding > FREE_LIMIT

    /**
     * How many stored files have to go before [adding] more will fit.
     *
     * Exists because "remove one to make room" is only true at [used] == [FREE_LIMIT], and [used]
     * can legitimately sit above it — import never charges against the cap, so a vault carrying
     * thirty attachments is a supported state. Someone in that state who is told to remove one
     * does so, watches nothing change, and has been given a false instruction by an app that knew
     * the real number the whole time. Never below one: the only caller is a refusal, and a refusal
     * that asks for zero deletions describes no way forward.
     */
    fun mustRemove(used: Int, adding: Int = 1): Int =
        (used + adding - FREE_LIMIT).coerceAtLeast(1)

    /**
     * Convenience for the three call sites that hold a repository rather than a list.
     *
     * Counted from the complete storage inventory, not from a presentation read: a concealed item's
     * files occupy the same disk as any other. Counting only what is visible would make hiding a
     * folder a way to buy quota back, and the cap could then be walked past indefinitely by hiding
     * everything already stored. The live filter is applied here rather than taken from
     * `getAllItems()` — trashed files are not charged, but concealed ones are.
     */
    fun exceedsFreeLimit(repository: VaultRepository, isPro: Boolean, adding: Int = 1): Boolean =
        exceedsFreeLimit(chargeable(repository), isPro, adding)

    /** [used], counted from a repository on the same terms as the overload above. */
    fun used(repository: VaultRepository): Int = used(chargeable(repository))

    /** Everything on disk that a slot is charged for: live records only, concealed ones included. */
    private fun chargeable(repository: VaultRepository): List<VaultItem> =
        repository.getCompleteStorageInventory().allItems.filterNot(VaultItem::isDeleted)

    // `LIMIT_MESSAGE` lived here — the English sentence shown when someone hit the limit. Both of
    // its callers moved to `R.string.files_error_quota_exceeded` when feature/files was extracted,
    // and nothing referenced it afterwards except its own test, so it was an untranslatable string
    // kept alive by the test that guarded it. Deleted 2026-07-30; the invariant it protected —
    // that the sentence names the number rather than the tier — is now asserted against the
    // resource, in feature/files/QuotaMessageTest.
}

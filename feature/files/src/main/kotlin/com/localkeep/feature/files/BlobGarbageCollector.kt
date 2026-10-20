package com.localkeep.feature.files

import com.localkeep.core.model.File as FileContent
import com.localkeep.core.vault.DuressUnlocker
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import javax.inject.Inject

/**
 * Reclaims encrypted blobs that no vault item references any more.
 *
 * `VaultRepository.deleteItem`/`purgeTrash` (in `:core:vault`, which cannot depend on this module)
 * only drop the `VaultItem` — they leave the item's `File` blob/thumbnail and any `Attachment` blobs
 * on disk. A `File` edit that replaces the photo before saving orphans the superseded blob too.
 * Rather than eagerly delete on each mutation (where a still-referenced blob could be dropped by a
 * race or a mis-scoped call), this sweeps the blob directory against the CURRENT referenced set —
 * live items AND trashed items (whose blobs must survive until purge). Deleting only what nothing
 * points at is idempotent and safe to run opportunistically (e.g. after a File save or trash purge).
 */
class BlobGarbageCollector @Inject constructor(
    private val repository: VaultRepository,
    private val lockManager: VaultLockManager,
    private val fileBlobStore: FileBlobStore,
) {

    /** Every blobRef reachable from the vault right now — File content + attachments, live + trashed. */
    fun referencedRefs(): Set<String> {
        val refs = mutableSetOf<String>()
        // The complete storage inventory, never the presentation getters. Two reasons, and both of
        // them are silent permanent data loss:
        //
        // A concealed item is absent from every presentation read by design, so a sweep driven by
        // one would compute "nothing points at this blob" and delete the attachments of exactly the
        // entries the user chose to protect — the feature would eat the data it was asked to hide.
        //
        // `getTrashItems()` was also the wrong half of the trash: it deliberately omits items that
        // went down with a folder, because the trash *screen* represents those by the folder's row.
        // Reachability is not a screen. Trashing a folder holding a photo and then sweeping dropped
        // that photo's blob while its item was still restorable, so the restore came back with a
        // dangling ref. `allItems` is every record the vault holds, which is the only correct
        // denominator for "is this blob referenced".
        val allItems = repository.getCompleteStorageInventory().allItems
        for (item in allItems) {
            (item.content as? FileContent)?.let { file ->
                if (file.blobRef.isNotEmpty()) refs += file.blobRef
                file.thumbnailRef?.let { refs += it }
            }
            for (att in item.attachments) {
                if (att.blobRef.isNotEmpty()) refs += att.blobRef
                att.thumbnailRef?.let { refs += it }
            }
        }
        return refs
    }

    /**
     * Deletes on-disk blobs no vault item references. Requires the vault to be unlocked/loaded.
     *
     * Declines entirely when a decoy region exists. A session holds the key for exactly one payload
     * region, so `referencedRefs()` is that region's denominator alone — never the union — and
     * "nothing points at this blob" is then false in *both* directions. A duress session computes
     * the referenced set from the decoy, which references no blobs at all, and would delete every
     * blob the real vault owns; a real session cannot see what was added inside the decoy and would
     * delete that instead. Neither is recoverable: `blobs/` is excluded from Auto Backup and device
     * transfer (`full_backup_content.xml`, `data_extraction_rules.xml`) and the rolling snapshots
     * never held blobs, so only a user-initiated `.vaultpack` export would have a copy.
     *
     * Reclaiming disk is an optimisation; deleting a user's media is permanent. So the sweep
     * declines rather than guesses, and an inconclusive probe counts as "decoy exists". The probe
     * costs one vault-file read and one region authentication per unlock, off the main thread.
     *
     * The decline is safe, but on its own it is also permanent: this is the app's only sweep, so
     * for a vault with a decoy nothing is ever reclaimed, the skipped amount is unbounded, and the
     * ciphertext of media the vault list has called permanently deleted stays on disk under the
     * live DEK. The two moments the union is knowable are the transitions, and they are where the
     * reclaim happens instead — `SettingsViewModel.setupDuress` sweeps just before the decoy is
     * created and `SettingsViewModel.removeDuress` just after it is destroyed. Note also that this
     * decline covers the REAL session, not only a duress one: the check below sits after the
     * `isDuress` return, so it is the ordinary user who once configured a decoy who pays for it.
     */
    suspend fun sweep(): Int {
        val state = lockManager.lockState.value
        if (state !is LockState.Unlocked) return 0
        if (state.isDuress) return 0
        val decoyExists = runCatching { DuressUnlocker.isConfigured(lockManager) }.getOrDefault(true)
        if (decoyExists) return 0
        return fileBlobStore.sweepUnreferenced(referencedRefs())
    }
}

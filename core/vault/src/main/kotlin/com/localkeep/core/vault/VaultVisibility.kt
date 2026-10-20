package com.localkeep.core.vault

import com.localkeep.core.model.Folder
import com.localkeep.core.model.HiddenLease
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID

/**
 * One immutable answer to "what may the user see right now": the live/trash lists a screen shows,
 * the concealed ids a revealed session marks, and the next lease that will change the picture.
 *
 * [concealedItemIds]/[concealedFolderIds] are populated whether or not a reveal session is active —
 * in a revealed session the presentation lists include the concealed records, and these sets are
 * what a row marker reads to say "this is hidden". They are the union of directly-hidden and
 * ancestry-concealed. [directlyHiddenItemIds]/[directlyHiddenFolderIds] are the subset with a lease
 * on the record itself: a directly-hidden row offers "Make visible"; an ancestry-only descendant
 * (concealed only because a parent folder is) carries the marker but no misleading stop action.
 */
data class VaultVisibilitySnapshot(
    val liveItems: List<VaultItem>,
    val trashItems: List<VaultItem>,
    val liveFolders: List<Folder>,
    val trashedFolderRows: List<TrashedFolder>,
    val concealedItemIds: Set<UUID>,
    val concealedFolderIds: Set<UUID>,
    val directlyHiddenItemIds: Set<UUID>,
    val directlyHiddenFolderIds: Set<UUID>,
    val nextExpiry: Instant?,
    /**
     * Exactly how many items an Empty Trash would destroy — the same predicate
     * `VaultRepository.planTrashPurge(PurgeScope.AllTrash)` applies, evaluated here so the sentence
     * the user agrees to and the set the purge takes cannot disagree.
     *
     * It is a count of the whole trash, **not** the sum of what the screen can show. Trash presents
     * flat items plus one row per trash *root*, and a folder trashed inside an already-trashed
     * parent is neither: [trashedFolderRows] admits only roots whose parent is not itself trashed,
     * and `trashFolder` retags nothing that is already deleted, so the inner folder keeps its own
     * `trashRootId` and its items keep pointing at it. Summing the rows therefore missed that whole
     * subtree while the purge still took it — trash a folder, trash its parent, and the Tier A
     * confirmation read "This permanently removes all 0 items" over five that were about to go.
     *
     * Derive this from the rows again and that bug comes back. The only safe source is the predicate
     * the destruction itself uses.
     */
    val purgeableTrashItemCount: Int,
)

/**
 * The single presentation boundary for concealment. Every user-facing read — the vault list, trash,
 * folder browsing, pickers, search escape counts, autofill and the credential provider — projects
 * through [project] rather than filtering `vault.items`/`vault.folders` itself, so a hidden record is
 * absent from *all* of them or none. Storage-integrity work (GC, quota, export, dedup) deliberately
 * does not come through here; it uses `VaultRepository.getCompleteStorageInventory()`.
 *
 * Pure and clock-injected: the caller passes `now`, so the decision is reproducible and testable and
 * a timer wake never has to touch disk.
 */
object VaultVisibility {

    /**
     * Projects [vault] at [now]. When [revealSessionActive] is true the presentation lists include
     * concealed records (the user typed the reveal phrase this session); the concealed-id sets stay
     * populated regardless, for row markers. A duress session never sets this true — reveal is
     * refused there at the lock boundary — so a decoy vault is always projected concealed.
     */
    fun project(vault: Vault, now: Instant, revealSessionActive: Boolean): VaultVisibilitySnapshot {
        val foldersById = vault.folders.associateBy { it.id }

        // Folders whose own lease is concealing right now. Everything under one of these is concealed
        // by ancestry, computed from the *current* parent chain — an item or subfolder moved out of a
        // hidden folder is no longer under it and so is no longer concealed, with no extra bookkeeping.
        val directlyHiddenFolderIds = vault.folders
            .filterTo(HashSet()) { HiddenLease.isHidden(it.hiddenUntil, now) }
            .mapTo(HashSet()) { it.id }

        val concealedFolderIds = vault.folders
            .filterTo(HashSet()) { folder ->
                var current: Folder? = folder
                val seen = HashSet<UUID>()
                while (current != null && seen.add(current.id)) {
                    if (current.id in directlyHiddenFolderIds) return@filterTo true
                    current = current.parentId?.let(foldersById::get)
                }
                false
            }
            .mapTo(HashSet()) { it.id }

        val directlyHiddenItemIds = vault.items
            .filterTo(HashSet()) { HiddenLease.isHidden(it.hiddenUntil, now) }
            .mapTo(HashSet()) { it.id }

        val concealedItemIds = vault.items
            .filterTo(HashSet()) { item ->
                item.id in directlyHiddenItemIds ||
                    (item.folderId != null && item.folderId in concealedFolderIds)
            }
            .mapTo(HashSet()) { it.id }

        // The nearest finite lease still concealing something — what the foreground coordinator wakes
        // on. INDEFINITE never expires, so it is excluded; every included instant is strictly after
        // [now] because isHidden requires `until > now`. A lease shadowed by an indefinite ancestor is
        // still counted: waking on it only recomputes a projection that turns out unchanged, which is
        // harmless, and excluding it would need the ancestry cost paid twice.
        val nextExpiry: Instant? = buildList {
            vault.items.forEach { if (HiddenLease.isHidden(it.hiddenUntil, now)) it.hiddenUntil?.let(::add) }
            vault.folders.forEach { if (HiddenLease.isHidden(it.hiddenUntil, now)) it.hiddenUntil?.let(::add) }
        }.filterNot { it == HiddenLease.INDEFINITE }.minOrNull()

        val conceal = !revealSessionActive
        val liveItems = vault.items.filter { !it.isDeleted && (!conceal || it.id !in concealedItemIds) }
        // Trash mirrors getTrashItems(): items that went down with a folder are represented by the
        // folder's row, not their own. Concealed trash is preserved (Empty Trash acts only on the
        // visible snapshot), so it is filtered out of the visible list here.
        val trashItems = vault.items.filter {
            it.isDeleted && it.trashedWithFolderId == null && (!conceal || it.id !in concealedItemIds)
        }
        val liveFolders = vault.folders.filter { it.deletedAt == null && (!conceal || it.id !in concealedFolderIds) }
        val trashedFolderRows = trashedFolderRows(vault, if (conceal) concealedItemIds else emptySet(), if (conceal) concealedFolderIds else emptySet())
            .filter { !conceal || it.folder.id !in concealedFolderIds }

        return VaultVisibilitySnapshot(
            liveItems = liveItems,
            trashItems = trashItems,
            liveFolders = liveFolders,
            trashedFolderRows = trashedFolderRows,
            concealedItemIds = concealedItemIds,
            concealedFolderIds = concealedFolderIds,
            directlyHiddenItemIds = directlyHiddenItemIds,
            directlyHiddenFolderIds = directlyHiddenFolderIds,
            nextExpiry = nextExpiry,
            // Mirrors planTrashPurge(AllTrash): every deleted item, minus the concealed ones the
            // purge also spares. Deliberately not `trashItems.size + rows.sumOf { it.itemCount }`.
            purgeableTrashItemCount = vault.items.count {
                it.isDeleted && (!conceal || it.id !in concealedItemIds)
            },
        )
    }

    /**
     * The trashed-folder rows with their restore counts — the same root and predicate
     * `VaultRepository.getTrashedFolders` uses, replicated here so the projection owns the whole
     * presentation and the two cannot drift. A trash root is a trashed folder whose parent is null or
     * not itself trashed.
     *
     * The counts exclude concealed members, and that is load-bearing rather than tidy. A row reading
     * "12 items" over a subtree the user can only see nine of is an oracle: it says a concealed
     * entry exists and how many, on a screen the feature is supposed to make silent. It would also
     * make the purge confirmation lie, because a purge destroys exactly the visible ids. The sets
     * arrive empty in a revealed session, where every member is on screen and the true count is the
     * honest one.
     *
     * Restore is the mirror case and is deliberately left alone: `restoreFolder` brings back the
     * concealed members too, so it restores more than the row names. From the user's side the two
     * agree — the concealed ones come back still concealed, so exactly the counted number appears —
     * and the alternative, naming the true total, is the oracle this exists to prevent.
     */
    private fun trashedFolderRows(
        vault: Vault,
        concealedItemIds: Set<UUID>,
        concealedFolderIds: Set<UUID>,
    ): List<TrashedFolder> {
        val trashed = vault.folders.filter { it.deletedAt != null }
        val trashedIds = trashed.mapTo(HashSet()) { it.id }

        // Tallied once over each collection rather than re-scanned per root. `project` is
        // uncached and runs on every visibility snapshot — a search keystroke, a save, a favourite
        // toggle, a reveal, a lease expiry — so the per-root form was O(roots x items) work on a
        // path taken several times per interaction. `FolderSubtreeCounts` records the same rewrite
        // for the live tree and what the fold-per-folder shape cost there.
        //
        // The two `+= 1`s under `folderCount` are the two halves of the `id == root || trashRootId
        // == root` disjunction, and the `!=` between them is what stops a root — whose own
        // `trashRootId` is its own id (`VaultRepository.trashFolder`) — being counted twice.
        val folderCounts = HashMap<UUID, Int>()
        for (folder in trashed) {
            if (folder.id in concealedFolderIds) continue
            folder.trashRootId?.let { folderCounts[it] = (folderCounts[it] ?: 0) + 1 }
            if (folder.trashRootId != folder.id) {
                folderCounts[folder.id] = (folderCounts[folder.id] ?: 0) + 1
            }
        }
        val itemCounts = HashMap<UUID, Int>()
        for (item in vault.items) {
            val root = item.trashedWithFolderId ?: continue
            if (item.id in concealedItemIds) continue
            itemCounts[root] = (itemCounts[root] ?: 0) + 1
        }

        return trashed
            .filter { it.parentId == null || it.parentId !in trashedIds }
            .map { root ->
                TrashedFolder(
                    folder = root,
                    folderCount = folderCounts[root.id] ?: 0,
                    itemCount = itemCounts[root.id] ?: 0,
                )
            }
    }
}

package com.localkeep.core.vault

import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Card
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.HiddenLease
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemMerge
import com.localkeep.core.model.Login
import com.localkeep.core.model.MatchStrategy
import com.localkeep.core.model.Note
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.RevealPhraseVerifier
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.net.IDN
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.text.Normalizer
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What [VaultRepository.updateItem] had to do to reconcile the write. */
enum class UpdateOutcome {
    /** The stored item was exactly what the caller expected; its edit was written as given. */
    UPDATED,

    /**
     * The item had changed since the caller loaded it, and the other writer's password was newer,
     * so it was kept. The caller's other edits were still applied.
     */
    KEPT_NEWER_PASSWORD,

    /**
     * The item was written, but the folder it named no longer exists, so it was filed nowhere.
     *
     * A folder can be deleted while an editor is open — the drawer's folder screen is one back-stack
     * entry away — and an item pointing at a folder that is gone is invisible in every list, which
     * is the one failure mode worse than the wrong folder. It is nulled rather than refused, on the
     * same reasoning as [KEPT_NEWER_PASSWORD]: the user's edit is real. This outcome takes
     * precedence over [KEPT_NEWER_PASSWORD] when one save hits both races, because the lost folder
     * is the part the user has to be told about; the password is preserved either way.
     */
    SAVED_FOLDER_ABSENT,

    /**
     * The edit was written, but the item had been moved to the trash since the editor loaded it, and
     * it stays there.
     *
     * Reachable from inside the editor itself: its folder picker can trash the folder the open item
     * is filed in, which trashes the item with it. Until this existed the save wrote the editor's
     * load-time `deletedAt = null` back over that, so the item came back live and unfiled and
     * nothing said so — the deletion the user had just confirmed was silently undone. Takes
     * precedence over [SAVED_FOLDER_ABSENT]: both are true in that flow, and "it is in the Trash" is
     * the fact the user needs, while "it has no folder" would send them looking in the wrong place.
     */
    SAVED_INTO_TRASH,
}

/** What [VaultRepository.addItem] had to do to the item before storing it. */
enum class AddItemOutcome {
    /** Stored exactly as given. */
    ADDED,

    /** Stored, but its folder was gone by the time the save landed, so it was filed nowhere. */
    FOLDER_ABSENT,
}

/** What [VaultRepository.trashFolder] took down with the folder, so the caller can say so. */
data class TrashFolderOutcome(
    val trashedId: UUID,
    /** The folder itself plus every descendant that was still live. */
    val folderCount: Int,
    /** Items inside that subtree that were still live. Ones already in the trash are left alone. */
    val itemCount: Int,
)

/** What [VaultRepository.restoreFolder] brought back, and where it had to put it. */
data class RestoreFolderOutcome(
    val restoredId: UUID,
    val folderCount: Int,
    val itemCount: Int,
    /**
     * Set when the folder's original parent is still in the trash, so it came back somewhere else:
     * the nearest live ancestor, or null for the vault root. [reparented] distinguishes that null
     * from "landed exactly where it was", which is also a null parent for a root folder.
     */
    val reparented: Boolean,
    val reparentedTo: UUID?,
)

/** What an unlock-time folder health check found. [needsRepair] is what gates the repair action. */
data class TreeHealthStatus(
    val treeReasons: List<String>,
    val orphanedItemCount: Int,
) {
    val needsRepair: Boolean get() = treeReasons.isNotEmpty() || orphanedItemCount > 0
}

/**
 * One trashed folder as the Trash screen shows it: the folder, and what restoring it would return.
 *
 * [folderCount] includes the root itself, so a childless folder reads 1 — the row says "1 folder,
 * 12 items", which is what the user is being offered back.
 */
data class TrashedFolder(
    val folder: Folder,
    val folderCount: Int,
    val itemCount: Int,
)

/**
 * Every record the vault physically holds, with no live/trash/presentation filtering — literally
 * `vault.items` and `vault.folders`, including deleted items carrying `trashedWithFolderId`.
 *
 * The deliberately awkward name is the point: storage-integrity work (blob GC, quota, export blob
 * reachability, private import dedup) must count *all* bytes a vault owns, concealed or not, or it
 * deletes an attachment reachable only from a hidden item. `HiddenChokepointTest` allowlists the
 * exact four callers permitted to read this, and none may feed its names or counts to any UI.
 */
data class VaultStorageInventory(
    val allItems: List<VaultItem>,
    val allFolders: List<Folder>,
)

/**
 * The directly-leased items and folders the authenticated Hidden-items screen manages — those a user
 * hid explicitly, active at the repository clock. Descendants concealed only by a hidden ancestor are
 * not here: they have no lease of their own and no honest "Stop hiding" to offer.
 */
data class HiddenEntries(
    val directlyHiddenItems: List<VaultItem>,
    val directlyHiddenFolders: List<Folder>,
)

/**
 * The projection a screen collects, so vault list, folders, trash, pickers, health and authenticated
 * management all read the *same* visibility rather than each caching its own. [Locked] is published
 * the instant the vault locks, so a subscriber can never replay an unlocked snapshot after a lock.
 */
sealed interface VaultPresentationState {
    data object Locked : VaultPresentationState
    data class Unlocked(val snapshot: VaultVisibilitySnapshot) : VaultPresentationState
}

/** What to hide, reveal or re-lease: an item or a folder, by id. */
sealed interface HiddenTarget {
    val id: UUID

    data class Item(override val id: UUID) : HiddenTarget
    data class Folder(override val id: UUID) : HiddenTarget
}

/** The result of a [VaultRepository.hide] request. Every non-success commits nothing. */
enum class HideOutcome {
    Hidden,
    TargetAbsent,
    PhraseRequired,
    PhraseCollision,
    InvalidDuration,
    InvalidVerifier,
}

/** What a repair actually moved, for the notice and for the tests. */
data class RepairOutcome(
    val treeChanges: List<String>,
    val unfiledItemCount: Int,
)

/** Immutable-vault CRUD/query layer; every mutation persists through [VaultLockManager.commit]. */
class VaultRepository(
    private val lockManager: VaultLockManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * How much text the search index may hold — see [defaultIndexCharBudget].
     *
     * A parameter only so a test can pick a budget small enough to cross without building a vault
     * the size of the device's heap. Production never passes it.
     */
    private val indexCharBudget: Long = defaultIndexCharBudget(),
    /**
     * The clock every hidden-state decision reads, so concealment and lazy expiry normalization are
     * reproducible and testable (Decision 2). Production supplies `Clock.systemUTC()`; a test passes
     * `Clock.fixed`. Only *hidden-state* timing uses it — the existing rename/move/trash stamps keep
     * their own `Instant.now()` so this change stays surgical.
     */
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutationMutex = Mutex()

    /**
     * The vault snapshot [searchIndex] was built from, compared by identity.
     *
     * `@Volatile` because the search path reads them on the main thread while [armPlaintextCacheRelease]'s
     * waiter clears them on another.
     */
    @Volatile private var indexedVault: Vault? = null

    @Volatile private var searchIndex: IdentityHashMap<VaultItem, List<String>>? = null

    /**
     * The folder *paths* [searchIndex] was built against, so a rename or a move can invalidate it.
     *
     * Paths rather than names: moving a folder changes the haystack of every item in its subtree
     * without any of those items changing, and keying on names alone would leave them indexed under
     * their old ancestors. The rebuild that costs is the pre-index path — 237 ms at 10,000 items,
     * against a full-vault write of 2.4 s, so a move pays a fraction of what it already pays.
     *
     * `@Volatile` for the same reason as the two above: read on the main thread by the search path,
     * cleared on another by [armPlaintextCacheRelease]'s waiter.
     */
    @Volatile private var indexedFolderNames: Map<UUID, List<String>> = emptyMap()

    /**
     * Whether the folder tree, as it was found at unlock, needs repairing.
     *
     * Held here rather than in a ViewModel because the vault is what is damaged, not a screen: the
     * restore path writes raw vault bytes with no folder validation at all, so a snapshot from a
     * build that predates the hierarchy — or one truncated mid-write — can arrive with a cycle or
     * with items pointing at folders that are gone. Writes would then be refused by
     * [VaultMutator.build] forever with nothing offering a way out, which is a write-brick.
     *
     * `@Volatile` for the same reason as the index state above: set on the unlock dispatcher, read
     * from the main thread by the folder screen.
     */
    @Volatile
    private var folderRepairFlag: Boolean = false

    /**
     * Whether the folder tree, as it was found at unlock, needs repairing.
     *
     * Read through the lock state rather than cleared by a coroutine watching it: a waiter would
     * have made "the flag is gone" true only *eventually*, so a caller that locked and immediately
     * asked would see the previous vault's answer. Locking is what makes this stale, so locking is
     * what the getter checks.
     */
    val needsFolderRepair: Boolean
        get() = folderRepairFlag && lockManager.lockState.value is LockState.Unlocked

    /**
     * Cached subtree sets and ancestor paths, invalidated whenever the folder list changes.
     *
     * This holds the vault's whole `List<Folder>` — every folder name the user typed — so it is
     * released at lock by [armPlaintextCacheRelease], on the same reasoning as the search index.
     */
    @Volatile private var cachedFolders: List<Folder>? = null

    /**
     * A concurrent map rather than an immutable one rebuilt with `+`.
     *
     * Rebuilding copied every entry already in the cache on each miss, so filling it for F folders
     * cost O(F²) map allocations — a cache whose warm-up got quadratically more expensive the more
     * of it was warm. The `@Volatile` reference is still swapped wholesale on invalidation, which is
     * what keeps a stale generation from being read after the folder list changes; only the
     * per-entry write is now in place.
     */
    @Volatile private var cachedDescendants: ConcurrentHashMap<UUID, Set<UUID>> = ConcurrentHashMap()

    /** Whether a coroutine is already waiting to drop the plaintext caches at the next lock. */
    private val cacheReleaseArmed = AtomicBoolean(false)

    /** How many items are currently indexed. Exists so a test can watch the index go away. */
    internal val indexedItemCount: Int
        get() = searchIndex?.size ?: 0

    /**
     * Arranges for the cached plaintext to be dropped the next time the vault locks.
     *
     * The search index holds a strong reference to the vault snapshot and to a lowercased copy of
     * every searchable string in it; [cachedFolders] holds the whole folder list beside it. Left
     * alone those would outlive `lock()` for the life of the process — this repository is a
     * `@Singleton` — and turn "locked" into a state where the plaintext is still reachable. That is
     * worse than the ordinary situation, where those strings merely become garbage: immutable
     * Strings cannot be wiped, so unreachable-and-collectable is the best this layer can do, and
     * holding them deliberately forfeits even that. Dropping them is part of locking, not an
     * optimisation.
     *
     * Deliberately a **one-shot wait rather than a `collect`**, armed only once there is something
     * to release. A permanent collector in `init` would have been shorter, and it leaked: a
     * repository is cheap enough that tests construct one per seeded item, and each would have left
     * a coroutine holding its lock manager alive for the rest of the process. This version starts
     * nothing until there is something to release, and finishes when it has released it.
     *
     * Armed from two places since 2026-09-07 — the search index build and the folder cache
     * populate — because arming from the search path alone released nothing for a session that
     * only browsed. `cacheReleaseArmed` is compare-and-set, so the second site cannot start a
     * second waiter; the cost is that reading a folder now starts the coroutine that reading the
     * search field used to, which widens the leak the paragraph above guards against from "every
     * repository that searched" to "every repository that read a folder". Still one coroutine per
     * repository, still finishing at the first non-`Unlocked` emission, but it is a real widening
     * and not a free call.
     */
    private fun armPlaintextCacheRelease() {
        if (!cacheReleaseArmed.compareAndSet(false, true)) return
        scope.launch {
            lockManager.lockState.first { it !is LockState.Unlocked }
            indexedVault = null
            indexedFolderNames = emptyMap()
            searchIndex = null
            cachedFolders = null
            cachedDescendants = ConcurrentHashMap()
            cacheReleaseArmed.set(false)
        }
    }

    val onSaved: SharedFlow<Unit>
        get() = lockManager.onSaved

    /**
     * Stores a new item, filing it nowhere if the folder it names has since been deleted.
     *
     * Returns an outcome rather than nothing because the editor has to be able to say "that folder
     * is gone" — an item silently pointing at a missing folder shows up in no list at all, and the
     * add path is exactly where the race is longest: the user opens the editor from inside a
     * folder, types for a minute, and the folder can be deleted from the drawer in between.
     */
    suspend fun addItem(item: VaultItem): AddItemOutcome = mutationMutex.withLock {
        val vault = requireVault()
        check(vault.items.none { it.id == item.id }) { "item already exists: ${item.id}" }
        val filed = vault.withLiveFolder(item)
        lockManager.commit(vault.copy(items = vault.items + filed).normalizedForSave(clock.instant()))
        if (filed.folderId != item.folderId) AddItemOutcome.FOLDER_ABSENT else AddItemOutcome.ADDED
    }

    /**
     * [item] with a `folderId` that names a folder this vault actually has, or none.
     *
     * **"Has" means present, not visible — `folders` here is the raw list and must stay that way.**
     * This is the one folder read in the repository that *writes*: point it at `.live` and every
     * update to an item sitting in a trashed folder permanently unfiles it, so restoring the folder
     * yields an empty one. The item is meant to come back with its folder, which means the marker
     * has to survive being edited while the folder is in the trash.
     */
    private fun Vault.withLiveFolder(item: VaultItem): VaultItem {
        val folderId = item.folderId ?: return item
        return if (folders.any { it.id == folderId }) item else item.copy(folderId = null)
    }

    /**
     * Replaces the stored item, optionally checking it has not moved underneath the caller.
     *
     * [expectedUpdatedAt] is the `updatedAt` the caller read when it loaded the item. Pass it from
     * any long-lived editor: an item screen is open for as long as the user is typing, and this app
     * has a second writer running the whole time. The autofill Save-confirm activity shares the
     * lock manager, so accepting Chrome's "update your saved password?" prompt writes a new
     * password straight into the vault — and the editor, whose in-memory copy predates that,
     * replaced the item wholesale on save and put the old password back. Nothing surfaced; the user
     * had a password manager holding a password that no longer worked.
     *
     * On a conflict the write is not refused — the user's edit is real and losing it would be its
     * own bug — but a password the other writer changed more recently survives it, exactly as
     * [ItemMerge.preserveNewerPassword] already resolves the same collision for imports. The caller
     * gets [UpdateOutcome.KEPT_NEWER_PASSWORD] so it can say so rather than pretending nothing
     * happened.
     *
     * Read, resolve and write all happen under [mutationMutex], so the check cannot be raced.
     */
    suspend fun updateItem(
        item: VaultItem,
        expectedUpdatedAt: Instant? = null,
    ): UpdateOutcome = mutationMutex.withLock {
        val vault = requireVault()
        val current = vault.items.find { it.id == item.id }
        check(current != null) { "item not found: ${item.id}" }
        val base = if (expectedUpdatedAt != null && current.updatedAt != expectedUpdatedAt) {
            ItemMerge.preserveNewerPassword(winner = item, loser = current)
        } else {
            item
        }
        // Decided here, where the answer is actually known, rather than re-derived downstream from
        // object identity. `preserveNewerPassword` returns its `winner` unchanged when there is
        // nothing to preserve, so `base !== item` is exactly "did the other writer's password
        // survive" — but only at this line. Every later `copy()` allocates, so an identity test
        // taken after one is unconditionally true; that is precisely how this reported a conflict
        // on every single save once the hidden-pair merge landed below.
        val keptNewerPassword = base !== item
        // A long-lived editor's snapshot is not the authority on concealment: a Hide (or lease
        // change) can land after the editor loaded, so the stored item's hidden pair is reconciled
        // back in on its own stamp. Because the editor never writes hidden fields, its `item` cannot
        // win this, so an unrelated save can never silently reveal a concurrently-hidden item.
        val (hUntil, hStamp) = ItemMerge.mergeHiddenPair(existing = current, incoming = item)
        // Trash state is carried across for the same reason, and it was not. An editor's snapshot
        // holds `deletedAt = null` from the moment it loaded, so a save after the item was trashed
        // wrote that null back and quietly resurrected it — live again, and unfiled, which is the
        // exact state the folder-delete flow was changed to stop producing. The reachable path is
        // inside one screen: the item editor's own folder picker can trash the folder the open item
        // sits in, and that trashing marks the item. Refusing the write instead would throw away the
        // user's typing, which this function's KDoc rejects; carrying the two fields keeps both the
        // edit and the deletion. Found 2026-08-19.
        val resolved = base.copy(
            hiddenUntil = hUntil,
            hiddenUpdatedAt = hStamp,
            deletedAt = current.deletedAt,
            trashedWithFolderId = current.trashedWithFolderId,
        )
        val trashedUnderneath = current.deletedAt != null && item.deletedAt == null
        val filed = vault.withLiveFolder(resolved)
        lockManager.commit(
            vault.copy(items = vault.items.map { if (it.id == item.id) filed else it })
                .normalizedForSave(clock.instant()),
        )
        when {
            // Precedence, deliberately: a save can hit both races at once, and the folder is the
            // one the user cannot recover from on their own — the password was preserved either
            // way, but an item filed into nothing is an item they will not find again.
            // Ahead of the folder outcome: an item that went to the trash while being edited is a
            // bigger surprise than one whose folder vanished, and the folder message would be
            // actively misleading about where the item now is.
            trashedUnderneath -> UpdateOutcome.SAVED_INTO_TRASH
            filed.folderId != resolved.folderId -> UpdateOutcome.SAVED_FOLDER_ABSENT
            keptNewerPassword -> UpdateOutcome.KEPT_NEWER_PASSWORD
            else -> UpdateOutcome.UPDATED
        }
    }

    /**
     * Reads the item, applies [transform] and commits the result — all under one [mutationMutex].
     *
     * This is the safe shape for every read-modify-write. Callers used to do the read themselves:
     *
     * ```
     * val existing = repository.getAllItems().first { it.id == id }  // unguarded
     * repository.updateItem(existing.copy(favorite = true))          // only this is locked
     * ```
     *
     * Any writer that committed between those two lines was overwritten wholesale. [updateItem]'s
     * conflict path only rescues `password`/`passwordChangedAt`/`passwordHistory`, so a title,
     * folder, tag, note or attachment changed in the gap was lost with nothing reported. Since
     * [transform] here sees the *stored* item, there is no stale expectation to reconcile and no
     * `expectedUpdatedAt` counterpart — [updateItem] keeps that job for the long-lived editors that
     * genuinely hold a snapshot across user typing.
     *
     * [transform] is deliberately a plain, non-suspending `(VaultItem) -> VaultItem`.
     * [mutationMutex] is a coroutines `Mutex` and is **not** reentrant, so a transform that called
     * back into this repository — [updateItem], [mutate], [addItem], [deleteItem] — would block on
     * a lock its own caller holds and deadlock forever, with the vault wedged for the rest of the
     * process. Making the parameter non-suspend means the compiler rejects any such call outright;
     * keep it that way. Keep the body pure and short for the same reason: it runs with every other
     * vault write blocked behind it.
     *
     * Returns the item as committed, which is what callers that mirror the result into UI state
     * need. (The [UpdateOutcome] [updateItem] returns would be a dead value here — with no expected
     * `updatedAt` to compare, it is always [UpdateOutcome.UPDATED].) An absent [id] throws, matching
     * [updateItem]'s `check(current != null)` rather than failing quietly.
     */
    suspend fun updateWith(
        id: UUID,
        transform: (VaultItem) -> VaultItem,
    ): VaultItem = mutationMutex.withLock {
        val vault = requireVault()
        val current = vault.items.find { it.id == id }
        check(current != null) { "item not found: $id" }
        val updated = transform(current)
        check(updated.id == id) { "transform changed the item id: $id -> ${updated.id}" }
        // Silently, unlike the two paths above: every caller here — the favourite toggle, attaching
        // a file, the files editor — carries no folder intent at all, so a folder that vanished
        // underneath them is not something the user did and not something they can act on. What
        // must not happen is the item keeping a dead folderId and disappearing from every list.
        val filed = vault.withLiveFolder(updated)
        lockManager.commit(
            vault.copy(items = vault.items.map { if (it.id == id) filed else it })
                .normalizedForSave(clock.instant()),
        )
        filed
    }

    suspend fun deleteItem(id: UUID) = mutateVault { vault ->
        check(vault.items.any { it.id == id }) { "item not found: $id" }
        vault.copy(items = vault.items.filterNot { it.id == id })
    }

    suspend fun moveToTrash(id: UUID) = mutateVault { vault ->
        val now = Instant.now()
        val item = vault.items.find { it.id == id } ?: error("item not found: $id")
        check(item.deletedAt == null) { "item is already in trash: $id" }
        vault.copy(
            items = vault.items.map {
                if (it.id == id) it.copy(updatedAt = now, deletedAt = now) else it
            },
        )
    }

    /**
     * Brings [id] back out of the trash, filing it under the nearest live ancestor of the folder it
     * names when that folder is itself in the trash.
     *
     * The reparenting is not cosmetic. An item trashed on its own keeps its `folderId` and gets no
     * `trashedWithFolderId`, and its folder can be trashed afterwards — [trashFolder] leaves
     * anything already in the trash alone — so clearing `deletedAt` on its own handed back a live
     * item filed inside an invisible folder, which no browse path reaches. See
     * [withItemRestoredFromTrash].
     */
    suspend fun restoreFromTrash(id: UUID) = mutateVault { vault ->
        vault.withItemRestoredFromTrash(id, Instant.now())
    }

    /**
     * Files [id] under [folderId], or unfiles it when that is null.
     *
     * `mutateVault` rather than `mutate`: this writes one field on one item and cannot change the
     * folder tree, so there is no tree to re-validate. Refuses a trashed target — filing something
     * into an invisible folder is how an item disappears without being deleted.
     */
    suspend fun setItemFolder(id: UUID, folderId: UUID?) = mutateVault { vault ->
        val item = vault.items.find { it.id == id } ?: error("item not found: $id")
        if (folderId != null) {
            val target = vault.folders.find { it.id == folderId }
            check(target != null) { "unknown folder: $folderId" }
            check(target.deletedAt == null) { "folder is in trash: $folderId" }
        }
        if (item.folderId == folderId) {
            vault
        } else {
            val now = Instant.now()
            vault.copy(
                items = vault.items.map {
                    if (it.id == id) it.copy(folderId = folderId, updatedAt = now) else it
                },
            )
        }
    }

    /**
     * Moves [id] and everything under it — folders and items alike — to the trash.
     *
     * `mutate` rather than `mutateVault`, because the folder list changes and
     * [VaultMutator.build]'s tree validation is what keeps a malformed tree off disk.
     *
     * One [Instant] for the whole operation, hoisted before the loops. A per-record `Instant.now()`
     * would give the subtree slightly different stamps, and `FolderTree.mergeFolder` orders on
     * exactly that value — a subtree whose members disagree about when they were trashed is the
     * torn state [Folder.trashRootId] exists to survive, and there is no reason to manufacture it
     * here. (`moveToTrash` already does it this way; `deleteFolder` and `duplicateFolder` each take
     * their own, so it is not automatic.)
     *
     * Anything already in the trash is left exactly as it is. It belongs to an earlier operation
     * with its own root, and re-tagging it would fold two separate deletions into one, so restoring
     * this folder would resurrect something the user had separately thrown away.
     *
     * `parentId` is never cleared. The trashed subtree keeps its shape, which is what lets a restore
     * rebuild it without storing a snapshot of where everything was.
     */
    suspend fun trashFolder(id: UUID): TrashFolderOutcome {
        lateinit var outcome: TrashFolderOutcome
        mutate { mutator ->
            val root = mutator.folders.firstOrNull { it.id == id } ?: error("unknown folder: $id")
            check(root.deletedAt == null) { "folder is already in trash: $id" }
            val now = Instant.now()
            val scope = FolderTree.descendantIds(mutator.folders, id)

            var folderCount = 0
            mutator.folders.replaceAll { folder ->
                if (folder.id in scope && folder.deletedAt == null) {
                    folderCount += 1
                    folder.copy(deletedAt = now, trashUpdatedAt = now, trashRootId = id)
                } else {
                    folder
                }
            }

            var itemCount = 0
            mutator.items.replaceAll { item ->
                val parent = item.folderId
                if (parent != null && parent in scope && item.deletedAt == null) {
                    itemCount += 1
                    item.copy(deletedAt = now, updatedAt = now, trashedWithFolderId = id)
                } else {
                    item
                }
            }

            outcome = TrashFolderOutcome(id, folderCount, itemCount)
        }
        return outcome
    }

    /**
     * Brings [id] and everything its own trashing took down back out of the trash.
     *
     * Keyed on [Folder.trashRootId] rather than on the stamp the folders share. The two look
     * equivalent and are not: `mergeFolder` decides each record separately, so after a merge one
     * subtree can hold several different `trashUpdatedAt` values, and a restore matching on the
     * instant would bring back part of a tree and leave the rest invisible. Keying on the root also
     * gives the behaviour the user expects when deletions nest — trash a child, then trash its
     * parent, and restoring the parent leaves the child where they put it.
     *
     * [id] is restored whether or not it points at itself, so a folder that a merge left carrying
     * someone else's root is still recoverable by the row the user actually taps.
     *
     * An item comes back only if its own folder is live once the folders are back. Its marker names
     * the operation, not a promise: after a merge tear the folder it belongs in can still be in the
     * trash, and restoring the item anyway would put a live item somewhere nothing can show it.
     */
    suspend fun restoreFolder(id: UUID): RestoreFolderOutcome {
        lateinit var outcome: RestoreFolderOutcome
        mutate { mutator ->
            val root = mutator.folders.firstOrNull { it.id == id } ?: error("unknown folder: $id")
            check(root.deletedAt != null) { "folder is not in trash: $id" }

            val now = Instant.now()
            var folderCount = 0
            mutator.folders.replaceAll { folder ->
                if (folder.deletedAt != null && (folder.id == id || folder.trashRootId == id)) {
                    folderCount += 1
                    folder.copy(deletedAt = null, trashUpdatedAt = now, trashRootId = null)
                } else {
                    folder
                }
            }

            // Ancestor fallback. The folder keeps its parentId through a trashing, so the parent it
            // wants back may itself still be in the trash — restore it there and it returns to a
            // place the user cannot navigate to. Climb to the nearest live ancestor instead, and
            // report where it landed so the caller can say so.
            val restoredRoot = mutator.folders.first { it.id == id }
            val liveById = mutator.folders.filter { it.deletedAt == null }.associateBy { it.id }
            // Bounded by a visited set for the same reason `pathOf` and `descendantIds` are: a
            // cyclic parent chain is rejected on the way to disk but can still arrive from a
            // restored snapshot, and climbing one unbounded would hang the restore rather than
            // fail it. Giving up lands the folder at the root, which is always reachable.
            val climbed = mutableSetOf<UUID>()
            var destination = restoredRoot.parentId
            while (destination != null && destination !in liveById && climbed.add(destination)) {
                destination = mutator.folders.firstOrNull { it.id == destination }?.parentId
            }
            if (destination != null && destination !in liveById) destination = null
            val reparented = destination != restoredRoot.parentId
            if (reparented) {
                val index = mutator.folders.indexOfFirst { it.id == id }
                mutator.folders[index] = restoredRoot.copy(
                    parentId = destination,
                    parentUpdatedAt = now,
                )
            }

            val liveFolderIds = mutator.folders.filter { it.deletedAt == null }.map { it.id }.toSet()
            var itemCount = 0
            mutator.items.replaceAll { item ->
                val parent = item.folderId
                if (item.trashedWithFolderId == id && parent != null && parent in liveFolderIds) {
                    itemCount += 1
                    item.copy(deletedAt = null, updatedAt = now, trashedWithFolderId = null)
                } else {
                    item
                }
            }

            outcome = RestoreFolderOutcome(
                restoredId = id,
                folderCount = folderCount,
                itemCount = itemCount,
                reparented = reparented,
                reparentedTo = destination,
            )
        }
        return outcome
    }

    /**
     * Empties the trash: every trashed item, every trashed folder, and everything filed inside one.
     *
     * **`internal`, so no UI module can reach it.** It destroys by predicate — everything carrying a
     * `deletedAt` — which stopped being a safe definition of "the trash" the moment part of the trash
     * could be concealed. A screen calling this would destroy entries the user was never shown and
     * could not have agreed to lose. Screens call [purgeVisibleTrash] with a [VisibleTrashPurgePlan];
     * this remains as the primitive the folder-repair tests drive directly.
     *
     * `mutate`, because removing folders can leave the tree invalid. Normally it cannot — trashing
     * takes a whole subtree, so a trashed folder never has a live child — but a merge can produce
     * one, and dropping its parent would leave a dangling `parentId` that `VaultMutator.build`
     * refuses. Survivors of a purged folder are lifted to the root rather than deleted: purge is
     * already the one irreversible path here, and it should destroy what the user put in the trash,
     * not what a merge happened to leave sitting under it.
     */
    internal suspend fun purgeTrash() = mutate { mutator ->
        val purgedFolderIds = mutator.folders.filter { it.deletedAt != null }.map { it.id }.toSet()

        // Trashed items go whether or not they carry a marker: an item trashed on its own inside a
        // folder that was later trashed has none, and it is still the user's deleted data. A *live*
        // item inside one — only reachable through a merge — is not, so it is unfiled rather than
        // destroyed. Deleting it would be the purge quietly taking data the user never trashed.
        mutator.items.removeAll { it.deletedAt != null }
        mutator.folders.removeAll { it.id in purgedFolderIds }

        val survivingIds = mutator.folders.map { it.id }.toSet()
        mutator.items.replaceAll { item ->
            val parent = item.folderId
            if (parent != null && parent !in survivingIds) item.copy(folderId = null) else item
        }
        mutator.folders.replaceAll { folder ->
            val parent = folder.parentId
            if (parent != null && parent !in survivingIds) folder.copy(parentId = null) else folder
        }
    }

    /**
     * Destroys one trash root and everything that went down with it. Irreversible.
     *
     * `internal` for [purgeTrash]'s reason: it is scoped by predicate, not by what the user saw.
     *
     * Scoped by the same predicate [restoreFolder] uses, so the row's counts describe exactly what
     * this removes — the confirmation names a number, and a number that came from a different rule
     * than the deletion is worse than no number.
     *
     * A *live* item or folder found inside the subtree is lifted out rather than destroyed, for
     * [purgeTrash]'s reason: only a merge puts one there, the user never trashed it, and purge is
     * the one path in this app with no undo behind it.
     */
    internal suspend fun purgeFolder(id: UUID) = mutate { mutator ->
        val root = mutator.folders.firstOrNull { it.id == id } ?: error("unknown folder: $id")
        check(root.deletedAt != null) { "folder is not in the trash: $id" }
        val purgedIds = mutator.folders
            .filter { it.deletedAt != null && (it.id == id || it.trashRootId == id) }
            .map { it.id }
            .toSet()

        // `trashedWithFolderId`, not `folderId` — see `planTrashPurge`. Keyed on the current folder
        // this destroyed an item that had been trashed on its own before the folder was, which the
        // folder's row never counted.
        mutator.items.removeAll { it.deletedAt != null && it.trashedWithFolderId in purgedIds }
        mutator.folders.removeAll { it.id in purgedIds }

        mutator.items.replaceAll { item ->
            val parent = item.folderId
            if (parent != null && parent in purgedIds) item.copy(folderId = null) else item
        }
        mutator.folders.replaceAll { folder ->
            val parent = folder.parentId
            if (parent != null && parent in purgedIds) folder.copy(parentId = null) else folder
        }
    }

    /**
     * What an Empty Trash or a single-folder purge would destroy, as seen right now.
     *
     * Handed to the confirmation dialog and handed back to [purgeVisibleTrash] unchanged. See
     * [VisibleTrashPurgePlan] for why the destructive path carries a list of ids instead of the
     * predicate it used to carry.
     */
    fun planTrashPurge(scope: PurgeScope): VisibleTrashPurgePlan =
        planTrashPurge(requireVault(), visibilitySnapshot(), scope)

    private fun planTrashPurge(
        vault: Vault,
        snapshot: VaultVisibilitySnapshot,
        scope: PurgeScope,
    ): VisibleTrashPurgePlan {
        val trashed = vault.folders.filter { it.deletedAt != null }
        val inScope = when (scope) {
            PurgeScope.AllTrash -> trashed
            is PurgeScope.Folder -> trashed.filter { it.id == scope.id || it.trashRootId == scope.id }
        }
        val folderIds = inScope
            .filterNot { it.id in snapshot.concealedFolderIds }
            .mapTo(LinkedHashSet()) { it.id }

        // A folder-scoped purge takes the items that went down *with* that folder — keyed on
        // `trashedWithFolderId`, the same marker `restoreFolder` and the row's own item count use.
        //
        // Keyed on `folderId` (as `purgeFolder` still is) it also took an item that had been trashed
        // on its own **before** the folder was: `trashFolder` only marks items that were still live,
        // so such an item keeps `trashedWithFolderId == null`, keeps its own row in the flat trash
        // list, and is *not* in the number the folder's confirmation names. The dialog could say
        // "1 folder, 0 items" and destroy an item whose row was on screen beside it. Found by a QA
        // sweep, 2026-08-19.
        //
        // AllTrash deliberately stays a predicate over everything deleted: there the confirmation
        // counts the flat list, which contains exactly those markerless items.
        val itemIds = vault.items
            .filter { it.isDeleted }
            .filter {
                when (scope) {
                    PurgeScope.AllTrash -> true
                    is PurgeScope.Folder -> it.trashedWithFolderId in folderIds
                }
            }
            .filterNot { it.id in snapshot.concealedItemIds }
            .mapTo(LinkedHashSet()) { it.id }

        return VisibleTrashPurgePlan(
            scope = scope,
            itemIds = itemIds,
            folderIds = folderIds,
            fingerprint = purgeFingerprint(scope, itemIds, folderIds),
        )
    }

    /**
     * Destroys exactly what [plan] names, or nothing at all. Irreversible.
     *
     * The plan is re-derived here, inside the mutation lock, and a changed fingerprint returns
     * [PurgeOutcome.Stale] without writing. That is the whole reason this is not a predicate: a lease
     * can expire, a reveal can land, another surface can restore a row, all while the confirmation
     * dialog is on screen — and a purge that widened itself to match the new state would be
     * destroying records the user was never shown.
     *
     * Survivors are concealed records, and they are repaired rather than left dangling. A surviving
     * folder whose parent was destroyed climbs to its nearest surviving ancestor (the root, if there
     * is none) and is retagged with the trash root it now belongs to; an item that went down with a
     * destroyed folder is re-tagged to the surviving root above it, or promoted to its own trash row
     * when nothing survives above it. Blobs need no special handling: the items still exist, and
     * reachability is computed from the complete storage inventory.
     */
    suspend fun purgeVisibleTrash(plan: VisibleTrashPurgePlan): PurgeOutcome = mutationMutex.withLock {
        val vault = requireVault()
        val now = clock.instant()
        val current = planTrashPurge(
            vault,
            VaultVisibility.project(vault, now, lockManager.revealSession.revealed.value),
            plan.scope,
        )
        if (current.fingerprint != plan.fingerprint) return@withLock PurgeOutcome.Stale

        val mutator = VaultMutator(vault)
        // The pre-removal parent links, because the climb has to walk through the folders that are
        // about to stop existing to find the one that does not.
        val originalParent = vault.folders.associate { it.id to it.parentId }

        mutator.items.removeAll { it.id in plan.itemIds }
        mutator.folders.removeAll { it.id in plan.folderIds }
        val surviving = mutator.folders.mapTo(HashSet()) { it.id }

        mutator.folders.replaceAll { folder ->
            val parent = folder.parentId
            if (parent == null || parent in surviving) return@replaceAll folder
            // Bounded by a visited set for `restoreFolder`'s reason: a cyclic chain is refused on the
            // way to disk but can arrive from a merge, and climbing one unbounded would hang the
            // purge rather than fail it. Giving up lands the folder at the root, which always exists.
            val climbed = HashSet<UUID>()
            var candidate = originalParent[parent]
            while (candidate != null && candidate !in surviving && climbed.add(candidate)) {
                candidate = originalParent[candidate]
            }
            folder.copy(
                parentId = candidate?.takeIf { it in surviving },
                parentUpdatedAt = now,
            )
        }

        // Retagged from the *new* parent chain: a survivor whose trash root was destroyed is now the
        // root of what is left, and a `trashRootId` still naming the destroyed folder would make it
        // unrestorable — `restoreFolder` keys on exactly that field.
        val byId = mutator.folders.associateBy { it.id }
        val newTrashRoot = mutator.folders
            .filter { it.deletedAt != null }
            .associate { it.id to trashRootOf(it, byId) }
        mutator.folders.replaceAll { folder ->
            val root = newTrashRoot[folder.id]
            if (root == null || folder.trashRootId == root) folder
            else folder.copy(trashRootId = root, trashUpdatedAt = now)
        }

        mutator.items.replaceAll { item ->
            var next = item
            val wentDownWith = next.trashedWithFolderId
            if (wentDownWith != null && wentDownWith !in surviving) {
                // Prefer the surviving root above it, so a concealed subtree keeps showing as one
                // folder row after reveal instead of scattering into loose rows.
                val home = next.folderId?.takeIf { it in surviving }
                next = next.copy(trashedWithFolderId = home?.let(newTrashRoot::get), updatedAt = now)
            }
            val parent = next.folderId
            if (parent != null && parent !in surviving) next = next.copy(folderId = null, updatedAt = now)
            next
        }

        lockManager.commit(mutator.build().normalizedForSave(now))
        PurgeOutcome.Purged(itemCount = plan.itemIds.size, folderCount = plan.folderIds.size)
    }

    /** The topmost still-trashed ancestor of [folder], which is [folder] itself when it has none. */
    private fun trashRootOf(folder: Folder, byId: Map<UUID, Folder>): UUID {
        var root = folder
        val climbed = HashSet<UUID>()
        while (climbed.add(root.id)) {
            val parent = root.parentId?.let(byId::get) ?: break
            if (parent.deletedAt == null) break
            root = parent
        }
        return root.id
    }

    fun getAllItems(): List<VaultItem> = requireVault().items.filterNot(VaultItem::isDeleted)

    /**
     * The trash's flat item list — **excluding items that went down with a folder.**
     *
     * Those are represented by their folder's row instead. Without the exclusion, trashing one
     * forty-item folder buries the individually-deleted items the user actually came here to find,
     * and offers forty separate restores for something that is one thing.
     *
     * An item trashed on its own *inside* a folder that was later trashed keeps `trashedWithFolderId
     * = null` — `trashFolder` only marks items that were still live — so it stays here, restorable
     * by itself. That is right: the user deleted it deliberately, before the folder went anywhere.
     */
    fun getTrashItems(): List<VaultItem> =
        requireVault().items.filter { it.isDeleted && it.trashedWithFolderId == null }

    /**
     * The trashed folders that get a row, with the counts their restore would bring back.
     *
     * **A trash root is a trashed folder whose parent is null or is not itself trashed** — not
     * simply one with no parent. After a restore's ancestor fallback, or a merge, a trashed folder
     * can sit under a live one; defining the root by `parentId == null` would leave it with no row
     * and its whole subtree unreachable from any screen.
     *
     * The counts are computed with the *same* predicate `restoreFolder` uses, so the number on the
     * row is what actually comes back rather than an independent guess that can drift from it.
     */
    fun getTrashedFolders(): List<TrashedFolder> {
        val vault = requireVault()
        val trashed = vault.folders.filter { it.deletedAt != null }
        val trashedIds = trashed.map { it.id }.toSet()
        return trashed
            .filter { it.parentId == null || it.parentId !in trashedIds }
            .map { root ->
                TrashedFolder(
                    folder = root,
                    folderCount = trashed.count { it.id == root.id || it.trashRootId == root.id },
                    itemCount = vault.items.count { it.trashedWithFolderId == root.id },
                )
            }
    }

    fun getFavorites(): List<VaultItem> = getAllItems().filter(VaultItem::favorite)

    fun search(query: String): List<VaultItem> {
        val needle = query.trim().lowercase(Locale.ROOT).foldDiacritics()
        if (needle.isEmpty()) return getAllItems()
        val index = searchIndex()
        val folderPaths = requireVault().folderPaths()
        return getAllItems().filter { item ->
            index[item]?.any { needle in it }
                ?: item.matchesSearchQuery(needle, folderPaths[item.folderId].orEmpty())
        }
    }

    /**
     * [search], over the trash instead of the live vault.
     *
     * Trash search used to match the **title only**, while the vault list matched title, username,
     * URLs, notes, tags and custom fields — so an item findable a moment before deletion became
     * unfindable after it, which is exactly backwards for a screen whose job is "I deleted the wrong
     * thing". Recorded as UX debt 8-3.
     *
     * It could not be fixed at the call site: `search()` excludes deleted items by definition, and
     * `matchesSearchQuery` is `internal` to this module, so `:feature:vaultlist` cannot reach it.
     * Hence a second entry point here rather than a copy of the matcher there — which is also what
     * keeps the two paths from drifting on what "matches" means. The matcher's own decisions carry
     * over unchanged, including that **masked custom-field values are deliberately not searchable**
     * and that card numbers are narrowed rather than exposed as raw fields.
     */
    fun searchTrash(query: String): List<VaultItem> {
        val needle = query.trim().lowercase(Locale.ROOT).foldDiacritics()
        if (needle.isEmpty()) return getTrashItems()
        val index = searchIndex()
        val folderPaths = requireVault().folderPaths()
        return getTrashItems().filter { item ->
            index[item]?.any { needle in it }
                ?: item.matchesSearchQuery(needle, folderPaths[item.folderId].orEmpty())
        }
    }

    /**
     * The normalized text of every item in the currently-unlocked vault, built once per vault
     * snapshot instead of once per keystroke.
     *
     * Measured on an emulator with 10,000 items (`VaultScaleTest`): a single keystroke took
     * **237 ms** before this existed, and **51 ms** after. `matchesSearchQuery` lowercased and
     * diacritic-folded roughly six fields of every item on every call, so the cost was ~60,000 fresh
     * strings per character typed, and it was paid again for the next character. Nothing was wrong
     * with the matcher; it was being asked to redo identical work at the rate a person types.
     *
     * Keyed by **item identity, not id**. `VaultItem` is immutable and a commit rebuilds the list
     * while leaving untouched items as the very same instances, so re-indexing after an edit reuses
     * every entry but the one that actually changed — which is what keeps a save from costing a
     * full re-index. It is also what makes staleness structurally impossible: an edited item is a
     * new instance, so it cannot inherit the old one's entry. An `IdentityHashMap` additionally
     * avoids `VaultItem.hashCode()`, which for a data class walks every field including the notes.
     *
     * **It holds no secret this process was not already holding.** The entries come from
     * [searchableText], which by construction excludes passwords, CVV, PIN, TOTP secrets and masked
     * custom-field values; everything in it is plaintext already resident in the unlocked `Vault`.
     * Any future widening of [searchableText] has to be judged against that, not against this cache.
     *
     * Callers fall back to computing the haystack inline on a miss, so an absent entry is a
     * performance matter and can never change which items match.
     *
     * That fallback is also what makes the [indexCharBudget] cap below safe: the index is allowed to
     * cover only part of the vault, and a partial index is a partial speed-up rather than a wrong
     * answer. Nothing here may ever become load-bearing for correctness.
     */
    private fun searchIndex(): Map<VaultItem, List<String>> {
        val vault = requireVault()
        val cached = searchIndex
        if (indexedVault === vault && cached != null) return cached
        val folderPaths = vault.folderPaths()
        // Entry reuse is keyed on item identity, which is exactly the wrong key once the haystack
        // depends on something outside the item. Renaming a folder leaves every item in it the same
        // instance, so `previous[item]` would hand back a haystack still carrying the old name and
        // the rename would never become searchable. Reuse is therefore conditional on the folder
        // names being unchanged; when they are not, every entry is recomputed. That is the rare
        // case (a rename), and the common one — an edit to a single item — is untouched.
        val previous = if (indexedFolderNames == folderPaths) cached.orEmpty() else emptyMap()
        val rebuilt = IdentityHashMap<VaultItem, List<String>>(vault.items.size)
        val budget = indexCharBudget
        var used = 0L
        try {
            for (item in vault.items) {
                if (used > budget) break
                val haystack = previous[item] ?: item.searchHaystack(folderPaths[item.folderId].orEmpty())
                rebuilt[item] = haystack
                used += haystack.sumOf { it.length }
            }
        } catch (_: OutOfMemoryError) {
            // The budget is an estimate, and an estimate can be wrong on a device nobody measured.
            // Keeping whatever was built costs nothing — every un-indexed item already falls back to
            // computing its haystack inline — so the worst outcome here is a slower search rather
            // than a dead process. Catching an `Error` is deliberate, on the same reasoning as
            // `commit`: the failed allocation is one string list and it is unreachable immediately.
        }
        indexedVault = vault
        indexedFolderNames = folderPaths
        searchIndex = rebuilt
        armPlaintextCacheRelease()
        return rebuilt
    }

    /**
     * Folder id to its ancestor names, root first — the haystack entries an item in that folder
     * gets. Empty names are dropped.
     *
     * The whole chain rather than the direct folder alone, because a folder is now a location: an
     * item in "Work > Bank" is *in* Work, and a search for "work" that did not find it would be
     * wrong in the same way the direct-folder-only search was before folder names were indexed at
     * all. Each ancestor is kept as its **own** entry rather than one joined string — matching is
     * substring-per-field, so a joined "work bank" would match the query "k b", which is nonsense.
     */
    private fun Vault.folderPaths(): Map<UUID, List<String>> {
        val byId = folders.associateBy { it.id }
        return folders.associate { folder ->
            var current: Folder? = folder
            val names = ArrayDeque<String>()
            val seen = mutableSetOf<UUID>()
            while (current != null && seen.add(current.id)) {
                if (current.name.isNotBlank()) names.addFirst(current.name)
                current = current.parentId?.let(byId::get)
            }
            folder.id to names.toList()
        }
    }

    companion object {
        /**
         * How much text the search index may hold, as a share of this device's heap cap.
         *
         * The index roughly doubles the resident plaintext text of the vault — bought deliberately,
         * and it is what takes a keystroke at 10,000 items from 237 ms to 51 ms. On the API 35
         * image's 192 MiB cap that is free. On API 28's **48 MiB**, this app's own minSdk, it is a
         * meaningful share of a heap that a 10,000-item save already cannot fit in (H-2, measured
         * 2026-07-29).
         *
         * Derived from `maxMemory()` rather than from an item count, because that is the shape the
         * bound actually has: H-2 established that what runs out is heap, and any item count would
         * be wrong on some device in the supported range, in one direction or the other. 5% of the
         * cap, at two bytes per char as the pessimistic assumption — ART stores ASCII strings
         * compressed at one byte, so on real vault text this reserves roughly twice what it spends.
         * At 48 MiB that admits ~1.2M characters, which a 10,000-item vault exceeds and an ordinary
         * one never approaches; at 192 MiB it admits ~4.8M and nothing is capped in practice.
         */
        internal fun defaultIndexCharBudget(): Long = Runtime.getRuntime().maxMemory() / 20 / 2
    }

    /**
     * The logins that match a filling context — the single feed behind autofill datasets, the save
     * prompt's "you already have this" suppression, and the credential provider's password entries.
     *
     * Through the presentation projection, not `getAllItems()`. This is the surface the feature
     * exists for: a concealed bank login that still offers itself in another app's autofill sheet has
     * been hidden from the vault list and nowhere else, which is worse than not hiding it at all —
     * the user believes it is gone. Concealment applies to the *absence* of a match too, so a hidden
     * login no longer suppresses the save prompt; the prompt behaves exactly as it does for a domain
     * the vault has never seen, which is the only behaviour that does not disclose the entry.
     *
     * A reveal session lifts it, deliberately: reaching autofill always means backgrounding this app,
     * and the reveal follows the existing background grace timer rather than a second, stricter rule
     * of its own (spec §5). Hand-someone-your-phone is a backgrounding that outlives the grace.
     */
    fun findMatching(
        webDomain: String?,
        packageName: String?,
    ): List<VaultItem> {
        if (webDomain == null && packageName == null) return emptyList()
        return visibilitySnapshot().liveItems.filter { item ->
            val login = item.content as? Login ?: return@filter false
            val domainMatches =
                webDomain != null && login.uris.any { matchesUri(webDomain, it) }
            val packageMatches =
                packageName != null && packageName in login.androidPackages
            domainMatches || packageMatches
        }
    }

    // ---- folders ----------------------------------------------------------------------------
    //
    // Every write here goes through `mutate`, so `VaultMutator.build` validates the resulting tree
    // and an invalid one never reaches disk. Rejections are `check`/`error`, i.e.
    // IllegalStateException, which is what the ViewModels' `runCatching` already handles.

    /**
     * Creates a folder under [parentId], refusing a blank name, an unknown parent, a sibling that
     * already has this name, and a parent already at the depth limit.
     *
     * Sibling-scoped rather than global: a "Bank" inside Work and a "Bank" inside Personal are two
     * different places and forbidding the second would be arbitrary. Two vaults merging by id can
     * still produce same-named siblings at rest, which is legal — the UI disambiguates them — but
     * nothing the user types is allowed to create one.
     */
    suspend fun createFolder(name: String, parentId: UUID? = null): Folder {
        val trimmed = name.trim()
        check(trimmed.isNotEmpty()) { "folder name is blank" }
        lateinit var created: Folder
        mutate { mutator ->
            if (parentId != null) {
                check(mutator.folders.any { it.id == parentId }) { "unknown parent folder: $parentId" }
                val parentDepth = FolderTree.depthOf(mutator.folders, parentId)
                if (parentDepth == null || parentDepth >= FolderTree.MAX_DEPTH) throw FolderTooDeepException()
            }
            mutator.nameHolders().requireNoSibling(trimmed, parentId)
            created = Folder(
                id = UUID.randomUUID(),
                name = trimmed,
                parentId = parentId,
                updatedAt = Instant.now(),
                // Stamped on creation for the same reason `updatedAt` is: a folder created after a
                // backup was taken must not have its parent overwritten by that backup's silence.
                parentUpdatedAt = Instant.now(),
            )
            mutator.folders += created
        }
        return created
    }

    /** Renames a folder, stamping `updatedAt` only — a rename is not a move (see `mergeFolder`). */
    suspend fun renameFolder(id: UUID, name: String): Folder {
        val trimmed = name.trim()
        check(trimmed.isNotEmpty()) { "folder name is blank" }
        lateinit var renamed: Folder
        mutate { mutator ->
            val index = mutator.folders.indexOfFirst { it.id == id }
            check(index >= 0) { "unknown folder: $id" }
            val current = mutator.folders[index]
            mutator.nameHolders().requireNoSibling(trimmed, current.parentId, except = id)
            renamed = current.copy(name = trimmed, updatedAt = Instant.now())
            mutator.folders[index] = renamed
        }
        return renamed
    }

    /**
     * Re-parents a folder, stamping `parentUpdatedAt` only.
     *
     * Refuses a target inside the folder's own subtree — which covers moving it into itself — since
     * that detaches the whole branch from the vault and no list would ever reach it again.
     */
    suspend fun moveFolder(id: UUID, newParentId: UUID?): Folder {
        lateinit var moved: Folder
        mutate { mutator ->
            val index = mutator.folders.indexOfFirst { it.id == id }
            check(index >= 0) { "unknown folder: $id" }
            val current = mutator.folders[index]
            if (current.parentId == newParentId) {
                moved = current
                return@mutate
            }
            if (newParentId != null) {
                check(mutator.folders.any { it.id == newParentId }) { "unknown parent folder: $newParentId" }
                check(!FolderTree.wouldBeDescendant(mutator.folders, id, newParentId)) {
                    "cannot move a folder into its own subtree"
                }
            }
            // This guard was missing entirely. Unknown parent, cycle and name clash were checked;
            // depth was not, so an over-deep move was composed and then rejected by
            // `FolderTree.validate` inside `VaultMutator.build` — the commit refusing a request the
            // repository had already accepted. Same rule as `createFolder`, and unlike creation a
            // move carries a whole subtree, so the payload's own height counts too.
            if (FolderTree.wouldExceedDepth(mutator.folders, id, newParentId)) throw FolderTooDeepException()
            mutator.nameHolders().requireNoSibling(current.name, newParentId, except = id)
            moved = current.copy(parentId = newParentId, parentUpdatedAt = Instant.now())
            mutator.folders[index] = moved
        }
        return moved
    }

    /**
     * Deletes a folder the way every other system does it — the folder and everything inside it go
     * together, and the trash is the undo.
     *
     * This used to promote child folders one level up and unfile direct items, so that deleting a
     * container never destroyed what was in it. That was the right call while folder deletion was
     * irreversible: it was the only thing standing between a mis-tap and lost data. Now that
     * [trashFolder] exists, the promotion is the surprising behaviour — the user asked to delete a
     * folder and got its contents scattered into the parent instead — and recoverability comes from
     * Trash rather than from refusing to delete.
     *
     * Kept as a named operation rather than replaced at the call sites, because "delete this
     * folder" is what the UI means and how it recovers is this layer's business.
     */
    suspend fun deleteFolder(id: UUID): TrashFolderOutcome = trashFolder(id)

    /**
     * Deep-copies a folder, its whole subtree of folders, and every item filed anywhere in that
     * subtree, minting fresh ids throughout. The clone's root is a sibling of the original — same
     * [Folder.parentId], so the same depth — which is why no depth check is needed here: if the
     * original validated, the clone validates too, and [VaultMutator.build] asserts it regardless.
     *
     * The root's name comes from [disambiguate], the same caller-supplied `(base, ordinal) -> name`
     * contract [deleteFolder] uses, so the "(copy)" wording stays a translated string in the UI and
     * never a literal in this module. Ordinal 1 is the first try; it climbs while a sibling clashes.
     *
     * Nothing is shared with the original: new folder ids, new item ids, and `parentId` rewired to
     * stay inside the clone. The original is left untouched.
     *
     * Copies only what the user can see — see [duplicableSubtree]: never trash, and never a
     * concealed record outside a reveal session (2026-10-02). The free-tier allowances are the
     * caller's to ask, through [DuplicateAllowance] and [itemsCopiedByDuplicate], the same way
     * every other creation path asks them above this layer.
     */
    suspend fun duplicateFolder(
        id: UUID,
        disambiguate: (String, Int) -> String,
    ): Folder {
        lateinit var created: Folder
        mutate { mutator ->
            val sources = requireVault()
                .copy(items = mutator.items.toList(), folders = mutator.folders.toList())
                .duplicableSubtree(id, clock.instant(), lockManager.revealSession.revealed.value)
                ?: error("unknown folder: $id")
            val source = sources.folders.first { it.id == id }
            val now = Instant.now()
            // The root is in the clone set, and so is every cloned folder's parent.
            val idMap = sources.folders.associate { it.id to UUID.randomUUID() }

            var ordinal = 1
            var name = disambiguate(source.name, ordinal)
            val nameHolders = mutator.nameHolders()
            while (nameHolders.hasSibling(name, source.parentId)) {
                ordinal += 1
                name = disambiguate(source.name, ordinal)
            }

            val clonedFolders = sources.folders.map { folder ->
                folder.copy(
                    id = idMap.getValue(folder.id),
                    // The root takes the disambiguated name and keeps the original's parent; every
                    // other folder keeps its name and re-points at its cloned parent.
                    name = if (folder.id == id) name else folder.name,
                    parentId = if (folder.id == id) source.parentId else idMap.getValue(folder.parentId!!),
                    updatedAt = now,
                    parentUpdatedAt = now,
                    // A duplicate starts visible (Decision 8): copying an invisible tree into another
                    // would create data the action never shows, and would carry stale merge stamps.
                    hiddenUntil = null,
                    hiddenUpdatedAt = null,
                )
            }
            val clonedItems = sources.items.map { item ->
                item.copy(
                    id = UUID.randomUUID(),
                    folderId = idMap.getValue(item.folderId!!),
                    hiddenUntil = null,
                    hiddenUpdatedAt = null,
                )
            }
            mutator.folders += clonedFolders
            mutator.items += clonedItems
            created = clonedFolders.first { it.id == idMap.getValue(id) }
        }
        return created
    }

    /**
     * The originals [duplicateFolder] would copy items from for [id] right now, by the same
     * selection — so an allowance asked about this list is asked about exactly what gets created.
     * Empty when the folder cannot be duplicated at all.
     */
    fun itemsCopiedByDuplicate(id: UUID): List<VaultItem> =
        requireVault().duplicableSubtree(id, clock.instant(), lockManager.revealSession.revealed.value)
            ?.items.orEmpty()

    /**
     * [rootId] and everything under it, cached per folder-list snapshot.
     *
     * Raw, like every folder read in this class: the callers pass ids they got from already-filtered
     * state, so a trashed folder is never the root, and the trash operations need the subtree whole.
     */
    fun folderDescendants(rootId: UUID): Set<UUID> {
        val folders = requireVault().folders
        if (cachedFolders != folders) {
            cachedFolders = folders
            cachedDescendants = ConcurrentHashMap()
            // Browsing folders caches the whole folder list, so browsing has to arm the release
            // too. Until 2026-09-07 the release was armed from `searchIndex()` alone, so a session
            // that never used the search field left every folder name resident past `lock()` in a
            // `@Singleton` repository — the more common of the two journeys, and the one nothing
            // covered. Idempotent with the search path: `cacheReleaseArmed` is compare-and-set, so
            // whichever path reaches it first is the only one that starts a coroutine.
            armPlaintextCacheRelease()
        }
        val cache = cachedDescendants
        cache[rootId]?.let { return it }
        val computed = FolderTree.descendantIds(folders, rootId)
        cache[rootId] = computed
        return computed
    }

    /** The ancestor chain of [folderId], root first, or null if it is unknown or its chain broken. */
    fun folderPathOf(folderId: UUID): List<Folder>? = FolderTree.pathOf(requireVault().folders, folderId)

    /**
     * What is wrong with the stored folder tree, if anything — run once per unlock.
     *
     * Covers items as well as folders: an item whose `folderId` names a folder that is gone appears
     * in no list at all, neither the root one (which shows unfiled items) nor any folder's. That is
     * data the user cannot see and cannot get back without this.
     *
     * **A trashed folder is a known folder.** Reading `.live` here would count every item in every
     * trashed folder as an orphan, raise the repair banner at unlock, and — one tap later — hand
     * [repairFolderTree] a list it would unfile in a single save. The repair offered to fix a
     * broken vault would be the thing that broke it.
     */
    fun checkFolderTreeHealth(): TreeHealthStatus {
        val vault = requireVault()
        val reasons = (FolderTree.validate(vault.folders) as? FolderTree.ValidationResult.Invalid)
            ?.reasons.orEmpty()
        val known = vault.folders.map { it.id }.toSet()
        val orphans = vault.items.count { it.folderId != null && it.folderId !in known }
        val status = TreeHealthStatus(reasons, orphans)
        folderRepairFlag = status.needsRepair
        return status
    }

    /**
     * Writes the normalized tree and unfiles orphaned items, in one save.
     *
     * `known` counts trashed folders, for [checkFolderTreeHealth]'s reason: an item is orphaned when
     * its folder is *gone*, and a trashed folder is not gone. `FolderTree.normalize` also runs here,
     * which is what trashes any live folder left under a trashed parent by a merge — inheriting the
     * parent's stamp rather than taking a new one, so the repair cannot outrank a real restore.
     */
    suspend fun repairFolderTree(): RepairOutcome {
        lateinit var outcome: RepairOutcome
        mutate { mutator ->
            val normalized = FolderTree.normalize(mutator.folders)
            mutator.folders.clear()
            mutator.folders += normalized.folders
            val known = normalized.folders.map { it.id }.toSet()
            var unfiled = 0
            mutator.items.replaceAll { item ->
                val folderId = item.folderId
                if (folderId != null && folderId !in known) {
                    unfiled += 1
                    item.copy(folderId = null)
                } else {
                    item
                }
            }
            outcome = RepairOutcome(normalized.changes, unfiled)
        }
        folderRepairFlag = false
        return outcome
    }

    /**
     * Whether [parentId] already holds a folder called [name], **counting trashed ones**.
     *
     * A trashed folder still occupies its name, because restoring it has to be possible. Ignoring
     * them would let a new folder take the name, and the restore would then produce two same-name
     * siblings — a state `createFolder`, `renameFolder` and `moveFolder` all refuse to create and
     * which nothing else prevents. The import path deliberately takes the other side of this: see
     * `reconcileImportedFolders`.
     */
    private fun List<Folder>.hasSibling(name: String, parentId: UUID?, except: UUID? = null): Boolean =
        siblingNamed(name, parentId, except) != null

    private fun List<Folder>.siblingNamed(name: String, parentId: UUID?, except: UUID?): Folder? =
        firstOrNull { it.id != except && it.parentId == parentId && it.name.equals(name, ignoreCase = true) }

    /** Refuses [name] under [parentId] if a sibling holds it, saying whether that sibling is in Trash. */
    private fun List<Folder>.requireNoSibling(name: String, parentId: UUID?, except: UUID? = null) {
        siblingNamed(name, parentId, except)?.let { throw FolderNameTakenException(inTrash = it.deletedAt != null) }
    }

    /**
     * The folders whose names a new name may not repeat: all of them, minus what an invisible folder
     * is concealing outside a reveal.
     *
     * A hidden folder used to hold its name too, so typing it answered "That didn't work" over a list
     * showing no such folder — telling whoever holds the phone that one is hidden there, the question
     * `FolderViewModel.nameTakenAmongSiblings` already declines to answer (QA 2026-10-03). A trashed
     * folder still holds its name, so restoring it cannot produce two.
     */
    private fun VaultMutator.nameHolders(): List<Folder> {
        if (lockManager.revealSession.revealed.value) return folders
        val concealed = VaultVisibility.project(
            requireVault().copy(items = emptyList(), folders = folders.toList()),
            clock.instant(),
            revealSessionActive = false,
        ).concealedFolderIds
        return folders.filter { it.id !in concealed }
    }

    /**
     * Every record the vault holds, raw and unfiltered — the one complete storage inventory. See
     * [VaultStorageInventory]. Never derived from the presentation getters, and its result never
     * reaches a UI: `HiddenChokepointTest` enforces both.
     */
    fun getCompleteStorageInventory(): VaultStorageInventory {
        val vault = requireVault()
        return VaultStorageInventory(allItems = vault.items, allFolders = vault.folders)
    }

    /**
     * The explicitly-leased items and folders still concealing at the repository clock — the raw feed
     * for the authenticated Hidden-items management screen. Expired leases are excluded (a lazy
     * normalization has not necessarily cleared them yet), so a screen with none shows the same empty
     * state whether the feature was never used or every lease has expired.
     */
    fun getHiddenEntriesForManagement(): HiddenEntries {
        val vault = requireVault()
        val now = clock.instant()
        return HiddenEntries(
            directlyHiddenItems = vault.items.filter { HiddenLease.isHidden(it.hiddenUntil, now) },
            directlyHiddenFolders = vault.folders.filter { HiddenLease.isHidden(it.hiddenUntil, now) },
        )
    }

    /** The current presentation projection at the repository clock and reveal state. */
    fun visibilitySnapshot(): VaultVisibilitySnapshot =
        VaultVisibility.project(requireVault(), clock.instant(), lockManager.revealSession.revealed.value)

    private val presentationRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Recomputes [presentationState] now — the seam the [ForegroundVisibilityCoordinator] pulls at an
     * exact lease boundary, since expiry is the one change no lock/reveal/save event announces.
     */
    fun refreshPresentation() {
        presentationRefresh.tryEmit(Unit)
    }

    /**
     * The single presentation flow every screen collects. Recomputes on lock/unlock, reveal/conceal,
     * every save ([VaultLockManager.onSaved]) and each [refreshPresentation]. A lock flips
     * [lockManager]'s `lockState` to Locked synchronously inside the critical section that zeroizes the
     * DEK, so this maps straight to [VaultPresentationState.Locked] and no stale unlocked snapshot can
     * be replayed to a later subscriber.
     */
    val presentationState: StateFlow<VaultPresentationState> =
        combine(
            lockManager.lockState,
            lockManager.revealSession.revealed,
            merge(flowOf(Unit), lockManager.onSaved, presentationRefresh),
        ) { lock, revealed, _ ->
            when (lock) {
                is LockState.Unlocked ->
                    VaultPresentationState.Unlocked(VaultVisibility.project(lock.vault, clock.instant(), revealed))
                else -> VaultPresentationState.Locked
            }
        }.stateIn(scope, SharingStarted.Eagerly, VaultPresentationState.Locked)

    /**
     * Whether [phrase] would currently return any *visible* global search result (Decision 12). Used
     * to refuse a reveal phrase that collides with a live item, so setting it would immediately reveal
     * on every keystroke. Concealed matches do not count — the whole point is what the user can see.
     *
     * Materializes the phrase as a transient `String` for the matcher; it cannot be wiped (an
     * immutable `String` never can) and is a user secret being set, about to be hashed anyway.
     */
    private fun phraseCollidesWithVisible(phrase: CharArray): Boolean {
        val concealed = visibilitySnapshot().concealedItemIds
        return search(String(phrase)).any { it.id !in concealed }
    }

    /**
     * The collision check and the Argon2id derivation for a new reveal phrase, both off the caller's
     * thread. Null means the phrase collides with something visible; a returned verifier still has
     * to be checked for [RevealPhraseVerifier.isValid].
     *
     * The dispatcher lives here rather than in the two callers for the reason
     * [VaultLockManager.attemptHiddenReveal] gives at length: a requirement every call site has to
     * remember is one a call site will eventually forget. Both callers reach this from a bare
     * `viewModelScope.launch {}`, which is `Dispatchers.Main.immediate` — so the whole-vault
     * projection and whole-vault search in [phraseCollidesWithVisible] (the same pair
     * `VaultListViewModel.maybeAttemptReveal` already moved off Main, and on a large vault the
     * bigger half of this) and the floor-cost KDF after it were both running on the main thread.
     *
     * Deliberately *not* [kotlinx.coroutines.NonCancellable], unlike `attemptHiddenReveal`: that one
     * carries it because the search box cancels the job its KDF runs in, and there is no such caller
     * here. Adding it would only make a hide the user started uncancellable. The phrase wipe stays
     * in the callers' `finally`, outside this hop, so a cancellation mid-derivation still wipes it.
     */
    private suspend fun derivePhraseGate(phrase: CharArray): RevealPhraseVerifier? =
        withContext(Dispatchers.Default) {
            if (phraseCollidesWithVisible(phrase)) null else RevealPhraseGate.create(phrase)
        }

    /**
     * Hides [target] until [until], creating the reveal phrase from [firstPhrase] when the vault has
     * none yet (the first hide and the first phrase are one commit — Decision 4). Returns the outcome
     * and commits nothing on any non-[HideOutcome.Hidden] result. [firstPhrase] is wiped here.
     */
    suspend fun hide(
        target: HiddenTarget,
        until: Instant,
        firstPhrase: CharArray? = null,
    ): HideOutcome = mutationMutex.withLock {
        try {
            val vault = requireVault()
            val now = clock.instant()
            if (until != HiddenLease.INDEFINITE && !until.isAfter(now)) return@withLock HideOutcome.InvalidDuration
            val present = when (target) {
                is HiddenTarget.Item -> vault.items.any { it.id == target.id }
                is HiddenTarget.Folder -> vault.folders.any { it.id == target.id }
            }
            if (!present) return@withLock HideOutcome.TargetAbsent

            var newVerifier: RevealPhraseVerifier? = null
            if (vault.revealPhrase == null) {
                if (firstPhrase == null) return@withLock HideOutcome.PhraseRequired
                val gate = derivePhraseGate(firstPhrase)
                if (gate == null) return@withLock HideOutcome.PhraseCollision
                if (!gate.isValid) return@withLock HideOutcome.InvalidVerifier
                newVerifier = gate
            }

            val staged = vault
                .let { if (newVerifier != null) it.copy(revealPhrase = newVerifier) else it }
                .withHiddenLease(target, until, now)
            lockManager.commit(staged.normalizedForSave(now))
            HideOutcome.Hidden
        } finally {
            Zeroizer.wipe(firstPhrase)
        }
    }

    /** Clears [target]'s lease, stamping the unhide so it wins a later merge against a stale hide. */
    suspend fun stopHiding(target: HiddenTarget) = mutationMutex.withLock {
        val vault = requireVault()
        val now = clock.instant()
        lockManager.commit(vault.withHiddenLease(target, until = null, now = now).normalizedForSave(now))
    }

    /** Re-leases [target] to a new [until], stamping the change. Assumes a verifier already exists. */
    suspend fun changeHiddenUntil(target: HiddenTarget, until: Instant) = mutationMutex.withLock {
        val vault = requireVault()
        val now = clock.instant()
        lockManager.commit(vault.withHiddenLease(target, until, now).normalizedForSave(now))
    }

    /**
     * Rotates the reveal phrase (Decision 7: hidden state is not keyed by the phrase, so nothing is
     * re-keyed). [newPhrase] is wiped here. A collision with a visible item is refused, as at setup.
     */
    suspend fun changeRevealPhrase(newPhrase: CharArray): HideOutcome = mutationMutex.withLock {
        try {
            val verifier = derivePhraseGate(newPhrase) ?: return@withLock HideOutcome.PhraseCollision
            if (!verifier.isValid) return@withLock HideOutcome.InvalidVerifier
            val vault = requireVault()
            lockManager.commit(vault.copy(revealPhrase = verifier).normalizedForSave(clock.instant()))
            HideOutcome.Hidden
        } finally {
            Zeroizer.wipe(newPhrase)
        }
    }

    private fun Vault.withHiddenLease(target: HiddenTarget, until: Instant?, now: Instant): Vault =
        when (target) {
            is HiddenTarget.Item -> copy(
                items = items.map {
                    if (it.id == target.id) it.copy(hiddenUntil = until, hiddenUpdatedAt = now) else it
                },
            )
            is HiddenTarget.Folder -> copy(
                folders = folders.map {
                    if (it.id == target.id) it.copy(hiddenUntil = until, hiddenUpdatedAt = now) else it
                },
            )
        }

    /**
     * Lazy expiry normalization for a save (Decision 3). An expired *finite* lease becomes visible —
     * `hiddenUntil` cleared — and its stamp advances to the expiry instant, `max(oldStamp, expiry)`,
     * rather than to save time, so a later extension imported through a merge still beats this logical
     * expiry. [HiddenLease.INDEFINITE] and still-active leases are untouched. Reads never call this;
     * only the commit choke does, so a timer wake or a projection never rewrites the vault.
     */
    private fun Vault.normalizedForSave(now: Instant): Vault {
        fun normalizedLease(until: Instant?, stamp: Instant?): Pair<Instant?, Instant?>? {
            if (until == null || until == HiddenLease.INDEFINITE) return null
            if (until.isAfter(now)) return null // still hidden
            val advanced = if (stamp == null || until.isAfter(stamp)) until else stamp
            return null to advanced
        }
        val items = items.map { item ->
            normalizedLease(item.hiddenUntil, item.hiddenUpdatedAt)
                ?.let { (u, s) -> item.copy(hiddenUntil = u, hiddenUpdatedAt = s) } ?: item
        }
        val folders = folders.map { folder ->
            normalizedLease(folder.hiddenUntil, folder.hiddenUpdatedAt)
                ?.let { (u, s) -> folder.copy(hiddenUntil = u, hiddenUpdatedAt = s) } ?: folder
        }
        return copy(items = items, folders = folders)
    }

    /** Batches arbitrary item/folder edits into exactly one encrypted save. */
    suspend fun mutate(block: (VaultMutator) -> Unit) = mutationMutex.withLock {
        val mutator = VaultMutator(requireVault())
        block(mutator)
        lockManager.commit(mutator.build().normalizedForSave(clock.instant()))
    }

    private suspend fun mutateVault(transform: (Vault) -> Vault) = mutationMutex.withLock {
        lockManager.commit(transform(requireVault()).normalizedForSave(clock.instant()))
    }

    private fun requireVault(): Vault {
        val state = lockManager.lockState.value
        check(state is LockState.Unlocked) { "vault is locked" }
        return state.vault
    }
}

/**
 * This vault with item [id] out of the trash, filed somewhere that can actually show it.
 *
 * The folder-liveness climb is the whole point. [VaultRepository.setItemFolder] refuses a trashed
 * target — "filing something into an invisible folder is how an item disappears without being
 * deleted" — and [VaultRepository.restoreFolder] skips an item whose own folder is still trashed;
 * an individual restore is the third door into the same room, and it was the one left open.
 *
 * Climbs to the nearest live ancestor rather than dropping straight to the root, so an item filed
 * deep inside a trashed subtree lands as close to where the user left it as anything visible
 * allows. Same fallback, and the same bounded climb, as [VaultRepository.restoreFolder]: a cyclic
 * parent chain is rejected on the way to disk but can still arrive from a restored snapshot, and
 * climbing one unbounded would hang the restore rather than fail it.
 *
 * A top-level function rather than a method so the arithmetic is reachable without an unlocked
 * vault, which needs a device; the repository keeps the single caller.
 */
internal fun Vault.withItemRestoredFromTrash(id: UUID, now: Instant): Vault {
    val item = items.find { it.id == id } ?: error("item not found: $id")
    check(item.deletedAt != null) { "item is not in trash: $id" }

    val liveFolderIds = folders.filter { it.deletedAt == null }.map { it.id }.toSet()
    val climbed = mutableSetOf<UUID>()
    var destination = item.folderId
    while (destination != null && destination !in liveFolderIds && climbed.add(destination)) {
        destination = folders.firstOrNull { it.id == destination }?.parentId
    }
    // Also covers a folderId naming a folder this vault no longer has: the climb above walks off
    // the end of the list and leaves null, and a surviving trashed id is dropped here.
    if (destination != null && destination !in liveFolderIds) destination = null

    return copy(
        items = items.map {
            if (it.id == id) {
                it.copy(updatedAt = now, folderId = destination, deletedAt = null)
            } else {
                it
            }
        },
    )
}

/** The originals [VaultRepository.duplicateFolder] copies: the root, its subtree, their items. */
internal data class DuplicableSubtree(val folders: List<Folder>, val items: List<VaultItem>)

/**
 * What duplicating [rootId] copies: the part of its subtree the user can see right now, through the
 * same [VaultVisibility.project] every other user-facing read goes through. Null when the root
 * itself is not visible — trashed, unknown, or concealed outside a reveal session.
 *
 * This walked the *raw* lists until 2026-10-02, so two kinds of record the user could not see came
 * along. An item made invisible inside a visible folder (or an invisible subfolder) was copied with
 * its lease cleared — one menu action, no reveal phrase, and a permanently visible copy of every
 * concealed entry under the folder. And trashed descendants were copied with their trash markers
 * still naming the *original* trash root, so Trash grew phantom rows and restoring or purging the
 * original row acted on the copy too.
 *
 * The subtree is walked over the visible folders only, so a cloned folder's parent is always cloned
 * as well. In a reveal session the concealed records are on screen, so they are copied; Decision 8
 * of the make-invisible plan governs them from there, unchanged.
 *
 * A top-level function, like [withItemRestoredFromTrash], so the selection is provable without an
 * unlocked vault.
 */
internal fun Vault.duplicableSubtree(
    rootId: UUID,
    now: Instant,
    revealSessionActive: Boolean,
): DuplicableSubtree? {
    val visible = VaultVisibility.project(this, now, revealSessionActive)
    if (visible.liveFolders.none { it.id == rootId }) return null
    val subtree = FolderTree.descendantIds(visible.liveFolders, rootId)
    return DuplicableSubtree(
        folders = visible.liveFolders.filter { it.id in subtree },
        items = visible.liveItems.filter { it.folderId in subtree },
    )
}

class VaultMutator(
    private val initial: Vault,
) {
    val items: MutableList<VaultItem> = initial.items.toMutableList()
    val folders: MutableList<Folder> = initial.folders.toMutableList()

    /**
     * The reveal-phrase verifier the built vault will carry. Defaults to the one already there, so
     * an ordinary edit cannot change it by omission.
     *
     * Writable for exactly one caller: a **replace**-mode import, which swaps every item and folder
     * for another vault's. Without this the replace kept the *local* verifier over the *imported*
     * records — so a backup's hidden entries arrived concealed and the phrase that reveals them was
     * the one that had just been thrown away. The entries were still recoverable through the
     * authenticated Settings screen, which is why nothing was lost; the user's own reveal phrase for
     * their own data simply stopped working, with no message saying so.
     */
    var revealPhrase: RevealPhraseVerifier? = initial.revealPhrase

    /**
     * The choke point every folder-list write goes through, and therefore where the tree's
     * invariants are asserted.
     *
     * A throw here means the commit never happens, which is the right outcome for a write the app
     * itself composed: a cycle or a folder past the depth limit is a bug in the caller, not a state
     * the user should be left holding. Data arriving from *outside* — a merge, a restored snapshot
     * — is normalized before it gets here, so this never becomes the thing that makes an import
     * impossible to finish.
     */
    fun build(): Vault {
        val validation = FolderTree.validate(folders)
        check(validation.isValid) {
            "invalid folder tree: ${(validation as FolderTree.ValidationResult.Invalid).reasons}"
        }
        return initial.copy(
            items = items.toList(),
            folders = folders.toList(),
            revealPhrase = revealPhrase,
        )
    }
}

/**
 * Prefixes [matchesUri]'s [MatchStrategy.DOMAIN] third clause treats as non-identifying — the
 * label(s) a saved host may carry ahead of the bare apex without the two counting as different
 * sites. See the clause's own KDoc below for why this list exists and why it must stay short.
 */
private val GENERIC_HOST_PREFIXES = setOf("www", "accounts")

internal fun matchesUri(
    candidate: String,
    rule: UriMatch,
): Boolean = when (rule.strategy) {
    MatchStrategy.DOMAIN -> {
        val candidateHost = normalizedHost(candidate) ?: return false
        val configuredHost = normalizedHost(rule.uri) ?: return false
        candidateHost == configuredHost ||
            candidateHost.endsWith(".$configuredHost") ||
            // This third arm exists for exactly one shape: a credential saved from a page whose
            // reported host still carried a generic prefix (`www.example.com`,
            // `accounts.example.com`) must still fill when the user later visits the bare apex
            // (`example.com`). SaveConfirmActivity stores the browser-reported host verbatim and
            // normalizedHost() does not strip "www.", so without this arm a login saved from a
            // `www.` page would need re-saving from the apex to ever autofill there again.
            //
            // It is deliberately NOT "the configured host is any suffix of the candidate" — an
            // unbounded version of this arm let a saved `bank.co.uk` credential match a visited
            // host reported as the bare public suffix `co.uk`: structurally identical to the
            // legitimate `www.example.com` / `example.com` pair (configured has exactly one extra
            // leading label), but `co.uk` is not a registrable domain the way `example.com` is —
            // LocalKeep ships no Public Suffix List and so cannot tell the two shapes apart from
            // the host alone. Restricting the extra label to [GENERIC_HOST_PREFIXES] closes that
            // gap without needing one: no real save produces `www.co.uk` or `accounts.co.uk`, so a
            // credential is only ever offered to the apex it was actually saved under a prefix of.
            //
            // Residual, so nobody re-derives it: this assumes no real save produces
            // `www.<public-suffix>` or `accounts.<public-suffix>` at all. True for `co.uk`; weaker
            // for a suffix like `github.io`, where a `www.` host can actually resolve. The
            // consequence stays bounded even there — a credential offered to the apex of a public
            // suffix, which is not normally a login surface — and it is strictly narrower than the
            // unbounded arm this replaced. A Public Suffix List would close it properly; see the
            // "no PSL" decision above.
            (configuredHost.endsWith(".$candidateHost") &&
                configuredHost.removeSuffix(".$candidateHost") in GENERIC_HOST_PREFIXES)
    }

    MatchStrategy.HOST ->
        normalizedHost(candidate)?.let { it == normalizedHost(rule.uri) } == true

    MatchStrategy.STARTS_WITH -> {
        val candidateHost = normalizedHost(candidate)
        val configuredHost = normalizedHost(rule.uri)
        candidateHost != null &&
            candidateHost == configuredHost &&
            hasUriPrefix(normalizedUri(candidate), normalizedUri(rule.uri))
    }

    MatchStrategy.EXACT -> normalizedUri(candidate) == normalizedUri(rule.uri)
    MatchStrategy.REGEX ->
        candidate.length <= MAX_MATCH_INPUT_LENGTH &&
            rule.uri.length <= MAX_REGEX_LENGTH &&
            runCatching { Regex(rule.uri).matches(candidate) }.getOrDefault(false)

    MatchStrategy.NEVER -> false
}

private fun normalizedHost(value: String): String? = runCatching {
    val trimmed = value.trim()
    val uri = URI(if (SCHEME_PATTERN.containsMatchIn(trimmed)) trimmed else "https://$trimmed")
    val host = uri.host?.trimEnd('.') ?: return null
    IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
}.getOrNull()

private fun normalizedUri(value: String): String {
    val trimmed = value.trim()
    return runCatching {
        val uri = URI(trimmed)
        val scheme = uri.scheme ?: return@runCatching trimmed.trimEnd('/')
            .lowercase(Locale.ROOT)
        val host = normalizedHost(trimmed) ?: return@runCatching trimmed.trimEnd('/')
        buildString {
            append(scheme.lowercase(Locale.ROOT))
            append("://")
            append(host)
            if (uri.port >= 0) append(':').append(uri.port)
            append(uri.rawPath.orEmpty().trimEnd('/'))
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }.getOrElse { trimmed.trimEnd('/') }
}

private fun hasUriPrefix(
    candidate: String,
    prefix: String,
): Boolean {
    if (!candidate.startsWith(prefix)) return false
    if (candidate.length == prefix.length || prefix.endsWith('/')) return true
    return candidate[prefix.length] in "/?#"
}

/**
 * Whether this item matches an already-normalised needle (trimmed, `Locale.ROOT`-lowercased,
 * diacritics folded — see [search], which is the only caller that normalises).
 *
 * Extracted so the predicate has exactly one implementation: what gets indexed is a security
 * decision (masked values are deliberately absent — see [searchableText]), and a test that
 * re-implemented the matching would keep passing while the real one drifted.
 */
internal fun VaultItem.matchesSearchQuery(needle: String, folderPath: List<String>): Boolean =
    searchHaystack(folderPath).any { value -> needle in value }

/**
 * Every searchable field of this item, lowercased and diacritic-folded once.
 *
 * Normalising here rather than inside [matchesSearchQuery] is what makes the result cacheable: the
 * item is immutable, so this list is too, and it does not depend on the query. See
 * [VaultRepository.searchIndex] for why that matters.
 *
 * [folderPath] is passed in rather than looked up because a [VaultItem] holds only a `folderId`;
 * the names live on the `Vault`. Deliberately **not** defaulted to null: the index and the inline
 * fallback have to agree on what matches, and a default is how the two would quietly drift apart
 * the first time someone added a call site.
 */
internal fun VaultItem.searchHaystack(folderPath: List<String>): List<String> =
    searchableText(folderPath).map(String::foldDiacritics).toList()

private fun VaultItem.searchableText(folderPath: List<String>): Sequence<String> = sequence {
    yield(title.lowercase(Locale.ROOT))
    tags.forEach { yield(it.lowercase(Locale.ROOT)) }
    // Indexed for the same reason tags are: it is a label the user chose and expects to be able to
    // search by. Nothing about it is secret — the name is already rendered on the item's row.
    // The whole ancestor chain, one entry each: an item in "Work > Bank" is in Work too.
    folderPath.forEach { yield(it.lowercase(Locale.ROOT)) }
    customFields.forEach {
        // The name is a label, not the secret, so it is always indexed — a hidden field the user
        // cannot find by name is a field they cannot use.
        yield(it.name.lowercase(Locale.ROOT))
        // The value is indexed only when the UI does not mask it. Same reasoning as the card PAN
        // below, and the same failure it already fixed: matching on a masked value makes it
        // findable by a field the UI hides everywhere else. It is worse here than for the PAN,
        // because `search()` matches on substring — a masked value is recoverable a character at a
        // time by probing, which walks straight past the re-auth prompt that reveal demands on a
        // reprompt-flagged item. KDBX imports are the common case: KeePassJava2 exposes no
        // per-property protection flag, so every non-standard string property lands HIDDEN.
        if (it.type != CustomFieldType.HIDDEN) yield(it.value.lowercase(Locale.ROOT))
    }
    when (val itemContent = content) {
        is Login -> {
            yield(itemContent.username.lowercase(Locale.ROOT))
            yield(itemContent.notes.lowercase(Locale.ROOT))
            itemContent.uris.forEach { yield(it.uri.lowercase(Locale.ROOT)) }
        }
        is Totp -> {
            yield(itemContent.issuer.lowercase(Locale.ROOT))
            yield(itemContent.account.lowercase(Locale.ROOT))
        }
        is Note -> yield(itemContent.body.lowercase(Locale.ROOT))
        is Card -> {
            yield(itemContent.holder.lowercase(Locale.ROOT))
            // The user's own notes, same as Login.notes and Note.body. It is a plain writable
            // field the editor surfaces and the search placeholder promises ("...notes..."), so
            // leaving it unindexed was a silent recall miss on the one card field a person writes
            // free text into. Not a masked value — CVV/PIN/PAN stay excluded below.
            yield(itemContent.notes.lowercase(Locale.ROOT))
            // Last four only. The whole PAN used to be indexed, which put a full card number in the
            // match set for any query that was a substring of it — and made the number findable by
            // a field the UI masks everywhere else. The last four is what a person actually
            // searches by ("the card ending 0366"), and it is not the secret: it is printed on
            // every receipt. CVV and PIN were already excluded and stay excluded.
            val digits = itemContent.number.filter(Char::isDigit)
            if (digits.length >= 4) yield(digits.takeLast(4))
        }
        is Identity -> {
            yield(itemContent.firstName.lowercase(Locale.ROOT))
            yield(itemContent.lastName.lowercase(Locale.ROOT))
            yield(itemContent.company.lowercase(Locale.ROOT))
            yield(itemContent.email.lowercase(Locale.ROOT))
            yield(itemContent.phone.lowercase(Locale.ROOT))
        }
        is File -> {
            yield(itemContent.filename.lowercase(Locale.ROOT))
            yield(itemContent.mime.lowercase(Locale.ROOT))
            // The user's own note, on the same terms as Login.notes and Card.notes above: prose
            // they typed, nothing masked, and the search box promises "notes". Added with the
            // field itself (schema 8) — a note the search cannot see would have been a smaller
            // copy of the defect that adding it fixed.
            yield(itemContent.notes.lowercase(Locale.ROOT))
        }
        is Passkey -> {
            yield(itemContent.rpId.lowercase(Locale.ROOT))
            yield(itemContent.userName.lowercase(Locale.ROOT))
            yield(itemContent.userDisplayName.lowercase(Locale.ROOT))
        }
        // Nothing. Deliberate, and the one branch here that yields no field at all.
        //
        // The title, tags and folder path are already indexed above, which is what makes a saved
        // location findable by the name the user gave it. Indexing the position would make it
        // findable by the position — and `search()` matches on substring, so a coordinate in the
        // index is recoverable a digit at a time by probing, which is a worse leak than rendering
        // it would be: it walks straight past the reveal gate on the detail screen. Same reasoning
        // as the card PAN above, with a stronger conclusion because there is no "last four" here
        // that a person would plausibly search by.
        //
        // capturedAt and accuracy are excluded for the same reason: an exact capture time is a
        // statement about where someone was at a moment, which is the thing being protected.
        is Coordinate -> Unit
        // Nothing — the words and the passphrase ARE the secret, and `search()` matches on
        // substring, so a single indexed word would let the phrase be recovered a word at a time by
        // probing, straight past the reveal gate. The title ("what it opens"), tags and folder path
        // are indexed above, which is how a saved phrase stays findable by the name the user gave
        // it. `SearchExcludesHiddenFieldsTest` pins that searching a word never surfaces its item.
        is SeedPhrase -> Unit
    }
}

private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
private const val MAX_REGEX_LENGTH = 1_024
private const val MAX_MATCH_INPUT_LENGTH = 4_096

/**
 * Strips combining marks so "cafe" finds "Café".
 *
 * Lowercasing alone left the two strings different, so an item the user had saved with the accents
 * their keyboard produces was unreachable by the ASCII spelling most people actually type — and the
 * result is indistinguishable from the item not being there.
 *
 * NFD splits each accented character into a base letter plus its combining mark; dropping the marks
 * leaves the base. Only applied to the search path: what is stored and displayed is untouched.
 *
 * Public rather than internal because every search box in the app has to agree about this, and one
 * that did not was found on 2026-08-30: the health dashboard's category drill-down matched
 * with a bare `contains(ignoreCase = true)`, so a "Café Wifi" imported from a KDBX was unreachable
 * by "cafe" in the one place a user goes *because* something is already wrong with it.
 */
fun String.foldDiacritics(): String =
    if (all { it.code < 0x80 }) {
        // The overwhelmingly common case, and normalising it would allocate two strings to arrive
        // back at this one.
        this
    } else {
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    }

package com.localkeep.feature.vaultlist

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.common.sortedByDisplayName
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.live
import com.localkeep.core.vault.DuplicateAllowance
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultVisibilitySnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One folder as the management screen shows it.
 *
 * [depth] drives the indent, [itemCount] counts the folder's **whole subtree** — the same number the
 * vault list's folder rows show. A parent reading "0 items" while holding a child with forty sends
 * the user looking for data that is one tap away.
 */
data class FolderRow(val folder: Folder, val itemCount: Int, val depth: Int = 0) {
    /** The path from the root down to this folder, which is what opening it in the list needs. */
    var path: List<UUID> = listOf(folder.id)
        internal set
}

data class FolderUiState(
    val folders: List<FolderRow> = emptyList(),
    val newName: String = "",
    /**
     * True while the new-folder card is open.
     *
     * The field used to be permanently on screen above the list. Most visits here are about a folder
     * that already exists — rename, move, delete — and every one of them opened on a prompt to make
     * another one.
     */
    val showNewFolder: Boolean = false,
    /** Which folder a newly created one goes under. Null is the root. */
    val newParentId: UUID? = null,
    /** Non-null while a folder's name is being edited in place. */
    val renamingId: UUID? = null,
    val renameText: String = "",
    /** Non-null while the delete confirmation is up for that folder. */
    val pendingDeletion: UUID? = null,
    /** Non-null while the "Move to…" picker is open for that folder. */
    val movingId: UUID? = null,
    /** True when the stored tree, or an item's folder id, was found broken at unlock. */
    val needsRepair: Boolean = false,
    /** True while the repair confirmation is up. */
    val pendingRepair: Boolean = false,
    /**
     * The folder just created from [NewFolderField], while its row still shows only Delete.
     *
     * A folder made a second ago is the one most likely to have been a slip — a wrong name, a wrong
     * place — so its row offers the undo (delete) and withholds rename/move until the user does
     * anything else. The full set is one long-press away the whole time; this only quiets the row's
     * inline controls at the single moment they are most likely to be mis-tapped.
     */
    val justCreatedId: UUID? = null,
    @param:StringRes val error: Int? = null,
) {
    /**
     * [error], when the new-folder card is what raised it — so the card can render it itself.
     *
     * 2C-F2: with 18 folders on screen the card sits about six screens down, and "A folder with that
     * name already exists." was drawn by the screen's own inline error at the very top of the list.
     * The Create button the user had just pressed therefore read as dead: nothing within six screens
     * of it changed. The card is the only thing open that can raise an error while [showNewFolder]
     * is true and no rename is in flight — `createFolder` raises exactly two, the sibling clash and
     * the depth limit — so that is the whole test.
     */
    @get:StringRes
    val composerError: Int? get() = error?.takeIf { showNewFolder && renamingId == null }

    /** [error] when it is *not* the card's, which is the only kind the screen renders at the top. */
    @get:StringRes
    val listError: Int? get() = if (composerError == null) error else null

    val pendingDeletionRow: FolderRow? get() = folders.firstOrNull { it.folder.id == pendingDeletion }

    val movingRow: FolderRow? get() = folders.firstOrNull { it.folder.id == movingId }

    /**
     * How many folders the pending delete would move to the trash, the folder itself included.
     *
     * The subtree, not the direct children: deleting a folder takes everything under it now, so a
     * dialog counting one level would understate what it is about to move. Counted off [path],
     * which every row already carries, so this cannot disagree with what the rows show.
     */
    val pendingFolderCount: Int
        get() = pendingDeletion?.let { id -> folders.count { id in it.path } } ?: 0

    /** How many items go with it — [FolderRow.itemCount] is already the whole subtree's. */
    val pendingItemCount: Int
        get() = pendingDeletionRow?.itemCount ?: 0
}

/**
 * Publishes a fresh projection, dropping any half-finished interaction whose folder is no longer in it.
 *
 * Rows can now change without this screen having asked — a reveal session expiring conceals folders
 * that were listed a moment ago. Copying only [FolderUiState.folders] would leave the rename field
 * open over a row that is gone, where [FolderViewModel.commitRename] returns silently and the user's
 * typing goes nowhere with nothing on screen saying so; the "Move to…" picker and the delete
 * confirmation would likewise stay up naming a folder the screen may no longer admit exists.
 *
 * Applied on every projection, not only the collected ones, so there is one rule rather than two.
 */
internal fun FolderUiState.withRows(rows: List<FolderRow>, needsRepair: Boolean): FolderUiState {
    val present = rows.mapTo(HashSet()) { it.folder.id }
    val stillRenaming = renamingId?.takeIf { it in present }
    return copy(
        folders = rows,
        needsRepair = needsRepair,
        renamingId = stillRenaming,
        renameText = if (stillRenaming == null) "" else renameText,
        movingId = movingId?.takeIf { it in present },
        pendingDeletion = pendingDeletion?.takeIf { it in present },
        justCreatedId = justCreatedId?.takeIf { it in present },
    )
}

/**
 * What the screen looks like once a folder has been made: the name is spent, and **the card is still
 * open under the same parent**.
 *
 * It used to close — `showNewFolder = false`, parent forgotten — so a second folder meant tapping
 * New folder, re-choosing the parent and typing again. Reported 2026-10-12: "What if I want to
 * create 5 or 10 folders or 20? Do I have to click every time?" Leaving is the user's own act
 * (Cancel, or Back), never a side effect of creating.
 */
internal fun FolderUiState.afterFolderCreated(createdId: UUID?): FolderUiState =
    copy(newName = "", justCreatedId = createdId)

/**
 * Folders as something the user can manage, rather than something that only exists mid-edit.
 *
 * Every mutation here is one of [VaultRepository]'s folder operations, which is what makes the
 * hierarchy's rules the same wherever they are reached from: this screen, the editor's picker, an
 * import. The screen contributes the two things the repository deliberately does not hold — the
 * translated suffix a promoted folder may need, and the decision to show a repair at all.
 *
 * Name collisions are refused **among siblings**, not globally. "Bank" inside Work and "Bank"
 * inside Personal are two different places; forbidding the second would be arbitrary. Same-named
 * siblings can still arrive at rest from a merge, and the rows disambiguate those rather than
 * renaming the user's data.
 */
@HiltViewModel
class FolderViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val lockManager: VaultLockManager,
    private val entitlement: Entitlement,
) : ViewModel() {

    private val _state = MutableStateFlow(FolderUiState())
    val state: StateFlow<FolderUiState> = _state.asStateFlow()

    init {
        // Synchronously, and not merely as the collector's first emission: `viewModelScope`
        // dispatches on `Main.immediate`, so on any thread that is not the main one — an
        // instrumentation test, say — a launched first load would leave the first read of [state]
        // with no rows at all.
        load()
        viewModelScope.launch {
            // The same shared projection the vault list and trash collect, for the reason this
            // screen was the last one missing it: a reveal session expiring, a conceal, a Stop
            // hiding or an exact lease boundary all change what may be shown here, and none of
            // them is a write this screen performed. Without this, opening Folders inside a reveal
            // window listed the concealed folders by name with their subtree counts and went on
            // listing them after the window closed — offering Rename, Move to, Duplicate and
            // Delete on records `VaultVisibility` had already put back out of reach everywhere
            // else. Its contract is that a hidden record is absent from all user-facing reads or
            // none; the read here was simply stale.
            //
            // Guarded on Unlocked, as both neighbours are: the flow's initial value is Locked, and
            // clearing on it would race an unlock in progress. `collectLatest`, so a burst of
            // saves supersedes rather than queues.
            repository.presentationState.collectLatest { presentation ->
                if (presentation is VaultPresentationState.Unlocked) {
                    // Off the main dispatcher. This flow also emits on every save
                    // (`VaultLockManager.onSaved`), and [load] is a whole-tree walk plus
                    // `checkFolderTreeHealth()` — on `Main.immediate` that is a dropped frame per
                    // commit for as long as this screen is open, an import included.
                    withContext(Dispatchers.Default) { load(presentation.snapshot) }
                }
            }
        }
    }

    fun onNewNameChanged(value: String) = _state.update { it.copy(newName = value, error = null) }

    fun showNewFolderInput() = _state.update { it.copy(showNewFolder = true, error = null) }

    /**
     * Closes the card and forgets what was typed.
     *
     * Clearing the name matters: [createFolder] reads it, so a name abandoned here and left behind
     * would be waiting the next time the card opened, under whatever parent was current then.
     */
    fun hideNewFolderInput() =
        _state.update { it.copy(showNewFolder = false, newName = "", newParentId = null, error = null) }

    /** Which folder the next "New folder" lands in. The row's own menu sets this before opening it. */
    fun setNewParent(parentId: UUID?) = _state.update { it.copy(newParentId = parentId, error = null) }

    fun createFolder() {
        val name = _state.value.newName.trim()
        if (name.isEmpty()) return
        val parentId = _state.value.newParentId
        if (nameTakenAmongSiblings(name, parentId)) {
            _state.update { it.copy(error = R.string.vaultlist_folders_error_name_exists) }
            return
        }
        // Named, like the sibling clash above. The repository refuses a ninth level with a bare
        // `check`, which `mutate` renders as "That didn't work" — true, and no help.
        val parentRow = parentId?.let { id -> _state.value.folders.firstOrNull { it.folder.id == id } }
        if (parentRow != null && parentRow.depth + 1 >= FolderTree.MAX_DEPTH) {
            _state.update { it.copy(error = R.string.vaultlist_folders_error_too_deep) }
            return
        }
        var createdId: UUID? = null
        mutate(after = { _state.update { it.afterFolderCreated(createdId) } }) {
            createdId = repository.createFolder(name, parentId).id
        }
    }

    fun startRenaming(id: UUID) {
        val row = _state.value.folders.firstOrNull { it.folder.id == id } ?: return
        _state.update {
            it.copy(renamingId = id, renameText = row.folder.name, justCreatedId = null, error = null)
        }
    }

    fun onRenameTextChanged(value: String) = _state.update { it.copy(renameText = value, error = null) }

    fun cancelRenaming() = _state.update { it.copy(renamingId = null, renameText = "", error = null) }

    fun commitRename() {
        val id = _state.value.renamingId ?: return
        val name = _state.value.renameText.trim()
        val current = _state.value.folders.firstOrNull { it.folder.id == id } ?: return
        if (name.isEmpty() || name == current.folder.name) {
            cancelRenaming()
            return
        }
        if (nameTakenAmongSiblings(name, current.folder.parentId, except = id)) {
            _state.update { it.copy(error = R.string.vaultlist_folders_error_name_exists) }
            return
        }
        mutate(after = ::cancelRenaming) { repository.renameFolder(id, name) }
    }

    fun startMoving(id: UUID) = _state.update { it.copy(movingId = id, justCreatedId = null, error = null) }

    fun cancelMoving() = _state.update { it.copy(movingId = null) }

    /**
     * Whether [target] is a legal destination for the folder being moved — self and subtree are not,
     * and neither is a parent that would push the moved subtree past [FolderTree.MAX_DEPTH].
     *
     * Both halves, because the write enforces both: `VaultRepository.moveFolder` guards the depth
     * with a `check(...)`, and the vault list's own drag-and-drop already rejects an over-depth
     * target before the drop. This picker checked only the descendant half, so it offered
     * destinations the write would refuse — and the refusal surfaces as the generic "That didn't
     * work. Nothing was changed.", which names neither depth nor the destination. A control that
     * looks available and is not is the defect; the guard downstream was never the problem.
     */
    fun canMoveInto(target: UUID?): Boolean {
        val id = _state.value.movingId ?: return false
        val folders = _state.value.folders.map { it.folder }
        if (FolderTree.wouldExceedDepth(folders, id, target)) return false
        if (target == null) return true
        return !FolderTree.wouldBeDescendant(folders, id, target)
    }

    fun commitMove(newParentId: UUID?) {
        val id = _state.value.movingId ?: return
        // Closed on a refusal too. The dialog covers the list and the error saying why renders above
        // the list, so a dialog left open read as a tap that did nothing (QA 2026-10-03).
        mutate(after = ::cancelMoving, failed = ::cancelMoving) { repository.moveFolder(id, newParentId) }
    }

    fun askDelete(id: UUID) = _state.update { it.copy(pendingDeletion = id, error = null) }

    fun cancelDelete() = _state.update { it.copy(pendingDeletion = null) }

    /**
     * Moves the folder and everything inside it to the trash.
     *
     * It used to unfile the folder's items and promote its child folders one level up, and the
     * reason was that folders had no trash: a child that was not promoted would have been gone for
     * good along with its parent. They have one now, so the contents travel with the folder and
     * come back with it, and the promotion — which scattered a user's folders into the parent as a
     * side effect of deleting one — is no longer the safe option but the surprising one.
     *
     * `disambiguate` and `onRenamed` went with it. Both existed only to name a promoted folder out
     * of a clash with its new siblings, and nothing is promoted any more.
     */
    fun confirmDelete() {
        val id = _state.value.pendingDeletion ?: return
        mutate(after = { _state.update { it.copy(pendingDeletion = null) } }) {
            repository.deleteFolder(id)
        }
    }

    /**
     * Deep-copies [id]'s whole subtree (and its items) into a sibling folder.
     *
     * @param disambiguate resolved by the composable from `vaultlist_folder_copy_suffix` /
     *   `vaultlist_folder_copy_suffix_n` — same closure contract as [confirmDelete]'s.
     */
    fun duplicate(id: UUID, disambiguate: (String, Int) -> String) {
        _state.update { it.copy(justCreatedId = null) }
        mutate {
            // A duplicate is creation, and was the one creation path no free allowance reached
            // (2026-10-02): every File, Card and Identity under the folder was copied unasked. Asked
            // about exactly what the repository will copy, off Main — two whole-vault counts. A
            // read that throws is "not blocked", as in ItemEditViewModel: the copy below then
            // fails on the same locked vault, with the generic error.
            val blocked = withContext(Dispatchers.Default) {
                runCatching {
                    DuplicateAllowance.exceeded(
                        repository,
                        repository.itemsCopiedByDuplicate(id),
                        isPro = entitlement.isPro.value,
                    )
                }.getOrDefault(false)
            }
            if (blocked) {
                _state.update { it.copy(error = R.string.vaultlist_folders_error_duplicate_allowance) }
            } else {
                repository.duplicateFolder(id, disambiguate)
            }
        }
    }

    fun askRepair() = _state.update { it.copy(pendingRepair = true) }

    fun cancelRepair() = _state.update { it.copy(pendingRepair = false) }

    /** Writes the normalized tree and unfiles items whose folder is gone. See [FolderTree.normalize]. */
    fun confirmRepair() {
        mutate(after = { _state.update { it.copy(pendingRepair = false) } }) {
            repository.repairFolderTree()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Reads the projected rows, so a concealed sibling does not block a name.
     *
     * Refusing "Bank" because a hidden "Bank" sits beside it would answer a question the user never
     * asked and Make-Invisible exists to refuse — a rename dialog that says no is an oracle. Two
     * same-named siblings are already something this screen tolerates and disambiguates rather than
     * renames, per the note above, so allowing one more costs nothing that was being protected.
     */
    private fun nameTakenAmongSiblings(name: String, parentId: UUID?, except: UUID? = null) =
        _state.value.folders.any {
            it.folder.id != except &&
                it.folder.parentId == parentId &&
                it.folder.name.equals(name, ignoreCase = true)
        }

    /**
     * Reads the folder tree off the unlocked vault, depth-first and name-sorted within each level,
     * so the rows arrive in the order the indent implies.
     *
     * Counting here rather than in the composable because a count is a fact about the vault, not
     * about a screen — and because a locked vault has to read as no folders rather than as a crash.
     */
    private fun load(projected: VaultVisibilitySnapshot? = null) {
        // Through the projection, not off `lockState.value.vault`. Trashed folders leave the app
        // here — this screen is where folders are created, renamed, moved and deleted, so a trashed
        // one appearing in it would offer four operations on something the user believes they threw
        // away, and `moveFolder` onto a trashed parent would put a live folder somewhere invisible.
        // Concealed ones leave for a stronger reason: filtering on `deletedAt` alone listed a hidden
        // folder by name, counted its items into every ancestor, and offered it in the "Move to…"
        // picker, which is the whole of what Make-Invisible promises not to do. `liveFolders` and
        // `liveItems` drop both, and they honour an active reveal session, which a hand-rolled
        // `hiddenUntil` filter here would not. See VaultVisibility: a hidden record is absent from
        // all user-facing reads or none.
        // [projected] is the snapshot the caller already holds: `VaultPresentationState.Unlocked`
        // carries the one `VaultVisibility.project` has just produced, and re-deriving it would
        // walk the whole vault a second time for the same answer.
        val snapshot = projected ?: runCatching { repository.visibilitySnapshot() }.getOrNull()
        val folders = snapshot?.liveFolders.orEmpty()
        // Counted from the same projection the rows come from, deliberately. `trashFolder` does move
        // a concealed descendant, so this dialog can understate what leaves — but counting the
        // concealed ones would put "3 items" under a folder showing 2, which tells a shoulder-surfer
        // something is hidden there. The count yields; concealment does not. Whether deleting a
        // folder should take its hidden contents at all is a question about the repository's
        // semantics rather than about this screen, and it is a tracked follow-up.
        val items = snapshot?.liveItems.orEmpty()
        val childrenOf = folders.groupBy { it.parentId }
        val rows = mutableListOf<FolderRow>()
        // Once, before the walk, rather than a descendant-set lookup per folder crossed with every
        // item inside it — see [FolderSubtreeCounts], which the vault list moved to for the same
        // reason. This screen runs `load` after every create, rename, move, delete, duplicate and
        // repair, so the old `O(folders × items)` pass sat on the main dispatcher between the tap
        // and the redraw. The counts are identical, cycles and dangling parents included; the
        // helper's own test asserts that case by case against the scan this replaces.
        val counts = FolderSubtreeCounts.of(folders, items)

        fun walk(parentId: UUID?, depth: Int, path: List<UUID>) {
            // Bounded by the tree's own limit rather than by trust: a cyclic list on disk would
            // otherwise recurse until the stack ran out, on the very screen that offers the repair.
            // Bounds the recursion only — a folder past the limit still contributes its items to
            // every ancestor's count, exactly as the unbounded descendant scan did.
            if (depth > FolderTree.MAX_DEPTH) return
            childrenOf[parentId].orEmpty()
                .sortedByDisplayName { it.name }
                .forEach { folder ->
                    rows += FolderRow(folder, counts[folder.id] ?: 0, depth)
                        .also { it.path = path + folder.id }
                    walk(folder.id, depth + 1, path + folder.id)
                }
        }
        walk(null, 0, emptyList())

        val health = runCatching { repository.checkFolderTreeHealth() }.getOrNull()
        _state.update { it.withRows(rows, needsRepair = health?.needsRepair == true) }
    }

    /**
     * Runs a vault mutation, reloads, and clears the screen state that the mutation resolved.
     *
     * [after] runs only on success. A failed write that still closed the rename field would leave
     * the user looking at the old name with no indication their edit had gone nowhere.
     */
    private fun mutate(after: () -> Unit = {}, failed: () -> Unit = {}, block: suspend () -> Unit) {
        // A second tap while the first write is still in flight is refused, matching
        // `VaultListViewModel.bulkEdit`, `ItemEditViewModel.save` and `ImportWizardViewModel
        // .applyImport`. The harm those three name is this one: every commit fires `onSaved`, which
        // takes another rolling snapshot, and the ring holds five — so a duplicate of the state
        // just written evicts a genuinely older generation. Folders make it worse than most,
        // because `duplicate` deep-copies a whole subtree and the row menu leaves the action under
        // the finger long enough to hit twice.
        if (working) return
        working = true
        viewModelScope.launch {
            try {
                runCatching { block() }
                    .onSuccess {
                        load()
                        after()
                    }
                    .onFailure { failure ->
                        _state.update { it.copy(error = folderWriteError(failure, R.string.vaultlist_folders_error_action)) }
                        failed()
                    }
            } finally {
                // In a `finally` rather than after the `runCatching`: a cancelled scope (the screen
                // left mid-write) would otherwise leave the flag latched, and this ViewModel would
                // refuse every action for the rest of its life.
                working = false
            }
        }
    }

    /** Read and written only from the main dispatcher, which is where every folder action starts. */
    private var working = false
}

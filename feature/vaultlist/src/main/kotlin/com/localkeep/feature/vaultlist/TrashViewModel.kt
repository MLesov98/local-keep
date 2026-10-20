package com.localkeep.feature.vaultlist

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.common.sortedByDisplayName
import com.localkeep.core.model.Folder
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.RevealAuth
import com.localkeep.core.vault.PurgeOutcome
import com.localkeep.core.vault.PurgeScope
import com.localkeep.core.vault.TrashedFolder
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.SessionKey
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VisibleTrashPurgePlan
import com.localkeep.feature.files.FileBlobStore
import com.localkeep.feature.files.R as FilesR
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One trash scan's result, carried out of the IO block as a named record rather than a `Triple`.
 *
 * The fourth member is the reason it is named: three anonymous `Int`s at a destructuring site is
 * exactly how [purgeableItemCount] would get wired to [totalCount] by a future edit, which is the
 * bug this whole change exists to close.
 */
private data class TrashScan(
    val shown: List<VaultItem>,
    val folders: List<TrashedFolder>,
    val folderTree: List<Folder>,
    val total: Int,
    val purgeable: Int,
)

data class TrashUiState(
    /** Already narrowed by [searchQuery] — the screen renders this list as-is. */
    val items: List<VaultItem> = emptyList(),
    /**
     * Trashed folders, one row per trash root, above the items.
     *
     * Not search-filtered. The search matches item fields, and a folder row stands for a subtree
     * whose items are deliberately absent from [items] — narrowing it by a query aimed at item text
     * would hide folders for failing a test they were never given.
     */
    val folders: List<TrashedFolder> = emptyList(),
    /**
     * Every folder a trashed item's `folderId` could name — live ones and trashed ones together —
     * so a row can say where the item came from (2C-F8).
     *
     * Both halves are needed: an item trashed on its own still points at a live folder, and one
     * trashed along with its folder points at a trashed one. Resolving against either alone leaves
     * the other half of the rows silent, which is worse than silence everywhere because it reads as
     * "this one had no folder".
     */
    val folderTree: List<Folder> = emptyList(),
    val searchQuery: String = "",
    /** How many items are in the trash in total, regardless of the search. */
    val totalCount: Int = 0,
    /**
     * What an Empty Trash would actually destroy, straight from
     * [com.localkeep.core.vault.VaultVisibilitySnapshot.purgeableTrashItemCount].
     *
     * Not derivable from [totalCount] and [folders]: a folder trashed inside an already-trashed
     * parent gets no row of its own, so its items are represented nowhere on this screen while the
     * purge still takes them. See the field's own doc for the sequence that made the confirmation
     * say zero over five.
     */
    val purgeableItemCount: Int = 0,
    @param:StringRes val error: Int? = null,
    /** True only when [error] came from a failed *read*, which is the case with nothing else to press. */
    val loadFailed: Boolean = false,
    /**
     * A restore or delete is mid-write.
     *
     * Every action here re-encrypts and rewrites the whole vault, which is long enough on a large
     * one for the row to sit there looking untouched — so the buttons disable while it runs rather
     * than accepting a second tap nobody meant to make.
     */
    val isWorking: Boolean = false,
    /**
     * The exact records the open purge confirmation stands for, captured when it opened.
     *
     * Held here rather than re-derived on confirm, which is the only arrangement that makes the
     * staleness check mean anything: a plan built at the moment of the tap always matches the vault
     * at the moment of the tap. See [VisibleTrashPurgePlan].
     */
    val pendingPurge: VisibleTrashPurgePlan? = null,
    /**
     * The attachment a reveal challenge is currently running for, or null while none is.
     *
     * Held here rather than in a `rememberSaveable`, mirroring `AttachmentPickerScreen`: a rotation
     * must not drop a challenge that is already on screen, and process death must not bring the app
     * back up raising a biometric prompt nobody asked for.
     */
    val pendingRevealAttachmentId: String? = null,
    /** True while the screen should raise the challenge for [pendingRevealAttachmentId]. */
    val pendingPreviewReveal: Boolean = false,
    /**
     * Which attachment the preview's secure viewer is open on.
     *
     * Written only by a passed challenge — see [TrashViewModel.completeReveal]. Nothing else in this
     * class or the screen sets it, which is what makes "the viewer is open" and "a reveal passed"
     * the same fact rather than two that can drift apart.
     */
    val viewingAttachmentId: String? = null,
    /**
     * Why the last challenge refused, or null.
     *
     * A gate that refuses in silence is its own defect: the row is tappable, the tap does nothing,
     * and nothing says why. Measured on a lock-less device for the sibling gate in
     * `AttachmentPickerScreen`, and the same failure is reachable here.
     */
    @param:StringRes val previewGateError: Int? = null,
)

/**
 * The mutating actions below take their [LkNotices] confirmation text as a plain-String parameter
 * rather than holding it: `LkNotices.post` has no `@StringRes` overload (core/ui), so the caller —
 * a composable — resolves it via `stringResource` and passes the value in.
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val lockManager: VaultLockManager,
    /** Exposed for the read-only preview's media viewer, mirroring AttachmentPickerViewModel. */
    val fileBlobStore: FileBlobStore,
) : ViewModel() {
    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            // The same shared projection flow the vault list collects, for the same reason: a lease
            // expiring, a reveal, a conceal or a Stop hiding all change what this screen may show,
            // and none of them is a save this screen performed. Guarded on Unlocked so the initial
            // Locked value cannot pin the read-error banner over an unlock in progress.
            repository.presentationState.collect { presentation ->
                if (presentation is VaultPresentationState.Unlocked) refresh()
            }
        }
    }

    fun onSearchChanged(query: String) {
        _state.update { it.copy(searchQuery = query) }
        // Debounced at the vault list's interval, and for the defect the vault list already fixed:
        // [refresh] runs `visibilitySnapshot()` and `searchTrash()` synchronously on the main
        // dispatcher, so a keystroke was a complete pass over the vault and ten characters were
        // ten of them — to show the result of the tenth.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            refresh()
        }
    }

    /** The debounce timer for [onSearchChanged]; cancelled and restarted on each keystroke. */
    private var searchJob: Job? = null

    /** The in-flight [refresh] scan; a newer refresh cancels it so a superseded scan cannot publish. */
    private var refreshJob: Job? = null

    /**
     * The unlock epoch a passed reveal was granted against, or null while no challenge has passed.
     *
     * The token, not the [SessionKey] itself: `sessionKeyOrNull(token)` re-checks the epoch on every
     * read, so a lock — or a relock followed by a fresh unlock, which is a different session and, as
     * far as this gate is concerned, possibly a different person — drops the viewer on its own. That
     * is the fail-closed behaviour the untokened read gave for free, kept rather than traded away
     * for a cached handle.
     */
    private var previewRevealToken: Long? = null

    /**
     * The session key the preview's media viewer decrypts with.
     *
     * Null until a [RevealAuth] challenge has passed, and null again the moment that session ends.
     * This used to be an untokened `sessionKeyOrNull()` called straight from the composable, so
     * Trash opened a File item's payload with no challenge anywhere while the live route to the same
     * bytes always raises one. Trash is not a weaker copy of the viewer: anyone holding the unlocked
     * phone can trash an item, look at it here and restore it, so the cheaper route is raised to the
     * stricter one rather than the other way round — the rule `FileItemEditScreen`'s viewer gate
     * already states.
     */
    fun previewSessionKeyOrNull(): SessionKey? =
        previewRevealToken?.let { lockManager.sessionKeyOrNull(it) }

    /** Asks the screen to challenge before [attachmentId] opens. Never opens it directly. */
    fun requestPreviewReveal(attachmentId: String) {
        _state.update {
            it.copy(
                pendingRevealAttachmentId = attachmentId,
                pendingPreviewReveal = true,
                previewGateError = null,
            )
        }
    }

    /**
     * The token captured immediately before the system prompt is shown, mirroring
     * [com.localkeep.feature.files.FileItemViewerViewModel]. Taken first so the key released
     * afterwards can be proved to belong to the session the user was challenged in.
     */
    fun beginReveal(): Long? = lockManager.unlockedSessionToken()

    /** Releases the viewer only on [RevealAuth.RevealOutcome.Success] against a still-live session. */
    fun completeReveal(token: Long?, outcome: RevealAuth.RevealOutcome) {
        val target = _state.value.pendingRevealAttachmentId
        val granted = outcome is RevealAuth.RevealOutcome.Success &&
            token != null &&
            target != null &&
            lockManager.sessionKeyOrNull(token) != null
        if (granted) {
            previewRevealToken = token
            _state.update {
                it.copy(
                    viewingAttachmentId = target,
                    pendingRevealAttachmentId = null,
                    pendingPreviewReveal = false,
                    previewGateError = null,
                )
            }
            return
        }
        previewRevealToken = null
        _state.update {
            it.copy(
                viewingAttachmentId = null,
                pendingRevealAttachmentId = null,
                pendingPreviewReveal = false,
                previewGateError = revealGateMessage(outcome),
            )
        }
    }

    /** Closes the viewer and drops the reveal with it, so opening it again is challenged again. */
    fun closePreviewViewer() {
        previewRevealToken = null
        _state.update {
            it.copy(
                viewingAttachmentId = null,
                pendingRevealAttachmentId = null,
                pendingPreviewReveal = false,
            )
        }
    }

    /** Clears the refusal message once the surface showing it has gone. */
    fun clearPreviewGateError() {
        _state.update { it.copy(previewGateError = null) }
    }

    /**
     * @param confirmation used when the item landed back in the folder it was trashed from (or was
     *   already unfiled and still is) — resolved by the caller via `stringResource`, see the class
     *   kdoc.
     * @param confirmationAt template taking the destination folder's name, for when it did not:
     *   [VaultRepository.restoreFromTrash] climbs to the nearest live ancestor when the original
     *   folder is itself still in the trash, same as [restoreFolder].
     * @param confirmationAtRoot used when that climb found no live ancestor and the item fell
     *   through to the vault root.
     * @param undoLabel the notice's action label; the undo puts the item straight back in the trash.
     * @param undoneNotice announced once it has.
     *
     * Restore had no undo, which is the wrong way round for this screen: every *other* action here
     * is guarded by a confirmation dialog precisely because it cannot be taken back, and the one
     * action that can be taken back trivially — `moveToTrash` on the id just restored — offered
     * nothing. Reported 2026-09-02: *"I just restored an entry but there's no option to undo"*.
     *
     * The notice carries an action, so [LkNotices] gives it the longer `ACTION_MS` window on its
     * own — an undo the user cannot read in time is not an undo. The same shape `VaultListViewModel`
     * already uses for the reverse direction ("Moved to trash · Undo").
     *
     * Reported again 2026-09-09: the confirmation named neither outcome, so a restore that fell
     * through to the root — because the original folder is itself gone, which a recent fix makes a
     * real and recurring case, not a corner one — left the user looking for the item in a folder it
     * was never filed back into. [VaultRepository.restoreFromTrash] reports nothing about where it
     * put the item (unlike [restoreFolder]'s [RestoreFolderOutcome]), so the destination is read
     * back the only way available here: the item's `folderId` immediately before the trash write is
     * compared against its `folderId` immediately after. See [restoreItemNotice].
     */
    fun restore(
        id: UUID,
        confirmation: String,
        confirmationAt: (String) -> String,
        confirmationAtRoot: String,
        undoLabel: String,
        undoneNotice: String,
    ) {
        val originalFolderId = _state.value.items.find { it.id == id }?.folderId
        mutateReporting {
            repository.restoreFromTrash(id)
            val destinationFolderId = repository.getAllItems().find { it.id == id }?.folderId
            val text = restoreItemNotice(
                originalFolderId = originalFolderId,
                destinationFolderId = destinationFolderId,
                confirmation = confirmation,
                confirmationAt = confirmationAt,
                confirmationAtRoot = confirmationAtRoot,
                folderName = ::folderName,
            )
            LkNotices.post(text, actionLabel = undoLabel) { undoRestore(id, undoneNotice) }
            // Already posted, with its action attached. Returning the same text through
            // [mutateReporting]'s own post would immediately replace the notice that carries the
            // undo with an identical-looking one that does not, which is a worse failure than
            // having no undo at all: the offer appears and vanishes within a frame.
            null
        }
    }

    /** Puts a just-restored item back in the trash. Announced, so the undo is visibly an act too. */
    private fun undoRestore(id: UUID, undoneNotice: String) {
        mutate(undoneNotice) { repository.moveToTrash(id) }
    }

    fun deleteForever(id: UUID, confirmation: String) {
        mutate(confirmation) { repository.deleteItem(id) }
    }

    /**
     * Restores a trashed folder and everything that went down with it.
     *
     * The confirmation is chosen *after* the write, from what the restore actually did: when the
     * original parent is still in the trash the folder comes back somewhere else, and a notice
     * saying only "Folder restored" would send the user looking where it is not.
     *
     * @param confirmation used when the folder went back where it was.
     * @param confirmationAt template taking the destination folder's name, for the fallback.
     * @param confirmationAtRoot used when the fallback reached the top level, which has no name.
     */
    fun restoreFolder(
        id: UUID,
        confirmation: String,
        confirmationAt: (String) -> String,
        confirmationAtRoot: String,
    ) {
        mutateReporting {
            val outcome = repository.restoreFolder(id)
            val destination = outcome.reparentedTo
            when {
                !outcome.reparented -> confirmation
                destination == null -> confirmationAtRoot
                // If the destination cannot be named, say "top level" rather than nothing: a notice
                // that names no place is worse than one naming the wrong grain of place.
                else -> folderName(destination)?.let(confirmationAt) ?: confirmationAtRoot
            }
        }
    }

    /** The restored folder's new parent, for the notice. Null when it cannot be read. */
    private fun folderName(id: UUID): String? =
        runCatching { repository.folderPathOf(id)?.lastOrNull()?.name }.getOrNull()

    /**
     * Captures what a purge confirmation is about to stand for. Called as the dialog opens.
     *
     * A failure here leaves [TrashUiState.pendingPurge] null, and [confirmPurge] refuses on null, so
     * a vault that locked between the tap and the read cannot produce a purge with no plan behind it.
     */
    fun preparePurge(scope: PurgeScope) {
        val plan = runCatching { repository.planTrashPurge(scope) }.getOrNull()
        _state.update { it.copy(pendingPurge = plan) }
    }

    fun cancelPurge() {
        _state.update { it.copy(pendingPurge = null) }
    }

    /**
     * Destroys exactly the plan captured by [preparePurge], or nothing.
     *
     * @param confirmation announced when the purge ran.
     * @param staleNotice announced when it did not, because the visible trash changed underneath the
     *   dialog. Nothing was deleted and the user has to look and decide again — which is the point,
     *   not a retry the app can perform on their behalf.
     */
    fun confirmPurge(confirmation: String, staleNotice: String) {
        val plan = _state.value.pendingPurge ?: return
        _state.update { it.copy(pendingPurge = null) }
        mutateReporting {
            when (repository.purgeVisibleTrash(plan)) {
                is PurgeOutcome.Purged -> confirmation
                PurgeOutcome.Stale -> staleNotice
            }
        }
    }

    /**
     * @param confirmation announced once the change is on disk. Success used to be the one outcome
     *   with no channel here — the row simply vanished and the user inferred the rest — which is a
     *   poor way to report the only two irreversible actions in the app.
     */
    private fun mutate(confirmation: String, block: suspend () -> Unit) =
        mutateReporting { block(); confirmation }

    /**
     * [mutate] for the one action whose confirmation is not knowable until it has run.
     *
     * The block returns the text to announce. Restoring a folder is the case: whether it landed
     * where it was, or under an ancestor because its parent is still in the trash, is decided by
     * `restoreFolder` and cannot be predicted from the row that was tapped.
     *
     * Null means "the block posted its own notice" — [restore] does, because its notice carries an
     * undo action and a plain re-post here would replace it with an actionless twin.
     */
    private fun mutateReporting(block: suspend () -> String?) {
        // Re-entrancy guard, matching VaultListViewModel.bulkEdit: a second tap that lands before
        // the first write returns is an ordinary double tap on a slow save, and it costs a whole
        // extra commit — which takes another rolling snapshot and evicts a genuinely older
        // generation from a ring that only holds five.
        if (_state.value.isWorking) return
        _state.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { announcement ->
                    refresh()
                    if (announcement != null) LkNotices.post(announcement)
                }
                .onFailure { _state.update { it.copy(error = ACTION_ERROR, loadFailed = false) } }
            _state.update { it.copy(isWorking = false) }
        }
    }

    /** Re-reads the trash after a failed load. Exposed because that failure leaves nothing else to press. */
    fun retryLoad() = refresh()

    private fun refresh() {
        // The whole-vault scan/search/sort/count runs off the main thread. Every caller — the init
        // load, the presentation-state collector, the search debounce, a restore's re-read, and
        // retryLoad — used to run this synchronously on viewModelScope's default (the main thread),
        // dropping frames at the 10k-item scale this app targets. Offloaded to Dispatchers.Default,
        // mirroring VaultListViewModel.refresh(). A newer refresh cancels an in-flight one, and the
        // isActive guard below stops a superseded scan from overwriting fresher state.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            // Trash never expires on a timer, so it only grows — and the vault list has a search
            // field while this screen had no way to narrow anything at all. Filtered here rather than
            // in the composable so the header's count and the list can never disagree about what is
            // shown. Ordered under [displayNameComparator], the same collation as the vault list.
            val query = _state.value.searchQuery.trim()
            val outcome = runCatching {
                // Both reads in the one `runCatching`: a locked vault must produce a single failed
                // state, not an empty folder section sitting beside a load error.
                //
                // Through the projection, not `getTrashItems()`/`getTrashedFolders()`. Deleting a
                // concealed item does not un-conceal it, and for as long as this screen read the raw
                // accessors it was the one surface where hiding something and then trashing it put it
                // back on screen — with a Delete forever beside it.
                val snapshot = repository.visibilitySnapshot()
                val items = snapshot.trashItems.sortedByDisplayName { it.title }
                val folders = snapshot.trashedFolderRows
                    .sortedByDisplayName { it.folder.name }
                // Unsorted and unfiltered: this is a lookup table for `FolderTree.pathOf`, not a
                // list anything renders.
                val folderTree = snapshot.liveFolders + snapshot.trashedFolderRows.map { it.folder }
                val shown = if (query.isEmpty()) {
                    // Matched by the SAME matcher the vault list uses, via `searchTrash`, rather than
                    // by `title.contains` — an item findable by its username a moment before deletion
                    // used to become unfindable the moment it was deleted, which is backwards for the
                    // one screen whose job is "I deleted the wrong thing". UX debt 8-3.
                    //
                    // The sorted order above is preserved by intersecting rather than re-listing: the
                    // repository returns items in vault order, and this screen sorts by title.
                    items
                } else {
                    val matched = repository.searchTrash(query).mapTo(HashSet()) { it.id }
                    items.filter { it.id in matched }
                }
                TrashScan(shown, folders, folderTree, items.size, snapshot.purgeableTrashItemCount)
            }
            // A newer refresh has already superseded this scan; do not publish its stale result.
            if (!isActive) return@launch
            outcome
                .onSuccess { (shown, folders, folderTree, total, purgeable) ->
                    _state.update {
                        it.copy(
                            items = shown,
                            folders = folders,
                            folderTree = folderTree,
                            totalCount = total,
                            purgeableItemCount = purgeable,
                            error = null,
                            loadFailed = false,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            items = emptyList(),
                            folders = emptyList(),
                            folderTree = emptyList(),
                            totalCount = 0,
                            error = LOAD_ERROR,
                            loadFailed = true,
                        )
                    }
                }
        }
    }

    private companion object {
        // Split for the same reason as VaultListViewModel: a failed restore leaves the row and its
        // buttons in place, a failed read leaves an empty screen that looks like an emptied trash.
        val ACTION_ERROR = R.string.vaultlist_error_action
        val LOAD_ERROR = R.string.vaultlist_error_load_trash
    }
}

/**
 * Which restore notice [TrashViewModel.restore] should show, given where the item's `folderId`
 * pointed immediately before the trash write and immediately after.
 *
 * A pure decision, deliberately pulled out of the class: [TrashViewModel.restore] cannot ask the
 * repository what it did (`restoreFromTrash` returns nothing, unlike [RestoreFolderOutcome]), so it
 * reads the outcome back by comparing folder ids either side of the write and hands the comparison
 * here — the same three-way shape [TrashViewModel.restoreFolder] already gets for free from
 * [RestoreFolderOutcome.reparented] / `.reparentedTo`.
 *
 * - Unchanged ([destinationFolderId] == [originalFolderId], including both null): the original
 *   folder was still live, or the item was already unfiled — [confirmation].
 * - Landed at the root ([destinationFolderId] is null but [originalFolderId] was not): the climb in
 *   `Vault.withItemRestoredFromTrash` found no live ancestor — [confirmationAtRoot].
 * - Landed somewhere else: climbed to a live ancestor — [confirmationAt] of its name, falling back
 *   to [confirmationAtRoot] if [folderName] cannot resolve it (a locked vault, or the folder gone by
 *   the time this runs), the same fallback [restoreFolder]'s notice already uses.
 */
internal fun restoreItemNotice(
    originalFolderId: UUID?,
    destinationFolderId: UUID?,
    confirmation: String,
    confirmationAt: (String) -> String,
    confirmationAtRoot: String,
    folderName: (UUID) -> String?,
): String = when {
    destinationFolderId == originalFolderId -> confirmation
    destinationFolderId == null -> confirmationAtRoot
    else -> folderName(destinationFolderId)?.let(confirmationAt) ?: confirmationAtRoot
}

/**
 * What the preview's gate says happened.
 *
 * The strings are `:feature:files`' own — the same sentences the live file viewer already shows for
 * the same five outcomes, already tagged `SECURITY-COPY: tier C` and translated in all four locales
 * there. Restating them under `vaultlist_` keys would put a second copy of security copy in the tree
 * to keep in step, which is how two surfaces end up making different claims about one guarantee.
 * `:feature:itemedit` borrows `files_attachments_photo_note` the same way.
 */
@StringRes
private fun revealGateMessage(outcome: RevealAuth.RevealOutcome): Int = when (outcome) {
    RevealAuth.RevealOutcome.UserCancelled -> FilesR.string.files_viewer_auth_cancelled
    RevealAuth.RevealOutcome.Denied -> FilesR.string.files_viewer_auth_denied
    RevealAuth.RevealOutcome.Unavailable -> FilesR.string.files_viewer_auth_unavailable
    is RevealAuth.RevealOutcome.Error -> FilesR.string.files_viewer_auth_failed
    // Reached only when a Success arrives against a session that has since ended — the key is
    // withheld either way, so it reports the same refusal rather than a fifth sentence.
    RevealAuth.RevealOutcome.Success -> FilesR.string.files_viewer_auth_failed
}

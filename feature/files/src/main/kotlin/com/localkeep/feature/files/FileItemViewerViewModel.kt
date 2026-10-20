package com.localkeep.feature.files

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.model.MediaViewerEntry
import com.localkeep.core.model.MediaViewerFocus
import com.localkeep.core.model.MediaViewerFocusRequest
import com.localkeep.core.model.MediaViewerScope
import com.localkeep.core.model.projectMediaViewerEntries
import com.localkeep.core.model.orderVaultItems
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.RevealAuth
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.SessionKey
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FileViewerPhase {
    Loading,
    Authenticating,
    Ready,
    Denied,
    Unavailable,
    Locked,
    Error,
}

data class FileItemViewerUiState(
    val itemId: UUID? = null,
    val scope: MediaViewerScope? = null,
    val entries: List<MediaViewerEntry> = emptyList(),
    val selectedPageId: String? = null,
    val phase: FileViewerPhase = FileViewerPhase.Loading,
    val pendingReveal: Boolean = false,
    @StringRes val outcomeMessage: Int? = null,
    @StringRes val actionError: Int? = null,
    val exitRequested: Boolean = false,
)

/** Owns the standalone file viewer's scope projection and its session-bound DEK. */
@HiltViewModel
class FileItemViewerViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val lockManager: VaultLockManager,
    private val fileBlobStore: FileBlobStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(FileItemViewerUiState())
    val state: StateFlow<FileItemViewerUiState> = _state.asStateFlow()

    private var focusRequest: MediaViewerFocusRequest? = null

    /**
     * A borrow handle, not a copy of the key. This used to hold its own `copyDekForSession` array
     * and wipe it on close, lock and clear — which is the same race the manager's own array had:
     * `onVaultLocked` fires from the lock-state collector and zeroed the array a decrypt on the IO
     * dispatcher was still reading. The handle carries the re-auth token instead, so the epoch is
     * checked at every read rather than once at the prompt, and there is no second copy of the DEK
     * in this process to forget about.
     */
    private var sessionKey: SessionKey? = null

    init {
        val itemId = savedStateHandle.get<String>(ITEM_ID_ARG)?.let(::parseUuid)
        if (itemId == null) {
            fail(R.string.files_viewer_item_missing)
        } else {
            focusRequest = MediaViewerFocus.take(itemId)
            if (focusRequest == null) {
                // The carrier is intentionally process-local. A restored route without it must
                // close rather than broaden into a full-vault viewer.
                fail(R.string.files_viewer_item_missing)
            } else if (!projectCurrent(itemId, initial = true)) {
                fail(R.string.files_viewer_item_missing)
            }
        }

        viewModelScope.launch {
            lockManager.lockState.collect { lockState ->
                if (lockState is LockState.Locked) onVaultLocked()
            }
        }
        // The lock is not the only way these pages can stop being viewable, and it was the only one
        // this screen watched. A reveal window runs on its own deadline, and that deadline does not
        // move: `MainActivity` extends the auto-lock on every user interaction while `RevealSession`
        // leaves the reveal where it is — so paging and scrubbing, the natural things to do in a
        // media viewer, are exactly what keeps the vault open past the moment a concealed item stops
        // being visible everywhere else. Nothing here was subscribed to the projection, so the
        // concealed pages stayed mounted, decrypted and swipeable. The backgrounded case was already
        // covered (ON_RESUME calls `revalidate`); the hole was strictly foreground-and-resumed.
        //
        // `revalidate` is the whole fix because it already does the mandated thing: `projectCurrent`
        // re-intersects the launch snapshot with the *current* projection before any page is
        // decrypted, which is the per-page intersection the media-viewer design calls for, and it
        // fails closed when the open page is one of the ones that went. Confined to
        // `VaultPresentationState.Unlocked` and — inside `revalidate` — to `Ready`, so neither this
        // StateFlow's `Locked` seed nor any emission arriving while the reveal prompt is still up can
        // empty a screen that has not finished authenticating.
        viewModelScope.launch {
            repository.presentationState.collect { presentation ->
                if (presentation !is VaultPresentationState.Unlocked) return@collect
                revalidate()
            }
        }
    }

    /** The token captured immediately before the system prompt is shown. */
    fun beginReveal(): Long? = lockManager.unlockedSessionToken()

    fun completeReveal(token: Long?, outcome: RevealAuth.RevealOutcome) {
        if (_state.value.exitRequested) return
        if (outcome is RevealAuth.RevealOutcome.Success && token != null) {
            val key = lockManager.sessionKeyOrNull(token)
            if (key == null) {
                onVaultLocked()
                return
            }
            sessionKey = key
            _state.value = _state.value.copy(
                phase = FileViewerPhase.Ready,
                pendingReveal = false,
                outcomeMessage = null,
            )
        } else {
            val message = when (outcome) {
                RevealAuth.RevealOutcome.UserCancelled -> R.string.files_viewer_auth_cancelled
                RevealAuth.RevealOutcome.Denied -> R.string.files_viewer_auth_denied
                RevealAuth.RevealOutcome.Unavailable -> R.string.files_viewer_auth_unavailable
                is RevealAuth.RevealOutcome.Error -> R.string.files_viewer_auth_failed
                RevealAuth.RevealOutcome.Success -> R.string.files_viewer_auth_failed
            }
            _state.value = _state.value.copy(
                phase = if (outcome is RevealAuth.RevealOutcome.Unavailable) {
                    FileViewerPhase.Unavailable
                } else {
                    FileViewerPhase.Denied
                },
                pendingReveal = false,
                outcomeMessage = message,
            )
        }
    }

    fun retry() {
        val itemId = _state.value.itemId ?: return
        if (!projectCurrent(itemId, initial = false)) return
        _state.value = _state.value.copy(
            phase = FileViewerPhase.Authenticating,
            pendingReveal = true,
            outcomeMessage = null,
            exitRequested = false,
        )
    }

    fun updateSelectedPage(id: String) {
        if (_state.value.entries.any { it.item.id.toString() == id }) {
            _state.value = _state.value.copy(selectedPageId = id)
        }
    }

    fun deletePage(itemId: String) {
        val id = parseUuid(itemId) ?: return
        viewModelScope.launch {
            runCatching { repository.moveToTrash(id) }
                .onSuccess {
                    offerUndo(repository, id)
                    close()
                }
                // Not the folder-action error this used to borrow: that one reads "Your changes are
                // still here", which is the right sentence for a half-typed form and the wrong one
                // for a delete the user is watching fail on a file they are looking at.
                .onFailure { _state.value = _state.value.copy(actionError = R.string.files_error_delete_failed) }
        }
    }

    fun clearActionError() {
        _state.value = _state.value.copy(actionError = null)
    }

    /** Re-reads the launch projection after Settings returns; never clamps to another page. */
    fun revalidate() {
        val id = _state.value.itemId ?: return
        if (_state.value.phase != FileViewerPhase.Ready) return
        if (!projectCurrent(id, initial = false)) {
            fail(R.string.files_viewer_out_of_scope)
        }
    }

    fun close() {
        sessionKey = null
        _state.value = _state.value.copy(
            entries = emptyList(),
            phase = FileViewerPhase.Error,
            pendingReveal = false,
            outcomeMessage = null,
            actionError = null,
            exitRequested = true,
        )
    }

    fun onVaultLocked() {
        if (_state.value.phase == FileViewerPhase.Locked && _state.value.exitRequested) return
        sessionKey = null
        _state.value = _state.value.copy(
            entries = emptyList(),
            phase = FileViewerPhase.Locked,
            pendingReveal = false,
            exitRequested = true,
        )
    }

    /** Called by the screen only while [FileViewerPhase.Ready]. */
    fun viewerSessionKeyOrNull(): SessionKey? = sessionKey

    fun viewerBlobStore(): FileBlobStore = fileBlobStore

    private fun projectCurrent(itemId: UUID, initial: Boolean): Boolean {
        val request = focusRequest ?: return false
        val snapshot = runCatching { repository.visibilitySnapshot() }.getOrNull() ?: return false
        val launchIds = request.visibleOrderedItemIds.toSet()
        val directFolderId = (request.scope as? MediaViewerScope.Folder)?.id
        val orderedItems = orderVaultItems(
            snapshot.liveItems.filter { it.id in launchIds },
            directFolderId = directFolderId,
        )
        val entries = projectMediaViewerEntries(
            orderedItems = orderedItems,
            folders = snapshot.liveFolders,
            scope = request.scope,
            visibleOrderedItemIds = orderedItems.map { it.id },
        )
        val selected = _state.value.selectedPageId ?: itemId.toString()
        // Keyed off the page actually on screen, never off the page the route launched with. There
        // used to be a second disjunct on [itemId] too, and the two were OR'd: page on from a
        // revealed file to a sibling that was never hidden, let the reveal window close, and the
        // launch item leaving the projection closed the viewer out from under a photo that was
        // still perfectly live. The gate is unchanged for the page being looked at — a concealed
        // open page still fails closed here, and a concealed sibling has already been dropped from
        // `entries` above whether or not the viewer stays up. `selected` falls back to `itemId` on
        // the initial projection, when nothing has been paged to yet, so the launch case is the
        // same check it always was.
        if (entries.none { it.item.id.toString() == selected }) {
            return false
        }
        _state.value = _state.value.copy(
            itemId = itemId,
            scope = request.scope,
            entries = entries,
            selectedPageId = selected,
            phase = if (initial) FileViewerPhase.Authenticating else _state.value.phase,
            pendingReveal = if (initial) true else _state.value.pendingReveal,
            outcomeMessage = null,
            exitRequested = false,
        )
        return true
    }

    private fun fail(@StringRes message: Int) {
        sessionKey = null
        _state.value = _state.value.copy(
            entries = emptyList(),
            phase = FileViewerPhase.Error,
            outcomeMessage = message,
            exitRequested = true,
        )
    }

    override fun onCleared() {
        sessionKey = null
        super.onCleared()
    }

    private companion object {
        const val ITEM_ID_ARG = "itemId"

        /**
         * Where the Undo runs: a scope the process owns, because `close()` takes this screen off the
         * stack and clears this ViewModel, and an Undo launched on its `viewModelScope` would run on
         * a cancelled scope and do nothing. The trap `TrashUndoBus` (`:app`) and
         * `VaultListViewModel.undoScope` document for the detail screen and the list.
         */
        val undoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /**
         * "Moved to trash · Undo", the notice the row menu, the bulk bar and the item detail screen
         * all post for the same act. The viewer trashed and closed in silence (C1 device pass,
         * 2026-10-04) while its own confirmation promised "this can be undone". [repository] is
         * passed in rather than read off the ViewModel so the stored closure holds nothing of it.
         */
        fun offerUndo(repository: VaultRepository, id: UUID) {
            LkNotices.post(R.string.files_notice_moved_to_trash, R.string.files_notice_undo) {
                undoScope.launch {
                    runCatching { repository.restoreFromTrash(id) }
                        .onSuccess { LkNotices.post(R.string.files_notice_restored) }
                        // Through the notice, against LkNotices' own rule, for TrashUndoBus's
                        // reason: the screen that could carry the error is gone, and the retry is
                        // real — the file is still in Trash, which is what the sentence says.
                        .onFailure { LkNotices.postRefusal(R.string.files_notice_restore_failed) }
                }
            }
        }
    }
}

private fun parseUuid(value: String): UUID? =
    runCatching { UUID.fromString(value) }.getOrNull()

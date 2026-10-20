package com.localkeep.app

import com.localkeep.core.ui.LkNotices
import com.localkeep.core.vault.VaultRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * An Undo for an action taken on a screen that destroys itself by succeeding.
 *
 * **Why this exists at all (4A-F7, 2026-09-20).** Trashing an item from its detail screen was the
 * one trash route in the app with no way back: the row menu offers "Moved to trash · Undo" and the
 * selection bar offers it for a whole batch, while the detail screen offered a confirmation dialog
 * and then nothing. Three routes to one action gave three different safety nets.
 *
 * The reason it stayed that way is real, and it is why the fix lives here rather than in
 * `ItemEditViewModel`: setting `trashed` pops the detail screen, which destroys its
 * `NavBackStackEntry` and clears that ViewModel — so an undo lambda closing over `viewModelScope`
 * would be launched on a cancelled scope and silently do nothing, which is worse than offering no
 * undo. The notice outlives the screen ([LkNotices] is a singleton hosted by the app shell); the
 * work behind its button has to outlive it too, and the only scope in the app that does is one the
 * process owns.
 *
 * **Registered rather than injected**, the same seam `VaultLockManager.registerBlobReKeyStore`
 * uses: [com.localkeep.app.navigation.AppNavGraph] is a composable with no injection point of its
 * own, and `LocalKeepApplication` already holds the repository. Before [register] runs there is
 * nothing to restore anyway — the vault is locked and no item detail can be on screen.
 *
 * Deliberately narrow. This is not a general undo stack: it holds no history, one tap consumes the
 * offer with the notice that carried it, and a second trash replaces the first notice exactly as
 * [LkNotices] replaces any other.
 */
object TrashUndoBus {

    /**
     * Process-lifetime, because that is the whole point. `SupervisorJob` so a failed restore cannot
     * take the scope down and leave every later undo inert — the same shape `LocalKeepApplication`
     * gives its own `appScope`.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var repository: VaultRepository? = null

    fun register(repository: VaultRepository) {
        this.repository = repository
    }

    /**
     * Announces that [id] has been trashed, with a button that puts it back.
     *
     * The three strings are resolved by the caller, which is the pattern `VaultListViewModel`'s own
     * `moveToTrash` already uses for the identical notice: [LkNotices] is deliberately Context-free
     * and cannot resolve a resource itself.
     *
     * The list does not need telling. `VaultRepository.presentationState` emits on every vault
     * mutation and `VaultListViewModel` re-reads its projection from it, so a restore made from
     * this scope reaches the screen the user is looking at without this object knowing it exists.
     */
    fun offerUndo(
        id: UUID,
        movedNotice: String,
        undoLabel: String,
        restoredNotice: String,
        restoreFailedNotice: String,
    ) {
        LkNotices.post(movedNotice, actionLabel = undoLabel) {
            val repo = repository ?: return@post
            scope.launch { restoreAndAnnounce({ repo.restoreFromTrash(id) }, restoredNotice, restoreFailedNotice) }
        }
    }

    /**
     * The body of the Undo button, split from the scope launch so its two outcomes can be driven
     * without a repository: the real one opens AndroidKeyStore on construction.
     */
    internal suspend fun restoreAndAnnounce(
        restore: suspend () -> Unit,
        restoredNotice: String,
        restoreFailedNotice: String,
    ) {
        runCatching { restore() }
            .onSuccess { LkNotices.post(restoredNotice) }
            // The one place this app reports a failure through [LkNotices], against that
            // object's own rule that "errors belong on the screen that can retry them".
            // There is no such screen left: the detail popped when the item was trashed,
            // and the alternative here is silence on a button the user pressed to get their
            // item back. The retry is real and one tap away — the item is still in Trash,
            // which is where the sentence sends them.
            .onFailure { LkNotices.postRefusal(restoreFailedNotice) }
    }
}

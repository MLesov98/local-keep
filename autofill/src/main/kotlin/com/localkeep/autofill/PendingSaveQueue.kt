package com.localkeep.autofill

import android.content.Context
import android.content.Intent
import com.localkeep.core.vault.LockState

/** What an unlock owes a save that is still waiting to be confirmed. */
enum class PendingSaveAction {
    /** Nothing is waiting, or the vault is not open. */
    NONE,

    /** Show the confirmation now, from the foreground. */
    OFFER,

    /** Drop it unseen — see [PendingSaveQueue.discardAll]. */
    DISCARD,
}

/**
 * The app shell's whole view of autofill saves the platform never got to show.
 *
 * `SaveConfirmActivity` is launched by the platform through a PendingIntent, and on API 35 that
 * launch can be refused outright as a background activity start (`BAL_BLOCK`, result code 102,
 * measured 2026-09-17) when the vault locks between the system's own "Save password to LocalKeep?"
 * sheet and the app coming forward. The service cannot see the refusal — `onSuccess(intentSender)`
 * is the last it hears — so the only honest recovery is the one the user's next unlock provides:
 * the app is in the foreground then, the launch is allowed, and the credential is still in memory.
 *
 * Deliberately a hand-off rather than storage. Nothing here touches disk: the vault is this app's
 * only at-rest secret store, and a captured password parked outside it — even wrapped by a keystore
 * key the app can unwrap without the master password — would be a second one, readable without the
 * lock the whole product is built on. Process death drops the queue, which is the right answer for
 * a secret with nowhere safe to wait.
 *
 * The unlock is never bypassed and never anticipated: the offer is made *after* an unlock this
 * object neither performs nor influences, and the confirmation it opens is the same screen, with
 * the same Save/Discard, that the ordinary path shows.
 */
object PendingSaveQueue {

    /**
     * What [state] means for the queue.
     *
     * A duress session discards rather than declines. A decoy vault exists to be shown to someone
     * who must believe it is all there is; offering it a login the real user captured minutes ago
     * would both file that credential in the wrong vault and say out loud, by name, that another
     * one was open recently.
     */
    fun actionFor(state: LockState): PendingSaveAction = when {
        state !is LockState.Unlocked -> PendingSaveAction.NONE
        state.isDuress -> PendingSaveAction.DISCARD
        PendingSaveStore.awaitingUnlock() != null -> PendingSaveAction.OFFER
        else -> PendingSaveAction.NONE
    }

    /**
     * The confirmation for the oldest queued save, or null when nothing is waiting any more.
     *
     * Re-read at the point of launch rather than carried from [actionFor]: the queue has its own
     * TTL and the caller waits for the Activity to be resumed first, which is an unbounded wait.
     * Handing the confirmation a token that expired in between would open it on its own
     * "this save expired" dead end — a message about something the user never saw offered.
     */
    fun offerIntent(context: Context): Intent? =
        PendingSaveStore.awaitingUnlock()?.let { token ->
            SaveConfirmActivity.intent(context, token)
        }

    /** Drops every queued save, wiping the password or card it holds. */
    fun discardAll() {
        PendingSaveStore.discardAwaitingUnlock()
    }
}

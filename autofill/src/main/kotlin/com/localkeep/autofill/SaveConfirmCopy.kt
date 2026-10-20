package com.localkeep.autofill

import androidx.annotation.StringRes

/**
 * Which title and which primary-action label [SaveConfirmActivity] shows.
 *
 * Pulled out of `onCreate` so the choice can be tested: the strings themselves have distinguished a
 * replacement from a first save since the update copy landed, but nothing pinned *which* of the
 * three titles a given state picks, and the branch lives in an Activity that needs Hilt, a
 * repository and a live lock manager to construct. These two functions are the whole decision; the
 * activity keeps the formatting, because a card title takes one argument and a login title takes
 * two.
 */
@StringRes
internal fun saveConfirmTitleRes(
    hasCard: Boolean,
    locked: Boolean,
    isUpdate: Boolean,
    awaitingUnlock: Boolean = false,
    hasUsername: Boolean = true,
): Int = when {
    hasCard -> R.string.autofill_save_confirm_card_title
    // A password-only form saves with no username since 2026-10-02, and every title below names
    // one: "Save a new login for  on example.com?". These take the site alone. "Update" only on an
    // open vault, as [saveConfirmActionRes] decides; the summary card still says a queued offer
    // was captured while locked.
    !hasUsername -> if (isUpdate && !locked) {
        R.string.autofill_save_confirm_password_title_update_no_username
    } else {
        R.string.autofill_save_confirm_password_title_no_username
    }
    // While locked the vault couldn't be searched, so we genuinely don't know yet whether this is
    // a new login or an update — say the neutral thing rather than guessing wrong.
    locked -> R.string.autofill_save_confirm_password_title_locked
    // Captured while locked and reaching the user only now, after an unlock: the two conditions
    // together are what identify the queued offer, with no extra carried in the Intent to get out
    // of step with the store. The user is no longer on the site they signed in to and may have
    // stopped expecting this at all, so the title says when it came from rather than pretending
    // to be the prompt they accepted minutes ago. `isUpdate` is always false here — SaveHandler
    // had no readable vault to match against — and SaveConfirmActivity re-resolves it at save time.
    awaitingUnlock -> R.string.autofill_save_confirm_password_title_queued
    isUpdate -> R.string.autofill_save_confirm_password_title_update
    else -> R.string.autofill_save_confirm_password_title_new
}

/**
 * "Update" only when the vault is open *and* it already holds this login.
 *
 * The lock check is not redundant with [saveConfirmTitleRes]'s: a button reading "Update" over a
 * title that declines to say whether anything is being replaced is the one combination that reads
 * as a contradiction, and a locked vault is exactly the state in which the match may be stale —
 * `SaveHandler` resolved it before the lock, `SaveConfirmActivity.resolveExisting` re-resolves it
 * after the unlock, and between those two points the honest word is "Save".
 */
@StringRes
internal fun saveConfirmActionRes(
    locked: Boolean,
    isUpdate: Boolean,
): Int = if (isUpdate && !locked) {
    R.string.autofill_save_confirm_update
} else {
    R.string.autofill_save_confirm_save
}

/**
 * What the confirm screen's summary card says about the credential about to be stored.
 *
 * It carries no secret, deliberately: [secretLabel] names the masked row and the bullets themselves
 * are drawn by the screen, so the captured password never reaches a composable at all. This screen
 * is drawn over another app's sign-in or checkout page, and a layout edit made months from now
 * cannot put a password on it that it was never given.
 */
internal data class SaveConfirmSummary(
    val targetLabel: String,
    /** The site or app the credential belongs to — the same label the title already names. */
    val target: String,
    val identityLabel: String,
    /**
     * The username, or for a captured card its last four digits. Blank when the captured form
     * carried neither; the row is then left out rather than drawn empty.
     */
    val identity: String,
    /** Names the masked password row, or null for a card, whose number is already [identity]. */
    val secretLabel: String?,
    /** Set only for an offer captured while the vault was locked. */
    val queuedNote: String?,
)

/**
 * The summary card's contents for one pending save.
 *
 * Pure, with the resource lookup passed in, for the same reason the two functions above are: the
 * branch deciding whether this is a card or a login — and therefore whether a masked password row
 * is drawn at all — is worth pinning without a Hilt-constructed Activity.
 */
internal fun saveConfirmSummary(
    entry: PendingSaveStore.Entry,
    target: String,
    string: (Int) -> String,
): SaveConfirmSummary {
    val card = entry.card
    return SaveConfirmSummary(
        targetLabel = string(R.string.autofill_save_confirm_summary_target),
        target = target,
        identityLabel = string(
            if (card != null) {
                R.string.autofill_save_confirm_summary_card
            } else {
                R.string.autofill_save_confirm_summary_username
            },
        ),
        // Last four only, through the same mask the suggestion rows use — never the PAN, for the
        // reason the card title gives: this screen can be looked at over a shoulder.
        identity = if (card != null) CardMask.tail(card.number.concatToString()) else entry.username,
        // No password row for a card. Its number is the row above, and the security code is a
        // secret this screen has no reason to acknowledge at all.
        secretLabel = if (card != null) {
            null
        } else {
            string(R.string.autofill_save_confirm_summary_password)
        },
        // Says where the offer came from, because a queued one arrives long after the user left the
        // site — the same reason the queued *title* names the site rather than pretending to be the
        // prompt they accepted minutes ago.
        queuedNote = if (entry.awaitingUnlock) {
            string(R.string.autofill_save_confirm_summary_queued)
        } else {
            null
        },
    )
}

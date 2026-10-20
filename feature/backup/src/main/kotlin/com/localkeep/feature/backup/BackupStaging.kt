package com.localkeep.feature.backup

/**
 * Whether a decrypted `.vault` restore is staged and still waiting on Merge or Replace.
 *
 * Exists for one reader: `MainActivity`'s lock collector, which owes the next unlock a notice when
 * the lock has just thrown away work in progress. That decision was made from
 * `AppNavGraph.droppedEditRoute(currentRoute)`, a route-template test that names the import wizard
 * and never named `backup` — so a staged restore, which costs a file pick, a password and an
 * Argon2id derivation, vanished with no word at all (fixed 2026-09-13).
 *
 * **Not fixed by adding `"backup"` to that route list, and this is the whole reason this object
 * exists.** The route is where the user *was*, not what they had. `droppedEditRoute` already
 * carries a scar from exactly that mistake — a broad `startsWith("item/")` matched the read-only
 * detail route and announced discarded edits to people who had only *looked* at an item — and
 * `backup` is the same shape, only worse: it is the screen a user sits on to read about cloud
 * backup or to check when they last exported, and every auto-lock there would have claimed their
 * changes were discarded when there were none. The question has to be "was something staged", and
 * only the ViewModel knows that.
 *
 * Held in memory, like [BackupFocus], so it cannot reach saved instance state; the flag is a
 * statement about a live ViewModel and means nothing once the process has died. [take] consumes it
 * so one dropped restore produces one notice.
 */
object BackupStaging {

    @Volatile
    private var staged: Boolean = false

    /** Mirrors `BackupSettingsUiState.pendingImportVault != null`. */
    fun setStaged(value: Boolean) {
        staged = value
    }

    /** Whether a restore was staged, forgetting it. Called once, as the lock drops the screen. */
    fun take(): Boolean {
        val taken = staged
        staged = false
        return taken
    }
}

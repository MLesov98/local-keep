package com.localkeep.core.ui

import androidx.annotation.StringRes

/**
 * A notice to show at the next unlock, for something a lock did.
 *
 * A lock takes down whatever screen was open, and a notice posted at that moment would expire over
 * the unlock screen before anyone came back to read it. This holds the one sentence until the vault
 * list sees the vault open again and posts it through [LkNotices].
 *
 * Holds a resource id and nothing else — never the content of what was lost, which is the point of
 * the lock — and only in memory: a process that dies while locked has nothing left to report.
 */
object LkUnlockNotices {

    @Volatile
    private var pending: Int? = null

    /** Queues [messageRes] for the next unlock, replacing anything already waiting. */
    fun queue(@StringRes messageRes: Int) {
        pending = messageRes
    }

    /**
     * Posts the waiting notice, if there is one, and forgets it. Through [LkNotices.postAtUnlock],
     * because it lands with the unlock itself — see [LkNotices.UNLOCK_MS].
     */
    fun postPending() {
        take()?.let(LkNotices::postAtUnlock)
    }

    /** Returns the waiting notice and forgets it, or null if there is none. */
    @StringRes
    fun take(): Int? {
        val taken = pending
        pending = null
        return taken
    }
}

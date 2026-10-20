package com.localkeep.core.common

/**
 * The id of an item the vault list should point at when it next appears.
 *
 * Saving a new item used to return the user to exactly the list they left, which on a type-filtered
 * destination could not show what they had just made: add a login from the drawer's "2FA codes" and
 * the header still read "2FA codes · 1 item", the new login nowhere on screen and nothing saying it
 * had been saved at all (device-confirmed 2026-08-10). The two ways into that screen did not even
 * agree — the chip route reset its filter on return and the drawer route kept it — so the same
 * action had two different outcomes depending on which "2FA" the user had tapped.
 *
 * Unlike [PendingSecret], which exists because a password must never touch a navigation route, an
 * item id is not sensitive: it is already in the `item/{itemId}` route of every detail screen. This
 * is in memory for a duller reason — it has to work for a `popBackStack` and a `navigate` alike, and
 * reading the back-stack entry that is about to become current is timing-dependent in a way this is
 * not.
 *
 * Losing it to process death is correct. A momentary highlight is not state worth restoring, and
 * restoring one minutes later, after the vault has re-locked and re-opened, would be a flash of
 * emphasis on a row the user is no longer thinking about.
 */
object PendingReveal {

    @Volatile
    private var itemId: String? = null

    /** Asks the next vault list to reveal [id]. Replaces anything already waiting. */
    fun offer(id: String) {
        itemId = id
    }

    /** Returns the waiting id and forgets it, so a later return to the list is not re-highlighted. */
    fun take(): String? {
        val taken = itemId
        itemId = null
        return taken
    }

    fun clear() {
        itemId = null
    }
}

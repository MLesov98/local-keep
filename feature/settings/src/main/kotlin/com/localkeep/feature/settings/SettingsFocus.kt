package com.localkeep.feature.settings

import androidx.annotation.StringRes

/**
 * Which settings row the next [SettingsScreen] should open on.
 *
 * The drawer offers "Duress / decoy" as a destination of its own (UI/UX spec §4.3), but the duress
 * form is a row inside Settings rather than a screen — so the link landed on the top of a list of
 * twenty rows and left the user to find the one it had just named.
 *
 * Carried in memory rather than as a navigation argument, for the same reason as
 * [com.localkeep.core.common.PendingSecret]: a route string is serialised into the saved instance
 * state and written to disk, and "the user went looking for the duress vault" is exactly the fact
 * the duress feature exists to keep off this device. [take] consumes it, so arriving at Settings by
 * any other route shows the whole list.
 */
object SettingsFocus {

    /**
     * The entry title of the duress row, as a resource rather than a literal.
     *
     * It held the English string `"Duress vault"` and was matched against the row's *rendered*
     * title, so it only ever worked in English: under any translation — caught on the `en-XA`
     * pseudolocale device pass, 2026-07-30 — Settings opened with that literal typed into its
     * search field, matched nothing, and showed "No matches". The drawer's Duress destination was
     * a dead end in fr, de, es and pt. The screen resolves this with `stringResource` at
     * composition, the same rule the codebase applies to ViewModels.
     */
    @StringRes
    val DURESS: Int = R.string.settings_duress_title

    @Volatile
    private var pending: Int? = null

    /** Asks the next Settings screen to open on the entry whose title is [entryTitleRes]. */
    fun request(@StringRes entryTitleRes: Int) {
        pending = entryTitleRes
    }

    /** Returns the requested entry title resource and forgets it, or null if there is none. */
    @StringRes
    fun take(): Int? {
        val taken = pending
        pending = null
        return taken
    }

    fun clear() {
        pending = null
    }
}

package com.localkeep.app.review

import android.content.Context
import android.content.SharedPreferences

/**
 * Decides whether to ask Play to show its review card, and records that we asked.
 *
 * ## Why this is a gate and not a prompt
 *
 * Google's In-App Review API never reports the outcome: the call completes identically whether
 * the user submitted a rating, dismissed the card, or never saw it because Play's own undocumented
 * quota suppressed it. So there is no "has rated" flag to read, and there never will be — any
 * design that branches on whether the user rated is building on a fact the platform refuses to
 * give. This gate therefore counts *asks*, not ratings, and is deliberately stingy about them.
 *
 * That is also why the design handed over in the 2026-07-30 brief could not ship. It was a bottom
 * sheet reading "Enjoying LocalKeep?" over a "Rate on Google Play" button, and Google's own design
 * guidance for this API bans both halves by name: do not ask the user an opinion question before
 * or while presenting the card, and do not put a call-to-action button in front of it. The reason
 * for the second one is practical rather than bureaucratic — the quota may silently suppress the
 * card, so a button promising a rating flow is a button that sometimes does nothing.
 *
 * The escape hatch for someone who actively wants to rate is the Settings row, which opens the
 * store listing directly. That is an ordinary intent, not this API, so none of the above binds it.
 *
 * ## The thresholds
 *
 * All four must hold. They are conservative on purpose: this app asks the user for a master
 * password and nothing else, and an unsolicited card is the only thing it will ever put in front
 * of them that it did not need to.
 *
 *  - [MIN_UNLOCKS] successful unlocks — evidence the vault is in real use, not a trial run.
 *  - [MIN_DAYS_INSTALLED] days since first launch, so a burst of first-day setup unlocks cannot
 *    qualify on its own.
 *  - [MIN_DAYS_BETWEEN_ASKS] days since the last ask.
 *  - [MAX_ASKS] asks ever. After that this gate is closed permanently.
 *
 * Play enforces its own quota underneath all of this. These exist because that quota is
 * explicitly documented as an implementation detail that can change without notice, so it is not
 * something to rely on for restraint.
 *
 * ## Storage
 *
 * Its own SharedPreferences file, not [com.localkeep.feature.settings.SettingsPreferences]: this
 * is bookkeeping about the app's relationship with the store, not a user preference, and nothing
 * here should appear anywhere near the settings the user actually set. The file is inside the
 * app's Auto Backup set, which is the behaviour we want — restoring to a new phone should carry
 * "already asked twice" with it rather than resetting the counter.
 */
object ReviewGate {
    private const val FILE_NAME = "review_gate"
    private const val KEY_FIRST_LAUNCH = "first_launch_at"
    private const val KEY_UNLOCKS = "unlock_count"
    private const val KEY_LAST_ASK = "last_ask_at"
    private const val KEY_ASK_COUNT = "ask_count"

    const val MIN_UNLOCKS = 5
    const val MIN_DAYS_INSTALLED = 14
    const val MIN_DAYS_BETWEEN_ASKS = 90
    const val MAX_ASKS = 3

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Records a successful unlock and reports whether this is a moment to ask.
     *
     * Returns true at most [MAX_ASKS] times in the life of an install. The caller is expected to
     * treat a `true` as permission to *request* the card, not as a promise that one will appear.
     *
     * [now] is a parameter rather than a `System.currentTimeMillis()` call inside so the thresholds
     * are testable without waiting two weeks.
     */
    fun onUnlock(
        context: Context,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val prefs = preferences(context)
        val firstLaunch = firstLaunchAt(prefs, now)
        val unlocks = prefs.getInt(KEY_UNLOCKS, 0) + 1
        prefs.edit().putInt(KEY_UNLOCKS, unlocks).apply()

        val asks = prefs.getInt(KEY_ASK_COUNT, 0)
        val lastAsk = prefs.getLong(KEY_LAST_ASK, 0L)

        // A clock that moved backwards (timezone edit, NTP correction, a restored backup from a
        // phone set to next year) would otherwise make the elapsed checks pass instantly. Clamping
        // at zero means a backwards clock delays the ask rather than triggering it.
        val daysInstalled = daysBetween(firstLaunch, now)
        val daysSinceAsk = if (lastAsk == 0L) Long.MAX_VALUE else daysBetween(lastAsk, now)

        return unlocks >= MIN_UNLOCKS &&
            daysInstalled >= MIN_DAYS_INSTALLED &&
            asks < MAX_ASKS &&
            daysSinceAsk >= MIN_DAYS_BETWEEN_ASKS
    }

    /**
     * Records that the review flow was requested.
     *
     * Called whether or not Play actually showed anything, because we cannot tell the difference —
     * and treating an invisible, quota-suppressed request as "didn't count" is how an app ends up
     * calling the API on every single unlock.
     */
    fun onAsked(
        context: Context,
        now: Long = System.currentTimeMillis(),
    ) {
        val prefs = preferences(context)
        prefs.edit()
            .putLong(KEY_LAST_ASK, now)
            .putInt(KEY_ASK_COUNT, prefs.getInt(KEY_ASK_COUNT, 0) + 1)
            .apply()
    }

    /** Stamps first launch on the first call and returns it thereafter. */
    private fun firstLaunchAt(
        prefs: SharedPreferences,
        now: Long,
    ): Long {
        val stored = prefs.getLong(KEY_FIRST_LAUNCH, 0L)
        if (stored != 0L) return stored
        prefs.edit().putLong(KEY_FIRST_LAUNCH, now).apply()
        return now
    }

    private fun daysBetween(
        from: Long,
        to: Long,
    ): Long = ((to - from).coerceAtLeast(0L)) / DAY_MS

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}

package com.localkeep.feature.backup

import android.content.Context
import com.localkeep.core.vault.LocalKeepSettings
import java.util.concurrent.TimeUnit

/**
 * "You have not exported in a while" — decided here, shown in the app.
 *
 * This was a daily WorkManager job that posted a system notification. It cost six permissions
 * across the merged manifest (POST_NOTIFICATIONS plus WAKE_LOCK, ACCESS_NETWORK_STATE,
 * RECEIVE_BOOT_COMPLETED and FOREGROUND_SERVICE injected by androidx.work) in an app that
 * advertises needing none — and it was the *weakest* possible delivery for the message, because a
 * notification the user has denied, or that Android's background limits defer, is a reminder that
 * never arrives. Someone who has never exported their vault is exactly the person least likely to
 * have granted a notification permission to a password manager.
 *
 * An in-app banner has none of those problems: it needs no permission, cannot be silently dropped,
 * and appears where the user can act on it. It is also honest about what it knows — the check reads
 * one timestamp and nothing else.
 *
 * The staleness rule is pure and lives here so it can be tested without a device.
 */
object StaleExportReminder {
    const val STALE_THRESHOLD_DAYS: Long = 30L

    // Names owned by `:core:vault`'s LocalKeepSettings — vault creation clears these same three
    // keys, and a second spelling of them here is how that clearing would come to miss one.
    private const val KEY_DISMISSED_UNTIL_EPOCH_MS = LocalKeepSettings.KEY_EXPORT_REMINDER_SNOOZED_UNTIL
    private const val KEY_FIRST_SEEN_EPOCH_MS = LocalKeepSettings.KEY_EXPORT_REMINDER_FIRST_SEEN

    /** How long "Not now" hides the banner for. Long enough not to nag, short enough to matter. */
    private val SNOOZE_MS = TimeUnit.DAYS.toMillis(7)

    /**
     * True when the vault has not been exported inside the threshold and the banner is not snoozed.
     *
     * "Never exported" is measured from [since] — the first moment this app had a vault to remind
     * anyone about — not from the epoch. Without that anchor a vault created ten seconds ago is
     * instantly "overdue", and the banner appeared *over the onboarding flow*, telling someone who
     * had not finished creating their vault that it had been a while since they last exported it.
     * Which is not a nag, it is a false statement.
     */
    fun shouldRemind(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nowMs < snoozedUntil(context)) return false
        return isStale(
            lastExportMs = BackupPreferences.lastExportEpochMillis(context),
            nowMs = nowMs,
            since = firstSeen(context, nowMs),
        )
    }

    /**
     * Pure staleness rule.
     *
     * [lastExportMs] null means never exported, in which case [since] stands in for it: the clock
     * starts when the vault first existed.
     */
    fun isStale(lastExportMs: Long?, nowMs: Long, since: Long = 0L): Boolean {
        val from = lastExportMs ?: since
        return nowMs - from > TimeUnit.DAYS.toMillis(STALE_THRESHOLD_DAYS)
    }

    fun snooze(context: Context, nowMs: Long = System.currentTimeMillis()) {
        LocalKeepSettings.of(context)
            .edit()
            .putLong(KEY_DISMISSED_UNTIL_EPOCH_MS, nowMs + SNOOZE_MS)
            .apply()
    }

    private fun snoozedUntil(context: Context): Long =
        LocalKeepSettings.of(context)
            .getLong(KEY_DISMISSED_UNTIL_EPOCH_MS, 0L)

    /** When this install first had something worth backing up. Written once, then read. */
    private fun firstSeen(context: Context, nowMs: Long): Long {
        val prefs = LocalKeepSettings.of(context)
        val stored = prefs.getLong(KEY_FIRST_SEEN_EPOCH_MS, 0L)
        if (stored > 0L) return stored
        prefs.edit().putLong(KEY_FIRST_SEEN_EPOCH_MS, nowMs).apply()
        return nowMs
    }
}

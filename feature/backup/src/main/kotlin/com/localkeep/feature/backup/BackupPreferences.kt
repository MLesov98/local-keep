package com.localkeep.feature.backup

import android.content.Context
import com.localkeep.core.vault.LocalKeepSettings

/**
 * Non-secret backup settings: the disable-cloud-backup toggle and the last-export
 * timestamp. Backed up itself (via `dataExtractionRules`'s `sharedpref` include) —
 * neither value is sensitive.
 *
 * DEVIATION from the plan: the plan had this object own a brand-new `backup_prefs`
 * SharedPreferences file. In the real codebase, Phase 8 already shipped a
 * "Disable cloud backup" toggle in `:feature:settings` (`SettingsPreferences`,
 * file `localkeep_settings`, key `disable_cloud_backup`) as a stub ahead of this
 * phase's real enforcement ("The preference is stored now. Phase 15 adds and
 * verifies the backup enforcement." — see `SettingsScreen.kt`). Feature modules in
 * this codebase never depend on each other (only `:core:*` -> feature, wired
 * together by `:app`), so `:feature:backup` cannot import `:feature:settings`'
 * `SettingsPreferences` object directly. Instead this object reads/writes the exact
 * same underlying SharedPreferences file + key by name, so `SettingsScreen`'s
 * existing switch and `BackupSettingsScreen`'s switch (Task 7) always agree on a
 * single stored value — the disable mechanism would otherwise be silently split
 * across two independent booleans, which is precisely the "cosmetic toggle" bug §H
 * warns against.
 */
object BackupPreferences {
    // The file name and both keys now come from `:core:vault`'s LocalKeepSettings, which every
    // module that touches this file depends on. They used to be spelled out here, in
    // SettingsPreferences, in StaleExportReminder and in VaultInitializer — four copies of one
    // string, each documented, each a chance to drift onto a key nobody writes.
    private const val KEY_DISABLE_CLOUD_BACKUP = LocalKeepSettings.KEY_DISABLE_CLOUD_BACKUP
    private const val KEY_LAST_EXPORT_EPOCH_MS = LocalKeepSettings.KEY_LAST_EXPORT_EPOCH_MS

    // Not from LocalKeepSettings: :app reads this one through the two functions below and nothing
    // else touches the key, so there is no second spelling of it to drift onto — the reason the
    // other two keys had to move there does not apply.
    private const val KEY_RESTORE_NOTICE_PENDING = "restore_notice_pending"

    // Written alongside KEY_RESTORE_NOTICE_PENDING by the same commit, read back independently by
    // consumeRestoreNoticePendingMissingBlobRefCount — see that function for why it is a separate
    // one-shot consumer rather than folded into the boolean's own return value.
    private const val KEY_RESTORE_NOTICE_MISSING_FILES = "restore_notice_missing_files"

    fun isCloudBackupDisabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISABLE_CLOUD_BACKUP, false)

    fun setCloudBackupDisabled(context: Context, disabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISABLE_CLOUD_BACKUP, disabled).apply()
    }

    fun recordExportNow(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_EXPORT_EPOCH_MS, System.currentTimeMillis()).apply()
    }

    /**
     * A snapshot restore succeeded and the user has not been told yet.
     *
     * [VaultLockManager.restoreVaultFile] force-locks the vault before it returns, which routes the
     * UI to the unlock screen and destroys the Backup screen's back-stack entry — so the success
     * cannot be reported where it happened. This flag carries it across the lock; `MainActivity`
     * consumes it after the next successful unlock and posts the notice there.
     *
     * `commit`, not `apply`: the write happens microseconds before the forced lock tears the screen
     * down, and the flag is the only record that the restore ran at all.
     *
     * Persisted rather than kept in memory because the gap it spans is unbounded — the user can
     * leave the phone on the unlock screen for a day, and a process death in between would drop the
     * confirmation for the one operation that replaces the entire vault. It rides Auto Backup like
     * the rest of this file, so restoring these prefs onto a second device can post the notice once
     * there; a spurious "restored" after an actual restore is a smaller harm than silence.
     */
    /**
     * [missingBlobRefCount] is the distinct count of blob refs the restored snapshot's items point
     * at that this device does not have on disk — see [RollingBackupManager.missingBlobRefCount],
     * the restore-side counterpart of [VaultImporter.ImportResult.missingBlobRefCount]. Written in
     * the same commit as the pending flag so the two can never disagree about which restore they
     * describe, but read back through its own one-shot consumer
     * ([consumeRestoreNoticePendingMissingBlobRefCount]) rather than out of this function's return
     * value: [consumeRestoreNoticePending] is `:app`'s existing, already-shipped call site, and
     * changing what it returns would be a silent behavioural change for a caller this module does
     * not own. Defaults to 0 — "nothing known missing" — which is also what a caller that never
     * asks for the count sees, so nothing downstream regresses by ignoring it.
     */
    fun setRestoreNoticePending(context: Context, missingBlobRefCount: Int = 0) {
        prefs(context).edit()
            .putBoolean(KEY_RESTORE_NOTICE_PENDING, true)
            .putInt(KEY_RESTORE_NOTICE_MISSING_FILES, missingBlobRefCount)
            .commit()
    }

    /** True once per [setRestoreNoticePending]; clears the flag so the notice is not posted twice. */
    fun consumeRestoreNoticePending(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_RESTORE_NOTICE_PENDING, false)) return false
        prefs.edit().remove(KEY_RESTORE_NOTICE_PENDING).apply()
        return true
    }

    /**
     * The [setRestoreNoticePending] missing-file count, consumed once and independently of
     * [consumeRestoreNoticePending] — see that parameter's doc for why. Reads 0 (the same default a
     * caller that never wrote one would see) whether nothing is pending or a pending restore simply
     * had nothing missing; the two are indistinguishable here on purpose, since a caller that wants
     * to know whether a restore happened at all already has [consumeRestoreNoticePending] for that.
     */
    fun consumeRestoreNoticePendingMissingBlobRefCount(context: Context): Int {
        val prefs = prefs(context)
        val count = prefs.getInt(KEY_RESTORE_NOTICE_MISSING_FILES, 0)
        prefs.edit().remove(KEY_RESTORE_NOTICE_MISSING_FILES).apply()
        return count
    }

    /** Null means "never exported" — [StaleExportReminderWorker] treats that as stale. */
    fun lastExportEpochMillis(context: Context): Long? {
        val v = prefs(context).getLong(KEY_LAST_EXPORT_EPOCH_MS, -1L)
        return if (v >= 0L) v else null
    }

    private fun prefs(context: Context) = LocalKeepSettings.of(context)
}

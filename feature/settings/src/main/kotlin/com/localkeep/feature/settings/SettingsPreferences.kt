package com.localkeep.feature.settings

import android.content.Context
import com.localkeep.core.vault.LocalKeepSettings
import com.localkeep.core.vault.AutoLockPreset

/**
 * Non-secret local preferences.
 *
 * This deferred backup-policy enforcement to a later phase until that phase had already shipped. The
 * `disable_cloud_backup` bool below is no longer a stub: `LocalKeepBackupAgent.onFullBackup` reads
 * it through `BackupPreferences`, which shares this exact file and key, and omits `vault.enc` from
 * the backup set when it is set.
 */
object SettingsPreferences {
    private const val FILE_NAME = LocalKeepSettings.FILE
    private const val AUTO_LOCK_PRESET = "auto_lock_preset"

    /**
     * The stored auto-lock profile (§10.2).
     *
     * There used to be a migration here from a flat `auto_lock_seconds` key, mapping it onto the
     * nearest preset. It has been removed rather than fixed: the flat picker it migrated from
     * existed only in the very first commit and was replaced before any release, `versionCode` is
     * still 1 and there are no tags, so no user has ever upgraded through it — and the whole
     * seconds-based API it fed (`autoLockSeconds`, `setAutoLockSeconds`, `allowedAutoLockSeconds`)
     * had no caller anywhere in the app. Unreachable code hides defects; this is the delete half of
     * "wire it or delete it".
     */
    fun autoLockPreset(context: Context): AutoLockPreset =
        AutoLockPreset.byName(preferences(context).getString(AUTO_LOCK_PRESET, null))
            ?: AutoLockPreset.DEFAULT

    fun setAutoLockPreset(
        context: Context,
        preset: AutoLockPreset,
    ) {
        preferences(context).edit().putString(AUTO_LOCK_PRESET, preset.name).apply()
    }


    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}

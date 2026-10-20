package com.localkeep.core.common

/**
 * The name of the one non-secret settings file, and the keys inside it that more than one module
 * reads or writes.
 *
 * Five objects across five modules were spelling `"localkeep_settings"` out for themselves —
 * `:feature:settings`, `:feature:backup` (twice), `:core:ui` and `:core:vault`. Not by accident:
 * feature modules in this codebase never depend on each other, so until now a shared owner had
 * nowhere to live that all of them could see. Every copy was documented and every copy was still a
 * chance for one to drift onto a key nobody writes — silently, the way a stale string always does.
 *
 * `:core:common` is a plain Kotlin module with no Android on the classpath, which is exactly why
 * the names live here and nothing else does: a `String` needs no `Context`, so this is the one
 * place every module can reach. The `SharedPreferences` helpers that use these are in
 * `:core:vault`'s `LocalKeepSettings`.
 *
 * Nothing secret belongs in this file. It sits inside Auto Backup's `sharedpref` include, so
 * anything written to it travels to the user's Google account with the rest of the prefs.
 */
object SettingsKeys {
    const val FILE: String = "localkeep_settings"

    /** Opts `vault.enc` out of Android Auto Backup. Read by the app's `BackupAgent`. */
    const val DISABLE_CLOUD_BACKUP: String = "disable_cloud_backup"

    /**
     * False until the user has actually saved a Recovery Kit — exported a PDF, printed one, or
     * captured the QR — whether that happened during onboarding or later from Settings.
     *
     * Onboarding no longer refuses to move on without it, so something has to remember that the
     * one unrecoverable artefact in the product is still unmade. Says nothing about the vault, the
     * password or the kit's contents, which is what makes it safe to keep in this file at all.
     */
    const val RECOVERY_KIT_SAVED: String = "recovery_kit_saved"

    /**
     * Whether saving a gallery photo or a local document asks about deleting the original left
     * outside the vault. On unless the user said "Don't ask again" or switched it off in Settings.
     */
    const val OFFER_DELETE_ORIGINALS: String = "offer_delete_originals"

    const val LAST_EXPORT_EPOCH_MS: String = "last_export_epoch_millis"
    const val EXPORT_REMINDER_FIRST_SEEN: String = "export_reminder_first_seen"
    const val EXPORT_REMINDER_SNOOZED_UNTIL: String = "export_reminder_snoozed_until"
}

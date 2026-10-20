package com.localkeep.core.vault

import android.content.Context
import android.content.SharedPreferences
import com.localkeep.core.common.SettingsKeys

/**
 * `SharedPreferences` access to the one non-secret settings file.
 *
 * The names themselves live in `:core:common`'s [SettingsKeys], because a `String` needs no
 * `Context` and `:core:common` is the only module every caller can see — `:core:ui` reads this same
 * file and does not depend on `:core:vault`. This object is the Android half: the helpers whose
 * behaviour more than one module has to agree on, in the lowest module that has a `Context`.
 *
 * Nothing secret belongs in this file. It sits inside Auto Backup's `sharedpref` include, so
 * anything written through it travels to the user's Google account with the rest of the prefs.
 */
object LocalKeepSettings {
    const val FILE: String = SettingsKeys.FILE
    const val KEY_DISABLE_CLOUD_BACKUP: String = SettingsKeys.DISABLE_CLOUD_BACKUP
    const val KEY_RECOVERY_KIT_SAVED: String = SettingsKeys.RECOVERY_KIT_SAVED
    const val KEY_LAST_EXPORT_EPOCH_MS: String = SettingsKeys.LAST_EXPORT_EPOCH_MS
    const val KEY_EXPORT_REMINDER_FIRST_SEEN: String = SettingsKeys.EXPORT_REMINDER_FIRST_SEEN
    const val KEY_EXPORT_REMINDER_SNOOZED_UNTIL: String = SettingsKeys.EXPORT_REMINDER_SNOOZED_UNTIL

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Whether the File editor offers to delete an original after saving it. Written by the dialog's
     * "Don't ask again" and by the Settings switch, read by both; defaults to on, because the offer
     * is what keeps an unprotected copy from being forgotten outside the vault.
     */
    fun isOriginalDeleteOffered(context: Context): Boolean =
        of(context).getBoolean(SettingsKeys.OFFER_DELETE_ORIGINALS, true)

    fun setOriginalDeleteOffered(context: Context, offered: Boolean) {
        of(context).edit().putBoolean(SettingsKeys.OFFER_DELETE_ORIGINALS, offered).apply()
    }

    fun isCloudBackupDisabled(context: Context): Boolean =
        of(context).getBoolean(KEY_DISABLE_CLOUD_BACKUP, false)

    fun setCloudBackupDisabled(context: Context, disabled: Boolean) {
        of(context).edit().putBoolean(KEY_DISABLE_CLOUD_BACKUP, disabled).apply()
    }

    /**
     * Whether a Recovery Kit has ever actually been saved.
     *
     * Defaults to false, which is the safe direction: a vault whose kit state is unknown is treated
     * as one with no kit, so the worst case is telling someone to do something they have already
     * done rather than staying quiet about the only thing that can open a forgotten vault.
     */
    fun isRecoveryKitSaved(context: Context): Boolean =
        of(context).getBoolean(KEY_RECOVERY_KIT_SAVED, false)

    /** Set only by a real export — a written PDF, a completed print, or a captured QR. */
    fun setRecoveryKitSaved(context: Context) {
        of(context).edit().putBoolean(KEY_RECOVERY_KIT_SAVED, true).apply()
    }

    /**
     * Forgets a kit that has stopped working, returning the flag to its "no kit" default.
     *
     * Called when a master-password change rotates the data key, which revokes any existing kit —
     * the 24 words wrap a key that no longer opens this vault. Until this existed the flag had no
     * false path at all, so the Settings row that carries the no-kit debt kept its neutral summary
     * over a kit that opened nothing, and the one-time notice about it died with the ViewModel.
     *
     * `remove` rather than writing false, so the key means what [isRecoveryKitSaved]'s default says
     * it means: absent is "no kit", which is the reading that errs toward telling the user to make
     * one.
     */
    fun forgetRecoveryKitSaved(context: Context) {
        of(context).edit().remove(KEY_RECOVERY_KIT_SAVED).apply()
    }

    /**
     * Forgets the "you haven't exported in a while" clock.
     *
     * Called when a vault is created, because this vault has never been exported anywhere. The
     * clock lives in this file, and this file rides Auto Backup to a new device *even when the
     * vault deliberately does not* — so a replacement phone used to restore an "already exported
     * three days ago" timestamp, route to onboarding because no vault came with it, and then stay
     * silent for up to another 30 days. The one prompt telling the user to make a backup was muted
     * on exactly the install whose vault had no copy anywhere.
     *
     * Removes three keys rather than clearing the file: the auto-lock timeout and the
     * disable-cloud-backup toggle live here too, and wiping those would silently switch cloud
     * backup back on for someone who had turned it off.
     */
    fun forgetExportReminderClock(context: Context) {
        of(context).edit()
            .remove(KEY_LAST_EXPORT_EPOCH_MS)
            .remove(KEY_EXPORT_REMINDER_FIRST_SEEN)
            .remove(KEY_EXPORT_REMINDER_SNOOZED_UNTIL)
            .apply()
    }

    /**
     * Not sourced from [SettingsKeys] like the keys above: nothing outside `:feature:onboarding`
     * reads or writes it yet (unlike the others, which more than one module has to agree on), so it
     * stays private here rather than reaching into `:core:common` for a string only this file uses.
     * Promote it to `SettingsKeys` the day a second reader needs it — see [isCloudBackupChoicePending]'s
     * doc for who that is expected to be.
     */
    private const val KEY_CLOUD_BACKUP_CHOICE_PENDING = "cloud_backup_choice_pending"

    /**
     * True from the moment onboarding creates a vault until [clearCloudBackupChoicePending] records
     * the user's Auto-Backup decision (called from `OnboardingViewModel.chooseCloudBackup`) — i.e.
     * true exactly while a vault exists on disk with nobody yet asked about
     * [KEY_DISABLE_CLOUD_BACKUP].
     *
     * Absence is read as "already chosen", which is the safe default in both directions this flag
     * has to cover:
     *  - **Every vault created before this flag existed** never had [markCloudBackupChoicePending]
     *    called for it, so this reads false forever for those installs. That is deliberate: nothing
     *    may route an already-installed copy of this app back into onboarding just because the flag
     *    is missing (see [markCloudBackupChoicePending]'s doc for why the naive reading is wrong).
     *  - **A future onboarding run that finishes normally** clears the flag before it can ever be
     *    read as pending again.
     *
     * The only vaults this can ever report true for are ones created by a build that also shipped
     * [markCloudBackupChoicePending] — which is exactly the population an interrupted-onboarding
     * fix is allowed to touch.
     *
     * Nothing in `:feature:onboarding` or `:core:vault` currently reads this to change routing —
     * `MainActivity`'s onboarding/unlock decision lives in `:app`, outside this change's module
     * boundary. A consumer there (or a post-unlock banner, the way `StaleExportBanner` already
     * offers a nudge back to `Settings → Backup & export`, which now owns this same switch) is the
     * follow-up that makes a pending choice actually resurface; this flag and its migration-safe
     * default are the part of the fix that is real today.
     */
    fun isCloudBackupChoicePending(context: Context): Boolean =
        of(context).getBoolean(KEY_CLOUD_BACKUP_CHOICE_PENDING, false)

    /**
     * Called once, right after [VaultInitializer.create] writes a fresh vault to disk — never on an
     * existing vault, and never retroactively. That timing is what keeps
     * [isCloudBackupChoicePending] migration-safe: a vault created by code that predates this flag
     * can never have this called for it, so it can never read as pending. Only a vault created by a
     * build that ships this call can.
     */
    fun markCloudBackupChoicePending(context: Context) {
        of(context).edit().putBoolean(KEY_CLOUD_BACKUP_CHOICE_PENDING, true).apply()
    }

    /**
     * Called from [OnboardingViewModel.chooseCloudBackup] the moment the user's Auto-Backup choice
     * is recorded — the same event that already writes [KEY_DISABLE_CLOUD_BACKUP] straight through
     * rather than deferring to [OnboardingViewModel.finish]. `remove` rather than writing false, for
     * the same reason [forgetRecoveryKitSaved] uses `remove`: it keeps "absent" meaning what
     * [isCloudBackupChoicePending]'s default says it means.
     */
    fun clearCloudBackupChoicePending(context: Context) {
        of(context).edit().remove(KEY_CLOUD_BACKUP_CHOICE_PENDING).apply()
    }
}

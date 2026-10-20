package com.localkeep.app

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import com.localkeep.core.vault.VaultLoader
import com.localkeep.feature.backup.BackupPreferences
import java.io.File

/**
 * Conditionally excludes `vault.enc` from Android's full-backup data set when the user has
 * enabled "Disable cloud backup" in Backup Settings (§H). This is the ONLY mechanism that
 * actually stops Auto Backup — the SharedPreferences toggle by itself does nothing without
 * this override.
 *
 * `onFullBackup` is invoked by BOTH the cloud-backup transport (`bmgr backupnow`, Google's
 * backup service) and the device-to-device transfer flow — same method, same enforcement.
 *
 * DEVIATION from the plan: the plan guessed at two prefs file names
 * (`${packageName}_preferences.xml`, a `PreferenceManager`-style default file this app never
 * creates, and `backup_prefs.xml`, from the plan's now-superseded standalone `BackupPreferences`
 * store — see that class's doc comment). The real, single non-secret prefs file this app writes
 * is `:feature:settings`'s `SettingsPreferences` file `localkeep_settings` (holding auto-lock
 * timeout, the disable-cloud-backup bool, and — via [BackupPreferences], which now deliberately
 * shares that same file/key — the last-export timestamp). That is the one file backed up here.
 */
class LocalKeepBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        if (BackupPreferences.isCloudBackupDisabled(applicationContext)) {
            backUpEverythingExceptVault(data)
        } else {
            // Default behavior — applies dataExtractionRules/fullBackupContent's include/exclude
            // rules, which already omit device_keys.bin/blobs/backups (Task 8 Steps 1-2).
            super.onFullBackup(data)
        }
    }

    /**
     * Re-implements the XML rules' inclusion set MINUS `vault.enc` — the one file the
     * disable-toggle exists to omit. Nothing is backed up here unless explicitly added via
     * [fullBackupFile]; there is no implicit default once `super.onFullBackup` is bypassed.
     *
     * It used to add exactly one file, `shared_prefs/localkeep_settings.xml`, while claiming in
     * this very comment to be the XML set minus the vault. The gap was silent and unguarded: a
     * privacy-conscious user's `localkeep_root_warning` and `clipboard_pending` prefs simply fell
     * out of backup, and any file a future version writes would have joined them with nothing in
     * the build noticing. Enumerating the rules instead of a hand-list means the toggle now means
     * only "omit the vault" — and [EXCLUDED_PATHS] is asserted against both XML files by
     * `BackupInclusionSetTest`, so the two cannot drift apart again.
     */
    private fun backUpEverythingExceptVault(data: FullBackupDataOutput) {
        // <include domain="sharedpref" path="."/>
        val root = applicationContext.filesDir.parentFile
        // minus <exclude domain="sharedpref" .../> — see EXCLUDED_SHARED_PREFS.
        File(root, "shared_prefs").listFiles()
            ?.filter { it.name !in EXCLUDED_SHARED_PREFS }
            ?.sortedBy(File::getName)
            ?.forEach { fullBackupFile(it, data) }

        // <include domain="file" path="."/> minus every <exclude>, minus the vault itself.
        addFilesRecursively(applicationContext.filesDir, applicationContext.filesDir, data)
    }

    private fun addFilesRecursively(root: File, dir: File, data: FullBackupDataOutput) {
        for (child in dir.listFiles()?.sortedBy(File::getName).orEmpty()) {
            // Relative to filesDir and slash-suffixed for directories, exactly the shape the XML
            // rules' `path` attribute uses, so the two sets are compared like for like.
            val relative = child.toRelativeString(root).replace(File.separatorChar, '/')
            if (child.isDirectory) {
                if ("$relative/" !in EXCLUDED_PATHS) addFilesRecursively(root, child, data)
            } else {
                if (relative !in EXCLUDED_PATHS && relative != VAULT_FILE) {
                    fullBackupFile(child, data)
                }
            }
        }
    }

    // Legacy key/value backup/restore path — unused. Modern targetSdk always uses the
    // full-data path above; these overrides exist only because BackupAgent requires them.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) {
        // Intentionally empty — see class doc.
    }

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) {
        // Intentionally empty — see class doc.
    }

    internal companion object {
        /**
         * The one file the disable-cloud-backup toggle exists to omit.
         *
         * Taken from `:core:vault` rather than spelled out again. It was a hand-written
         * `"vault.enc"`, one of three copies of a name `AtomicVaultWriter` owns, so a rename there
         * would have compiled cleanly and quietly started including the vault in every
         * toggle-off user's cloud backup. Now a rename turns `BackupInclusionSetTest` red instead,
         * which is the correct answer: the two XML rule files need changing with it.
         */
        internal const val VAULT_FILE = VaultLoader.PRIMARY_CONTAINER_FILE

        /**
         * Every `<exclude domain="file">` path in `data_extraction_rules.xml` and
         * `full_backup_content.xml`, verbatim — directories keep their trailing slash.
         *
         * `BackupInclusionSetTest` parses both XML files and fails if this set and theirs differ in
         * either direction, so adding an exclude to the rules without adding it here (or the
         * reverse) is a build failure rather than a silent difference between what a toggle-off
         * user's backup contains and a toggle-on user's.
         */
        /**
         * `<exclude domain="sharedpref" path="…"/>` in both rules files, mirrored here for the same
         * reason [EXCLUDED_PATHS] is. `unlock_throttle.xml` holds the unlock backoff's failure count
         * and lockout deadline (`core/vault` `UnlockThrottle`): nothing secret, but device-local
         * state — restored onto a new phone it would re-arm the backoff at a high exponent on the
         * first wrong guess there. Asserted against the XML by `BackupInclusionSetTest`.
         */
        internal val EXCLUDED_SHARED_PREFS = setOf("unlock_throttle.xml")

        internal val EXCLUDED_PATHS = setOf(
            "device_keys.bin",
            // The atomic-write sibling of the file above, and excluded for exactly the reason the
            // file above is. `DeviceKeyStore.save` fsyncs `device_keys.bin.tmp` and then renames it;
            // a kill in that window leaves a byte-identical copy of the device-wrapped DEK behind
            // under the `.tmp` name, and nothing ever sweeps it — `load` and `delete` only name
            // `device_keys.bin`, so it survives a restore too. Naming only the base file made the
            // T5 withholding of device-bound key material one filename suffix wide.
            "device_keys.bin.tmp",
            "vault.enc.bak",
            // The *sweeper's* atomic-write sibling of the file above — a third instance of the same
            // gap, and the one that was missed. `StaleContainerSweeper.convert` re-keys
            // `vault.enc.bak` by writing `vault.enc.bak.sweep.tmp` beside it, fsyncing, then
            // renaming over the original; process death in that window leaves a COMPLETE container
            // behind — the previous vault generation, sealed under the user's current master
            // password — and nothing ever removes it. The sweeper's own cleanup pass scans only
            // `backups/` (whose `.sweep.tmp` files are covered by the `backups/` exclusion anyway),
            // and its candidate list never re-lists a `.sweep.tmp`, so the orphan survives every
            // later rotation and save. Android matches `<exclude>` by canonical path rather than
            // string prefix, so `vault.enc.bak` above does not cover this name.
            "vault.enc.bak.sweep.tmp",
            "vault.enc.tmp",
            "blobs/",
            // In-flight replacement journals. They are deleted the moment their transaction ends,
            // so in steady state this directory is empty — but a backup taken mid-replacement would
            // otherwise carry a PREPARED journal off the device, and this agent uploads regardless
            // of whether the user turned cloud backup off.
            "blob-replacements/",
            // The DEK-rotation journal and its atomic-write sibling, for the same reason as
            // blob-replacements above: both exist only while a re-key pass is unfinished, and
            // a backup taken in that window carried them off the device. What they hold is not
            // openable without the new DEK — the retired key inside is wrapped under it — so
            // this excludes metadata (that a rotation stalled, and how many blob refs it still
            // owed) rather than key material. The .tmp is orphaned by a kill between fsync and
            // rename and nothing sweeps it, so naming only the journal would miss half of it.
            "dek-rotation.journal",
            "dek-rotation.journal.tmp",
            "backups/",
            // Copies of a container that would not open, kept aside by the pre-unlock restore
            // flow before it replaces the live one (`PreUnlockRestore.QUARANTINE_DIR`). A
            // directory rather than timestamped siblings of `vault.enc` precisely so it can be
            // named here: Android matches `<exclude>` by canonical path and does no prefix
            // matching, so `vault.enc.damaged-<stamp>` could never have been excluded at all.
            "damaged/",
            "datastore/",
        )
    }
}

package com.localkeep.feature.backup

import android.app.backup.BackupManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells Android that this app's backed-up data changed, so the next eligible Auto Backup pass runs
 * sooner than it otherwise would.
 *
 * The cloud-backup switch wrote its preference and nothing else. Turning it OFF therefore left the
 * `vault.enc` already in Google's backup set sitting there until whenever Android next chose to run
 * a pass, and turning it back ON left the vault out of the set for just as long — in both
 * directions the setting the user had just chosen was not the one in force (S3, sweep
 * `visual-20260915`). `dataChanged()` does not upload anything itself and cannot promise a time; it
 * only marks the app as due, which is why the screen still states the timing rather than implying
 * the change is immediate.
 *
 * Its own type rather than a direct `BackupManager` call so the toggle's use of it is assertable off
 * a device: `android.app.backup.BackupManager` is a platform class with no seam of its own, and a
 * unit test standing in for this one never constructs it.
 */
@Singleton
class BackupDataChangeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun dataChanged() {
        BackupManager(context).dataChanged()
    }
}

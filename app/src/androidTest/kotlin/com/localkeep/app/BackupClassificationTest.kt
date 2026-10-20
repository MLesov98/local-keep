package com.localkeep.app

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The OS must classify this app as a **full-data backup** app.
 *
 * This is the one assertion that would have caught the defect found on real hardware on 2026-07-29,
 * and it asserts the runtime classification rather than the XML, because the XML was never the
 * problem.
 *
 * ### What was wrong
 *
 * Declaring `android:backupAgent` makes an app **key/value** by default. Auto Backup applies to an
 * app with a custom agent only when `android:fullBackupOnly="true"` is also set, and it was not. So
 * the platform put LocalKeep on the key/value path — where [LocalKeepBackupAgent.onBackup] and
 * `onRestore` are *intentionally empty*, because the class was written for the full-data path.
 *
 * The result was that **nothing was backed up at all**. Not the vault, not the settings. On a
 * Galaxy S24 Ultra: `bmgr backupnow` reported `Success`, `bmgr fullbackup` finished in ten
 * milliseconds without ever starting the app process or binding the agent, and a restore reported
 * `restoreFinished: 0` and returned an empty data directory. `dumpsys package` showed
 * `pkgFlags=[ … ALLOW_BACKUP … ]` with no `FULL_BACKUP_ONLY`.
 *
 * Two things follow, and the second is the one that matters more:
 *
 *  1. `vault.enc` never reached Google's backup set — but by accident, not by design.
 *  2. **Users had no cloud backup**, while Settings offered them a switch to turn it off. Every
 *     line of [LocalKeepBackupAgent]'s conditional-exclusion logic was unreachable, and
 *     `BackupInclusionSetTest` passed the whole time, because it compares two static lists and
 *     neither of them is the platform's opinion. That is the `panicLock()` shape again: a feature
 *     wired to nothing, with a green test over it.
 *
 * ### Why this assertion and not another
 *
 * `FLAG_FULL_BACKUP_ONLY` is the platform's own answer to "will Auto Backup call this agent". A
 * test that parsed the manifest would restate the fix; this one restates the *requirement*, and it
 * fails if a future manifest merge, flavour overlay or library drops the attribute.
 */
@RunWith(AndroidJUnit4::class)
class BackupClassificationTest {

    @Test
    fun theAppIsRegisteredForFullDataBackup() {
        val info = ApplicationProvider.getApplicationContext<Context>().applicationInfo

        assertTrue(
            info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
            "allowBackup must stay on — the vault is meant to be recoverable",
        )
        assertTrue(
            info.flags and ApplicationInfo.FLAG_FULL_BACKUP_ONLY != 0,
            "the app declares a backupAgent, so without android:fullBackupOnly the platform " +
                "treats it as key/value and never calls onFullBackup — nothing gets backed up",
        )
    }
}

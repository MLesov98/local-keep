package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * A brand-new vault must not inherit the previous device's "already exported" clock.
 *
 * `localkeep_settings` travels in Auto Backup *even when the vault deliberately does not* — the
 * backup agent omits `vault.enc` for a disable-cloud-backup user and still ships the prefs file. So
 * a replacement phone restored that prefs file, found no vault, routed to onboarding, and the fresh
 * vault started life believing it had been exported three days ago: no reminder for up to another
 * 30 days, on the one install whose vault has no copy anywhere.
 *
 * Instrumented rather than a unit test because the thing under test is SharedPreferences, and a
 * stub for it would be asserting against the stub.
 */
@RunWith(AndroidJUnit4::class)
class VaultInitializerReminderClockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val prefs = context.getSharedPreferences("localkeep_settings", Context.MODE_PRIVATE)

    @AfterTest
    fun cleanup() {
        File(context.filesDir, AtomicVaultWriter.VAULT_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.BACKUP_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.TEMP_FILE).delete()
        prefs.edit().clear().commit()
    }

    @Test
    fun creatingAVaultForgetsARestoredExportClock() = runBlocking {
        // Exactly what a restore leaves behind: an export timestamp, a first-seen anchor and a
        // live snooze, all from a device this vault has never been on.
        prefs.edit()
            .putLong("last_export_epoch_millis", 1_700_000_000_000L)
            .putLong("export_reminder_first_seen", 1_600_000_000_000L)
            .putLong("export_reminder_snoozed_until", Long.MAX_VALUE)
            .commit()

        VaultInitializer.create(context, "correct horse battery staple".toCharArray())

        assertFalse(prefs.contains("last_export_epoch_millis"), "the export clock must not survive")
        assertFalse(prefs.contains("export_reminder_first_seen"))
        assertFalse(prefs.contains("export_reminder_snoozed_until"))
    }

    /**
     * The same restore, carrying the other flag that means "this vault is already looked after".
     *
     * `recovery_kit_saved` is written only by a real export and, before this, was cleared only by a
     * master-password change. So a restored `true` from the old device outlived vault creation, and
     * a user who deferred the kit step lost the one persistent reminder that they have no way back
     * into this vault: the Settings → Recovery Kit row's warning.
     */
    @Test
    fun creatingAVaultForgetsARestoredRecoveryKitFlag() = runBlocking {
        prefs.edit().putBoolean("recovery_kit_saved", true).commit()

        VaultInitializer.create(context, "correct horse battery staple".toCharArray())

        assertFalse(
            prefs.contains("recovery_kit_saved"),
            "a kit saved on another device is not a kit for this vault",
        )
    }

    /**
     * The counter-case. These three keys share `localkeep_settings` with the auto-lock timeout and
     * the disable-cloud-backup toggle, and clearing the file wholesale would silently switch cloud
     * backup back on for someone who had turned it off — turning a reminder fix into a privacy
     * regression.
     */
    @Test
    fun creatingAVaultLeavesTheOtherSettingsInThatFileAlone() = runBlocking {
        prefs.edit()
            .putBoolean("disable_cloud_backup", true)
            .putString("auto_lock_timeout", "AGGRESSIVE")
            .commit()

        VaultInitializer.create(context, "correct horse battery staple".toCharArray())

        assertEquals(true, prefs.getBoolean("disable_cloud_backup", false))
        assertEquals("AGGRESSIVE", prefs.getString("auto_lock_timeout", null))
    }
}

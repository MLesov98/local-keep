package com.localkeep.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A brand-new vault must not inherit the previous device's "kit already saved" flag.
 *
 * Same mechanism as the export clock this file's sibling assertion pins: `localkeep_settings` rides
 * Auto Backup to a replacement phone *even when the vault deliberately does not*, so a restored
 * prefs file can carry `recovery_kit_saved=true` from an install whose 24 words open nothing here.
 * The user then routes to onboarding, creates a fresh vault, defers the kit step — and the one
 * persistent no-kit reminder, the Settings → Recovery Kit row, never appears, because deferring
 * writes nothing and nothing else in the app ever cleared the flag.
 *
 * Asserted against the source rather than against SharedPreferences because this module's unit tests
 * have no Android runtime; the behavioural twin runs on a device in
 * `VaultInitializerReminderClockTest`.
 */
class VaultInitializerRecoveryKitFlagTest {

    @Test
    fun `creating a vault forgets a restored recovery-kit flag`() {
        val source = File("src/main/kotlin/com/localkeep/core/vault/VaultInitializer.kt").readText()
        val afterWrite = source.substringAfter("AtomicVaultWriter.write(appContext, withRecovery)")
        assertTrue(
            "LocalKeepSettings.forgetRecoveryKitSaved(appContext)" in afterWrite,
            "create() must forget a restored recovery_kit_saved after writing the vault. A vault " +
                "that has just been created cannot have a saved kit, so a flag surviving from " +
                "another device leaves the Settings row silently claiming one exists.",
        )
    }
}

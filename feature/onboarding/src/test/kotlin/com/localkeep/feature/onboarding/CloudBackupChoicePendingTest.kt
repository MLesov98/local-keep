package com.localkeep.feature.onboarding

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.vault.LocalKeepSettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An interrupted onboarding run must not leave the Auto-Backup choice silently defaulted, and a
 * fix for that must not route every already-installed vault back into onboarding on upgrade.
 *
 * `OnboardingViewModel.chooseCloudBackup` is the only place `LocalKeepSettings.setCloudBackupDisabled`
 * is ever written from onboarding, and `Step.CLOUD_BACKUP` sits after vault creation, biometric
 * enrollment and the Recovery Kit ceremony. A user who kills the app after the vault is created on
 * disk but before reaching that step is never asked, and the stored default
 * (`isCloudBackupDisabled` -> false) silently keeps Auto Backup on.
 *
 * `LocalKeepSettings.isCloudBackupChoicePending` exists to make that interruption detectable by a
 * future consumer (see its own doc for why nothing in this module or `:core:vault` reads it yet —
 * that consumer lives in `:app`, outside this fix's module boundary). What this test pins is the
 * part that is real today: the flag is set exactly when a fresh vault is created, cleared exactly
 * when the choice is actually recorded, and — the load-bearing case — reads false by default, so a
 * vault created before this flag existed can never be read as having a choice pending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudBackupChoicePendingTest {

    private fun viewModelSource(): String =
        File("src/main/kotlin/com/localkeep/feature/onboarding/OnboardingViewModel.kt").readText()

    @Test
    fun `an install that never ran the marking code reads as already chosen`() {
        // The load-bearing case. Every vault created before this flag existed never had
        // markCloudBackupChoicePending called for it, and never will — this simulates that by
        // simply never calling it. If this ever reads true by default, every existing install
        // would be routed back into onboarding by a future consumer of this flag.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(
            LocalKeepSettings.isCloudBackupChoicePending(context),
            "a vault with no marking call must never read as having a pending cloud-backup choice " +
                "— that is what keeps existing installs from being routed back into onboarding.",
        )
    }

    @Test
    fun `marking then clearing round-trips through the real preference`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        LocalKeepSettings.markCloudBackupChoicePending(context)
        assertTrue(
            LocalKeepSettings.isCloudBackupChoicePending(context),
            "precondition: a freshly created vault has not yet had the choice made",
        )

        LocalKeepSettings.clearCloudBackupChoicePending(context)

        assertFalse(
            LocalKeepSettings.isCloudBackupChoicePending(context),
            "the flag must clear once the choice is actually recorded, or every future launch " +
                "would read this vault as still owing the question",
        )
    }

    @Test
    fun `vault creation marks the choice pending before the vault can be interrupted out of`() {
        val body = viewModelSource()
            .substringAfter("val kit = try {")
            .substringBefore("\n            // Unlock with what the user just typed twice")
        assertTrue(
            "LocalKeepSettings.markCloudBackupChoicePending(context)" in body,
            "createVault must mark the choice pending once the vault exists on disk, or an app " +
                "kill between creation and Step.CLOUD_BACKUP leaves no record that the question " +
                "was ever owed. Body: $body",
        )
        // Must be on the success path (after VaultInitializer.create returns), not inside either
        // catch block — the vault does not exist on either failure branch, so nothing is owed yet.
        val creationFailedCatch = body
            .substringAfter("} catch (_: Exception) {")
            .substringBefore("return@launch")
        assertFalse(
            "markCloudBackupChoicePending" in creationFailedCatch,
            "the flag must not be set when vault creation itself failed — no vault exists to owe " +
                "the choice for. Catch body: $creationFailedCatch",
        )
    }

    @Test
    fun `choosing cloud backup clears the pending flag`() {
        val body = viewModelSource()
            .substringAfter("fun chooseCloudBackup(disabled: Boolean) {")
            .substringBefore("\n    }")
        assertTrue(
            "LocalKeepSettings.clearCloudBackupChoicePending(context)" in body,
            "chooseCloudBackup must clear the pending flag once the choice is recorded, or every " +
                "future launch of this vault would keep reading it as still owed. Body: $body",
        )
    }
}

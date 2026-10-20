package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A vault whose owner never reached onboarding's Android-backup step is asked once.
 *
 * Onboarding writes that choice at `Step.CLOUD_BACKUP`, which comes after the vault is already on
 * disk — after the password, after biometrics, after the Recovery Kit ceremony. Kill the app in
 * between and `MainActivity.startRoute` sends every later launch to UNLOCK, because vault files
 * exist; onboarding never runs again, and the choice stays at its shipped default. That default
 * decides whether vault ciphertext reaches Android Auto Backup, so it is the one setting in the app
 * that should not be made on the user's behalf without the question ever appearing.
 *
 * `LocalKeepSettings` carries the pending flag across the interruption. This test exists because a
 * flag nothing reads is worse than no flag: it looks like the problem has been handled. The rule in
 * this repo is wire it or delete it, and the same absence — `unlockWithRecoveryKit` written, tested
 * and never called — is what the QR redemption path had to fix in this same wave.
 *
 * The migration hazard is the reason this needed a decision rather than a patch: the flag is absent
 * on every install that predates it, and a naive "absent means unasked" would route the entire
 * existing user base back into onboarding on upgrade. Absence reads as already-chosen; only vaults
 * created after the flag exists can be pending.
 */
class CloudBackupChoiceIsAskedOnceTest {

    private val activity: String
        get() {
            val relative = "src/main/kotlin/com/localkeep/app/MainActivity.kt"
            val direct = File(relative)
            val file = if (direct.isFile) direct else File(repoRoot(), "app/$relative")
            if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
            return file.readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        }

    @Test
    fun `the pending flag is read after unlock and leads to the switch`() {
        val text = activity
        assertTrue(
            "LocalKeepSettings.isCloudBackupChoicePending(this@MainActivity)" in text,
            "the flag onboarding writes must actually be read somewhere, or it records a question " +
                "nobody will ever be asked",
        )
        assertTrue(
            "R.string.app_notice_cloud_backup_unchosen" in text &&
                "navController.navigate(AppNavGraph.BACKUP)" in text,
            "the notice must carry an action to the screen that owns the switch; telling someone " +
                "there is an unmade decision without a route to it is worse than silence",
        )
    }

    @Test
    fun `it is asked once, not at every unlock`() {
        val text = activity
        assertTrue(
            "LocalKeepSettings.clearCloudBackupChoicePending(this@MainActivity)" in text,
            "the flag must be cleared where it is shown, or the prompt returns at every unlock " +
                "about a setting the user may have deliberately left alone",
        )
    }

    /**
     * The control, and it must stay green while the two above go red.
     *
     * Routing is the thing this change must not touch. An existing install has no flag, so it must
     * take exactly the path it took yesterday: vault files present means UNLOCK, and nothing about
     * an unanswered question may send anyone back into onboarding — that is the migration hazard
     * that made this a decision rather than a patch, and it lives in this `when`.
     */
    @Test
    fun `an existing vault still routes straight to unlock`() {
        val text = activity
        assertTrue(
            "VaultLoader.hasVaultFiles(this) -> AppNavGraph.UNLOCK" in text,
            "a vault on disk must still go to the unlock screen",
        )
        val startRoute = text.substringAfter("val startRoute = when {").substringBefore("}")
        assertTrue(
            "CloudBackupChoicePending" !in startRoute,
            "start routing must not consult the pending flag: it is absent on every install made " +
                "before it existed, so a naive reading sends the entire existing user base back " +
                "through onboarding. Found:\n$startRoute",
        )
    }

    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("repo root not found above ${File("").absolutePath}")
}

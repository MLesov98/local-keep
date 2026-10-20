package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two controls on the Settings screen that stayed live while another operation held `isWorking`.
 *
 * **Remove duress.** `removeDuress` opens with a bare `if (isDuressSession || isWorking) return`,
 * which writes neither a message nor an error. So while a master-password change or a duress setup
 * was still running, the destructive confirmation still opened, the user answered it, it closed —
 * and nothing happened. The row deliberately never reveals whether a decoy is configured (the vault
 * header carries two slots either way, so "the app genuinely cannot know"), which means the
 * transient status note is this control's *only* feedback channel and there was no second way to
 * notice: the coercion escape hatch the user believed they had just removed was still armed. The
 * identical defect on `BackupSettingsScreen`'s Replace button was fixed, with tests, in an earlier
 * sweep; this is the same fix on the sibling control, including the dialog half — the removal
 * flag lives on the ViewModel ([DuressFormState]), so it outlives the composition its button lives
 * in and a configuration change can bring the dialog back over a busy screen.
 *
 * **The cloud-backup switch** was the second, and it is gone rather than gated: this screen owned
 * a duplicate of the switch on Backup & export, over the same preference, spelled with the
 * opposite polarity. It navigates there now, so the in-flight question no longer arises and what
 * is asserted instead is that the duplicate has not come back.
 *
 * Neither change touches the duress row's own constraint, stated in the screen itself: no control
 * here may become conditional on duress *configuration* state, and the whole row is withheld in a
 * decoy session. `isWorking` is raised by every Settings operation and reveals nothing about
 * whether a decoy exists.
 *
 * Asserted on the source: `:feature:settings` declares Robolectric but no Compose UI test
 * dependency. Same idiom as [HiddenItemsSurfaceTest] and `feature/backup`'s
 * `ReplaceControlIsWorkingGateTest`.
 */
class SettingsInFlightControlsTest {

    /**
     * The screen with its comments removed.
     *
     * This codebase documents why each decision was taken, so the comment beside a gate quotes the
     * gate — and a scan that cannot tell prose from code would pass with the fix reverted. Same
     * reasoning, same treatment, as [HiddenItemsSurfaceTest].
     */
    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    @Test
    fun `the duress removal button is disabled while an operation is in flight`() {
        val block = screen.substringAfter("R.string.settings_duress_remove_button")
            .substringBefore("confirmRemoval = true")
        assertTrue(
            block.contains("enabled = !state.isWorking"),
            "the confirmation opened over a busy screen and the confirmed tap died silently at " +
                "removeDuress's isWorking return; found: $block",
        )
    }

    @Test
    fun `the duress removal confirmation does not reappear over a busy screen`() {
        assertTrue(
            screen.contains("if (duressForm.confirmRemoval && !state.isWorking)"),
            "the removal flag lives on the ViewModel and outlives the composition its button " +
                "lives in, so a restore into a busy state brings the dialog back past that gate",
        )
    }

    @Test
    fun `settings routes to the cloud-backup switch instead of owning a second one`() {
        // This screen used to carry its own LkSwitchRow over the `disable_cloud_backup`
        // preference, two rows below the row that opens the screen holding the other one. Two
        // writers, neither observing the shared value, and the two spelled the setting opposite
        // ways: the Backup screen asks whether the vault is *included*, this one asked whether
        // cloud backup is *disabled*, so one accent colour meant opposite things about one
        // preference on two screens a tap apart. That is D19's uncounted second instance.
        //
        // Now the row navigates. There is one writer, so there is nothing to keep in sync and no
        // polarity to disagree about - which is why the re-read that used to sit beside the
        // Recovery Kit one is gone rather than broken.
        assertTrue(
            "R.string.settings_cloud_backup_switch_label" !in screen &&
                "setDisableCloudBackup" !in screen,
            "Settings must not own a second cloud-backup switch; the one on Backup & export is " +
                "the single writer of that preference",
        )
        val anchor = "R.string.settings_cloud_backup_title"
        assertTrue(
            anchor in screen,
            "anchor '$anchor' is gone from SettingsScreen.kt - update it, or the assertion below " +
                "is being made against the whole file",
        )
        // A fixed window rather than a `substringBefore("),")`: the summary line directly under
        // the title ends in exactly that, so cutting on it stops one line into the entry and the
        // assertion below reads nothing. Length, not punctuation, is the safe cut here.
        val block = screen.substringAfter(anchor).take(300)
        assertTrue(
            block.contains("onClick = onBackupClick"),
            "the row must lead to the screen that holds the switch, or it is a dead label; " +
                "found: $block",
        )
    }

    @Test
    fun `the recovery-kit re-read that established the pattern is still there`() {
        // The control. Removing the cloud-backup switch removed the re-read that rode beside this
        // one on the same trigger; this asserts the LaunchedEffect itself survived that deletion,
        // rather than being taken wholesale along with it.
        assertTrue(
            screen.contains("viewModel.refreshRecoveryKitState()"),
            "the Recovery Kit re-read is the precedent the cloud-backup one rides on; losing it " +
                "means the shared LaunchedEffect has gone",
        )
    }

    @Test
    fun `the duress row keeps the two properties this change must not touch`() {
        // Controls, both of them security properties rather than gates. The row is withheld
        // entirely in a decoy session, and the setup prompt is unconditional — asking only when a
        // decoy exists would answer, out loud, the question the feature exists to leave open.
        assertTrue(
            screen.contains("if (state.isDuressSession) {"),
            "the duress entry must stay absent — not disabled — in a decoy session",
        )
        val setup = screen.substringAfter("text = setupDuressLabel")
            .substringBefore("confirmSetup = true")
        // The gate's terms moved into `duressSetupReady` (SettingsFormGates.kt, pinned by
        // SettingsFormGatesTest) when the length floor joined them; the button must still be gated
        // on the in-flight state and on the confirm field through it.
        assertTrue(
            setup.contains("duressSetupReady(") &&
                setup.contains("isWorking = state.isWorking") &&
                setup.contains("confirmPassword = duressForm.confirmPassword"),
            "the Setup button's own gate is the pattern Remove was measured against and is not " +
                "this change's to move; found: $setup",
        )
    }
}

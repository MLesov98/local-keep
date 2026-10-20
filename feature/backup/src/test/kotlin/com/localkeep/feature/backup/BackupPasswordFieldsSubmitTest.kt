package com.localkeep.feature.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every password well on the Backup screen submits from the keyboard's Done key.
 *
 * All four — the export master password, the independent export passphrase, the import password
 * and the KDF re-wrap password — passed no `onImeAction`, so `LkPasswordField` fell back to
 * `KeyboardActions.Default`, which for `ImeAction.Done` only hides the keyboard. Someone driving
 * the app with a hardware keyboard, a DPAD or switch access then had a separate navigate-and-
 * activate step on every export, every import and every recalibration — on the screen with more
 * password entry concentrated in one place than any other in the app, and against the app's own
 * convention everywhere else: `UnlockScreen`, `HideFlow`, `RecoveryKitScreen`, the import wizard
 * and both onboarding fields all wire Done straight to their action.
 *
 * Each handler carries the same gate as the button beside it (the `HideFlow` idiom), so Done can
 * never start an operation the visible control refuses — and `exportEnabled` is hoisted out of the
 * Export button for exactly that reason, so the button and the two wells above it cannot drift into
 * disagreeing about when an export is allowed.
 *
 * Asserted on the source rather than by driving the composable, for the reason
 * [ExportPassphraseGateTest] states: `:feature:backup` declares no Compose UI test dependency.
 */
class BackupPasswordFieldsSubmitTest {

    private val screen =
        File("src/main/kotlin/com/localkeep/feature/backup/BackupSettingsScreen.kt").readText()

    /**
     * One password well's parameters: everything after its `label =` line, bounded at whichever
     * composable follows it.
     *
     * Bounded rather than open-ended because the KDF well is followed immediately by the Update
     * button, whose `onClick` says the same thing the well's `onImeAction` has to — so an unbounded
     * block would pass with the fix reverted.
     */
    private fun fieldBlockAfter(key: String): String =
        screen.substringAfter("label = stringResource($key)")
            .substringBefore("LkPasswordField(")
            .substringBefore("LkPrimaryButton(")
            .substringBefore("LkSecondaryButton(")

    @Test
    fun `both export wells fire the export the button would`() {
        listOf(
            "R.string.backup_export_master_password_field_label",
            "R.string.backup_export_passphrase_label",
        ).forEach { label ->
            val block = fieldBlockAfter(label)
            // Both halves, separately: `onImeAction` alone would pass with the body emptied, and
            // the call alone would pass with the button's gate dropped off the well. The action is
            // `requestExportDestination` rather than the launcher since F-39 moved the
            // master-password check in front of the destination picker.
            assertTrue(
                block.contains("if (exportEnabled)") &&
                    block.contains("viewModel.requestExportDestination(masterPassword.toCharArray())"),
                "Done only hid the keyboard on $label; found: $block",
            )
        }
    }

    @Test
    fun `the import well opens the picker the button would`() {
        val block = fieldBlockAfter("R.string.backup_import_password_field_label")
        assertTrue(
            block.contains("onImeAction = {") && block.contains("importLauncher(arrayOf(\"*/*\"))"),
            "Done only hid the keyboard on the import password; found: $block",
        )
        assertTrue(
            block.contains("importPassword.isNotEmpty() && !state.isWorking"),
            "Done must carry the same gate as the Choose-file button, or it starts a pick the " +
                "button itself refuses; found: $block",
        )
    }

    @Test
    fun `the recalibration well applies the update the button would`() {
        val block = fieldBlockAfter("R.string.backup_kdf_master_password_field_label")
        assertTrue(
            block.contains("viewModel.applyKdfRecalibration(password.toCharArray())"),
            "Done only hid the keyboard on the KDF password; found: $block",
        )
        assertTrue(
            block.contains("password = \"\""),
            "the ViewModel takes ownership of the array and wipes it, so the field must not keep " +
                "the string behind it — the button's own onClick clears it for the same reason; " +
                "found: $block",
        )
    }

    @Test
    fun `the export button and its wells read one gate`() {
        // Control, and the reason the gate was hoisted at all: two copies of this expression is
        // how a Done key that exports on a below-floor passphrase gets written.
        assertTrue(
            screen.contains("val exportEnabled = masterPassword.isNotEmpty()"),
            "exportEnabled is what the wells and the button share",
        )
        assertTrue(
            screen.contains("exportPassphraseScore >= StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE"),
            "the strength floor on the independent export passphrase survived the hoist",
        )
        val button = screen.substringAfter("R.string.backup_export_button")
            .substringBefore("onClick = { exportLauncher(\"vault-export.vault\") }")
        assertTrue(
            button.contains("enabled = exportEnabled"),
            "found: $button",
        )
    }
}

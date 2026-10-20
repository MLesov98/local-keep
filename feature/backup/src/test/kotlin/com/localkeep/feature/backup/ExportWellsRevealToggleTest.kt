package com.localkeep.feature.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for 1C-O10 (release sweep 2026-09-20): the export passphrase was typed blind.
 *
 * It is a secret the user is CREATING — typed once, with no confirm field and nothing anywhere that
 * shows it again — so a typo produces a `.vault` that nobody, including its author, can ever open.
 * `LkPasswordField` has taken `revealed` / `onToggleReveal` since the unlock screen needed them;
 * this well simply passed neither. The master-password well beside it gets the same control, both
 * because the unlock screen already reveals that exact secret and because two adjacent wells where
 * only one carries an eye read as a defect of the other.
 *
 * Asserted on the source rather than by driving the composable, for the reason
 * [BackupPasswordFieldsSubmitTest] states: `:feature:backup` declares no Compose UI test dependency.
 */
class ExportWellsRevealToggleTest {

    private val screen =
        File("src/main/kotlin/com/localkeep/feature/backup/BackupSettingsScreen.kt").readText()

    /** Everything after a well's `label =` line, bounded at whichever composable follows it. */
    private fun fieldBlockAfter(key: String): String =
        screen.substringAfter("label = stringResource($key)")
            .substringBefore("LkPasswordField(")
            .substringBefore("LkPrimaryButton(")
            .substringBefore("LkSecondaryButton(")

    @Test
    fun `both export wells can be unmasked`() {
        listOf(
            "R.string.backup_export_master_password_field_label",
            "R.string.backup_export_passphrase_label",
        ).forEach { label ->
            val block = fieldBlockAfter(label)
            // Both halves, separately: `revealed =` alone would pass with a constant `false` and no
            // way to change it, and `onToggleReveal =` alone would pass with the well still drawing
            // bullets whatever the toggle said.
            assertTrue(
                block.contains("revealed = ") && block.contains("onToggleReveal = "),
                "$label is typed blind; found: $block",
            )
        }
    }

    /**
     * The control that must stay green: a revealed secret may not be written into saved instance
     * state, so the flags are `remember`, never `rememberSaveable`. Coming back from the background
     * with the well re-masked is the safe direction, and it is what the unlock screen does.
     */
    @Test
    fun `the reveal flags do not survive into saved state`() {
        listOf("masterPasswordRevealed", "exportPassphraseRevealed").forEach { flag ->
            assertTrue(
                screen.contains("var $flag by remember { mutableStateOf(false) }"),
                "$flag must be held in `remember`, not `rememberSaveable`",
            )
        }
    }
}

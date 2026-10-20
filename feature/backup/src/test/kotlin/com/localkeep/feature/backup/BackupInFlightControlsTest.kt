package com.localkeep.feature.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the Backup screen does while an operation is in flight: which controls it withholds, what it
 * says, and — the part that had no answer at all — how the user gets off it.
 *
 * Three findings from the 2026-09-07 sweep land on this one screen.
 *
 * **The cloud-backup switch was the last control here with no `isWorking` gate.** Every button on
 * the screen has one; this switch did not, and `setDisableCloudBackup` writes `status`
 * unconditionally with no guard of its own, so a toggle during an export replaced "Exporting…" with
 * its own confirmation and the export's completion replaced that back a moment later. The toggle
 * was never acknowledged and the in-flight note disappeared for the gap. The preference write was
 * always correct — this is the status line, not the setting.
 *
 * **Nothing was announced while the unlock-speed check ran.** Export, import, merge, replace and
 * restore each write their own status, and that note carries the live-region announcement.
 * `checkKdfCalibration` writes none, so the sole feedback for a multi-second Argon2id derivation
 * was the button dimming — which a screen reader is not told about.
 *
 * **Back was blocked during an export, and there was nowhere else to go.** Backup is outside
 * `AppShell`'s `shellRoutes`, so it composes no drawer and no bottom bar; the header chevron was
 * gated on the same flag as the `BackHandler`; and the SAF copy at the end of an export is a
 * blocking stdlib stream loop with no timeout, which its own KDoc says "can be an upload to a cloud
 * provider and takes as long as the network does". A stalled provider left force-stopping the app
 * as the only exit. An export never touches the local vault, so the guard was protecting nothing
 * there — [BackupSettingsUiState.blocksExit] is the distinction the flag was missing.
 *
 * Asserted on the source rather than by driving the composable, for the reason
 * [ExportPassphraseGateTest] states: `:feature:backup` declares no Compose UI test dependency.
 */
class BackupInFlightControlsTest {

    private companion object {
        const val ANCHOR = "R.string.backup_cloud_include_label"
    }

    private val screen =
        File("src/main/kotlin/com/localkeep/feature/backup/BackupSettingsScreen.kt").readText()

    /** The `LkSwitchRow(...)` that draws the cloud-backup toggle, from its label to `first =`. */
    private fun cloudSwitchBlock(): String {
        // Asserted, not assumed. `substringAfter` returns the WHOLE receiver when its delimiter is
        // absent, so a renamed label resource does not break this helper - it silently widens it to
        // the entire file and the test below then passes on some unrelated `enabled =` line. That
        // is exactly what happened when the switch was inverted on 2026-09-09 and
        // `backup_cloud_disable_label` became `backup_cloud_include_label`: the isolation this
        // helper's own KDoc promises was gone for a day, and nothing failed to say so.
        assertTrue(
            ANCHOR in screen,
            "The cloud-backup switch's label resource has been renamed away from $ANCHOR. Update " +
                "this anchor: without it `substringAfter` returns the whole file and every " +
                "assertion below is meaningless.",
        )
        return screen.substringAfter(ANCHOR).substringBefore("first = true,")
    }

    @Test
    fun `the cloud-backup switch is disabled while an operation is in flight`() {
        val block = cloudSwitchBlock()
        assertTrue(
            block.contains("enabled = !state.isWorking"),
            "The cloud switch overwrote an in-flight operation's status line with its own " +
                "confirmation, which that operation's completion then overwrote back; found: $block",
        )
    }

    @Test
    fun `an operation with no status of its own still announces that it is running`() {
        assertTrue(
            screen.contains("if (state.isWorking && state.status == null)"),
            "the unlock-speed check sets only isWorking, so without this branch a screen reader " +
                "is told nothing at all while a real Argon2id derivation runs",
        )
        assertTrue(
            screen.contains("R.string.backup_working"),
            "the in-flight note needs a string; a hardcoded one would fail checkNoHardcodedUiStrings",
        )
        // The announcement is the whole point of the note — an inline note with no live region
        // would be the same silence in a different shape.
        assertTrue(
            screen.substringAfter("R.string.backup_working").substringBefore("}")
                .contains("Modifier.announce(working)"),
            "the note has to be pinned to a live region or it is silent to TalkBack",
        )
    }

    @Test
    fun `the check that writes no status also clears a stale one`() {
        val viewModel =
            File("src/main/kotlin/com/localkeep/feature/backup/BackupSettingsViewModel.kt").readText()
        val start = viewModel.substringAfter("fun checkKdfCalibration()")
            .substringBefore("viewModelScope.launch")
        // The whole `copy(...)`, not the clause: this file explains its decisions in prose, so
        // `status = null` appears in the comment above the line as well, and a scan that cannot
        // tell the two apart would pass with the fix reverted.
        assertTrue(
            start.contains("copy(isWorking = true, error = null, status = null)"),
            "a completed export's \"Exported 4.5 MB\" otherwise sits on screen for the whole " +
                "derivation, describing an operation that has finished and suppressing the " +
                "in-flight note that keys off status == null; found: $start",
        )
    }

    @Test
    fun `an export can be left, a vault-rewriting operation cannot`() {
        // Read as terms rather than as the exact line. This used to assert the literal
        // `BackHandler(enabled = state.blocksExit)`, which stopped being the whole condition on
        // 2026-09-13 (F37): a staged `.vault` restore now arms the handler too, so that it can
        // raise a discard confirmation instead of dropping the restore in silence. The claim this
        // test is *for* is unchanged and still checked — an in-flight export must not swallow Back,
        // and the term that decides that is `blocksExit`, which excludes an export by construction
        // (pinned directly two tests below). What must never come back is `isWorking`.
        val backHandler = screen.substringAfter("BackHandler(enabled = ").substringBefore("\n")
        assertTrue(
            backHandler.startsWith("state.blocksExit"),
            "Back was swallowed for the whole of an export, whose SAF copy has no timeout and " +
                "nothing a cancellation can land on, on a screen with no drawer and no bottom " +
                "bar; found: $backHandler",
        )
        assertFalse(
            backHandler.contains("isWorking"),
            "gating Back on isWorking is the defect blocksExit exists to fix; found: $backHandler",
        )
        val header = screen.substringAfter("R.string.backup_screen_back")
            .substringBefore("plateAlignment")
        assertTrue(
            header.contains("enabled = !state.blocksExit"),
            "the chevron is the other half of the same exit; gating it on isWorking while the " +
                "BackHandler uses blocksExit would leave the screen half-escapable; found: $header",
        )
    }

    @Test
    fun `blocksExit excludes an export and nothing else`() {
        // The predicate itself, rather than its spelling in the screen. `isWorking` alone is what
        // every other operation raises, so this is the whole of the behaviour change.
        assertFalse(BackupSettingsUiState().blocksExit, "an idle screen was never blocked")
        assertFalse(
            BackupSettingsUiState(isWorking = true, isExporting = true).blocksExit,
            "an export never touches the local vault, so leaving mid-export loses nothing",
        )
        assertTrue(
            BackupSettingsUiState(isWorking = true).blocksExit,
            "a merge, replace, restore or KDF re-wrap rewrites the vault and must not be " +
                "abandoned part-way",
        )
    }

    @Test
    fun `the controls that were already gated keep their gates`() {
        // Controls. None of these moved, and this change is not theirs to move — the flag they read
        // is deliberately still `isWorking`, not `blocksExit`: an in-flight export must keep the
        // screen's own actions dim even though it no longer holds the user on the screen.
        listOf(
            "R.string.backup_kdf_check_button" to "onClick = viewModel::checkKdfCalibration",
            "R.string.backup_import_merge_button" to "onClick = { viewModel.confirmImport(ImportMode.MERGE) }",
            "R.string.backup_import_cancel_button" to "onClick = viewModel::cancelImport",
            "R.string.backup_snapshot_restore_button" to "onClick = { confirmRestorePath = file.path }",
        ).forEach { (label, terminator) ->
            val block = screen.substringAfter(label).substringBefore(terminator)
            assertTrue(
                block.contains("enabled = !state.isWorking"),
                "$label lost its in-flight gate; found: $block",
            )
        }
    }

    @Test
    fun `the replace-import confirmation cannot outlive the vault it would replace`() {
        // Red before the fix. `confirmReplace` is `rememberSaveable` and survives process death;
        // the staged `pendingImportVault` lives in the ViewModel with no `SavedStateHandle` and
        // does not. Restored from a process death the dialog came back over nothing, and
        // `confirmImport` opens with `pendingImportVault ?: return` - so Confirm dismissed a dialog
        // about destroying the vault and did nothing: no work, no status, no error. A destructive
        // confirmation that silently no-ops is worse than one that fails, because nothing gives the
        // user a reason to doubt it worked.
        assertTrue(
            screen.contains("confirmReplace && !state.isWorking && state.pendingImportVault != null"),
            "the replace dialog must be gated on the staged vault still existing, not on the " +
                "saveable flag alone",
        )
    }
}

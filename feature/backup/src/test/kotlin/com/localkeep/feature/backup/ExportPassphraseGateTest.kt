package com.localkeep.feature.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checking "Use a separate export passphrase" and leaving the well empty must not produce a
 * master-password export reported as a plain success.
 *
 * `BackupSettingsScreen`'s export launcher hands `exportPassphrase.toCharArray()` to
 * [BackupSettingsViewModel.export] whenever the checkbox is on, and an empty string makes that an
 * empty — not null — `CharArray`. `export` reads empty as "no passphrase" and falls through to
 * `exportWithMasterPassword`, then reports the ordinary "Exported 4.5 MB" success. The user asked
 * for a file protected by a passphrase they chose and received one protected by their master
 * password *as it was at export time*: rotate the master password afterwards, forget the old one,
 * and the backup is unopenable by anyone, including its owner.
 *
 * The gate is the same idiom Import a few lines below already uses and documents — an empty field
 * must not be allowed to spend a whole file pick and an Argon2id derivation before failing.
 *
 * Asserted on the source rather than by driving the composable: `:feature:backup` has no
 * Compose UI test dependency (`build.gradle.kts` declares none, and that file is outside this
 * change's scope), so there is no way to observe the button's `enabled` at runtime from this
 * module. `AuthActivityRepromptLockTest` reads its own activity's source for the same reason.
 */
class ExportPassphraseGateTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/backup/BackupSettingsScreen.kt").readText()

    /**
     * A slice bounded at both ends, or a loud failure.
     *
     * `substringAfter`/`substringBefore` return the *whole* receiver when the delimiter is absent,
     * so a renamed anchor silently widens the slice to the rest of the file rather than failing.
     * That matters here: `useIndependentPassphrase` occurs eight times in this screen and
     * `exportPassphrase.isNotEmpty()` twice, so a widened block would still satisfy every
     * `contains` below with the gate it is named for deleted. Checking both delimiters match
     * first is what keeps these assertions about the Export gate.
     */
    private fun bounded(after: String, before: String): String {
        assertTrue(
            source.contains(after),
            "anchor \"$after\" no longer occurs in BackupSettingsScreen.kt, so the slice would " +
                "widen to the whole file and these assertions would pass on unrelated code",
        )
        val tail = source.substringAfter(after)
        assertTrue(
            tail.contains(before),
            "delimiter \"$before\" no longer occurs after \"$after\", so the slice would run " +
                "to end of file and these assertions would pass on unrelated code",
        )
        return tail.substringBefore(before)
    }

    /**
     * The Export gate plus the button that reads it.
     *
     * The terms used to sit inline on `LkPrimaryButton(enabled = ...)`. They were hoisted to a
     * `val exportEnabled` on 2026-09-07 so the two password wells could fire the
     * same action from the keyboard's Done key against the same gate — a button and a well
     * disagreeing about when an export is allowed is the defect that change closed. Slicing both
     * regions keeps every assertion below about the gate's *terms*, and
     * `the export button reads the hoisted gate` is what stops the gate being declared and ignored.
     */
    private fun exportButtonBlock(): String =
        bounded("val exportEnabled", "// Both launchers") +
            bounded("R.string.backup_export_button", "onClick = { viewModel.requestExportDestination(")

    /** The `LkSecondaryButton(...)` block that draws Import's file picker. The control. */
    private fun importButtonBlock(): String =
        bounded("R.string.backup_import_choose_button", "onClick = { importLauncher(")

    @Test
    fun `the export button is disabled while the chosen passphrase well is empty`() {
        val block = exportButtonBlock()
        assertTrue(
            block.contains("useIndependentPassphrase") &&
                block.contains("exportPassphrase.isNotEmpty()"),
            "with the separate-passphrase checkbox on, an empty well must not be exportable — " +
                "`export` reads an empty CharArray as \"no passphrase\" and silently writes a " +
                "master-password export instead; found: $block",
        )
    }

    @Test
    fun `the export button still requires the master password and an idle vault`() {
        // Controls. A gate that only checked the passphrase would let an export start with no
        // master password to re-authenticate against, or on top of one already running.
        val block = exportButtonBlock()
        assertTrue(
            block.contains("masterPassword.isNotEmpty()"),
            "the master password is what `export` re-authenticates with; found: $block",
        )
        assertTrue(
            block.contains("!state.isWorking"),
            "a second export must not start while one is in flight; found: $block",
        )
    }

    @Test
    fun `the import button's own gate is unchanged`() {
        // The other control: Import's gate is the idiom being copied, and it is not this
        // change's to move.
        val block = importButtonBlock()
        assertTrue(
            block.contains("enabled = importPassword.isNotEmpty() && !state.isWorking"),
            "Import stays gated on its own password alone; found: $block",
        )
    }

    @Test
    fun `the export button reads the hoisted gate`() {
        // Without this, every assertion above could pass against a `val exportEnabled` that no
        // control consults. It is the seam the hoist created, so it is the one worth pinning.
        assertTrue(
            bounded("R.string.backup_export_button", "onClick = { viewModel.requestExportDestination(")
                .contains("enabled = exportEnabled"),
            "the Export button must be gated on the hoisted `exportEnabled`",
        )
    }
}

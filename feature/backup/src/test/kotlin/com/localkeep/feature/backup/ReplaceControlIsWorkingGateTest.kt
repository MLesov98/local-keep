package com.localkeep.feature.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Replace — the only control in the app that discards a vault — must be gated on `isWorking` like
 * every other action on this screen.
 *
 * It was the one that was not, and the screen's own comment two hundred lines above says the
 * opposite ("Every action button on this screen is already gated on `isWorking`"), as does
 * [BackupSettingsUiState.isWorking]'s KDoc. The cause was mechanical: Replace is the only
 * `LkTintButton` on the screen and that composable had no `enabled` parameter at all until this
 * change gave it one. So during an in-flight export, import, merge or restore the destructive
 * confirmation still opened and could still be confirmed, and the confirmed tap then died at
 * `confirmImport`'s bare `isWorking` return — which writes neither a status nor an error, leaving
 * the other operation's notice on screen as the apparent answer. After a KDF check in particular
 * (`checkKdfCalibration` sets `isWorking` and writes no status) the screen says nothing at all.
 *
 * The flag needs the same guard: `confirmReplace` is `rememberSaveable`, so it survives a
 * configuration change or a process-death restore into a state where an operation is running, which
 * no gate on the button itself can cover.
 *
 * Asserted on the source rather than by driving the composable, for the reason
 * [ExportPassphraseGateTest] states: `:feature:backup` declares no Compose UI test dependency. The
 * behaviour of `enabled` itself is pinned in `:core:ui` by `LkTintButtonEnabledTest`, which is the
 * other half of this fix.
 */
class ReplaceControlIsWorkingGateTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/backup/BackupSettingsScreen.kt").readText()

    /**
     * A slice bounded at both ends, or a loud failure.
     *
     * `substringAfter`/`substringBefore` default to returning the *whole* receiver when the
     * delimiter is absent, so a renamed anchor silently widens the slice to the rest of the file
     * instead of failing. That is not theoretical here: `enabled = !state.isWorking` occurs six
     * times in this screen, and this test's own sibling case proves at least two of those belong to
     * other buttons — so a widened slice would keep finding Merge's or Cancel's gate and pass with
     * Replace's own gate deleted. Asserting both delimiters match first is what keeps these
     * assertions about Replace.
     */
    private fun bounded(after: String, before: String): String {
        assertTrue(
            source.contains(after),
            "anchor \"$after\" no longer occurs in BackupSettingsScreen.kt, so the slice would " +
                "widen to the whole file and this test would pass on another button's gate",
        )
        val tail = source.substringAfter(after)
        assertTrue(
            tail.contains(before),
            "delimiter \"$before\" no longer occurs after \"$after\", so the slice would run to " +
                "end of file and this test would pass on another button's gate",
        )
        return tail.substringBefore(before)
    }

    /** The `LkTintButton(...)` block that draws Replace, from its label to its `onClick`. */
    private fun replaceButtonBlock(): String =
        bounded("R.string.backup_import_replace_button", "onClick = { confirmReplace = true }")

    @Test
    fun `the replace button is disabled while an operation is in flight`() {
        val block = replaceButtonBlock()
        assertTrue(
            block.contains("enabled = !state.isWorking"),
            "Replace opened its vault-discarding confirmation during an in-flight operation, and " +
                "the confirmed tap then died at confirmImport's isWorking return with nothing " +
                "written to the screen; found: $block",
        )
    }

    @Test
    fun `the replace confirmation does not reappear over a busy screen`() {
        // Matched without the closing paren on purpose: this asserts that the `isWorking` gate is
        // ON the dialog, not that it is the *only* condition there. Pinning the whole expression
        // meant the next legitimate condition broke it - which is what happened on 2026-09-10 when
        // `state.pendingImportVault != null` was added beside it, for a different failure on the
        // same line. A test that fails on punctuation teaches people to edit the test.
        assertTrue(
            source.contains("if (confirmReplace && !state.isWorking"),
            "confirmReplace is rememberSaveable and outlives the composition the button lives in, " +
                "so a restore into a busy state brings the dialog back past the button's own gate",
        )
    }

    @Test
    fun `merge and cancel keep the gates they already had`() {
        // Controls. Replace was the outlier on this screen; its two neighbours were already gated
        // and this change is not theirs to move.
        val mergeBlock = bounded(
            "R.string.backup_import_merge_button",
            "onClick = { viewModel.confirmImport(ImportMode.MERGE) }",
        )
        assertTrue(
            mergeBlock.contains("enabled = !state.isWorking"),
            "found: $mergeBlock",
        )
        val cancelBlock = bounded(
            "R.string.backup_import_cancel_button",
            "onClick = viewModel::cancelImport",
        )
        assertTrue(
            cancelBlock.contains("enabled = !state.isWorking"),
            "found: $cancelBlock",
        )
    }
}

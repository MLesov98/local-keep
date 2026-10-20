package com.localkeep.feature.importwizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression tests for two hardening findings on the Instructions and Done steps (F21, F8).
 *
 * Same source-reading approach as [ImportCancelOrderingTest], and for the same reason:
 * `ImportWizardViewModel` needs a `Context`, a `VaultRepository` and a `VaultLockManager` to
 * construct, `feature/import` has no test source set with Robolectric, and this repo ships no
 * Robolectric anywhere — so a behavioural test that actually runs the ViewModel or renders the
 * composable is not available here (recorded as owed). These pin the
 * *structure* of each fix instead: each assertion fails if the fix is reverted, which is the
 * property a regression test has to have. Neither is a substitute for driving the wizard.
 */
class ImportWizardHardeningTest {

    private val viewModelSource =
        File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardViewModel.kt")
    private val screenSource =
        File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardScreen.kt")

    private fun text(file: File): String {
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }

    /** The body of one member, from its opening brace to the first closing brace at member indent. */
    private fun bodyOf(source: String, marker: String): String =
        source.substringAfter(marker).substringBefore("\n    }")

    // ---- F21: a second tap during a ~minute KDBX parse re-entered onFilePicked, overwriting
    // pendingBytes/pendingUri and reassigning the single parseJob, orphaning the first coroutine.

    @Test
    fun `onFilePicked no-ops while a parse is already in flight`() {
        val body = bodyOf(text(viewModelSource), "fun onFilePicked(uri: Uri) {")
        val guardIndex = body.indexOf("if (_state.value.isWorking) return")
        val launchIndex = body.indexOf("parseJob = viewModelScope.launch")
        assertTrue(
            guardIndex >= 0,
            "onFilePicked must guard re-entrancy with `if (_state.value.isWorking) return`, " +
                "mirroring the guard in applyImport() and back()",
        )
        assertTrue(launchIndex >= 0, "onFilePicked must still launch the read/parse coroutine")
        assertTrue(
            guardIndex < launchIndex,
            "the guard must run before parseJob is reassigned — otherwise a re-entrant call still " +
                "orphans the first parse coroutine and overwrites pendingBytes/pendingUri out from " +
                "under it",
        )
    }

    @Test
    fun `the Instructions step Choose file button is disabled while working`() {
        val body = bodyOf(
            text(screenSource),
            "private fun Instructions(state: ImportUiState, viewModel: ImportWizardViewModel, onChooseFile: () -> Unit) {",
        )
        val buttonCall = body.substringAfter("LkPrimaryButton(", missingDelimiterValue = "")
        assertTrue(buttonCall.isNotEmpty(), "expected an LkPrimaryButton call inside Instructions()")
        assertTrue(
            buttonCall.substringBefore(")\n").contains("enabled = !state.isWorking"),
            "the Choose-file button must gate on isWorking, matching every other primary button in " +
                "this wizard (Password/Preview/Done) — without it a tap mid-parse re-enters " +
                "onFilePicked",
        )
    }

    // ---- F8: the Done step's delete button called viewModel::deleteSourceFile directly, so a
    // single tap permanently deleted the user's original export file with no confirmation.

    @Test
    fun `deleteSourceFile is not wired directly to the Done step button`() {
        val doneBody = bodyOf(text(screenSource), "private fun Done(")
        val dialogIndex = doneBody.indexOf("if (state.confirmDelete) {")
        assertTrue(dialogIndex >= 0, "the Done step must gate a dialog on state.confirmDelete")
        // Everything before the dialog block is the button (and the prose/warnings above it) — the
        // one place a bare `deleteSourceFile()` wire-up would put the file one tap away from gone.
        val buttonRegion = doneBody.substring(0, dialogIndex)
        assertFalse(
            buttonRegion.contains("onClick = viewModel::deleteSourceFile"),
            "the delete button must not call deleteSourceFile() directly — a single tap must not " +
                "permanently delete the user's export file. Route it through " +
                "requestDeleteSourceFile() and a confirmation dialog instead.",
        )
        assertTrue(
            buttonRegion.contains("onClick = viewModel::requestDeleteSourceFile"),
            "the delete button must open the confirmation dialog via requestDeleteSourceFile()",
        )
    }

    @Test
    fun `the confirmation dialog is the only remaining call site that actually deletes`() {
        val doneBody = bodyOf(text(screenSource), "private fun Done(")
        val dialogIndex = doneBody.indexOf("if (state.confirmDelete) {")
        val deleteCallIndex = doneBody.indexOf("onClick = viewModel::deleteSourceFile")
        assertTrue(dialogIndex >= 0, "the Done step must gate a dialog on state.confirmDelete")
        assertTrue(deleteCallIndex >= 0, "the dialog must still be able to call deleteSourceFile() to confirm")
        assertTrue(
            deleteCallIndex > dialogIndex,
            "deleteSourceFile() must only be reachable from inside the confirmDelete-gated block, " +
                "not from the button that opens it",
        )
        val dialogRegion = doneBody.substring(dialogIndex)
        assertTrue(
            dialogRegion.contains("emphasis = LkActionEmphasis.Destructive"),
            "the confirm action must be styled Destructive, matching TrashScreen's irreversible-" +
                "action confirmations",
        )
    }

    @Test
    fun `deleteSourceFile itself closes the dialog it was confirmed from`() {
        val body = bodyOf(text(viewModelSource), "fun deleteSourceFile() {")
        assertTrue(
            body.contains("confirmDelete = false"),
            "deleteSourceFile() must clear confirmDelete when it runs, or the dialog reopens on the " +
                "next recomposition of an unrelated state change",
        )
    }

    // ---- F2/F22 (i18n Task 10): an importer's raw AppError.message — internal-only by convention
    // (never translated, never shown), and possibly quoting a library's own exception text — used to be shown
    // to the user verbatim via `ImportError.FromImporter(parsed.error.message)`. These pin the fix
    // structurally: FromImporter must carry a typed reason, not a String, and the composable must
    // resolve it through a @StringRes rather than reading it back off the error object as text.
    //
    // RED without the fix: `data class FromImporter(val message: String)` is what these fail
    // against — `val reason:` is absent and `error.message` (not `error.reason.resolve()`) is what
    // the resolver reads. GREEN after: see ImportWizardViewModel.ImportError.FromImporter and
    // ImportWizardScreen.resolveImportError / ImportFailureReason.resolve().

    @Test
    fun `ImportError FromImporter carries a typed reason, not a raw message string`() {
        // ImportError is a top-level sealed interface (not nested in a class), so its own closing
        // brace is at column 0 — bodyOf's 4-space-indent convention doesn't apply here, hence the
        // direct substring check rather than bodyOf(text, "sealed interface ImportError {").
        assertTrue(
            text(viewModelSource).contains("data class FromImporter(val reason: ImportFailureReason) : ImportError"),
            "FromImporter must hold a typed ImportFailureReason. Holding `val message: String` " +
                "again means AppError.message — internal-only by convention — can reach the UI " +
                "unresolved, exactly as it did for findings F2 and F22.",
        )
    }

    @Test
    fun `the FromImporter construction site reads AppError cause, never AppError message`() {
        val body = bodyOf(text(viewModelSource), "private suspend fun parse(bytes: ByteArray, password: CharArray?, keyFile: ByteArray? = null) {")
        val constructionIndex = body.indexOf("error = ImportError.FromImporter(")
        assertTrue(constructionIndex >= 0, "expected a FromImporter construction inside parse()")
        val construction = body.substring(constructionIndex).substringBefore("password = \"\",")
        assertTrue(
            construction.contains("parsed.error.cause as? ImportRejection") &&
                construction.contains(".reason"),
            "FromImporter must be built from `(parsed.error.cause as? ImportRejection)?.reason`, " +
                "not from `parsed.error.message` — the message field is the one F2/F22 leaked " +
                "through.",
        )
        assertFalse(
            construction.contains("parsed.error.message"),
            "parsed.error.message must not appear in the FromImporter construction at all",
        )
    }

    @Test
    fun `resolveImportError resolves FromImporter through a StringRes, not a bare field read`() {
        // resolveImportError is a top-level function (not a class member), so its own closing brace
        // is at column 0 — bodyOf's 4-space-indent convention doesn't apply; bound the check between
        // the function's own marker and the next top-level declaration instead.
        val fromMarker = text(screenSource).substringAfter(
            "private fun resolveImportError(error: ImportError?): String? = when (error) {",
        )
        val body = fromMarker.substringBefore("\n}")
        assertTrue(
            body.contains("is ImportError.FromImporter -> error.reason.resolve()"),
            "the FromImporter branch must resolve error.reason through ImportFailureReason.resolve() " +
                "(a stringResource call), not read a String field on the error object directly",
        )
        // Not `body.contains("error.message")` — the Simple branch legitimately reads
        // `error.messageRes`, which contains "error.message" as a plain substring. The specific
        // regression this guards is the FromImporter branch reading a message field again.
        assertFalse(
            body.contains("is ImportError.FromImporter -> error.message"),
            "resolveImportError's FromImporter branch must not read error.message — that field no " +
                "longer exists on FromImporter and reading it is what caused F2/F22",
        )
    }

    // ---- N-02: "Add key file" (the KeePass password step's own suggested repair for a failed
    // attempt) used to send the user back to an empty password well, so the repair failed a second
    // time for a reason the screen never stated. Same structural-pin approach as the F2/F22 tests
    // above, for the same reason (no Robolectric here).

    @Test
    fun `a failed KeePass attempt restores the typed password instead of re-clearing it`() {
        val body = bodyOf(
            text(viewModelSource),
            "private suspend fun parse(bytes: ByteArray, password: CharArray?, keyFile: ByteArray? = null) {",
        )
        val failureIndex = body.indexOf("is Outcome.Failure -> _state.update {")
        assertTrue(failureIndex >= 0, "expected an Outcome.Failure branch inside parse()")
        val failureBranch = body.substring(failureIndex).substringBefore("is Outcome.Success ->")
        assertTrue(
            failureBranch.contains("password = recoveredPassword ?: \"\""),
            "a failed parse (wrong password / needs a key file) must restore the typed password " +
                "from recoveredPassword, not re-clear it with password = \"\" — an empty well is " +
                "exactly what sent the 'Add key file' repair through the same wrong-password loop " +
                "a second time (N-02).",
        )
        assertFalse(
            failureBranch.contains("password = \"\","),
            "the Outcome.Failure branch must not clear the password field outright any more",
        )
    }

    @Test
    fun `recoveredPassword is only captured for a failed attempt, never for success`() {
        val body = bodyOf(
            text(viewModelSource),
            "private suspend fun parse(bytes: ByteArray, password: CharArray?, keyFile: ByteArray? = null) {",
        )
        // `retained`, not `password`: the importer zeroes the array it is handed, so the copy has to
        // be its own — ImportKeyFileRetryTest drives the retry this is for (D1 finding 1, 2026-10-04).
        assertTrue(
            body.contains(
                "result to if (result?.second is Outcome.Failure) retained?.concatToString() else null",
            ),
            "recoveredPassword must be materialised only when the attempt actually failed — " +
                "capturing it unconditionally would put a String copy of the plaintext password in " +
                "memory for the whole derivation on every import, including a successful one, which " +
                "is exactly the exposure window the surrounding wipe in the finally block exists to " +
                "close",
        )
        assertTrue(
            body.contains("retained?.fill('\\u0000')"),
            "the retry copy is the plaintext password too, and must be wiped with the original",
        )
        val successIndex = body.indexOf("is Outcome.Success -> {")
        assertTrue(successIndex >= 0, "expected an Outcome.Success branch inside parse()")
        val successBranch = body.substring(successIndex)
        assertTrue(
            successBranch.contains("password = \"\","),
            "a successful parse must still clear the password outright — recoveredPassword exists " +
                "for the retry case only, not to keep the plaintext around after it opened the file",
        )
    }
}

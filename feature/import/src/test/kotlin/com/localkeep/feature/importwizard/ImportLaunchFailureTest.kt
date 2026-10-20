package com.localkeep.feature.importwizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test for F9 (device-observed, finding D1-2): the Instructions step's "Open the
 * source app" and "Open the export page" buttons wrapped `context.startActivity(...)` in a bare
 * `runCatching { }` with no `onFailure` and no catch branch, so when the intent could not be
 * resolved the tap did nothing at all — no error, no fallback, no change of state.
 *
 * Same source-reading approach as [ImportWizardHardeningTest], and for the same reason:
 * `ImportWizardViewModel` needs a `Context`, a `VaultRepository` and a `VaultLockManager` to
 * construct, and this repo ships no Robolectric anywhere, so a behavioural test that actually
 * runs the ViewModel or renders the composable is not available here. These pin the *structure*
 * of the fix instead: each assertion fails if the fix is reverted.
 */
class ImportLaunchFailureTest {

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

    @Test
    fun `openSourceApp catches a failed launch and surfaces a persistent error`() {
        val source = text(viewModelSource)
        assertTrue(
            source.contains("fun openSourceApp(intent: Intent)"),
            "expected ImportWizardViewModel.openSourceApp(intent: Intent) — the source-app " +
                "launch has to go through the ViewModel so a failure can reach ImportUiState.error, " +
                "the same persistent inline-error channel SettingsViewModel.openSystemSettings uses",
        )
        val body = bodyOf(source, "fun openSourceApp(intent: Intent) {")
        assertTrue(
            body.contains("catch") || body.contains("onFailure"),
            "openSourceApp must have a failure branch — a bare runCatching { } with nothing after " +
                "it is exactly the F9 defect: the tap does nothing and nothing tells the user why",
        )
        assertTrue(
            body.contains("ImportError.Simple(R.string.import_error_open_app_failed)"),
            "a failed source-app launch must set state.error to a named, translated string — " +
                "there is no fallback for \"open this other app\", so an inline error is the only " +
                "way to tell the user their tap did not silently work",
        )
    }

    @Test
    fun `tryOpenUrl reports failure instead of swallowing it`() {
        val source = text(viewModelSource)
        assertTrue(
            source.contains("fun tryOpenUrl(url: String): Boolean"),
            "expected ImportWizardViewModel.tryOpenUrl(url: String): Boolean — the export-page " +
                "launch has to report success/failure so the caller can fall back to copying the " +
                "link instead of doing nothing",
        )
        // tryOpenUrl is expression-bodied (`= try { ... } catch (...) { false }`), so its catch
        // branch closes with the same "    }" indent as the try block itself — bodyOf's marker-to-
        // first-"\n    }" convention would cut the body off before the catch branch. Blank-line-
        // delimited instead, matching the gap Kotlin conventionally leaves between members.
        val body = source.substringAfter("fun tryOpenUrl(url: String): Boolean = try {")
            .substringBefore("\n\n")
        assertTrue(
            body.contains("catch") && body.contains("false"),
            "tryOpenUrl must catch a failed launch and return false — a bare runCatching { } " +
                "with the result discarded is exactly the F9 defect",
        )
    }

    @Test
    fun `Instructions no longer wraps startActivity in a bare unhandled runCatching`() {
        val body = bodyOf(
            text(screenSource),
            "private fun Instructions(state: ImportUiState, viewModel: ImportWizardViewModel, onChooseFile: () -> Unit) {",
        )
        // This is the exact unfixed call site (F9): a bare runCatching around startActivity with no
        // onFailure and no catch, so a failed intent resolution produced no error, no fallback and
        // no state change at all.
        assertFalse(
            body.contains("onClick = { runCatching { context.startActivity(launchable) } }"),
            "the \"Open the source app\" button must not silently swallow a failed launch — route " +
                "it through viewModel.openSourceApp(launchable), which has a failure branch",
        )
        assertTrue(
            body.contains("viewModel.openSourceApp(launchable)"),
            "the \"Open the source app\" button must call viewModel.openSourceApp(launchable)",
        )
    }

    @Test
    fun `the export-page button falls back to copying the link on a failed launch`() {
        val body = bodyOf(
            text(screenSource),
            "private fun Instructions(state: ImportUiState, viewModel: ImportWizardViewModel, onChooseFile: () -> Unit) {",
        )
        val urlBlockIndex = body.indexOf("source.webExportUrl?.let { url ->")
        assertTrue(urlBlockIndex >= 0, "expected the export-page button inside a webExportUrl?.let block")
        val urlBlock = body.substring(urlBlockIndex).substringBefore("\n        }\n\n")
        assertFalse(
            urlBlock.contains("runCatching {") && !urlBlock.contains("viewModel.tryOpenUrl"),
            "the export-page button must not wrap startActivity in a bare, unhandled runCatching",
        )
        assertTrue(
            urlBlock.contains("viewModel.tryOpenUrl(url)"),
            "the export-page button must call viewModel.tryOpenUrl(url) so it knows whether the " +
                "launch worked",
        )
        assertTrue(
            urlBlock.contains("clipboard.setText") && urlBlock.contains("LkNotices.post"),
            "on a failed launch the button must fall back to copying the URL — the wizard already " +
                "has this exact pattern for \"Copy these steps\" a few lines below",
        )
    }
}

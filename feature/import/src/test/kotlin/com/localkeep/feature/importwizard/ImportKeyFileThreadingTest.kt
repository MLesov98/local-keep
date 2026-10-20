package com.localkeep.feature.importwizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A KeePass database can be protected by a password, a key file, or both, and the wizard had no way
 * to ask for the second: eight databases in the real-world corpus answered "wrong password" while
 * the password was right. `core/import` now takes
 * the key file — `RealWorldKeePassImportTest.a database protected by a password and a key file
 * opens` is the behaviour test for that — and this pins the three things the *wizard* has to do or
 * the importer never sees it.
 *
 * Source assertions, the same convention and for the same reason as [ImportPasswordZeroizationTest]
 * and [ImportWizardHardeningTest]: `feature/import` has no Robolectric, so the ViewModel cannot be
 * run here. Each of the three fails if its half of the wiring is reverted.
 */
class ImportKeyFileThreadingTest {

    private val viewModel = File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardViewModel.kt")
    private val screen = File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardScreen.kt")

    private fun text(file: File): String {
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }

    private fun bodyOf(source: String, marker: String): String =
        source.substringAfter(marker).substringBefore("\n    }")

    @Test
    fun `the picked key file reaches the importer`() {
        val body = bodyOf(text(viewModel), "private suspend fun parse(")
        assertTrue(
            body.contains("importer.parse(bytes, password, titles, keyFile)"),
            "the key file must be passed to PasswordProtectedImporter.parse — held but not handed " +
                "over, it is a file picker that changes nothing",
        )
        assertTrue(
            bodyOf(text(viewModel), "fun submitPassword() {").contains("parse(bytes, passwordChars, pendingKeyFile)"),
            "submitPassword must hand the buffered key file to the parse it launches",
        )
    }

    /**
     * A key file on its own is a whole credential. Requiring typed characters before the form would
     * submit made a key-file-only database unopenable through this wizard however correct the file.
     */
    @Test
    fun `an empty password with a key file still submits`() {
        val body = bodyOf(text(viewModel), "fun submitPassword() {")
        assertTrue(
            body.contains("if (password.isEmpty() && pendingKeyFile == null) return"),
            "submitPassword must refuse only when there is neither a password nor a key file",
        )
        assertFalse(
            body.contains("if (password.isEmpty()) return"),
            "the password-only guard must be gone, not merely shadowed",
        )
        assertTrue(
            text(screen).contains("(state.password.isNotEmpty() || state.keyFileName != null) && !state.isWorking"),
            "the Open database button must enable on a key file alone as well as on a password",
        )
    }

    /**
     * Key-file bytes are secret material with the same lifetime as the export's own, and
     * [ImportWizardViewModel.discardPending] is the one place either is released.
     */
    @Test
    fun `the key file bytes are wiped with everything else`() {
        val body = bodyOf(text(viewModel), "private fun discardPending() {")
        assertTrue(
            body.contains("pendingKeyFile?.fill(0)") && body.contains("pendingKeyFile = null"),
            "discardPending must zero and release the key file, not only the export",
        )
        assertFalse(
            text(viewModel).contains("val keyFile: ByteArray"),
            "the key file's bytes must not live on ImportUiState — that object is what a crash " +
                "report and the preview reach; only its display name belongs there",
        )
    }
}

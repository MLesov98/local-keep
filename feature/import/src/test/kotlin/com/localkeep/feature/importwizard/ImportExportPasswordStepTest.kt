package com.localkeep.feature.importwizard

import com.localkeep.core.import.BitwardenJsonImporter
import com.localkeep.core.import.ImportFailureReason
import com.localkeep.core.import.PasswordProtectedImporter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The password step now serves two different things, and the difference is per *file* rather than
 * per source tile: a `.kdbx` is always encrypted, while a Bitwarden export is the same JSON whether
 * or not its author gave it an export password.
 *
 * `RealWorldBitwardenImportTest` is the behaviour test for the opening itself. These pin the wizard
 * wiring, which is what decides whether a user is ever offered somewhere to type the password —
 * and, on the other side, whether every Bitwarden import is stopped to ask for one that does not
 * exist. Source assertions for the reason given on [ImportKeyFileThreadingTest].
 */
class ImportExportPasswordStepTest {

    private val viewModel = File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardViewModel.kt")
    private val screen = File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardScreen.kt")

    private fun text(file: File): String {
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }

    private fun bodyOf(source: String, marker: String): String =
        source.substringAfter(marker).substringBefore("\n    }")

    @Test
    fun `the password step is decided by the file, not only by the picked tile`() {
        val body = bodyOf(text(viewModel), "fun onFilePicked(uri: Uri) {")
        assertTrue(
            body.contains("encrypted?.needsPassword(bytes)"),
            "the importer must be asked about these bytes; `source.needsPassword` alone cannot " +
                "tell an encrypted Bitwarden export from an ordinary one",
        )
        assertTrue(
            body.contains("(source.needsPassword && KdbxImporter.detect(bytes)) ||"),
            "a .kdbx must still reach the step on the source and its signature — it is always " +
                "encrypted, and a file without the signature has no password to ask for",
        )
    }

    /** A key file is a KeePass idea; offering one on an export sends the user hunting for nothing. */
    @Test
    fun `the key file pick is offered for a database and withheld from an export`() {
        assertTrue(
            text(screen).contains("if (state.passwordIsForDatabase) Column("),
            "the Add key file block must be gated on the step's subject",
        )
        assertTrue(
            text(screen).contains("R.string.import_password_body_export"),
            "the step must say \"export\" when that is what it is asking about",
        )
        assertTrue(
            bodyOf(text(viewModel), "fun onFilePicked(uri: Uri) {").contains("passwordIsForDatabase ="),
            "the subject must be set where the step is entered",
        )
    }

    /**
     * The three refusals must stay distinct. `ContainerEncrypted` tells the user to export again
     * without encryption — right for an account-encrypted file, and wrong advice for one whose
     * password they simply mistyped.
     */
    @Test
    fun `a wrong export password resolves to its own message`() {
        assertTrue(
            text(screen).contains("is ImportFailureReason.WrongExportPassword ->"),
            "the reason must be resolved, or it falls through to the KeePass sentence",
        )
        assertTrue(
            text(screen).contains("R.string.core_import_error_wrong_export_password"),
            "and to its own string, which names the export rather than a KeePass database",
        )
    }

    /**
     * V3-02. An account-restricted export and a password-protected one are two different refusals,
     * and the wizard has to resolve them to two different sentences — sharing one meant an
     * account-restricted file was called password-protected (the shape that *does* import) and told
     * to turn encryption off, three lines above the STEP 3 card saying the opposite.
     */
    @Test
    fun `an account-encrypted export resolves to its own message, not the password-protected one`() {
        assertTrue(
            text(screen).contains("is ImportFailureReason.AccountEncrypted ->"),
            "the reason must be resolved, or the when is not exhaustive and the case cannot be shown",
        )
        assertTrue(
            text(screen).contains("R.string.core_import_error_account_encrypted"),
            "and to its own string, not to core_import_error_container_encrypted",
        )
    }

    /**
     * The copy itself, because the resolution above is only half the fix: the two strings have to
     * actually differ in what they name and what they advise.
     */
    @Test
    fun `the account-encrypted copy names the account key and never the password-protected remedy`() {
        val strings = text(File("src/main/res/values/strings.xml"))
        val account = strings
            .substringAfter("""<string name="core_import_error_account_encrypted">""")
            .substringBefore("</string>")
        assertTrue(account.contains("account key"), "it must name what actually sealed the file: $account")
        assertFalse(
            account.contains("Turn off encryption"),
            "re-exporting as \"Password protected\" imports here and never writes the passwords in " +
                "the clear, so turning encryption off must not be the advice: $account",
        )
        assertTrue(
            account.contains("password-protected"),
            "the remedy the STEP 3 card already names must be offered: $account",
        )
    }

    /** The wizard's dispatch assumes this; it is the only importer besides KDBX that may ask. */
    @Test
    fun `the Bitwarden importer is one the wizard can ask for a password`() {
        assertTrue(BitwardenJsonImporter is PasswordProtectedImporter)
        assertFalse(
            BitwardenJsonImporter.needsPassword("""{"encrypted":false,"folders":[],"items":[]}""".toByteArray()),
            "an ordinary export must not be stopped to ask for a password it does not have",
        )
        assertEquals(
            ImportFailureReason.WrongExportPassword(com.localkeep.core.import.SourceFormat.BITWARDEN),
            ImportFailureReason.WrongExportPassword(com.localkeep.core.import.SourceFormat.BITWARDEN),
        )
    }
}

package com.localkeep.feature.importwizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * V3-04. The Done step's cleanup warning must be chosen by whether the *file* is still encrypted,
 * not by which source tile was picked.
 *
 * It read `state.source == ImportSource.KEEPASS`, so a Bitwarden export written as "Password
 * protected" — AES-encrypted under a password the user typed one step earlier — was described as
 * "a plain, unencrypted copy of your passwords sitting in your storage". Tier-A copy, false as
 * shown, on the one screen that asks the user to delete something for good.
 *
 * Source assertions for the reason given on [ImportWizardHardeningTest]: this module has no
 * Robolectric, so the ViewModel cannot be constructed and the composable cannot be rendered here.
 * Each assertion fails if the fix is reverted, which is the property the test has to have; the
 * behaviour itself is pinned in `core/import` (`BitwardenJsonImporter.needsPassword`) and on a
 * device.
 */
class ImportDoneCleanupWarningTest {

    private val viewModelSource =
        File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardViewModel.kt")
    private val screenSource =
        File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardScreen.kt")
    private val strings = File("src/main/res/values/strings.xml")

    private fun text(file: File): String {
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }

    @Test
    fun `the warning is selected by whether the file was decrypted with a password`() {
        val screen = text(screenSource)
        assertTrue(
            screen.contains("if (state.sourceEncryptedWithPassword) {"),
            "the cleanup warning must branch on the file, not on the picked tile",
        )
        assertTrue(
            screen.contains("R.string.import_done_delete_warning_encrypted"),
            "the encrypted variant must be the one that branch selects",
        )
        // The branch that was there, named exactly: the warning pair used to be picked by the tile,
        // and `import_done_delete_warning_keepass` is the string only that branch could select.
        // (The *button* label legitimately still reads the source — a Bitwarden export is an export
        // whether or not it is encrypted — so a blanket "KEEPASS is not mentioned" would be false.)
        assertFalse(
            screen.contains("import_done_delete_warning_keepass"),
            "the source-named warning must be gone, or both branches still exist",
        )
        val warning = screen.substringAfter("if (state.sourceEncryptedWithPassword) {")
            .substringBefore("            )")
        assertFalse(
            warning.contains("ImportSource."),
            "nothing about the warning may consult the tile: $warning",
        )
    }

    /** The flag has to be set from the importer and the bytes, or it is the tile under a new name. */
    @Test
    fun `the flag is set from the importer that actually asked for a password`() {
        val viewModel = text(viewModelSource)
        assertTrue(
            viewModel.contains("val sourceEncryptedWithPassword: Boolean = false,"),
            "the state must carry the fact, not re-derive it in the composable",
        )
        val set = viewModel.substringAfter("sourceEncryptedWithPassword = ").substringBefore(",\n")
        assertTrue(set.contains("password != null"), "a file nobody typed a password for is not this: $set")
        assertTrue(
            set.contains("importer is PasswordProtectedImporter"),
            "only an importer that can be asked for a password may answer here: $set",
        )
        assertTrue(
            set.contains("importer.needsPassword(bytes)"),
            "a Bitwarden export is the same JSON either way, so only the bytes decide: $set",
        )
    }

    /**
     * The renamed key, and why it was renamed: `_keepass` named the source, and the source is no
     * longer what selects it. The two warnings must also stay different claims — that is the whole
     * of the finding.
     */
    @Test
    fun `the encrypted warning no longer claims the file is a plain unencrypted copy`() {
        val catalogue = text(strings)
        assertFalse(
            catalogue.contains("""name="import_done_delete_warning_keepass""""),
            "the old key names a source that no longer decides the string",
        )
        val encrypted = catalogue
            .substringAfter("""<string name="import_done_delete_warning_encrypted">""")
            .substringBefore("</string>")
        assertTrue(
            encrypted.contains("protected only by its own password"),
            "the tier-A claim this string exists to make must survive the rename: $encrypted",
        )
        assertFalse(
            encrypted.contains("unencrypted"),
            "this branch is for a file that is still encrypted: $encrypted",
        )
        assertFalse(
            encrypted.contains("database"),
            "a Bitwarden export reaches this string too, and it is not a database: $encrypted",
        )
    }

    /** Control: the plain-file warning is untouched and still says the strong thing. */
    @Test
    fun `the plain warning still names the export as unencrypted`() {
        val plain = text(strings)
            .substringAfter("""<string name="import_done_delete_warning_plain">""")
            .substringBefore("</string>")
        assertTrue(plain.contains("plain, unencrypted copy"), plain)
    }
}

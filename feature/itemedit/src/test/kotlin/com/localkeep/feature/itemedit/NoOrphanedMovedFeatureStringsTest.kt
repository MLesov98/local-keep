package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Strings this module kept after the UI that rendered them left it.
 *
 * Folder creation and renaming used to live inside the item editor's own folder picker. It moved to
 * `feature/vaultlist`'s `NewFolderScreen`/`FolderScreen`, which resolve their own
 * `vaultlist_folders_*` keys — and this module's parallel copy was left behind with no Kotlin
 * reader anywhere in the repository. Three more strings had been superseded in place by a
 * differently-named live key.
 *
 * No runtime effect, which is exactly why it survives: dead copy costs nothing a user can see and
 * four translations plus a reviewer's attention on every i18n sweep, as though it were live UI.
 * Modelled on `NoOrphanedProGateNoteTest`, which makes the same class of defect build-breaking for
 * the Pro-gate note in this same module.
 */
class NoOrphanedMovedFeatureStringsTest {

    private val locales = listOf("values", "values-de", "values-es", "values-fr", "values-pt")

    /** The folder create/rename copy, which now lives in :feature:vaultlist under its own keys. */
    private val movedToVaultlist = listOf(
        "itemedit_folder_name_field_label",
        "itemedit_folder_rename_a11y",
        "itemedit_folder_save_name_a11y",
        "itemedit_folder_cancel_rename_a11y",
    )

    /** Superseded in place: each has a live sibling under a different key in this same module. */
    private val superseded = listOf(
        // -> itemedit_detail_identity_phone_label
        "itemedit_identity_phone_label",
        // -> itemedit_card_holder_field_label
        "itemedit_detail_card_holder_label",
        // The detail screen names each copy action after its own field; this generic one is unread.
        "itemedit_detail_field_copy",
    )

    @Test
    fun `nothing in the repository reads them`() {
        // Production Kotlin only. A catalogue naturally contains the key it defines, and this test
        // names all seven itself — so a scan that included resources or test sources would report
        // its own text as a reader. The module root is two levels under the repository root.
        val repo = File("../..").canonicalFile
        val sources = repo.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") && !it.name.endsWith("-out") }
            .filter { it.isFile && it.extension == "kt" }
            .filter { "${File.separator}src${File.separator}main${File.separator}" in it.path }
            .toList()
        assertTrue(sources.size > 100, "only ${sources.size} Kotlin files found under $repo — wrong root?")
        for (key in movedToVaultlist + superseded) {
            val readers = sources.filter { key in it.readText() }
            assertTrue(
                readers.isEmpty(),
                "$key is read again by ${readers.map(File::getName)}. If the folder create/rename " +
                    "UI came back to this module, or a superseded key was re-adopted, delete it " +
                    "from this list in the same commit rather than leaving the two disagreeing.",
            )
        }
    }

    @Test
    fun `they are gone from every catalogue`() {
        for (locale in locales) {
            val file = File("src/main/res/$locale/strings.xml")
            assertTrue(file.exists(), "no catalogue at ${file.absolutePath}")
            val text = file.readText()
            // The control: still this module's catalogue, and still carrying the folder copy that
            // does have a live call site. True before the fix and after, so a wrong path cannot
            // pass by reporting every key absent.
            assertTrue(
                """name="itemedit_folder_label"""" in text,
                "$locale/strings.xml does not look like this module's catalogue",
            )
            for (key in movedToVaultlist + superseded) {
                assertFalse(
                    """name="$key"""" in text,
                    "$key is still in $locale/strings.xml. Nothing renders it — the folder " +
                        "create/rename UI is :feature:vaultlist's now, and the rest were " +
                        "superseded in place — so it is four translations nobody can reach.",
                )
            }
        }
    }
}

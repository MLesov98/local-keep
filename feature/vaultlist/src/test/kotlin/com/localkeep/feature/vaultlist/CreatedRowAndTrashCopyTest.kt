package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Copy and small pure rules on the Trash and Folders screens.
 *
 * Reads the resource files directly, in the manner of `EmptyStateLabelTest`: a pure JVM test, and a
 * string carries more than its English.
 */
class CreatedRowAndTrashCopyTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private fun strings(dir: String): String =
        File("src/main/res/$dir/strings.xml").takeIf { it.exists() }?.readText()
            ?: fail("$dir/strings.xml is missing")

    private fun value(dir: String, name: String): String =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings(dir))?.groupValues?.get(1) ?: fail("$name not found in $dir/strings.xml")

    private fun items(dir: String, plural: String): List<String> {
        val block = Regex("""<plurals name="$plural"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings(dir))?.groupValues?.get(1) ?: fail("plural $plural not found in $dir/strings.xml")
        return Regex("""<item quantity="[a-z]+">(.*?)</item>""").findAll(block).map { it.groupValues[1] }.toList()
    }

    // ---- S3-F-22: no count line over an empty trash ----

    @Test
    fun anEmptyTrashHasNoCountLine() {
        assertFalse(trashCountSubtitleShown(purgeableItemCount = 0, flatTotal = 0, folderRows = 0))
    }

    @Test
    fun aTrashHoldingAnythingKeepsItsCountLine() {
        assertTrue(trashCountSubtitleShown(purgeableItemCount = 3, flatTotal = 1, folderRows = 0))
        // Items only inside a trashed folder have no flat row of their own.
        assertTrue(trashCountSubtitleShown(purgeableItemCount = 5, flatTotal = 0, folderRows = 1))
        // A trashed empty folder is a row on the screen, so the line above it is not a count of nothing.
        assertTrue(trashCountSubtitleShown(purgeableItemCount = 0, flatTotal = 0, folderRows = 1))
    }

    // ---- S3-F-21: Empty trash names the folders it destroys ----

    private fun body(foldersClause: String) = emptyTrashBody(
        totalCount = 5,
        shownCount = 5,
        removesPhrase = "This permanently removes all 5 items",
        hidingClause = ", including the ones your search is hiding",
        noUndoClause = ". There is no undo here.",
        foldersClause = foldersClause,
    )

    @Test
    fun theFoldersClauseJoinsTheItemPhrase() {
        assertEquals(
            "This permanently removes all 5 items and 2 folders. There is no undo here.",
            body("and 2 folders"),
        )
    }

    @Test
    fun withNoFoldersTheSentenceIsExactlyWhatItWas() {
        assertEquals("This permanently removes all 5 items. There is no undo here.", body(""))
    }

    @Test
    fun everyLanguageShipsTheFolderClauseWithItsCount() {
        languages.forEach { dir ->
            val forms = items(dir, "vaultlist_trash_empty_and_folders")
            assertTrue(forms.isNotEmpty(), "$dir has no folder clause")
            forms.forEach { assertTrue("%1\$d" in it, "$dir: \"$it\" drops the count") }
        }
    }

    // ---- S3-F-05: a numeral never parts from its noun in the delete-folder body ----

    @Test
    fun theDeleteFolderBodyKeepsTheNumeralWithItsNoun() {
        languages.forEach { dir ->
            items(dir, "vaultlist_folders_delete_body").forEach { form ->
                assertTrue(
                    "%1\$d\\u00a0" in form,
                    "$dir: \"$form\" can break between the count and what it counts",
                )
                assertFalse("%1\$d " in form, "$dir: \"$form\" still has a plain space after the count")
            }
        }
    }

    // ---- S2-F-19: the custom-duration error names the floor as well as the ceiling ----

    @Test
    fun theCustomDurationErrorNamesTheFloor() {
        languages.forEach { dir ->
            val text = value(dir, "vaultlist_hide_custom_error")
            assertTrue(Regex("""\b1\b""").containsMatchIn(text), "$dir: \"$text\" does not say the number starts at 1")
        }
    }

    // ---- D2-F-02: the empty Folders body no longer repeats the subtitle ----

    @Test
    fun theEmptyFoldersBodyIsOneShortInstruction() {
        languages.forEach { dir ->
            val body = value(dir, "vaultlist_folders_empty_body")
            assertTrue('.' == body.last() && body.count { it == '.' } == 1, "$dir: \"$body\" is more than one sentence")
            assertTrue("—" !in body, "$dir: \"$body\" still carries the Trash explanation the subtitle gives")
        }
    }

    // ---- S2-F-21: "trash" is the common noun in English ----

    @Test
    fun theDragNoticeSpellsTrashLikeTheOtherNotices() {
        assertTrue(value("values", "vaultlist_drag_trashed_folder").endsWith("moved to trash"))
    }
}

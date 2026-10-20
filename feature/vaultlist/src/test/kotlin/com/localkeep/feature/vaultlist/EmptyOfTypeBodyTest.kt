package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The empty state of a type chip says where the type is missing from.
 *
 * Inside a folder it read "Add one, or clear the filter to see the rest of your vault" — but the
 * chip may be empty only *there*, and clearing it shows the folder's other items, not the vault.
 * And the body began "Nothing here yet." under a title that already said "No cards yet".
 */
class EmptyOfTypeBodyTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private fun value(dir: String, name: String): String {
        val text = File("src/main/res/$dir/strings.xml").takeIf { it.exists() }?.readText()
            ?: fail("$dir/strings.xml is missing")
        return Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: fail("$name not found in $dir/strings.xml")
    }

    @Test
    fun aFolderGetsItsOwnSentence() {
        assertNotEquals(emptyOfTypeBodyRes(inFolder = true), emptyOfTypeBodyRes(inFolder = false))
        assertEquals(R.string.vaultlist_empty_body_addable, emptyOfTypeBodyRes(inFolder = false))
    }

    @Test
    fun theFolderSentenceNamesTheFolderNotTheVault() {
        languages.forEach { dir ->
            val inFolder = value(dir, "vaultlist_empty_body_addable_in_folder")
            val atRoot = value(dir, "vaultlist_empty_body_addable")
            assertNotEquals(inFolder, atRoot, "$dir: the two bodies are the same sentence")
        }
        assertTrue("vault" !in value("values", "vaultlist_empty_body_addable_in_folder"))
    }

    @Test
    fun theBodyDoesNotRestateTheTitle() {
        assertTrue(!value("values", "vaultlist_empty_body_addable").startsWith("Nothing here yet"))
        assertTrue(!value("values", "vaultlist_empty_body_addable_in_folder").startsWith("Nothing here yet"))
    }
}

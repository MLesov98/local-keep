package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two columns every real CSV export writes and this mapper discarded.
 *
 * LastPass writes `fav` and `grouping`; Bitwarden's CSV writes `favorite` and `folder`. Neither
 * reached the vault, so the two pieces of filing a user does by hand most often — starring an entry
 * and putting it somewhere — arrived empty on an import that reported full success (S4 and S5,
 * 2026-09-15). `grouping`/`folder` are still deliberately not tags: they name one path, and reading
 * them as a tag set would file one concept under another.
 */
class CsvFavoriteAndFolderColumnTest {

    private fun parse(csv: String): ParsedImport {
        val result = CsvImporter.parse(csv.toByteArray(Charsets.UTF_8), ImportDefaultTitles())
        assertTrue(result is Outcome.Success, "fixture must parse: $result")
        return result.value
    }

    private fun ParsedImport.folderOf(title: String): Folder? {
        val id = items.single { it.title == title }.folderId ?: return null
        return folders.single { it.id == id }
    }

    private val lastpass = """
        url,username,password,totp,extra,name,grouping,fav
        https://bank.example,ada,pw1,,,Bank,Finance\Banks,1
        https://card.example,ada,pw2,,,Card,Finance,0
        https://social.example,ada,pw3,,,Social,,yes
    """.trimIndent()

    @Test
    fun `a lastpass fav column becomes the favorite flag`() {
        val parsed = parse(lastpass)

        assertTrue(parsed.items.single { it.title == "Bank" }.favorite, "1 means starred")
        assertFalse(parsed.items.single { it.title == "Card" }.favorite, "0 does not")
        assertTrue(parsed.items.single { it.title == "Social" }.favorite, "and so does yes")
    }

    /** Anything the mapper does not recognise is "not starred" — never a guess. */
    @Test
    fun `an unrecognised favorite cell is not a favorite`() {
        val parsed = parse(
            """
            name,url,username,password,favorite
            Odd,https://odd.example,ada,pw,maybe
            """.trimIndent(),
        )

        assertFalse(parsed.items.single().favorite)
    }

    @Test
    fun `a lastpass grouping column becomes a folder, and its backslash nests`() {
        val parsed = parse(lastpass)

        val banks = assertNotNull(parsed.folderOf("Bank"), "the Bank row must be filed")
        assertEquals("Banks", banks.name)
        val finance = assertNotNull(parsed.folders.single { it.id == banks.parentId })
        assertEquals("Finance", finance.name)
        assertNull(finance.parentId, "Finance is the top of the path")

        assertEquals(finance.id, assertNotNull(parsed.folderOf("Card")).id, "the same path is the same folder")
        assertNull(parsed.items.single { it.title == "Social" }.folderId, "a blank cell is no folder at all")
        assertEquals(2, parsed.folders.size, "and nothing is created that nothing was filed into")
    }

    /** Bitwarden's CSV spells the same column `folder` and nests it with a forward slash. */
    @Test
    fun `a bitwarden csv folder column nests on a forward slash`() {
        val parsed = parse(
            """
            name,login_uri,login_username,login_password,folder
            Bank,https://bank.example,ada,pw1,Finance/Banks
            """.trimIndent(),
        )

        val banks = assertNotNull(parsed.folderOf("Bank"))
        assertEquals("Banks", banks.name)
        assertEquals("Finance", parsed.folders.single { it.id == banks.parentId }.name)
    }

    /**
     * The separator is chosen from the header name, not by splitting on both: a LastPass folder
     * called `Cars/Bikes` is one folder, and guessing would have made it two.
     */
    @Test
    fun `the other vendor's separator is left alone as part of the name`() {
        val parsed = parse(
            """
            url,username,password,name,grouping
            https://x.example,ada,pw,X,Cars/Bikes
            """.trimIndent(),
        )

        assertEquals(listOf("Cars/Bikes"), parsed.folders.map { it.name })
    }

    /** Clamped the way KdbxImporter clamps a KeePass tree: near where it was, never refused. */
    @Test
    fun `a path deeper than the app allows is clamped rather than dropped`() {
        val deep = (1..FolderTree.MAX_DEPTH + 4).joinToString("\\") { "L$it" }
        val parsed = parse(
            """
            url,username,password,name,grouping
            https://x.example,ada,pw,X,$deep
            """.trimIndent(),
        )

        assertEquals(FolderTree.MAX_DEPTH, parsed.folders.size)
        assertEquals("L${FolderTree.MAX_DEPTH}", assertNotNull(parsed.folderOf("X")).name)
    }

    /** The point TAGS_HEADERS has always made, now that the column is read for something else. */
    @Test
    fun `a folder path is never read as a tag`() {
        val parsed = parse(lastpass)

        assertTrue(parsed.items.all { it.tags.isEmpty() })
    }

    /** Control: a CSV with neither column still imports, unfiled and unstarred. */
    @Test
    fun `a csv with neither column is unchanged`() {
        val parsed = parse(
            """
            name,url,username,password
            Plain,https://plain.example,ada,pw
            """.trimIndent(),
        )

        assertTrue(parsed.folders.isEmpty())
        assertNull(parsed.items.single().folderId)
        assertFalse(parsed.items.single().favorite)
    }
}

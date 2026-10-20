package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bitwarden's folders survive the import.
 *
 * They did not until 2026-07-31: `mapItem` hardcoded `folderId = null` and the parse result had
 * nowhere to put a folder, so a user migrating an organised vault arrived with a flat one and no
 * indication anything had been lost. That was the honest behaviour while `ParsedImport` carried
 * items and nothing else — an item pointing at a folder the vault does not have is worse than an
 * item filed nowhere — so the contract grew the list and this is the behaviour that came with it.
 */
class BitwardenFolderImportTest {

    private fun parse(json: String): ParsedImport =
        when (val outcome = BitwardenJsonImporter.parse(json.toByteArray())) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("expected a successful parse, got: ${outcome.error.message}")
        }

    private fun export(folders: String, items: String) = """
        { "encrypted": false, "folders": [$folders], "items": [$items] }
    """.trimIndent()

    /**
     * A complete type-1 entry. The `login` object is not optional decoration: a type-1 item without
     * one is reported as "Incomplete entry in the export" and skipped, so a fixture missing it
     * tests the skip path rather than the folder path.
     */
    private fun loginItem(name: String, folderId: String?): String {
        val folder = folderId?.let { """"folderId": "$it", """ }.orEmpty()
        return """{ "type": 1, "name": "$name", ${folder}"login": { "username": "me", "password": "pw" } }"""
    }

    @Test
    fun `an item arrives filed in the folder the export put it in`() {
        val parsed = parse(
            export(
                folders = """{ "id": "bw-1", "name": "Finance" }""",
                items = loginItem("Bank", "bw-1"),
            ),
        )

        val folder = parsed.folders.single()
        assertEquals("Finance", folder.name)
        assertEquals(
            "the item must point at the folder this importer minted, not at Bitwarden's own id",
            folder.id,
            parsed.items.single().folderId,
        )
    }

    @Test
    fun `bitwarden's opaque id is replaced by a real UUID`() {
        // The export's ids are its own strings and are meaningless in this vault. The regression
        // this guards is a future refactor trying to reuse them and producing collisions with a
        // second import, or with folders the user made by hand.
        val parsed = parse(
            export(
                folders = """{ "id": "not-a-uuid", "name": "Finance" }""",
                items = loginItem("Bank", "not-a-uuid"),
            ),
        )
        assertNotNull(parsed.folders.single().id)
        assertTrue(
            "two parses of the same export must not mint the same folder id",
            parse(
                export(
                    folders = """{ "id": "not-a-uuid", "name": "Finance" }""",
                    items = loginItem("Bank", "not-a-uuid"),
                ),
            ).folders.single().id != parsed.folders.single().id,
        )
    }

    @Test
    fun `an empty folder is not carried across`() {
        // An export lists every folder the account had. Creating the ones nothing landed in leaves
        // the user tidying up after an import that was not asked to reorganise anything.
        val parsed = parse(
            export(
                folders = """{ "id": "bw-1", "name": "Finance" }, { "id": "bw-2", "name": "Empty" }""",
                items = loginItem("Bank", "bw-1"),
            ),
        )
        assertEquals(listOf("Finance"), parsed.folders.map { it.name })
    }

    @Test
    fun `a folder whose only items were skipped is not carried across`() {
        // Type 99 is not a Bitwarden type this build maps, so `mapItem` returns null and the entry
        // is reported as skipped instead. Its folder therefore ends up occupied by nothing, which
        // is the same case as an empty folder and must be treated the same way.
        val parsed = parse(
            export(
                folders = """{ "id": "bw-9", "name": "Ghosts" }""",
                items = """{ "type": 99, "name": "Something future", "folderId": "bw-9" }""",
            ),
        )
        assertTrue("nothing landed in it, so it must not be created", parsed.folders.isEmpty())
    }

    @Test
    fun `an unnamed folder is dropped rather than imported as a blank one`() {
        val parsed = parse(
            export(
                folders = """{ "id": "bw-1", "name": "" }""",
                items = loginItem("Bank", "bw-1"),
            ),
        )
        assertTrue(parsed.folders.isEmpty())
        assertNull(
            "with no folder to point at, the item is filed nowhere rather than at a dangling id",
            parsed.items.single().folderId,
        )
    }

    @Test
    fun `an item referencing a folder the export never listed is filed nowhere`() {
        val parsed = parse(
            export(
                folders = "",
                items = loginItem("Bank", "bw-missing"),
            ),
        )
        assertTrue(parsed.folders.isEmpty())
        assertNull(parsed.items.single().folderId)
    }

    @Test
    fun `an export with no folders at all still imports its items`() {
        // The overwhelmingly common shape, and the one that must not regress.
        val parsed = parse(export(folders = "", items = loginItem("Bank", folderId = null)))
        assertTrue(parsed.folders.isEmpty())
        assertEquals(1, parsed.items.size)
        assertNull(parsed.items.single().folderId)
    }
}

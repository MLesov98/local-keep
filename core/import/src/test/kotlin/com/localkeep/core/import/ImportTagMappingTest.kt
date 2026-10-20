package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxHeader
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import org.linguafranca.pwdb.kdbx.dom.DomSerializableDatabase
import org.w3c.dom.Element

/**
 * Tags, which `VaultItem.tags` has always carried and which only the 1Password importer ever set.
 *
 * A tag set is the one piece of filing a folder tree cannot express — it cuts across the tree — so
 * dropping it is not "the same information arranged differently", it is the information gone. Every
 * importer here reported full success while doing it, and the wizard's Done step then offers to
 * delete the export (F14 residual, 2026-09-13).
 *
 * Bitwarden's half is a design decision as much as a mapping, and the argument is in
 * [BitwardenCollection]: `collectionIds` is an array, `VaultItem.folderId` is one id, so collections
 * can only be carried faithfully as tags.
 */
class ImportTagMappingTest {

    // ---------------------------------------------------------------------------------------- KDBX

    private val password = "correct horse battery staple"
    private fun creds() = KdbxCreds(password.toByteArray(Charsets.UTF_8))

    private fun child(parent: Element, name: String): Element? {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == name) return node
        }
        return null
    }

    /**
     * Builds a one-entry KDBX4 whose entry carries [tags] in its `<Tags>` element.
     *
     * Written by hand because KDBX tags are a child *element* of `<Entry>`, not a named string
     * field — which is exactly why `DomEntryWrapper.getPropertyNames` never saw them and the
     * custom-field rescue could not catch them either. The library exposes no accessor at all, so
     * the fixture opens its own container and adds the element.
     */
    private fun kdbxWithTags(tags: String): ByteArray {
        val db = DomDatabaseWrapper().apply {
            rootGroup.addEntry(newEntry().apply { title = "Tagged"; username = "u"; setPassword("p") })
        }
        val first = ByteArrayOutputStream().use { out -> db.save(creds(), out); out.toByteArray() }
        val reopened = DomSerializableDatabase.createEmptyDatabase()
        KdbxStreamFormat(KdbxHeader()).load(reopened, creds(), ByteArrayInputStream(first))
        val doc = reopened.doc
        val root = checkNotNull(child(checkNotNull(child(doc.documentElement, "Root")), "Group"))
        val entry = checkNotNull(child(root, "Entry")) { "fixture has no entry" }
        entry.appendChild(doc.createElement("Tags").apply { textContent = tags })
        return ByteArrayOutputStream().use { out ->
            KdbxStreamFormat(KdbxHeader(4)).save(reopened, creds(), out)
            out.toByteArray()
        }
    }

    private fun parseKdbx(bytes: ByteArray): ParsedImport {
        val result = KdbxImporter.parse(bytes, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed: $result")
        return result.value
    }

    @Test
    fun `KDBX entry tags become item tags`() {
        // KeePass separates with ';', KeePassXC with ',', and both read either.
        assertEquals(
            listOf("work", "finance"),
            parseKdbx(kdbxWithTags("work;finance")).items.single().tags,
        )
        assertEquals(
            listOf("work", "finance"),
            parseKdbx(kdbxWithTags("work, finance")).items.single().tags,
        )
    }

    @Test
    fun `a KDBX entry with no tags element carries no tags`() {
        // The control: the element is optional, and its absence must not become a blank tag.
        val db = DomDatabaseWrapper().apply {
            rootGroup.addEntry(newEntry().apply { title = "Plain"; username = "u"; setPassword("p") })
        }
        val bytes = ByteArrayOutputStream().use { out -> db.save(creds(), out); out.toByteArray() }
        assertEquals(emptyList(), parseKdbx(bytes).items.single().tags)
        assertEquals(emptyList(), parseKdbx(kdbxWithTags("  ;  , ")).items.single().tags)
    }

    // ----------------------------------------------------------------------------------------- CSV

    private fun parseCsv(text: String): ParsedImport {
        val result = CsvImporter.parse(text.toByteArray(Charsets.UTF_8), ImportDefaultTitles())
        assertTrue(result is Outcome.Success, "import must succeed: $result")
        return result.value
    }

    @Test
    fun `a CSV tags column becomes item tags`() {
        // 1Password's own CSV export writes the column as `Tags`, multi-valued inside one quoted
        // cell. Header matching is case-insensitive already (`autoDetect` lowercases).
        val parsed = parseCsv(
            """
            Title,Url,Username,Password,Tags
            Bank,https://bank.example,alice,hunter2,"work,finance"
            """.trimIndent(),
        )
        assertEquals(listOf("work", "finance"), parsed.items.single().tags)
    }

    @Test
    fun `a CSV with no tags column still imports, with no tags`() {
        val parsed = parseCsv(
            """
            name,url,username,password
            Bank,https://bank.example,alice,hunter2
            """.trimIndent(),
        )
        assertEquals(emptyList(), parsed.items.single().tags)
    }

    // ----------------------------------------------------------------------------------- Bitwarden

    private fun parseBitwarden(json: String): ParsedImport {
        val result = BitwardenJsonImporter.parse(json.toByteArray(Charsets.UTF_8), ImportDefaultTitles())
        assertTrue(result is Outcome.Success, "import must succeed: $result")
        return result.value
    }

    private val orgExport = """
        {"encrypted":false,
         "collections":[{"id":"c1","organizationId":"o1","name":"Engineering","externalId":null},
                        {"id":"c2","organizationId":"o1","name":"Finance","externalId":null},
                        {"id":"c3","organizationId":"o1","name":"   ","externalId":null}],
         "items":[{"id":"i1","organizationId":"o1","type":1,"name":"Shared login",
                   "collectionIds":["c1","c2","c3","unknown"],
                   "login":{"username":"alice","password":"s3cr3t"}}]}
    """.trimIndent()

    @Test
    fun `Bitwarden collections become tags`() {
        val item = parseBitwarden(orgExport).items.single()
        assertEquals(listOf("Engineering", "Finance"), item.tags)
        // An unnamed collection and an id the export never declared both drop out rather than
        // producing a blank label or a raw Bitwarden id in the user's tag list.
        assertEquals(2, item.tags.size)
    }

    @Test
    fun `collections do not become folders`() {
        // The decision this mapping rests on: an item can be in several collections and can only be
        // in one folder, so folders would have to pick a winner silently.
        val parsed = parseBitwarden(orgExport)
        assertTrue(parsed.folders.isEmpty(), "collections must not be minted as folders")
        assertEquals(null, parsed.items.single().folderId)
    }

    @Test
    fun `a personal export still imports, with folders and no tags`() {
        // The control: `collections` is absent from a personal-vault export, and its handling must
        // not disturb the folder mapping that has always been there.
        val parsed = parseBitwarden(
            """
            {"encrypted":false,"folders":[{"id":"f1","name":"Work"}],
             "items":[{"id":"i1","type":1,"name":"Login","folderId":"f1",
                       "login":{"username":"alice","password":"s3cr3t"}}]}
            """.trimIndent(),
        )
        val item = parsed.items.single()
        assertEquals(emptyList(), item.tags)
        assertEquals(listOf("Work"), parsed.folders.map { it.name })
        assertEquals(parsed.folders.single().id, item.folderId)
    }

    @Test
    fun `an organisation export with no items is still recognised as Bitwarden`() {
        // `detect` used to require a `folders` array, which an organisation export does not write —
        // so an empty org export was answered with "this doesn't look like a valid export", the
        // same false claim an empty personal export used to get (1-3).
        assertTrue(
            BitwardenJsonImporter.detect(
                """{"encrypted":false,"collections":[],"items":[]}""".toByteArray(Charsets.UTF_8),
            ),
        )
    }
}

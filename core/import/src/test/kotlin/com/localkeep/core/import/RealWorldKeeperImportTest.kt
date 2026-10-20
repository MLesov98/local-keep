package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Keeper, from `src/test/resources/realworld/keeper/` (see `PROVENANCE.md` there).
 *
 * Keeper exports two shapes and, until [KeeperImporter], LocalKeep read neither. Its CSV is
 * **header-less and positional** (`folder,title,login,password,url,notes,shared_folder` then
 * repeating custom-field name/value pairs), and its JSON is a `{shared_folders,records}` document.
 *
 * The refusal the corpus recorded is still asserted below, and it is not a leftover: [CsvImporter]
 * must go on saying no to a header-less file, because a generic column-position reading of one is
 * exactly how a file imports as confident nonsense. What changed is that a second importer now
 * recognises the same file by its *shape* and reads it by position deliberately.
 */
class RealWorldKeeperImportTest {

    private val csvFiles = listOf(
        "keeper/export.csv",
        "keeper/export-multi-collection.csv",
        "keeper/export-dedicated-item-types.csv",
    )

    private val jsonFiles = listOf(
        "keeper/export-cli.json",
        "keeper/export-web.json",
        "keeper/export-legacy.json",
        "keeper/export-enterprise.json",
    )

    /**
     * With no header row there is nothing for `autoDetect` to bind, so `passwordCol` stays null and
     * the generic mapper's detection fails. That refusal is load-bearing, not obsolete:
     * [KeeperCsv.detect] is built on top of it (its first rule is "the generic mapper does not
     * already recognise this"), so if this ever started saying yes, Keeper's own recognition would
     * stop rather than double up.
     */
    @Test
    fun `a header-less Keeper CSV is still refused by the generic CSV mapper`() {
        csvFiles.forEach { path ->
            assertFalse(CsvImporter.detect(RealWorldCorpus.bytes(path)), "$path is recognised — expectation is stale")
            // And if it is forced through anyway, it must produce no confidently-wrong login: the
            // first row is consumed as a header and the rest have no mapped column to fill.
            val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            assertEquals(emptyList(), parsed.items, "$path produced items from an unmapped file")
        }
    }

    /** Keeper's native JSON is claimed by [KeeperImporter] and by nothing that it is not. */
    @Test
    fun `Keeper JSON is claimed by the Keeper importer and by no other`() {
        jsonFiles.forEach { path ->
            val bytes = RealWorldCorpus.bytes(path)
            assertTrue(KeeperImporter.detect(bytes), "$path was not recognised as a Keeper export")
            listOf(BitwardenJsonImporter, AegisJsonImporter, OnePuxImporter, CsvImporter).forEach { importer ->
                assertFalse(
                    importer.detect(bytes),
                    "$path was claimed by ${importer::class.simpleName} — it is not that format",
                )
            }
        }
    }

    @Test
    fun `a Keeper export imports its records`() {
        val path = "keeper/export.csv"
        assertTrue(KeeperImporter.detect(RealWorldCorpus.bytes(path)))
        // The file's own row count.
        assertEquals(3, KeeperImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.size)
    }

    /**
     * The file's own inventory, read off the fixture: three rows, the folder `Foo` with the child
     * `Baz` under it (written `Foo\\Baz` in the CSV cell), and the third row's `TFC:Keeper` field
     * carrying an otpauth URI whose secret is `JBSWY3DPEHPK3PXP`.
     */
    @Test
    fun `the Keeper CSV carries folders, custom fields and a TOTP seed`() {
        val path = "keeper/export.csv"
        val parsed = KeeperImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)

        assertEquals(listOf("Foo", "Baz"), parsed.folders.map { it.name }, "folder names")
        val foo = parsed.folders.first { it.name == "Foo" }
        val baz = parsed.folders.first { it.name == "Baz" }
        assertEquals(null, foo.parentId, "Foo is top-level")
        assertEquals(foo.id, baz.parentId, "Baz nests under Foo")

        val second = parsed.items.first { it.title == "Bar 1" }
        assertEquals(
            listOf("Account ID" to "12345", "Org ID" to "54321"),
            second.customFields.map { it.name to it.value },
            "the repeating name/value pairs",
        )

        val third = parsed.items.first { it.title == "Bar 2" }
        assertEquals(baz.id, third.folderId, "the Foo\\Baz row lands in Baz")
        val totp = assertNotNull((third.content as Login).totp, "TFC:Keeper was not read")
        assertEquals("JBSWY3DPEHPK3PXP", totp.secretBase32)
    }

    /**
     * The JSON half, on the CLI variant: 24 records of which three are `sshKeys`, and a record's
     * `folders[].folder` path nests on single backslashes while `\\` is an escaped literal one.
     */
    @Test
    fun `the Keeper JSON export carries every record it holds`() {
        val path = "keeper/export-cli.json"
        val parsed = KeeperImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)

        assertEquals(24, parsed.items.size + parsed.skipped.size, "records in the file")
        assertEquals(
            List(3) { SkipReason.SshKey },
            parsed.skipped.map { it.reason },
            "the three sshKeys records, named rather than dropped",
        )
        assertTrue(parsed.timestampsKnown, "Keeper JSON writes last_modified")
        // `"Development\\Name/with\\\\both/slashes"` decodes to
        // `Development\Name/with\\both/slashes`: two segments, the second holding a literal `\`.
        assertTrue(
            parsed.folders.any { it.name == """Name/with\both/slashes""" },
            "the escaped-backslash folder name: ${parsed.folders.map { it.name }}",
        )
    }
}

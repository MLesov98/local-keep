package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.failureReason
import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.File
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 1Password, from `src/test/resources/realworld/onepassword/` (see `PROVENANCE.md` there).
 *
 * `1PasswordExport.1pux` is the real thing — a ZIP with `export.data`, `export.attributes` and two
 * image attachments — and is the only file in the corpus that exercises [OnePuxImporter]'s archive
 * path end to end. The loose `export.data-*.json` files are the *inner* document of other exports,
 * and the `.1pif` files are 1Password's pre-1PUX text format; neither is a `.1pux` archive, and
 * both are recorded below as the named refusals they produce.
 */
class RealWorldOnePasswordImportTest {

    private val archive = "onepassword/1PasswordExport.1pux"

    /** One archive entry's own uncompressed size, read from the zip rather than from the importer. */
    private fun entrySize(name: String): Long =
        ZipInputStream(ByteArrayInputStream(RealWorldCorpus.bytes(archive))).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == name }
            zip.readBytes().size.toLong()
        }

    /** Items in the archive's own `export.data`, counted without going through the importer. */
    private fun itemsInArchive(): Int {
        val data = ZipInputStream(ByteArrayInputStream(RealWorldCorpus.bytes(archive))).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == "export.data" }
                ?: error("the fixture is no longer a 1PUX archive")
            zip.readBytes().toString(Charsets.UTF_8)
        }
        // One `"uuid"` per item, inside the `items` arrays. Counting `"categoryUuid"` is the safer
        // marker: 1Password writes exactly one per item and nowhere else in the document.
        return Regex("\"categoryUuid\"").findAll(data).count()
    }

    @Test
    fun `the real 1PUX archive is recognised and accounts for every item in it`() {
        assertTrue(OnePuxImporter.detect(RealWorldCorpus.bytes(archive)), "the archive was not recognised")
        val parsed = OnePuxImporter.parse(RealWorldCorpus.bytes(archive)).orFail(archive)
        assertEquals(9, itemsInArchive(), "the fixture no longer holds nine items")
        assertEquals(
            9,
            parsed.items.size + parsed.skipped.size,
            "every item in both vaults must become a vault item or a named skip",
        )
        assertEquals(true, parsed.timestampsKnown, "1Password writes real created/updated times")
    }

    @Test
    fun `the five mappable categories cross over with their own content`() {
        val parsed = OnePuxImporter.parse(RealWorldCorpus.bytes(archive)).orFail(archive)
        val byTitle = parsed.items.associateBy { it.title }
        // "KeePassXC Logo" is the Document (006) item, mappable since its file is read — see
        // `a 1PUX attachment reaches the vault`.
        assertEquals(setOf("Login", "Credit Card", "Secure Note", "Identity", "KeePassXC Logo"), byTitle.keys)

        val login = byTitle.getValue("Login")
        assertEquals(true, login.favorite, "the export's favIndex of 1 on exactly this item")
        assertEquals(listOf("website"), login.tags, "1Password's own tag on this item")
        val content = login.content as Login
        assertEquals("team@keepassxc.org", content.username)
        assertEquals(
            listOf("https://keepassxc.org", "https://twitter.com"),
            content.uris.map { it.uri },
            "the primary URL and the extra one the item carries",
        )
        // The archive's one-time-password field holds the placeholder seed `DFDFDEF`, which is not
        // decodable Base32 — so it cannot become a Totp, and must instead be rescued into a HIDDEN
        // custom field. Before WP11 this importer dropped it outright, on an item that still
        // reported as fully imported.
        assertEquals(null, content.totp)
        assertEquals(
            listOf("DFDFDEF"),
            login.customFields.filter { it.type == CustomFieldType.HIDDEN }.map { it.value },
            "the raw seed must survive as a rescued HIDDEN field, not be dropped",
        )

        assertTrue(byTitle.getValue("Credit Card").content is Card)
        assertTrue(byTitle.getValue("Identity").content is Identity)
        val note = byTitle.getValue("Secure Note")
        assertEquals(listOf("Note", "Secret Stuff"), note.tags)
        assertTrue((note.content as Note).body.isNotBlank())
    }

    /**
     * What the archive holds that this app has no type for, each named rather than dropped: the
     * 1Password categories 101 Bank Account and 109 Wireless Router, the standalone-password
     * category 005, and the archived login.
     *
     * A Bank Account is the user's own record — an import that lost it silently and then offered to
     * delete the export would have destroyed the only copy. 006 Document left this list on
     * 2026-09-17: its file is now read out of the archive and the item crosses over.
     */
    @Test
    fun `every unmappable item is named with a reason the wizard can render`() {
        val parsed = OnePuxImporter.parse(RealWorldCorpus.bytes(archive)).orFail(archive)
        assertEquals(4, parsed.skipped.size)
        assertEquals(
            setOf("005", "109", "101"),
            parsed.skipped.mapNotNull { (it.reason as? SkipReason.UnsupportedOnePasswordCategory)?.categoryUuid }
                .toSet(),
            "1Password's own category ids, passed through rather than guessed at",
        )
        assertEquals(
            listOf("Login Archived"),
            parsed.skipped.filter { it.reason == SkipReason.OnePasswordArchived }.map { it.title },
        )
    }

    /**
     * The loose `export.data` documents are the JSON *inside* a `.1pux`, and the `.1pif` files are
     * the older text format. Neither is an archive, so both must fail as "no export.data in this
     * zip" rather than as a malformed or unrecognised file — the wizard renders that as a prompt to
     * pick the `.1pux` itself.
     */
    @Test
    fun `a loose export-data document or a legacy 1PIF fails with the missing-archive reason`() {
        listOf(
            "onepassword/export.data-example.json",
            "onepassword/export.data-login.json",
            "onepassword/export.data-identity.json",
            "onepassword/export.data-sanitized.json",
            "onepassword/legacy-login.1pif",
            "onepassword/legacy-identity.1pif",
            "onepassword/legacy-windows-opvault.1pif",
        ).forEach { path ->
            assertEquals(
                ImportFailureReason.OnePuxMissingExportData,
                OnePuxImporter.parse(RealWorldCorpus.bytes(path)).failureReason(path),
                "$path failure reason",
            )
        }
    }

    /**
     * `1PasswordExport.1pux` carries `files/oakiw7lqbp53fgqrxrk63su2gu__keepassxc.png` as the file a
     * Document item *is*. Every number below is read out of the archive rather than recorded from a
     * run: the entry's own uncompressed size, and the item's title from `export.data`.
     */
    @Test
    fun `a 1PUX attachment reaches the vault`() {
        val parsed = OnePuxImporter.parse(RealWorldCorpus.bytes(archive)).orFail(archive)
        val document = parsed.items.single { it.title == "KeePassXC Logo" }
        val content = document.content as File
        assertEquals("keepassxc.png", content.filename)
        assertEquals("image/png", content.mime)
        assertEquals(entrySize("files/oakiw7lqbp53fgqrxrk63su2gu__keepassxc.png"), content.sizeBytes)
        assertEquals(
            "",
            content.blobRef,
            "core/import has no vault key and no blob store — the wizard seals the bytes and fills this",
        )

        val payload = parsed.attachmentPayloads.getValue(document.id).single()
        assertEquals("keepassxc.png", payload.filename)
        assertEquals(entrySize("files/oakiw7lqbp53fgqrxrk63su2gu__keepassxc.png"), payload.bytes.size.toLong())
        // The real bytes, not a placeholder: a PNG starts with the eight-byte signature.
        assertEquals(
            listOf(0x89, 0x50, 0x4E, 0x47),
            payload.bytes.take(4).map { it.toInt() and 0xFF },
        )
    }

    /**
     * The archive's other `files/` entry is a vault *avatar*, which no record points at. Reading it
     * would spend the decompression cap on bytes that can never reach the vault, and would attach a
     * picture of a vault icon to something.
     */
    @Test
    fun `an unreferenced file in the archive is not read`() {
        val parsed = OnePuxImporter.parse(RealWorldCorpus.bytes(archive)).orFail(archive)
        assertEquals(1, parsed.attachmentPayloads.values.sumOf { it.size })
    }

    /**
     * The control for the branch above: a loose `export.data` document names a `documentId` it has
     * no archive to resolve, so nothing may be invented for it.
     */
    @Test
    fun `a document record with no archive entry is skipped rather than imported empty`() {
        val parsed = OnePuxImporter.parse(RealWorldCorpus.bytes(archive)).orFail(archive)
        assertTrue(parsed.items.none { it.content is File && (it.content as File).filename.isBlank() })
    }

    /**
     * V3-03, on the real archive: importing this `.1pux` twice must leave the vault the size the
     * first import made it.
     *
     * Measured on a device 2026-09-17 before the fix: four of the five items previewed as "already
     * look like entries you have" and the File item `KeePassXC Logo` did not — it stayed selected,
     * `Import 1 item` ran, the header went 127 → 128 and the vault showed two identical rows.
     * Attachments are the largest items a vault holds, so this was the duplicate that cost most.
     *
     * The second pass is planned as well as merged: a preview that says "will be added" while the
     * merge de-duplicates would be the same defect wearing the other face.
     */
    @Test
    fun `importing the same 1PUX twice adds nothing the second time`() {
        val parsed = OnePuxImporter.parse(RealWorldCorpus.bytes(archive)).orFail(archive)
        assertTrue(
            parsed.items.any { it.content is File },
            "the fixture must carry a File item, or this test cannot see the defect it guards",
        )
        val first = ImportMerger.merge(existing = emptyList(), imported = parsed.items)
        assertEquals(parsed.items.size, first.size)

        // Re-parsed rather than re-used: a second import reads the file again and mints fresh item
        // ids, which is exactly why identity has to come from the content.
        val again = OnePuxImporter.parse(RealWorldCorpus.bytes(archive)).orFail(archive)
        val plan = ImportMerger.plan(existing = first, imported = again.items, again.timestampsKnown)
        assertEquals(
            emptyList(),
            plan.filter { it.disposition == ImportMerger.Disposition.NEW }.map { it.item.title },
            "every row must be recognised as already in the vault",
        )
        assertEquals(
            first.size,
            ImportMerger.merge(first, again.items, again.timestampsKnown).size,
            "the second pass must not append the attachment again",
        )
    }

    /**
     * FORMAT GAP — 1Password's pre-1PUX `.1pif` is still what several export paths produce and what
     * old backups are saved as. It is a text file of `***uuid***\n{json}\n` blocks, not a zip.
     */
    @Test
    @Ignore("Format gap: legacy .1pif exports have no importer")
    fun `a legacy 1PIF export imports its entries`() {
        val path = "onepassword/legacy-login.1pif"
        assertTrue(OnePuxImporter.detect(RealWorldCorpus.bytes(path)))
        assertEquals(1, OnePuxImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.size)
    }
}

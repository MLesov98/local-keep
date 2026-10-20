package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Card
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proton Pass, from `src/test/resources/realworld/protonpass/` (see `PROVENANCE.md` there).
 *
 * Proton Pass's **CSV** was already supported and is covered by [ProtonPassCsvUsernameTest] — it is
 * the export whose `email` and `username` columns forced them apart in `CsvColumnMapper`. What the
 * corpus added was the **JSON** export, which is the shape Proton Pass's desktop and web apps
 * actually produce: a `{version,userId,encrypted,vaults}` document where `vaults` is an object
 * keyed by vault id, not an array.
 */
class RealWorldProtonPassImportTest {

    private val jsonFiles = listOf(
        "protonpass/export.json",
        "protonpass/export-bitwarden-fixture.json",
        "protonpass/export-dedicated-types.json",
    )

    /**
     * The near-miss the corpus recorded, still pinned: every one of these files carries
     * `"encrypted": false` at the top level, which is a key `BitwardenExport` declares. Bitwarden's
     * recognition does not rest on it — it needs `items`/`folders`/`collections` too — and if it
     * ever did, a Proton Pass export would be claimed by the Bitwarden importer and imported as
     * nothing at all, with a success message. [ProtonPassJsonImporter] does not rest on it either:
     * it recognises `vaults` being a JSON *object*, which no other format here writes.
     */
    @Test
    fun `Proton Pass JSON is claimed by the Proton Pass importer and by no other`() {
        jsonFiles.forEach { path ->
            assertTrue(
                "\"encrypted\"" in RealWorldCorpus.text(path),
                "$path no longer carries the top-level key this test is about",
            )
            val bytes = RealWorldCorpus.bytes(path)
            assertTrue(ProtonPassJsonImporter.detect(bytes), "$path was not recognised")
            listOf(BitwardenJsonImporter, AegisJsonImporter, OnePuxImporter, CsvImporter).forEach { importer ->
                assertFalse(
                    importer.detect(bytes),
                    "$path was claimed by ${importer::class.simpleName} — it is not that format",
                )
            }
        }
    }

    @Test
    fun `a Proton Pass JSON export imports its vaults`() {
        val path = "protonpass/export.json"
        val parsed = ProtonPassJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        // The file's own inventory: the "Personal" vault holds 5 items and "Test" holds 1.
        assertEquals(6, parsed.items.size + parsed.skipped.size)
        assertEquals(
            listOf("Personal", "Test"),
            parsed.folders.map { it.name }.sorted(),
            "one folder per vault",
        )
    }

    /**
     * The fixture's own facts, item by item. `Test Login` carries two urls and an `otpauth://` uri
     * whose secret is `TOTPCODE`; `Test Card` carries `1234222233334444` expiring `2025-01`; and
     * `My Deleted Note` is `"state": 2`, Proton's trash.
     */
    @Test
    fun `the Proton Pass export maps logins, cards and its trash`() {
        val path = "protonpass/export.json"
        val parsed = ProtonPassJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)

        assertEquals(
            listOf("My Deleted Note" to SkipReason.SourceRecycleBin),
            parsed.skipped.map { it.title to it.reason },
            "the trashed item, named rather than imported",
        )

        val login = parsed.items.first { it.title == "Test Login" }
        val content = login.content as Login
        assertEquals(
            listOf("https://example.com/", "https://example2.com/"),
            content.uris.map { it.uri },
        )
        assertEquals("TOTPCODE", assertNotNull(content.totp, "totpUri was not read").secretBase32)
        assertEquals("My login secure note.", content.notes)

        val card = parsed.items.first { it.title == "Test Card" }.content as Card
        assertEquals("1234222233334444", card.number)
        assertEquals(1, card.expMonth, "2025-01 is January")
        assertEquals(2025, card.expYear)
        assertEquals("333", card.cvv)
        assertEquals("1234", card.pin)

        assertTrue(parsed.timestampsKnown, "Proton Pass writes createTime and modifyTime")
    }

    /**
     * An `alias` item's whole content is the generated address it carries, which sits beside the
     * item as `aliasEmail` rather than inside `content`. The Bitwarden-sourced fixture holds one.
     */
    @Test
    fun `an alias item becomes a login carrying its generated address`() {
        val path = "protonpass/export-bitwarden-fixture.json"
        val parsed = ProtonPassJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)

        val alias = parsed.items.first { it.title == "Alias" }.content as Login
        assertEquals("alias.removing005@passinbox.com", alias.username)
        // And the SSH key in the same vault is named rather than silently dropped.
        assertTrue(
            parsed.skipped.any { it.title == "SSH Key Item" && it.reason == SkipReason.SshKey },
            "skipped: ${parsed.skipped}",
        )
    }

    /**
     * The "dedicated types" fixture is three `custom`-typed items — a bank account, a driver's
     * licence and a passport — whose whole content is `extraFields`. Nothing here has a LocalKeep
     * item type, so each becomes a note that keeps every field rather than a counted loss.
     */
    @Test
    fun `the dedicated item types keep every field they carry`() {
        val path = "protonpass/export-dedicated-types.json"
        val parsed = ProtonPassJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)

        assertEquals(emptyList(), parsed.skipped, "nothing was dropped")
        assertEquals(3, parsed.items.size)

        val passport = parsed.items.first { it.title == "Test Passport" }
        assertEquals(
            listOf(
                "Full Name", "Passport Number", "Country", "Expiry Date", "Date of Birth",
                "Issuing Authority",
            ),
            passport.customFields.map { it.name },
            "every extraField the fixture lists, in order",
        )
        assertEquals("1234567890", passport.customFields.first { it.name == "Passport Number" }.value)
    }
}

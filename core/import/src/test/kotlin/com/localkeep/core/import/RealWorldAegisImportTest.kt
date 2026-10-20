package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.failureReason
import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Aegis Authenticator JSON, from `src/test/resources/realworld/aegis/` (see `PROVENANCE.md` there).
 *
 * The plain vault holds seven entries and is the corpus's best test of *naming what was dropped*:
 * three are HOTP (no counter field exists on [Totp]), one is a TOTP whose settings this app cannot
 * compute (SHA256, 7 digits, 20s), one is Steam (which is imported), and two are ordinary TOTP.
 * Every one of the seven has to be accounted for.
 */
class RealWorldAegisImportTest {

    private val plainFiles = listOf("aegis/plain.json", "aegis/plain-grouped-v2.json")

    /** The `db.entries` array length, counted in the file itself rather than through the importer. */
    private fun entryCount(path: String): Int {
        val root = Json.parseToJsonElement(RealWorldCorpus.text(path)) as JsonObject
        return ((root.getValue("db") as JsonObject).getValue("entries") as JsonArray).size
    }

    @Test
    fun `every plain vault is recognised and accounts for all seven entries`() {
        plainFiles.forEach { path ->
            assertTrue(AegisJsonImporter.detect(RealWorldCorpus.bytes(path)), "$path was not recognised as Aegis")
            val parsed = AegisJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            assertEquals(7, entryCount(path), "$path — the fixture no longer holds seven entries")
            assertEquals(
                7,
                parsed.items.size + parsed.skipped.size,
                "$path — every entry must become an item or a named skip",
            )
            // An Aegis entry carries no created/modified time, so the import instant means nothing.
            assertEquals(false, parsed.timestampsKnown, "$path timestampsKnown")
        }
    }

    @Test
    fun `the two supported TOTP entries and the Steam entry cross over with their own settings`() {
        val path = "aegis/plain.json"
        val parsed = AegisJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val byTitle = parsed.items.associate { it.title to it.content as Totp }
        assertEquals(setOf("Mason", "Elijah", "Sophia"), byTitle.keys)

        val sha1 = byTitle.getValue("Mason")
        assertEquals("Deno", sha1.issuer)
        assertEquals(TotpAlgorithm.SHA1, sha1.algorithm)
        assertEquals(6, sha1.digits)
        assertEquals(30, sha1.periodSec)

        val sha512 = byTitle.getValue("Elijah")
        assertEquals("Airbnb", sha512.issuer)
        assertEquals(TotpAlgorithm.SHA512, sha512.algorithm)
        assertEquals(8, sha512.digits)
        assertEquals(50, sha512.periodSec, "the export's own period, not the 30-second default")

        // Steam's parameters are fixed by the scheme, so the export's `digits: 5` is deliberately
        // not copied onto the model — the flag is what makes the code render.
        val steam = byTitle.getValue("Sophia")
        assertEquals(true, steam.steam)
        assertEquals("Boeing", steam.issuer)
    }

    /**
     * What was dropped, and under which reason. The three HOTP entries must not borrow
     * `UnsupportedTotpSettings`, which renders the algorithm/digits/period and would blame
     * parameters that are not the problem — that distinction is the point.
     */
    @Test
    fun `the four unimportable entries are named with the reason that fits each`() {
        val path = "aegis/plain.json"
        val parsed = AegisJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        assertEquals(4, parsed.skipped.size)
        assertEquals(
            listOf(SkipReason.UnsupportedAegisType("HOTP")),
            parsed.skipped.map { it.reason }.filterIsInstance<SkipReason.UnsupportedAegisType>().distinct(),
        )
        assertEquals(3, parsed.skipped.count { it.reason == SkipReason.UnsupportedAegisType("HOTP") })
        assertEquals(
            listOf(SkipReason.UnsupportedTotpSettings(algorithm = "SHA256", digits = 7, periodSec = 20)),
            parsed.skipped.map { it.reason }.filterIsInstance<SkipReason.UnsupportedTotpSettings>(),
            "the one entry whose settings really are the problem, quoted from the export",
        )
        assertEquals(
            setOf("James", "Benjamin", "Mason"),
            parsed.skipped.map { it.title }.toSet(),
            "each skip is named with the entry's own name, so the user can find it in their export",
        )
    }

    /**
     * An encrypted Aegis backup must say so. `detect` has to say yes first — the ciphertext `db` is
     * a JSON *string*, not an object, and recognising only the decrypted shape meant the user was
     * told the file was not recognised at all rather than that it needed decrypting.
     */
    @Test
    fun `an encrypted vault is recognised and fails with the encrypted reason`() {
        val path = "aegis/encrypted.json"
        assertTrue(AegisJsonImporter.detect(RealWorldCorpus.bytes(path)), "$path must be recognised")
        assertEquals(
            ImportFailureReason.ContainerEncrypted(SourceFormat.AEGIS),
            AegisJsonImporter.parse(RealWorldCorpus.bytes(path)).failureReason(path),
        )
    }

    /**
     * Grouping changes where entries are filed, not which ones cross over.
     */
    @Test
    fun `the grouped v2 vault imports the same entries as the ungrouped one`() {
        val plain = AegisJsonImporter.parse(RealWorldCorpus.bytes("aegis/plain.json")).orFail("aegis/plain.json")
        val grouped = AegisJsonImporter.parse(RealWorldCorpus.bytes("aegis/plain-grouped-v2.json"))
            .orFail("aegis/plain-grouped-v2.json")
        assertEquals(plain.items.map { it.title }, grouped.items.map { it.title })
        assertEquals(emptyList(), plain.folders, "the ungrouped vault mints no folder")
    }

    /**
     * **Gap 5, closed.** Aegis `db.version: 2` files each entry under a `group` name (`group1`,
     * `group2`, null in this fixture). [ParsedImport.folders] exists and both the CSV and KDBX
     * importers fill it; `AegisJsonImporter` never read `group`, so a user who organised their
     * codes into groups got them back in one flat list, on an import reporting full success.
     *
     * One folder, not two: `group2` holds only the HOTP entry this build skips, and a folder minted
     * for it would arrive in the vault empty.
     */
    @Test
    fun `an Aegis group becomes a folder`() {
        val path = "aegis/plain-grouped-v2.json"
        assertTrue("\"group\": \"group1\"" in RealWorldCorpus.text(path))
        assertTrue("\"group\": \"group2\"" in RealWorldCorpus.text(path), "the fixture's second group")
        val parsed = AegisJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        assertEquals(listOf("group1"), parsed.folders.map { it.name })
        val folderId = parsed.folders.single().id
        assertEquals(
            listOf("Mason", "Elijah"),
            parsed.items.filter { it.folderId == folderId }.map { it.title },
            "the two group1 entries are filed in it",
        )
        // Only the Steam entry: the fixture's other ungrouped TOTP entry ("James") is the
        // SHA256/7-digit one this build skips, and its HOTP namesake in `group2` is skipped too.
        assertEquals(
            listOf("Sophia"),
            parsed.items.filter { it.folderId == null }.map { it.title },
            "and the ungrouped ones are filed in nothing",
        )
    }
}

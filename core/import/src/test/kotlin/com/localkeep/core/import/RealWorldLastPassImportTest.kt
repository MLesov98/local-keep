package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Card
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LastPass CSV, from `src/test/resources/realworld/lastpass/` (see `PROVENANCE.md` there).
 *
 * LastPass is behind `ImportSource.CSV` ("Other") and so goes through [CsvImporter]. Its header is
 * the reason `ColumnMapping.folderSeparator` exists: `grouping` nests with a backslash where every
 * other export nests with a slash.
 */
class RealWorldLastPassImportTest {

    private val files = listOf(
        "lastpass/login-with-totp.csv",
        "lastpass/secure-note-bank-account.csv",
        "lastpass/secure-note-credit-card.csv",
        "lastpass/secure-note-drivers-license.csv",
        "lastpass/secure-note-passport.csv",
    )

    @Test
    fun `every LastPass export is recognised and yields one item per data row`() {
        files.forEach { path ->
            assertTrue(CsvImporter.detect(RealWorldCorpus.bytes(path)), "$path was not recognised as a CSV export")
            val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            assertEquals(RealWorldCorpus.csvDataRows(path), parsed.items.size, "$path item count")
            assertEquals(emptyList(), parsed.skipped, "$path skipped nothing")
        }
    }

    @Test
    fun `a login row carries its TOTP seed, note and unset favourite`() {
        val path = "lastpass/login-with-totp.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val item = parsed.items.single()
        assertEquals("example.com", item.title)
        // `fav` is `0` in this row, and `0` must read as "no" rather than as "a value, so yes".
        assertEquals(false, item.favorite)
        val login = item.content as Login
        assertEquals("someUser", login.username)
        assertEquals("myPassword", login.password)
        assertEquals("Y64VEVMBTSXCYIWRSHRNDZW62MPGVU2G", login.totp?.secretBase32)
        assertEquals("super secure notes", login.notes)
    }

    /**
     * Three of the secure-note fixtures carry `grouping = "Tools Test Items"`, which must become a
     * folder — this is the column S5 was fixed for, and the one that distinguishes LastPass from
     * every other CSV in the corpus.
     */
    @Test
    fun `the grouping column becomes a folder`() {
        listOf(
            "lastpass/secure-note-bank-account.csv",
            "lastpass/secure-note-drivers-license.csv",
            "lastpass/secure-note-passport.csv",
        ).forEach { path ->
            val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            assertEquals(listOf("Tools Test Items"), parsed.folders.map { it.name }, "$path folders")
            assertEquals(parsed.folders.single().id, parsed.items.single().folderId, "$path item is filed in it")
        }
        // The login fixture's `grouping` cell is empty, so it must mint no folder at all.
        val unfiled = CsvImporter.parse(RealWorldCorpus.bytes("lastpass/login-with-totp.csv"))
            .orFail("lastpass/login-with-totp.csv")
        assertEquals(emptyList(), unfiled.folders)
        assertEquals(null, unfiled.items.single().folderId)
    }

    /**
     * A secure note with no closer home stays a [Note] carrying the blob verbatim — including the
     * `NoteType:` line LastPass writes first, and every field of a template this app has no type
     * for. Bank Account is that case: nothing here models routing numbers and IBANs.
     */
    @Test
    fun `a secure note with no matching type keeps its whole body`() {
        val path = "lastpass/secure-note-bank-account.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val item = parsed.items.single()
        val note = item.content as Note
        assertTrue(note.body.startsWith("NoteType:Bank Account\n"), "note body: ${note.body.take(40)}")
        assertTrue("IBAN Number:1234" in note.body, "every line of the blob survives")
        assertEquals("Test Bank Account", item.title)
    }

    /**
     * The `4'0""` in the driver's-licence fixture is the one place in the corpus where an RFC 4180
     * escaped quote sits inside a multi-line quoted cell. It has no home on an [Identity], so it
     * arrives as a custom field under LastPass's own label — which is where the escaping has to
     * still be right.
     */
    @Test
    fun `an escaped quote survives into the field that carries it`() {
        val path = "lastpass/secure-note-drivers-license.csv"
        val item = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single()
        assertEquals("4'0\"", item.customFields.first { it.name == "Height" }.value)
        assertEquals("Test Drivers License", item.title)
    }

    /**
     * **Gap 3, closed.** LastPass writes `http://sn` in the `url` column of every secure note, as a
     * sentinel meaning "this row is a note, not a login". Nothing here knew that, so the row was a
     * non-blank URL and became a [Login] carrying `http://sn` as a real address: autofill-eligible
     * for a host called `sn`, and counted by the health dashboard as a login with a blank password.
     *
     * No row behind the sentinel is a login any more, whatever it maps to.
     */
    @Test
    fun `a LastPass secure note never imports as a Login`() {
        listOf(
            "lastpass/secure-note-bank-account.csv",
            "lastpass/secure-note-credit-card.csv",
            "lastpass/secure-note-drivers-license.csv",
            "lastpass/secure-note-passport.csv",
        ).forEach { path ->
            val content = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single().content
            assertTrue(content !is Login, "$path imported as a Login: $content")
        }
        // Control: the sentinel is what decides it, not the file. An ordinary LastPass login row
        // still imports as a Login with its real URL.
        val login = CsvImporter.parse(RealWorldCorpus.bytes("lastpass/login-with-totp.csv"))
            .orFail("lastpass/login-with-totp.csv").items.single().content as Login
        assertEquals("http://example.com", login.uris.single().uri)
    }

    /** A `NoteType:Credit Card` blob is a [Card], with the values in the fields that mean them. */
    @Test
    fun `a credit-card secure note becomes a Card`() {
        val path = "lastpass/secure-note-credit-card.csv"
        val card = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single().content as Card
        assertEquals("John Doe", card.holder)
        assertEquals("1234567812345678", card.number)
        assertEquals("123", card.cvv)
        // "June,2020", the export's own spelling.
        assertEquals(6, card.expMonth)
        assertEquals(2020, card.expYear)
        assertEquals("some text", card.notes)
    }

    /** `Passport` and `Driver's License` are each an [Identity], in the field that names them. */
    @Test
    fun `a passport and a licence each become an Identity`() {
        val passport = CsvImporter.parse(RealWorldCorpus.bytes("lastpass/secure-note-passport.csv"))
            .orFail("lastpass/secure-note-passport.csv").items.single()
        val asPassport = passport.content as Identity
        assertEquals("Bilbo", asPassport.firstName)
        assertEquals("Baggins", asPassport.lastName)
        assertEquals("1234567890", asPassport.passport)
        assertEquals("The Shire", asPassport.address.country)
        // The template's own fields that this app has no home for are carried, not dropped.
        assertEquals("Shire-folk", passport.customFields.first { it.name == "Nationality" }.value)

        val licence = CsvImporter.parse(RealWorldCorpus.bytes("lastpass/secure-note-drivers-license.csv"))
            .orFail("lastpass/secure-note-drivers-license.csv").items.single()
        val asLicence = licence.content as Identity
        assertEquals("1234567890", asLicence.license)
        assertEquals("", asLicence.passport, "a licence number is not a passport number")
        assertEquals("Hobbiton", asLicence.address.city)
        assertEquals("Bag End, Bagshot Row, Underhill", asLicence.address.line1)
    }
}

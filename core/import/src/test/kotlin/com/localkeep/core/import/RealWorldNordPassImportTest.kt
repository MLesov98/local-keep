package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Card
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NordPass CSV, from `src/test/resources/realworld/nordpass/` (see `PROVENANCE.md` there).
 *
 * NordPass writes one unified 22-column schema for every record kind and distinguishes them with a
 * `type` cell (`password`, `note`, `credit_card`, `identity`). Because the header always carries
 * `username` and `password`, [CsvImporter] recognises every one of these files — including the ones
 * whose rows are not logins at all, which is why the card and identity cases below are asserted as
 * the gaps they are rather than left to look like successes.
 */
class RealWorldNordPassImportTest {

    private val files = listOf(
        "nordpass/login.csv",
        "nordpass/login-with-additinal-urls.csv",
        "nordpass/secure-note.csv",
        "nordpass/card.csv",
        "nordpass/identity.csv",
    )

    @Test
    fun `every NordPass export is recognised and accounts for every data row`() {
        files.forEach { path ->
            assertTrue(CsvImporter.detect(RealWorldCorpus.bytes(path)), "$path was not recognised as a CSV export")
            val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            assertEquals(
                RealWorldCorpus.csvDataRows(path),
                parsed.items.size + parsed.skipped.size,
                "$path — every data row must become an item or a named skip, never neither",
            )
        }
    }

    @Test
    fun `a login row keeps its credentials, note and folder`() {
        val path = "nordpass/login.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val item = parsed.items.single()
        assertEquals("SomeVaultItemName", item.title)
        assertEquals(listOf("SomeFolderForVaultItem"), parsed.folders.map { it.name })
        assertEquals(parsed.folders.single().id, item.folderId)
        val login = item.content as Login
        assertEquals("hello@bitwarden.com", login.username)
        assertEquals("someStrongPassword", login.password)
        assertEquals("https://example.com", login.uris.single().uri)
        assertEquals("Some note for the VaultItem", login.notes)
    }

    /**
     * The secure-note file holds two rows: a bare folder declaration with every cell but `name`
     * empty, and the note itself. The first has nothing left to carry and must be named in
     * `skipped`; the second must become a [Note] filed in that folder.
     */
    @Test
    fun `a secure note becomes a Note and the bare folder row is named as skipped`() {
        val path = "nordpass/secure-note.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        assertEquals(listOf(SkipReason.NoLoginFields), parsed.skipped.map { it.reason })
        assertEquals("notesFolder", parsed.skipped.single().title)
        val item = parsed.items.single()
        assertEquals("MySuperSecureNoteTitle", item.title)
        assertEquals("MySuperSecureNote", (item.content as Note).body)
        assertEquals(listOf("notesFolder"), parsed.folders.map { it.name })
    }

    /**
     * **Gaps 6 and 7, closed.** NordPass writes one 22-column schema for every record kind and says
     * which with a `type` cell. A `credit_card` row fills `cardholdername`/`cardnumber`/`cvc`/
     * `expirydate` and leaves url, username and password empty, so the generic mapper saw a row
     * that was not a login and nothing in `notes`/`fields` to rescue: reported as
     * [SkipReason.NoLoginFields], honest but with the card number carried nowhere.
     */
    @Test
    fun `a card row becomes a Card`() {
        val path = "nordpass/card.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        assertEquals(emptyList(), parsed.skipped)
        val item = parsed.items.single()
        assertEquals("SomeVisa", item.title)
        val card = item.content as Card
        assertEquals("SomeHolder", card.holder)
        assertEquals("4024007103939509", card.number)
        assertEquals("123", card.cvv)
        // "01 / 22", the export's own spelling, and a two-digit year is 20xx.
        assertEquals(1, card.expMonth)
        assertEquals(2022, card.expYear)
        // Read off the digits, not off a brand column NordPass does not export.
        assertEquals(CardBrand.VISA, card.brand)
    }

    /**
     * The identity row was the worse half of the same gap: it *does* fill `email`, so it imported
     * as a [Login] whose username was the person's email address, with their name, phone and postal
     * address dropped without a word.
     */
    @Test
    fun `an identity row becomes an Identity`() {
        val path = "nordpass/identity.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val identity = parsed.items.single().content as Identity
        assertEquals("#fullName", identity.firstName)
        assertEquals("hello@bitwarden.com", identity.email)
        assertEquals("123456789", identity.phone)
        assertEquals("Test street 123", identity.address.line1)
        assertEquals("additional addressinfo", identity.address.line2)
        assertEquals("Cologne", identity.address.city)
        assertEquals("North-Rhine-Westphalia", identity.address.state)
        assertEquals("Germany", identity.address.country)
        assertEquals("123456", identity.address.zip)
    }

    /**
     * **Gap 7.** NordPass's `custom_fields` cell is a JSON array of `{label,type,value}`, and
     * `FIELDS_HEADERS` matched only the bare `fields` Bitwarden writes. The login fixture has two,
     * one of them hidden, and both were dropped on an import that reported success. A field the
     * source masked must arrive masked.
     */
    @Test
    fun `a login row keeps its custom fields, hidden ones still hidden`() {
        val path = "nordpass/login.csv"
        val item = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single()
        assertEquals(listOf("textLabel", "hiddenLabel"), item.customFields.map { it.name })
        assertEquals(listOf("text value", "hidden value"), item.customFields.map { it.value })
        assertEquals(
            listOf(CustomFieldType.TEXT, CustomFieldType.HIDDEN),
            item.customFields.map { it.type },
        )
        // Control: the sibling fixture's cell is empty, and an empty cell mints no fields.
        val without = CsvImporter.parse(RealWorldCorpus.bytes("nordpass/login-with-additinal-urls.csv"))
            .orFail("nordpass/login-with-additinal-urls.csv").items.single()
        assertEquals(emptyList(), without.customFields)
    }
}

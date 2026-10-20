package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dashlane's CSV exports, from `src/test/resources/realworld/dashlane/` (see `PROVENANCE.md` there).
 *
 * Dashlane does not export one file — it exports **seven**, one per record kind, and only
 * `credentials.csv` / `credentials-otpurl.csv` are logins. The other five carry identities, payment
 * cards, ID documents and secure notes in schemas with no password column at all, so [CsvImporter]
 * does not recognise them; [DashlaneImporter] recognises four of those five by their exact header
 * set (the fifth is `credentials-otpurl.csv`'s sibling, already covered above).
 */
class RealWorldDashlaneImportTest {

    private val credentialFiles = listOf("dashlane/credentials.csv", "dashlane/credentials-otpurl.csv")

    private val nonCredentialFiles = listOf(
        "dashlane/id.csv",
        "dashlane/payments.csv",
        "dashlane/personal-info.csv",
        "dashlane/multiple-personal-info.csv",
        "dashlane/securenotes.csv",
    )

    @Test
    fun `both credential exports are recognised and yield one item per data row`() {
        credentialFiles.forEach { path ->
            assertTrue(CsvImporter.detect(RealWorldCorpus.bytes(path)), "$path was not recognised as a CSV export")
            val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            assertEquals(RealWorldCorpus.csvDataRows(path), parsed.items.size, "$path item count")
            assertEquals(emptyList(), parsed.skipped, "$path skipped nothing")
            val item = parsed.items.single()
            assertEquals("example.com", item.title, "$path title")
            val login = item.content as Login
            assertEquals("jdoe", login.username, "$path username — not the empty username2/username3")
            assertEquals("somePassword", login.password)
            assertEquals("https://www.example.com", login.uris.single().uri, "$path url")
            assertEquals("some note for example.com", login.notes, "$path note")
        }
    }

    /**
     * The credential exports stay with the generic mapper, which is what reads them.
     *
     * Asserted because [DashlaneImporter] shares their brand and could plausibly grow to claim
     * them: it must not, or two importers would race on registry order for a file one of them
     * already reads correctly.
     */
    @Test
    fun `the credential exports are not claimed by the Dashlane importer`() {
        credentialFiles.forEach { path ->
            assertFalse(
                DashlaneImporter.detect(RealWorldCorpus.bytes(path)),
                "$path belongs to CsvImporter — it has a password column",
            )
        }
    }

    /**
     * Dashlane writes its 2FA column two ways — `otpSecret` for a bare seed, `otpUrl` for a full
     * `otpauth://` URI — and both ship as real fixtures here. Neither fixture's value is a decodable
     * secret (they are the upstream suite's placeholders), so neither can become a
     * [com.localkeep.core.model.Totp]; what matters is that the column is *read* at all, so the
     * value is rescued into a HIDDEN custom field instead of vanishing.
     */
    @Test
    fun `both spellings of the 2FA column are read`() {
        mapOf(
            "dashlane/credentials.csv" to "someTOTPSeed",
            "dashlane/credentials-otpurl.csv" to "anotherTOTPSeed",
        ).forEach { (path, seed) ->
            val header = CsvColumnMapper.parseRows(RealWorldCorpus.bytes(path)).first()
            assertEquals(8, CsvColumnMapper.autoDetect(header).totpCol, "$path 2FA column index")
            val item = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single()
            val rescued = item.customFields.single()
            assertEquals(seed, rescued.value, "$path rescued seed")
            assertEquals(CustomFieldType.HIDDEN, rescued.type, "$path rescued seed is hidden")
        }
    }

    /**
     * The five non-credential exports still have no password column, so the *generic* mapper still
     * refuses them — being recognised by it was never the same as being understood
     * (`payments.csv`'s card number would have landed in no field at all). [DashlaneImporter]
     * claims them instead, by exact header set.
     */
    @Test
    fun `the identity, payment, document and note exports are claimed by the Dashlane importer only`() {
        nonCredentialFiles.forEach { path ->
            val bytes = RealWorldCorpus.bytes(path)
            assertFalse(CsvImporter.detect(bytes), "$path is recognised by the generic mapper — expectation is stale")
            assertTrue(DashlaneImporter.detect(bytes), "$path was not recognised")
            listOf(KeeperImporter, EnpassImporter, ProtonPassJsonImporter).forEach { importer ->
                assertFalse(
                    importer.detect(bytes),
                    "$path was claimed by ${importer::class.simpleName} — it is not that format",
                )
            }
        }
    }

    @Test
    fun `a Dashlane payments export imports its cards`() {
        val path = "dashlane/payments.csv"
        assertTrue(DashlaneImporter.detect(RealWorldCorpus.bytes(path)))
        assertEquals(2, DashlaneImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.size)
    }

    /**
     * `payments.csv`'s own two rows: a `bank` account and a `credit_card`. The card's number is
     * `41111111111111111`; the bank row has a routing and an account number, which [Card] has
     * nowhere to put, so it becomes a note whose fields survive as HIDDEN custom fields.
     */
    @Test
    fun `a bank row becomes a note and a credit card row becomes a card`() {
        val path = "dashlane/payments.csv"
        val parsed = DashlaneImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)

        val bank = parsed.items.first { it.title == "John's savings account" }
        assertTrue(bank.content is Note, "a bank account is not a payment card")
        assertEquals(
            listOf("routing_number", "account_number"),
            bank.customFields.filter { it.type == CustomFieldType.HIDDEN }.map { it.name },
            "the two secret columns, masked",
        )
        assertEquals("routingNumber", bank.customFields.first { it.name == "routing_number" }.value)

        val card = parsed.items.first { it.content is Card }.content as Card
        assertEquals("41111111111111111", card.number)
        assertEquals("John Doe", card.holder)
        assertEquals(1, card.expMonth)
        assertEquals(2023, card.expYear)
        assertEquals("123", card.cvv)
        assertFalse(parsed.timestampsKnown, "no Dashlane CSV carries a per-row time")
    }

    /**
     * `id.csv`'s five rows, one per document kind, each with its number in the [Identity] field
     * that exists for it — and `card` / `tax_number`, which have none, keeping theirs under the
     * source's own `number` column name.
     */
    @Test
    fun `the id export files each document number in its own field`() {
        val path = "dashlane/id.csv"
        val parsed = DashlaneImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)

        assertEquals(5, parsed.items.size, "one item per data row")
        val identities = parsed.items.map { it.content as Identity }
        assertEquals("123123123", identities.single { it.passport.isNotBlank() }.passport)
        assertEquals("1234556", identities.single { it.license.isNotBlank() }.license)
        assertEquals("123123123", identities.single { it.ssn.isNotBlank() }.ssn)
        assertEquals("DC", identities.single { it.license.isNotBlank() }.address.state)

        val taxNumber = parsed.items.first { item ->
            item.customFields.any { it.name == "type" && it.value == "tax_number" }
        }
        assertEquals("123123123", taxNumber.customFields.first { it.name == "number" }.value)
    }

    /**
     * A document with no name is not titled by its `type` column: "tax_number" is the source's
     * internal key, not anything the user called the row, and it read in the list as a title they
     * had typed. The row falls back to the same placeholder every other nameless row gets, and its
     * `type` still travels as a custom field, so nothing is lost.
     */
    @Test
    fun `a nameless id row is untitled rather than titled by its type key`() {
        val csv = "type,number,name,issue_date,expiration_date,place_of_issue,state\n" +
            "tax_number,123123123,,,,,\n" +
            "passport,555,Jane Doe,,,,\n"
        val parsed = DashlaneImporter.parse(csv.toByteArray()).orFail("inline id.csv")

        val titles = parsed.items.map { it.title }
        assertTrue("Untitled row" in titles, "the nameless row must use the placeholder, got $titles")
        assertFalse("tax_number" in titles, "a type key was used as a title, got $titles")
        // Control: a row that does have a name keeps it.
        assertTrue("Jane Doe" in titles, "a named row lost its name, got $titles")
        val nameless = parsed.items.first { it.title == "Untitled row" }
        assertTrue(
            nameless.customFields.any { it.name == "type" && it.value == "tax_number" },
            "the type key must still be carried as a custom field",
        )
    }

    /**
     * `personal-info.csv` is one row per *fact*, not per person, and `multiple-personal-info.csv`
     * carries a second `name` row — two people in one file, with nothing saying which of the other
     * rows belongs to which. One item per row states exactly what the export does.
     */
    @Test
    fun `the personal-info export makes one item per row`() {
        val single = DashlaneImporter.parse(RealWorldCorpus.bytes("dashlane/personal-info.csv"))
            .orFail("dashlane/personal-info.csv")
        assertEquals(5, single.items.size, "the file's five rows")

        val multiple = DashlaneImporter.parse(RealWorldCorpus.bytes("dashlane/multiple-personal-info.csv"))
            .orFail("dashlane/multiple-personal-info.csv")
        assertEquals(6, multiple.items.size, "the file's six rows")
        assertEquals(
            listOf("John", "Jane"),
            multiple.items.map { it.content as Identity }.map { it.firstName }.filter { it.isNotBlank() },
            "both people in the file",
        )

        val address = single.items.first { it.title == "John's home address" }.content as Identity
        assertEquals("1 some street", address.address.line1)
        assertEquals("some city", address.address.city)
        assertEquals("123123", address.address.zip)
        assertEquals("de", address.address.country)
    }

    /** `securenotes.csv` is `title,note` and has nothing else to decide. */
    @Test
    fun `the secure-notes export becomes notes`() {
        val path = "dashlane/securenotes.csv"
        val parsed = DashlaneImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val item = parsed.items.single()
        assertEquals("01", item.title)
        assertEquals("test", (item.content as Note).body)
    }
}

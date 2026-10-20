package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.import.RealWorldCorpus.failureReason
import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [KeeperImporter]'s own behaviour: the two folder-escaping rules, the typed-record mapping, and
 * the header-less CSV recognition rules — each one of which can refuse a real file, so each one is
 * pinned with a case that must pass *and* a case that must not.
 */
class KeeperImporterTest {

    // ---- folder paths -------------------------------------------------------------------------

    /**
     * The JSON rule: a single backslash separates, a doubled one is a literal backslash in a name,
     * and `/` is an ordinary character. Read with the CSV rule instead, `A\B` would be one folder
     * called `A\B` — a plausible-looking result that is not what the user had.
     */
    @Test
    fun `a JSON folder path nests on single backslashes and unescapes doubled ones`() {
        assertEquals(listOf("A", "B"), KeeperFolderPath.split("""A\B"""))
        assertEquals(listOf("""A\B"""), KeeperFolderPath.split("""A\\B"""))
        assertEquals(listOf("Shared/With/Slashes"), KeeperFolderPath.split("Shared/With/Slashes"))
        assertEquals(
            listOf("Development", """Name/with\both/slashes"""),
            KeeperFolderPath.split("""Development\Name/with\\both/slashes"""),
        )
    }

    /** The CSV rule: no JSON layer to unescape, so a run of backslashes is the separator. */
    @Test
    fun `a CSV folder cell nests on a run of backslashes`() {
        assertEquals(listOf("Foo", "Baz"), KeeperFolderPath.splitCsv("""Foo\\Baz"""))
        assertEquals(listOf("Foo", "Baz", "Bar"), KeeperFolderPath.splitCsv("""Foo\\Baz\\Bar"""))
        assertEquals(emptyList(), KeeperFolderPath.splitCsv(""))
    }

    /** Deeper than the vault's own tree allows lands near where it was, rather than being refused. */
    @Test
    fun `a folder path deeper than the tree allows is clamped`() {
        val deep = (1..12).joinToString("""\""") { "level$it" }
        assertEquals(8, KeeperFolderPath.split(deep).size)
    }

    // ---- CSV recognition ----------------------------------------------------------------------

    /**
     * The rule that does the real work. A seven-column CSV *with* a header is not a header-less
     * file, and mapping one by position puts the header row's words into a login.
     */
    @Test
    fun `a headed CSV of Keeper's own column count is refused`() {
        val headed = """
            type,number,name,issue_date,expiration_date,place_of_issue,state
            passport,123,John Doe,2022-01-30,2032-01-30,Berlin,
        """.trimIndent().toByteArray()
        assertFalse(KeeperImporter.detect(headed), "a headed file must not be read by position")

        // The control: the same seven columns with data in the first row is claimed.
        val headerless = """
            "Foo","Bar","j@example.com","pw","https://example.com/","",""
        """.trimIndent().toByteArray()
        assertTrue(KeeperImporter.detect(headerless))
    }

    /** An odd cell count cannot be `7 + 2n`, so the trailing pair is incomplete and the shape wrong. */
    @Test
    fun `a row whose custom-field pairs do not pair up is refused`() {
        val odd = """"Foo","Bar","j@example.com","pw","https://e.com","","","Account ID"""".toByteArray()
        assertFalse(KeeperImporter.detect(odd))
    }

    /** Enpass's sensitive-label marker, which four of its CSV fixtures satisfy Keeper's shape with. */
    @Test
    fun `a row carrying an Enpass sensitive-label marker is refused`() {
        val enpassShaped =
            """"Visa","Cardholder","John Doe","*Number","4111111111111111","Type","Visa","*CVC","123","Expiry date","01/2030""""
        assertFalse(KeeperImporter.detect(enpassShaped.toByteArray()))
    }

    // ---- JSON mapping -------------------------------------------------------------------------

    /**
     * The corpus's `US Passport` record: `$type` `passport`, a `$name` object and
     * `$accountNumber:passportNumber` of `543826194`. An [Identity] is the only content type with a
     * place for a passport number at all.
     */
    @Test
    fun `a passport record becomes an identity carrying its number`() {
        val parsed = KeeperImporter.parse(RealWorldCorpus.bytes(PATH_CLI)).orFail(PATH_CLI)
        val passport = parsed.items.first { it.title == "US Passport" }
        val identity = passport.content as Identity

        assertEquals("Jennifer", identity.firstName)
        assertEquals("Williams", identity.lastName)
        assertEquals("543826194", identity.passport)
        // Identity has no notes field, so the record's own notes are rescued rather than dropped.
        assertEquals(
            "Valid US passport for international travel",
            passport.customFields.first { it.name == ImportDefaultTitles().identityNotesFieldName }.value,
        )
    }

    /** `Chase Visa`: `$paymentCard` holds the number, expiry and CVV; `$pinCode` the PIN. */
    @Test
    fun `a bankCard record becomes a card whose brand comes from the number`() {
        val parsed = KeeperImporter.parse(RealWorldCorpus.bytes(PATH_CLI)).orFail(PATH_CLI)
        val card = parsed.items.first { it.title == "Chase Visa" }.content as Card

        assertEquals("4532123456789010", card.number)
        assertEquals("Sarah Johnson", card.holder)
        assertEquals(6, card.expMonth)
        assertEquals(2030, card.expYear)
        assertEquals("347", card.cvv)
        assertEquals("8426", card.pin)
        assertEquals(com.localkeep.core.model.CardBrand.VISA, card.brand)
    }

    /**
     * `Wells Fargo Checking` is `$type` `bankAccount`. [Card] has nowhere for a routing number and
     * [Identity] would misfile it, so it becomes a note whose `$bankAccount` object survives as a
     * custom field.
     */
    @Test
    fun `a bankAccount record becomes a note that keeps its account fields`() {
        val parsed = KeeperImporter.parse(RealWorldCorpus.bytes(PATH_CLI)).orFail(PATH_CLI)
        val account = parsed.items.first { it.title == "Wells Fargo Checking" }

        assertTrue(account.content is Note, "a bank account is not a payment card")
        val carried = account.customFields.first { it.name == "bankAccount" }.value
        assertTrue("routingNumber: 121000248" in carried, carried)
        assertTrue("accountNumber: 8472651938" in carried, carried)
    }

    /**
     * `$url` may be an **array**, and the corpus's Amazon record carries two addresses in one.
     * Rendered as a single newline-joined string it arrived as one URI that autofill can never
     * match and nothing on screen would flag.
     */
    @Test
    fun `a url array becomes one uri per address`() {
        val parsed = KeeperImporter.parse(RealWorldCorpus.bytes(PATH_CLI)).orFail(PATH_CLI)
        val login = parsed.items.first { it.title == "Amazon Account" }.content as Login

        assertTrue("https://login.amazon.com" in login.uris.map { it.uri }, "${login.uris}")
        assertTrue("https://logout.amazon.com" in login.uris.map { it.uri }, "${login.uris}")
        assertEquals("I65VU7K5ZQL7WB4E", assertNotNull(login.totp).secretBase32)
    }

    /** `$pinCode` and `$secret` are secrets, so they arrive masked rather than as plain text. */
    @Test
    fun `secret-typed custom fields arrive hidden`() {
        val parsed = KeeperImporter.parse(RealWorldCorpus.bytes(PATH_CLI)).orFail(PATH_CLI)
        val amazon = parsed.items.first { it.title == "Amazon Account" }

        assertEquals(
            CustomFieldType.HIDDEN,
            amazon.customFields.first { it.name == "special secret" }.type,
        )
        assertEquals(
            CustomFieldType.TEXT,
            amazon.customFields.first { it.name == "some label" }.type,
        )
    }

    /** A record filed in several folders lands in the first and names the rest. */
    @Test
    fun `a record in more than one folder keeps the folders it could not be filed in`() {
        val parsed = KeeperImporter.parse(RealWorldCorpus.bytes(PATH_CLI)).orFail(PATH_CLI)
        val licence = parsed.items.first { it.title == "Oregon Driver's License" }

        assertNotNull(licence.folderId, "it was filed somewhere")
        assertEquals(
            """Development\Web""",
            licence.customFields.first { it.name == KeeperImporter.FOLDER_FIELD_NAME }.value,
        )
    }

    // ---- failure ------------------------------------------------------------------------------

    /** A file this importer claimed but cannot decode fails with a named reason, never a throw. */
    @Test
    fun `a truncated Keeper JSON fails with a typed reason`() {
        val broken = """{"shared_folders":[],"records":[{"title":"x"""".toByteArray()
        assertTrue(KeeperImporter.detect(broken).not(), "an unparseable document is not claimed")

        val claimedButBroken = """{"shared_folders":[],"records":[1,2,3]}""".toByteArray()
        assertTrue(KeeperImporter.detect(claimedButBroken))
        assertEquals(
            ImportFailureReason.MalformedExport(SourceFormat.KEEPER),
            KeeperImporter.parse(claimedButBroken).failureReason("records-of-numbers"),
        )
    }

    /** An empty file is claimed by nothing and parses to nothing rather than throwing. */
    @Test
    fun `an empty file is not claimed`() {
        assertFalse(KeeperImporter.detect(ByteArray(0)))
        assertTrue(KeeperImporter.parse(ByteArray(0)) is Outcome.Success)
    }

    private companion object {
        const val PATH_CLI = "keeper/export-cli.json"
    }
}

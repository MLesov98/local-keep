package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Card
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.PasswordHistory
import com.localkeep.core.model.VaultItem
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnePuxImporterTest {

    /** A `.1pux` is a zip whose `export.data` entry holds the whole export as JSON. */
    private fun onePux(exportData: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("export.data"))
            zip.write(exportData.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun parseFull(exportData: String): ParsedImport =
        when (val outcome = OnePuxImporter.parse(onePux(exportData))) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("expected a successful parse, got: ${outcome.error.message}")
        }

    private fun parse(exportData: String): List<VaultItem> = parseFull(exportData).items

    /** One credit card, with [fields] spliced into its single section. */
    private fun cardExport(fields: String): String =
        """
        { "accounts": [ { "vaults": [ { "items": [ {
            "categoryUuid": "002",
            "overview": { "title": "Travel card" },
            "details": { "sections": [ { "fields": [ $fields ] } ] }
        } ] } ] } ] }
        """.trimIndent()

    private fun card(fields: String): Card = parse(cardExport(fields)).single().content as Card

    /**
     * The card number is the point of importing a card, and it was arriving blank.
     *
     * 1Password writes it under the `creditCardNumber` value-union key, and the reader only knew
     * about `string`/`concealed`/`email`/`phone` — so every 1Password card came into the vault as a
     * title, a cardholder, and nothing else.
     */
    @Test
    fun theCardNumberIsReadFromItsOwnValueUnionKey() {
        val card = card(
            """
            { "id": "cardholder", "value": { "string": "A Lovelace" } },
            { "id": "ccnum", "value": { "creditCardNumber": "4111111111111111" } },
            { "id": "cvv", "value": { "concealed": "737" } }
            """.trimIndent(),
        )

        assertEquals("4111111111111111", card.number)
        assertEquals("A Lovelace", card.holder)
        assertEquals("737", card.cvv)
    }

    /** `monthYear` is a single `YYYYMM` integer, and the two [Card] fields want it split. */
    @Test
    fun theExpiryIsSplitOutOfTheMonthYearNumber() {
        val card = card("""{ "id": "expiry", "value": { "monthYear": 202603 } }""")

        assertEquals(2026, card.expYear)
        assertEquals(3, card.expMonth)
    }

    /**
     * Absent beats wrong. A `0` or a placeholder used to be as likely to become "month 0" as
     * anything else, and the editor would then show an expiry the user never entered.
     */
    @Test
    fun anImplausibleExpiryLeavesBothFieldsUnset() {
        listOf("0", "202613", "17", """"soon"""").forEach { raw ->
            val card = card("""{ "id": "expiry", "value": { "monthYear": $raw } }""")

            assertNull("monthYear $raw should not produce a year", card.expYear)
            assertNull("monthYear $raw should not produce a month", card.expMonth)
        }
    }

    /** With no number to read, 1Password's own short codes are all there is to go on. */
    @Test
    fun aNumberlessCardFallsBackToOnePasswordsOwnBrandCodes() {
        assertEquals(
            CardBrand.MASTERCARD,
            card("""{ "id": "type", "value": { "creditCardType": "mc" } }""").brand,
        )
        assertEquals(
            CardBrand.AMEX,
            card("""{ "id": "type", "value": { "menu": "amex" } }""").brand,
        )
        assertEquals(
            CardBrand.UNKNOWN,
            card("""{ "id": "type", "value": { "string": "store loyalty" } }""").brand,
        )
    }

    /**
     * The digits win. The card editor recomputes the brand from the number on every save, so a
     * brand taken from the export's label would survive only until the user first opened the item —
     * and where the two disagree it is the label that is wrong.
     */
    @Test
    fun theCardNumberOutranksTheExportsBrandLabel() {
        val card = card(
            """
            { "id": "ccnum", "value": { "creditCardNumber": "4111111111111111" } },
            { "id": "type", "value": { "creditCardType": "mc" } }
            """.trimIndent(),
        )

        assertEquals(CardBrand.VISA, card.brand)
    }

    /** A card item with no section fields at all still imports, rather than failing the export. */
    @Test
    fun aCardWithNoRecognisedFieldsStillImports() {
        val card = card("""{ "id": "unrelated", "value": { "string": "x" } }""")

        assertEquals("", card.number)
        assertEquals(CardBrand.UNKNOWN, card.brand)
        assertNull(card.expYear)
    }

    /**
     * [Identity] has no notes field of its own (unlike Login/Note/Card, which all carry
     * `notesPlain` directly), so the text must be rescued into a custom field instead of being
     * silently discarded while the wizard still counts the item as fully carried.
     */
    @Test
    fun anIdentitysFreeTextNotesSurviveAsACustomField() {
        val exportData = """
            { "accounts": [ { "vaults": [ { "items": [ {
                "categoryUuid": "004",
                "overview": { "title": "My passport" },
                "details": {
                    "notesPlain": "renew before 2030, kept in the safe",
                    "sections": [ { "fields": [
                        { "id": "firstname", "value": { "string": "Ada" } }
                    ] } ]
                }
            } ] } ] } ] }
        """.trimIndent()

        val item = parse(exportData).single()
        val identity = item.content as Identity
        assertEquals("Ada", identity.firstName)
        assertEquals(
            "renew before 2030, kept in the safe",
            item.customFields.single { it.name == "Notes" }.value,
        )
    }

    /** An Identity item with no notes at all must not grow an empty custom field. */
    @Test
    fun anIdentityWithNoNotesGetsNoNotesCustomField() {
        val exportData = """
            { "accounts": [ { "vaults": [ { "items": [ {
                "categoryUuid": "004",
                "overview": { "title": "My passport" },
                "details": { "sections": [] }
            } ] } ] } ] }
        """.trimIndent()

        val item = parse(exportData).single()
        assertNull(item.customFields.find { it.name == "Notes" })
    }

    /**
     * Two fields of ordinary user data that never crossed over.
     *
     * `OnePuxDetails` and `OnePuxOverview` declared neither `passwordHistory` nor `tags`, so
     * `ignoreUnknownKeys` dropped both before `mapItem` could see them — and `mapItem` never set
     * `VaultItem.tags` or `Login.passwordHistory` either, both of which are real, wired model
     * fields. `BitwardenJsonImporter` has carried the equivalent password history since item 3-5,
     * so 1Password was the one importer left behind on an otherwise-fixed defect class, and nothing
     * disclosed the loss: a field-level loss has no home in `SkippedEntry`, and 1Password's only
     * caveat is about handling the plaintext file (4-4, 2026-09-13).
     */
    @Test
    fun aLoginsPasswordHistoryAndTagsCrossOver() {
        val exportData = """
            { "accounts": [ { "vaults": [ { "items": [ {
                "categoryUuid": "001",
                "createdAt": 1700000000,
                "overview": { "title": "Bank", "tags": ["Finance", "Shared/Household"] },
                "details": {
                    "loginFields": [ { "designation": "username", "value": "ada" } ],
                    "password": "new",
                    "passwordHistory": [
                        { "value": "old",   "time": 1700000100 },
                        { "value": "older", "time": 1600000000 }
                    ]
                }
            } ] } ] } ] }
        """.trimIndent()

        val item = parse(exportData).single()

        assertEquals(listOf("Finance", "Shared/Household"), item.tags)
        val history = (item.content as Login).passwordHistory
        assertEquals(listOf("old", "older"), history.map { it.password })
        assertEquals(Instant.ofEpochSecond(1700000100), history.first().changedAt)
    }

    /**
     * A history entry with no usable `time` keeps its password rather than being dropped — the
     * password is the part worth keeping, and the item's own creation date is the earliest anything
     * on it can have happened, so it sorts oldest. The same rule `BitwardenJsonImporter.parseDate`
     * states for its own format: an absent date never drops data.
     */
    @Test
    fun anUndatedPasswordHistoryEntryStillCrossesOver() {
        val exportData = """
            { "accounts": [ { "vaults": [ { "items": [ {
                "categoryUuid": "001",
                "createdAt": 1700000000,
                "overview": { "title": "Bank" },
                "details": {
                    "password": "new",
                    "passwordHistory": [ { "value": "old" }, { "value": "" } ]
                }
            } ] } ] } ] }
        """.trimIndent()

        val history = (parse(exportData).single().content as Login).passwordHistory

        assertEquals("a blank password is not a secret to keep", listOf("old"), history.map { it.password })
        assertEquals(Instant.ofEpochSecond(1700000000), history.single().changedAt)
    }

    /**
     * An export can carry more history per item than this app retains, and an import must not be a
     * door around the app's own limit — the same bound `BitwardenJsonImporter` applies.
     */
    @Test
    fun passwordHistoryIsBoundedByTheAppsOwnLimit() {
        val entries = (1..12).joinToString(",") { """{ "value": "p$it", "time": ${1700000000 + it} }""" }
        val exportData = """
            { "accounts": [ { "vaults": [ { "items": [ {
                "categoryUuid": "001",
                "overview": { "title": "Bank" },
                "details": { "password": "new", "passwordHistory": [ $entries ] }
            } ] } ] } ] }
        """.trimIndent()

        val history = (parse(exportData).single().content as Login).passwordHistory

        assertEquals(PasswordHistory.MAX_ENTRIES, history.size)
        assertEquals("newest first", "p12", history.first().password)
    }

    /** The control: an item with neither must still map to empty lists, never fabricated ones. */
    @Test
    fun anItemWithNoTagsOrHistoryGetsEmptyLists() {
        val exportData = """
            { "accounts": [ { "vaults": [ { "items": [ {
                "categoryUuid": "001",
                "overview": { "title": "Bank" },
                "details": { "password": "pw" }
            } ] } ] } ] }
        """.trimIndent()

        val item = parse(exportData).single()

        assertEquals(emptyList<String>(), item.tags)
        assertEquals(emptyList<String>(), (item.content as Login).passwordHistory.map { it.password })
    }

    /**
     * Real exports carry `email` and `address` as objects, not strings — 1Password writes an email
     * as `{ "email_address": ..., "provider": ... }` and an address as a street/city/state/zip/
     * country map (KeePassXC's OPUXReader reads both that way). The reader called `.jsonPrimitive`
     * on them, which throws on an object, and the throw was caught as "this doesn't look like a
     * valid 1Password export" — so one identity with an email address rejected the whole file.
     * Found on a device 2026-09-14 with a ten-item export that never got past the preview.
     */
    @Test
    fun anIdentitysEmailAndAddressObjectsAreReadNotFatal() {
        val exportData = """
            { "accounts": [ { "vaults": [ { "items": [ {
                "categoryUuid": "004",
                "overview": { "title": "Passport" },
                "details": { "sections": [ { "fields": [
                    { "id": "firstname", "value": { "string": "Ada" } },
                    { "id": "email", "value": { "email": { "email_address": "ada@example.com", "provider": "" } } },
                    { "id": "defphone", "value": { "phone": "+44123456" } },
                    { "id": "address", "value": { "address": { "street": "1 Analytical St", "city": "London", "country": "gb", "zip": "E1 6AN", "state": "" } } }
                ] } ] }
            }, {
                "categoryUuid": "001",
                "overview": { "title": "Mail login", "url": "https://mail.example" },
                "details": {
                    "loginFields": [ { "designation": "username", "value": "ada" }, { "designation": "password", "value": "pw" } ],
                    "sections": [ { "fields": [
                        { "id": "recovery", "title": "recovery email", "value": { "email": { "email_address": "backup@example.com" } } },
                        { "id": "office", "title": "office", "value": { "address": { "street": "2 Engine Rd", "city": "Bath", "country": "gb", "zip": "", "state": "" } } }
                    ] } ]
                }
            } ] } ] } ] }
        """.trimIndent()

        val items = parse(exportData)
        val identity = items[0].content as Identity
        assertEquals("ada@example.com", identity.email)
        assertEquals("+44123456", identity.phone)
        assertEquals("1 Analytical St", identity.address.line1)
        assertEquals("London", identity.address.city)
        assertEquals("E1 6AN", identity.address.zip)
        // 1Password writes a lowercased alpha-2 code, which rendered on the item as `gb` under a
        // London address (1A-F10, 2026-09-20). Compared against the platform's own name for the
        // region rather than a literal, because the language is the device's — see ImportedCountry.
        val gb = ImportedCountry.of("gb")
        assertEquals(gb, identity.address.country)
        assertNotEquals("gb", identity.address.country, "the raw code must not reach the item")

        val login = items[1]
        assertEquals("backup@example.com", login.customFields.single { it.name == "recovery email" }.value)
        assertEquals("2 Engine Rd, Bath, $gb", login.customFields.single { it.name == "office" }.value)
    }
}

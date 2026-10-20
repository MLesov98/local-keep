package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Card
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.MatchStrategy
import com.localkeep.core.model.VaultItem
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwardenJsonImporterTest {

    private fun parseFull(json: String): ParsedImport =
        when (val outcome = BitwardenJsonImporter.parse(json.toByteArray())) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("expected a successful parse, got: ${outcome.error.message}")
        }

    private fun parse(json: String): List<VaultItem> = parseFull(json).items

    /**
     * The export's own dates, not the import moment. Every item used to be stamped `Instant.now()`,
     * which told the security dashboard's age check that a decade-old password was minted seconds
     * ago — the one screen that exists to find stale credentials had them all looking fresh.
     */
    @Test
    fun itemTimestampsComeFromTheExport() {
        val items = parse(
            """
            {
              "encrypted": false,
              "items": [{
                "type": 1,
                "name": "GitHub",
                "creationDate": "2019-03-04T11:22:33.170Z",
                "revisionDate": "2021-07-19T08:00:00.000Z",
                "login": { "username": "octocat", "password": "pw" }
              }]
            }
            """.trimIndent(),
        )

        val item = items.single()
        assertEquals(Instant.parse("2019-03-04T11:22:33.170Z"), item.createdAt)
        assertEquals(Instant.parse("2021-07-19T08:00:00Z"), item.updatedAt)
        assertEquals("octocat", (item.content as Login).username)
    }

    /** An older export without the dates, or one with a date this JVM will not parse. */
    @Test
    fun missingOrUnparseableDatesFallBackToTheImportMoment() {
        val before = Instant.now()
        val items = parse(
            """
            {
              "encrypted": false,
              "items": [
                { "type": 2, "name": "No dates", "notes": "body" },
                { "type": 2, "name": "Bad dates", "notes": "body",
                  "creationDate": "the fourth of March", "revisionDate": "" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, items.size)
        items.forEach {
            assertTrue(
                "createdAt ${it.createdAt} should be the import moment",
                !it.createdAt.isBefore(before),
            )
            assertEquals(it.createdAt, it.updatedAt)
        }
    }

    /** A revision date alone still dates the item — it is what the merge tie-break reads. */
    @Test
    fun aRevisionDateWithNoCreationDateIsUsedForBoth() {
        val items = parse(
            """
            {
              "encrypted": false,
              "items": [{ "type": 2, "name": "Note", "notes": "b",
                          "revisionDate": "2020-02-02T02:02:02Z" }]
            }
            """.trimIndent(),
        )

        val item = items.single()
        assertEquals(Instant.parse("2020-02-02T02:02:02Z"), item.createdAt)
        assertEquals(Instant.parse("2020-02-02T02:02:02Z"), item.updatedAt)
    }

    /**
     * Folder membership crosses over, pointed at a folder this parse also returns.
     *
     * This test asserted the opposite until 2026-07-31, and was right to: while `ParsedImport`
     * carried items and nothing else, keeping Bitwarden's id would have left the item pointing at a
     * folder the vault does not contain — a reference nothing resolves. The contract now carries
     * the folder too, so the id is resolvable and dropping it would lose the user's organisation
     * for no reason. Full coverage of the edge cases lives in [BitwardenFolderImportTest]; this one
     * stays here to pin the id-resolution invariant next to the rest of the item mapping.
     */
    @Test
    fun folderIdIsCarriedAcrossAndPointsAtAnImportedFolder() {
        val parsed = parseFull(
            """
            {
              "encrypted": false,
              "folders": [{ "id": "3f1e6d2a-1111-4222-8333-444455556666", "name": "Work" }],
              "items": [{
                "type": 1,
                "name": "GitHub",
                "folderId": "3f1e6d2a-1111-4222-8333-444455556666",
                "login": { "username": "octocat", "password": "pw" }
              }]
            }
            """.trimIndent(),
        )

        val folder = parsed.folders.single()
        assertEquals("Work", folder.name)
        assertEquals(folder.id, parsed.items.single().folderId)
    }

    /**
     * The card's network was never worked out at import, so every card arrived as
     * [CardBrand.UNKNOWN]. The number decides it — the same `CardBrands.detect` the card editor
     * runs on every save — and the export's own `brand` text is the fallback for a card saved
     * without a number. Where they disagree the digits win, because the editor would overwrite the
     * label anyway the first time the user opened the item.
     */
    @Test
    fun theCardNetworkComesFromTheNumberAndFallsBackToTheBrandField() {
        val items = parse(
            """
            {
              "encrypted": false,
              "items": [
                { "type": 3, "name": "By number", "card": { "number": "378282246310005" } },
                { "type": 3, "name": "Mislabelled", "card": {
                    "number": "4111111111111111", "brand": "Mastercard" } },
                { "type": 3, "name": "No number", "card": { "brand": "Diners Club" } },
                { "type": 3, "name": "Loyalty", "card": { "brand": "Nectar" } },
                { "type": 3, "name": "Neither", "card": { "cardholderName": "A Lovelace" } }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                CardBrand.AMEX,
                CardBrand.VISA,
                CardBrand.DINERS,
                CardBrand.UNKNOWN,
                CardBrand.UNKNOWN,
            ),
            items.map { (it.content as Card).brand },
        )
    }

    /**
     * The match type was parsed off the wire and never used, so every URI took the DOMAIN default —
     * including the "Never" a user set precisely to stop a login being offered somewhere.
     */
    @Test
    fun theUriMatchTypeSurvivesTheImport() {
        val items = parse(
            """
            {
              "encrypted": false,
              "items": [{
                "type": 1, "name": "Bank",
                "login": { "username": "ada", "uris": [
                  { "uri": "https://a.example", "match": 0 },
                  { "uri": "https://b.example", "match": 1 },
                  { "uri": "https://c.example", "match": 2 },
                  { "uri": "https://d.example", "match": 3 },
                  { "uri": "https://e.example", "match": 4 },
                  { "uri": "https://f.example", "match": 5 },
                  { "uri": "https://g.example" },
                  { "uri": "https://h.example", "match": 99 }
                ] }
              }]
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                MatchStrategy.DOMAIN,
                MatchStrategy.HOST,
                MatchStrategy.STARTS_WITH,
                MatchStrategy.EXACT,
                MatchStrategy.REGEX,
                MatchStrategy.NEVER,
                // Absent and unknown both keep the default rather than guessing.
                MatchStrategy.DOMAIN,
                MatchStrategy.DOMAIN,
            ),
            (items.single().content as Login).uris.map { it.strategy },
        )
    }

    @Test
    fun anEncryptedExportSaysSoInsteadOfFailingGenerically() {
        val outcome = BitwardenJsonImporter.parse(
            """{ "encrypted": true, "items": [] }""".toByteArray(),
        )

        val failure = outcome as Outcome.Failure
        // The specific "password-protected" English wording used to live on AppError.message, which
        // ImportWizardViewModel echoed verbatim (findings F2, F22). It now lives as a typed reason on
        // AppError.cause, resolved to a @StringRes by feature/import — see ImportErrorHygieneTest for
        // the dedicated regression coverage of that.
        // AccountEncrypted, not ContainerEncrypted: no `passwordProtected` flag means Bitwarden's
        // account-restricted shape, which no password opens (V3-02).
        assertEquals(
            ImportFailureReason.AccountEncrypted(SourceFormat.BITWARDEN),
            (failure.error.cause as ImportRejection).reason,
        )
    }

    /**
     * [Identity] has no notes field of its own (unlike types 1-3, which all carry `item.notes`
     * directly), so the text must be rescued into a custom field instead of being silently
     * discarded while the wizard still counts the item as fully carried.
     */
    @Test
    fun anIdentitysFreeTextNotesSurviveAsACustomField() {
        val item = parse(
            """
            {
              "encrypted": false,
              "items": [{
                "type": 4,
                "name": "My passport",
                "notes": "renew before 2030, kept in the safe",
                "identity": { "firstName": "Ada" }
              }]
            }
            """.trimIndent(),
        ).single()

        assertEquals("Ada", (item.content as Identity).firstName)
        assertEquals(
            "renew before 2030, kept in the safe",
            item.customFields.single { it.name == "Notes" }.value,
        )
    }

    /** An Identity item with no notes at all must not grow an empty custom field. */
    @Test
    fun anIdentityWithNoNotesGetsNoNotesCustomField() {
        val item = parse(
            """
            {
              "encrypted": false,
              "items": [{ "type": 4, "name": "My passport", "identity": { "firstName": "Ada" } }]
            }
            """.trimIndent(),
        ).single()

        assertNull(item.customFields.find { it.name == "Notes" })
    }

    /**
     * A TOTP value Bitwarden exported but [TotpFieldParser] refuses — a `steam://` token, an
     * `otpauth://hotp/...` URI, a seed of an invalid Base32 length — used to evaluate to null on
     * the `totp = login.totp?.let(...)` line and vanish: the item was still built, still counted
     * as fully imported, and nothing was appended to `skipped`. The seed is the user's second
     * factor and the export is often its only remaining copy, so rescue the raw text into a HIDDEN
     * custom field the way the CSV and KDBX paths already do.
     */
    @Test
    fun unparseableLoginTotpIsRescuedIntoAHiddenCustomField() {
        val raw = "steam://ABCDEFGHIJKLMNOP"
        val item = parse(
            """
            {
              "encrypted": false,
              "items": [{
                "type": 1,
                "name": "Steam",
                "login": { "username": "ada", "password": "pw", "totp": "$raw" }
              }]
            }
            """.trimIndent(),
        ).single()

        assertNull((item.content as Login).totp)
        val rescued = item.customFields.single { it.name == "TOTP seed" }
        assertEquals(raw, rescued.value)
        assertEquals(CustomFieldType.HIDDEN, rescued.type)
    }

    /** The same rescue for the counter-based URI shape, which is the other common rejection. */
    @Test
    fun hotpUriIsRescuedRatherThanDropped() {
        val raw = "otpauth://hotp/Example:ada?secret=JBSWY3DPEHPK3PXP&counter=1"
        val item = parse(
            """
            {
              "encrypted": false,
              "items": [{
                "type": 1,
                "name": "Legacy",
                "login": { "username": "ada", "password": "pw", "totp": "$raw" }
              }]
            }
            """.trimIndent(),
        ).single()

        assertNull((item.content as Login).totp)
        assertEquals(raw, item.customFields.single { it.name == "TOTP seed" }.value)
    }

    /** Control: a seed that parses stays on the item and must not also grow a rescue field. */
    @Test
    fun aParseableTotpGrowsNoRescueField() {
        val item = parse(
            """
            {
              "encrypted": false,
              "items": [{
                "type": 1,
                "name": "GitHub",
                "login": { "username": "ada", "password": "pw", "totp": "JBSWY3DPEHPK3PXP" }
              }]
            }
            """.trimIndent(),
        ).single()

        assertEquals("JBSWY3DPEHPK3PXP", (item.content as Login).totp?.secretBase32)
        assertNull(item.customFields.find { it.name == "TOTP seed" })
    }

    /**
     * An export of an empty vault is still an export.
     *
     * `detect` recognised a file only by its *contents* — encrypted, or at least one item or folder
     * — so a real, unencrypted export of an empty vault made all three false. The wizard then
     * discarded the user's explicit Bitwarden pick, found nothing else that matched, and answered
     * "Check you exported the unencrypted format, and that the file didn't get truncated": a claim
     * about encryption and truncation, neither of which is true of a valid file that simply holds
     * nothing. `parse` would have reported the accurate "contained no items" outcome the wizard
     * already has (1-3, 2026-09-13).
     */
    @Test
    fun anEmptyUnencryptedExportIsStillRecognised() {
        assertTrue(
            BitwardenJsonImporter.detect("""{"encrypted":false,"folders":[],"items":[]}""".toByteArray()),
        )
    }

    /**
     * The control, and the reason recognition is by *key presence* rather than by decode success:
     * `ignoreUnknownKeys` decodes an Aegis export into an all-defaults `BitwardenExport` just as
     * happily, and this importer is tried before Aegis in `ALL_IMPORTERS` — so a decode-success
     * check would have made `detect` a rubber stamp that stole every JSON export from its own
     * importer.
     */
    @Test
    fun detectStillRefusesJsonThatIsNotABitwardenExport() {
        assertFalse(
            "an Aegis export decodes cleanly as an all-defaults BitwardenExport",
            BitwardenJsonImporter.detect(
                """{"version":1,"header":{"slots":null},"db":{"version":2,"entries":[]}}""".toByteArray(),
            ),
        )
        assertFalse(BitwardenJsonImporter.detect("""{}""".toByteArray()))
        assertFalse(BitwardenJsonImporter.detect("not json at all".toByteArray()))
    }

    /**
     * An imported URI is stored trimmed, and a blank one is not stored at all.
     *
     * The URI used to be kept exactly as the export wrote it, while `ItemEditViewModel.toContent()`
     * trims the form's copy before matching it back against the stored one by exact string
     * equality — so an imported URI carrying surrounding whitespace found no match on the first
     * save from the editor and fell back to the `DOMAIN` default, silently losing the `EXACT` or
     * `NEVER` the user had set in Bitwarden and changing where autofill offers the credential
     * (7-4 residual, 2026-09-13).
     */
    @Test
    fun anImportedUriIsStoredTrimmed() {
        val uris = (
            parse(
                """
                {
                  "encrypted": false,
                  "items": [{
                    "type": 1, "name": "Bank",
                    "login": { "username": "ada", "uris": [
                      { "uri": "  https://bank.example  ", "match": 3 },
                      { "uri": "   " }
                    ] } }]
                }
                """.trimIndent(),
            ).single().content as Login
            ).uris

        assertEquals(listOf("https://bank.example"), uris.map { it.uri })
        assertEquals(MatchStrategy.EXACT, uris.single().strategy)
    }
}

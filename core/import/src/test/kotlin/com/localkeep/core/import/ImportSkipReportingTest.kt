package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Note
import com.localkeep.core.model.Totp
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an importer left behind has to be reportable, not just absent.
 *
 * Every importer here already dropped entries it had no mapping for and returned a bare list, so
 * the "42 items found" the user approved was the *post-drop* count. An import that lost a third of
 * the source file looked exactly like a complete one — and the wizard's last step then offers to
 * securely delete the export, taking the only copy of what was dropped with it.
 *
 * These assert the count *and* the reason: a bare number ("18 not imported") is not enough to act
 * on, because the user cannot tell which 18 to re-enter by hand.
 */
class ImportSkipReportingTest {

    private fun onePux(exportData: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("export.data"))
            zip.write(exportData.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun <T> succeed(outcome: Outcome<T>): T = when (outcome) {
        is Outcome.Success -> outcome.value
        is Outcome.Failure -> error("expected a successful parse, got: ${outcome.error.message}")
    }

    @Test
    fun `a 1Password category with no vault equivalent is named, not silently dropped`() {
        val parsed = succeed(
            OnePuxImporter.parse(
                onePux(
                    """
                    { "accounts": [ { "vaults": [ { "items": [
                        { "categoryUuid": "001", "overview": { "title": "Bank login" },
                          "details": { "loginFields": [ { "designation": "username", "value": "me" } ],
                                       "password": "pw" } },
                        { "categoryUuid": "101", "overview": { "title": "Chequing" }, "details": {} },
                        { "categoryUuid": "100", "overview": { "title": "Sublime" }, "details": {} }
                    ] } ] } ] }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(1, parsed.items.size, "only the login has a vault equivalent")
        assertEquals(2, parsed.skipped.size)
        assertEquals(
            listOf(
                "Chequing" to SkipReason.UnsupportedOnePasswordCategory("101"),
                "Sublime" to SkipReason.UnsupportedOnePasswordCategory("100"),
            ),
            parsed.skipped.map { it.title to it.reason },
        )
    }

    /**
     * The failure mode that makes this worth a test: the source titles are what the user searches
     * their export for. A skip list without them tells them something is missing but not what.
     */
    @Test
    fun `an unsupported Bitwarden type is reported with its own name`() {
        val parsed = succeed(
            BitwardenJsonImporter.parse(
                """
                { "items": [
                    { "type": 1, "name": "Bank", "login": { "username": "me", "password": "pw" } },
                    { "type": 5, "name": "deploy key" },
                    { "type": 99, "name": "something newer" }
                ] }
                """.trimIndent().toByteArray(),
            ),
        )

        assertEquals(1, parsed.items.size)
        assertEquals(
            listOf(
                "deploy key" to SkipReason.SshKey,
                "something newer" to SkipReason.UnsupportedBitwardenType(99),
            ),
            parsed.skipped.map { it.title to it.reason },
        )
    }

    /**
     * HOTP is skipped and named; Steam is not skipped at all.
     *
     * Steam used to sit in this test beside HOTP, both reported as "LocalKeep only carries TOTP".
     * That sentence was false: `TotpGenerator.generateSteam` has always existed and the 2FA editor
     * has a Steam Guard toggle, so the one export format that can carry a Steam token was refusing
     * it and telling the user the app could not do it. HOTP genuinely cannot be carried — `Totp` has
     * no counter field — so it stays here.
     */
    @Test
    fun `an Aegis HOTP code is reported, and a Steam code is imported`() {
        val parsed = succeed(
            AegisJsonImporter.parse(
                """
                { "version": 1, "db": { "version": 2, "entries": [
                    { "type": "totp",  "name": "alice", "issuer": "GitHub",
                      "info": { "secret": "JBSWY3DPEHPK3PXP" } },
                    { "type": "hotp",  "name": "token",  "issuer": "Bank", "info": { "secret": "JBSWY3DPEHPK3PXP" } },
                    { "type": "steam", "name": "gamer",  "issuer": "Steam",
                      "info": { "secret": "JBSWY3DPEHPK3PXP", "digits": 5 } }
                ] } }
                """.trimIndent().toByteArray(),
            ),
        )

        assertEquals(listOf("token"), parsed.skipped.map { it.title })
        assertEquals(listOf(SkipReason.UnsupportedAegisType("HOTP")), parsed.skipped.map { it.reason })

        assertEquals(listOf("alice", "gamer"), parsed.items.map { it.title })
        val steam = parsed.items.single { it.title == "gamer" }.content as Totp
        assertTrue(steam.steam)
        assertEquals("JBSWY3DPEHPK3PXP", steam.secretBase32)
        // The export's `digits: 5` is deliberately not copied: Steam's length is fixed by the
        // scheme, and 5 is a value every other TOTP path in the app rejects.
        assertEquals(6, steam.digits)
    }

    /**
     * A Steam entry whose secret cannot be read is still reported rather than imported broken.
     *
     * The reason changed on 2026-09-13 (4-3): it used to be `UnsupportedTotpSettings`, whose text
     * quotes the algorithm, digit count and period — three values a Steam entry's import never even
     * reads, because `generateSteam` hard-codes the scheme's. Blaming settings that were never
     * consulted for a missing secret is the same misdirection the totp path had.
     */
    @Test
    fun `an Aegis Steam code with an unreadable secret is reported`() {
        val parsed = succeed(
            AegisJsonImporter.parse(
                """
                { "version": 1, "db": { "version": 2, "entries": [
                    { "type": "steam", "name": "gamer", "issuer": "Steam", "info": { "secret": "not base32!!" } }
                ] } }
                """.trimIndent().toByteArray(),
            ),
        )

        assertEquals(emptyList(), parsed.items.map { it.title })
        assertEquals(listOf("gamer"), parsed.skipped.map { it.title })
        assertEquals(SkipReason.IncompleteEntry, parsed.skipped.single().reason)
    }

    /**
     * An Aegis export's `info` used to be copied onto `Totp` unread, so `digits: 7`, `period: 0` or
     * an algorithm this app cannot compute produced an item that imported "successfully" and then
     * rendered nothing forever — every `TotpGenerator` call site swallows the `require` it trips.
     * The entry is named in `skipped` now, which is the one outcome the user can act on.
     */
    @Test
    fun `an Aegis entry this app cannot compute is reported rather than imported broken`() {
        val parsed = succeed(
            AegisJsonImporter.parse(
                """
                { "version": 1, "db": { "version": 2, "entries": [
                    { "type": "totp", "name": "alice",  "issuer": "GitHub",
                      "info": { "secret": "JBSWY3DPEHPK3PXP" } },
                    { "type": "totp", "name": "seven",  "issuer": "Odd",
                      "info": { "secret": "JBSWY3DPEHPK3PXP", "digits": 7 } },
                    { "type": "totp", "name": "nowhen", "issuer": "Odd",
                      "info": { "secret": "JBSWY3DPEHPK3PXP", "period": 0 } },
                    { "type": "totp", "name": "exotic", "issuer": "Odd",
                      "info": { "secret": "JBSWY3DPEHPK3PXP", "algo": "SHA384" } }
                ] } }
                """.trimIndent().toByteArray(),
            ),
        )

        assertEquals(listOf("alice"), parsed.items.map { it.title })
        assertEquals(listOf("seven", "nowhen", "exotic"), parsed.skipped.map { it.title })
        assertTrue(parsed.skipped.all { it.reason is SkipReason.UnsupportedTotpSettings })
    }

    /** The control: an ordinary 8-digit, 60-second SHA256 code is a real token and still imports. */
    @Test
    fun `an Aegis entry with unusual but supported settings still imports`() {
        val parsed = succeed(
            AegisJsonImporter.parse(
                """
                { "version": 1, "db": { "version": 2, "entries": [
                    { "type": "totp", "name": "alice", "issuer": "GitHub",
                      "info": { "secret": "JBSWY3DPEHPK3PXP", "digits": 8, "period": 60, "algo": "SHA256" } }
                ] } }
                """.trimIndent().toByteArray(),
            ),
        )

        assertEquals(1, parsed.items.size)
        assertTrue(parsed.skipped.isEmpty())
    }

    /**
     * The reason must name the secret, not the settings.
     *
     * This asserted `UnsupportedTotpSettings` until 2026-09-13 — the reason that renders as
     * "Unsupported TOTP settings (SHA1, 6 digits, 30s)". Both entries below leave algo/digits/period
     * at their defaults, and those three defaults are the most common, fully supported TOTP
     * parameters there are: the message blamed them for a failure whose sole cause was the secret,
     * and `SkipReason`'s own kdoc had already scoped that reason to "algorithm, digit count, or
     * period". The old assertion pinned the wrong behaviour rather than reviewing it, which is why
     * this expectation is being changed deliberately (4-3).
     */
    @Test
    fun `Aegis entries with empty or malformed secrets are reported as incomplete, not as bad settings`() {
        val parsed = succeed(
            AegisJsonImporter.parse(
                """
                { "version": 1, "db": { "version": 2, "entries": [
                    { "type": "totp", "name": "empty", "issuer": "Odd",
                      "info": { "secret": "" } },
                    { "type": "totp", "name": "malformed", "issuer": "Odd",
                      "info": { "secret": "not!base32" } }
                ] } }
                """.trimIndent().toByteArray(),
            ),
        )

        assertTrue(parsed.items.isEmpty())
        assertEquals(listOf("empty", "malformed"), parsed.skipped.map { it.title })
        assertEquals(
            listOf(SkipReason.IncompleteEntry, SkipReason.IncompleteEntry),
            parsed.skipped.map { it.reason },
        )
    }

    /**
     * The control for the split above: an entry whose settings really are outside what this app can
     * compute still gets `UnsupportedTotpSettings`, carrying the export's own values — so the fix
     * narrowed that reason rather than retiring it.
     */
    @Test
    fun `an Aegis entry with a readable secret and an unsupported algorithm still names the settings`() {
        val parsed = succeed(
            AegisJsonImporter.parse(
                """
                { "version": 1, "db": { "version": 2, "entries": [
                    { "type": "totp", "name": "exotic", "issuer": "Odd",
                      "info": { "secret": "JBSWY3DPEHPK3PXP", "algo": "MD5" } }
                ] } }
                """.trimIndent().toByteArray(),
            ),
        )

        assertTrue(parsed.items.isEmpty())
        assertEquals(
            SkipReason.UnsupportedTotpSettings(algorithm = "MD5", digits = 6, periodSec = 30),
            parsed.skipped.single().reason,
        )
    }

    /**
     * A CSV row with nothing that makes it a login is still a row the user has in their file, and
     * it can carry a title and a note they wrote. Counter-asserted below: a *complete* file must
     * report nothing skipped, or the warning becomes noise and stops being read.
     *
     * **The fixture changed on 2026-09-13, deliberately.** Its skipped row used to be
     * `Reminder,,,,"call the branch"` — a row whose *notes* cell held real text — and this test
     * asserted that the row came out as a title and a `SkipReason`, which is exactly the data loss
     * 2-4 is about: `SkippedEntry` has no content field, and `notesCol` was read only inside the
     * item branch the skip returned past, so "call the branch" reached neither the vault nor the
     * skip report. The wizard's last step then offers to delete the export. That row is now
     * imported as a secure note (see the two tests below), so the row here is genuinely empty —
     * which is the case this test was always meant to pin.
     */
    @Test
    fun `a CSV row with no credential at all is reported`() {
        val csv = """
            name,url,username,password,notes
            Bank,https://bank.example,me,hunter2,
            Reminder,,,,
        """.trimIndent()

        val parsed = succeed(CsvImporter.parse(csv.toByteArray()))

        assertEquals(1, parsed.items.size)
        assertEquals(listOf("Reminder"), parsed.skipped.map { it.title })
        assertEquals(listOf(SkipReason.NoLoginFields), parsed.skipped.map { it.reason })
    }

    /**
     * A row that is not a login but still holds the user's text becomes a secure note.
     *
     * Bitwarden's CSV writes its secure notes exactly this way: `type=note`, all three `login_*`
     * columns blank, the body in `notes`. Dropping such a row to a bare title destroyed the content
     * rather than mistyping it — and unlike the LastPass secure notes recorded as X7, which carry a
     * placeholder `http://sn` url and so at least arrive intact as mistyped logins, these rows never
     * reached the item branch at all (2-4, 2026-09-13).
     */
    @Test
    fun `a Bitwarden CSV note row is imported as a secure note rather than skipped`() {
        val csv = "folder,favorite,type,name,notes,fields,login_uri,login_username,login_password,login_totp\n" +
            ",,note,My Secret,\"the actual secret text\",,,,,"

        val parsed = succeed(CsvImporter.parse(csv.toByteArray()))

        assertTrue(parsed.skipped.isEmpty(), "a row with content is not a row with nothing in it")
        val item = parsed.items.single()
        assertEquals("My Secret", item.title)
        assertEquals("the actual secret text", (item.content as Note).body)
    }

    /**
     * The same for a `type=card` row, whose numbers live in Bitwarden's `fields` column — which this
     * mapper has never read at all, so a card row lost its PAN, expiry and CVV the same way even
     * when `notes` was empty.
     *
     * The body is the `fields` cell **verbatim and unlabelled**, which is deliberate and is the
     * honest floor rather than the ideal: `core/import` cannot author text (see
     * `ImportFailureReason`'s kdoc), so any heading explaining where the text came from would be
     * untranslatable English written into the user's vault. Typing the row as a real `Card` needs a
     * parser for that column and belongs in its own change; losing it does not.
     */
    @Test
    fun `a Bitwarden CSV card row keeps its fields column verbatim in a secure note`() {
        val csv = "folder,favorite,type,name,notes,fields,login_uri,login_username,login_password,login_totp\n" +
            ",,card,My Visa,,\"number: 4111111111111111\",,,,"

        val parsed = succeed(CsvImporter.parse(csv.toByteArray()))

        assertTrue(parsed.skipped.isEmpty())
        val item = parsed.items.single()
        assertEquals("My Visa", item.title)
        assertEquals("number: 4111111111111111", (item.content as Note).body)
    }

    /** Both columns populated: both survive, and neither is labelled with text this module invented. */
    @Test
    fun `a row carrying both notes and fields keeps both`() {
        val csv = "folder,favorite,type,name,notes,fields,login_uri,login_username,login_password,login_totp\n" +
            ",,card,My Visa,\"kept in the drawer\",\"number: 4111111111111111\",,,,"

        val parsed = succeed(CsvImporter.parse(csv.toByteArray()))

        assertEquals(
            "kept in the drawer\n\nnumber: 4111111111111111",
            (parsed.items.single().content as Note).body,
        )
    }

    @Test
    fun `a file every entry of which imports reports nothing skipped`() {
        val csv = """
            name,url,username,password
            Bank,https://bank.example,me,hunter2
            Mail,https://mail.example,me,hunter3
        """.trimIndent()

        val parsed = succeed(CsvImporter.parse(csv.toByteArray()))

        assertEquals(2, parsed.items.size)
        assertTrue(parsed.skipped.isEmpty(), "a complete import must not warn about anything")
    }
}

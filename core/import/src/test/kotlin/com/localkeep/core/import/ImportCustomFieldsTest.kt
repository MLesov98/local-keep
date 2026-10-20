package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Login
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper

/**
 * The fields a source carries that are not username, password, URL or note.
 *
 * No importer mapped them. A KeePassXC entry's protected strings — the ones people keep recovery
 * codes and security answers in — arrived empty on an import that reported full success, and the
 * user found out when they were locked out of an account, typically long after deleting the source
 * file. `CustomField` has existed the whole time, with a HIDDEN type meant for exactly this.
 */
class ImportCustomFieldsTest {

    private fun <T> succeed(outcome: Outcome<T>): T = when (outcome) {
        is Outcome.Success -> outcome.value
        is Outcome.Failure -> error("expected a successful parse, got: ${outcome.error.message}")
    }

    private fun kdbx(build: DomDatabaseWrapper.() -> Unit): ParsedImport {
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper().apply(build)
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }
        return succeed(KdbxImporter.parse(encrypted, password.toCharArray()))
    }

    @Test
    fun `a KeePass entry's extra string fields cross over as custom fields`() {
        val parsed = kdbx {
            rootGroup.addEntry(
                newEntry().apply {
                    title = "Bank"
                    username = "me"
                    setPassword("hunter2")
                    setProperty("Recovery codes", "abc-def-ghi")
                    setProperty("Security question", "first pet")
                },
            )
        }

        val fields = parsed.items.single().customFields.associate { it.name to it.value }
        assertEquals("abc-def-ghi", fields["Recovery codes"])
        assertEquals("first pet", fields["Security question"])
    }

    /**
     * Everything lands HIDDEN because KeePassJava2 exposes no per-property protection flag, so this
     * importer genuinely cannot tell a protected string from a plain one. Between masking a label
     * that did not need it — one tap to reveal — and rendering a recovery code in the clear, only
     * the second is unrecoverable.
     */
    @Test
    fun `an imported KeePass field is masked by default`() {
        val parsed = kdbx {
            rootGroup.addEntry(
                newEntry().apply {
                    title = "Bank"
                    setProperty("Recovery codes", "abc-def-ghi")
                },
            )
        }

        assertEquals(CustomFieldType.HIDDEN, parsed.items.single().customFields.single().type)
    }

    @Test
    fun `the fields that already have a home on the item are not duplicated as custom fields`() {
        val parsed = kdbx {
            rootGroup.addEntry(
                newEntry().apply {
                    title = "Bank"
                    username = "me"
                    setPassword("hunter2")
                    url = "https://bank.example"
                    notes = "a note"
                    setProperty("TOTP Seed", "JBSWY3DPEHPK3PXP")
                },
            )
        }

        val item = parsed.items.single()
        assertEquals("JBSWY3DPEHPK3PXP", (item.content as Login).totp?.secretBase32)
        assertTrue(
            item.customFields.isEmpty(),
            "title/username/password/url/notes and a consumed TOTP seed must not be repeated: " +
                item.customFields.map { it.name },
        )
    }

    /**
     * The counter-case, and the reason the TOTP keys are reported as consumed only when they parse:
     * a seed in a shape [TotpFieldParser] does not know used to be dropped by the very code meant
     * to rescue it. Carried as text, it is at least still in the vault.
     */
    @Test
    fun `a TOTP seed the parser cannot read survives as a custom field`() {
        val parsed = kdbx {
            rootGroup.addEntry(
                newEntry().apply {
                    title = "Odd TOTP"
                    setProperty("TOTP Seed", "not!base32!at!all")
                },
            )
        }

        val item = parsed.items.single()
        assertNull((item.content as Login).totp)
        assertEquals("not!base32!at!all", item.customFields.single { it.name == "TOTP Seed" }.value)
    }

    // --- Bitwarden: reprompt, fields and passwordHistory were undeclared, so the decoder's
    // --- `ignoreUnknownKeys = true` discarded all three without a signal.

    private fun bitwarden(items: String): ParsedImport =
        succeed(BitwardenJsonImporter.parse("""{ "items": [ $items ] }""".toByteArray()))

    /**
     * `reprompt` is a live gate here, not a cosmetic flag: `DatasetBuilder` reads it to decide
     * whether an autofill suggestion needs re-authentication. Losing it meant the items the user
     * had *deliberately* protected in Bitwarden were the ones that arrived unprotected.
     */
    @Test
    fun `a Bitwarden item's reprompt flag survives the import`() {
        val parsed = bitwarden(
            """
            { "type": 1, "name": "Bank", "reprompt": 1, "login": { "username": "me", "password": "pw" } },
            { "type": 1, "name": "Forum", "reprompt": 0, "login": { "username": "me", "password": "pw" } }
            """.trimIndent(),
        )

        val byTitle = parsed.items.associateBy { it.title }
        assertTrue(byTitle.getValue("Bank").reprompt, "a gated item must arrive gated")
        assertTrue(!byTitle.getValue("Forum").reprompt)
    }

    @Test
    fun `Bitwarden custom fields keep their name, value and hidden-ness`() {
        val parsed = bitwarden(
            """
            { "type": 1, "name": "Bank", "login": { "username": "me", "password": "pw" },
              "fields": [
                { "name": "Branch", "value": "Plovdiv", "type": 0 },
                { "name": "Recovery codes", "value": "abc-def", "type": 1 },
                { "name": "Joint account", "value": "true", "type": 2 }
              ] }
            """.trimIndent(),
        )

        val fields = parsed.items.single().customFields
        assertEquals(3, fields.size)
        assertEquals(CustomFieldType.TEXT, fields[0].type)
        assertEquals(CustomFieldType.HIDDEN, fields[1].type)
        assertEquals("abc-def", fields[1].value)
        assertEquals(CustomFieldType.BOOLEAN, fields[2].type)
    }

    /**
     * Bitwarden's type 3 is a *linked* field — it points at another field on the same item by an
     * integer id, which is not what `CustomFieldType.LINKED` means here (that carries a
     * `VaultItem.id`). Mapping it across would produce a reference this vault cannot resolve.
     */
    @Test
    fun `a Bitwarden linked field arrives as text, not as a dangling link`() {
        val parsed = bitwarden(
            """
            { "type": 1, "name": "Bank", "login": { "username": "me", "password": "pw" },
              "fields": [ { "name": "Alias", "value": "see username", "type": 3 } ] }
            """.trimIndent(),
        )

        val field = parsed.items.single().customFields.single()
        assertEquals(CustomFieldType.TEXT, field.type)
        assertNull(field.linkedItemId)
    }

    @Test
    fun `Bitwarden password history crosses over with its dates`() {
        val parsed = bitwarden(
            """
            { "type": 1, "name": "Bank", "login": { "username": "me", "password": "new" },
              "passwordHistory": [
                { "password": "old", "lastUsedDate": "2024-01-02T03:04:05.000Z" },
                { "password": "older", "lastUsedDate": "2023-01-02T03:04:05.000Z" }
              ] }
            """.trimIndent(),
        )

        val history = (parsed.items.single().content as Login).passwordHistory
        assertEquals(listOf("old", "older"), history.map { it.password })
        assertEquals("2024-01-02T03:04:05Z", history.first().changedAt.toString())
    }
}

package com.localkeep.core.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Every [ItemContent] that carries a secret prints nothing.
 *
 * `Coordinate` and `SeedPhrase` had this from the start, with the argument written on
 * `Coordinate.toString`: a data class prints its fields, and `toString()` is what an exception
 * message, a `Log` call, a crash report and Compose's recomposition tooling all reach for when
 * handed an object. None of them names the field, so none of them is a call site anyone would think
 * to audit. The rest of the sealed hierarchy was still generated — a `Login` printed the password,
 * a `Card` its PAN, CVV and PIN, an `Identity` the SSN — which made the redaction a property of two
 * types rather than of the vault.
 *
 * This test is why the generated form cannot come back.
 */
class ItemContentRedactionTest {

    private val login = Login(
        username = "ada@example.com",
        password = "correct-horse-battery-staple",
        passwordHistory = listOf(PasswordHistoryEntry(password = "hunter2", changedAt = Instant.EPOCH)),
        notes = "the recovery codes are 111111 and 222222",
    )

    private val card = Card(
        holder = "A LOVELACE",
        number = "4111111111111111",
        cvv = "737",
        pin = "4291",
        notes = "billing address is the old one",
    )

    private val identity = Identity(
        firstName = "Ada",
        lastName = "Lovelace",
        ssn = "078-05-1120",
        passport = "X01234567",
        license = "LOVEL751213AL9AA",
    )

    private val totp = Totp(
        issuer = "GitHub",
        account = "ada@example.com",
        secretBase32 = "JBSWY3DPEHPK3PXP",
    )

    private val note = Note(body = "the safe combination is 17-32-08")

    private val passkey = Passkey(
        rpId = "bank.example",
        credentialId = byteArrayOf(1, 2, 3),
        userHandle = byteArrayOf(4, 5, 6),
        privateKeyRef = byteArrayOf(7, 8, 9),
        createdAt = Instant.EPOCH,
        userName = "ada@example.com",
        userDisplayName = "Ada Lovelace",
    )

    @Test
    fun `no content type prints anything it holds`() {
        val cases = listOf(
            login to listOf("correct-horse-battery-staple", "hunter2", "111111", "ada@example.com"),
            card to listOf("4111111111111111", "737", "4291", "A LOVELACE"),
            identity to listOf("078-05-1120", "X01234567", "LOVEL751213AL9AA", "Lovelace"),
            totp to listOf("JBSWY3DPEHPK3PXP", "GitHub", "ada@example.com"),
            note to listOf("17-32-08", "safe combination"),
            passkey to listOf("bank.example", "ada@example.com", "Ada Lovelace"),
        )

        cases.forEach { (content, secrets) ->
            val printed = content.toString()
            secrets.forEach { secret ->
                assertFalse(secret in printed, "${content::class.simpleName}.toString leaked $secret")
            }
        }
    }

    @Test
    fun `each redaction still names its type, so a log line is not a mystery`() {
        assertEquals("Login(redacted)", login.toString())
        assertEquals("Card(redacted)", card.toString())
        assertEquals("Identity(redacted)", identity.toString())
        assertEquals("Totp(redacted)", totp.toString())
        assertEquals("Note(redacted)", note.toString())
        assertEquals("Passkey(redacted)", passkey.toString())
    }

    /**
     * The containing item is a data class too, and its own generated `toString` prints its content —
     * which is the vector that makes this worth having at all, since almost nothing logs an
     * `ItemContent` directly.
     */
    @Test
    fun `the item that holds the content does not leak it either`() {
        val item = VaultItem(
            id = UUID.randomUUID(),
            title = "Bank",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = card,
        )

        assertFalse("4111111111111111" in item.toString(), "VaultItem.toString leaked the PAN")
    }

    /** Overriding `toString` must not cost the generated `equals`/`hashCode` the merge depends on. */
    @Test
    fun `redaction does not cost equality`() {
        assertEquals(login, login.copy())
        assertEquals(login.hashCode(), login.copy().hashCode())
        assertFalse(login == login.copy(password = "something else"))

        assertEquals(card, card.copy())
        assertEquals(identity, identity.copy())
        assertEquals(totp, totp.copy())
        assertEquals(note, note.copy())
    }

    @Test
    fun `custom field toString redacts hidden values`() {
        val field = CustomField(
            name = "Recovery codes",
            value = "secret-code-123",
            type = CustomFieldType.HIDDEN,
        )

        val printed = field.toString()
        assertFalse("secret-code-123" in printed)
        assertEquals(
            "CustomField(name=Recovery codes, value=redacted, type=HIDDEN, linkedItemId=null)",
            printed,
        )
        assertEquals(field, field.copy())
    }
}

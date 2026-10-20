package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Card
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EnpassImporter]'s own behaviour: the label/value CSV reading, the autofill-URI shapes, and the
 * typed JSON fields.
 */
class EnpassImporterTest {

    // ---- CSV shape ----------------------------------------------------------------------------

    /**
     * An odd number of cells after the title leaves one over, and that one is Enpass's trailing
     * note. Read as a label with no value it would be silently dropped, which is how a note the
     * user typed disappears on an import that reports success.
     */
    @Test
    fun `an odd trailing cell is the note, not an unpaired label`() {
        val path = "enpass/login-trailing-note.csv"
        val item = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single()
        val login = item.content as Login

        assertEquals("someuser", login.username)
        assertEquals("somepass", login.password)
        assertEquals("Some trailing note text", login.notes)
    }

    /** A row with no label/value pair at all is the note itself. */
    @Test
    fun `a title and one trailing cell is a note`() {
        val path = "enpass/secure-note.csv"
        val item = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single()

        assertEquals("MyNote", item.title)
        assertEquals("Some note text", (item.content as Note).body)
    }

    /** `*` marks a sensitive label, so its value arrives masked rather than as plain text. */
    @Test
    fun `a starred label makes a hidden custom field`() {
        val path = "enpass/login-sensitive-custom-field.csv"
        val item = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single()

        val answer = item.customFields.single()
        assertEquals("Security answer", answer.name, "the marker is not part of the name")
        assertEquals("42", answer.value)
        assertEquals(CustomFieldType.HIDDEN, answer.type)
    }

    /**
     * `card.csv` is the same label/value shape carrying a card: `Cardholder`, `*Number`, `Type`,
     * `*CVC`, `Expiry date`. The brand comes from the number, not from the `Type` cell.
     */
    @Test
    fun `a label-value card row becomes a card`() {
        val path = "enpass/card.csv"
        val card = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            .items.single().content as Card

        assertEquals("John Doe", card.holder)
        assertEquals("4111111111111111", card.number)
        assertEquals(CardBrand.VISA, card.brand)
        assertEquals(1, card.expMonth)
        assertEquals(2030, card.expYear)
        assertEquals("123", card.cvv)
    }

    /**
     * An unreadable one-time code is rescued into a HIDDEN field rather than dropped.
     * `TOTP_SEED_VALUE` is the upstream suite's placeholder and is not decodable Base32, so no
     * `Totp` can be built from it — but the value is still something the user has.
     */
    @Test
    fun `an unreadable one-time code is rescued rather than lost`() {
        val path = "enpass/login-one-time-code.csv"
        val item = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single()

        assertNull((item.content as Login).totp)
        val rescued = item.customFields.single()
        assertEquals(ImportDefaultTitles().rescuedTotpFieldName, rescued.name)
        assertEquals("TOTP_SEED_VALUE", rescued.value)
        assertEquals(CustomFieldType.HIDDEN, rescued.type)
    }

    /** Two `Website` labels in one row are two addresses, not one overwriting the other. */
    @Test
    fun `repeated labels keep every value`() {
        val path = "enpass/login.csv"
        val parsed = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val booking = parsed.items.first { it.title == "Booking" }.content as Login

        assertEquals(
            listOf("https://account.booking.com", "https://account.booking.com/sign-in"),
            booking.uris.map { it.uri },
        )
        // No username cell, so the e-mail becomes the username and nothing is carried beside it.
        assertEquals("rwilsoncloud@gmail.com", booking.username)
    }

    // ---- autofill URIs ------------------------------------------------------------------------

    /**
     * Enpass writes its autofill field four ways, all four in `json-login-android-url.json`. What
     * LocalKeep stores is the package name: a stored `android://<hash>@com.amazon` matches no app
     * at autofill time, so the scheme and signature hash have to come off.
     */
    @Test
    fun `every shape of Enpass autofill value yields a bare package name`() {
        assertEquals("com.amazon.0", EnpassAndroidPackage.of("com.amazon.0"))
        assertEquals("com.amazon.2", EnpassAndroidPackage.of("android://com.amazon.2"))
        assertEquals("com.amazon.3", EnpassAndroidPackage.of("androidapp://com.amazon.3"))
        assertEquals(
            "com.amazon.1",
            EnpassAndroidPackage.of(
                "android://pMUhLBalOhcc3yK-84sMiGc2U856FVVUhm8PZveoRfNFT3ocT1KWZlciAkF2ED--" +
                    "B5i_fMuNlC6JfPxcHk1AQg==@com.amazon.1",
            ),
        )
    }

    @Test
    fun `the Android autofill fields reach the imported login`() {
        val path = "enpass/json-login-android-url.json"
        val login = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            .items.single().content as Login

        assertEquals(
            listOf("com.amazon.0", "com.amazon.1", "com.amazon.2", "com.amazon.3"),
            login.androidPackages,
        )
    }

    // ---- JSON ---------------------------------------------------------------------------------

    /**
     * The credit-card fixture, whose `creditcard` template also carries an "INTERNET BANKING"
     * login section. [Card] has nowhere for a username or password, so those are carried as custom
     * fields — the password masked — rather than dropped with the item still reported as complete.
     */
    @Test
    fun `a credit-card item keeps the login fields its template carries`() {
        val path = "enpass/json-credit-card.json"
        val item = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single()
        val card = item.content as Card

        assertEquals("Emily Sample", card.holder)
        assertEquals(CardBrand.AMEX, card.brand)
        assertEquals(3, card.expMonth, "03/23 is March")
        assertEquals(2023, card.expYear)
        assertEquals("9874", card.pin)
        assertTrue(item.favorite, "the fixture's favorite flag is 1")

        assertEquals("Emily_ENP", item.customFields.first { it.name == "username" }.value)
        assertEquals(
            CustomFieldType.HIDDEN,
            item.customFields.first { it.name == "password" }.type,
            "the card's login password is a secret",
        )
    }

    /** A `section` field is a heading with no value; carrying it would add an empty custom field. */
    @Test
    fun `section fields do not become custom fields`() {
        val path = "enpass/json-login.json"
        val item = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single()

        assertFalse(
            item.customFields.any { it.name == "ADDITIONAL DETAILS" },
            "custom fields: ${item.customFields.map { it.name }}",
        )
        assertEquals("SECURITY_QUESTION", item.customFields.first { it.name == "Security question" }.value)
        assertEquals(
            CustomFieldType.HIDDEN,
            item.customFields.first { it.name == "Security answer" }.type,
            "the fixture marks the answer sensitive",
        )
    }

    /** An empty `{folders:[],items:[]}` is Bitwarden's shape, and must not be claimed as Enpass. */
    @Test
    fun `an items-and-folders document with no Enpass item is not claimed`() {
        val bitwardenShaped = """{"encrypted":false,"folders":[],"items":[]}""".toByteArray()
        assertFalse(EnpassImporter.detect(bitwardenShaped))
        assertTrue(BitwardenJsonImporter.detect(bitwardenShaped), "it is still Bitwarden's")
    }
}

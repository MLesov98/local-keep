package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.failureReason
import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bitwarden JSON, against real exports in `src/test/resources/realworld/bitwarden/` (see
 * `PROVENANCE.md` there).
 *
 * This is the class that found the defect the corpus was collected for. Bitwarden writes JSON
 * `null` — not `[]` — for every empty list on an item (`"passwordHistory": null`,
 * `"collectionIds": null`, and `"collectionIds": [null]` for an item in no collection), and a null
 * landing on a non-nullable `List` failed the *whole file*: four of the five unencrypted exports
 * here were answered with `MalformedExport`, and `detect` said no as well, so the wizard could not
 * even reach that message. See the `coerceInputValues` note on [BitwardenJsonImporter].
 */
class RealWorldBitwardenImportTest {

    private val unencrypted = listOf(
        "bitwarden/plain-export.json",
        "bitwarden/nested-folders-export.json",
        "bitwarden/passkey-export.json",
        "bitwarden/unencrypted-with-cipher-key.json",
        "bitwarden/cipher-with-collections.json",
        "bitwarden/empty-unencrypted.json",
    )

    private val encrypted = listOf(
        "bitwarden/account-encrypted-pbkdf2.json",
        "bitwarden/account-encrypted-argon2id.json",
        "bitwarden/empty-account-encrypted.json",
        "bitwarden/password-protected.json",
    )

    @Test
    fun `every unencrypted export is recognised and accounts for every item in the file`() {
        unencrypted.forEach { path ->
            assertTrue(
                BitwardenJsonImporter.detect(RealWorldCorpus.bytes(path)),
                "$path was not recognised as a Bitwarden export",
            )
            val parsed = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            assertEquals(
                RealWorldCorpus.jsonArraySize(path, "items"),
                parsed.items.size + parsed.skipped.size,
                "$path — every item in the export must become a vault item or a named skip",
            )
            // Bitwarden writes real creation/revision dates, so the merge may trust them.
            assertEquals(true, parsed.timestampsKnown, "$path timestampsKnown")
        }
    }

    /**
     * An encrypted export must say it is encrypted, and say *which* encryption. The two failure
     * modes both reasons have to beat are worse lies: "not recognised" (the file is a perfectly
     * valid Bitwarden export) and "wrong password" (no password was asked for).
     *
     * The split is V3-02 (2026-09-17). Three of these four carry `passwordProtected: true` — the
     * shape the wizard opens on a typed password, so reaching the password-free parse at all means
     * a caller skipped that step, and `ContainerEncrypted` is what says a password is involved.
     * `empty-account-encrypted.json` is the genuinely account-restricted one, which nothing the user
     * can type will ever open; it is the only file here that may answer `AccountEncrypted`, and
     * answering `ContainerEncrypted` for it told the user to fix a problem they did not have.
     */
    @Test
    fun `every encrypted export fails with the encrypted reason, not with malformed or unrecognised`() {
        val expected = mapOf(
            "bitwarden/account-encrypted-pbkdf2.json" to
                ImportFailureReason.ContainerEncrypted(SourceFormat.BITWARDEN),
            "bitwarden/account-encrypted-argon2id.json" to
                ImportFailureReason.ContainerEncrypted(SourceFormat.BITWARDEN),
            "bitwarden/password-protected.json" to
                ImportFailureReason.ContainerEncrypted(SourceFormat.BITWARDEN),
            "bitwarden/empty-account-encrypted.json" to
                ImportFailureReason.AccountEncrypted(SourceFormat.BITWARDEN),
        )
        assertEquals(encrypted.toSet(), expected.keys, "every encrypted fixture needs an expected reason")
        encrypted.forEach { path ->
            assertTrue(
                BitwardenJsonImporter.detect(RealWorldCorpus.bytes(path)),
                "$path must be recognised, or parse never runs and the typed reason never reaches the user",
            )
            assertEquals(
                expected.getValue(path),
                BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).failureReason(path),
                "$path failure reason",
            )
        }
    }

    /**
     * The full-coverage export: one item of each of Bitwarden's four mappable types, two folders,
     * one favourite, three custom fields per item, a password history entry and a TOTP URI. Every
     * number below is read out of the file.
     */
    @Test
    fun `the full-coverage export carries all four item types into their own content types`() {
        val path = "bitwarden/plain-export.json"
        val parsed = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        assertEquals(4, parsed.items.size)
        assertEquals(listOf("My Folder", "Second Folder"), parsed.folders.map { it.name }.sorted())

        val byTitle = parsed.items.associateBy { it.title }
        assertTrue(byTitle.getValue("My Secure Note").content is Note)
        assertTrue(byTitle.getValue("Card Name").content is Card)
        assertTrue(byTitle.getValue("My Identity").content is Identity)

        val login = byTitle.getValue("Login Name")
        assertEquals(true, login.favorite, "the export marks exactly this item favorite:true")
        val content = login.content as Login
        assertEquals("myusername@gmail.com", content.username)
        assertEquals("mypassword", content.password)
        assertEquals(
            listOf("https://mail.google.com", "https://google.com", "https://gmail.com"),
            content.uris.map { it.uri },
            "all three URIs, in the order the export listed them",
        )
        assertEquals(1, content.passwordHistory.size, "the export carries one previous password")
        assertEquals("oldpassword", content.passwordHistory.single().password)
        // The export's `otpauth://` URI carries the placeholder secret `DFDFDEF=`, whose trailing
        // bits make it undecodable Base32 — so no Totp can be built from it, and the seed is
        // rescued into a HIDDEN field instead of being dropped (the rescue that exists precisely
        // because an export is often a second factor's last remaining copy).
        assertEquals(null, content.totp)
        assertTrue(
            login.customFields.any { it.value.startsWith("otpauth://totp/Google:") },
            "the raw otpauth:// URI must survive as a rescued field: ${login.customFields.map { it.name }}",
        )

        // Bitwarden's three field types, on every item in this export.
        val fields = login.customFields.associateBy { it.name }
        assertEquals(CustomFieldType.TEXT, fields.getValue("Text Field").type)
        assertEquals(CustomFieldType.HIDDEN, fields.getValue("Hidden Field").type)
        assertEquals("text-field-value", fields.getValue("Text Field").value)
    }

    /** The card and identity halves of the same export, so no type is carried only in name. */
    @Test
    fun `the card and identity keep their own fields`() {
        val path = "bitwarden/plain-export.json"
        val parsed = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val card = parsed.items.first { it.content is Card }.content as Card
        assertEquals("Jane Doe", card.holder)
        assertEquals("1234567891011121", card.number)
        assertEquals("123", card.cvv)

        val identity = parsed.items.first { it.content is Identity }.content as Identity
        assertEquals("Jane", identity.firstName)
        assertEquals("Doe", identity.lastName)
        assertEquals("myemail@gmail.com", identity.email)
        assertEquals("123456789", identity.passport)
    }

    /**
     * An organisation export: `collections` instead of `folders`, and the item's membership in one.
     * Collections become tags rather than a folder, because an item belongs to as many as it was
     * shared into — see [BitwardenCollection].
     */
    @Test
    fun `an organisation export carries collection membership as tags`() {
        val path = "bitwarden/cipher-with-collections.json"
        assertEquals(1, RealWorldCorpus.jsonArraySize(path, "collections"))
        val parsed = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val item = parsed.items.single()
        assertEquals("asdf123", item.title)
        assertEquals(listOf("asdf"), item.tags, "the collection's own name, taken from the export")
        assertEquals(emptyList(), parsed.folders, "an org export has no folders to create")
    }

    /**
     * The export of an empty vault is valid and must be reported as empty, not as unreadable —
     * `detect` falls back to the keys being present rather than populated for exactly this case.
     */
    @Test
    fun `an empty vault imports as empty rather than failing`() {
        val path = "bitwarden/empty-unencrypted.json"
        val parsed = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        assertEquals(emptyList(), parsed.items)
        assertEquals(emptyList(), parsed.skipped)
    }

    /**
     * An item with a per-cipher `key` (Bitwarden's newer per-item encryption key, present on
     * unencrypted exports too) must not confuse the parse: the key is not something this app reads,
     * and the item beside it is an ordinary login.
     */
    @Test
    fun `an item carrying a per-cipher key still imports`() {
        val path = "bitwarden/unencrypted-with-cipher-key.json"
        assertTrue("\"key\"" in RealWorldCorpus.text(path), "the fixture no longer carries the field this test is about")
        val parsed = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val login = parsed.items.single().content as Login
        assertTrue(login.password.isNotEmpty(), "the login beside the cipher key must still carry its password")
    }

    /**
     * Bitwarden encodes folder nesting in the folder's *name*: this export's six records are
     * `Test`, `Test/Subfolder` (twice), `Test/SubFolder`, `Test/SubFolder/AnotherSubFolder` and
     * `SecondTest`. Created verbatim they arrived as six top-level folders with slashes in their
     * names; they now build a tree, as [CsvColumnMapper] and [KdbxImporter] already did.
     *
     * Five folders out of six records, and that is the fix rather than a loss: two records carry
     * the *identical* path `Test/Subfolder`, and a path is what identifies a folder here, so they
     * are one folder holding both records' items. Every item still lands somewhere.
     */
    @Test
    fun `a nested-folder export carries every item and every folder`() {
        val path = "bitwarden/nested-folders-export.json"
        val parsed = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        assertEquals(RealWorldCorpus.jsonArraySize(path, "items"), parsed.items.size)
        assertEquals(
            listOf("SecondTest", "Test", "Subfolder", "SubFolder", "AnotherSubFolder").sorted(),
            parsed.folders.map { it.name }.sorted(),
        )
        assertEquals(
            parsed.items.size,
            parsed.items.count { it.folderId == null || parsed.folders.any { f -> f.id == it.folderId } },
            "every item's folder is one the import creates",
        )
    }

    @Test
    fun `a nested-folder export builds a folder tree`() {
        val path = "bitwarden/nested-folders-export.json"
        val parsed = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val subfolder = parsed.folders.first { it.name == "Subfolder" }
        assertEquals(parsed.folders.first { it.name == "Test" }.id, subfolder.parentId)
    }

    /**
     * Case-variant siblings stay two folders. Bitwarden shows `Test/Subfolder` and
     * `Test/SubFolder` side by side, so collapsing them would move items the user never asked to
     * move — and the two are three levels apart in this export, since only one has a child.
     */
    @Test
    fun `case-variant sibling folders stay distinct`() {
        val path = "bitwarden/nested-folders-export.json"
        val parsed = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val test = parsed.folders.first { it.name == "Test" }
        val lower = parsed.folders.first { it.name == "Subfolder" }
        val upper = parsed.folders.first { it.name == "SubFolder" }
        assertEquals(test.id, lower.parentId)
        assertEquals(test.id, upper.parentId)
        assertEquals(upper.id, parsed.folders.first { it.name == "AnotherSubFolder" }.parentId)
    }

    /**
     * FORMAT GAP — a login's attached WebAuthn passkey still cannot be *carried*: the credential is
     * a private key bound to Bitwarden's own store, and nothing here can hold one.
     *
     * No longer silent, which was the half of this that was a defect rather than a missing feature:
     * `login.fido2Credentials` is declared now, and a login whose only credential is a passkey is
     * counted into [ParsedImport.passkeyOnlyLoginCount] and stated on the preview and the Done step
     * (1A-F8, see [BitwardenPasskeyOnlyLoginTest]). This test stays ignored for the feature it is
     * about — rescuing the credential itself onto the item.
     */
    @Test
    @Ignore("Format gap: a Bitwarden passkey cannot be carried onto an item; its absence is now reported instead")
    fun `a passkey export carries the passkey`() {
        val path = "bitwarden/passkey-export.json"
        assertTrue("fido2Credentials" in RealWorldCorpus.text(path))
        val item = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.single()
        assertTrue(
            item.customFields.any { "passkey" in it.name.lowercase() },
            "the passkey must reach the vault in some form, even if only as a rescued field",
        )
    }

    /**
     * A password-protected export opens with its export password — both key slots, PBKDF2 and
     * Argon2id, on the two KeePassXC-sourced files whose password that project's own
     * `TestImports::testBitwardenEncrypted` confirms is `a`.
     *
     * This is a known-answer test for the whole derivation chain and not a smoke test: the
     * envelope's `encKeyValidation_DO_NOT_EDIT` is MAC-checked before anything is decrypted, so a
     * key that is wrong in any detail — the salt read as base64 instead of as text, HKDF-Extract
     * run where only Expand belongs, `"mac"` and `"enc"` swapped — cannot reach a parse at all.
     *
     * The file names say "account-encrypted" and the corpus keeps them: they are the upstream
     * names, and both files in fact carry `passwordProtected: true`. `empty-account-encrypted.json`
     * is the genuinely account-encrypted one, and it is asserted below.
     */
    @Test
    fun `a password-protected export opens with its export password`() {
        listOf(
            "bitwarden/account-encrypted-pbkdf2.json",
            "bitwarden/account-encrypted-argon2id.json",
        ).forEach { path ->
            assertTrue(
                BitwardenJsonImporter.needsPassword(RealWorldCorpus.bytes(path)),
                "$path must ask for a password, or the wizard never offers a step to type one into",
            )
            val parsed = BitwardenJsonImporter
                .parse(RealWorldCorpus.bytes(path), "a".toCharArray())
                .orFail(path)
            assertTrue(
                parsed.items.isNotEmpty(),
                "$path decrypted but carried no items, so the plaintext was not a Bitwarden export",
            )
        }
    }

    /** The wrong password is named as one — never as a damaged file, and never as "re-export". */
    @Test
    fun `a wrong export password is named as a wrong password`() {
        val path = "bitwarden/account-encrypted-pbkdf2.json"
        assertEquals(
            ImportFailureReason.WrongExportPassword(SourceFormat.BITWARDEN),
            BitwardenJsonImporter
                .parse(RealWorldCorpus.bytes(path), "not-the-password".toCharArray())
                .failureReason(path),
        )
    }

    /**
     * The other half of the format, and the reason `passwordProtected` is read rather than
     * inferred: an account-encrypted export is sealed with a key that never leaves the Bitwarden
     * account, so no password opens it and none is asked for. It gets `AccountEncrypted`, whose copy
     * names the two remedies that work — and never `ContainerEncrypted`, which calls the file
     * password-protected (the shape that *does* import) and tells the user to export in the clear.
     */
    @Test
    fun `an account-encrypted export is still refused, with no password step offered`() {
        val path = "bitwarden/empty-account-encrypted.json"
        assertEquals(false, BitwardenJsonImporter.needsPassword(RealWorldCorpus.bytes(path)))
        assertEquals(
            ImportFailureReason.AccountEncrypted(SourceFormat.BITWARDEN),
            BitwardenJsonImporter
                .parse(RealWorldCorpus.bytes(path), "a".toCharArray())
                .failureReason(path),
        )
    }

    /**
     * The control for the assertion above, and the whole point of the split: the file whose export
     * password *does* open it must not be described as account-restricted. Typing the wrong password
     * for it is `WrongExportPassword` (asserted above); reaching the password-free parse is
     * `ContainerEncrypted`. Neither is `AccountEncrypted`, on any path.
     */
    @Test
    fun `a password-protected export is never refused as account-encrypted`() {
        listOf(
            "bitwarden/password-protected.json",
            "bitwarden/account-encrypted-pbkdf2.json",
            "bitwarden/account-encrypted-argon2id.json",
        ).forEach { path ->
            val reason = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).failureReason(path)
            assertEquals(
                ImportFailureReason.ContainerEncrypted(SourceFormat.BITWARDEN),
                reason,
                "$path carries passwordProtected:true and must not be named account-restricted",
            )
        }
    }

    /** An ordinary unencrypted export never sees the password step. */
    @Test
    fun `an unencrypted export does not ask for a password`() {
        unencrypted.forEach { path ->
            assertEquals(
                false,
                BitwardenJsonImporter.needsPassword(RealWorldCorpus.bytes(path)),
                "$path must not be sent to the password step",
            )
        }
    }

    /** The export password is wiped by the importer, whatever the outcome — the KDBX contract. */
    @Test
    fun `the export password is zeroed on both the success and the failure path`() {
        val path = "bitwarden/account-encrypted-pbkdf2.json"
        val right = "a".toCharArray()
        BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path), right).orFail(path)
        assertTrue(right.all { it == '\u0000' }, "password not wiped after a successful open")

        val wrong = "nope".toCharArray()
        BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path), wrong).failureReason(path)
        assertTrue(wrong.all { it == '\u0000' }, "password not wiped after a failed open")
    }
}

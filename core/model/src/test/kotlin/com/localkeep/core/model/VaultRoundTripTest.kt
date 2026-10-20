package com.localkeep.core.model

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VaultRoundTripTest {
    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)
    private val t1 = Instant.ofEpochMilli(1_700_000_100_000L)
    private val rootFolderId = uuid("00000000-0000-0000-0000-0000000000f0")

    private fun uuid(value: String): UUID = UUID.fromString(value)

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }

    private fun item(
        id: String,
        content: ItemContent,
        deleted: Boolean = false,
    ): VaultItem = VaultItem(
        id = uuid(id),
        title = "item-$id",
        folderId = rootFolderId,
        tags = listOf("a", "b"),
        favorite = true,
        createdAt = t0,
        updatedAt = t1,
        reprompt = true,
        customFields = listOf(
            CustomField("plain", "v", CustomFieldType.TEXT),
            CustomField("secret", "s", CustomFieldType.HIDDEN),
            CustomField("flag", "true", CustomFieldType.BOOLEAN),
            CustomField(
                name = "link",
                type = CustomFieldType.LINKED,
                linkedItemId = uuid("00000000-0000-0000-0000-0000000000aa"),
            ),
        ),
        attachments = listOf(
            Attachment(
                id = uuid("00000000-0000-0000-0000-0000000000bb"),
                filename = "a.pdf",
                mime = "application/pdf",
                sizeBytes = 123L,
                blobRef = "blob-1",
                thumbnailRef = "thumb-1",
            ),
        ),
        deletedAt = if (deleted) t1 else null,
        content = content,
    )

    private fun completeVault(): Vault = Vault(
        folders = listOf(
            Folder(rootFolderId, "Root"),
            Folder(
                id = uuid("00000000-0000-0000-0000-0000000000f1"),
                name = "Child",
                parentId = rootFolderId,
            ),
        ),
        items = listOf(
            item(
                "00000000-0000-0000-0000-000000000001",
                Login(
                    username = "u",
                    password = "p",
                    passwordHistory = listOf(PasswordHistoryEntry("old", t0)),
                    uris = listOf(UriMatch("https://example.com", MatchStrategy.DOMAIN)),
                    androidPackages = listOf("com.example.app"),
                    totp = Totp(
                        issuer = "Ex",
                        account = "u",
                        secretBase32 = "JBSWY3DPEHPK3PXP",
                        algorithm = TotpAlgorithm.SHA256,
                        digits = 8,
                        periodSec = 60,
                    ),
                    notes = "n",
                ),
            ),
            item(
                "00000000-0000-0000-0000-000000000002",
                Totp(
                    issuer = "Steam",
                    account = "acct",
                    secretBase32 = "GEZDGNBV",
                    steam = true,
                ),
            ),
            item(
                "00000000-0000-0000-0000-000000000003",
                Note("multi\nline"),
            ),
            item(
                "00000000-0000-0000-0000-000000000004",
                Card(
                    holder = "Jane",
                    number = "4111111111111111",
                    brand = CardBrand.VISA,
                    expMonth = 12,
                    expYear = 2030,
                    cvv = "123",
                    pin = "0000",
                    notes = "note",
                ),
            ),
            item(
                "00000000-0000-0000-0000-000000000005",
                Identity(
                    firstName = "Jane",
                    lastName = "Doe",
                    email = "j@x.com",
                    address = Address("l1", "l2", "City", "ST", "00000", "US"),
                ),
            ),
            item(
                "00000000-0000-0000-0000-000000000006",
                File(
                    filename = "photo.jpg",
                    mime = "image/jpeg",
                    sizeBytes = 4096L,
                    blobRef = "blob-2",
                    thumbnailRef = "thumb-2",
                    capturedInApp = true,
                    removedFromGallery = true,
                ),
            ),
            item(
                "00000000-0000-0000-0000-000000000007",
                Passkey(
                    rpId = "example.com",
                    credentialId = byteArrayOf(1, 2, 3),
                    userHandle = byteArrayOf(4, 5),
                    privateKeyRef = byteArrayOf(9, 8, 7),
                    createdAt = t0,
                    userName = "jane",
                    userDisplayName = "Jane Doe",
                ),
            ),
            item(
                "00000000-0000-0000-0000-000000000008",
                Note("trashed"),
                deleted = true,
            ),
        ),
        createdAt = t0,
        modifiedAt = t1,
    )

    @Test
    fun `vault with every item type round-trips losslessly`() {
        val vault = completeVault()

        val decoded = VaultSerializer.decode(VaultSerializer.encode(vault))

        assertEquals(vault, decoded)
        assertTrue(decoded.items.last().isDeleted)
    }

    @Test
    fun `encoding same vault is deterministic`() {
        val vault = completeVault()
        val encoded = VaultSerializer.encodeAtVersion(vault, 2)

        assertContentEquals(
            encoded,
            VaultSerializer.encodeAtVersion(vault, 2),
        )
        // field 1 (schemaVersion), varint, always encoded. Pinned to 2 rather than read from
        // CURRENT_SCHEMA_VERSION so the byte stays a literal the reviewer can check; the
        // current-version prefix is asserted by the schema-4 variants in FolderWireFormatTest.
        assertContentEquals(byteArrayOf(0x08, 0x02), encoded.copyOf(2))
    }

    @Test
    fun `wire format stays stable`() {
        assertEquals(
            "693562be0aad1d52d249403f46b8a462fd9fe4b8e85aea094f080f063c7b1a99",
            // Pinned to its own version stamp: encode() writes CURRENT_SCHEMA_VERSION, so without
            // encodeAtVersion every bump would silently rewrite a hash whose whole job is to not
            // move. The value is unchanged across schema 3 and 4; only the version it is measured
            // at is explicit.
            VaultSerializer.encodeAtVersion(completeVault(), 2).sha256(),
        )
    }

    @Test
    fun `schema 2 changed nothing on the wire except the version byte`() {
        // The load-bearing compatibility claim behind the schema-1 -> 2 bump. Adding
        // Folder.updatedAt must be purely additive: an existing vault, re-encoded with its own
        // version stamp and no timestamps set, has to produce the exact bytes it always did.
        //
        // Asserting the historical hash here (rather than only updating the one above) is what
        // makes the bump reviewable — a new golden hash on its own would hide an accidental
        // renumbering or reordering just as effectively as it records an intended change.
        assertEquals(
            "12b0c461f8435f8c2d295cd69374319c9037c09e4fbff47c58006d38b50e53f7",
            VaultSerializer.encodeAtVersion(completeVault(), 1).sha256(),
        )
    }

    @Test
    fun `an unset folder timestamp is absent from the wire, not encoded as a zero`() {
        // encodeDefaults = false is what makes the line above hold. If a null updatedAt were ever
        // written as a present-but-empty field 4, every pre-schema-2 vault would grow bytes on
        // re-save and the "null means unknown" merge rule would stop being observable.
        val undated = Vault(
            folders = listOf(Folder(rootFolderId, "Root")),
            createdAt = t0,
            modifiedAt = t1,
        )
        val dated = undated.copy(
            folders = listOf(Folder(rootFolderId, "Root", updatedAt = t1)),
        )
        assertTrue(
            VaultSerializer.encode(dated).size > VaultSerializer.encode(undated).size,
            "a set timestamp must occupy bytes an unset one does not",
        )
        assertEquals(null, VaultSerializer.decode(VaultSerializer.encode(undated)).folders.single().updatedAt)
        assertEquals(t1, VaultSerializer.decode(VaultSerializer.encode(dated)).folders.single().updatedAt)
    }

    @Test
    fun `login passwordChangedAt round-trips and is absent by default`() {
        val withField = item(
            "00000000-0000-0000-0000-0000000000c1",
            Login(username = "u", password = "p", passwordChangedAt = t1),
        )
        val vault = Vault(items = listOf(withField), createdAt = t0, modifiedAt = t1)

        val decoded = VaultSerializer.decode(VaultSerializer.encode(vault))
        assertEquals(t1, (decoded.items.single().content as Login).passwordChangedAt)
        // A Login that never set the field defaults to null (old vaults decode this way).
        assertEquals(null, Login(password = "p").passwordChangedAt)
    }

    @Test
    fun `malformed bytes return storage failure`() {
        val result = VaultSerializer.decodeCatching(byteArrayOf(0x7f))

        val failure = assertIs<Outcome.Failure>(result)
        assertIs<AppError.Storage>(failure.error)
    }
}

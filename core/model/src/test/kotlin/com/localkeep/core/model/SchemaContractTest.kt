package com.localkeep.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaContractTest {
    private fun assertFields(
        serializer: KSerializer<*>,
        vararg fields: Pair<String, Int>,
    ) {
        val descriptor = serializer.descriptor
        assertEquals(fields.size, descriptor.elementsCount, descriptor.serialName)

        fields.forEachIndexed { index, (name, number) ->
            assertEquals(name, descriptor.getElementName(index), descriptor.serialName)
            assertEquals(
                number,
                descriptor.getElementAnnotations(index)
                    .filterIsInstance<ProtoNumber>()
                    .single()
                    .number,
                "$name in ${descriptor.serialName}",
            )
        }
    }

    @Test
    fun `all schema fields keep frozen protobuf numbers`() {
        assertFields(
            Address.serializer(),
            "line1" to 1,
            "line2" to 2,
            "city" to 3,
            "state" to 4,
            "zip" to 5,
            "country" to 6,
        )
        assertFields(
            UriMatch.serializer(),
            "uri" to 1,
            "strategy" to 2,
        )
        assertFields(
            PasswordHistoryEntry.serializer(),
            "password" to 1,
            "changedAt" to 2,
        )
        assertFields(
            CustomField.serializer(),
            "name" to 1,
            "value" to 2,
            "type" to 3,
            "linkedItemId" to 4,
        )
        assertFields(
            Attachment.serializer(),
            "id" to 1,
            "filename" to 2,
            "mime" to 3,
            "sizeBytes" to 4,
            "blobRef" to 5,
            "thumbnailRef" to 6,
        )
        assertFields(
            Folder.serializer(),
            "id" to 1,
            "name" to 2,
            "parentId" to 3,
            "updatedAt" to 4, // added in schema 2
            "parentUpdatedAt" to 5, // added in schema 3
            "deletedAt" to 6, // added in schema 4
            "trashUpdatedAt" to 7, // added in schema 4
            "trashRootId" to 8, // added in schema 4
            "hiddenUntil" to 9, // added in schema 5
            "hiddenUpdatedAt" to 10, // added in schema 5
        )
        assertFields(
            RevealPhraseVerifier.serializer(),
            "salt" to 1,
            "memoryKib" to 2,
            "iterations" to 3,
            "parallelism" to 4,
            "hash" to 5,
        )
        assertFields(
            Login.serializer(),
            "username" to 1,
            "password" to 2,
            "passwordHistory" to 3,
            "uris" to 4,
            "androidPackages" to 5,
            "totp" to 6,
            "notes" to 7,
            "passwordChangedAt" to 8,
        )
        assertFields(
            Totp.serializer(),
            "issuer" to 1,
            "account" to 2,
            "secretBase32" to 3,
            "algorithm" to 4,
            "digits" to 5,
            "periodSec" to 6,
            "steam" to 7,
        )
        assertFields(
            Note.serializer(),
            "body" to 1,
        )
        assertFields(
            Card.serializer(),
            "holder" to 1,
            "number" to 2,
            "brand" to 3,
            "expMonth" to 4,
            "expYear" to 5,
            "cvv" to 6,
            "pin" to 7,
            "notes" to 8,
        )
        assertFields(
            Identity.serializer(),
            "firstName" to 1,
            "lastName" to 2,
            "company" to 3,
            "ssn" to 4,
            "passport" to 5,
            "license" to 6,
            "email" to 7,
            "phone" to 8,
            "address" to 9,
        )
        assertFields(
            File.serializer(),
            "filename" to 1,
            "mime" to 2,
            "sizeBytes" to 3,
            "blobRef" to 4,
            "thumbnailRef" to 5,
            "capturedInApp" to 6,
            "removedFromGallery" to 7,
            "notes" to 8, // added in schema 8
        )
        assertFields(
            Passkey.serializer(),
            "rpId" to 1,
            "credentialId" to 2,
            "userHandle" to 3,
            "privateKeyRef" to 4,
            "algorithm" to 5,
            "signCounter" to 6,
            "createdAt" to 7,
            "userName" to 8,
            "userDisplayName" to 9,
        )
        assertFields(
            Coordinate.serializer(), // added in schema 6
            "latitude" to 1,
            "longitude" to 2,
            "capturedAt" to 3,
            "accuracyMeters" to 4,
        )
        assertFields(
            SeedPhrase.serializer(), // added in schema 7
            "words" to 1,
            "passphrase" to 2,
        )
        assertFields(
            VaultItem.serializer(),
            "id" to 1,
            "title" to 2,
            "folderId" to 3,
            "tags" to 4,
            "favorite" to 5,
            "createdAt" to 6,
            "updatedAt" to 7,
            "reprompt" to 8,
            "customFields" to 9,
            "attachments" to 10,
            "deletedAt" to 11,
            "content" to 12,
            "trashedWithFolderId" to 13, // added in schema 4
            "hiddenUntil" to 14, // added in schema 5
            "hiddenUpdatedAt" to 15, // added in schema 5
        )
        assertFields(
            Vault.serializer(),
            "schemaVersion" to 1,
            "items" to 2,
            "folders" to 3,
            "createdAt" to 4,
            "modifiedAt" to 5,
            "revealPhrase" to 6, // added in schema 5
        )
    }

    @Test
    fun `polymorphic subtype names stay frozen`() {
        assertEquals("login", Login.serializer().descriptor.serialName)
        assertEquals("totp", Totp.serializer().descriptor.serialName)
        assertEquals("note", Note.serializer().descriptor.serialName)
        assertEquals("card", Card.serializer().descriptor.serialName)
        assertEquals("identity", Identity.serializer().descriptor.serialName)
        assertEquals("file", File.serializer().descriptor.serialName)
        assertEquals("passkey", Passkey.serializer().descriptor.serialName)
        assertEquals("coordinate", Coordinate.serializer().descriptor.serialName)
        assertEquals("seedPhrase", SeedPhrase.serializer().descriptor.serialName)
    }

    @Test
    fun `enum ordinals stay append-only`() {
        assertEquals(
            listOf("DOMAIN", "HOST", "STARTS_WITH", "EXACT", "REGEX", "NEVER"),
            MatchStrategy.entries.map { it.name },
        )
        assertEquals(
            listOf("SHA1", "SHA256", "SHA512"),
            TotpAlgorithm.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "UNKNOWN",
                "VISA",
                "MASTERCARD",
                "AMEX",
                "DISCOVER",
                "JCB",
                "DINERS",
                "UNIONPAY",
                "MAESTRO",
            ),
            CardBrand.entries.map { it.name },
        )
        assertEquals(listOf("ES256"), PasskeyAlgorithm.entries.map { it.name })
        assertEquals(
            listOf("TEXT", "HIDDEN", "BOOLEAN", "LINKED"),
            CustomFieldType.entries.map { it.name },
        )
    }
}

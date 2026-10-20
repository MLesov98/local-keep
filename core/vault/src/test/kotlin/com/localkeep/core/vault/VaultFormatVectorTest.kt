package com.localkeep.core.vault

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KdfParamsCodec
import com.localkeep.core.crypto.KeyCommitment
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.VersionedHeader
import com.localkeep.core.model.Card
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Known-answer test for the version-1 vault container.
 *
 * Every other test in this module encrypts something and decrypts it back, so it passes whatever the
 * format happens to be *this build* — change the Argon2 parallelism default, reorder two header
 * fields, swap the AEAD, and a round-trip test still goes green while every vault already on a
 * user's phone stops opening. This one decrypts **committed bytes** that this build did not produce.
 * It is the only test here that can fail because the format moved.
 *
 * The vector is a real container written by `VaultFileCodec`, and the password below is what opens
 * it. That is not a leaked credential: the file is fabricated test data with no relationship to any
 * vault anyone owns, which is why the plaintext it holds is deliberately unmistakable — addresses
 * under `.invalid`, a published Visa test number, and a note that says what it is.
 *
 * **Regenerating.** Do not, for v1. Freezing these bytes is the entire point; if a change makes this
 * test fail, that change breaks existing vaults and needs a format version bump and a migration
 * path, not a new vector. A v2 gets its own file alongside this one, and this one stays as the proof
 * that v2 can still read v1.
 */
class VaultFormatVectorTest {

    private val vectorPath = "/vectors/vault-v1-argon2id-floor.vault"
    private val password = "correct horse battery staple".toCharArray()

    /** Both fixed in the vector so the KDF is reproducible; a real vault draws its salt randomly. */
    private val salt = ByteArray(CryptoConstants.SALT_LEN) { (it + 1).toByte() }
    private val kdf = KdfParams.FLOOR

    private fun vectorBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(vectorPath)) {
            "missing vector $vectorPath"
        }.use { it.readBytes() }

    /** The DEK the vector's first unlock slot wraps, recovered the way an unlock recovers it. */
    private fun dek(): ByteArray {
        val header = VaultHeaderCodec.decode(vectorBytes())
        val kek = EnvelopeCrypto.deriveKek(password, salt, kdf)
        return EnvelopeCrypto.unwrapDek(kek, header.slots[0].wrappedDek)
    }

    @Test
    fun `the committed vector still opens with its password`() {
        // The whole point, in one assertion: bytes this build did not write, opened by this build.
        val encKey = VaultSubkeys.encKey(dek())
        val (_, vault) = VaultFileCodec.decrypt(vectorBytes(), encKey)

        assertEquals(2, vault.schemaVersion)
        assertEquals(3, vault.items.size)
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), vault.createdAt)
    }

    @Test
    fun `every field of the vector's plaintext is byte-for-byte what it was`() {
        // Not just "it decrypted". A serializer change that dropped a field, or a proto number that
        // moved, would still decrypt and still parse — and would silently lose that field on the
        // next save.
        val encKey = VaultSubkeys.encKey(dek())
        val (_, vault) = VaultFileCodec.decrypt(vectorBytes(), encKey)

        val login = vault.items.single { it.id == uuid(1) }
        assertEquals("Example Login", login.title)
        assertEquals(listOf("vector"), login.tags)
        assertEquals(uuid(0xf001), login.folderId)
        val credentials = login.content as Login
        assertEquals("vector@example.invalid", credentials.username)
        assertEquals("not-a-real-password", credentials.password)
        assertEquals(listOf("https://example.invalid"), credentials.uris.map { it.uri })

        val note = vault.items.single { it.id == uuid(2) }
        assertEquals("Known-answer vector. Not a real secret.", (note.content as Note).body)

        val card = vault.items.single { it.id == uuid(3) }.content as Card
        assertEquals("A Vector", card.holder)
        assertEquals("4111111111111111", card.number)
        assertEquals(CardBrand.VISA, card.brand)
        assertEquals(12, card.expMonth)
        assertEquals(2030, card.expYear)

        val folder = vault.folders.single()
        assertEquals("Vectors", folder.name)
        assertEquals(uuid(0xf001), folder.id)
    }

    @Test
    fun `the vector's header still declares the algorithms this build implements`() {
        // An algorithm-agility prefix is only worth anything if somebody checks it. These three
        // bytes are what a second implementation would read first.
        val header = VaultHeaderCodec.decode(vectorBytes())

        assertContentEquals(VersionedHeader.MAGIC, vectorBytes().copyOfRange(0, 4))
        assertEquals(CryptoConstants.FORMAT_VERSION_1, header.versioned.formatVersion)
        assertEquals(CryptoConstants.KDF_ARGON2ID, header.versioned.kdfAlgId)
        assertEquals(CryptoConstants.AEAD_AES256_GCM_SIV, header.versioned.aeadAlgId)
    }

    @Test
    fun `the vector's KDF parameters survive the header encoding unchanged`() {
        // The divergence this whole file exists to catch: an implementation that writes a different
        // Argon2 parallelism produces a KEK nobody else derives, and the vault is unopenable with
        // the correct password. Asserted against the literal numbers, not against KdfParams.FLOOR,
        // so moving the floor cannot move the expectation with it.
        val header = VaultHeaderCodec.decode(vectorBytes())
        val params = header.slots[0].kdfParams

        assertEquals(19_456, params.memoryKib)
        assertEquals(2, params.iterations)
        assertEquals(1, params.parallelism)
        assertEquals(KdfParamsCodec.ENCODED_SIZE, KdfParamsCodec.encode(params).size)
        assertEquals(params, header.slots[1].kdfParams, "both slots carry equal KDF parameters")
    }

    @Test
    fun `the vector's slot commitment still matches the DEK it wraps`() {
        // Constant-time commitment verification is what tells "wrong password" from "damaged file".
        // If the commitment derivation moved, every unlock would report the wrong one of those.
        val commitKey = VaultSubkeys.commitKey(dek())

        assertTrue(KeyCommitment.verify(commitKey, VaultHeaderCodec.decode(vectorBytes()).slots[0].commitment))
    }

    @Test
    fun `the vector's container layout is exactly what the spec describes`() {
        val bytes = vectorBytes()
        val header = VaultHeaderCodec.decode(bytes)

        assertEquals(VaultHeaderCodec.ENCODED_SIZE, header.regions[0].offset)
        assertEquals(header.regions[0].offset + header.regions[0].length, header.regions[1].offset)
        assertEquals(header.regions[0].length, header.regions[1].length, "regions are equal length")
        // Two equal payload regions plus the trailing recovery block, and nothing else.
        assertEquals(
            header.regions[1].offset + header.regions[1].length + VaultRecoveryBlock.ENCODED_SIZE,
            bytes.size,
        )
    }

    @Test
    fun `the sizes the vault format spec quotes are the sizes the code uses`() {
        // The spec is prose, and prose drifts. These are the numbers a second implementation would
        // hard-code off it, so they are asserted here rather than trusted to stay true.
        assertEquals(289, VaultHeaderCodec.ENCODED_SIZE, "plaintext header")
        assertEquals(65, VaultRecoveryBlock.ENCODED_SIZE, "recovery block")
        assertEquals(7, VersionedHeader.ENCODED_SIZE, "magic + three algorithm bytes")
        assertEquals(12, KdfParamsCodec.ENCODED_SIZE, "three big-endian KDF ints")
        assertEquals(256, VaultFileCodec.MIN_PLAINTEXT_BUCKET, "smallest payload bucket")
        assertEquals(49, VaultHeaderCodec.payloadAad(VaultHeaderCodec.decode(vectorBytes())).size)
    }

    @Test
    fun `the vector's filler region is not a second vault`() {
        // A container with no duress vault seals random filler into region 1 under the real key, so
        // it authenticates but does not parse. That is what makes "duress configured" and "not
        // configured" indistinguishable from the file, and it must stay true of the vector.
        val encKey = VaultSubkeys.encKey(dek())

        assertTrue(VaultFileCodec.authenticatesRegion(vectorBytes(), encKey, regionIndex = 1))
        val parsed = runCatching { VaultFileCodec.decryptRegion(vectorBytes(), encKey, 1) }
        assertTrue(parsed.isFailure, "filler must not decode as a Vault")
    }

    @Test
    fun `a wrong password does not open the vector`() {
        val header = VaultHeaderCodec.decode(vectorBytes())
        val wrongKek = EnvelopeCrypto.deriveKek("wrong horse battery staple".toCharArray(), salt, kdf)

        val unwrapped = runCatching { EnvelopeCrypto.unwrapDek(wrongKek, header.slots[0].wrappedDek) }
        assertTrue(unwrapped.isFailure)
    }

    @Test
    fun `the vault the vector decodes re-encodes unchanged except for its version stamp`() {
        // Guards the serializer in the other direction: a save after an unlock must not rewrite the
        // payload into something a peer implementation reads differently. Every field has to
        // survive the round trip untouched.
        //
        // With exactly one exception, and it is deliberate. The vector is a schema-2 vault, and
        // since schema 4 a save stamps CURRENT_SCHEMA_VERSION rather than preserving whatever was
        // read — opening an old vault in this build upgrades it. Asserting the upgrade here rather
        // than excluding the field is the point: if the stamp ever stopped being written, a payload
        // could go back to disk declaring an older format than it actually contains, and an older
        // build would open it and silently truncate the fields it does not know.
        val encKey = VaultSubkeys.encKey(dek())
        val (_, vault) = VaultFileCodec.decrypt(vectorBytes(), encKey)
        assertEquals(2, vault.schemaVersion, "the vector must still be the old format this asserts about")

        val reEncoded = com.localkeep.core.model.VaultSerializer.encode(vault)
        val reDecoded: Vault = com.localkeep.core.model.VaultSerializer.decode(reEncoded)

        assertEquals(Vault.CURRENT_SCHEMA_VERSION, reDecoded.schemaVersion)
        assertEquals(vault.copy(schemaVersion = Vault.CURRENT_SCHEMA_VERSION), reDecoded)
    }

    private fun uuid(tail: Int) =
        UUID.fromString("00000000-0000-4000-8000-%012x".format(tail))
}

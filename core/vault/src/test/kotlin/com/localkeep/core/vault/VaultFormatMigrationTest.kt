package com.localkeep.core.vault

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.WrappedDek
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Vault-format migration backstop (Phase 16, Task 3 Step 5, §I / §12.10 MUST). If a v2 format is
 * ever introduced, this is where the migration corpus grows: add a v2 fixture that decodes
 * correctly AND a "v1 still decodes under the v2-aware decoder" regression case.
 */
class VaultFormatMigrationTest {
    private val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
    private val wrappedDek = WrappedDek(
        nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
        ciphertext = SecureRandomProvider.nextBytes(
            CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
        ),
    )
    private val commitment = SecureRandomProvider.nextBytes(UnlockSlot.COMMITMENT_SIZE)

    @Test
    fun `a frozen v1 header decodes field for field`() {
        val header = VaultHeaderCodec.decode(Base64.getDecoder().decode(V1_HEADER_BASE64))

        assertEquals(CryptoConstants.FORMAT_VERSION_1, header.versioned.formatVersion)
        assertEquals(CryptoConstants.KDF_ARGON2ID, header.versioned.kdfAlgId)
        assertEquals(CryptoConstants.AEAD_AES256_GCM_SIV, header.versioned.aeadAlgId)
        assertEquals(VaultHeader.NUM_SLOTS, header.slots.size)
        assertEquals(VaultHeader.NUM_REGIONS, header.regions.size)

        // The real slot: every field read back at the offset it was written to. A reordered or
        // resized field would still decode into *something*, which is exactly why the assertion has
        // to name the values rather than just the shape.
        val slot = header.slots[0]
        assertContentEquals(ByteArray(CryptoConstants.SALT_LEN) { (it + 1).toByte() }, slot.salt)
        assertEquals(KdfParams(memoryKib = 65536, iterations = 3, parallelism = 4), slot.kdfParams)
        assertContentEquals(
            ByteArray(CryptoConstants.AEAD_NONCE_LEN) { (0x40 + it).toByte() },
            slot.wrappedDek.nonce,
        )
        assertContentEquals(
            ByteArray(CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN) { (0x80 + it).toByte() },
            slot.wrappedDek.ciphertext,
        )
        assertContentEquals(
            ByteArray(UnlockSlot.COMMITMENT_SIZE) { (0xC0 + it).toByte() },
            slot.commitment,
        )

        // Regions carry the offsets the payload is read from, so an off-by-one here is a vault that
        // decrypts to garbage rather than one that fails to open.
        assertEquals(289, header.regions[0].offset)
        assertEquals(4096, header.regions[0].length)
        assertEquals(4385, header.regions[1].offset)
        assertEquals(2048, header.regions[1].length)
    }

    @Test
    fun `today's encoder still writes the frozen v1 layout, byte for byte`() {
        assertContentEquals(
            Base64.getDecoder().decode(V1_HEADER_BASE64),
            VaultHeaderCodec.encode(
                VaultHeaderCodec.decode(Base64.getDecoder().decode(V1_HEADER_BASE64)),
            ),
        )
    }

    @Test
    fun `an unknown format version fails cleanly, not with a crash`() {
        val header = VaultHeaderCodec.create(salt, KdfParams.DEFAULT, wrappedDek, commitment)
        val encoded = VaultHeaderCodec.encode(header)
        val unknownVersion = encoded.copyOf().also { it[4] = 99 }

        val failure = assertFailsWith<IllegalArgumentException> {
            VaultHeaderCodec.decode(unknownVersion)
        }
        assertTrue(
            failure.message.orEmpty().contains("unsupported vault format version"),
            "expected a clear unsupported-format-version message, got: ${failure.message}",
        )
    }

    private companion object {
        /**
         * A version-1 header exactly as it exists on disk, frozen as bytes.
         *
         * The test this replaced built its "real v1 vault fixture" with `VaultHeaderCodec.encode`
         * one line before decoding it, so it round-tripped today's codec against itself: reorder
         * two fields in `writeSlot` and `readSlot` together and it still passed, while every vault
         * already on a user's phone would have been read at the wrong offsets and reported as a
         * wrong password. `VaultHeaderCodec` writes raw sequential offsets with no field tags, which
         * is the shape of codec where only a frozen fixture can catch that.
         *
         * Generated once from the codec (deterministic byte patterns, `KdfParams.DEFAULT` at
         * 65536/3/4) and committed as a literal. It must never be regenerated to make a test pass —
         * a v2 format gets a *second* fixture beside this one, not an edit to this one.
         */
        const val V1_HEADER_BASE64: String =
            "VkxUMQEBAQICAQIDBAUGBwgJCgsMDQ4PEAABAAAAAAADAAAABEBBQkNERUZHSElKS4CBgoOEhYa" +
                "HiImKi4yNjo+QkZKTlJWWl5iZmpucnZ6foKGio6SlpqeoqaqrrK2ur8DBwsPExcbHyMnKy8zN" +
                "zs/Q0dLT1NXW19jZ2tvc3d7fEBESExQVFhcYGRobHB0eHwABAAAAAAADAAAABFBRUlNUVVZXW" +
                "FlaW5CRkpOUlZaXmJmam5ydnp+goaKjpKWmp6ipqqusra6vsLGys7S1tre4ubq7vL2+v9DR0t" +
                "PU1dbX2Nna29zd3t/g4eLj5OXm5+jp6uvs7e7vICEiIyQlJicoKSorAAABIQAAEAAwMTIzNDU" +
                "2Nzg5OjsAABEhAAAIAA=="
    }
}

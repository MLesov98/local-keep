package com.localkeep.core.vault

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.WrappedDek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VaultHeaderCodecTest {
    private val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
    private val wrappedDek = WrappedDek(
        nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
        ciphertext = SecureRandomProvider.nextBytes(
            CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
        ),
    )
    private val commitment = SecureRandomProvider.nextBytes(UnlockSlot.COMMITMENT_SIZE)

    @Test
    fun `header round-trip is byte-exact and fixed-size`() {
        val header = header()
        val encoded = VaultHeaderCodec.encode(header)

        assertEquals(289, VaultHeaderCodec.ENCODED_SIZE)
        assertEquals(VaultHeaderCodec.ENCODED_SIZE, encoded.size)
        assertEquals(header, VaultHeaderCodec.decode(encoded))
        assertEquals(encoded.toList(), VaultHeaderCodec.encode(VaultHeaderCodec.decode(encoded)).toList())
    }

    @Test
    fun `full vault bytes can be passed to header decoder`() {
        val expected = header()
        val encoded = VaultHeaderCodec.encode(expected) + ByteArray(32)

        assertEquals(expected, VaultHeaderCodec.decode(encoded))
    }

    @Test
    fun `bad magic and truncated input are rejected`() {
        val badMagic = VaultHeaderCodec.encode(header()).also { it[0] = 0 }

        assertFailsWith<IllegalArgumentException> {
            VaultHeaderCodec.decode(badMagic)
        }
        assertFailsWith<IllegalArgumentException> {
            VaultHeaderCodec.decode(ByteArray(VaultHeaderCodec.ENCODED_SIZE - 1))
        }
    }

    @Test
    fun `unsupported version and algorithm IDs are rejected`() {
        val encoded = VaultHeaderCodec.encode(header())

        assertFailsWith<IllegalArgumentException> {
            VaultHeaderCodec.decode(encoded.copyOf().also { it[4] = 2 })
        }
        assertFailsWith<IllegalArgumentException> {
            VaultHeaderCodec.decode(encoded.copyOf().also { it[5] = 2 })
        }
        assertFailsWith<IllegalArgumentException> {
            VaultHeaderCodec.decode(encoded.copyOf().also { it[6] = 2 })
        }
    }

    @Test
    fun `slot count and bounded KDF parameters are validated before use`() {
        val encoded = VaultHeaderCodec.encode(header())

        assertFailsWith<IllegalArgumentException> {
            VaultHeaderCodec.decode(encoded.copyOf().also { it[7] = 1 })
        }
        assertFailsWith<IllegalArgumentException> {
            VaultHeaderCodec.decode(
                encoded.copyOf().also {
                    val memoryOffset = 9 + CryptoConstants.SALT_LEN
                    it[memoryOffset] = 0x7f
                },
            )
        }
    }

    @Test
    fun `both slots must use identical bounded KDF work`() {
        val original = header()
        val mismatched = original.copy(
            slots = listOf(
                original.slots[0],
                original.slots[1].copy(kdfParams = KdfParams.FLOOR),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            VaultHeaderCodec.encode(mismatched)
        }
    }

    @Test
    fun `unused slot is rerandomized with real bounded KDF parameters`() {
        val first = header().slots[1]
        val second = header().slots[1]

        assertEquals(KdfParams.DEFAULT, first.kdfParams)
        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.wrappedDek.nonce.contentEquals(second.wrappedDek.nonce))
        assertFalse(first.wrappedDek.ciphertext.contentEquals(second.wrappedDek.ciphertext))
        assertFalse(first.commitment.contentEquals(second.commitment))
    }

    private fun header(): VaultHeader =
        VaultHeaderCodec.create(salt, KdfParams.DEFAULT, wrappedDek, commitment)
}

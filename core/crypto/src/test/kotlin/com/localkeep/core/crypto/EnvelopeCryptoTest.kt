package com.localkeep.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvelopeCryptoTest {
    @Test
    fun `wrap then unwrap returns DEK`() {
        val kek = ByteArray(CryptoConstants.KEY_LEN) { it.toByte() }
        val dek = EnvelopeCrypto.generateDek()

        val wrapped = EnvelopeCrypto.wrapDek(kek, dek)

        assertEquals(CryptoConstants.AEAD_NONCE_LEN, wrapped.nonce.size)
        assertEquals(
            CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
            wrapped.ciphertext.size,
        )
        assertContentEquals(dek, EnvelopeCrypto.unwrapDek(kek, wrapped))
    }

    @Test
    fun `wrong KEK and tampering fail authentication`() {
        val kek = ByteArray(CryptoConstants.KEY_LEN) { it.toByte() }
        val wrapped = EnvelopeCrypto.wrapDek(kek, EnvelopeCrypto.generateDek())

        assertFailsWith<AeadAuthException> {
            EnvelopeCrypto.unwrapDek(ByteArray(CryptoConstants.KEY_LEN) { 9 }, wrapped)
        }
        assertFailsWith<AeadAuthException> {
            EnvelopeCrypto.unwrapDek(
                kek,
                WrappedDek(
                    wrapped.nonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
                    wrapped.ciphertext,
                ),
            )
        }
        assertFailsWith<AeadAuthException> {
            EnvelopeCrypto.unwrapDek(
                kek,
                WrappedDek(
                    wrapped.nonce,
                    wrapped.ciphertext.copyOf().also {
                        it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                    },
                ),
            )
        }
    }

    @Test
    fun `malformed wrapped values fail as authentication errors`() {
        val kek = ByteArray(CryptoConstants.KEY_LEN)

        assertFailsWith<AeadAuthException> {
            EnvelopeCrypto.unwrapDek(kek, WrappedDek(ByteArray(11), ByteArray(48)))
        }
        assertFailsWith<AeadAuthException> {
            EnvelopeCrypto.unwrapDek(kek, WrappedDek(ByteArray(12), ByteArray(47)))
        }
    }

    @Test
    fun `commitment is stable DEK-specific and verifiable`() {
        val firstDek = ByteArray(CryptoConstants.KEY_LEN) { it.toByte() }
        val secondDek = firstDek.copyOf().also { it[0] = 99 }
        val commitment = EnvelopeCrypto.slotCommitment(firstDek)
        val commitKey = VaultSubkeys.commitKey(firstDek)

        assertContentEquals(commitment, EnvelopeCrypto.slotCommitment(firstDek))
        assertFalse(commitment.contentEquals(EnvelopeCrypto.slotCommitment(secondDek)))
        assertTrue(KeyCommitment.verify(commitKey, commitment))
    }

    @Test
    fun `wrapped value equality is structural`() {
        val first = WrappedDek(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val equal = WrappedDek(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val different = WrappedDek(byteArrayOf(1, 2), byteArrayOf(3, 5))

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertFalse(first == different)
    }

    @Test
    fun `DEK and KEK lengths are enforced`() {
        assertEquals(CryptoConstants.KEY_LEN, EnvelopeCrypto.generateDek().size)
        assertFailsWith<IllegalArgumentException> {
            EnvelopeCrypto.wrapDek(ByteArray(31), ByteArray(32))
        }
        assertFailsWith<IllegalArgumentException> {
            EnvelopeCrypto.wrapDek(ByteArray(32), ByteArray(31))
        }
        assertFailsWith<IllegalArgumentException> {
            EnvelopeCrypto.unwrapDek(ByteArray(31), WrappedDek(ByteArray(12), ByteArray(48)))
        }
    }

    @Test
    fun `variable-length secret wraps with authenticated context`() {
        val key = ByteArray(CryptoConstants.KEY_LEN) { it.toByte() }
        val secret = ByteArray(138) { (it * 3).toByte() }
        val aad = "localkeep.passkey.private-key.v1".encodeToByteArray()

        val wrapped = EnvelopeCrypto.wrapBytes(key, secret, aad)

        assertEquals(CryptoConstants.AEAD_NONCE_LEN, wrapped.nonce.size)
        assertEquals(secret.size + CryptoConstants.AEAD_TAG_LEN, wrapped.ciphertext.size)
        assertContentEquals(secret, EnvelopeCrypto.unwrapBytes(key, wrapped, aad))
        assertFailsWith<AeadAuthException> {
            EnvelopeCrypto.unwrapBytes(key, wrapped, "wrong-context".encodeToByteArray())
        }
        assertFailsWith<AeadAuthException> {
            EnvelopeCrypto.unwrapBytes(
                key,
                WrappedBytes(
                    wrapped.nonce,
                    wrapped.ciphertext.copyOf().also {
                        it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                    },
                ),
                aad,
            )
        }
    }

    @Test
    fun `variable-length wrapper rejects malformed values`() {
        val key = ByteArray(CryptoConstants.KEY_LEN)

        assertFailsWith<IllegalArgumentException> {
            EnvelopeCrypto.wrapBytes(ByteArray(31), byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            EnvelopeCrypto.wrapBytes(key, byteArrayOf())
        }
        assertFailsWith<AeadAuthException> {
            EnvelopeCrypto.unwrapBytes(key, WrappedBytes(ByteArray(11), ByteArray(17)))
        }
        assertFailsWith<AeadAuthException> {
            EnvelopeCrypto.unwrapBytes(key, WrappedBytes(ByteArray(12), ByteArray(16)))
        }
    }
}

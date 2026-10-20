package com.localkeep.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AeadKatTest {
    @Test
    fun `rfc8452 aes256 gcm siv empty vector`() {
        val result = AesGcmSivAead.seal(
            key = hex(
                "01000000000000000000000000000000" +
                    "00000000000000000000000000000000",
            ),
            nonce = hex("030000000000000000000000"),
            plaintext = byteArrayOf(),
            aad = byteArrayOf(),
        )

        assertEquals("07f5f4169bbf55a8400cd47ea6fd400f", result.toHex())
    }

    @Test
    fun `nist cavp aes256 gcm empty vector`() {
        val result = AesGcmAead.seal(
            key = hex(
                "b52c505a37d78eda5dd34f20c22540ea" +
                    "1b58963cf8e5bf8ffa85f9f2492505b4",
            ),
            nonce = hex("516c33929df5a3284ff463d7"),
            plaintext = byteArrayOf(),
            aad = byteArrayOf(),
        )

        assertEquals("bdc1ac884d332457a1d2664f168c76f0", result.toHex())
    }

    @Test
    fun `both AEADs round-trip with AAD`() {
        listOf(AesGcmSivAead, AesGcmAead).forEach { aead ->
            val key = ByteArray(CryptoConstants.KEY_LEN) { it.toByte() }
            val nonce = ByteArray(CryptoConstants.AEAD_NONCE_LEN) { (it + 1).toByte() }
            val plaintext = "top secret vault payload".toByteArray()
            val aad = "authenticated header".toByteArray()

            val ciphertext = aead.seal(key, nonce, plaintext, aad)

            assertContentEquals(plaintext, aead.open(key, nonce, ciphertext, aad))
        }
    }

    @Test
    fun `both AEADs reject modified ciphertext and wrong AAD`() {
        listOf(AesGcmSivAead, AesGcmAead).forEach { aead ->
            val key = ByteArray(CryptoConstants.KEY_LEN) { it.toByte() }
            val nonce = ByteArray(CryptoConstants.AEAD_NONCE_LEN) { (it + 1).toByte() }
            val aad = "aad".toByteArray()
            val ciphertext = aead.seal(key, nonce, byteArrayOf(7), aad)

            val tampered = ciphertext.copyOf().also {
                it[0] = (it[0].toInt() xor 1).toByte()
            }
            assertFailsWith<AeadAuthException> {
                aead.open(key, nonce, tampered, aad)
            }
            assertFailsWith<AeadAuthException> {
                aead.open(key, nonce, ciphertext, "wrong".toByteArray())
            }
        }
    }

    @Test
    fun `both AEADs reject malformed key nonce and ciphertext lengths`() {
        listOf(AesGcmSivAead, AesGcmAead).forEach { aead ->
            assertFailsWith<IllegalArgumentException> {
                aead.seal(ByteArray(31), ByteArray(12), byteArrayOf(), byteArrayOf())
            }
            assertFailsWith<IllegalArgumentException> {
                aead.seal(ByteArray(32), ByteArray(11), byteArrayOf(), byteArrayOf())
            }
            assertFailsWith<IllegalArgumentException> {
                aead.open(ByteArray(32), ByteArray(12), ByteArray(15), byteArrayOf())
            }
        }
    }
}

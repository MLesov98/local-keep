package com.localkeep.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HkdfKatTest {
    @Test
    fun `rfc5869 case 1`() {
        val output = Hkdf.derive(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )

        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            output.toHex(),
        )
    }

    @Test
    fun `purpose labels derive distinct stable keys`() {
        val dek = ByteArray(CryptoConstants.KEY_LEN) { it.toByte() }
        val encryptionKey = VaultSubkeys.encKey(dek)
        val commitmentKey = VaultSubkeys.commitKey(dek)
        val streamKey = VaultSubkeys.fileStreamKey(dek)

        assertContentEquals(Hkdf.derive(dek, CryptoConstants.LABEL_PAYLOAD_ENC), encryptionKey)
        assertFalse(encryptionKey.contentEquals(commitmentKey))
        assertFalse(encryptionKey.contentEquals(streamKey))
        assertFalse(commitmentKey.contentEquals(streamKey))
    }

    @Test
    fun `rejects invalid output and DEK lengths`() {
        assertFailsWith<IllegalArgumentException> { Hkdf.derive(byteArrayOf(1), byteArrayOf(), 0) }
        assertFailsWith<IllegalArgumentException> {
            Hkdf.derive(byteArrayOf(1), byteArrayOf(), 255 * 32 + 1)
        }
        assertFailsWith<IllegalArgumentException> { VaultSubkeys.encKey(ByteArray(31)) }
    }
}

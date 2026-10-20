package com.localkeep.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Argon2KatTest {
    @Test
    fun `rfc9106 argon2id vector`() {
        val output = Argon2.deriveBytes(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 },
            memoryKib = 32,
            iterations = 3,
            parallelism = 4,
        )

        assertEquals(
            "0d640df58d78766c08c037a34a8b53c9" +
                "d01ef0452d75b65eb52520e96b01e659",
            output.toHex(),
        )
    }

    @Test
    fun `public KDF returns a 256-bit key`() {
        val output = Argon2.deriveKey(
            password = charArrayOf('p', '\u0105', '\ud83d', '\udd10'),
            salt = ByteArray(CryptoConstants.SALT_LEN) { it.toByte() },
            params = KdfParams.FLOOR,
        )

        assertEquals(CryptoConstants.KEY_LEN, output.size)
    }

    @Test
    fun `public KDF rejects wrong salt and extreme costs`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2.deriveKey(charArrayOf('x'), ByteArray(15), KdfParams.FLOOR)
        }
        assertFailsWith<IllegalArgumentException> {
            Argon2.deriveKey(
                charArrayOf('x'),
                ByteArray(CryptoConstants.SALT_LEN),
                KdfParams(KdfParamsCodec.MAX_MEMORY_KIB + 1, 2, 1),
            )
        }
    }
}

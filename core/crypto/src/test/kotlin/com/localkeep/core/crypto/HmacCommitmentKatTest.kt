package com.localkeep.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HmacCommitmentKatTest {
    @Test
    fun `rfc4231 case 2 hmac sha256`() {
        val output = KeyCommitment.hmacSha256(
            key = "Jefe".toByteArray(Charsets.US_ASCII),
            message = "what do ya want for nothing?".toByteArray(Charsets.US_ASCII),
        )

        assertEquals(
            "5bdcc146bf60754e6a042426089575c7" +
                "5a003f089d2739839dec58b964ec3843",
            output.toHex(),
        )
    }

    @Test
    fun `commitment accepts exact value and rejects tampering`() {
        val key = ByteArray(CryptoConstants.KEY_LEN) { it.toByte() }
        val commitment = KeyCommitment.compute(key)

        assertTrue(KeyCommitment.verify(key, commitment))
        assertFalse(
            KeyCommitment.verify(
                key,
                commitment.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
            ),
        )
        assertFalse(KeyCommitment.verify(key, commitment.copyOf(commitment.size - 1)))
    }
}

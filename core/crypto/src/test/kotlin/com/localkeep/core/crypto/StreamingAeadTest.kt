package com.localkeep.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StreamingAeadTest {
    private val dek = ByteArray(CryptoConstants.KEY_LEN) { it.toByte() }

    @Test
    fun `round-trips empty boundary and multi-frame inputs`() {
        listOf(
            0,
            1,
            StreamingAead.CHUNK - 1,
            StreamingAead.CHUNK,
            StreamingAead.CHUNK + 1,
            StreamingAead.CHUNK * 3 + 123,
        ).forEach { size ->
            val plaintext = patternedBytes(size)
            assertContentEquals(plaintext, decrypt(encrypt(plaintext)), "size=$size")
        }
    }

    @Test
    fun `same plaintext encrypts differently`() {
        val plaintext = patternedBytes(1_000)
        assertFalse(encrypt(plaintext).contentEquals(encrypt(plaintext)))
    }

    @Test
    fun `wrong DEK and header tampering fail authentication`() {
        val ciphertext = encrypt(patternedBytes(100))
        assertFailsWith<AeadAuthException> {
            decrypt(ciphertext, ByteArray(CryptoConstants.KEY_LEN) { 7 })
        }

        listOf(0, CryptoConstants.SALT_LEN).forEach { offset ->
            val tampered = ciphertext.copyOf()
            tampered[offset] = (tampered[offset].toInt() xor 1).toByte()
            assertFailsWith<AeadAuthException> { decrypt(tampered) }
        }
    }

    @Test
    fun `middle-frame tamper deletion and reorder fail authentication`() {
        val ciphertext = encrypt(patternedBytes(StreamingAead.CHUNK * 3))
        val header = StreamingAead.HEADER_LENGTH
        val frame = StreamingAead.FRAME_LENGTH

        val tampered = ciphertext.copyOf().also {
            val offset = header + frame + 31
            it[offset] = (it[offset].toInt() xor 1).toByte()
        }
        assertFailsWith<AeadAuthException> { decrypt(tampered) }

        val deleted = ciphertext.copyOfRange(0, header + frame) +
            ciphertext.copyOfRange(header + frame * 2, ciphertext.size)
        assertFailsWith<AeadAuthException> { decrypt(deleted) }

        val reordered = ciphertext.copyOf().also {
            val first = it.copyOfRange(header, header + frame)
            val second = it.copyOfRange(header + frame, header + frame * 2)
            second.copyInto(it, header)
            first.copyInto(it, header + frame)
        }
        assertFailsWith<AeadAuthException> { decrypt(reordered) }
    }

    @Test
    fun `all truncation shapes fail as authentication errors`() {
        val ciphertext = encrypt(patternedBytes(StreamingAead.CHUNK * 3))
        val withoutFinalFrame = ciphertext.copyOf(ciphertext.size - StreamingAead.FRAME_LENGTH)

        listOf(
            byteArrayOf(),
            ciphertext.copyOf(CryptoConstants.SALT_LEN),
            ciphertext.copyOf(StreamingAead.HEADER_LENGTH),
            ciphertext.copyOf(StreamingAead.HEADER_LENGTH + CryptoConstants.AEAD_TAG_LEN - 1),
            ciphertext.copyOf(ciphertext.size - 1),
            withoutFinalFrame,
        ).forEach {
            assertFailsWith<AeadAuthException> { decrypt(it) }
        }
    }

    @Test
    fun `appended bytes fail final-frame authentication`() {
        val ciphertext = encrypt(patternedBytes(100))
        assertFailsWith<AeadAuthException> {
            decrypt(ciphertext + byteArrayOf(1))
        }
    }

    @Test
    fun `fragmented streams work without assuming full reads`() {
        val plaintext = patternedBytes(StreamingAead.CHUNK + 37)
        val encryptedOutput = ByteArrayOutputStream()
        StreamingAead.encrypt(dek, FragmentedInputStream(plaintext), encryptedOutput)
        val decryptedOutput = ByteArrayOutputStream()
        StreamingAead.decrypt(
            dek,
            FragmentedInputStream(encryptedOutput.toByteArray(), returnZeroOnce = true),
            decryptedOutput,
        )

        assertContentEquals(plaintext, decryptedOutput.toByteArray())
    }

    @Test
    fun `rejects malformed DEK lengths before processing`() {
        assertFailsWith<IllegalArgumentException> {
            StreamingAead.encrypt(ByteArray(31), ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
        }
        assertFailsWith<IllegalArgumentException> {
            StreamingAead.decrypt(ByteArray(31), ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray =
        ByteArrayOutputStream().also {
            StreamingAead.encrypt(dek, ByteArrayInputStream(plaintext), it)
        }.toByteArray()

    private fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray = dek,
    ): ByteArray = ByteArrayOutputStream().also {
        StreamingAead.decrypt(key, ByteArrayInputStream(ciphertext), it)
    }.toByteArray()

    private fun patternedBytes(size: Int): ByteArray =
        ByteArray(size) { ((it * 31) xor (it ushr 3)).toByte() }

    private class FragmentedInputStream(
        bytes: ByteArray,
        private var returnZeroOnce: Boolean = false,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (returnZeroOnce) {
                returnZeroOnce = false
                return 0
            }
            return delegate.read(buffer, offset, min(length, 7))
        }
    }
}

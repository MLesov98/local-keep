package com.localkeep.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The random-access reader has to agree with [StreamingAead.decrypt] byte for byte, at every offset,
 * or a video plays back subtly corrupted in a way no build or launch would reveal. So the shape of
 * these tests is: encrypt, then assert that *every* read the decoder could plausibly issue returns
 * exactly the slice sequential decryption would have produced.
 */
class StreamingAeadReaderTest {
    private val dek = ByteArray(CryptoConstants.KEY_LEN) { it.toByte() }

    @Test
    fun `reports the plaintext length at every frame boundary`() {
        sizes().forEach { size ->
            withReader(patternedBytes(size)) { reader ->
                assertEquals(size.toLong(), reader.plaintextLength, "size=$size")
            }
        }
    }

    @Test
    fun `a full sequential read reproduces the plaintext`() {
        sizes().forEach { size ->
            val plaintext = patternedBytes(size)
            withReader(plaintext) { reader ->
                val out = ByteArray(size)
                var filled = 0
                while (filled < size) {
                    val n = reader.readAt(filled.toLong(), out, filled, size - filled)
                    assertTrue(n > 0, "stalled at $filled of $size")
                    filled += n
                }
                assertContentEquals(plaintext, out, "size=$size")
            }
        }
    }

    @Test
    fun `random reads match the same slice of the plaintext`() {
        val size = StreamingAead.CHUNK * 3 + 977
        val plaintext = patternedBytes(size)
        // Offsets chosen to land on, just before, and just after every frame boundary — the places
        // where an off-by-one in the frame arithmetic would hide.
        val offsets = listOf(
            0L, 1L,
            StreamingAead.CHUNK - 1L, StreamingAead.CHUNK.toLong(), StreamingAead.CHUNK + 1L,
            StreamingAead.CHUNK * 2 - 1L, StreamingAead.CHUNK * 2L,
            StreamingAead.CHUNK * 3L, size - 1L,
        )
        val lengths = listOf(1, 2, 999, StreamingAead.CHUNK, StreamingAead.CHUNK + 5, size)

        withReader(plaintext) { reader ->
            for (offset in offsets) {
                for (length in lengths) {
                    val buffer = ByteArray(length)
                    val n = reader.readAt(offset, buffer, 0, length)
                    val expected = minOf(length.toLong(), size - offset).toInt()
                    assertEquals(expected, n, "offset=$offset length=$length")
                    assertContentEquals(
                        plaintext.copyOfRange(offset.toInt(), offset.toInt() + expected),
                        buffer.copyOf(expected),
                        "offset=$offset length=$length",
                    )
                }
            }
        }
    }

    @Test
    fun `honours the buffer offset rather than always writing from zero`() {
        val plaintext = patternedBytes(4_096)
        withReader(plaintext) { reader ->
            val buffer = ByteArray(600) { 0x5A }
            val n = reader.readAt(1_000L, buffer, 100, 400)
            assertEquals(400, n)
            assertContentEquals(plaintext.copyOfRange(1_000, 1_400), buffer.copyOfRange(100, 500))
            // Everything outside the requested window must be untouched.
            assertTrue(buffer.copyOf(100).all { it == 0x5A.toByte() })
            assertTrue(buffer.copyOfRange(500, 600).all { it == 0x5A.toByte() })
        }
    }

    @Test
    fun `signals end of stream with -1 and permits a zero-length read`() {
        val plaintext = patternedBytes(500)
        withReader(plaintext) { reader ->
            val buffer = ByteArray(16)
            assertEquals(-1, reader.readAt(500L, buffer, 0, 16))
            assertEquals(-1, reader.readAt(10_000L, buffer, 0, 16))
            // A zero-length request is not end-of-stream; reporting it as such makes a decoder
            // abandon a file it could have played.
            assertEquals(0, reader.readAt(0L, buffer, 0, 0))
        }
    }

    @Test
    fun `an empty blob has zero length and reads as end of stream`() {
        withReader(ByteArray(0)) { reader ->
            assertEquals(0L, reader.plaintextLength)
            assertEquals(-1, reader.readAt(0L, ByteArray(4), 0, 4))
        }
    }

    @Test
    fun `a tampered frame fails authentication when it is read`() {
        val plaintext = patternedBytes(StreamingAead.CHUNK * 3)
        val ciphertext = encrypt(plaintext)
        // Flip a bit inside the SECOND frame.
        val offset = StreamingAead.HEADER_LENGTH + StreamingAead.FRAME_LENGTH + 31
        ciphertext[offset] = (ciphertext[offset].toInt() xor 1).toByte()

        withReader(ciphertext = ciphertext) { reader ->
            // The first frame is untouched and still reads.
            assertEquals(16, reader.readAt(0L, ByteArray(16), 0, 16))
            assertFailsWith<AeadAuthException> {
                reader.readAt(StreamingAead.CHUNK.toLong(), ByteArray(16), 0, 16)
            }
        }
    }

    @Test
    fun `a wrong DEK fails on the first frame read`() {
        withReader(patternedBytes(1_000), key = ByteArray(CryptoConstants.KEY_LEN) { 7 }) { reader ->
            assertFailsWith<AeadAuthException> { reader.readAt(0L, ByteArray(16), 0, 16) }
        }
    }

    @Test
    fun `truncation is caught by the length arithmetic, not by a short read`() {
        val ciphertext = encrypt(patternedBytes(StreamingAead.CHUNK * 2))

        // A file too short to even hold a header.
        assertFailsWith<AeadAuthException> {
            withReader(ciphertext = ciphertext.copyOf(CryptoConstants.SALT_LEN)) {}
        }
        // A header with no frame behind it.
        assertFailsWith<AeadAuthException> {
            withReader(ciphertext = ciphertext.copyOf(StreamingAead.HEADER_LENGTH)) {}
        }
        // A final frame too short to hold its own tag.
        assertFailsWith<AeadAuthException> {
            withReader(
                ciphertext = ciphertext.copyOf(
                    StreamingAead.HEADER_LENGTH + StreamingAead.FRAME_LENGTH +
                        CryptoConstants.AEAD_TAG_LEN - 1,
                ),
            ) {}
        }

        // A whole frame lopped off the end: the length is now consistent, so nothing about the file
        // size gives it away — the *finality flag* in the remaining last frame's nonce does. It was
        // written as non-final and is now being read as final, so it fails to authenticate.
        withReader(
            ciphertext = ciphertext.copyOf(ciphertext.size - StreamingAead.FRAME_LENGTH),
        ) { reader ->
            assertFailsWith<AeadAuthException> { reader.readAt(0L, ByteArray(16), 0, 16) }
        }
    }

    @Test
    fun `rejects a malformed DEK and out-of-range reads`() {
        assertFailsWith<IllegalArgumentException> {
            withReader(patternedBytes(10), key = ByteArray(31)) {}
        }
        withReader(patternedBytes(100)) { reader ->
            val buffer = ByteArray(10)
            assertFailsWith<IndexOutOfBoundsException> { reader.readAt(0L, buffer, 0, 11) }
            assertFailsWith<IndexOutOfBoundsException> { reader.readAt(0L, buffer, 5, 6) }
            assertFailsWith<IndexOutOfBoundsException> { reader.readAt(0L, buffer, -1, 4) }
            assertFailsWith<IndexOutOfBoundsException> { reader.readAt(-1L, buffer, 0, 4) }
        }
    }

    @Test
    fun `closes the source when construction fails`() {
        val file = tempBlob(ByteArray(0))
        val source = RandomAccessFile(file, "r")
        try {
            assertFailsWith<AeadAuthException> { StreamingAeadReader(dek, source) }
            assertFailsWith<IOException> { source.length() }
        } finally {
            source.close()
            file.delete()
        }
    }

    @Test
    fun `reading after close is refused rather than serving stale plaintext`() {
        val file = tempBlob(encrypt(patternedBytes(1_000)))
        val reader = StreamingAeadReader(dek, RandomAccessFile(file, "r"))
        reader.readAt(0L, ByteArray(16), 0, 16)
        reader.close()
        reader.close() // idempotent
        assertFailsWith<IllegalStateException> { reader.readAt(0L, ByteArray(16), 0, 16) }
        file.delete()
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun sizes() = listOf(
        0,
        1,
        StreamingAead.CHUNK - 1,
        StreamingAead.CHUNK,
        StreamingAead.CHUNK + 1,
        StreamingAead.CHUNK * 2,
        StreamingAead.CHUNK * 3 + 123,
    )

    private fun encrypt(plaintext: ByteArray): ByteArray =
        ByteArrayOutputStream().also {
            StreamingAead.encrypt(dek, ByteArrayInputStream(plaintext), it)
        }.toByteArray()

    private fun tempBlob(ciphertext: ByteArray): File =
        File.createTempFile("lk-reader", ".enc").also { it.writeBytes(ciphertext) }

    private fun withReader(
        plaintext: ByteArray? = null,
        ciphertext: ByteArray? = null,
        key: ByteArray = dek,
        block: (StreamingAeadReader) -> Unit,
    ) {
        val bytes = ciphertext ?: encrypt(plaintext!!)
        val file = tempBlob(bytes)
        try {
            StreamingAeadReader(key, RandomAccessFile(file, "r")).use(block)
        } finally {
            file.delete()
        }
    }

    private fun patternedBytes(size: Int): ByteArray =
        ByteArray(size) { ((it * 31) xor (it ushr 3)).toByte() }
}

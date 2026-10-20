package com.localkeep.core.crypto

import java.io.Closeable
import java.io.EOFException
import java.io.RandomAccessFile

/**
 * Random-access plaintext reads over a [StreamingAead] blob, decrypting one frame at a time.
 *
 * This exists so a media decoder can play an encrypted file. `MediaPlayer` seeks — it reads the
 * container header, then the index, then jumps to the first sample, and again on every scrub. The
 * two obvious ways to serve that both break a rule this app has committed to:
 *
 *  - decrypt to a temp file and hand over the path → plaintext on disk, which is exactly what
 *    `NoPlaintextToDiskTest` exists to prevent;
 *  - decrypt the whole blob into a `ByteArray` → a 500MB video is 500MB of heap.
 *
 * A frame in this format is a fixed [StreamingAead.CHUNK] of plaintext, so frame `n` starts at a
 * computable file offset and can be opened on its own. Peak memory here is two frames — 128 KiB —
 * regardless of file size, and no plaintext ever reaches storage.
 *
 * **What is still authenticated, and what is not.** Every frame this reader returns has had its GCM
 * tag verified against a nonce binding its index and finality, so a modified, reordered, or
 * substituted frame fails on read. Truncation is caught too: the total length is derived from the
 * file's size and the final frame is the only one allowed to be short. What sequential
 * [StreamingAead.decrypt] gives that this cannot is *completeness* — a caller that seeks past a
 * tampered frame never reads it, and so never learns it was tampered with. That is inherent to
 * random access, and it is why this is an addition to [StreamingAead.decrypt] rather than a
 * replacement for it: anything that consumes a whole blob should still use the sequential path.
 *
 * Not thread-safe on its own — [readAt] is `@Synchronized` because a `MediaDataSource` is called
 * from the platform's own decoder threads, and the underlying [RandomAccessFile] has a single shared
 * cursor.
 *
 * Closing wipes the derived key and the cached frame. The caller keeps ownership of `dek`.
 */
class StreamingAeadReader(
    dek: ByteArray,
    private val source: RandomAccessFile,
) : Closeable {

    private val key: ByteArray
    private val noncePrefix = ByteArray(StreamingAead.NONCE_PREFIX_LENGTH)

    /** Index of the final frame — the only one permitted to hold fewer than [StreamingAead.CHUNK] bytes. */
    private val finalFrameIndex: Long

    /** Plaintext bytes in the final frame; `1..CHUNK` for a non-empty blob, `0` for an empty one. */
    private val finalFrameLength: Int

    /** Total decrypted size, which is what a `MediaDataSource` must report as `getSize()`. */
    val plaintextLength: Long

    private var cachedIndex = -1L
    private var cachedFrame: ByteArray? = null
    private var closed = false

    init {
        try {
            require(dek.size == CryptoConstants.KEY_LEN) { "DEK must be 256 bits" }

            val salt = ByteArray(CryptoConstants.SALT_LEN)
            try {
                source.seek(0)
                source.readFully(salt)
                source.readFully(noncePrefix)
            } catch (e: EOFException) {
                throw AeadAuthException("truncated streaming header", e)
            }
            // How many frames, and how long the last one is, follow from the file's size alone: the
            // format has no length field, and adding one would be a value an attacker could edit.
            val ciphertextLength = source.length() - StreamingAead.HEADER_LENGTH
            if (ciphertextLength < CryptoConstants.AEAD_TAG_LEN) {
                throw AeadAuthException("missing encrypted frame")
            }
            val wholeFrames = ciphertextLength / StreamingAead.FRAME_LENGTH
            val remainder = (ciphertextLength % StreamingAead.FRAME_LENGTH).toInt()
            if (remainder == 0) {
                // An exact multiple: the last frame is itself full-length.
                finalFrameIndex = wholeFrames - 1
                finalFrameLength = StreamingAead.CHUNK
            } else {
                if (remainder < CryptoConstants.AEAD_TAG_LEN) {
                    throw AeadAuthException("truncated encrypted frame")
                }
                finalFrameIndex = wholeFrames
                finalFrameLength = remainder - CryptoConstants.AEAD_TAG_LEN
            }
            if (finalFrameIndex > StreamingAead.MAX_FRAME_INDEX) {
                throw AeadAuthException("too many streaming frames")
            }
            plaintextLength = finalFrameIndex * StreamingAead.CHUNK + finalFrameLength

            key = Hkdf.derive(ikm = dek, info = CryptoConstants.LABEL_FILE_STREAM, salt = salt)
        } catch (t: Throwable) {
            runCatching { source.close() }
            throw t
        }
    }

    /**
     * Fills [buffer] from plaintext offset [position], spanning frames as needed.
     *
     * Returns the number of bytes read, or `-1` at or past the end — the exact contract
     * `MediaDataSource.readAt` requires, so the platform wrapper can delegate here verbatim.
     */
    @Synchronized
    @Throws(AeadAuthException::class)
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        check(!closed) { "reader is closed" }
        if (offset < 0 || size < 0 || offset > buffer.size - size) {
            throw IndexOutOfBoundsException("offset=$offset size=$size buffer=${buffer.size}")
        }
        if (position < 0) throw IndexOutOfBoundsException("position=$position")
        // MediaDataSource treats -1 as end-of-stream; a 0-byte request is a legal no-op, and must
        // not be reported as EOF or the decoder gives up on a perfectly good file.
        if (position >= plaintextLength) return -1
        if (size == 0) return 0

        val wanted = minOf(size.toLong(), plaintextLength - position).toInt()
        var copied = 0
        while (copied < wanted) {
            val at = position + copied
            val frameIndex = at / StreamingAead.CHUNK
            val withinFrame = (at % StreamingAead.CHUNK).toInt()
            val frame = frame(frameIndex)
            val take = minOf(wanted - copied, frame.size - withinFrame)
            // A short non-final frame would mean the file lied about its own length; refuse rather
            // than return a silently truncated read the decoder would treat as valid data.
            if (take <= 0) throw AeadAuthException("short frame $frameIndex")
            frame.copyInto(buffer, offset + copied, withinFrame, withinFrame + take)
            copied += take
        }
        return copied
    }

    /** Decrypts frame [index], reusing the cached one when a read spans or repeats it. */
    private fun frame(index: Long): ByteArray {
        cachedFrame?.let { if (cachedIndex == index) return it }

        val isFinal = index == finalFrameIndex
        val ciphertextLength =
            if (isFinal) finalFrameLength + CryptoConstants.AEAD_TAG_LEN else StreamingAead.FRAME_LENGTH
        val ciphertext = ByteArray(ciphertextLength)
        try {
            source.seek(StreamingAead.HEADER_LENGTH + index * StreamingAead.FRAME_LENGTH)
            source.readFully(ciphertext)
        } catch (e: EOFException) {
            throw AeadAuthException("truncated encrypted frame $index", e)
        }

        val nonce = StreamingAead.nonce(noncePrefix, index, isFinal)
        val plaintext = try {
            AesGcmAead.open(key, nonce, ciphertext, nonce)
        } finally {
            Zeroizer.wipe(ciphertext)
        }

        Zeroizer.wipe(cachedFrame)
        cachedFrame = plaintext
        cachedIndex = index
        return plaintext
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        Zeroizer.wipe(key)
        Zeroizer.wipe(cachedFrame)
        Zeroizer.wipe(noncePrefix)
        cachedFrame = null
        cachedIndex = -1L
        source.close()
    }
}

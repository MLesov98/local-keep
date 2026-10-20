package com.localkeep.core.crypto

import java.io.InputStream
import java.io.OutputStream

/**
 * Tink-style AES-256-GCM-HKDF streaming for file blobs.
 *
 * Layout: 16-byte salt || 7-byte nonce prefix || encrypted frames. Each frame nonce and AAD are
 * prefix || unsigned 32-bit frame index || final-frame flag. This binds position and finality, so
 * frames cannot be changed, removed, appended, or reordered without authentication failure.
 *
 * Because every frame but the last is exactly [FRAME_LENGTH] bytes, the layout is also seekable:
 * plaintext offset `p` lives in frame `p / CHUNK` at file offset `HEADER_LENGTH + frame * FRAME_LENGTH`.
 * [StreamingAeadReader] uses that to decrypt one frame at a time on demand — which is what makes
 * playing an encrypted video possible without either buffering it whole or staging it on disk.
 */
object StreamingAead {
    internal const val NONCE_PREFIX_LENGTH = 7
    internal const val MAX_FRAME_INDEX = 0xffff_ffffL

    const val CHUNK: Int = 64 * 1024
    internal const val HEADER_LENGTH: Int = CryptoConstants.SALT_LEN + NONCE_PREFIX_LENGTH
    internal const val FRAME_LENGTH: Int = CHUNK + CryptoConstants.AEAD_TAG_LEN

    fun encrypt(dek: ByteArray, input: InputStream, output: OutputStream) {
        requireDek(dek)
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val noncePrefix = SecureRandomProvider.nextBytes(NONCE_PREFIX_LENGTH)
        val key = Hkdf.derive(
            ikm = dek,
            info = CryptoConstants.LABEL_FILE_STREAM,
            salt = salt,
        )
        var current = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        try {
            output.write(salt)
            output.write(noncePrefix)
            var index = 0L
            var currentLength = readChunk(input, current)
            if (currentLength == 0) {
                writeEncryptedFrame(output, key, noncePrefix, index, final = true, current, 0)
                return
            }

            while (true) {
                val nextLength = readChunk(input, next)
                val final = nextLength == 0
                writeEncryptedFrame(
                    output,
                    key,
                    noncePrefix,
                    index,
                    final,
                    current,
                    currentLength,
                )
                Zeroizer.wipe(current)
                if (final) {
                    break
                }
                val swap = current
                current = next
                next = swap
                currentLength = nextLength
                index = nextIndex(index)
            }
        } finally {
            Zeroizer.wipe(key)
            Zeroizer.wipe(current)
            Zeroizer.wipe(next)
        }
    }

    @Throws(AeadAuthException::class)
    fun decrypt(dek: ByteArray, input: InputStream, output: OutputStream) {
        requireDek(dek)
        val salt = ByteArray(CryptoConstants.SALT_LEN)
        val noncePrefix = ByteArray(NONCE_PREFIX_LENGTH)
        if (
            readChunk(input, salt) != salt.size ||
            readChunk(input, noncePrefix) != noncePrefix.size
        ) {
            throw AeadAuthException("truncated streaming header")
        }

        val key = Hkdf.derive(
            ikm = dek,
            info = CryptoConstants.LABEL_FILE_STREAM,
            salt = salt,
        )
        var current = ByteArray(FRAME_LENGTH)
        var next = ByteArray(FRAME_LENGTH)
        try {
            var index = 0L
            var currentLength = readChunk(input, current)
            if (currentLength == 0) {
                throw AeadAuthException("missing encrypted frame")
            }

            while (true) {
                val nextLength = readChunk(input, next)
                val final = nextLength == 0
                if (currentLength < CryptoConstants.AEAD_TAG_LEN) {
                    throw AeadAuthException("truncated encrypted frame")
                }
                val plaintext = openFrame(
                    key,
                    noncePrefix,
                    index,
                    final,
                    current,
                    currentLength,
                )
                try {
                    output.write(plaintext)
                } finally {
                    Zeroizer.wipe(plaintext)
                }
                if (final) {
                    break
                }
                val swap = current
                current = next
                next = swap
                currentLength = nextLength
                index = nextIndex(index)
            }
        } finally {
            Zeroizer.wipe(key)
            Zeroizer.wipe(current)
            Zeroizer.wipe(next)
        }
    }

    private fun writeEncryptedFrame(
        output: OutputStream,
        key: ByteArray,
        noncePrefix: ByteArray,
        index: Long,
        final: Boolean,
        buffer: ByteArray,
        length: Int,
    ) {
        val plaintext = buffer.copyOf(length)
        val nonce = nonce(noncePrefix, index, final)
        try {
            output.write(AesGcmAead.seal(key, nonce, plaintext, nonce))
        } finally {
            Zeroizer.wipe(plaintext)
        }
    }

    internal fun openFrame(
        key: ByteArray,
        noncePrefix: ByteArray,
        index: Long,
        final: Boolean,
        buffer: ByteArray,
        length: Int,
    ): ByteArray {
        val ciphertext = buffer.copyOf(length)
        val nonce = nonce(noncePrefix, index, final)
        return try {
            AesGcmAead.open(key, nonce, ciphertext, nonce)
        } finally {
            Zeroizer.wipe(ciphertext)
        }
    }

    internal fun nonce(prefix: ByteArray, index: Long, final: Boolean): ByteArray {
        require(index in 0..MAX_FRAME_INDEX) { "too many streaming frames" }
        return ByteArray(CryptoConstants.AEAD_NONCE_LEN).also {
            prefix.copyInto(it)
            it[7] = (index ushr 24).toByte()
            it[8] = (index ushr 16).toByte()
            it[9] = (index ushr 8).toByte()
            it[10] = index.toByte()
            it[11] = if (final) 1 else 0
        }
    }

    private fun nextIndex(index: Long): Long {
        require(index < MAX_FRAME_INDEX) { "too many streaming frames" }
        return index + 1
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            when {
                count < 0 -> break
                count > 0 -> offset += count
                else -> {
                    val byte = input.read()
                    if (byte < 0) {
                        break
                    }
                    buffer[offset++] = byte.toByte()
                }
            }
        }
        return offset
    }

    private fun requireDek(dek: ByteArray) {
        require(dek.size == CryptoConstants.KEY_LEN) { "DEK must be 256 bits" }
    }

}

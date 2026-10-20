package com.localkeep.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/** HKDF-SHA256 extract-and-expand (RFC 5869). */
object Hkdf {
    fun derive(
        ikm: ByteArray,
        info: String,
        length: Int = CryptoConstants.KEY_LEN,
        salt: ByteArray? = null,
    ): ByteArray = derive(ikm, info.toByteArray(Charsets.US_ASCII), length, salt)

    fun derive(
        ikm: ByteArray,
        info: ByteArray,
        length: Int = CryptoConstants.KEY_LEN,
        salt: ByteArray? = null,
    ): ByteArray {
        require(length in 1..MAX_OUTPUT_LENGTH) { "invalid HKDF output length" }
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also {
            generator.generateBytes(it, 0, it.size)
        }
    }

    private const val MAX_OUTPUT_LENGTH = 255 * 32
}

/** Purpose-separated vault subkeys; the DEK itself is never used as an AEAD or MAC key. */
object VaultSubkeys {
    fun encKey(dek: ByteArray): ByteArray {
        requireKey(dek)
        return Hkdf.derive(dek, CryptoConstants.LABEL_PAYLOAD_ENC)
    }

    fun commitKey(dek: ByteArray): ByteArray {
        requireKey(dek)
        return Hkdf.derive(dek, CryptoConstants.LABEL_COMMIT)
    }

    fun fileStreamKey(dek: ByteArray): ByteArray {
        requireKey(dek)
        return Hkdf.derive(dek, CryptoConstants.LABEL_FILE_STREAM)
    }

    private fun requireKey(key: ByteArray) {
        require(key.size == CryptoConstants.KEY_LEN) { "DEK must be 256 bits" }
    }
}

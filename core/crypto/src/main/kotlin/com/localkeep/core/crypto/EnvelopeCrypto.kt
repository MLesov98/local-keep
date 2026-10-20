package com.localkeep.core.crypto

class WrappedDek(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is WrappedDek &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int =
        31 * nonce.contentHashCode() + ciphertext.contentHashCode()
}

class WrappedBytes(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is WrappedBytes &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int =
        31 * nonce.contentHashCode() + ciphertext.contentHashCode()
}

/** Generates one vault DEK and wraps it under independently derived or device-bound KEKs. */
object EnvelopeCrypto {
    fun generateDek(): ByteArray = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)

    fun deriveKek(
        password: CharArray,
        salt: ByteArray,
        params: KdfParams,
    ): ByteArray = Argon2.deriveKey(password, salt, params)

    fun wrapDek(kek: ByteArray, dek: ByteArray): WrappedDek {
        require(kek.size == CryptoConstants.KEY_LEN) { "KEK must be 256 bits" }
        require(dek.size == CryptoConstants.KEY_LEN) { "DEK must be 256 bits" }
        val nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN)
        return WrappedDek(
            nonce = nonce,
            ciphertext = AesGcmAead.seal(kek, nonce, dek, byteArrayOf()),
        )
    }

    @Throws(AeadAuthException::class)
    fun unwrapDek(kek: ByteArray, wrapped: WrappedDek): ByteArray {
        require(kek.size == CryptoConstants.KEY_LEN) { "KEK must be 256 bits" }
        if (
            wrapped.nonce.size != CryptoConstants.AEAD_NONCE_LEN ||
            wrapped.ciphertext.size != WRAPPED_DEK_LENGTH
        ) {
            throw AeadAuthException("invalid wrapped DEK")
        }
        return AesGcmAead.open(kek, wrapped.nonce, wrapped.ciphertext, byteArrayOf())
    }

    /**
     * Wraps a non-empty variable-length secret under a 256-bit key.
     *
     * This deliberately stays separate from [wrapDek], whose fixed-size contract guards the
     * vault header format.
     */
    fun wrapBytes(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = byteArrayOf(),
    ): WrappedBytes {
        require(key.size == CryptoConstants.KEY_LEN) { "wrapping key must be 256 bits" }
        require(plaintext.isNotEmpty()) { "wrapped plaintext must not be empty" }
        val nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN)
        return WrappedBytes(
            nonce = nonce,
            ciphertext = AesGcmAead.seal(key, nonce, plaintext, aad),
        )
    }

    @Throws(AeadAuthException::class)
    fun unwrapBytes(
        key: ByteArray,
        wrapped: WrappedBytes,
        aad: ByteArray = byteArrayOf(),
    ): ByteArray {
        require(key.size == CryptoConstants.KEY_LEN) { "wrapping key must be 256 bits" }
        if (
            wrapped.nonce.size != CryptoConstants.AEAD_NONCE_LEN ||
            wrapped.ciphertext.size <= CryptoConstants.AEAD_TAG_LEN
        ) {
            throw AeadAuthException("invalid wrapped bytes")
        }
        return AesGcmAead.open(key, wrapped.nonce, wrapped.ciphertext, aad)
    }

    fun slotCommitment(dek: ByteArray): ByteArray {
        val commitmentKey = VaultSubkeys.commitKey(dek)
        return try {
            KeyCommitment.compute(commitmentKey)
        } finally {
            Zeroizer.wipe(commitmentKey)
        }
    }

    private const val WRAPPED_DEK_LENGTH =
        CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN
}

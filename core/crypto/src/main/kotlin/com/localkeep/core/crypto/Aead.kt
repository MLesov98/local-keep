package com.localkeep.core.crypto

class AeadAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Authenticated encryption. Output is ciphertext followed by its 16-byte tag. */
interface Aead {
    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    @Throws(AeadAuthException::class)
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray
}

internal fun validateAeadInputs(key: ByteArray, nonce: ByteArray, inputSize: Int, decrypting: Boolean) {
    require(key.size == CryptoConstants.KEY_LEN) { "AEAD key must be 256 bits" }
    require(nonce.size == CryptoConstants.AEAD_NONCE_LEN) { "AEAD nonce must be 96 bits" }
    if (decrypting) {
        require(inputSize >= CryptoConstants.AEAD_TAG_LEN) { "ciphertext is shorter than tag" }
    }
}

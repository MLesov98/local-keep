package com.localkeep.core.crypto

/** Persisted algorithm identifiers and frozen HKDF purpose labels. */
object CryptoConstants {
    const val FORMAT_VERSION_1: Int = 1
    const val KDF_ARGON2ID: Int = 1
    const val AEAD_AES256_GCM_SIV: Int = 1
    const val AEAD_AES256_GCM: Int = 2

    const val LABEL_PAYLOAD_ENC: String = "localkeep/v1/payload-enc"
    const val LABEL_COMMIT: String = "localkeep/v1/commit"
    const val LABEL_FILE_STREAM: String = "localkeep/v1/file-stream"

    const val AEAD_NONCE_LEN: Int = 12
    const val AEAD_TAG_BITS: Int = 128
    const val AEAD_TAG_LEN: Int = AEAD_TAG_BITS / Byte.SIZE_BITS
    const val KEY_LEN: Int = 32
    const val SALT_LEN: Int = 16
}

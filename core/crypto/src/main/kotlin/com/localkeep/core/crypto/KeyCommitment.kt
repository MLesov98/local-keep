package com.localkeep.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Per-slot HMAC-SHA256 commitment over the fixed ASCII message "commit". */
object KeyCommitment {
    private val commitMessage = "commit".toByteArray(Charsets.US_ASCII)

    fun compute(commitKey: ByteArray): ByteArray {
        require(commitKey.size == CryptoConstants.KEY_LEN) { "commit key must be 256 bits" }
        return hmacSha256(commitKey, commitMessage)
    }

    fun verify(commitKey: ByteArray, expected: ByteArray): Boolean {
        val actual = compute(commitKey)
        return try {
            ConstantTime.equals(actual, expected)
        } finally {
            Zeroizer.wipe(actual)
        }
    }

    internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }
}

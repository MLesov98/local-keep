package com.localkeep.core.crypto

import java.security.SecureRandom

object SecureRandomProvider {
    fun newInstance(): SecureRandom = SecureRandom()

    fun nextBytes(length: Int): ByteArray {
        require(length >= 0) { "length must be non-negative" }
        return ByteArray(length).also(newInstance()::nextBytes)
    }
}

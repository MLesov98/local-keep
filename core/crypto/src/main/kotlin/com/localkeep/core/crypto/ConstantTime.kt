package com.localkeep.core.crypto

import java.security.MessageDigest

object ConstantTime {
    fun equals(first: ByteArray, second: ByteArray): Boolean =
        MessageDigest.isEqual(first, second)
}

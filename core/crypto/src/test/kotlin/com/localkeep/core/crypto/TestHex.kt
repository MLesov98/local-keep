package com.localkeep.core.crypto

internal fun hex(value: String): ByteArray {
    val clean = value.filterNot(Char::isWhitespace)
    require(clean.length % 2 == 0) { "hex must contain complete bytes" }
    return ByteArray(clean.length / 2) { index ->
        val high = clean[index * 2].digitToInt(16)
        val low = clean[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

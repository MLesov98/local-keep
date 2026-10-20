package com.localkeep.core.crypto

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

object Passwords {
    /** Encodes without constructing an immutable String. Caller must wipe the returned bytes. */
    fun toUtf8(chars: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        return try {
            ByteArray(encoded.remaining()).also(encoded::get)
        } finally {
            if (encoded.hasArray()) {
                encoded.array().fill(0)
            }
        }
    }
}

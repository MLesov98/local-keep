package com.localkeep.core.crypto

/** Takes ownership of [secret] and wipes it when closed. */
class SecretBytes(private val secret: ByteArray) : AutoCloseable {
    @Volatile
    private var closed = false

    val bytes: ByteArray
        get() {
            check(!closed) { "secret is closed" }
            return secret
        }

    val size: Int
        get() = secret.size

    @Synchronized
    override fun close() {
        if (!closed) {
            Zeroizer.wipe(secret)
            closed = true
        }
    }
}

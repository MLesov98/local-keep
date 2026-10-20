package com.localkeep.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id v1.3 with a fixed 32-byte output. */
object Argon2 {
    fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        require(salt.size == CryptoConstants.SALT_LEN) { "Argon2 salt must be 128 bits" }
        requireSupported(params)
        val passwordBytes = Passwords.toUtf8(password)
        return try {
            deriveBytes(
                password = passwordBytes,
                salt = salt,
                memoryKib = params.memoryKib,
                iterations = params.iterations,
                parallelism = params.parallelism,
            )
        } finally {
            Zeroizer.wipe(passwordBytes)
        }
    }

    internal fun deriveBytes(
        password: ByteArray,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
        secret: ByteArray? = null,
        associatedData: ByteArray? = null,
    ): ByteArray {
        require(memoryKib > 0 && iterations > 0 && parallelism > 0) {
            "Argon2 parameters must be positive"
        }
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
        secret?.let(builder::withSecret)
        associatedData?.let(builder::withAdditional)

        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        return ByteArray(CryptoConstants.KEY_LEN).also {
            generator.generateBytes(password, it, 0, it.size)
        }
    }

    private fun requireSupported(params: KdfParams) {
        require(params.memoryKib <= KdfParamsCodec.MAX_MEMORY_KIB) { "kdf memory out of range" }
        require(params.iterations <= KdfParamsCodec.MAX_ITERATIONS) { "kdf iterations out of range" }
        require(params.parallelism <= KdfParamsCodec.MAX_PARALLELISM) {
            "kdf parallelism out of range"
        }
    }
}

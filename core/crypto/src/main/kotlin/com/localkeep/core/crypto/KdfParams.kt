package com.localkeep.core.crypto

/** Argon2id cost parameters persisted per unlock slot. */
data class KdfParams(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(memoryKib >= FLOOR_MEMORY_KIB) { "memory below Argon2id floor" }
        require(iterations >= FLOOR_ITERATIONS) { "iterations below Argon2id floor" }
        require(parallelism >= FLOOR_PARALLELISM) { "parallelism below Argon2id floor" }
    }

    companion object {
        private const val FLOOR_MEMORY_KIB = 19_456
        private const val FLOOR_ITERATIONS = 2
        private const val FLOOR_PARALLELISM = 1

        /**
         * The most memory a vault this app creates may ever ask for.
         *
         * Argon2id's cost parameter **is** a heap allocation — BouncyCastle holds the whole cost as
         * a `Block[]` — and the parameters are written into the vault header at creation and reused
         * wherever that vault goes. Android caps an ordinary app at `dalvik.vm.heapgrowthlimit`:
         * 192 MiB on the API 35 image, **48 MiB on API 28**, which is this app's own `minSdk`. So a
         * vault created on a modern phone at 64 MiB could not be opened on a device the app claims
         * to support — correct password, right vault, no way in, and the Recovery Kit no help
         * because it derives through the same KDF. That is H-1, and it is data-loss-shaped.
         *
         * The ceiling is the fix, and it is a prevention rather than a remedy: the remedy needs the
         * vault open, which is exactly what is impossible. Capping here costs every user a factor of
         * two in KDF memory and buys the invariant that **any vault this app creates opens on any
         * device this app supports**. An app that can create vaults its own `minSdk` cannot open is
         * incoherent before it is insecure.
         *
         * 32 MiB × t=3 remains comfortably above OWASP's Argon2id guidance (m=19 MiB, t=2, p=1) —
         * this is a smaller number, not a weak one.
         */
        const val PORTABLE_MAX_MEMORY_KIB = 32_768

        /**
         * What a new vault gets when the device can afford it — the portability ceiling above.
         *
         * Was 64 MiB until 2026-07-29. See [PORTABLE_MAX_MEMORY_KIB] for why it moved and what the
         * trade is.
         */
        val DEFAULT = KdfParams(
            memoryKib = PORTABLE_MAX_MEMORY_KIB,
            iterations = 3,
            parallelism = 4,
        )
        val FLOOR = KdfParams(
            memoryKib = FLOOR_MEMORY_KIB,
            iterations = FLOOR_ITERATIONS,
            parallelism = FLOOR_PARALLELISM,
        )
    }
}

/** Fixed, bounded, 12-byte big-endian codec used by the plaintext vault header. */
object KdfParamsCodec {
    const val ENCODED_SIZE: Int = 12
    const val MAX_MEMORY_KIB: Int = 1_048_576
    const val MAX_ITERATIONS: Int = 20
    const val MAX_PARALLELISM: Int = 16

    fun encode(params: KdfParams): ByteArray {
        validate(params.memoryKib, params.iterations, params.parallelism)
        return ByteArray(ENCODED_SIZE).also {
            writeIntBE(it, 0, params.memoryKib)
            writeIntBE(it, 4, params.iterations)
            writeIntBE(it, 8, params.parallelism)
        }
    }

    fun decode(bytes: ByteArray, offset: Int = 0): KdfParams {
        require(offset >= 0 && offset <= bytes.size - ENCODED_SIZE) { "kdf params truncated" }
        val memoryKib = readIntBE(bytes, offset)
        val iterations = readIntBE(bytes, offset + 4)
        val parallelism = readIntBE(bytes, offset + 8)
        validate(memoryKib, iterations, parallelism)
        return KdfParams(memoryKib, iterations, parallelism)
    }

    private fun validate(memoryKib: Int, iterations: Int, parallelism: Int) {
        require(memoryKib in KdfParams.FLOOR.memoryKib..MAX_MEMORY_KIB) {
            "kdf memory out of range"
        }
        require(iterations in KdfParams.FLOOR.iterations..MAX_ITERATIONS) {
            "kdf iterations out of range"
        }
        require(parallelism in KdfParams.FLOOR.parallelism..MAX_PARALLELISM) {
            "kdf parallelism out of range"
        }
    }

    private fun writeIntBE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}

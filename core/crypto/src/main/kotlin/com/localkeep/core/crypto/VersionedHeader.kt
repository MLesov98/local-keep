package com.localkeep.core.crypto

/** Seven-byte algorithm-agility prefix embedded at the start of each vault header. */
data class VersionedHeader(
    val formatVersion: Int,
    val kdfAlgId: Int,
    val aeadAlgId: Int,
) {
    init {
        require(formatVersion in UBYTE_RANGE) { "format version must fit one byte" }
        require(kdfAlgId in UBYTE_RANGE) { "KDF algorithm ID must fit one byte" }
        require(aeadAlgId in UBYTE_RANGE) { "AEAD algorithm ID must fit one byte" }
    }

    fun encode(): ByteArray = byteArrayOf(
        magic[0],
        magic[1],
        magic[2],
        magic[3],
        formatVersion.toByte(),
        kdfAlgId.toByte(),
        aeadAlgId.toByte(),
    )

    companion object {
        const val ENCODED_SIZE: Int = 7
        private val magic = "VLT1".toByteArray(Charsets.US_ASCII)
        private val UBYTE_RANGE = 0..0xff

        val MAGIC: ByteArray
            get() = magic.copyOf()

        fun decode(bytes: ByteArray): VersionedHeader {
            require(bytes.size >= ENCODED_SIZE) { "header too short" }
            require(
                bytes[0] == magic[0] &&
                    bytes[1] == magic[1] &&
                    bytes[2] == magic[2] &&
                    bytes[3] == magic[3],
            ) { "bad magic" }
            return VersionedHeader(
                formatVersion = bytes[4].toInt() and 0xff,
                kdfAlgId = bytes[5].toInt() and 0xff,
                aeadAlgId = bytes[6].toInt() and 0xff,
            )
        }
    }
}

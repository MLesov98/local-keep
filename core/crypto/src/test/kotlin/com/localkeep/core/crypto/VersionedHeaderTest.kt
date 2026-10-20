package com.localkeep.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VersionedHeaderTest {
    @Test
    fun `encodes frozen version and algorithm bytes`() {
        val header = VersionedHeader(
            formatVersion = CryptoConstants.FORMAT_VERSION_1,
            kdfAlgId = CryptoConstants.KDF_ARGON2ID,
            aeadAlgId = CryptoConstants.AEAD_AES256_GCM_SIV,
        )

        assertContentEquals(
            byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte(), 1, 1, 1),
            header.encode(),
        )
        assertEquals(VersionedHeader.ENCODED_SIZE, header.encode().size)
        assertEquals(header, VersionedHeader.decode(header.encode() + byteArrayOf(99)))
    }

    @Test
    fun `decodes unsigned future IDs without interpreting them`() {
        val decoded = VersionedHeader.decode(
            VersionedHeader.MAGIC + byteArrayOf(0xff.toByte(), 0x80.toByte(), 0xfe.toByte()),
        )

        assertEquals(255, decoded.formatVersion)
        assertEquals(128, decoded.kdfAlgId)
        assertEquals(254, decoded.aeadAlgId)
    }

    @Test
    fun `rejects truncated bad magic and values wider than one byte`() {
        assertFailsWith<IllegalArgumentException> {
            VersionedHeader.decode(ByteArray(VersionedHeader.ENCODED_SIZE - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            VersionedHeader.decode("NOPE\u0001\u0001\u0001".toByteArray(Charsets.US_ASCII))
        }
        assertFailsWith<IllegalArgumentException> { VersionedHeader(-1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { VersionedHeader(1, 256, 1) }
        assertFailsWith<IllegalArgumentException> { VersionedHeader(1, 1, 300) }
    }

    @Test
    fun `magic accessor cannot mutate codec state`() {
        val exposed = VersionedHeader.MAGIC
        exposed[0] = 0

        assertFalse(exposed.contentEquals(VersionedHeader.MAGIC))
        assertEquals(
            VersionedHeader(1, 1, 1),
            VersionedHeader.decode(VersionedHeader(1, 1, 1).encode()),
        )
    }
}

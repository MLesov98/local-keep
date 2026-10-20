package com.localkeep.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KdfParamsCodecTest {
    @Test
    fun `round-trips fixed big-endian representation`() {
        val params = KdfParams(memoryKib = 65_536, iterations = 3, parallelism = 4)

        val encoded = KdfParamsCodec.encode(params)

        assertContentEquals(
            byteArrayOf(0, 1, 0, 0, 0, 0, 0, 3, 0, 0, 0, 4),
            encoded,
        )
        assertEquals(params, KdfParamsCodec.decode(encoded))
        assertEquals(KdfParamsCodec.ENCODED_SIZE, encoded.size)
    }

    @Test
    fun `decodes from a bounded offset`() {
        val bytes = byteArrayOf(99) + KdfParamsCodec.encode(KdfParams.FLOOR) + byteArrayOf(88)
        assertEquals(KdfParams.FLOOR, KdfParamsCodec.decode(bytes, offset = 1))
    }

    @Test
    fun `rejects truncated or invalid offsets`() {
        assertFailsWith<IllegalArgumentException> {
            KdfParamsCodec.decode(ByteArray(KdfParamsCodec.ENCODED_SIZE - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            KdfParamsCodec.decode(ByteArray(KdfParamsCodec.ENCODED_SIZE), offset = -1)
        }
    }

    @Test
    fun `rejects values outside floor and ceilings`() {
        val belowFloor = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 1)
        val aboveCeiling = byteArrayOf(0x40, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 4)

        assertFailsWith<IllegalArgumentException> { KdfParamsCodec.decode(belowFloor) }
        assertFailsWith<IllegalArgumentException> { KdfParamsCodec.decode(aboveCeiling) }
        assertFailsWith<IllegalArgumentException> {
            KdfParamsCodec.encode(KdfParams(KdfParamsCodec.MAX_MEMORY_KIB + 1, 2, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            KdfParamsCodec.encode(KdfParams(19_456, KdfParamsCodec.MAX_ITERATIONS + 1, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            KdfParamsCodec.encode(KdfParams(19_456, 2, KdfParamsCodec.MAX_PARALLELISM + 1))
        }
    }
}

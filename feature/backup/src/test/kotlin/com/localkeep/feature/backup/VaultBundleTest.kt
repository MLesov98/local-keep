package com.localkeep.feature.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultBundleTest {

    private companion object {
        // Real refs are `UUID.toString()`, and the reader refuses any other shape since 2026-10-02.
        const val A_REF = "0000aaaa-0000-4000-8000-000000001111"
        const val B_REF = "0000bbbb-0000-4000-8000-000000002222"
        const val EMPTY_REF = "00000000-0000-4000-8000-0000000e0000"
        const val X_REF = "00000000-0000-4000-8000-00000000000a"
        const val Y_REF = "00000000-0000-4000-8000-00000000000b"
    }

    private fun blob(ref: String, bytes: ByteArray) =
        VaultBundle.BlobSource(ref, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    private fun roundTrip(
        vaultBytes: ByteArray,
        blobs: List<Pair<String, ByteArray>>,
    ): Pair<ByteArray, Map<String, ByteArray>> {
        val out = ByteArrayOutputStream()
        VaultBundle.write(out, vaultBytes, blobs.map { (ref, bytes) -> blob(ref, bytes) })
        val reader = VaultBundle.open(ByteArrayInputStream(out.toByteArray()))
        val read = linkedMapOf<String, ByteArray>()
        reader.forEachBlob { ref, stream, _ -> read[ref] = stream.readBytes() }
        return reader.vaultBytes to read
    }

    @Test
    fun `a bundle round-trips the vault and every blob`() {
        val vault = ByteArray(3_000) { (it % 251).toByte() }
        val blobs = listOf(
            A_REF to ByteArray(70_000) { (it % 97).toByte() },
            B_REF to byteArrayOf(1, 2, 3),
        )

        val (readVault, readBlobs) = roundTrip(vault, blobs)

        assertContentEquals(vault, readVault)
        assertEquals(listOf(A_REF, B_REF), readBlobs.keys.toList())
        blobs.forEach { (ref, bytes) -> assertContentEquals(bytes, readBlobs.getValue(ref)) }
    }

    @Test
    fun `a vault with no media still round-trips`() {
        val vault = ByteArray(64) { 7 }

        val (readVault, readBlobs) = roundTrip(vault, emptyList())

        assertContentEquals(vault, readVault)
        assertTrue(readBlobs.isEmpty())
    }

    @Test
    fun `an empty blob is carried rather than dropped`() {
        val (_, readBlobs) = roundTrip(ByteArray(8), listOf(EMPTY_REF to ByteArray(0)))

        assertEquals(setOf(EMPTY_REF), readBlobs.keys)
        assertEquals(0, readBlobs.getValue(EMPTY_REF).size)
    }

    // The magic is what lets a pre-bundle export keep working: anything not starting with it is
    // read as a bare vault file.
    @Test
    fun `a bare vault file is not mistaken for a bundle`() {
        assertFalse(VaultBundle.isBundle(ByteArray(64) { 0 }))
        assertFalse(VaultBundle.isBundle(byteArrayOf(1, 2)))

        val out = ByteArrayOutputStream()
        VaultBundle.write(out, ByteArray(4), emptyList())
        assertTrue(VaultBundle.isBundle(out.toByteArray()))
    }

    @Test
    fun `a truncated bundle fails instead of returning a short blob`() {
        val out = ByteArrayOutputStream()
        VaultBundle.write(out, ByteArray(16), listOf(blob(X_REF, ByteArray(5_000) { 3 })))
        val truncated = out.toByteArray().copyOf(out.size() - 2_000)

        val reader = VaultBundle.open(ByteArrayInputStream(truncated))
        assertFailsWith<Exception> {
            reader.forEachBlob { _, stream, _ -> stream.readBytes() }
        }
    }

    // A sink that stops early would leave the reader mid-blob, and every later entry would decode
    // as garbage. The reader has no way to seek, so it has to refuse rather than carry on.
    @Test
    fun `a blob left partly read is rejected`() {
        val out = ByteArrayOutputStream()
        VaultBundle.write(
            out,
            ByteArray(16),
            listOf(blob(X_REF, ByteArray(4_000) { 1 }), blob(Y_REF, ByteArray(10) { 2 })),
        )

        val reader = VaultBundle.open(ByteArrayInputStream(out.toByteArray()))
        assertFailsWith<IllegalStateException> {
            reader.forEachBlob { _, stream, _ -> stream.read() }
        }
    }
}

package com.localkeep.feature.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `VaultBundle.open` meets an attacker-chosen file before any password or crypto check.
 *
 * It is the only pre-auth untrusted parser in the import/backup surface with no Jazzer target
 * (V12, 2026-09-12). A `.vault` file is picked from anywhere through SAF, exactly like a KDBX, and
 * `open` reads a length field out of it and allocates from that number — so the failure modes that
 * matter are the ones that escape the caller's `catch`, not the ones that land in it.
 *
 * This is a bounded robustness test, not fuzzing. Adding a real `@FuzzTest` would mean moving this
 * module's test suite onto the JUnit platform (Jazzer is a JUnit 5 extension), which is how
 * `VaultHeaderCodecFuzzTest` ended up living inside `:core:vault`; doing that here would put a
 * working Robolectric suite through a runner change for a low-severity coverage gap. Tracked as a
 * follow-up instead. What this does cover is the contract: every documented guard fires, and
 * nothing outside the caller's declared catch set escapes.
 *
 * The allowed set matches what `VaultImporter` actually catches — `Exception` plus an explicit
 * `OutOfMemoryError` backstop at `VaultImporter.kt:76`, which exists precisely because a declared
 * length can be larger than the heap. Anything else reaching the caller is a real defect.
 */
class VaultBundleHostileInputTest {

    private fun header(vaultLength: Int, magic: ByteArray = VaultBundle.MAGIC, version: Int = VaultBundle.VERSION): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write(magic)
            writeByte(version)
            writeInt(vaultLength)
        }
        return out.toByteArray()
    }

    private fun open(bytes: ByteArray) = VaultBundle.open(ByteArrayInputStream(bytes))

    @Test
    fun `a declared vault length past the ceiling is refused before allocating`() {
        val tooBig = header(Int.MAX_VALUE)
        try {
            open(tooBig)
            fail("a vault length of Int.MAX_VALUE must be refused, not allocated")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("implausible vault length"))
        }
    }

    @Test
    fun `a negative vault length is refused rather than reaching ByteArray`() {
        try {
            open(header(-1))
            fail("a negative vault length must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("implausible vault length"))
        }
        // The point of the guard: without it this is a NegativeArraySizeException, which is not an
        // IllegalArgumentException and would not have been what the caller was told to expect.
    }

    @Test
    fun `a wrong magic and a wrong version are both refused`() {
        try {
            open(header(0, magic = "NOTABUND".toByteArray()))
            fail("a non-bundle file must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("not a vault bundle"))
        }
        try {
            open(header(0, version = 99))
            fail("an unknown bundle version must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("unsupported bundle version"))
        }
    }

    @Test
    fun `truncated and mutated inputs never escape the caller's catch`() {
        val valid = ByteArrayOutputStream()
            .also { VaultBundle.write(it, ByteArray(64) { i -> i.toByte() }, emptyList()) }
            .toByteArray()

        val random = Random(20260912)
        val cases = buildList {
            // every truncation of a valid bundle
            for (n in 0..valid.size) add(valid.copyOfRange(0, n))
            // single-byte mutations, with the 4-byte length field clamped so a mutated length
            // cannot ask this JVM for hundreds of megabytes and turn a contract test into a
            // memory test
            repeat(400) {
                val copy = valid.copyOf()
                val at = random.nextInt(copy.size)
                copy[at] = random.nextInt(256).toByte()
                if (at in VaultBundle.MAGIC.size + 1..VaultBundle.MAGIC.size + 4) {
                    copy[VaultBundle.MAGIC.size + 1] = 0
                    copy[VaultBundle.MAGIC.size + 2] = 0
                }
                add(copy)
            }
            repeat(200) { add(random.nextBytes(random.nextInt(0, 128))) }
        }

        cases.forEach { bytes ->
            try {
                val reader = open(bytes)
                reader.forEachBlob { _, stream, _ -> stream.readBytes() }
            } catch (expected: IllegalArgumentException) {
                // a `require` firing — the documented rejection
            } catch (expected: IllegalStateException) {
                // a `check` firing — a blob left unconsumed
            } catch (expected: IOException) {
                // truncation: readFully on a short stream
            } catch (expected: OutOfMemoryError) {
                // the declared-length backstop VaultImporter catches explicitly
            } catch (t: Throwable) {
                fail(
                    "VaultBundle.open threw ${t::class.java.name} (${t.message}) for a " +
                        "${bytes.size}-byte input — outside everything the caller catches",
                )
            }
        }
    }
}

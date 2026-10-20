package com.localkeep.core.health

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Regression test for the first finding the fuzz harness produced (2026-07-28,
 * `:test:fuzz` / `BreachFilterFuzzTest.load`).
 *
 * A **78-byte** input passed every `require` in [BreachFilter.load] and then asked for a 320 MB
 * `ByteArray`. The bounds check was doing its job as written — `bodyLen` really was inside
 * `0..MAX_SANE_BODY_LEN` — but a bound cannot tell a truthful length from a lie, and the array was
 * allocated before `readFully` got to find out the stream held 18 more bytes.
 *
 * `:test:fuzz` already carries the exact reproducer as a committed seed, so the campaign replays it
 * on every run. This test exists next to the code because that seed is 78 opaque bytes: a future
 * reader who breaks this needs to see *what* the input claims, not a hash.
 *
 * Read the KDoc on the second test before assuming all four of these guard the fix. They do not,
 * and which ones do is the useful thing to know.
 */
class BreachFilterTruncationTest {

    /** A header that is valid up to the point where it lies about how much data follows. */
    private fun header(
        checkpointCount: Int,
        declaredBodyLen: Int,
        actualBody: ByteArray,
    ): ByteArray = ByteArrayOutputStream().use { out ->
        DataOutputStream(out).use { dos ->
            dos.writeInt(BREACH_FILTER_MAGIC)
            dos.writeInt(BREACH_FILTER_FORMAT_VERSION)
            dos.writeLong(1024L) // universeSize
            dos.writeLong(1L) // entryCount
            dos.writeInt(BreachFilter.DEFAULT_RICE_K)
            dos.writeInt(0) // vintageLen
            dos.writeInt(BreachFilter.DEFAULT_BLOCK_SIZE)
            dos.writeInt(checkpointCount)
            repeat(checkpointCount) { dos.writeLong(0L) } // values
            repeat(checkpointCount) { dos.writeLong(0L) } // bit offsets
            dos.writeInt(declaredBodyLen)
            dos.write(actualBody)
        }
        out.toByteArray()
    }

    @Test
    fun `the fuzzer's original 320 MB claim is now outside the bound`() {
        // The reproducer as found, spelled out: 320 MB claimed, 18 bytes present. 62 bytes rather
        // than the original's 78 only because that one carried a 16-byte vintage string.
        val bytes = header(checkpointCount = 0, declaredBodyLen = 320 * 1024 * 1024, actualBody = ByteArray(18))
        assertEquals(62, bytes.size, "if this changed, the header format did")

        assertFailsWith<IllegalArgumentException> {
            BreachFilter(minimumCredibleEntries = 0) { null }.load(ByteArrayInputStream(bytes))
        }
    }

    /**
     * **This test passes with or without the incremental read, and that is worth stating rather
     * than discovering.** It was written expecting to guard `readExactly`; it does not.
     *
     * The distinguishing observable is heap exhaustion, and this task runs with the build's default
     * 4 GB — where the old `ByteArray(bodyLen)` succeeds and `readFully` then throws the same
     * `EOFException` the fixed code throws. Nothing here can tell the two apart.
     *
     * So the honest accounting of the two-part fix is:
     *
     *  - **Tightening MAX_SANE_* is the part with teeth**, and the test above fails without it.
     *  - **The incremental read is defence in depth.** After tightening, the worst over-allocation
     *    the bounds still permit is ~80 MB (64 MB body + two 8 MB checkpoint arrays), which survives
     *    a phone-sized heap. No test in this repo can currently distinguish it, because there is no
     *    input that both passes the bounds and exhausts a heap. It stays because the next person to
     *    raise a bound should not also have to rediscover why allocating from a claim is wrong.
     *
     * What this test does pin is the *behaviour*: a truncated stream is an `EOFException`, not a
     * silent short read and not a corrupted filter.
     */
    @Test
    fun `a body length inside the bound but not in the stream fails as EOF`() {
        // 64 MB is exactly the largest value still accepted, with 18 bytes behind it.
        val bytes = header(checkpointCount = 0, declaredBodyLen = 64 * 1024 * 1024, actualBody = ByteArray(18))

        assertFailsWith<EOFException> {
            BreachFilter(minimumCredibleEntries = 0) { null }.load(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `a checkpoint count the stream cannot supply fails as EOF, not as an allocation`() {
        // The same shape one field earlier: two LongArrays are allocated from `checkpointCount`.
        // 1,000,000 is the tightened MAX_SANE_CHECKPOINT_COUNT, i.e. the largest value still
        // accepted — so this is the worst case the bound permits, with no data behind it.
        val bytes = ByteArrayOutputStream().use { out ->
            DataOutputStream(out).use { dos ->
                dos.writeInt(BREACH_FILTER_MAGIC)
                dos.writeInt(BREACH_FILTER_FORMAT_VERSION)
                dos.writeLong(1024L)
                dos.writeLong(1L)
                dos.writeInt(BreachFilter.DEFAULT_RICE_K)
                dos.writeInt(0)
                dos.writeInt(BreachFilter.DEFAULT_BLOCK_SIZE)
                dos.writeInt(1_000_000)
            }
            out.toByteArray()
        }

        assertFailsWith<EOFException> {
            BreachFilter(minimumCredibleEntries = 0) { null }.load(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `ensureLoaded still degrades to no data rather than propagating`() {
        // The class contract the Security screen is built on: a malformed asset is "no data", a
        // third state distinct from "not breached". Fixing the allocation must not change this.
        val bytes = header(checkpointCount = 0, declaredBodyLen = 320 * 1024 * 1024, actualBody = ByteArray(18))
        val filter = BreachFilter(minimumCredibleEntries = 0) { ByteArrayInputStream(bytes) }
        filter.ensureLoaded()
        assertFalse(filter.hasData, "a truncated asset must leave hasData false, never crash")
        assertEquals("not loaded", filter.filterVintage())
    }

    @Test
    fun `a valid asset still round-trips after the incremental read`() {
        val asset = BreachFilterAssetBuilder.build(
            passwords = listOf("password", "123456", "qwerty", "letmein"),
            vintage = "truncation regression",
        )
        val filter = BreachFilter(minimumCredibleEntries = 0) { ByteArrayInputStream(asset) }
        filter.ensureLoaded()
        assertEquals("truncation regression", filter.filterVintage())
    }
}

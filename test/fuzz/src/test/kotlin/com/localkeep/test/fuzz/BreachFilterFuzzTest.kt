package com.localkeep.test.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import com.localkeep.core.health.BreachFilter
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * `BreachFilter.load` is ranked last in the fuzzing plan for an honest reason: **its input is a
 * bundled asset, not a file the user picks.** Nobody hands this parser attacker-controlled bytes
 * today.
 *
 * It is fuzzed anyway because it is the one parser in the app whose bounds checks were clearly
 * authored for a desktop, and a bound that has never been tested is a comment:
 *
 * ```
 * MAX_SANE_CHECKPOINT_COUNT = 50_000_000     // BreachFilter.kt:18  ->  two LongArrays, ~800 MB
 * MAX_SANE_BODY_LEN         = 512 * 1024 * 1024  // BreachFilter.kt:19
 * ```
 *
 * Every one of those `require` calls passes on a header a few dozen bytes long, and the allocation
 * happens before anything checks whether the stream actually holds that much data. On a phone, that
 * is an OOM rather than a rejection.
 *
 * This module's test JVM runs with `-Xmx256m` (build.gradle.kts) — closer to a phone than to CI's
 * 4 GB — which is what makes the difference between a rejection and an exhaustion observable at all.
 *
 * `load` is the right entry point rather than `ensureLoaded`: `ensureLoaded` catches `Throwable` by
 * design so the feature degrades to "no data" instead of crashing, and that catch would swallow
 * exactly the failure this target exists to find. [ensureLoadedNeverThrows] covers the contract of
 * the catching wrapper separately.
 */
class BreachFilterFuzzTest {

    @FuzzTest(maxDuration = "10m")
    fun load(bytes: ByteArray) {
        val filter = BreachFilter(minimumCredibleEntries = 0) { null }
        try {
            filter.load(ByteArrayInputStream(bytes))
        } catch (expected: IllegalArgumentException) {
            // A `require` rejecting a corrupt header. This is the parser working.
        } catch (expected: IOException) {
            // Truncated stream — DataInputStream.readFully on a short input. Also working.
        } catch (t: OutOfMemoryError) {
            throw AssertionError(
                "BreachFilter.load exhausted a 256 MB heap on ${bytes.size} bytes of input.\n\n" +
                    "The header's length fields are validated against MAX_SANE_* bounds and then " +
                    "allocated from directly, so a few dozen bytes can ask for hundreds of " +
                    "megabytes before readFully discovers the stream is empty. Bound the " +
                    "allocation by what the stream can actually supply, not only by a constant.",
                t,
            )
        } catch (t: Throwable) {
            throw AssertionError(
                "BreachFilter.load threw ${t::class.java.name}, which is neither a rejection " +
                    "(IllegalArgumentException) nor a truncated stream (IOException): ${t.message}",
                t,
            )
        }
    }

    /**
     * The published contract, in the class KDoc: `ensureLoaded` NEVER throws — a null source, an I/O
     * failure and a malformed asset all leave `hasData` false instead of crashing the caller. Worth
     * a target of its own because the guarantee is what the whole Security screen is built on, and
     * because it is stated in prose that nothing checks.
     */
    @FuzzTest(maxDuration = "10m")
    fun ensureLoadedNeverThrows(bytes: ByteArray) {
        val filter = BreachFilter(minimumCredibleEntries = 0) { ByteArrayInputStream(bytes) }
        try {
            filter.ensureLoaded()
        } catch (t: Throwable) {
            throw AssertionError("BreachFilter.ensureLoaded propagated ${t::class.java.name}", t)
        }
        // A filter that failed to load must report no data — never a silent "miss", which the class
        // doc calls out as a distinct third state that must not be collapsed into "not breached".
        if (!filter.hasData) {
            check(filter.filterVintage() == "not loaded") {
                "hasData is false but the vintage is not 'not loaded' — the two disagree about " +
                    "whether a corpus is present."
            }
        }
    }
}

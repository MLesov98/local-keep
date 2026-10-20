package com.localkeep.core.health

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A load that fails for a *transient* reason must not latch "no breach data" for the life of the
 * process.
 *
 * Observed on a foss debug build (emulator, Pixel_8_API_35, 2026-09-01): running the import wizard
 * and then opening Password health showed "The breach list could not be read on this device, so
 * nothing here can tell you whether a password has already leaked." It stayed after 45 seconds and
 * after navigating away to Vault and back — every later scan in that process reported no data.
 * `am force-stop` plus a fresh unlock, same vault, same asset, reported "Checked against 9.9M
 * known-breached passwords". Identical bytes, opposite verdicts, so the failure was not in the
 * asset.
 *
 * `ensureLoaded()` set `attempted = true` before trying and never cleared it, so the first failure
 * — and `HealthDashboardViewModel.refresh()` runs once per vault commit, several times in a row
 * during an import — decided the answer for every scan that followed. The screen then stated it as
 * a fact about the device.
 *
 * Retrying has to stay selective. `isPotentiallyBreached()` calls `ensureLoaded()` per lookup, so a
 * blanket retry would re-decode the ~14.5 MB asset once per password.
 */
class BreachFilterRetryTest {

    private val corpus = listOf("password123", "123456", "qwerty", "letmein", "iloveyou")

    /** Counts opens so a test can assert the asset was *not* re-read. */
    private class CountingSource(private val body: () -> InputStream?) : BreachFilterSource {
        var opens = 0
            private set

        override fun open(): InputStream? {
            opens++
            return body()
        }
    }

    private fun goodBytes() = BreachFilterAssetBuilder.build(corpus, vintage = "test-fixture")

    @Test
    fun `a transient failure does not latch - a later retry loads the corpus`() {
        var failNext = true
        val source = CountingSource {
            if (failNext) {
                failNext = false
                // The realistic transient here: the corpus decode is the biggest single allocation
                // the app makes, and it lost a race with the import running in the same process.
                throw OutOfMemoryError("simulated pressure during import")
            }
            ByteArrayInputStream(goodBytes())
        }
        val filter = BreachFilter(minimumCredibleEntries = 0, source = source)

        filter.ensureLoaded()
        assertFalse(filter.hasData, "the first attempt is expected to fail")

        filter.retryLoadIfTransientlyFailed()
        filter.ensureLoaded()

        assertTrue(filter.hasData, "a transient failure must not decide the answer for the process")
        assertTrue(filter.isPotentiallyBreached("password123"))
        assertEquals(2, source.opens)
    }

    @Test
    fun `an IO failure is transient too`() {
        var failNext = true
        val source = CountingSource {
            if (failNext) {
                failNext = false
                throw IOException("asset read failed")
            }
            ByteArrayInputStream(goodBytes())
        }
        val filter = BreachFilter(minimumCredibleEntries = 0, source = source)

        filter.ensureLoaded()
        assertFalse(filter.hasData)

        filter.retryLoadIfTransientlyFailed()
        filter.ensureLoaded()

        assertTrue(filter.hasData)
    }

    @Test
    fun `a plain ensureLoaded never re-reads the asset after a failure`() {
        // This is what keeps a per-lookup ensureLoaded() cheap: without it, a failed load would
        // re-decode the whole corpus once per password checked.
        val source = CountingSource { throw IOException("asset read failed") }
        val filter = BreachFilter(minimumCredibleEntries = 0, source = source)

        repeat(5) { filter.ensureLoaded() }

        assertFalse(filter.hasData)
        assertEquals(1, source.opens)
    }

    @Test
    fun `isPotentiallyBreached never re-reads the asset after a failure`() {
        val source = CountingSource { throw IOException("asset read failed") }
        val filter = BreachFilter(minimumCredibleEntries = 0, source = source)

        filter.ensureLoaded()
        repeat(20) { filter.isPotentiallyBreached("password123") }

        assertEquals(1, source.opens)
    }

    @Test
    fun `a corrupt asset is terminal - retrying it is pointless and does not happen`() {
        // Re-reading the same corrupt bytes cannot produce a different verdict, so this must latch.
        val source = CountingSource { ByteArrayInputStream(ByteArray(64) { 0x7F }) }
        val filter = BreachFilter(minimumCredibleEntries = 0, source = source)

        filter.ensureLoaded()
        assertFalse(filter.hasData)

        repeat(3) {
            filter.retryLoadIfTransientlyFailed()
            filter.ensureLoaded()
        }

        assertFalse(filter.hasData)
        assertEquals(1, source.opens)
    }

    @Test
    fun `a truncated asset is terminal`() {
        val truncated = goodBytes().copyOfRange(0, 24)
        val source = CountingSource { ByteArrayInputStream(truncated) }
        val filter = BreachFilter(minimumCredibleEntries = 0, source = source)

        filter.ensureLoaded()
        filter.retryLoadIfTransientlyFailed()
        filter.ensureLoaded()

        assertFalse(filter.hasData)
        assertEquals(1, source.opens)
    }

    @Test
    fun `a build that ships no asset is terminal`() {
        val source = CountingSource { null }
        val filter = BreachFilter(minimumCredibleEntries = 0, source = source)

        filter.ensureLoaded()
        filter.retryLoadIfTransientlyFailed()
        filter.ensureLoaded()

        assertFalse(filter.hasData)
        assertEquals(1, source.opens)
    }

    @Test
    fun `a placeholder corpus is terminal`() {
        // The placeholder parses perfectly — it is rejected on entry count, not on a throw — so the
        // terminal decision has to be made on that path too, or the app re-decodes it forever.
        val source = CountingSource { ByteArrayInputStream(goodBytes()) }
        val filter = BreachFilter(minimumCredibleEntries = 100_000, source = source)

        filter.ensureLoaded()
        assertFalse(filter.hasData, "5 entries is far below the credible floor")

        filter.retryLoadIfTransientlyFailed()
        filter.ensureLoaded()

        assertFalse(filter.hasData)
        assertEquals(1, source.opens)
    }

    @Test
    fun `a successful load is not re-read by a retry`() {
        val source = CountingSource { ByteArrayInputStream(goodBytes()) }
        val filter = BreachFilter(minimumCredibleEntries = 0, source = source)

        filter.ensureLoaded()
        assertTrue(filter.hasData)

        filter.retryLoadIfTransientlyFailed()
        filter.ensureLoaded()

        assertEquals(1, source.opens)
    }
}

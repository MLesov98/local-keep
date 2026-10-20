package com.localkeep.core.health

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BreachFilterTest {

    // A tiny, self-built asset — no need for the real ~15-20 MB corpus in a unit test.
    private val corpus = listOf("password123", "123456", "qwerty", "letmein", "iloveyou")

    private fun filterLoadedFrom(assetBytes: ByteArray): BreachFilter {
        val filter = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { ByteArrayInputStream(assetBytes) })
        filter.ensureLoaded()
        return filter
    }

    @Test fun `known-breached password hits`() {
        val filter = filterLoadedFrom(BreachFilterAssetBuilder.build(corpus, vintage = "test-fixture"))

        corpus.forEach { known ->
            assertTrue(filter.isPotentiallyBreached(known), "expected '$known' to hit")
        }
    }

    @Test fun `unrelated random strong password misses`() {
        val filter = filterLoadedFrom(BreachFilterAssetBuilder.build(corpus, vintage = "test-fixture"))

        // Verified offline (hand-computed bucket, does not collide with the 5-entry corpus above).
        val strongRandom = "Gk4#nP9\$wQ2@rT7&vB3!mL6^xF1*yH8"
        assertFalse(filter.isPotentiallyBreached(strongRandom))
    }

    @Test fun `bucketIndex is deterministic and within the universe`() {
        val universeSize = 5000L shl 10
        val a = breachBucketIndex("hello", universeSize)
        val b = breachBucketIndex("hello", universeSize)
        assertEquals(a, b)
        assertTrue(a in 0 until universeSize)
    }

    @Test fun `filterVintage reflects the loaded asset`() {
        val filter = filterLoadedFrom(BreachFilterAssetBuilder.build(corpus, vintage = "test-fixture-v42"))
        assertEquals("test-fixture-v42", filter.filterVintage())
    }

    @Test fun `null source (foss-shaped) has no data and never throws`() {
        val filter = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null })

        assertFalse(filter.hasData)
        assertFalse(filter.isPotentiallyBreached("password123")) // never throws, even for a known-breached string
        assertEquals("not loaded", filter.filterVintage())
    }

    @Test fun `source that throws IOException is treated as no data, not a crash`() {
        val filter = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { throw java.io.IOException("asset missing") })

        assertFalse(filter.hasData)
        assertFalse(filter.isPotentiallyBreached("password123"))
    }

    @Test fun `real BreachFilterSource over a classpath resource loads correctly`() {
        // Exercises the actual play-flavor-shaped seam (source.open() -> InputStream -> load()),
        // not just load() called directly — backed by the test-only placeholder committed to
        // core/health/src/test/resources/breached-passwords.filter (Task 4 Step 3). Never the
        // real ~15-20 MB corpus, and this resource is never packaged into any shipped build.
        val filter = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { javaClass.getResourceAsStream("/breached-passwords.filter") })
        filter.ensureLoaded() // BreachFilter loads lazily — hasData is only meaningful after this

        assertTrue(filter.hasData)
        assertTrue(filter.isPotentiallyBreached("password"), "expected the placeholder's well-known entry to hit")
    }
}

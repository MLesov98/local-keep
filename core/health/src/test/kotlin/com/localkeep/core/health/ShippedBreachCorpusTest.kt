package com.localkeep.core.health

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The corpus that actually ships must actually answer.
 *
 * `checkBreachCorpusNotPlaceholder` in the root build file checks the asset's **size**, which stops
 * the 190-byte stub from reaching a release and nothing else. A file can clear a size floor and
 * still be the wrong thing: truncated, built from the wrong list, built with mismatched Rice
 * parameters. Every one of those produces a Security screen that reports "not found in the breach
 * set" for every password a user owns — the exact false all-clear the placeholder gate exists to
 * prevent, arriving by a different road.
 *
 * So this loads the shipped filter and asks it questions with known answers.
 *
 * Skipped rather than failed when the asset is absent, so a developer who has not run
 * `buildBreachFilter` is not blocked by it. The size floor is what makes it non-optional at release
 * time — for both flavours now that the asset lives in `src/main`.
 */
class ShippedBreachCorpusTest {

    private fun shippedAsset(): File? = sequenceOf(
        File("../../app/src/main/assets/breached-passwords.filter"),
        File("app/src/main/assets/breached-passwords.filter"),
    ).firstOrNull(File::isFile)

    @Test
    fun `the shipped corpus recognises passwords everyone knows are breached`() {
        val asset = shippedAsset()
        assumeTrue("no shipped corpus in this checkout", asset != null)
        val filter = BreachFilter { requireNotNull(asset).inputStream() }
        filter.ensureLoaded()

        assertTrue(filter.hasData, "the shipped filter must carry a credible corpus, not a stub")

        // The most-used passwords in every breach compilation ever published. If the shipped filter
        // does not know these, it does not know anything, whatever its size says.
        for (password in listOf("123456", "password", "qwerty", "letmein", "iloveyou")) {
            assertTrue(
                filter.isPotentiallyBreached(password),
                "'$password' must be in the breach set — a filter that misses it is not answering",
            )
        }
    }

    /**
     * The corpus lives in `src/main`, so both flavours ship it.
     *
     * It sat in `src/play` while the redistribution question was open, and the cost was an F-Droid
     * user whose Security screen could not answer the one thing it exists to answer. The shipped
     * list derives from SecLists (`Pwdb_top-10000000`), which is MIT, so the question is settled and
     * the flavour split is gone. Asserted rather than remembered: moving the file back to a flavour
     * source set is a one-line change that nothing else in the build would notice, and the failure
     * it causes is invisible from the `play` side.
     */
    @Test
    fun `the corpus is not confined to a flavour source set`() {
        val repoRoot = sequenceOf(File("../.."), File("."))
            .firstOrNull { File(it, "app/src").isDirectory }
        assumeTrue("not running from a checkout", repoRoot != null)
        val root = requireNotNull(repoRoot)

        assumeTrue("no shipped corpus in this checkout", shippedAsset() != null)

        for (flavour in listOf("play", "foss")) {
            val stray = File(root, "app/src/$flavour/assets/breached-passwords.filter")
            assertFalse(
                stray.exists(),
                "the breach corpus is in the '$flavour' source set, so the other flavour ships " +
                    "none — it belongs in app/src/main/assets so both do",
            )
        }
    }

    /**
     * The other direction, which is the one that catches a filter that says yes to everything.
     *
     * A Golomb-Rice set has a disclosed false-positive rate — 1 in 2^riceK, so 1 in 1024 at the
     * shipped parameters — so this cannot assert "no false positives" from one sample. It asserts
     * that the rate is somewhere near the disclosed one rather than near certainty, which is what a
     * corrupt or wrongly-parameterised filter would produce.
     */
    @Test
    fun `the shipped corpus does not report everything as breached`() {
        val asset = shippedAsset()
        assumeTrue("no shipped corpus in this checkout", asset != null)
        val filter = BreachFilter { requireNotNull(asset).inputStream() }
        filter.ensureLoaded()

        val samples = (1..500).map { "not-a-real-password-$it-8f3a9c2e7b1d4056" }
        val hits = samples.count(filter::isPotentiallyBreached)

        assertFalse(
            hits > samples.size / 10,
            "$hits of ${samples.size} random strings reported breached — far above the disclosed " +
                "1-in-${BreachFilter.DISCLOSED_FALSE_POSITIVE_DENOMINATOR} rate",
        )
    }
}

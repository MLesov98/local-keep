package com.localkeep.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * When a vault's Argon2id cost stops suiting the device it is on.
 *
 * design.md §11.1 asks for a re-benchmark on restore or import, in both directions. Too slow is a
 * usability failure the user can see; too fast is a security failure they cannot, because the same
 * parameters that cost 250 ms on the phone that chose them cost a fraction of that on newer
 * hardware — and an offline attacker gets exactly that speed-up.
 *
 * The point of a pure rule is that this file can enumerate cases a benchmark never would.
 */
class KdfRecalibrationTest {

    /**
     * A vault from before the portability ceiling existed. Every speed fixture below stays at or
     * under [KdfParams.PORTABLE_MAX_MEMORY_KIB] on purpose: the portability rule is checked ahead of
     * both speed rules, so an over-ceiling fixture would make the speed cases assert the wrong
     * thing while still passing for the wrong reason.
     */
    private val overCeiling = KdfParams(memoryKib = 65_536, iterations = 3, parallelism = 4)

    /** The strongest a new vault can be, worked harder — still portable. */
    private val strong = KdfParams(memoryKib = 32_768, iterations = 4, parallelism = 4)
    private val middling = KdfParams(memoryKib = 32_768, iterations = 3, parallelism = 4)
    private val floor = KdfParams.FLOOR

    @Test
    fun `a vault that unlocks in about the target time is left alone`() {
        assertEquals(
            KdfRecalibration.Verdict.KEEP,
            KdfRecalibration.assess(stored = strong, calibrated = strong, storedMillis = 250),
        )
    }

    @Test
    fun `a vault too slow for this device offers to come down`() {
        assertEquals(
            KdfRecalibration.Verdict.OFFER_FASTER,
            KdfRecalibration.assess(stored = strong, calibrated = floor, storedMillis = 4_000),
        )
    }

    @Test
    fun `a vault too cheap for this device offers to go up`() {
        assertEquals(
            KdfRecalibration.Verdict.OFFER_STRONGER,
            KdfRecalibration.assess(stored = floor, calibrated = strong, storedMillis = 40),
        )
    }

    /**
     * The case that keeps this from nagging: slow, but there is nothing weaker worth moving to.
     * Offering a change that cannot help is worse than silence — it teaches the user to dismiss the
     * prompt, including the time it matters.
     */
    @Test
    fun `slowness with nowhere lower to go is not offered`() {
        assertEquals(
            KdfRecalibration.Verdict.KEEP,
            KdfRecalibration.assess(stored = floor, calibrated = floor, storedMillis = 9_000),
        )
    }

    @Test
    fun `speed with nothing stronger available is not offered`() {
        assertEquals(
            KdfRecalibration.Verdict.KEEP,
            KdfRecalibration.assess(stored = strong, calibrated = strong, storedMillis = 10),
        )
    }

    /**
     * The band between the two thresholds is deliberately wide and deliberately silent. A vault
     * that takes 800 ms is not pleasant and not dangerous, and prompting there would fire on
     * ordinary variation — a device under thermal pressure, a cold start, another app compacting.
     */
    @Test
    fun `the quiet band between the thresholds prompts for nothing`() {
        listOf(200L, 400L, 800L, 1_400L).forEach { millis ->
            assertEquals(
                KdfRecalibration.Verdict.KEEP,
                KdfRecalibration.assess(middling, strong, millis),
                "$millis ms should not prompt",
            )
            assertEquals(
                KdfRecalibration.Verdict.KEEP,
                KdfRecalibration.assess(strong, floor, millis),
                "$millis ms should not prompt",
            )
        }
    }

    /**
     * Work is memory × iterations, not memory alone.
     *
     * Ordering the ladder on memory would call `64 MiB, t=1` stronger than `48 MiB, t=4`, which is
     * backwards — an attacker pays for both. Parallelism is deliberately absent: it divides a
     * defender's wall clock when cores are free, but it does not reduce the total work per guess,
     * so counting it would let a params change look stronger while costing an attacker less.
     */
    @Test
    fun `work counts iterations, not memory alone`() {
        val wideAndShallow = KdfParams(memoryKib = 65_536, iterations = 2, parallelism = 4)
        val narrowAndDeep = KdfParams(memoryKib = 49_152, iterations = 3, parallelism = 4)

        assert(KdfRecalibration.work(narrowAndDeep) > KdfRecalibration.work(wideAndShallow))
    }

    /**
     * The H-1 rule, and the reason it is checked before either speed rule.
     *
     * A vault stored above the ceiling cannot be opened on a phone at this app's own `minSdk` — the
     * Argon2id cost is a heap allocation and API 28 caps an app at 48 MiB. Re-wrapping it needs the
     * vault open, so the fix can only be applied on a device where it *does* open, and there it is
     * fast and the speed rules say KEEP. Ordered below them, this verdict could never fire on the
     * one device able to act on it.
     */
    @Test
    fun `a vault above the portability ceiling is offered the fix on a fast device`() {
        assertEquals(
            KdfRecalibration.Verdict.OFFER_PORTABLE,
            KdfRecalibration.assess(stored = overCeiling, calibrated = middling, storedMillis = 180),
            "a modern phone opens it fine, and is the only place the re-wrap can happen",
        )
    }

    @Test
    fun `the portability rule outranks both speed rules`() {
        // Fast enough for OFFER_STRONGER on the numbers, if the ordering were wrong.
        assertEquals(
            KdfRecalibration.Verdict.OFFER_PORTABLE,
            KdfRecalibration.assess(stored = overCeiling, calibrated = strong, storedMillis = 10),
        )
        // Slow enough for OFFER_FASTER on the numbers.
        assertEquals(
            KdfRecalibration.Verdict.OFFER_PORTABLE,
            KdfRecalibration.assess(stored = overCeiling, calibrated = floor, storedMillis = 9_000),
        )
    }

    @Test
    fun `a vault exactly at the ceiling is portable and prompts for nothing`() {
        val atCeiling = KdfParams(
            memoryKib = KdfParams.PORTABLE_MAX_MEMORY_KIB,
            iterations = 3,
            parallelism = 4,
        )

        assertEquals(
            KdfRecalibration.Verdict.KEEP,
            KdfRecalibration.assess(stored = atCeiling, calibrated = atCeiling, storedMillis = 250),
        )
    }

    @Test
    fun `parallelism alone never changes the verdict`() {
        val oneCore = KdfParams(memoryKib = 32_768, iterations = 3, parallelism = 1)
        val fourCore = KdfParams(memoryKib = 32_768, iterations = 3, parallelism = 4)

        assertEquals(KdfRecalibration.work(oneCore), KdfRecalibration.work(fourCore))
        assertEquals(
            KdfRecalibration.Verdict.KEEP,
            KdfRecalibration.assess(stored = oneCore, calibrated = fourCore, storedMillis = 10),
        )
    }
}

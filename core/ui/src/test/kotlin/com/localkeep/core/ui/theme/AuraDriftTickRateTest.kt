package com.localkeep.core.ui.theme

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The aura's breathing is a *sample*, not an animation.
 *
 * `lkAuraTop` sits behind every screen header, and it drove its 14 s drift through
 * `animateFloatAsState`, which reads the frame clock: a completely static unlock screen redrew the
 * wash at the display's rate for as long as it was up — 289 frames in 5 s, measured on API 28.
 * Nothing in those frames differed, because the drift moves at most 0.058 of its range in 100 ms
 * and only scales the wash by a few percent of that.
 *
 * So the rate is the assertion. The tick is timed against the wall clock rather than a Compose test
 * clock because there is no Compose seam to time here: under Robolectric the effect coroutine a
 * `createComposeRule` installs never resumes its `delay`s across `mainClock.advanceTimeBy(1_000)`
 * (measured 2026-09-17 — a bare `LaunchedEffect { delay(100) }` produced one value, its first).
 * [driveAuraDrift] exists for that reason, and [rememberSlowDrift] is one expression over it.
 */
class AuraDriftTickRateTest {

    /** A second of drift may not produce more than a tick's worth of values. */
    @Test
    fun driftAdvancesAtMostTenTimesASecond() = runBlocking {
        val seen = mutableListOf<Float>()
        withTimeoutOrNull(1_000L) { driveAuraDrift { seen += it } }

        assertTrue(
            seen.size <= 12,
            "aura drift produced ${seen.size} values in 1 s — it is advancing per frame again, " +
                "not on its tick",
        )
        assertTrue(
            seen.size >= 5,
            "aura drift produced only ${seen.size} values in 1 s — the breathing has stopped",
        )
    }

    /**
     * And the composable is that driver, not a frame-clock animation wearing its name.
     *
     * Asserted on the source for the reason in the class KDoc: the effect's `delay` is untimeable
     * here, so nothing else can tell the two implementations apart. Same technique, and the same
     * limitation, as `ZoomableImageGestureTest` in :feature:files.
     */
    @Test
    fun theAuraDrivesItselfFromTheTickNotTheFrameClock() {
        val source = File("src/main/kotlin/com/localkeep/core/ui/theme/LkModifiers.kt")
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val body = source.readText()
            .substringAfter("internal fun rememberSlowDrift(")
            .substringBefore("\n/**")
        assertTrue("driveAuraDrift" in body, "rememberSlowDrift no longer ticks: $body")
        assertTrue(
            "animateFloatAsState" !in body,
            "rememberSlowDrift is back on the frame clock: $body",
        )
    }

    /** Control: the curve the tick samples is the one the tween drew. */
    @Test
    fun driftRisesAndFallsOverFourteenSeconds() {
        assertEquals(0f, auraDriftAt(0L), "the breath starts at rest")
        assertEquals(1f, auraDriftAt(7_000L), "the breath is full at the half cycle")
        assertEquals(0f, auraDriftAt(14_000L), "the breath is back at rest after a full cycle")
        assertTrue(auraDriftAt(3_500L) > 0f && auraDriftAt(3_500L) < 1f, "mid-rise is between the ends")
        assertTrue(auraDriftAt(10_500L) > 0f && auraDriftAt(10_500L) < 1f, "mid-fall is between the ends")
    }

    /** Control: it repeats, so a screen left up for a minute does not drift away from a fresh one. */
    @Test
    fun driftRepeatsEveryCycle() {
        listOf(0L, 1_200L, 6_000L, 9_900L).forEach { at ->
            assertEquals(
                auraDriftAt(at),
                auraDriftAt(at + 14_000L),
                "the cycle must repeat exactly at $at ms",
            )
        }
    }

    /**
     * Control: one tick is a small enough step that dropping the frames between them changes
     * nothing anyone can see. 0.058 is the measured worst case, at the steep front of
     * [LkMotion.Standard]; the wash turns that into a third of a percent of its radius.
     */
    @Test
    fun oneTickIsAnImperceptibleStep() {
        val largest = (0L until 14_000L step 100L)
            .maxOf { abs(auraDriftAt(it + 100L) - auraDriftAt(it)) }
        assertTrue(largest <= 0.06f, "a single tick moved the drift by $largest — visible, not ambient")
    }
}

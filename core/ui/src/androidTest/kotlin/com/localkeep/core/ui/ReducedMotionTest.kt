package com.localkeep.core.ui

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkOpen
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second defect class that had no signal, and the reason the drawer stayed broken for a week.
 *
 * `FontScaleTest` closed the dynamic-type half of this; the reduced-motion half had nothing. Every
 * surface in the app routes its animation through [lkTween], which collapses to zero when
 * `LocalLkReduceMotion` is set — every surface except the drawer, whose travel is decided inside
 * `ModalNavigationDrawer` and takes no spec. Settings tells the user in as many words that motion
 * follows their system setting, so the app's widest movement was the one place that was untrue.
 *
 * ### What it measures, after two approaches that measured nothing
 *
 * **Elapsed virtual time on the test clock.** `waitForIdle` runs the drawer to rest and advances
 * the clock by exactly what the animation consumed, so an open that snaps costs a frame or less and
 * an open that travels costs far more. That difference is the property reduced motion is about.
 *
 * The two discarded approaches are recorded because both looked right and both would have shipped a
 * test that passes on broken code:
 *
 *  1. **`DrawerState.currentValue`** — Material3 commits it to the target as soon as the animation
 *     *starts*, so the animated path reaches `Open` within two frames just as the snap does.
 *  2. **`DrawerState.currentOffset`, sampled between hand-stepped frames** with
 *     `mainClock.autoAdvance = false` — read identically in both modes, because the drawer had not
 *     begun to move at all. A coroutine launched on `Dispatchers.Main` does not inherit the test's
 *     frame clock; the animation simply never advanced, which reads exactly like a broken drawer
 *     and was entirely a harness mistake. Hence `rememberCoroutineScope` in [openDurationMillis].
 */
@RunWith(AndroidJUnit4::class)
class ReducedMotionTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    private companion object {
        /** 60 Hz. An open that lands within a frame did not animate. */
        const val ONE_FRAME_MS = 16L
    }

    @get:Rule
    val compose = createComposeRule()

    /**
     * With motion reduced, opening the drawer must consume no animation time.
     *
     * The observable is **elapsed virtual time on the test clock**, not [DrawerState.currentValue]
     * and not the offset. That choice is the test, and both alternatives were tried and discarded:
     * Material3 commits `currentValue` to the target as soon as the animation *starts*, so a
     * `currentValue` assertion is satisfied by the animated path too and passes on broken code; and
     * sampling `currentOffset` between hand-stepped frames measured a drawer that had not begun to
     * move, so it read the same in both modes. Time spent is the thing that actually differs.
     */
    @Test
    fun reducedMotionOpensTheDrawerWithoutSpendingTime() {
        assertTrue(
            openDurationMillis(reduceMotion = true) <= ONE_FRAME_MS,
            "a reduced-motion open must not animate",
        )
    }

    /**
     * The counterpart, and the reason the one above means something.
     *
     * Without it, "reduced motion works" would also be satisfied by deleting every animation in the
     * app, and the suite would applaud. This pins that the ordinary path still travels.
     */
    @Test
    fun ordinaryMotionStillAnimatesTheDrawer() {
        assertTrue(
            openDurationMillis(reduceMotion = false) > ONE_FRAME_MS,
            "the ordinary path must still animate",
        )
    }

    /**
     * Opens a real [ModalNavigationDrawer] and returns how much clock the open consumed.
     *
     * The drawer has to be composed: a bare `rememberDrawerState` has no anchors and reports `NaN`,
     * and `snapTo` has nowhere to snap to.
     */
    private fun openDurationMillis(reduceMotion: Boolean): Long {
        lateinit var drawer: DrawerState
        // From inside composition, so the coroutine inherits the test's frame clock. A scope built
        // on `Dispatchers.Main` instead does not, and the open animation simply never advances —
        // which reads exactly like a broken drawer and is purely a harness mistake.
        lateinit var uiScope: CoroutineScope

        compose.setContent {
            CompositionLocalProvider(LocalLkReduceMotion provides reduceMotion) {
                drawer = rememberDrawerState(DrawerValue.Closed)
                uiScope = rememberCoroutineScope()
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = { Box(Modifier.width(280.dp).fillMaxHeight()) },
                    content = { Box(Modifier.fillMaxSize()) },
                )
            }
        }
        compose.waitForIdle()

        val before = compose.mainClock.currentTime
        compose.runOnUiThread { uiScope.launch { drawer.lkOpen(reduceMotion) } }
        compose.waitForIdle()
        val elapsed = compose.mainClock.currentTime - before

        assertEquals(DrawerValue.Open, drawer.currentValue, "the drawer should have opened either way")
        return elapsed
    }

    /**
     * The vocabulary the rest of the app animates through, asserted directly.
     *
     * [lkTween] is read by every screen transition, indicator and stagger in the design system, so
     * one assertion here covers far more surface than any single screen test would. Duration is read
     * out of the spec via `vectorize`, because `TweenSpec`'s `durationMillis` is not public API.
     */
    @Test
    fun reducedMotionCollapsesTheSharedTween() {
        var reducedDuration = -1L
        var ordinaryDuration = -1L

        compose.setContent {
            CompositionLocalProvider(LocalLkReduceMotion provides true) {
                reducedDuration = durationNanosOf(lkTween(durationMillis = 240))
            }
            CompositionLocalProvider(LocalLkReduceMotion provides false) {
                ordinaryDuration = durationNanosOf(lkTween(durationMillis = 240))
            }
        }

        assertEquals(0L, reducedDuration, "lkTween must collapse to zero when motion is reduced")
        assertTrue(ordinaryDuration > 0L, "lkTween must still animate when motion is not reduced")
    }

    /** `TweenSpec.durationMillis` is not public API, so the duration is read off the vectorized spec. */
    private fun durationNanosOf(spec: FiniteAnimationSpec<Float>): Long =
        spec.vectorize(Float.VectorConverter).getDurationNanos(
            AnimationVector1D(0f),
            AnimationVector1D(1f),
            AnimationVector1D(0f),
        )
}

package com.localkeep.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.localkeep.core.ui.theme.LkMotion
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LkCardPreview
import com.localkeep.core.ui.theme.LkCardSlot
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `LkCardSlotGlyph` (`LkCards.kt`) animated every keystroke of a card number field via a raw
 * `tween(LkMotion.Quick, easing = LkMotion.Overshoot)`, with no `LocalLkReduceMotion` check
 * anywhere in the file — the one animation in the app that fires on every keypress rather than
 * once per screen or action. Every other per-item entrance in the design system threads
 * `LocalLkReduceMotion` through (see `ReducedMotionTest`, `lkTween`), and this was the gap.
 *
 * Same measurement idiom as `ReducedMotionTest.reducedMotionOpensTheDrawerWithoutSpendingTime`:
 * elapsed virtual time on the test clock, not the settled value, since `Animatable.animateTo`
 * commits toward its target immediately either way and only the travel time differs.
 */
@RunWith(AndroidJUnit4::class)
class LkCardSlotGlyphReducedMotionTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        /**
         * What the state change costs before any animation can run: flipping `landing` recomposes
         * the slot and relaunches its `LaunchedEffect`, and the test clock advances two 60 Hz
         * frames doing it. That floor is not motion and cannot be removed by a reduce-motion
         * setting.
         *
         * The budget was `ONE_FRAME_MS = 16` and the reduced path measured 32ms every time, so this
         * test could not pass whatever the code did — it was asserting that recomposition is free.
         * It went unnoticed because the local gate never compiles androidTest.
         */
        const val RECOMPOSITION_FLOOR_MS = 32L
    }

    @Test
    fun reducedMotionLandsTheDigitWithoutSpendingTime() {
        val spent = landingDurationMillis(reduceMotion = true)
        assertTrue(
            spent <= RECOMPOSITION_FLOOR_MS,
            "a reduced-motion digit landing must not animate; spent ${spent}ms, and the " +
                "recomposition floor is ${RECOMPOSITION_FLOOR_MS}ms",
        )
    }

    /** The control: without this, deleting the animation entirely would also pass the test above. */
    @Test
    fun ordinaryMotionStillAnimatesTheDigit() {
        // Asserts the full animation, not merely "more than a frame". The two paths are then
        // separated by a factor of five rather than by one frame, so neither deleting the animation
        // nor letting reduce-motion animate can slip through on timing noise.
        val spent = landingDurationMillis(reduceMotion = false)
        assertTrue(
            spent >= LkMotion.Quick,
            "the ordinary path must still animate a landing digit for its full ${LkMotion.Quick}ms; " +
                "spent ${spent}ms",
        )
    }

    /**
     * Renders a single-slot [LkCardPreview], flips that slot to `landing = true` after first idle,
     * and returns how much clock the landing animation consumed.
     */
    private fun landingDurationMillis(reduceMotion: Boolean): Long {
        val slots = mutableStateOf(listOf(LkCardSlot(ch = '4', filled = true, landing = false)))

        compose.setContent {
            CompositionLocalProvider(LocalLkReduceMotion provides reduceMotion) {
                LkCardPreview(
                    numberSlots = slots.value,
                    expiryDisplay = "",
                    holder = "",
                    expiryCaption = "",
                    holderPlaceholder = "",
                    contentDescription = "card",
                )
            }
        }
        compose.waitForIdle()

        val before = compose.mainClock.currentTime
        slots.value = listOf(LkCardSlot(ch = '4', filled = true, landing = true))
        compose.waitForIdle()
        return compose.mainClock.currentTime - before
    }
}

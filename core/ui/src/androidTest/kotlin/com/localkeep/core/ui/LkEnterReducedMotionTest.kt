package com.localkeep.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entrance must consume no animation time when motion is reduced, and must still travel when
 * it is not.
 *
 * **Why this reads a pixel rather than the clock.** The first version of this test measured elapsed
 * clock time, and read it *after* a `waitForIdle()` that had already run the animation to
 * completion — so both modes measured 0 ms. The reduce-motion assertion passed vacuously and its
 * own control, the one written to stop exactly that, failed with "ordinary entrance must animate
 * (>16ms), but took only 0ms". It shipped red in `863118c`.
 *
 * The entrance is a `graphicsLayer` translation, so layout position and bounds never move and only
 * a rendered frame can see it — the same reasoning, and the same instrument, as
 * `LkEnterReentranceTest` and `LkEnterNavReturnTest`. With the clock frozen, a few frames after
 * composition: reduced motion is already settled, ordinary motion is still ten dp low.
 */
@RunWith(AndroidJUnit4::class)
class LkEnterReducedMotionTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val compose = createComposeRule()

    /**
     * Sampled as a fraction of the frame, never a pixel constant — `captureToImage()` returns
     * pixels, so a literal row index is only the row you meant at density 1.0. The 40.dp subject
     * centred in the 80.dp frame settles across 25–75% of it; the entrance starts it 10.dp lower,
     * at 37.5–87.5%. 30% is inside the settled subject and outside the entering one, at every
     * density.
     */
    private fun settledBandPixel(): Color {
        val image = compose.onNodeWithTag(FRAME).captureToImage().toPixelMap()
        return image[image.width / 2, (image.height * SETTLED_ONLY_BAND).toInt()]
    }

    private fun composeSubject(reduceMotion: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                CompositionLocalProvider(LocalLkReduceMotion provides reduceMotion) {
                    Box(
                        Modifier.testTag(FRAME).size(80.dp).background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(40.dp).lkEnter().background(SUBJECT))
                    }
                }
            }
        }
        // Three frames: enough for composition to run the `LaunchedEffect` that flips `shown`, for
        // the recomposition it triggers, and for a collapsed tween to land. Nowhere near enough for
        // a 460 ms one — `LkEnterReentranceTest` uses the same 48 ms probe to catch an entrance
        // mid-travel. One frame is not enough, and asserting on it fails against reduce-motion too.
        compose.mainClock.advanceTimeBy(PROBE_MS)
    }

    @Test
    fun reducedMotionEntranceArrivesImmediately() {
        composeSubject(reduceMotion = true)
        assertEquals(
            SUBJECT,
            settledBandPixel(),
            "with motion reduced the entrance must consume no animation time — the subject should " +
                "already be at its settled position, but the settled band is still background",
        )
    }

    /**
     * The control. A check that never fires is broken, not clean.
     *
     * Without this, deleting the entrance entirely — or measuring something that cannot see it,
     * which is what the previous version did — would leave the assertion above passing.
     */
    @Test
    fun ordinaryMotionStillTravels() {
        composeSubject(reduceMotion = false)
        assertNotEquals(
            SUBJECT,
            settledBandPixel(),
            "the ordinary entrance was expected to still be travelling this early. If this passes, " +
                "the entrance is not animating at all and the reduce-motion test above proves nothing.",
        )
    }

    private companion object {
        const val FRAME = "frame"

        /** See [settledBandPixel]. */
        const val SETTLED_ONLY_BAND = 0.30f

        /** Three frames at 60 Hz. A collapsed animation is long done; a 460 ms one has barely begun. */
        const val PROBE_MS = 48L
        val SUBJECT = Color.Red
    }
}

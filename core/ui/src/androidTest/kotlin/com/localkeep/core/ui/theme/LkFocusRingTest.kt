package com.localkeep.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves [lkFocusRing] actually draws.
 *
 * The reason this is a pixel test and not an assertion about state: the ring is the *entire*
 * deliverable of "make focus visible", and every cheaper check passes whether or not a single pixel
 * changes. It compiles either way. `onFocusChanged` fires either way. A `uiautomator dump` reports
 * `focused=true` either way — it reads the semantics tree, which knows nothing about what was
 * painted. And the app's own windows carry `FLAG_SECURE`, so a screenshot taken off a running device
 * is uniformly black; there is no way to look at this by hand. `captureToImage()` renders into an
 * offscreen bitmap, which is not subject to that, so this is the one place the ring can be seen.
 *
 * **These tests passed, then failed, and the ring was never the reason** — worth recording, because
 * the false conclusion available at every step was "the focus indicator is broken". What actually
 * happened: the subject was built on `Modifier.clickable`, and a clickable is focusable only when
 * the device is *out of touch mode*. The first runs came minutes after driving the emulator with
 * D-pad keys, so focus was granted and everything passed. Later runs came after tapping around, so
 * the device was back in touch mode, `requestFocus()` quietly did nothing, and a test that captures
 * pixels reported the only thing it can see: no ring.
 *
 * Two things fell out of that. The subject uses `focusable()` now, which does not care about input
 * mode — in the app the ring sits on real clickables, and a keyboard or switch user has left touch
 * mode by definition, so nothing about the production path is being dodged. And the diagnosis came
 * from asserting the *precondition* (`assertIsFocused`) rather than staring at the assertion that
 * failed; a pixel test that cannot say whether it ever reached the state it was testing will send
 * you looking in the wrong file every time.
 */
@RunWith(AndroidJUnit4::class)
class LkFocusRingTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    private val ground = Color(0xFF0A0C0E)
    private val jade = Color(0xFF3ED9AE)

    /** A plain square on the app's ground: any green here can only have come from the ring. */
    @Composable
    private fun Subject(tag: String) {
        Box(
            Modifier
                .size(80.dp)
                .testTag(tag)
                .background(ground)
                .lkFocusRing(RectangleShape, accent = jade)
                // focusable(), not clickable(). A clickable is focusable only when the device is
                // out of touch mode, so a test built on one passes or fails depending on whether
                // the last thing sent to the emulator was a D-pad key or a tap — which is exactly
                // how these tests appeared to pass and then "spontaneously" broke. In the app the
                // ring sits on real clickables and a keyboard/switch user has already left touch
                // mode by definition; here the point is only that focus reaches the modifier.
                .focusable(),
        )
    }

    @Test
    fun drawsNothingUntilFocused() {
        rule.setContent { Subject("subject") }
        rule.waitForIdle()

        assertFalse(
            rule.onNodeWithTag("subject").captureToImage().hasRingOnItsEdge(),
            "an unfocused control is already drawing a focus ring",
        )
    }

    @Test
    fun stillDrawsWhenTheUserHasTurnedAnimationsOff() {
        // The ring fades in through lkTween, which collapses to a single frame under reduced motion.
        // "Collapses to nothing" would be the easy bug to write there, and it would take the focus
        // indicator away from precisely the users most likely to need it.
        rule.setContent {
            CompositionLocalProvider(LocalLkReduceMotion provides true) { Subject("subject") }
        }

        rule.onNodeWithTag("subject").requestFocus()
        rule.waitForIdle()

        assertTrue(
            rule.onNodeWithTag("subject").captureToImage().hasRingOnItsEdge(),
            "reduced motion removed the focus indicator instead of just its animation",
        )
    }

    @Test
    fun drawsOnTheEdgeOnceFocused() {
        rule.setContent { Subject("subject") }

        rule.onNodeWithTag("subject").requestFocus()
        rule.waitForIdle()
        // If THIS fails the test harness never granted focus and the ring is not the subject.
        rule.onNodeWithTag("subject").assertIsFocused()

        assertTrue(
            rule.onNodeWithTag("subject").captureToImage().hasRingOnItsEdge(),
            "focused, but nothing was drawn — the indicator is invisible",
        )
    }

    @Test
    fun stopsDrawingWhenFocusMovesToSomethingElse() {
        rule.setContent {
            Column(Modifier.background(ground)) {
                Subject("first")
                Subject("second")
            }
        }

        rule.onNodeWithTag("first").requestFocus()
        rule.waitForIdle()
        assertTrue(
            rule.onNodeWithTag("first").captureToImage().hasRingOnItsEdge(),
            "the first control never drew a ring, so this proves nothing about it losing one",
        )

        rule.onNodeWithTag("second").requestFocus()
        rule.waitForIdle()

        // A ring left behind on a control the user has moved away from is worse than none: it points
        // at the wrong place, and now two things claim to be focused.
        assertFalse(
            rule.onNodeWithTag("first").captureToImage().hasRingOnItsEdge(),
            "focus moved away but the old ring is still drawn",
        )
        assertTrue(
            rule.onNodeWithTag("second").captureToImage().hasRingOnItsEdge(),
            "focus moved here but nothing was drawn",
        )
    }

    /**
     * True if any pixel within 3px of the bounds is unmistakably the accent rather than the ground.
     *
     * Tests the green channel rather than matching jade exactly, because the ring is antialiased and
     * fades in — a strict match would pass or fail on which frame the capture landed, and a flaky
     * test on an accessibility guarantee reads as "sometimes the focus ring works". Ground is
     * #0A0C0E (green 12), jade is #3ED9AE (green 217): green past 100 and clear of red cannot be
     * anything else.
     */
    private fun ImageBitmap.hasRingOnItsEdge(): Boolean {
        val pixels = toPixelMap()
        val band = 3
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x >= band && y >= band && x < width - band && y < height - band) continue
                val argb = pixels[x, y].toArgb()
                val green = (argb shr 8) and 0xFF
                val red = (argb shr 16) and 0xFF
                if (green > 100 && green > red + 40) return true
            }
        }
        return false
    }
}

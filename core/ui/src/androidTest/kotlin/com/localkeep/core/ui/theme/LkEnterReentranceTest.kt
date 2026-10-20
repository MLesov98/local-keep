package com.localkeep.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
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
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A node that has already arrived must never arrive again.
 *
 * The defect this guards, reported from hands-on use 2026-08-11: "I created an entry and the folder
 * glitches, it's like it tries to escape ... since we introduced the sparkling around it." The vault
 * list gave a just-saved row a jade reveal wash and skipped its entrance while the wash was up, by
 * swapping the call site between a bare `Modifier` and `Modifier.lkEnter(...)`. [lkEnter] is a plain
 * `@Composable` extension, so its `remember` lives at the caller's call site — and when
 * `clearReveal()` flipped the flag back 1.4 s later, the `lkEnter` branch composed for the first
 * time, re-remembered `shown` as false, and replayed the entrance on a row that had been sitting on
 * screen since the save. The row blinked to nothing and jumped ten dp, with the monogram's glow
 * riding along, which is what read as coming loose.
 *
 * **Why pixels.** Everything cheaper is blind to this. The entrance is a `graphicsLayer` translation
 * that moves the subject down by `travel` (10.dp) while animating in. Both the layout position and
 * the layout bounds stay constant; only the painted position changes, visible only to `captureToImage()`
 * rendering offscreen. A `uiautomator dump` reads semantics and sees nothing; the app's `FLAG_SECURE`
 * blocks screenshots. Detecting reentrance via translation requires sampling a pixel that is outside
 * the subject's initial (translated) position but inside its final position. The edge-pixel approach
 * samples Y=25, which is inside the final 40.dp subject (centred in 80.dp frame: Y 20–60) but outside
 * its initial position during animation (translationY up to 10.dp, so Y 30–70 initially). During
 * entrance, edge pixel is background; after completion, it's subject colour. A replay puts it back
 * outside, returning background.
 *
 * The clock is frozen so a frame can be read mid-entrance, before the animation finishes and erases
 * its own evidence.
 */
@RunWith(AndroidJUnit4::class)
class LkEnterReentranceTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    /** Hoisted out of the composition so the test can flip it the way `clearReveal()` does. */
    private val revealed = mutableStateOf(true)

    /**
     * Samples the band that only the settled subject covers. Subject colour means arrived;
     * background means the entrance is replaying or still running.
     *
     * **As a fraction of the frame, never a pixel constant.** `captureToImage()` returns *pixels*,
     * so a literal row index is only the row you meant at density 1.0. This sampled Y=25 with the
     * geometry argued in dp, which on the 420 dpi emulator (2.625×) is 9.5.dp — above the subject
     * entirely, so both tests here failed their own preconditions on any ordinary device. Expressed
     * proportionally the arithmetic holds at every density: the 40.dp subject centred in the 80.dp
     * frame settles across 25–75% of it, and the entrance starts it 10.dp lower, at 37.5–87.5%.
     * Anything in 25–37.5% is inside the settled subject and outside the entering one; 30% sits in
     * the middle of that band, clear of edge antialiasing.
     */
    private fun edgePixel(): Color {
        val image = rule.onNodeWithTag(FRAME).captureToImage().toPixelMap()
        return image[image.width / 2, (image.height * SETTLED_ONLY_BAND).toInt()]
    }

    /**
     * @param conditionalCallSite the shape the vault list used to have — the bug under test. When
     *   false the call is unconditional and says "this one has already arrived" with `enabled`,
     *   which is the fix.
     */
    private fun composeSubject(conditionalCallSite: Boolean) {
        rule.setContent {
            LocalKeepTheme {
                Box(
                    Modifier.testTag(FRAME).size(80.dp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val enter = if (conditionalCallSite) {
                        if (revealed.value) Modifier else Modifier.lkEnter()
                    } else {
                        Modifier.lkEnter(enabled = !revealed.value)
                    }
                    Box(Modifier.size(40.dp).then(enter).background(SUBJECT))
                }
            }
        }
    }

    @Test
    fun aRevealedRowDoesNotReplayItsEntranceWhenTheRevealEnds() {
        rule.mainClock.autoAdvance = false
        composeSubject(conditionalCallSite = false)
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(
            SUBJECT,
            edgePixel(),
            "the subject should be fully shown (edge pixel at final position) before the reveal ends — the test is set up wrong",
        )

        // The reveal ends. Nothing about the node has changed except a flag it already honoured.
        rule.runOnUiThread { revealed.value = false }
        rule.mainClock.advanceTimeBy(48)
        assertEquals(
            SUBJECT,
            edgePixel(),
            "the entrance replayed when the reveal ended — the subject slid back to its initial " +
                "translated position, putting the edge pixel outside its bounds (back to background). " +
                "This is the glitch this test exists to prevent.",
        )
    }

    /**
     * The falsification. A check that never fires is broken, not clean.
     *
     * Composes the exact call-site shape the vault list used to carry, and proves the assertion
     * above can fail: with the branch swapped for a bare `Modifier`, flipping the flag restarts the
     * entrance and the edge pixel moves back outside the subject's final bounds. Without this, a
     * passing test above would be indistinguishable from an instrument that cannot see anything at all.
     */
    @Test
    fun theOldConditionalCallSiteReallyDoesReplayIt() {
        rule.mainClock.autoAdvance = false
        composeSubject(conditionalCallSite = true)
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(SUBJECT, edgePixel(), "precondition: the subject starts fully shown at final position")

        rule.runOnUiThread { revealed.value = false }
        rule.mainClock.advanceTimeBy(48)
        assertNotEquals(
            SUBJECT,
            edgePixel(),
            "the old call site was expected to replay the entrance. The edge pixel should move back " +
                "to background colour when the subject re-translates. If this passes, the instrument " +
                "cannot see the defect and the test above proves nothing.",
        )
    }

    private companion object {
        const val FRAME = "frame"

        /** See [edgePixel]: a fraction of the frame the settled subject covers and the entering one does not. */
        const val SETTLED_ONLY_BAND = 0.30f
        val SUBJECT = Color.Red
    }
}

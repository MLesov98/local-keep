package com.localkeep.core.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A screen you have already visited must not arrive again when you come back to it.
 *
 * The defect this guards, reported from hands-on use 2026-08-12: "the sparkling is cool but when I
 * open it, it again flickers for 0.5-1s and stops and this happens every time when I click on it
 * and go back". Measured on the reporter's own device, eight crossings across two recordings: the
 * frame body dips 19.5–19.8% below its settled luminance and takes 476–1278 ms to come back.
 *
 * The cause is not the halo, which has no animation of its own — it is a static `drawCircle` in
 * [LkMonogramTile] that rides the row's entrance. It is [lkEnter] restarting. `NavHost` renders each
 * back-stack entry through an `AnimatedContent` that disposes the outgoing entry's whole composition
 * group once its exit finishes, which discards every `remember` inside it. `shown` was a bare
 * `remember`, so every push, every pop and every bottom-bar tab switch re-seeded it to false and
 * replayed the entrance.
 *
 * `rememberSaveable` fixes it because `NavHost` has already built the machinery: it creates one
 * `rememberSaveableStateHolder()` outside the `AnimatedContent`, and gives each entry a
 * `SaveableStateProvider` keyed by an id cached in that entry's own `SavedStateHandle`. The provider
 * writes every saveable value out on dispose and reads it back on return. [lkEnter] only has to opt
 * in — which is why the fix is one word and no call site changed.
 *
 * **Why this test does not use `NavHost`.** It does not need to. The thing that decides the outcome
 * is the `SaveableStateProvider` dispose/restore cycle, which is composed here directly and keyed
 * the same way. Driving a real `NavHost` would test navigation-compose's wiring, which is not ours.
 *
 * **Why pixels, and why the clock is frozen** — both for the same reasons as [LkEnterReentranceTest],
 * whose edge-pixel technique this reuses. The entrance is a `graphicsLayer` translation; layout
 * position and bounds never move, so only a rendered frame can see it. Note that this survived the
 * removal of `lkEnter`'s alpha (`e69ff21`): the instrument reads position, not opacity.
 */
@RunWith(AndroidJUnit4::class)
class LkEnterNavReturnTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    /** Hoisted so the test can dispose and restore the subtree the way a push and a pop do. */
    private val present = mutableStateOf(true)

    /**
     * Sampled as a fraction of the frame, never a pixel constant — `captureToImage()` returns
     * pixels, and a literal row index is only the row you meant at density 1.0. The 40.dp subject
     * centred in the 80.dp frame settles across 25–75% of it; the entrance starts it 10.dp lower, at
     * 37.5–87.5%. 30% is inside the settled subject, outside the entering one, at every density.
     */
    private fun edgePixel(): Color {
        val image = rule.onNodeWithTag(FRAME).captureToImage().toPixelMap()
        return image[image.width / 2, (image.height * SETTLED_ONLY_BAND).toInt()]
    }

    /**
     * @param bareRemember composes a replica of [lkEnter] whose `shown` is a bare `remember` — the
     *   shape shipped before `e69ff21`, and the falsification below. The real modifier is used
     *   otherwise.
     */
    private fun composeSubject(bareRemember: Boolean) {
        rule.setContent {
            LocalKeepTheme {
                val holder = rememberSaveableStateHolder()
                Box(
                    Modifier.testTag(FRAME).size(80.dp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    // Exactly what NavHost wraps a destination in. Removing the call disposes the
                    // subtree and saves it; re-adding it restores.
                    if (present.value) {
                        holder.SaveableStateProvider(ENTRY_KEY) {
                            val enter = if (bareRemember) Modifier.enterWithBareRemember() else Modifier.lkEnter()
                            Box(Modifier.size(40.dp).then(enter).background(SUBJECT))
                        }
                    }
                }
            }
        }
    }

    /** Arrive, leave, come back. The subject must already be where it settled. */
    @Test
    fun aScreenReturnedToDoesNotReplayItsEntrance() {
        rule.mainClock.autoAdvance = false
        composeSubject(bareRemember = false)
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(
            SUBJECT,
            edgePixel(),
            "the subject should be fully arrived before we navigate away — the test is set up wrong",
        )

        // Push: the destination leaves the visible set and its composition group is disposed.
        rule.runOnUiThread { present.value = false }
        rule.mainClock.advanceTimeBy(48)
        // Pop: the same entry is composed again, and its saved state is restored.
        rule.runOnUiThread { present.value = true }
        rule.mainClock.advanceTimeBy(48)

        assertEquals(
            SUBJECT,
            edgePixel(),
            "the entrance replayed on the way back. The subject slid back to its initial translated " +
                "position, putting the edge pixel outside its settled bounds. This is the 0.5-1s " +
                "flicker on every return that this test exists to prevent.",
        )
    }

    /**
     * The falsification. A check that never fires is broken, not clean.
     *
     * The test above passes trivially if the instrument cannot see a replay at all — which is
     * exactly how the first version of this file was wrong: it reported a hardcoded `1f` instead of
     * reading anything, and its dispose step called `setContent` twice, which throws. This composes
     * the pre-`e69ff21` shape and proves a replay is visible here.
     */
    @Test
    fun theBareRememberVersionReallyDoesReplayIt() {
        rule.mainClock.autoAdvance = false
        composeSubject(bareRemember = true)
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(SUBJECT, edgePixel(), "precondition: the subject starts fully arrived")

        rule.runOnUiThread { present.value = false }
        rule.mainClock.advanceTimeBy(48)
        rule.runOnUiThread { present.value = true }
        rule.mainClock.advanceTimeBy(48)

        assertNotEquals(
            SUBJECT,
            edgePixel(),
            "a bare `remember` was expected to lose its state across the dispose and replay the " +
                "entrance. If this passes, the instrument cannot see the defect and the test above " +
                "proves nothing.",
        )
    }

    /**
     * [lkEnter] as it was before `e69ff21`, differing in the one word under test. Kept byte-for-byte
     * equivalent otherwise so the falsification isolates the `remember`/`rememberSaveable` choice
     * rather than some other difference.
     */
    @Composable
    private fun Modifier.enterWithBareRemember(travel: Dp = 10.dp): Modifier {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val progress by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = lkTween(LkMotion.Screen, 0, LkMotion.Standard),
            label = "enter-bare-remember",
        )
        val travelPx = with(LocalDensity.current) { travel.toPx() }
        return this.graphicsLayer { translationY = (1f - progress) * travelPx }
    }

    private companion object {
        const val FRAME = "frame"
        const val ENTRY_KEY = "entry"

        /** See [edgePixel]. */
        const val SETTLED_ONLY_BAND = 0.30f
        val SUBJECT = Color.Red
    }
}

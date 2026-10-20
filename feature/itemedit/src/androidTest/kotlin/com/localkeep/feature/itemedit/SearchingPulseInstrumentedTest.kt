package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * The SEARCHING panel: that it names itself, and that its dot is actually moving.
 *
 * Here rather than in a device sweep because **SEARCHING is unreachable on an emulator**. The phase
 * is `capturing && !hasFix` ([radarPhaseFor]), and `adb emu geo fix` makes the first fix land before
 * the chip's 300 ms fade-in has finished — measured 2026-08-21 across a 30 fps capture of a real
 * run, where the chip's first lit frame was already amber REFINING and no grey frame existed at all.
 * The REFINING half of the principal's 5.1–5.4 *was* verified on the device that day (a clean 1.8 s
 * cycle, `LkMotion.Breath`); this is the half that measurement could not reach.
 *
 * The pulse test compares two frames half a breath apart. That is the weakest assertion that still
 * distinguishes the thing asked for from the thing replaced: the complaint was *"a weird sparkling
 * that's happening randomly"*, and a still panel and a sparkling one both fail "the dot moves
 * smoothly", but only a still panel fails this. The shape of the motion — one wave, 2.5→10 dp,
 * eased — is [radarPulseRuns] and `LkMotionBreatheTest`'s business, and neither can see a frame.
 */
@RunWith(AndroidJUnit4::class)
class SearchingPulseInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    /** Where the chip sits: TopStart of the panel, inset by its own 14 dp padding. */
    private val cornerFraction = 0.42f

    @Test
    fun theSearchingPanelNamesItselfInTheCornerAndAnIdleOneDoesNot() {
        // One `setContent` and a state flip, not two calls: `setContent` may be used once per rule,
        // and calling it a second time throws rather than re-rendering. Cost a red run 2026-08-21.
        val phase = mutableStateOf(RadarPhase.SEARCHING)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.testTag(PANEL).size(320.dp, 220.dp)) {
                    LocationRadarField(phase = phase.value, band = null, accuracyMeters = null)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_200)
        val searching = cornerInk()

        phase.value = RadarPhase.IDLE
        // Past the chip's 180 ms exit, so this measures an idle panel and not one still leaving.
        compose.mainClock.advanceTimeBy(1_200)
        val idle = cornerInk()

        // The chip is `.decorative()`, so it has no semantics to assert on — deliberately, because
        // the sentence below the panel already says the same state in full words and a screen
        // reader announcing both says everything twice. Pixels are what is left.
        //
        // Twice, not "any more at all": the idle panel is not blank in that corner — the grid and
        // the outer accuracy ring cross it and account for every one of its pixels. Measured on the
        // API 36 emulator 2026-08-21: 2108 lit pixels with the chip against 620 without, so the
        // margin is 3.4x and the bar is set at half of what was observed rather than just above
        // the noise.
        assertTrue(
            searching > idle * 2,
            "SEARCHING should draw a chip in the corner; ink was $searching against idle $idle",
        )
    }

    @Test
    fun thePulseDotIsMovingRatherThanHoldingStill() {
        // REFINING, deliberately, even though this class is about SEARCHING.
        //
        // The first version of this test used SEARCHING and was **vacuous**: it passed with
        // `radarPulseRuns` hard-coded to `false`, because SEARCHING also runs the radar *sweep*
        // (`sweeping = phase == SEARCHING && !reduceMotion`) and the sweep crosses this same corner.
        // The test was measuring the sweep and claiming the pulse. Caught by the red-first control
        // on 2026-08-21 — which is the entire reason for running one.
        //
        // REFINING isolates it: the sweep is SEARCHING-only, the marker sits centre-panel, and the
        // accuracy ring is static at `band = null`, so the dot is the only thing in this corner that
        // can move. That `radarPulseRuns` answers SEARCHING and REFINING with one expression is
        // `RadarPulseTest`'s business, and it covers both.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.testTag(PANEL).size(320.dp, 220.dp)) {
                    LocationRadarField(phase = RadarPhase.REFINING, band = null, accuracyMeters = null)
                }
            }
        }
        // Past the chip's fade-in, so what follows is the pulse and not the entrance.
        compose.mainClock.advanceTimeBy(600)
        val first = corner(compose.onNodeWithTag(PANEL).captureToImage())
        // Half of LkMotion.Breath, which is where a breathing core is furthest from where it was.
        compose.mainClock.advanceTimeBy(900)
        val second = corner(compose.onNodeWithTag(PANEL).captureToImage())

        val moved = first.indices.count { first[it] != second[it] }
        assertTrue(
            moved > 20,
            "the pulse should differ half a breath later; only $moved of ${first.size} corner pixels changed",
        )
    }

    /** Total non-background ink in the panel's top-left corner, where the chip is drawn. */
    private fun cornerInk(): Int =
        // The panel's own fill is near-black; the chip's plate, ring and label all sit well above it.
        corner(compose.onNodeWithTag(PANEL).captureToImage()).count { luminance(it) > 70 }

    private fun corner(image: ImageBitmap): IntArray {
        val map = image.toPixelMap()
        val w = (map.width * cornerFraction).toInt()
        val h = (map.height * cornerFraction).toInt()
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) out[y * w + x] = map[x, y].toArgb()
        }
        return out
    }

    /**
     * Rec. 601 luma of a packed ARGB pixel.
     *
     * `PixelMap.get` hands back a `Color`, whose `value` is a packed ULong of *floats* and is not an
     * ARGB int — reading it as one returns 0 for every pixel and makes a rendered panel look
     * perfectly black. `toArgb()` is the conversion; this cost a red run on 2026-08-21.
     */
    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 30 + g * 59 + b * 11) / 100
    }

    private companion object {
        const val PANEL = "radar-panel-under-test"
    }
}

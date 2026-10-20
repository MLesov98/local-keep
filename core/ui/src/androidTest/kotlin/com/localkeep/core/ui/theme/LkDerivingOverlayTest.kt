package com.localkeep.core.ui.theme

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The unlock count-up, which is too fast to photograph on a calibrated device.
 *
 * Argon2id is calibrated to roughly 250ms per slot and a vault is scanned against two, so on a
 * modern phone the whole overlay is on screen for about half a second — a `screencap` round-trip is
 * slower than that, and a `uiautomator dump` is much slower. That is the design working (the count
 * snaps through on a small vault and dwells on a large one), and it is also why this is a composable
 * test rather than a device pass.
 */
@RunWith(AndroidJUnit4::class)
class LkDerivingOverlayTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theCountUpShowsWholePercentAndFloorsIt() {
        rule.setContent {
            LocalKeepTheme {
                LkDerivingOverlay(kdfSummary = "Argon2id · 3 passes · 256 MiB", progress = 0.6199f)
            }
        }
        // 61%, not 62%. Rounding up would put a number on screen ahead of the work that earns it,
        // which is the one thing this overlay is not allowed to do.
        rule.onNodeWithText("61%").assertExists()
    }

    /**
     * The ring must actually be painted. The percentage alone would still read as "something is
     * happening" if the arc silently failed to draw, and a `Canvas` that draws nothing throws
     * nothing.
     */
    @Test
    fun theRingIsDrawnInJade() {
        rule.setContent {
            LocalKeepTheme { LkDerivingOverlay(progress = 0.6f) }
        }
        val image = rule.onRoot().captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val jadeish = pixels.count { argb ->
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            // The jade ramp runs #2FBD93…#7BEFD0: green dominant, blue well ahead of red.
            g > 120 && g > r + 40 && b > r + 20
        }
        assertTrue(jadeish > 500, "Expected a jade arc; found $jadeish jade-ish pixels.")
    }

    /** Without a reporter there is no number, and the indeterminate halo is what should appear. */
    @Test
    fun withoutProgressThereIsNoPercentage() {
        rule.setContent {
            LocalKeepTheme { LkDerivingOverlay() }
        }
        rule.onNodeWithText("0%").assertDoesNotExist()
        rule.onNodeWithText("100%").assertDoesNotExist()
    }
}

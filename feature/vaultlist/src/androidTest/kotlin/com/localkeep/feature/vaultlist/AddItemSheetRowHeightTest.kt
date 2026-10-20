package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LocalKeepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two cells in one Add-sheet row are the same height, at every font scale.
 *
 * Measured on emulator-5554 (1080x2400, 420 dpi) 2026-09-08: at font scale 1.3 "Recovery phrase"
 * wraps to two lines and its cell grows to 170 px, while "GPS location" beside it stays at 126 px
 * and sits top-aligned — leaving a 55 px hole above the Card row where every other row gap is
 * 11 px. Reported from a Galaxy S24 Ultra as "more spacing between GPS location and Card than
 * there is for others".
 *
 * **Renders [AddTypeGrid], not [AddItemSheet], and that is load-bearing.** `ModalBottomSheet` draws
 * in a window of its own, which re-provides `LocalDensity` from the platform — so wrapping the
 * sheet in a `CompositionLocalProvider(LocalDensity …)` changes nothing inside it. Measured on the
 * first attempt: every cell came back 126 px at all five scales, on the very device where 1.3
 * visibly wraps. A test that cannot move the thing it varies passes for the wrong reason.
 *
 * **The width is fixed rather than the device's**, so which label wraps does not depend on the
 * screen the suite happens to run on. 360 dp is a narrow-but-ordinary phone, and the sheet's own
 * content width on the emulator this was measured on is 392 dp.
 *
 * The font-scale technique is core/ui's `FontScaleTest`: `Density(fontScale = …)` is the whole of
 * it. The scale is composition state and not a second `setContent` — that may be called once per
 * test and throws "has already set content" on the second call, which fails before reaching any
 * assertion and reads exactly like the red run this test exists to produce.
 */
@RunWith(AndroidJUnit4::class)
class AddItemSheetRowHeightTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theCoordinateAndPhraseCellsShareOneHeightAtEveryFontScale() {
        val scale = mutableFloatStateOf(SCALES.first())
        compose.setContent {
            val base = LocalDensity.current
            val current by scale
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = current),
            ) {
                LocalKeepTheme {
                    Box(Modifier.width(GRID_WIDTH)) {
                        AddTypeGrid(isPro = true, onSelect = {}, onNewFolder = {})
                    }
                }
            }
        }

        var wrappedSomewhere = false
        var pairedSomewhere = false
        val observed = mutableListOf<String>()
        SCALES.forEach { fontScale ->
            compose.runOnUiThread { scale.floatValue = fontScale }
            compose.waitForIdle()
            val bounds = TAGS.associateWith { tag -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot }
            val heights = bounds.mapValues { it.value.height }
            observed += "$fontScale -> $heights"
            // Only a pair that shares a row is compared. Once two columns cannot hold the labels the
            // grid goes one cell per row (SCL-013), and a cell alone on its row owes nobody a height.
            val sharesARow = bounds.getValue("add-type-coordinate").top == bounds.getValue("add-type-phrase").top
            if (sharesARow) {
                pairedSomewhere = true
                assertEquals(
                    heights.getValue("add-type-coordinate"),
                    heights.getValue("add-type-phrase"),
                    "at fontScale $fontScale the GPS location cell and the Recovery phrase cell " +
                        "differ — the row must hand both cells its own intrinsic height. " +
                        "Observed: $observed",
                )
            }
            // Counted only while the pair is side by side: a stacked grid lets the phrase wrap alone,
            // and that would satisfy the control below without straining the equality above.
            if (sharesARow && heights.getValue("add-type-phrase") > heights.getValue("add-type-login")) {
                wrappedSomewhere = true
            }
        }
        // The control. If no scale in the sweep makes the phrase label wrap, then the equality
        // above was never under any strain and a green run proves nothing. Widen the sweep rather
        // than deleting the assertion — this fired on the first attempt and was right to.
        assertTrue(
            pairedSomewhere,
            "the pair never shared a row at any of $SCALES, so the equality was never asserted. " +
                "Observed: $observed",
        )
        assertTrue(
            wrappedSomewhere,
            "no font scale in $SCALES made 'Recovery phrase' taller than 'Login' while the grid was " +
                "still two columns at $GRID_WIDTH, so nothing in this test was actually exercised. " +
                "Observed: $observed",
        )
    }

    private companion object {
        val SCALES = listOf(1.0f, 1.15f, 1.3f, 1.5f, 2.0f)
        val TAGS = listOf("add-type-login", "add-type-coordinate", "add-type-phrase")
        val GRID_WIDTH = 360.dp
    }
}

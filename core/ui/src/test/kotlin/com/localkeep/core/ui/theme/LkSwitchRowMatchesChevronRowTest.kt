package com.localkeep.core.ui.theme

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A switch row's title and subtitle are laid out like the chevron row's beside it in Settings.
 *
 * Settings' chevron rows draw the title in [LkText.body] and the summary in [LkText.subtitle], 3dp
 * apart. [LkSwitchRow] drew [LkText.rowTitle] over [LkText.caption] with no gap, so on a device the
 * title measured 50px against 56px, the subtitle was smaller and lighter, and the two kinds of row
 * read as different lists (D3-F-02, after a fix that matched the wrong style).
 *
 * Heights and the gap between the nodes are the observable; the expected values are read from the
 * styles the chevron row uses, so the test follows them if the ramp is retuned.
 *
 * FAILS WITHOUT THE FIX: the label measured the rowTitle line height and the gap was 0dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkSwitchRowMatchesChevronRowTest {

    @get:Rule
    val compose = createComposeRule()

    private class Measured(val labelHeight: Float, val hintHeight: Float, val gap: Float, val body: Float, val subtitle: Float)

    private fun measure(): Measured {
        var density = 0f
        var body = 0f
        var subtitle = 0f
        compose.setContent {
            density = LocalDensity.current.density
            with(LocalDensity.current) {
                body = LkText.body.lineHeight.toDp().value
                subtitle = LkText.subtitle.lineHeight.toDp().value
            }
            LocalKeepTheme {
                LkGroup {
                    LkSwitchRow(label = "Reduce haptics", checked = false, onCheckedChange = {}, first = true, hint = "Quiet taps")
                }
            }
        }
        compose.waitForIdle()
        val label = compose.onNodeWithText("Reduce haptics", useUnmergedTree = true).fetchSemanticsNode()
        val hint = compose.onNodeWithText("Quiet taps", useUnmergedTree = true).fetchSemanticsNode()
        val labelBox = label.boundsInRoot
        val hintBox = hint.boundsInRoot
        return Measured(
            labelHeight = labelBox.height / density,
            hintHeight = hintBox.height / density,
            gap = (hintBox.top - labelBox.bottom) / density,
            body = body,
            subtitle = subtitle,
        )
    }

    @Test
    fun theTitleIsTheChevronRowsTitle() {
        val m = measure()
        assertTrue(abs(m.labelHeight - m.body) < 1f, "title ${m.labelHeight}dp, the chevron row's is ${m.body}dp")
    }

    @Test
    fun theSubtitleIsTheChevronRowsSubtitle() {
        val m = measure()
        assertTrue(abs(m.hintHeight - m.subtitle) < 1f, "subtitle ${m.hintHeight}dp, the chevron row's is ${m.subtitle}dp")
    }

    @Test
    fun aGapSeparatesThemLikeTheChevronRow() {
        val m = measure()
        assertTrue(abs(m.gap - 3f) < 0.5f, "gap ${m.gap}dp, the chevron row's is 3dp")
    }
}

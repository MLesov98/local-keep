package com.localkeep.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localkeep.core.ui.theme.LkChip
import com.localkeep.core.ui.theme.LkGlyphButton
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the 48dp floor on the hand-rolled controls, and the placement rule that makes it real.
 *
 * [minTouchTarget] is `defaultMinSize`, and `defaultMinSize` only raises a dimension whose incoming
 * minimum constraint is zero. Written *after* a fixed `.size()`/`.height()` it therefore does
 * nothing at all — the chain reads like a fix, the target stays 40dp, and no test goes red. The
 * only arrangement that works is the one asserted here: the target and the click sit on an outer
 * node, the drawn box is a child that keeps its own size.
 */
@RunWith(AndroidJUnit4::class)
class MinTouchTargetTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val TARGET = "target"
        const val PLATE = "plate"
        val PLATE_SIZE = 40.dp
    }

    @After
    fun clearClipboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { ClipboardHelper.clearNow(instrumentation.targetContext) }
    }

    @Test
    fun clearNowMeetsTheMinimumTouchTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            ClipboardHelper.copy(instrumentation.targetContext, "Password", "hunter2")
        }
        // The countdown ticks against a real deadline for thirty seconds. With the test clock left
        // on auto-advance it chases that loop and never reports idle.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LocalKeepTheme {
                ClipboardCountdown()
            }
        }

        val floor = with(rule.density) { MinTouchTarget.toPx() }
        // Resolved, not hardcoded. The instrumentation process runs under the SYSTEM locale,
        // which is not the app's per-app locale — on this machine the system is fr-FR, so the
        // literal "Clear the clipboard now" matched nothing and this test failed for a reason
        // that had nothing to do with touch targets.
        val clearNow = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.core_ui_clipboard_clear_now_action)
        val bounds = rule.onNodeWithContentDescription(clearNow, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            bounds.width >= floor && bounds.height >= floor,
            "\"Clear now\" is ${bounds.width}x${bounds.height}px; the floor is ${floor}px square",
        )
    }

    @Test
    fun theTargetGrowsWithoutGrowingTheDrawnBox() {
        rule.setContent {
            LocalKeepTheme {
                Box(
                    modifier = Modifier
                        .minTouchTarget()
                        .clickable {}
                        .testTag(TARGET),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(PLATE_SIZE).testTag(PLATE))
                }
            }
        }

        val floor = with(rule.density) { MinTouchTarget.toPx() }
        val drawn = with(rule.density) { PLATE_SIZE.toPx() }
        val target = rule.onNodeWithTag(TARGET, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val plate = rule.onNodeWithTag(PLATE, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertTrue(
            target.width >= floor && target.height >= floor,
            "the target is ${target.width}x${target.height}px; the floor is ${floor}px square",
        )
        assertEquals(drawn, plate.width, 1f, "the drawn box widened with the target")
        assertEquals(drawn, plate.height, 1f, "the drawn box grew taller with the target")
    }

    /**
     * The components themselves, which is the gap this file used to have.
     *
     * Everything above pins the *pattern* and passed for months while all three of these shipped
     * the broken chain — `.size()` first, `minTouchTarget()` after it, inert. A test that documents
     * a trap without instantiating anything that could fall into it catches nothing. So: assert
     * against the real controls, by the accessibility name they are found by on a device.
     */
    @Test
    fun iconButtonMeetsTheFloor() = assertMeetsFloor("Copy password") {
        LkIconButton(icon = LkIcons.Copy, label = "Copy password", onClick = {})
    }

    /**
     * [LkGlyphButton] is the one control held to 40x48 rather than the 48dp square, and the
     * narrower axis is deliberate — see its own comment for the measurement.
     *
     * Three of these sit in a row inside a password field, and at 48dp wide each the visual gap
     * between two glyphs measured 37dp on emulator-5554, two and a half times the glyph. The width
     * came down to 40dp and the glyph inside went up to 30dp so the thing being aimed at is larger
     * than it was before, on a target of the same area (40x48 = 1920dp2, against 44x44's 1936).
     *
     * Asserted explicitly rather than folded into [assertNodeMeetsFloor], so that lowering any
     * *other* control below the square floor still fails, and so that anyone widening this one back
     * to 48 has to come here and read why it is not.
     */
    @Test
    fun glyphButtonMeetsTheRowFloor() {
        rule.setContent {
            LocalKeepTheme { LkGlyphButton(icon = LkIcons.Eye, label = "Show password", onClick = {}) }
        }
        val minWidth = with(rule.density) { 40.dp.toPx() }
        val minHeight = with(rule.density) { MinTouchTarget.toPx() }
        val bounds = rule.onNodeWithContentDescription("Show password", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            bounds.width >= minWidth && bounds.height >= minHeight,
            "LkGlyphButton is ${bounds.width}x${bounds.height}px; the floor is ${minWidth}x${minHeight}px",
        )
    }

    /**
     * The chip was short on one axis only — `.height(34.dp)` pinned the height while the width was
     * free — so a test asserting a square floor would have caught it and a test asserting width
     * alone would not. Both axes, deliberately.
     */
    @Test
    fun chipMeetsTheFloor() {
        rule.setContent {
            LocalKeepTheme {
                LkChip(text = "All", selected = true, onClick = {}, modifier = Modifier.testTag(TARGET))
            }
        }
        assertNodeMeetsFloor(rule.onNodeWithTag(TARGET, useUnmergedTree = true), "LkChip")
    }

    private fun assertMeetsFloor(label: String, content: @Composable () -> Unit) {
        rule.setContent { LocalKeepTheme { content() } }
        assertNodeMeetsFloor(
            rule.onNodeWithContentDescription(label, useUnmergedTree = true),
            label,
        )
    }

    private fun assertNodeMeetsFloor(node: SemanticsNodeInteraction, name: String) {
        val floor = with(rule.density) { MinTouchTarget.toPx() }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(
            bounds.width >= floor && bounds.height >= floor,
            "$name is ${bounds.width}x${bounds.height}px; the floor is ${floor}px square",
        )
    }
}

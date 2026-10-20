package com.localkeep.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.lkPressAndDrag
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The five things `combinedClickable` gave a row, re-provided by one replacement modifier.
 *
 * Swapping `combinedClickable` for a `pointerInput` looks like a gesture change and is not.
 * Foundation's `AbstractClickableNode` is a `DelegatingNode` that supplies tap and long-press
 * recognition, paired press interactions, focusability, TalkBack action labels and the default
 * activation — all at once. Four of those five are invisible in a screenshot and in a manual pass:
 * a row that no longer takes D-pad focus, or whose press interaction never ends, looks exactly like
 * a row that works until someone reaches for a keyboard or watches the row after touching it.
 *
 * So each is asserted separately, and two of them — scroll-cancel and the unpaired press — are
 * regressions with a specific known cause rather than hypotheticals. See the comments on each.
 */
@RunWith(AndroidJUnit4::class)
class LkPressAndDragTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    private class Recorder {
        var clicks = 0
        var longPresses = 0

        /** Fires the instant a long press registers, moved or not — the lift, not the drag. */
        var dragArms = 0
        var dragStarts = 0
        var dragCancels = 0
        var lastGrab: Offset? = null
    }

    /**
     * One row, optionally inside a scrollable list.
     *
     * @param inList wraps the row in a `LazyColumn` tall enough to scroll, which is what lets the
     *   scroll-cancel case be reproduced rather than simulated.
     */
    @androidx.compose.runtime.Composable
    private fun Subject(
        recorder: Recorder,
        inList: Boolean = false,
        dragEnabled: Boolean = true,
        onPressedChanged: (Boolean) -> Unit = {},
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        onPressedChanged(pressed)

        val row = @androidx.compose.runtime.Composable { tag: String ->
            Box(
                Modifier
                    .testTag(tag)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color.DarkGray)
                    .lkPressAndDrag(
                        interactionSource = interactionSource,
                        onClick = { recorder.clicks++ },
                        onClickLabel = "Open",
                        onLongPressRelease = { recorder.longPresses++ },
                        onLongPressLabel = "More",
                        // Explicit, not defaulted: `onDragArmed` defaults to `onDragStart` for a
                        // caller that does not care, and this file very much cares — several tests
                        // here assert `dragStarts` stays 0 across a hold that never moved. Left on
                        // the default, arming (which fires on every hold, moved or not) and starting
                        // (which fires only past touch slop) alias to the same counter and every one
                        // of those tests fails on a stationary long press alone.
                        onDragArmed = { _, _ -> recorder.dragArms++ },
                        onDragStart = { grab, _ ->
                            recorder.dragStarts++
                            recorder.lastGrab = grab
                        },
                        onDragCancel = { recorder.dragCancels++ },
                        dragEnabled = dragEnabled,
                    ),
            )
        }

        if (inList) {
            LazyColumn(Modifier.height(200.dp)) {
                items((0 until 20).toList()) { index ->
                    if (index == 0) row("row") else Box(Modifier.fillMaxWidth().height(64.dp))
                }
            }
        } else {
            Column { row("row") }
        }
    }

    @Test
    fun aTapOpensTheRow() {
        val recorder = Recorder()
        rule.setContent { Subject(recorder) }
        rule.onNodeWithTag("row").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals(1, recorder.clicks)
        assertEquals(0, recorder.longPresses)
        assertEquals(0, recorder.dragStarts)
    }

    @Test
    fun aLongPressReleasedWithoutMovingOpensTheMenu() {
        val recorder = Recorder()
        rule.setContent { Subject(recorder) }
        rule.onNodeWithTag("row").performTouchInput { longClick() }
        rule.waitForIdle()

        assertEquals(1, recorder.longPresses)
        assertEquals(0, recorder.clicks, "a long press is not also a tap")
        assertEquals(1, recorder.dragArms, "the hold still arms — that's the lift")
        assertEquals(0, recorder.dragStarts, "it never moved")
    }

    @Test
    fun scrollingWithAFingerOnARowDoesNotOpenIt() {
        // **The regression this whole test file exists for.**
        //
        // `awaitLongPressOrCancellation` returns null identically for "all pointers lifted before
        // the timeout" — a tap — and "the gesture was consumed or left the bounds", which is what a
        // LazyColumn does the moment it claims a drag for a scroll. The obvious implementation,
        // `if (result == null) onClick()`, therefore opens an item every time the user starts
        // scrolling with their finger resting on a row. That is not a rare edge: it is how most
        // people scroll a list.
        val recorder = Recorder()
        rule.setContent { Subject(recorder, inList = true) }
        rule.onNodeWithTag("row").performTouchInput { swipeUp() }
        rule.waitForIdle()

        assertEquals(0, recorder.clicks, "a scroll that started on this row must not open it")
        assertEquals(0, recorder.longPresses)
    }

    @Test
    fun aLongPressThenAMoveHandsTheDragOff() {
        // The row starts the drag and stops tracking it — see `lkPressAndDrag`'s comment. It cannot
        // keep tracking: a spring-load navigates the list under a stationary finger and disposes
        // this very row, which killed the gesture and stranded the payload on emulator-5554 on
        // 2026-08-18. So there is exactly one `onDragStart` and no end callback here at all.
        val recorder = Recorder()
        rule.setContent { Subject(recorder) }
        rule.onNodeWithTag("row").performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 120f))
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 240f))
            up()
        }
        rule.waitForIdle()

        assertEquals(1, recorder.dragArms, "the hold armed before it moved")
        assertEquals(1, recorder.dragStarts, "started once, and only once — it hands off after")
        assertEquals(0, recorder.longPresses, "movement decides: this was a drag, not a menu")
        assertEquals(0, recorder.clicks)
    }

    @Test
    fun theDragCanBeWithheldWithoutCostingTheMenu() {
        // Drag is disabled while searching at root, and that must not silently take the row's
        // context menu with it — the menu is how everything the drag does is reachable otherwise.
        val recorder = Recorder()
        rule.setContent { Subject(recorder, dragEnabled = false) }
        rule.onNodeWithTag("row").performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 240f))
            up()
        }
        rule.waitForIdle()

        assertEquals(0, recorder.dragStarts)
        assertEquals(1, recorder.longPresses)
    }

    @Test
    fun everyGesturePathEndsTheRowsPressState() {
        // `lkPressScale` reads `collectIsPressedAsState()`, which is true from Press until a
        // matching Release or Cancel arrives and has **no timeout of its own**. Drop the end
        // interaction on any path and the row scales to 0.985 and stays there for the life of the
        // screen — a row that looks permanently held down, with nothing in the logs about it.
        var pressed = false
        val recorder = Recorder()
        rule.setContent { Subject(recorder, onPressedChanged = { pressed = it }) }

        rule.onNodeWithTag("row").performTouchInput { click() }
        rule.waitForIdle()
        assertFalse(pressed, "a tap left the row pressed")

        rule.onNodeWithTag("row").performTouchInput { longClick() }
        rule.waitForIdle()
        assertFalse(pressed, "a long press left the row pressed")

        rule.onNodeWithTag("row").performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 200f))
            up()
        }
        rule.waitForIdle()
        assertFalse(pressed, "a drag left the row pressed")
    }

    @Test
    fun theRowIsStillFocusableAndStillCarriesItsLabels() {
        // Focusability is what makes `lkFocusRing` light up and what makes the row reachable by
        // keyboard, D-pad and Switch Access. It came free with `combinedClickable`; here it has to
        // be re-provided explicitly, and its absence is invisible to anyone using a touchscreen.
        val recorder = Recorder()
        rule.setContent { Subject(recorder) }

        val node = rule.onNodeWithTag("row")
        node.assertIsDisplayed()
        val config = node.fetchSemanticsNode().config
        val actions = config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        val longActions = config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)

        assertEquals("Open", actions?.label, "the TalkBack tap hint was a combinedClickable parameter")
        assertEquals("More", longActions?.label, "and so was the long-press hint")
        assertTrue(
            config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) != null,
            "the row must still report a focus state; without focusable() it reports none",
        )
    }
}

private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(
    key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
): T? = if (contains(key)) this[key] else null

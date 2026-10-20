package com.localkeep.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LkDragOverlay
import com.localkeep.core.ui.theme.LkDragSession
import com.localkeep.core.ui.theme.LkDragPayload
import com.localkeep.test.device.DeviceReadyRule
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The ghost is under the finger, in whatever box the overlay happens to live in.
 *
 * `Modifier.offset` places relative to the **parent**, and every position in the drag session is in
 * **root** coordinates. On a real screen the two differ by the status bar, so the ghost was drawn
 * that far below the finger — measured on emulator-5554 on 2026-08-18: a finger at y=1419 carried a
 * ghost whose top edge sat at y=1488. It looked like a deliberate offset rather than a bug, which
 * is why it survived a motion pass that was reviewing this very overlay frame by frame.
 *
 * The container here is deliberately inset by a large, odd amount: a test whose overlay starts at
 * the root origin passes either way and proves nothing.
 */
@RunWith(AndroidJUnit4::class)
class LkDragGhostPositionTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    private data class OnePayload(override val count: Int = 1) : LkDragPayload

    @Test
    fun theGhostSitsAtTheFingerEvenWhenTheOverlaysBoxDoesNotStartAtTheRoot() {
        val session = LkDragSession()
        val finger = Offset(500f, 900f)
        val grab = Offset(40f, 20f)
        var ghost = Rect.Zero
        var container = Rect.Zero

        rule.setContent {
            // The inset that makes this test able to fail. 64/140 dp stands in for the status bar
            // and anything else a screen scaffold puts above its root Box.
            Box(Modifier.fillMaxSize().padding(start = 64.dp, top = 140.dp)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { container = it.boundsInRoot() },
                ) {
                    LkDragOverlay(session) {
                        Box(
                            Modifier
                                .testTag("ghost")
                                .size(120.dp)
                                .height(48.dp)
                                .background(Color.Gray)
                                .onGloballyPositioned { ghost = it.boundsInRoot() },
                        )
                    }
                }
            }
        }

        rule.runOnIdle {
            session.payload = OnePayload()
            session.grabOffset = grab
            session.fingerPosition = finger
        }
        rule.waitForIdle()

        assertTrue(container.left > 0f && container.top > 0f, "the container must be inset, or this test cannot fail")

        // The ghost's left is the finger less where inside it the finger grabbed; its bottom edge sits
        // above the finger (S2-F-20 — it must not cover the target under the fingertip). Tolerance for
        // the lift's scale, which grows the ghost about its centre and so shifts its edges a little.
        val expectedLeft = finger.x - grab.x
        assertTrue(
            abs(ghost.left - expectedLeft) < 40f,
            "ghost left ${ghost.left} but the finger less the grab is $expectedLeft",
        )
        assertTrue(
            ghost.bottom < finger.y,
            "ghost bottom ${ghost.bottom} must clear the finger at y=${finger.y}",
        )
        assertTrue(
            ghost.bottom > finger.y - 200f && ghost.bottom <= finger.y - 8f,
            "ghost bottom ${ghost.bottom} should hover a fingertip's width above y=${finger.y}; " +
                "a ghost left of or below that is the container's inset (${container.left}, " +
                "${container.top}) if the root-to-parent conversion is missing",
        )
    }

    /**
     * A stationary hold arms the drag (`pendingMove = true`) but must not render the ghost.
     *
     * The ghost is a compact plate, not a clone of the row, so while the finger has not moved it
     * renders offset from — and smaller than — the row still visible underneath it: the same item
     * appears to be shown twice, which reads as "you are now dragging this" during what is still
     * just a long-press deciding between the context menu and a drag (phone report, 2026-08-19).
     * Regression for [LkDragOverlay] gating on `isMoving` rather than `isDragging`.
     */
    @Test
    fun theGhostDoesNotRenderDuringAStationaryHoldAndAppearsOnceTheFingerMoves() {
        val session = LkDragSession()

        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                LkDragOverlay(session) {
                    Box(Modifier.testTag("ghost").size(48.dp).background(Color.Gray))
                }
            }
        }

        rule.runOnIdle {
            session.payload = OnePayload()
            session.pendingMove = true
            session.fingerPosition = Offset(500f, 900f)
        }
        rule.waitForIdle()
        // useUnmergedTree: LkDragOverlay's own Box is `.decorative()`, which merges the ghost's
        // semantics into it — the plain (merged) tree would never see the "ghost" tag either way.
        rule.onNodeWithTag("ghost", useUnmergedTree = true).assertDoesNotExist()

        rule.runOnIdle { session.pendingMove = false }
        rule.waitForIdle()
        rule.onNodeWithTag("ghost", useUnmergedTree = true).assertExists()
    }
}

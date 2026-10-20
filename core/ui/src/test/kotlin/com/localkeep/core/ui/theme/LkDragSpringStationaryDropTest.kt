package com.localkeep.core.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A spring-load swaps what is under the finger, and the finger has not moved.
 *
 * Dwelling on a breadcrumb crumb navigates the list to that level while the finger is still down,
 * and whatever folder row the new list renders at those pixels becomes the hover. Letting go there
 * filed the item into that folder — a destination the user never aimed at, reached by resting on a
 * crumb (an item ended up in D1 on a device that way, after a ~0.6 s rest, so a time-only guard
 * shorter than that does not cover it). A drop after a spring has to wait for the finger to travel.
 */
class LkDragSpringStationaryDropTest {

    private object Payload : LkDragPayload {
        override val count = 1
    }

    private val finger = Offset(100f, 100f)
    private val crumb = LkDropTargetKey("crumb:root")
    private val folderD1 = LkDropTargetKey("folder:D1")
    private val folderD2 = LkDropTargetKey("folder:D2")

    private fun target(
        bounds: Rect,
        ordinal: Int,
        acceptance: LkDropAcceptance,
        springLoad: (() -> Unit)? = null,
        onDrop: (LkDragPayload) -> Unit = {},
    ) = LkRegisteredTarget(
        bounds = bounds,
        accepts = { acceptance },
        springLoad = springLoad,
        onDrop = onDrop,
        ordinal = ordinal,
    )

    /** A drag in motion, hovering a crumb at [finger], which then spring-loads to the root list. */
    private fun springedSession(delivered: MutableList<LkDropTargetKey>): LkDragSession {
        val session = LkDragSession()
        session.payload = Payload
        session.pendingMove = false
        session.onDragTo(finger)
        session.targets[crumb] = target(
            bounds = Rect(0f, 0f, 200f, 200f),
            ordinal = 1,
            acceptance = LkDropAcceptance.Neutral,
            springLoad = {},
        )
        session.hitTest(session.fingerPosition, frameTimeNanos = 0L)
        assertEquals(crumb, session.hovered)

        // The spring: the crumb leaves composition and the root list renders D1 at the same pixels,
        // D2 further down.
        session.targets.remove(crumb)
        session.targets[folderD1] = target(
            bounds = Rect(0f, 50f, 400f, 150f),
            ordinal = 2,
            acceptance = LkDropAcceptance.Accepts,
            onDrop = { delivered += folderD1 },
        )
        session.targets[folderD2] = target(
            bounds = Rect(0f, 300f, 400f, 400f),
            ordinal = 3,
            acceptance = LkDropAcceptance.Accepts,
            onDrop = { delivered += folderD2 },
        )
        session.rehitTest(frameTimeNanos = 700_000_000L)
        return session
    }

    @Test
    fun `a target that appeared under a stationary finger does not take the drop`() {
        val delivered = mutableListOf<LkDropTargetKey>()
        val session = springedSession(delivered)
        assertEquals(folderD1, session.hovered, "the re-hit test finds D1 under the unmoved finger")

        // Rest well past any settle window, finger never moving, then a frame's hit test.
        session.hitTest(session.fingerPosition, frameTimeNanos = 1_400_000_000L)
        val took = session.drop()

        assertFalse(took, "a release here must spring back")
        assertTrue(delivered.isEmpty(), "nothing may be written: delivered=$delivered")
        assertFalse(session.isDragging, "the drag still ends")
    }

    @Test
    fun `jitter inside touch slop is not movement`() {
        val delivered = mutableListOf<LkDropTargetKey>()
        val session = springedSession(delivered)
        session.moveSlopPx = 8f

        // A resting finger reports small moves; none of them is aiming anywhere.
        session.onDragTo(finger + Offset(2f, -3f))
        session.hitTest(session.fingerPosition, frameTimeNanos = 800_000_000L)
        val took = session.drop()

        assertFalse(took)
        assertTrue(delivered.isEmpty(), "delivered=$delivered")
    }

    @Test
    fun `moving onto a target after the spring delivers there`() {
        val delivered = mutableListOf<LkDropTargetKey>()
        val session = springedSession(delivered)
        session.moveSlopPx = 8f

        session.onDragTo(Offset(100f, 350f))
        session.hitTest(session.fingerPosition, frameTimeNanos = 800_000_000L)
        assertEquals(folderD2, session.hovered)
        val took = session.drop()

        assertTrue(took, "a finger that travelled to D2 has aimed at it")
        assertEquals(listOf(folderD2), delivered)
    }

    @Test
    fun `moving away and back onto the row that appeared delivers there`() {
        val delivered = mutableListOf<LkDropTargetKey>()
        val session = springedSession(delivered)
        session.moveSlopPx = 8f

        session.onDragTo(Offset(100f, 350f))
        session.hitTest(session.fingerPosition, frameTimeNanos = 800_000_000L)
        session.onDragTo(finger)
        session.hitTest(session.fingerPosition, frameTimeNanos = 900_000_000L)
        val took = session.drop()

        assertTrue(took, "once the finger has travelled, D1 is a deliberate destination")
        assertEquals(listOf(folderD1), delivered)
    }

    @Test
    fun `a drag with no spring still drops on the hovered target`() {
        val delivered = mutableListOf<LkDropTargetKey>()
        val session = LkDragSession()
        session.payload = Payload
        session.pendingMove = false
        session.targets[folderD1] = target(
            bounds = Rect(0f, 50f, 400f, 150f),
            ordinal = 1,
            acceptance = LkDropAcceptance.Accepts,
            onDrop = { delivered += folderD1 },
        )
        session.onDragTo(finger)
        session.hitTest(session.fingerPosition, frameTimeNanos = 0L)

        assertTrue(session.drop())
        assertEquals(listOf(folderD1), delivered)
    }
}

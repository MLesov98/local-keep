package com.localkeep.core.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * After a spring-load, a still finger over a folder row does not spring-load again.
 *
 * Dwelling on a breadcrumb crumb takes the list to that level; the folder row that now renders under
 * the unmoved finger became the hover and, after its own 600 ms, sprang INTO itself, so a user who
 * only rested on "back to root" ended up inside a folder (device pass 2 observation). [LkDragSession.drop]
 * already refuses delivery until the finger travels past touch slop; the spring now asks for the
 * same travel, and times its dwell from the frame that saw it.
 *
 * CONTROL: a drag that never sprang dwells on its first hover at once, as before.
 */
class LkDragSpringNeedsMovementTest {

    private object Payload : LkDragPayload {
        override val count = 1
    }

    private val finger = Offset(100f, 100f)
    private val crumb = LkDropTargetKey("crumb:root")
    private val folder = LkDropTargetKey("folder:D1")

    private fun target(ordinal: Int, acceptance: LkDropAcceptance, springLoad: (() -> Unit)?) = LkRegisteredTarget(
        bounds = Rect(0f, 0f, 400f, 200f),
        accepts = { acceptance },
        springLoad = springLoad,
        onDrop = {},
        ordinal = ordinal,
    )

    private fun springed(): LkDragSession {
        val session = LkDragSession()
        session.payload = Payload
        session.pendingMove = false
        session.moveSlopPx = 8f
        session.onDragTo(finger)
        session.targets[crumb] = target(1, LkDropAcceptance.Neutral) {}
        session.hitTest(finger, 0L)
        assertEquals(0L, session.dwellStartedAt, "the first hover dwells from its first frame")

        session.targets.remove(crumb)
        session.targets[folder] = target(2, LkDropAcceptance.Accepts) {}
        session.rehitTest(700_000_000L)
        return session
    }

    @Test
    fun `a folder that appeared under a still finger does not start a dwell`() {
        val session = springed()
        assertEquals(folder, session.hovered)
        session.hitTest(finger, 1_400_000_000L)
        assertNull(session.dwellStartedAt, "a still finger must not dwell on the row the spring revealed")
    }

    @Test
    fun `jitter inside touch slop is not movement`() {
        val session = springed()
        session.onDragTo(finger + Offset(2f, -3f))
        session.hitTest(session.fingerPosition, 1_400_000_000L)
        assertNull(session.dwellStartedAt)
    }

    @Test
    fun `moving past slop starts the dwell from that frame`() {
        val session = springed()
        session.onDragTo(finger + Offset(0f, 20f))
        session.hitTest(session.fingerPosition, 1_500_000_000L)
        assertEquals(folder, session.hovered)
        assertNotNull(session.dwellStartedAt)
        assertEquals(1_500_000_000L, session.dwellStartedAt, "the dwell is timed from the frame that saw the movement")
    }
}

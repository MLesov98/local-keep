package com.localkeep.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The line between "held" and "being dragged", which the destination affordances gate on.
 *
 * A row is picked up the instant the long press registers: [LkDragSession.payload] is set, the ghost
 * lifts, the haptic fires. But a release from *that* state is not a drop — it is the row's context
 * menu, the long press it has always had. So the bin, the "move to parent" chip and the unfile strip
 * must not appear yet; a coral bin flashing under a finger that only opened a menu reads as a threat
 * where none was meant. It was reported that way on a phone (2026-08-18), after an earlier fix had
 * moved every affordance onto the hold to answer the opposite complaint.
 *
 * [LkDragSession.isMoving] is that line, and the vault list gates the bin and the drop strips on it
 * rather than on [LkDragSession.isDragging]. This pins the predicate; the screen actually wiring the
 * affordances to it is verified on a device — an inline gate is not unit-testable here.
 */
class LkDragSessionMotionTest {

    private object Payload : LkDragPayload {
        override val count = 1
    }

    @Test
    fun `an armed but unmoved hold is holding, not moving`() {
        val session = LkDragSession()
        session.payload = Payload
        session.pendingMove = true

        assertTrue(session.isDragging, "the row is picked up — the ghost lifts from here")
        assertFalse(
            session.isMoving,
            "a release from here opens the context menu, so the bin must not have appeared yet",
        )
    }

    @Test
    fun `movement past slop turns a hold into a drag in motion`() {
        val session = LkDragSession()
        session.payload = Payload
        session.pendingMove = true
        // What the row does the moment the finger travels past touch slop.
        session.pendingMove = false

        assertTrue(session.isMoving, "now the bin and the drop strips belong on screen")
    }

    @Test
    fun `a cleared session is neither holding nor moving`() {
        val session = LkDragSession()
        session.payload = Payload
        session.pendingMove = false
        assertTrue(session.isMoving)

        session.clear()

        assertFalse(session.isDragging)
        assertFalse(session.isMoving, "nothing is carried, so nothing drag-related is on screen")
    }
}

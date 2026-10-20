package com.localkeep.feature.onboarding

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two pieces of arithmetic behind the Recovery-Kit step's fold.
 *
 * Step 3 pins its action strip unconditionally (`alwaysPinActions`, so the "Set this up later"
 * escape is never below the fold) over content that is a 24-word grid, an optional QR card, three
 * save buttons and a footnote. On emulator-5554, 2026-09-15, that left `Save as PDF` drawn as a
 * half-height sliver against the strip (`shots/195-onboarding-3.png`), and the QR — when shown —
 * rendered at a scroll offset where its top finder patterns were cut and faded
 * (`shots/197-onboarding-qr.png`).
 *
 * Neither number is a constant anyone can write down: the strip's height is a button plus a link at
 * the user's font scale in the user's language, and the QR card's is the shorter screen dimension.
 * Both are measured at runtime, so what is testable is what is *done* with the measurement — and
 * for the reserve, the guard that stops it feeding itself.
 */
class RecoveryStepFoldRoomTest {

    /**
     * The no-oscillation guard, which is the load-bearing half.
     *
     * Reserved room is content; content is what `maxValue` measures. A reserve applied to a step
     * that currently fits would create the overflow it is conditioned on, the condition would then
     * read true, and the layout would flip every frame forever.
     */
    @Test
    fun `a step that fits reserves nothing, so the reserve cannot create its own overflow`() {
        assertEquals(0.dp, scrollTailRoom(alwaysPinActions = true, scrollMaxValue = 0, actionBlockHeight = 202.dp))
    }

    @Test
    fun `a pinned step that already overflows reserves exactly what the strip measures`() {
        assertEquals(202.dp, scrollTailRoom(alwaysPinActions = true, scrollMaxValue = 640, actionBlockHeight = 202.dp))
    }

    /**
     * Every other step un-pins itself once it overflows (`StepColumn`'s `pinActions`), which moves
     * the strip into the scroll where there is nothing for it to come down on. Reserving there
     * would be blank space at the end of a step that has already solved this.
     */
    @Test
    fun `a step that un-pins when it overflows reserves nothing`() {
        assertEquals(0.dp, scrollTailRoom(alwaysPinActions = false, scrollMaxValue = 640, actionBlockHeight = 202.dp))
    }

    /**
     * The QR's requested rectangle must extend past the card on both edges. `bringIntoView` is
     * satisfied by an edge flush with the viewport, and flush is precisely where `lkScrollEdges`
     * paints its 28dp fade — so asking for the card's own bounds would put a gradient back over the
     * finder patterns one scroll position further along, which is F-50 again.
     */
    @Test
    fun `the QR asks to be revealed with clearance above and below, not flush`() {
        val rect = qrRevealRect(IntSize(880, 1040), clearancePx = 96f)

        assertTrue(rect.top <= -96f, "the request must reach above the card, or the fade lands on it")
        assertTrue(rect.bottom >= 1040f + 96f, "and below it, for the same reason")
        assertEquals(0f, rect.left)
        assertEquals(880f, rect.right)
    }

    /** The control: the rectangle still describes the card, not some unrelated region. */
    @Test
    fun `the requested rectangle grows the card rather than replacing it`() {
        val card = IntSize(880, 1040)
        val rect = qrRevealRect(card, clearancePx = 12f)

        assertEquals(-12f, rect.top)
        assertEquals(1052f, rect.bottom)
        assertEquals(card.width.toFloat(), rect.width)
        assertEquals(card.height + 24f, rect.height)
    }
}

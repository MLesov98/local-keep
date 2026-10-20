package com.localkeep.core.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drag ghost stays on screen.
 *
 * 2C-F7, on a device 2026-09-20: with the pointer near x=140 the ghost pill was drawn starting at
 * x&lt;0, so the user was carrying something they could only partly see. `LkDragOverlay` already
 * clamped the *grab point* into the ghost — which keeps the plate under the finger when the row
 * grabbed was wider than the plate — and that clamp says nothing about where the finger then goes.
 *
 * A 1080-wide overlay and a 400-wide pill throughout, which is roughly the real geometry.
 */
class LkDragGhostClampTest {

    private val overlay = IntSize(width = 1080, height = 2400)
    private val ghost = IntSize(width = 400, height = 120)
    private val origin = Offset.Zero

    /** The clear space between the ghost's bottom edge and the finger. Not the production value. */
    private val gap = 80f

    private fun topLeft(finger: Offset, grab: Offset) = ghostTopLeft(
        finger = finger,
        grab = grab,
        overlayOrigin = origin,
        overlaySize = overlay,
        ghostSize = ghost,
        fingerGap = gap,
    )

    @Test
    fun `a ghost that would hang off the left edge is pushed back on`() {
        // The finding's own geometry: finger at x=140, grabbed 200px into the plate.
        val result = topLeft(finger = Offset(140f, 900f), grab = Offset(200f, 60f))
        assertEquals(0f, result.x, "the ghost must not start left of the overlay")
        assertTrue(result.x >= 0f)
    }

    @Test
    fun `a ghost that would hang off the right edge is pushed back on`() {
        val result = topLeft(finger = Offset(1060f, 900f), grab = Offset(10f, 60f))
        assertEquals((overlay.width - ghost.width).toFloat(), result.x, "its right edge must stay inside")
    }

    @Test
    fun `the top and bottom edges get the same treatment`() {
        assertEquals(0f, topLeft(finger = Offset(500f, 10f), grab = Offset(200f, 60f)).y)
        // The ghost now rides above the finger, so a finger inside the overlay can no longer push it
        // off the bottom; only a finger below the overlay's own bottom edge can (a short overlay
        // under a tall root).
        assertEquals(
            (overlay.height - ghost.height).toFloat(),
            topLeft(finger = Offset(500f, 2700f), grab = Offset(200f, 10f)).y,
        )
    }

    @Test
    fun `a ghost already inside is left exactly where the finger puts it`() {
        // The control, and the important one: the clamp must not become a second thing that moves
        // the ghost. Horizontally it keeps the grab point; vertically it sits `gap` clear above the
        // finger, whatever the row's grab y was.
        val result = topLeft(finger = Offset(540f, 1200f), grab = Offset(200f, 60f))
        assertEquals(Offset(340f, 1200f - gap - ghost.height), result)
    }

    @Test
    fun `the ghost sits clear above the finger so the target under the finger stays readable`() {
        // S2-F-20: the plate under the fingertip covered the folder label the drag was aimed at.
        // Its bottom edge must clear the finger by the gap, wherever on the row it was grabbed.
        for (grabY in listOf(0f, 20f, 60f, 400f)) {
            val result = topLeft(finger = Offset(540f, 1200f), grab = Offset(200f, grabY))
            assertEquals(
                1200f - gap,
                result.y + ghost.height,
                "grab y=$grabY: the ghost's bottom edge must sit exactly the gap above the finger",
            )
            assertTrue(result.y + ghost.height < 1200f, "the finger must not be inside the ghost")
        }
    }

    @Test
    fun `at the top of the screen the ghost is held on screen rather than lifted off it`() {
        // Finger at y=100: lifting by gap + height would put the ghost at -100. It stays on screen,
        // under the finger, because a ghost nobody can see is the worse failure.
        assertEquals(0f, topLeft(finger = Offset(540f, 100f), grab = Offset(200f, 60f)).y)
    }

    @Test
    fun `the overlay's own origin is still subtracted`() {
        // The status-bar correction `LkDragOverlay` documents: an overlay that starts below the
        // status bar must not draw the ghost that far below the finger.
        val result = ghostTopLeft(
            finger = Offset(540f, 1200f),
            grab = Offset(200f, 60f),
            overlayOrigin = Offset(0f, 132f),
            overlaySize = overlay,
            ghostSize = ghost,
            fingerGap = gap,
        )
        assertEquals(1200f - gap - ghost.height - 132f, result.y)
    }

    @Test
    fun `nothing is clamped before either box has been measured`() {
        // First frame. Clamping into a zero-width range would pin the ghost to the corner for it.
        val unmeasured = ghostTopLeft(
            finger = Offset(140f, 900f),
            grab = Offset(200f, 60f),
            overlayOrigin = origin,
            overlaySize = IntSize.Zero,
            ghostSize = IntSize.Zero,
            fingerGap = gap,
        )
        assertEquals(Offset(-60f, 900f - gap), unmeasured)
    }

    @Test
    fun `a ghost wider than its overlay is pinned rather than inverted`() {
        // 400 wide inside 300: the slack is negative, and `coerceIn` throws on an inverted range.
        // Floored to zero, so the ghost pins to the left edge and the axis with real slack (300
        // tall minus a 120 tall plate) still clamps normally.
        val huge = ghostTopLeft(
            finger = Offset(500f, 900f),
            grab = Offset(200f, 60f),
            overlayOrigin = origin,
            overlaySize = IntSize(300, 300),
            ghostSize = ghost,
            fingerGap = gap,
        )
        assertEquals(0f, huge.x, "a negative slack must floor to zero, not throw")
        assertEquals(180f, huge.y) // 900 - 80 - 120 = 700, clamped to the 300 - 120 slack
    }
}

package com.localkeep.feature.generator

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Copying a password mounts the clipboard countdown over the bottom of the screen, and the options
 * list used to scroll by the card's whole height every time — 74dp on a device — even when nothing
 * it holds reached that far down, which only pushed the output card up under the header. It now
 * scrolls by the depth the last row actually extends into the overlay's band, and not at all when
 * the list runs past the viewport (the user scrolls it clear themselves).
 *
 * Pure arithmetic over measured pixels because a JVM test cannot lay out the shell's overlay.
 */
class GeneratorScrollsOnlyWhatTheOverlayCoversTest {

    // Viewport ends at y=1000. A 200px card plus a 30px gap above the viewport's edge puts the
    // top of the covered band at y=770.
    private val viewportBottom = 1000f
    private val overlay = 200
    private val gap = 30

    @Test
    fun contentThatEndsAboveTheBandIsNotScrolled() {
        assertEquals(0, overlayLiftScrollPx(600f, viewportBottom, overlay, gap))
    }

    @Test
    fun contentEndingExactlyAtTheBandEdgeIsNotScrolled() {
        assertEquals(0, overlayLiftScrollPx(770f, viewportBottom, overlay, gap))
    }

    @Test
    fun contentReachingIntoTheBandScrollsByExactlyTheDepth() {
        assertEquals(50, overlayLiftScrollPx(820f, viewportBottom, overlay, gap))
    }

    @Test
    fun contentRunningPastTheViewportIsNotScrolled() {
        // The last row is below the viewport, not inside the band: nothing the user is looking at
        // is the end of the list, and the overlay-sized bottom padding already lets them scroll it
        // clear. Scrolling here only pushed the output card up under the header.
        assertEquals(0, overlayLiftScrollPx(1800f, viewportBottom, overlay, gap))
    }

    @Test
    fun copyAtTheTopOfALongListOnTheDeviceDoesNotScroll() {
        // 1080x2400 @ 2.625: viewport ends at 2091, the clipboard card is 205px plus the 12dp (31px)
        // gap, and the options list runs on past the viewport (Digits is clipped at 2091, with
        // more rows below it). The old arithmetic returned the whole band, 236px = ~90dp.
        assertEquals(0, overlayLiftScrollPx(2600f, 2091f, 205, 31))
    }

    @Test
    fun contentEndingJustInsideTheViewportStillClearsTheBand() {
        // Control: the last row sits in the band and nothing below it can be scrolled to.
        assertEquals(229, overlayLiftScrollPx(999f, viewportBottom, overlay, gap))
    }
}

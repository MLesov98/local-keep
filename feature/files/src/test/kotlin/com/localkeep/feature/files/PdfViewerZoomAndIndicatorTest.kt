package com.localkeep.feature.files

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The PDF viewer's zoom arithmetic and its page counter.
 *
 * The viewer rendered every page at fit-to-width and offered nothing else: a scanned A4 contract on
 * a phone was a picture of text rather than text, and a long document scrolled as one list said
 * nothing about where in it you were. Both are answered here — a pinch that clamps at both ends, a
 * pan that cannot throw the page off the screen, and a one-based counter off the first visible item.
 *
 * Pure functions rather than a Compose test for the reason the whole module has: there is no
 * `ui-test-junit4` on this source set, and a gesture needs a real `PointerInputScope`. The same seam
 * `shouldConsumeViewerTransformGesture` uses.
 */
class PdfViewerZoomAndIndicatorTest {

    @Test
    fun fitToWidthIsTheFloor() {
        assertEquals(1f, pdfZoomScale(current = 1f, zoomChange = 0.4f), "a pinch-in cannot shrink the page")
        assertEquals(1f, pdfZoomScale(current = 1.2f, zoomChange = 0.1f), "zooming back out lands on fit-to-width")
    }

    @Test
    fun zoomHasACeiling() {
        assertEquals(4f, pdfZoomScale(current = 3f, zoomChange = 4f), "a fast pinch stops at the ceiling")
        assertEquals(4f, pdfZoomScale(current = 4f, zoomChange = 1.5f), "and stays there")
    }

    @Test
    fun aPinchInBetweenJustMultiplies() {
        assertEquals(2f, pdfZoomScale(current = 1f, zoomChange = 2f))
        assertEquals(3f, pdfZoomScale(current = 1.5f, zoomChange = 2f))
    }

    /** Tolerance, not equality: clamping a leftward flick to a zero-width range lands on -0.0f. */
    @Test
    fun thereIsNowhereToPanAtFitToWidth() {
        assertEquals(0f, clampPdfPan(panX = 900f, scale = 1f, viewportWidth = 1080), absoluteTolerance = 0f)
        assertEquals(0f, clampPdfPan(panX = -900f, scale = 1f, viewportWidth = 1080), absoluteTolerance = 0f)
    }

    /** Half the overhang each way — exactly the page's own edges, and not a pixel past them. */
    @Test
    fun panStopsAtThePageEdges() {
        assertEquals(540f, clampPdfPan(panX = 10_000f, scale = 2f, viewportWidth = 1080))
        assertEquals(-540f, clampPdfPan(panX = -10_000f, scale = 2f, viewportWidth = 1080))
        assertEquals(200f, clampPdfPan(panX = 200f, scale = 2f, viewportWidth = 1080), "inside the edges, untouched")
    }

    /** Vertical is the list's at every scale, or zooming in strands the reader on one page. */
    @Test
    fun verticalDragsStayTheListsAndHorizontalOnesPan() {
        assertTrue(pdfPanIsHorizontal(Offset(40f, 3f), scale = 2f), "a sideways drag pans the zoomed page")
        assertTrue(!pdfPanIsHorizontal(Offset(3f, 40f), scale = 2f), "a vertical drag scrolls the document")
        assertTrue(!pdfPanIsHorizontal(Offset(20f, 20f), scale = 2f), "a diagonal drag is not a pan")
    }

    /**
     * Regression for 4C.2-paging-2 (release sweep 2026-09-20): at fit-to-width there is nowhere to
     * pan — [clampPdfPan] proves it right above — yet `transformable` claimed every horizontal drag
     * anyway, so the swipe that pages from one attachment to the next did nothing at all on a PDF
     * page while it worked on a photo page.
     */
    @Test
    fun atFitToWidthAHorizontalDragIsLeftForThePager() {
        assertTrue(!pdfPanIsHorizontal(Offset(40f, 3f), scale = 1f), "nothing to pan, so nothing to claim")
        assertTrue(pdfPanIsHorizontal(Offset(40f, 3f), scale = 1.01f), "once zoomed it is the pan's again")
    }

    @Test
    fun theCounterIsOneBasedAndStaysInsideTheDocument() {
        assertEquals(1, pdfIndicatorPage(firstVisibleItemIndex = 0, pageCount = 12))
        assertEquals(3, pdfIndicatorPage(firstVisibleItemIndex = 2, pageCount = 12))
        assertEquals(12, pdfIndicatorPage(firstVisibleItemIndex = 11, pageCount = 12))
    }

    /**
     * Regression for 4C.2-pdf-4 (release sweep 2026-09-20): the pill read the list's FIRST visible
     * item, so a page with 235 px still showing at the top of the viewport outranked the page
     * filling the other 1281 px and the pill said "1 / 5" over page 2.
     */
    @Test
    fun theCounterNamesThePageShowingTheMostOfItself() {
        // The measured case: page 1 clipped to 235 px at the top, page 2 occupying 1281 px below it.
        val visible = listOf(
            PdfVisiblePage(index = 0, offset = -1046, size = 1281),
            PdfVisiblePage(index = 1, offset = 273, size = 1281),
        )

        assertEquals(1, pdfMostVisiblePageIndex(visible, viewportStart = 0, viewportEnd = 1554))
    }

    @Test
    fun aTieGoesToThePageTheReaderIsComingFrom() {
        val visible = listOf(
            PdfVisiblePage(index = 3, offset = -500, size = 1000),
            PdfVisiblePage(index = 4, offset = 500, size = 1000),
        )

        assertEquals(3, pdfMostVisiblePageIndex(visible, viewportStart = 0, viewportEnd = 1000))
    }

    @Test
    fun nothingLaidOutYetIsPageOne() {
        assertEquals(0, pdfMostVisiblePageIndex(emptyList(), viewportStart = 0, viewportEnd = 1554))
        assertEquals(1, pdfIndicatorPage(0, pageCount = 5))
    }

    /**
     * `firstVisibleItemIndex` is read a frame behind the item count when a document is swapped for a
     * shorter one in the same viewer, so the counter has to survive an index past the end rather
     * than print "14 / 12".
     */
    @Test
    fun theCounterNeverOutrunsThePageCount() {
        assertEquals(12, pdfIndicatorPage(firstVisibleItemIndex = 13, pageCount = 12))
        assertEquals(1, pdfIndicatorPage(firstVisibleItemIndex = 0, pageCount = 0))
    }
}

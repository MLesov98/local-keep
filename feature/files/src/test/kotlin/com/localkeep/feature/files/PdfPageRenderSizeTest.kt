package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S3-12, 2026-10-02: the PDF viewer capped a page bitmap's width and let its height follow the
 * page. A 390 x 20,000 pt document (a phone's full-page screenshot saved as PDF) therefore rendered
 * at 780 x 40,000 -- 31 MP, 125 MB of ARGB_8888 -- past the 100 MB at which the hardware canvas
 * throws "trying to draw too large bitmap", so the viewer crashed as the page scrolled into view.
 *
 * Pure arithmetic, so a JVM test states it exactly; the controls pin that a normal page is not
 * shrunk by the new cap.
 */
class PdfPageRenderSizeTest {

    private fun bytes(size: Pair<Int, Int>): Long = size.first.toLong() * size.second * 4L

    @Test
    fun `a very tall page stays under the canvas limit`() {
        val size = pdfPageRenderSize(pageWidth = 390, pageHeight = 20_000)

        assertTrue(
            bytes(size) < 100L * 1024 * 1024,
            "a ${size.first} x ${size.second} bitmap is ${bytes(size)} bytes, which the canvas refuses to draw",
        )
    }

    @Test
    fun `a very tall page keeps its aspect ratio`() {
        val (width, height) = pdfPageRenderSize(pageWidth = 390, pageHeight = 20_000)

        assertEquals(390.0 / 20_000.0, width.toDouble() / height, absoluteTolerance = 0.001)
    }

    @Test
    fun `an absurd page still yields a drawable bitmap`() {
        val size = pdfPageRenderSize(pageWidth = 10, pageHeight = 14_400_000)

        assertTrue(size.first >= 1 && size.second >= 1)
        assertTrue(bytes(size) < 100L * 1024 * 1024)
    }

    /** Control: an A4 page (595 x 842 pt) is still rendered at twice its size. */
    @Test
    fun `an ordinary page is rendered at twice its size`() {
        assertEquals(1190 to 1684, pdfPageRenderSize(pageWidth = 595, pageHeight = 842))
    }

    /** Control: the width cap still applies to a wide page. */
    @Test
    fun `a wide page is still narrowed to the width cap`() {
        assertEquals(1600 to 800, pdfPageRenderSize(pageWidth = 2_000, pageHeight = 1_000))
    }
}

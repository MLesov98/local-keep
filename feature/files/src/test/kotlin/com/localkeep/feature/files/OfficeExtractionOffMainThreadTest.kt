package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Office text extraction must not run during composition.
 *
 * `OfficeTextViewer` called `OfficeTextExtractor.extract` inside `remember(bytes) { … }`, so the
 * whole inflate-and-tag-strip pass ran on the main thread while the frame was being composed.
 * `MAX_OFFICE_BYTES` bounds the *compressed* bytes, not the work they expand into, so a legitimate
 * near-cap spreadsheet stalls the viewer for seconds and a crafted deck of many small, highly
 * compressible parts for far longer — with no progress and nothing to cancel. The decrypt that
 * produced those same bytes has been on `Dispatchers.IO` since it was written, for this reason.
 * Found 2026-09-13.
 *
 * Asserted on the source rather than by driving the composable: "which thread did this run on" is
 * not observable from a Compose unit test, and the property at stake is structural. Same idiom as
 * `feature/vaultlist`'s `TrashPreviewRevealGateTest`. Comment lines are dropped from the slice
 * before anything is asserted, because the fix's own comment quotes the line it replaced — a scan
 * that counted the prose would be answering a question about the comment.
 */
class OfficeExtractionOffMainThreadTest {

    private val viewer: String =
        File("src/main/kotlin/com/localkeep/feature/files/SecureMediaViewer.kt").readText()

    private val anchor = "private fun OfficeTextViewer("

    /** The viewer function's own code, comments removed, bounded to the 60 lines it fits inside. */
    private val officeViewerCode: String = run {
        val start = viewer.indexOf(anchor)
        require(start >= 0) { "anchor '$anchor' not found in SecureMediaViewer.kt" }
        viewer.substring(start)
            .lineSequence()
            .take(60)
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    /**
     * Control: the slice is the function this test thinks it is reading, it is bounded, and it runs
     * far enough to cover both the extraction call and the render at the end of the function.
     */
    @Test
    fun `reads the office viewer, whole and bounded`() {
        assertTrue(officeViewerCode.lines().size <= 60, "the slice must stay bounded")
        assertTrue(
            "OfficeTextExtractor.extract(" in officeViewerCode,
            "the extraction call is not inside the slice — the anchor or the bound is wrong",
        )
        assertTrue(
            "PlainTextViewer(" in officeViewerCode,
            "the slice stops before the end of OfficeTextViewer, so an absence proves nothing",
        )
    }

    @Test
    fun `the extraction is dispatched off the composition thread`() {
        assertFalse(
            "remember(bytes) { OfficeTextExtractor.extract" in officeViewerCode,
            "OfficeTextViewer extracts inside remember, which runs on the main thread during " +
                "composition — a large or crafted Office file stalls the viewer with no way out",
        )
        assertTrue(
            "Dispatchers.Default" in officeViewerCode,
            "the extraction has no dispatcher hop: it is CPU work and belongs on Default, the way " +
                "the decrypt that produced these bytes belongs on IO",
        )
    }
}

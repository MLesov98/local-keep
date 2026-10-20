package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The media viewer's gestures read the scale that is actually on screen.
 *
 * Two defects with one shape, both found by the F08 QA persona, 2026-08-30. The zoom shortcut never
 * touches `userScale` — it is expressed entirely by `isZoomShortcut`, and `effectiveScale` is where
 * the two meet — so anything reading the raw value while the shortcut is on believes the image is
 * at 1x when it is at 1.55x.
 *
 * One: `pointerInput(Unit)` never restarts, so its coroutine keeps the closure built on the first
 * composition, where the shortcut was off. A single tap on a zoomed image tested a stale scale of 1,
 * took the "not zoomed" branch and did nothing — and since the Toggle-zoom button is hidden while
 * zoomed, that left no way out of zoom at all.
 *
 * Two: the pan gate tested `newScale > 1f`, which on the shortcut's 1.55x is 1. A one-finger drag on
 * a visibly enlarged image moved nothing and reset the offset, by the most discoverable route into
 * zoom there is.
 *
 * Asserted on the source: the gestures need a real `PointerInputScope`, and this module has no
 * Robolectric. The same technique, and the same limitation, as `ImportCancelOrderingTest`.
 */
class ZoomableImageGestureTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/files/SecureMediaViewer.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    private fun zoomableImage(): String =
        text().substringAfter("private fun ZoomableImage(").substringBefore("\n/**")

    /**
     * Every value a long-lived gesture coroutine reads has to be re-read, not captured. Keying the
     * `pointerInput` on the scale instead would cancel and rebuild the detector mid-gesture, which
     * is a worse cure than the disease.
     */
    @Test
    fun `the gesture handlers read live state, not a first-composition capture`() {
        val body = zoomableImage()
        for (name in listOf("scaleNow", "shortcutNow", "toggleZoomNow", "toggleChromeNow", "swipeUpNow")) {
            assertTrue("rememberUpdatedState" in body && name in body, "$name must be tracked live")
        }
    }

    /** The tap handlers must not read the raw composition-local scale any more. */
    @Test
    fun `no tap handler tests the captured scale`() {
        val taps = zoomableImage().substringAfter("detectTapGestures(").substringBefore("detectViewerTransformGestures")
        assertTrue(
            "effectiveScale" !in taps,
            "a tap handler reading `effectiveScale` is reading the first composition's value. Found: $taps",
        )
    }

    /** And panning is enabled by what is on screen, not by the raw scale the shortcut never moves. */
    @Test
    fun `panning is gated on the effective scale`() {
        val transform = zoomableImage().substringAfter("detectViewerTransformGestures")
            .substringBefore("detectVerticalDragGestures")
        assertTrue(
            "nextEffective" in transform && "offset += pan" in transform,
            "the pan gate must be derived from the effective scale. Found: $transform",
        )
        assertTrue(
            "if (newScale > 1f)" !in transform,
            "the raw-scale gate is what disabled panning under the zoom shortcut",
        )
    }
}

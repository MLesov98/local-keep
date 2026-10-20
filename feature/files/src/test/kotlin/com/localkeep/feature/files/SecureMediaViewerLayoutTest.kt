package com.localkeep.feature.files

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.Attachment
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureMediaViewerLayoutTest {

    private fun attachmentsWithBlobRefs(count: Int): List<Attachment> =
        (0 until count).map { Attachment(id = UUID.randomUUID(), blobRef = "blob-$it") }

    /**
     * Regression for 9-1: the decoded-bitmap caches were additive and never shrank, so a gallery of
     * 30+ photos held one decoded bitmap per *visited* page for the life of the viewer. The keep set
     * must stay at three entries — current page plus one neighbour each side — no matter how large
     * the gallery is; without the eviction bound it grows with every page visited instead.
     */
    @Test
    fun keepWindowStaysAtThreeEntriesRegardlessOfGallerySize() {
        val attachments = attachmentsWithBlobRefs(30)

        val keep = viewerKeepWindow(attachments, currentIndex = 15)

        assertEquals(setOf("blob-14", "blob-15", "blob-16"), keep)
    }

    @Test
    fun keepWindowClampsAtTheFirstPageRatherThanIndexingNegative() {
        val attachments = attachmentsWithBlobRefs(5)

        val keep = viewerKeepWindow(attachments, currentIndex = 0)

        assertEquals(setOf("blob-0", "blob-1"), keep)
    }

    @Test
    fun keepWindowClampsAtTheLastPage() {
        val attachments = attachmentsWithBlobRefs(5)

        val keep = viewerKeepWindow(attachments, currentIndex = 4)

        assertEquals(setOf("blob-3", "blob-4"), keep)
    }

    /**
     * Regression for 4C.2-chrome / 4C.2-txt / 4C.2-zero (release sweep 2026-09-20): every non-media
     * page drew its content from the window's top edge while the viewer's title bar floated over
     * it, so the first two lines of any text, markdown, Office or PDF file were unreadable. The
     * worst case was a zero-byte file, whose entire content is the one line "This file is empty." —
     * hidden completely, leaving a screen that read as blank black. The bottom inset was already
     * there; the top one was not.
     */
    @Test
    fun aReadablePageIsHeldClearOfBothFloatingBars() {
        val insets = viewerPageInsets(FileKind.TEXT, topChromeHeight = 96.dp, bottomChromeHeight = 140.dp)

        assertEquals(96.dp, insets.top)
        assertEquals(140.dp, insets.bottom)
    }

    @Test
    fun everyKindThatIsReadRatherThanLookedAtGetsTheSameTopInset() {
        listOf(FileKind.TEXT, FileKind.OFFICE, FileKind.PDF, FileKind.PLAYABLE, FileKind.OTHER).forEach { kind ->
            assertEquals(96.dp, viewerPageInsets(kind, 96.dp, 140.dp).top, "$kind draws under the title bar")
        }
    }

    /** A photograph is meant to fill the screen, and a tap takes the chrome off it entirely. */
    @Test
    fun anImageStillDrawsEdgeToEdge() {
        val insets = viewerPageInsets(FileKind.IMAGE, topChromeHeight = 96.dp, bottomChromeHeight = 140.dp)

        assertEquals(0.dp, insets.top)
        assertEquals(0.dp, insets.bottom)
        assertEquals(0.dp, insets.horizontal)
    }

    /**
     * The other half of the image case, reported from a phone 2026-10-09: *"the < and the name of
     * the file are overlapping with the photo itself"* — for a screenshot, never for a camera photo
     * or a PDF. A screenshot has the screen's own shape, so `ContentScale.Fit` fills the whole
     * height and its status bar and buttons sit under the viewer's; a 4:3 photo letterboxes, and
     * the bars sit on black. Edge to edge is kept for the tap that hides the chrome.
     */
    @Test
    fun anImageThatWouldRunUnderTheBarsIsHeldBetweenThemWhileTheyShow() {
        val insets = viewerPageInsets(
            FileKind.IMAGE,
            topChromeHeight = 96.dp,
            bottomChromeHeight = 140.dp,
            imageUnderChrome = true,
        )

        assertEquals(96.dp, insets.top)
        assertEquals(140.dp, insets.bottom)
        assertEquals(0.dp, insets.horizontal)
    }

    @Test
    fun aScreenShapedImageRunsUnderTheBarsAndALetterboxedPhotoDoesNot() {
        // A 1080x2400 screenshot in a 1080x2274 viewport: Fit leaves no margin at all.
        assertTrue(imageRunsUnderChrome(1080, 2400, 1080f, 2274f, topBarPx = 160f, bottomBarPx = 210f))
        // A 4:3 camera photo in the same viewport letterboxes into ~732px above and below.
        assertFalse(imageRunsUnderChrome(4000, 3000, 1080f, 2274f, topBarPx = 160f, bottomBarPx = 210f))
        // A landscape screenshot (wider than tall) letterboxes too.
        assertFalse(imageRunsUnderChrome(2400, 1080, 1080f, 2274f, topBarPx = 160f, bottomBarPx = 210f))
    }

    /** Either bar alone is enough: a tall bottom bar over a margin that only clears the top one. */
    @Test
    fun anImageCollidingWithOnlyOneBarStillCounts() {
        // 1080x1800 in 1080x2274 leaves 237px above and below: clear of a 160px top bar, not of 300px.
        assertTrue(imageRunsUnderChrome(1080, 1800, 1080f, 2274f, topBarPx = 160f, bottomBarPx = 300f))
        assertFalse(imageRunsUnderChrome(1080, 1800, 1080f, 2274f, topBarPx = 160f, bottomBarPx = 200f))
    }

    /** Before the bitmap or the viewport has a size there is nothing to decide, and no inset. */
    @Test
    fun anUnmeasuredImageOrViewportIsNotInset() {
        assertFalse(imageRunsUnderChrome(0, 0, 1080f, 2274f, topBarPx = 160f, bottomBarPx = 210f))
        assertFalse(imageRunsUnderChrome(1080, 2400, 0f, 0f, topBarPx = 160f, bottomBarPx = 210f))
    }

    /**
     * The hint pill rides in the bottom bar's fade strip, not above it: above the bar it was drawn
     * over the picture (a stored screenshot's own Save button, emulator-5554 2026-10-09).
     */
    @Test
    fun theHintPillsStripIsTheBarsOwnFadeAndNothingAboveIt() {
        val bar = 180.dp
        val fade = 32.dp
        assertEquals(148.dp, hintPillStripBottom(bar, fade), "the strip ends where the bar's content begins")
        // strip top = bottom + fade = the bar's measured top edge, which is where an inset image ends.
        assertEquals(bar, hintPillStripBottom(bar, fade) + fade)
        assertEquals(0.dp, hintPillStripBottom(0.dp, fade), "an unmeasured bar never yields a negative padding")
    }

    @Test
    fun imageModifierFillsTheAvailableViewerViewport() {
        val elementNames = viewerImageModifier().foldIn(mutableListOf<String>()) { names, element ->
            names += element::class.qualifiedName.orEmpty()
            names
        }

        assertTrue(
            elementNames.any { it.contains("Fill", ignoreCase = true) },
            "Expected a fill-size modifier, got $elementNames",
        )
    }

    @Test
    fun baseScaleOneFingerHorizontalDragStaysWithPager() {
        assertFalse(
            shouldConsumeViewerTransformGesture(
                pointerCount = 1,
                effectiveScale = 1f,
                pan = Offset(120f, 4f),
                zoom = 1f,
            ),
        )
    }

    @Test
    fun pinchGestureIsConsumedForImageZoom() {
        assertTrue(
            shouldConsumeViewerTransformGesture(
                pointerCount = 2,
                effectiveScale = 1f,
                pan = Offset.Zero,
                zoom = 1.2f,
            ),
        )
    }

    @Test
    fun oneFingerDragIsConsumedWhenImageIsAlreadyZoomed() {
        assertTrue(
            shouldConsumeViewerTransformGesture(
                pointerCount = 1,
                effectiveScale = 2f,
                pan = Offset(120f, 4f),
                zoom = 1f,
            ),
        )
    }

    /**
     * The Export item was dimmed on `GalleryExporter.isSupported()`, which is API 29+, on an app
     * whose own `minSdk` is 28 — so on the oldest device it supports the control was permanently
     * grey and nothing anywhere said why. A blocked export now has a sentence attached to it.
     */
    @Test
    fun exportBelowAndroidTenGivesTheUserAReason() {
        assertEquals(
            MediaErrors.EXPORT_NEEDS_NEWER_ANDROID,
            viewerExportBlockedReason(galleryExportSupported = false),
        )
    }

    @Test
    fun exportIsNotBlockedWhenTheGalleryRouteExists() {
        assertNull(viewerExportBlockedReason(galleryExportSupported = true))
    }

    /**
     * Regression for 4C.2-zoom-3 (release sweep 2026-09-20): the single tap that left zoom ALSO
     * toggled the chrome, and the chrome was already hidden by the zoom — so the one gesture that
     * ends zoom put the image back at its normal size with no title, no close button and no action
     * bar. The double-tap path never had the bug, which is why it was reported as "double-tap works,
     * single tap does nothing".
     */
    @Test
    fun tappingAZoomedImageLeavesZoomWithoutTakingTheChromeWithIt() {
        val action = viewerSingleTapAction(effectiveScale = 1.55f)

        assertTrue(action.exitsZoom)
        assertFalse(action.togglesChrome, "the tap that ends zoom must not also hide the chrome")
    }

    @Test
    fun tappingAnUnzoomedImageStillTogglesTheChrome() {
        val action = viewerSingleTapAction(effectiveScale = 1f)

        assertFalse(action.exitsZoom)
        assertTrue(action.togglesChrome)
    }

    /**
     * Regression for 4C.2f-2 (release sweep 2026-09-20): the bottom action items were a flat
     * `height(60.dp)` holding sp text, so at `font_scale` 2.0 the caption was cut across the middle
     * — "Export" rendered as `Fxnort`, measured at 57 px of bar for roughly twice-size text. Same
     * dp-box-holding-sp-text shape as the health ring, the health category badge and the card-number
     * well, all of which this app has already had to fix.
     */
    @Test
    fun theActionBarGrowsWithTheFontItHolds() {
        assertEquals(60.dp, viewerActionItemHeight(1f), "the shipped height is unchanged at 1.0")
        assertTrue(
            viewerActionItemHeight(2f) >= 72.dp,
            "at font scale 2.0 the item was only ${viewerActionItemHeight(2f)} — a 22dp icon, a 4dp " +
                "gap, 12dp of padding and a caption at twice its line box do not fit in that",
        )
    }

    @Test
    fun theActionBarNeverShrinksBelowWhatItShipped() {
        assertEquals(60.dp, viewerActionItemHeight(0.85f))
        assertTrue(viewerActionItemHeight(1.5f) >= viewerActionItemHeight(1f))
        assertTrue(viewerActionItemHeight(2f) > viewerActionItemHeight(1.5f))
    }

    /**
     * The details sheet drew a drag handle and had no drag gesture behind it. Down puts it away;
     * up must not, or the gesture that *opens* the sheet from the image below would close it again
     * on the follow-through.
     */
    @Test
    fun draggingTheDetailsSheetDownDismissesItAndUpDoesNot() {
        assertTrue(shouldDismissDetailsSheet(40f))
        assertFalse(shouldDismissDetailsSheet(-40f))
        assertFalse(shouldDismissDetailsSheet(2f))
    }
}

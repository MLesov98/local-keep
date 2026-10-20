package com.localkeep.feature.files

import com.localkeep.core.model.Attachment
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bound on the viewer's filmstrip thumbnail cache.
 *
 * The strip used to draw the file extension — "JPG", "JPG", "JPG" — as lettering laid over the
 * photograph the viewer was showing (reported 2026-09-02: *"it overlaps with the actual image, it's
 * within the image itself"*). Drawing real thumbnails instead means decrypting and decoding, and
 * this codebase has already been bitten once by a decoded-image cache that only ever grew: a
 * thirty-page gallery ended the session holding thirty bitmaps in the native heap, and the process
 * could die from a low-memory kill that throws nothing for anything downstream to catch.
 *
 * So the fix comes with a window, and this holds it to one. The number of entries is a function of
 * [FILMSTRIP_THUMB_RADIUS] and nothing else — never of how many pages the gallery has, and never of
 * how long the user has been browsing it.
 */
class FilmstripKeepWindowTest {

    private fun attachments(n: Int): List<Attachment> = (0 until n).map {
        Attachment(id = UUID.randomUUID(), filename = "page-$it.jpg", mime = "image/jpeg", blobRef = "blob-$it")
    }

    @Test
    fun `an empty gallery keeps nothing`() {
        assertEquals(emptySet(), filmstripKeepWindow(emptyList(), 0))
    }

    @Test
    fun `the window never exceeds the radius either side of the current page`() {
        val list = attachments(200)
        val kept = filmstripKeepWindow(list, 100)
        assertEquals(FILMSTRIP_THUMB_RADIUS * 2 + 1, kept.size)
        assertTrue("blob-100" in kept, "the page being viewed must be in its own window")
        assertTrue("blob-${100 - FILMSTRIP_THUMB_RADIUS}" in kept)
        assertTrue("blob-${100 + FILMSTRIP_THUMB_RADIUS}" in kept)
    }

    /** The property the whole window exists for: a bound by construction, not by usage. */
    @Test
    fun `the cache size is bounded by the radius, not by the size of the gallery`() {
        val small = filmstripKeepWindow(attachments(40), 20).size
        val huge = filmstripKeepWindow(attachments(4_000), 2_000).size
        assertEquals(small, huge, "the window grew with the gallery — that is the leak, not the fix")
        assertTrue(huge <= FILMSTRIP_THUMB_RADIUS * 2 + 1)
    }

    @Test
    fun `the window clamps at both ends rather than running off the list`() {
        val list = attachments(4)
        assertEquals(list.map { it.blobRef }.toSet(), filmstripKeepWindow(list, 0))
        assertEquals(list.map { it.blobRef }.toSet(), filmstripKeepWindow(list, 3))
    }

    /**
     * Wider than the full-size cache's ±1 on purpose, and that difference is load-bearing: a strip
     * shows roughly six thumbnails at once, so a ±1 window would leave every one but three as the
     * lettering this change exists to remove.
     */
    @Test
    fun `the thumbnail window is wider than the full-size decode window`() {
        val list = attachments(60)
        val thumbs = filmstripKeepWindow(list, 30)
        val fullSize = viewerKeepWindow(list, 30)
        assertTrue(
            thumbs.size > fullSize.size,
            "thumbnails are ~64 KB each and the strip shows several at once; a ±1 window would " +
                "reintroduce the text badges for every page but the current one and its neighbours",
        )
        assertTrue(fullSize.all { it in thumbs }, "the strip must cover at least the decoded pages")
    }
}

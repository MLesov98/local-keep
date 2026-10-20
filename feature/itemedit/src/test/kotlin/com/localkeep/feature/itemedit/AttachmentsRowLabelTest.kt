package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The detail screen's attachments row has to say what is behind it.
 *
 * It read "Attachments" identically whether the item held nothing, one PDF, or the two photographs
 * of a passport that are the whole reason an Identity exists — reported 2026-09-03, *"I open an
 * Identity and I see Attachments, that is too broad"*.
 *
 * The load-bearing case is [`one PDF among the photos is not photos`]: "Photos" on a row that opens
 * onto a document is a promise the viewer does not keep, and it is the case a naive
 * `imageCount > 0` would get wrong.
 */
class AttachmentsRowLabelTest {

    @Test
    fun `nothing attached keeps the category name`() {
        assertEquals(AttachmentsRowLabel.Empty, attachmentsRowLabel(count = 0, imageCount = 0))
    }

    @Test
    fun `all images are photos`() {
        assertEquals(AttachmentsRowLabel.Photos(2), attachmentsRowLabel(count = 2, imageCount = 2))
        assertEquals(AttachmentsRowLabel.Photos(1), attachmentsRowLabel(count = 1, imageCount = 1))
    }

    @Test
    fun `one PDF among the photos is not photos`() {
        assertEquals(AttachmentsRowLabel.Files(3), attachmentsRowLabel(count = 3, imageCount = 2))
    }

    @Test
    fun `no images at all are files`() {
        assertEquals(AttachmentsRowLabel.Files(1), attachmentsRowLabel(count = 1, imageCount = 0))
    }

    @Test
    fun `an image count larger than the file count cannot claim photos it does not have`() {
        // Not reachable from `ItemEditViewModel`, which counts both from one list. Pinned anyway:
        // the failure it guards against — "3 photos" over two files — is the kind that reads as
        // plausible and so survives review.
        assertEquals(AttachmentsRowLabel.Photos(2), attachmentsRowLabel(count = 2, imageCount = 5))
    }

    @Test
    fun `a negative count is empty, not a label about minus one file`() {
        assertEquals(AttachmentsRowLabel.Empty, attachmentsRowLabel(count = -1, imageCount = 0))
    }
}

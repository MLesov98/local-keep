package com.localkeep.feature.files

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The sanitizer distinguishes five failure reasons and the screen used to state one sentence.
 *
 * "Metadata could not be removed" was shown for an image over the decode budget, for bytes that
 * are not the format the file claims, and for a disk that ran out — and only the first of those is
 * fixed by picking a smaller picture. The reason had already been computed by the time it was
 * thrown away.
 */
class MetadataCleanupMessageTest {

    @Test
    fun `an image over a size budget says so instead of failing generically`() {
        listOf(
            SanitizerFailureReason.SOURCE_TOO_LARGE,
            SanitizerFailureReason.DECODED_PIXELS_TOO_LARGE,
            SanitizerFailureReason.SANITIZED_OUTPUT_TOO_LARGE,
        ).forEach { reason ->
            assertEquals(
                R.string.files_edit_metadata_cleanup_too_large,
                metadataCleanupMessage(UnstrippableImageException(reason.asCleanupFailure())),
                "$reason is a size problem, and a smaller image fixes it",
            )
        }
    }

    @Test
    fun `unreadable bytes and a lying mime type are the same answer, and not the size one`() {
        listOf(
            SanitizerFailureReason.MALFORMED_INPUT,
            SanitizerFailureReason.MIME_MISMATCH,
        ).forEach { reason ->
            assertEquals(
                R.string.files_edit_metadata_cleanup_malformed,
                metadataCleanupMessage(UnstrippableImageException(reason.asCleanupFailure())),
            )
        }
        assertNotEquals(
            metadataCleanupMessage(
                UnstrippableImageException(SanitizerFailureReason.SOURCE_TOO_LARGE.asCleanupFailure()),
            ),
            metadataCleanupMessage(
                UnstrippableImageException(SanitizerFailureReason.MALFORMED_INPUT.asCleanupFailure()),
            ),
            "the whole point is that these two stop reading identically",
        )
    }

    @Test
    fun `a format this build cannot strip is not reported as a broken file`() {
        assertEquals(
            R.string.files_edit_metadata_cleanup_unsupported,
            metadataCleanupMessage(
                UnstrippableImageException(MetadataCleanupFailure.UNSUPPORTED_FORMAT),
            ),
        )
    }

    /** Control: the generic sentence still exists, for the failures that genuinely are generic. */
    @Test
    fun `a storage failure keeps the generic message`() {
        assertEquals(
            R.string.files_edit_metadata_cleanup_failed,
            metadataCleanupMessage(IOException("no space left on device")),
        )
        assertEquals(
            R.string.files_edit_metadata_cleanup_failed,
            metadataCleanupMessage(UnstrippableImageException()),
        )
    }
}

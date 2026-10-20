package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An identity-document photograph is cleaned by the fail-closed sanitizer, or not stored at all.
 *
 * S3-08, 2026-10-02: `captureIdentityPhoto` called `ExifStripper.strip`, which returns the original
 * bytes on any failure and whose own KDoc says it is "deliberately not used for privacy claims". So
 * a frame the stripper could not parse went into the vault with its Exif block — device, time,
 * thumbnail, any location — on the one route whose comment says a passport photo must never keep
 * where it was taken. The Attachments camera takes the same CameraX bytes through
 * `ExifStripper.sanitize` and refuses the capture on Failed/Unsupported; this route now does too.
 *
 * Read from source: the call sits in a ViewModel a JVM cannot build (see `ItemEditViewModelTest`).
 */
class IdentityPhotoStripIsFailClosedTest {

    private val body: String
        get() {
            val file = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt")
            assertTrue(file.isFile, "ItemEditViewModel.kt not found at ${file.absolutePath}")
            val text = file.readText()
            val start = text.indexOf("fun captureIdentityPhoto(")
            assertTrue(start > 0, "captureIdentityPhoto( not found — did it get renamed?")
            val end = text.indexOf("fun retakeLastIdentityPhoto()", start)
            assertTrue(end > start, "retakeLastIdentityPhoto( not found after it — region unbounded")
            return text.substring(start, end)
        }

    @Test
    fun `the capture goes through the fail-closed sanitizer`() {
        val capture = body
        assertTrue(
            "ExifStripper.sanitize(" in capture,
            "captureIdentityPhoto does not call ExifStripper.sanitize, the fail-closed path",
        )
        assertFalse(
            "ExifStripper.strip(" in capture,
            "captureIdentityPhoto still calls ExifStripper.strip, which stores the original bytes, " +
                "metadata included, whenever stripping fails",
        )
    }

    /** Failed and Unsupported must not reach the blob store — they have to throw into the catch. */
    @Test
    fun `a frame that cannot be cleaned is not encrypted`() {
        val capture = body
        val sanitizeAt = capture.indexOf("ExifStripper.sanitize(")
        val encryptAt = capture.indexOf("encryptToBlob(")
        assertTrue(sanitizeAt in 0 until encryptAt, "the sanitizer must run before the encrypt")
        val between = capture.substring(sanitizeAt, encryptAt)
        assertTrue(
            "SanitizerResult.Failed" in between && "SanitizerResult.Unsupported" in between &&
                "error(" in between,
            "a Failed or Unsupported result is not turned into a capture failure:\n$between",
        )
    }
}

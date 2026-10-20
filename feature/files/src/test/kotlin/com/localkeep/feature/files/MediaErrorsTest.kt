package com.localkeep.feature.files

import com.localkeep.core.crypto.AeadAuthException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MediaErrorsTest {

    /** The real messages Android puts on the exceptions this module catches. */
    private val leaky = listOf(
        FileNotFoundException(
            "/data/user/0/com.localkeep.foss/files/blobs/a1b2c3d4.enc: open failed: ENOENT (No such file or directory)",
        ),
        SecurityException(
            "Permission Denial: opening provider content://media/external/images/media/1234 from ProcessRecord",
        ),
        IOException("/data/user/0/com.localkeep.foss/cache/exif-strip123.jpg: No space left on device"),
        IllegalStateException("vault is locked - no session DEK available"),
    )

    /**
     * [MediaErrors] moved from `String` constants to `@StringRes Int` (i18n extraction,
     * 2026-07-30), so the actual text now lives in `res/values/strings.xml`, not in compiled
     * Kotlin — reflecting over [MediaErrors]'s fields finds only resource ids. This is a pure JVM
     * `kotlin.test`, with no Compose/Robolectric runtime to call `stringResource` from, so — same
     * approach as `UnlockSubtitleTest` — it reads the base (English) resource file directly.
     */
    private val allMessages: List<String> = run {
        val xml = File("src/main/res/values/strings.xml").readText()
        Regex("""<string name="files_error_[a-z_]+">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()
    }

    /** The same file, keyed by resource name, for the assertions that are about one string. */
    private val englishStrings: Map<String, String> = run {
        val xml = File("src/main/res/values/strings.xml").readText()
        Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `opening a file never repeats the exception text`() {
        for (e in leaky) assertEquals(MediaErrors.OPEN_FAILED, MediaErrors.openFailed(e))
    }

    @Test
    fun `opening a video never repeats the exception text`() {
        for (e in leaky) assertEquals(MediaErrors.VIDEO_OPEN_FAILED, MediaErrors.videoOpenFailed(e))
    }

    @Test
    fun `restoring to the gallery never repeats the exception text`() {
        for (e in leaky) assertEquals(MediaErrors.RESTORE_FAILED, MediaErrors.restoreFailed(e))
    }

    @Test
    fun `a failed authentication tag is reported as corruption, not as a failed open`() {
        val tampered = AeadAuthException("tag mismatch")
        assertEquals(MediaErrors.CORRUPTED_FILE, MediaErrors.openFailed(tampered))
        assertEquals(MediaErrors.CORRUPTED_VIDEO, MediaErrors.videoOpenFailed(tampered))
        assertEquals(MediaErrors.CORRUPTED_FILE, MediaErrors.restoreFailed(tampered))
    }

    @Test
    fun `no user-facing message carries a path, a provider URI, or a class name`() {
        assertFalse(allMessages.isEmpty())
        for (message in allMessages) {
            assertFalse(message.contains("/"), "leaks a path or URI: $message")
            assertFalse(message.contains("Exception"), "leaks a class name: $message")
            assertFalse(message.contains("DEK"), "leaks internal jargon: $message")
        }
    }

    /**
     * A page whose decrypt or decode failed used to be a dead end: the failure was cached in
     * `pageErrors` and the same effect that gates a re-attempt read that cache, so one transient
     * I/O hiccup made the page permanently blank until the viewer was reopened. The retry offered
     * now is only for failures a second attempt could actually change.
     */
    @Test
    fun `an authentication failure is not worth retrying, and an io failure is`() {
        assertFalse(
            MediaErrors.isRetryable(AeadAuthException("GCM authentication failed")),
            "the bytes are what they are — reading them again gives the same answer",
        )
        assertTrue(MediaErrors.isRetryable(IOException("read failed")))
        assertTrue(MediaErrors.isRetryable(IllegalStateException("descriptor closed")))
    }

    /**
     * Both non-crypto image failures used to be reported with one three-word title, "Can't display
     * this image", which restates the blank frame the user is already looking at. They are not the
     * same event: one is a format this device has no codec for, the other is a decode that ran the
     * heap out, and a user who is told which one it is knows whether a smaller copy would help.
     */
    @Test
    fun `a missing codec and a decode that ran out of memory are different failures`() {
        assertNotEquals(MediaErrors.IMAGE_UNDECODABLE, MediaErrors.IMAGE_TOO_LARGE)
        assertEquals(MediaErrors.IMAGE_UNDECODABLE, MediaErrors.imageDecodeFailed(null))
        assertEquals(MediaErrors.IMAGE_TOO_LARGE, MediaErrors.imageDecodeFailed(OutOfMemoryError()))
    }

    /**
     * The sentence naming a way out existed all along — it sat behind a `ZoomableImage(bitmap =
     * null)` branch the single call site could not reach, so the failure the user actually hits
     * offered nothing. Every message the viewer can put on a failed image now has to carry both
     * halves: what went wrong, and the one thing that still works.
     */
    @Test
    fun `an image that cannot be shown still names something the user can do`() {
        for (key in listOf("files_error_image_undecodable", "files_error_image_too_large")) {
            val message = englishStrings.getValue(key)
            assertTrue(
                message.trimEnd().dropLast(1).contains('.'),
                "one sentence, so it names a cause or a remedy but not both: $message",
            )
            assertTrue(message.contains("Export it to your gallery"), "no remedy: $message")
        }
    }

    /**
     * Regression for 30-1. An attach or a save on a device with no room left threw a plain
     * `IOException` naming ENOSPC, fell into the generic arm, and was reported as "It may be too
     * large, or the app that provided it may no longer have it" — a cause that is not the cause, on
     * a failure that repeats identically for as long as the user is willing to press the button.
     * `ItemEditViewModel.saveFailureMessage` already splits the same condition out of the item
     * editor's save; this is the same split for the files module.
     *
     * **Revert either `attachFailed` or `saveFailed` to return its generic constant unconditionally
     * and the first test goes red.** The second is the control: everything else must keep the
     * generic sentence, which does offer a retry and is right to.
     */
    @Test
    fun `a disk with no room left is not reported as a file that was too large`() {
        // The exact shapes the platform produces: no dedicated exception type exists for ENOSPC,
        // so the errno reason arrives in the message and `Files.move` wraps it one level deeper.
        val enospc = listOf(
            IOException("write failed: ENOSPC (No space left on device)"),
            IOException("/data/user/0/com.localkeep.foss/files/blobs/tmp.enc: No space left on device"),
            java.nio.file.FileSystemException("/data/vault.enc.tmp", null, "No space left on device"),
        )
        for (e in enospc) {
            assertEquals(MediaErrors.OUT_OF_SPACE, MediaErrors.attachFailed(e), "attach: $e")
            assertEquals(MediaErrors.OUT_OF_SPACE, MediaErrors.saveFailed(e), "save: $e")
        }
        assertNotEquals(MediaErrors.OUT_OF_SPACE, MediaErrors.ATTACH_FAILED)
        assertNotEquals(MediaErrors.OUT_OF_SPACE, MediaErrors.SAVE_FAILED)
    }

    @Test
    fun `every other attach or save failure keeps the generic sentence`() {
        val transient = listOf(
            IOException("read failed"),
            FileNotFoundException("/data/user/0/com.localkeep.foss/files/blobs/a1b2c3d4.enc"),
            SecurityException("Permission Denial: opening provider content://media/external/images/media/1234"),
            IllegalStateException("vault is locked - no session DEK available"),
            // Not an IOException, so the message alone must not be enough to reclassify it.
            RuntimeException("No space left on device"),
        )
        for (e in transient) {
            assertEquals(MediaErrors.ATTACH_FAILED, MediaErrors.attachFailed(e), "attach: $e")
            assertEquals(MediaErrors.SAVE_FAILED, MediaErrors.saveFailed(e), "save: $e")
        }
    }

    /**
     * The whole point of splitting this failure out is the remedy, so the sentence has to carry
     * one. A bare retry is what the generic message already offered and what does not work here.
     */
    @Test
    fun `the out-of-space message names freeing space as what to do`() {
        val message = englishStrings.getValue("files_error_out_of_space")
        assertTrue(message.contains("Free up space"), "no remedy: $message")
    }
}

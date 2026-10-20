package com.localkeep.feature.files

import androidx.annotation.StringRes
import com.localkeep.core.crypto.AeadAuthException

/**
 * The user-facing text for a failed media operation, and the only place this module decides it.
 *
 * None of these carry `e.message`. That text is written for a log, and every failure reachable here
 * puts something into it that the user did not ask to see: a missing blob throws
 * `FileNotFoundException` whose message is the app-private path plus the blob id, the
 * `ContentResolver` failures put a full provider URI on screen, and a mid-operation auto-lock
 * surfaces internal jargon about a session DEK. All of it is rendered inline *and* announced by
 * TalkBack, so there is nothing to gain by repeating it and a path to leak by doing so.
 *
 * The distinction worth keeping is the one the user can act on: a blob that fails its
 * authentication tag is corrupt, and saying so is not the same as saying an open failed.
 *
 * Every message here is a `@StringRes Int`, not a `String` — the same reason `GeneratorViewModel`
 * exposes one: this catalogue feeds `ViewModel` state directly (`AttachmentPickerViewModel`,
 * `FileItemEditViewModel`) as well as plain composable state, and a `ViewModel` must not resolve a
 * string itself (no injected `Context`). Callers resolve with `stringResource` at composition.
 */
internal object MediaErrors {

    @StringRes val CORRUPTED_FILE = R.string.files_error_corrupted_file

    @StringRes val CORRUPTED_VIDEO = R.string.files_error_corrupted_video

    @StringRes val OPEN_FAILED = R.string.files_error_open_failed

    @StringRes val VIDEO_OPEN_FAILED = R.string.files_error_video_open_failed

    @StringRes val RESTORE_FAILED = R.string.files_error_restore_failed

    @StringRes val IMPORT_FAILED = R.string.files_error_import_failed

    @StringRes val CAPTURE_FAILED = R.string.files_error_capture_failed

    @StringRes val SAVE_FAILED = R.string.files_error_save_failed

    @StringRes val PDF_OPEN_FAILED = R.string.files_error_pdf_open_failed

    @StringRes val TEXT_TOO_LARGE = R.string.files_error_text_too_large

    @StringRes val IMAGE_UNDECODABLE = R.string.files_error_image_undecodable

    @StringRes val IMAGE_TOO_LARGE = R.string.files_error_image_too_large

    @StringRes val EXPORT_NEEDS_NEWER_ANDROID = R.string.files_error_export_needs_newer_android

    @StringRes val OUT_OF_SPACE = R.string.files_error_out_of_space

    @StringRes val ATTACH_FAILED = R.string.files_attachments_error_attach_failed

    /**
     * Why an attach or a save could not write.
     *
     * A disk with no room left fails identically on every retry, and the generic messages here name
     * causes that are not it — "It may be too large, or the app that provided it may no longer have
     * it" for an attach, "your vault may have locked while you were editing" for a save. Both send
     * the user back to press the same button, and neither mentions the one thing that would work.
     * `ItemEditViewModel.saveFailureMessage` already splits the same failure out of the item
     * editor's save; this is the files module's half of it.
     */
    @StringRes
    fun attachFailed(e: Throwable): Int = if (e.isOutOfSpace()) OUT_OF_SPACE else ATTACH_FAILED

    @StringRes
    fun saveFailed(e: Throwable): Int = if (e.isOutOfSpace()) OUT_OF_SPACE else SAVE_FAILED

    /**
     * Whether [e] is the disk-full failure a blob write or a vault commit surfaces on ENOSPC.
     *
     * The platform gives that condition no dedicated exception type — it arrives as a plain
     * [java.io.IOException] (or, from `Files.move`, a [java.nio.file.FileSystemException], itself
     * an IOException) whose message names the errno reason, so the message is what there is to
     * match on. Read, never rendered: this class's whole rule is that an exception's text does not
     * reach the screen. Duplicated from `:feature:itemedit`'s private predicate rather than shared,
     * because that one is `internal` and `internal` is module-scoped; a third copy would be the
     * point at which it wants a home in `:core:common`.
     */
    private fun Throwable.isOutOfSpace(): Boolean =
        this is java.io.IOException &&
            message?.contains("No space left", ignoreCase = true) == true

    /**
     * Why an image that decrypted cleanly still could not be shown.
     *
     * Both outcomes used to land on the same bare title — *Can't display this image* — which named
     * the symptom the user was already looking at and left them nothing to do about it. They are
     * not one failure and they do not have one explanation. [cause] is null when `BitmapFactory`
     * handed back nothing: the bytes are intact and the file is fine, this device simply has no
     * codec for that format. It is an [OutOfMemoryError] when the decode ran the heap out, which is
     * a statement about the image's size and says nothing about its format.
     *
     * Neither is offered a retry, and neither should be: the same codec decoding the same bytes on
     * the same device returns the same answer. What both get instead is the one thing that does
     * work — a copy outside the vault, opened by an app that can read it.
     */
    @StringRes
    fun imageDecodeFailed(cause: Throwable?): Int =
        if (cause is OutOfMemoryError) IMAGE_TOO_LARGE else IMAGE_UNDECODABLE

    @StringRes
    fun pdfOpenFailed(e: Throwable): Int =
        if (e is AeadAuthException) CORRUPTED_FILE else PDF_OPEN_FAILED

    /**
     * Whether a second attempt could plausibly succeed.
     *
     * An [AeadAuthException] is a property of the stored bytes — truncated, tampered with, written
     * under a key that no longer applies — and reading them again produces the same answer. Any
     * other failure here came from the I/O around them, which is exactly the kind of thing that
     * goes away. Offering "Try again" on the first would be offering nothing.
     */
    fun isRetryable(e: Throwable): Boolean = e !is AeadAuthException

    @StringRes
    fun openFailed(e: Throwable): Int =
        if (e is AeadAuthException) CORRUPTED_FILE else OPEN_FAILED

    @StringRes
    fun videoOpenFailed(e: Throwable): Int =
        if (e is AeadAuthException) CORRUPTED_VIDEO else VIDEO_OPEN_FAILED

    @StringRes
    fun restoreFailed(e: Throwable): Int =
        if (e is AeadAuthException) CORRUPTED_FILE else RESTORE_FAILED
}

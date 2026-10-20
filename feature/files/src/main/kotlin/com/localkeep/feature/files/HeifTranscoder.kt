package com.localkeep.feature.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Re-encodes a HEIC/HEIF still as JPEG so [ExifStripper] can strip it.
 *
 * `ExifInterface.saveAttributes` writes JPEG, PNG and WebP and nothing else, so a HEIF's Exif can
 * only be removed by rewriting the container — see the note on [ExifStripper]. Decoding to a
 * `Bitmap` and recompressing sidesteps that entirely: a `Bitmap` is pixels, so **every** tag is
 * gone by construction rather than by a list of tags someone remembered to enumerate. That is a
 * stronger guarantee than the JPEG path gives, not a weaker one.
 *
 * The cost is real and is the reason this is not the default for every format: the image is
 * re-encoded, so it loses a generation of quality, and a 12 MP still is briefly held twice in
 * memory. Both are acceptable for the case this exists to serve — a photographed document, where
 * legibility is the only requirement — and neither is imposed on a format that can be stripped in
 * place.
 *
 * Platform decode of HEIF has been available since API 28, which is this app's `minSdk`, so there
 * is no version fallback here. A device whose decoder still refuses returns null, and the caller
 * refuses the import rather than storing bytes it cannot clean.
 */
object HeifTranscoder {
    /** Quality matches the in-app capture path (`CaptureToVault.imageProxyToUprightJpeg`), so a
     *  photographed document reads the same whichever way it entered the vault. */
    private const val JPEG_QUALITY = 92

    /**
     * Decodes at the widest colour depth this device can actually allocate right now.
     *
     * The allocation attempt *is* the test. Predicting it from `Runtime.freeMemory()` was tried and
     * removed: the prediction moved with whatever else the app had allocated, so the same 50 MP HEIC
     * was strippable one minute and not the next. `runCatching` catches `OutOfMemoryError` — it
     * catches `Throwable`, which is the point here rather than an accident — so a failed attempt
     * falls through to the narrower config, and a failure at every rung returns null and is refused
     * upstream. Nothing is half-written on the way, because nothing is written until a decode
     * succeeds.
     */
    private fun decodeWidestThatFits(bytes: ByteArray): Bitmap? {
        for (config in DECODE_CONFIG_LADDER) {
            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = config },
                )
            }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return null
    }

    /** JPEG bytes, or null if the platform could not decode [bytes] — never partial output. */
    fun toJpeg(bytes: ByteArray): ByteArray? {
        if (bytes.size.toLong() > MAX_SOURCE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth.toLong()
        val height = bounds.outHeight.toLong()
        // Arithmetic only — the former flat 25 MP cap made this return null for every 50 MP HEIC,
        // which is what a Samsung shoots by default, so the one format that *has* to come through
        // here was the one it always turned away. Whether the bitmap fits is decided by trying.
        if (width <= 0L || height <= 0L || width > Long.MAX_VALUE / height) return null
        if (!isAdmissiblePixelCount(width * height)) return null
        val bitmap = decodeWidestThatFits(bytes) ?: return null
        return try {
            val out = ByteArrayOutputStream(bytes.size)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            out.toByteArray().takeIf { it.size.toLong() <= MAX_SANITIZED_OUTPUT_BYTES }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * Thrown when an image cannot be stored with the privacy guarantee the import path claims.
 *
 * Raised rather than falling back to storing the original: the alternative is a vault holding a
 * photograph whose GPS the user was told had been removed, and there is no way for them to find
 * out otherwise. Refusing is visible; a silent pass-through is not.
 */
class UnstrippableImageException(
    /**
     * Why, in the terms the person looking at the screen can act on.
     *
     * The sanitizer already distinguishes five reasons and every one of them used to arrive at the
     * UI as the same sentence, so "your photo is bigger than the decode budget" and "these bytes
     * are not the image the file claims" were indistinguishable — and only one of them is worth
     * retrying with a smaller picture. [MetadataCleanupFailure.UNKNOWN] is the honest answer for
     * the call sites that genuinely do not know, which is most of them.
     */
    val failure: MetadataCleanupFailure = MetadataCleanupFailure.UNKNOWN,
) : Exception("image metadata could not be removed")

/** What to tell the user when metadata cleanup refuses. See [UnstrippableImageException.failure]. */
enum class MetadataCleanupFailure {
    /** Over the source-size, decoded-pixel or output-size budget. Retryable with a smaller image. */
    TOO_LARGE,

    /** Undecodable, or not the format the mime type claims. Nothing about the image will change. */
    MALFORMED,

    /** A format this build cannot strip in place. Not a fault in the file. */
    UNSUPPORTED_FORMAT,

    /** Everything else, including the storage failures around the sanitizer. */
    UNKNOWN,
}

/** The user-facing cause carried by a [SanitizerResult.Failed]. */
internal fun SanitizerFailureReason.asCleanupFailure(): MetadataCleanupFailure = when (this) {
    SanitizerFailureReason.SOURCE_TOO_LARGE,
    SanitizerFailureReason.DECODED_PIXELS_TOO_LARGE,
    SanitizerFailureReason.SANITIZED_OUTPUT_TOO_LARGE,
    -> MetadataCleanupFailure.TOO_LARGE
    SanitizerFailureReason.MALFORMED_INPUT,
    SanitizerFailureReason.MIME_MISMATCH,
    -> MetadataCleanupFailure.MALFORMED
}

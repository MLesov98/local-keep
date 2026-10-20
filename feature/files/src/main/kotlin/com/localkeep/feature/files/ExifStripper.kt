package com.localkeep.feature.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Privacy-sensitive limits for the in-memory still-image sanitizer. */
const val MAX_SOURCE_BYTES = 64L * 1024L * 1024L
const val MAX_SANITIZED_OUTPUT_BYTES = 64L * 1024L * 1024L

/**
 * Absolute ceiling on a decode, whatever the heap says. Nothing a phone camera produces comes near
 * it; it exists so a hostile header claiming billions of pixels is refused on arithmetic rather
 * than on how much memory happened to be free.
 */
const val MAX_DECODED_PIXELS = 200_000_000L

/** ARGB_8888 — what `BitmapFactory` allocates per pixel unless told otherwise. */
private const val BYTES_PER_DECODED_PIXEL = 4L

/** RGB_565 — half the memory, no alpha channel, 16-bit colour. */
private const val BYTES_PER_DECODED_PIXEL_565 = 2L

/**
 * Quality for the one re-encode this file performs — baking a JPEG's Exif orientation into its
 * pixels. Matches [HeifTranscoder] and the in-app capture path so a photographed document reads
 * the same whichever way it entered the vault.
 */
private const val UPRIGHT_JPEG_QUALITY = 92

/**
 * How many pixels this device can afford to decode *right now*.
 *
 * This used to be a flat `MAX_DECODED_PIXELS = 25_000_000`, and that number was the bug. Only HEIF
 * is ever decoded here — JPEG/PNG/WebP are byte surgery — and HEIF is precisely the format a modern
 * Samsung shoots by default, at 50 MP: 8160x6120 = 49,939,200 pixels. So the cap refused the exact
 * images it was most needed for. Measured on 2026-08-25: `sanitize` returned
 * `Failed(DECODED_PIXELS_TOO_LARGE)` and `HeifTranscoder.toJpeg` returned null for a real 50 MP
 * HEIC, which surfaced to the user as "Metadata could not be removed" with no way forward.
 *
 * A constant cannot be right here, because the thing it is guarding is not a property of the image
 * — it is a property of the device. 50 MP at 4 bytes a pixel is ~200 MB, which a flagship's heap
 * swallows and a low-RAM API 28 device cannot survive. So ask the heap. A device that can do the
 * work does it; a device that genuinely cannot still refuses, and refusing is still fail-closed.
 *
 * Half of what is *currently* free, not half of `maxMemory`: the caller is holding the encoded
 * source bytes and will hold the re-encoded JPEG too, so the budget has to come out of what is
 * actually left, not out of the theoretical ceiling.
 */
/**
 * Whether [pixels] is a sane number of pixels to attempt at all.
 *
 * Deliberately **not** a memory prediction, and that is the second thing this fix got wrong. The
 * first attempt raised the flat 25 MP cap; the second budgeted it against free heap. Both were
 * guesses, and the budget was worse than the constant in one respect: it moved. The same 50 MP HEIC
 * was admitted at 21:02 with 140 MB free and refused minutes later with 97 MB free (measured
 * 2026-08-25). A feature that strips your photo's GPS depending on what the app happened to have
 * allocated a moment earlier is not a feature, and no factor tuning fixes it — only removing the
 * prediction does.
 *
 * So this answers arithmetic only: not zero, not overflowing, not absurd. Whether the bitmap
 * actually fits is answered by trying to allocate it and catching the failure, which is the one
 * mechanism that cannot be wrong about this device at this moment. See [HeifTranscoder.toJpeg].
 */
fun isAdmissiblePixelCount(pixels: Long): Boolean = pixels in 1L..MAX_DECODED_PIXELS

/**
 * Bitmap configs to try for a decode, widest colour first.
 *
 * A 50 MP still is ~200 MB at [BYTES_PER_DECODED_PIXEL] and ~100 MB at
 * [BYTES_PER_DECODED_PIXEL_565], and an ordinary Android heap — 192 MB on the emulator here, around
 * 256 MB on a flagship without `largeHeap` — cannot hold the first. RGB_565 is what makes a 50 MP
 * photo strippable at all, and it costs less than it looks: these are camera stills with no alpha,
 * so the dropped byte is genuinely unused, and the loss is colour depth (8 bits per channel down to
 * 5-6-5) which can band a smooth gradient like a clear sky. Full colour is therefore tried first
 * and 565 is only the fallback — but 565 keeps **full resolution**, which is why it beats
 * downsampling, and it beats the alternative of not being able to remove the metadata at all.
 */
internal val DECODE_CONFIG_LADDER = listOf(
    android.graphics.Bitmap.Config.ARGB_8888,
    android.graphics.Bitmap.Config.RGB_565,
)

enum class SanitizerFailureReason {
    SOURCE_TOO_LARGE,
    DECODED_PIXELS_TOO_LARGE,
    SANITIZED_OUTPUT_TOO_LARGE,
    MALFORMED_INPUT,
    MIME_MISMATCH,
}

sealed interface SanitizerResult {
    data class Success(val bytes: ByteArray, val mime: String) : SanitizerResult
    data class Failed(val reason: SanitizerFailureReason) : SanitizerResult
    data class Unsupported(val format: String) : SanitizerResult
}

/**
 * Strips identifying metadata from an in-memory image before it is encrypted into the vault (§5.5).
 *
 * Stripping happens on the way **in**, not on the way out. Metadata that never enters the vault
 * cannot leak through an export path someone forgets to instrument later; a stripper on the share
 * path has to be reapplied at every exit, and there is no single place to check that it was.
 *
 * GPS, EXIF, XMP and IPTC metadata are removed by the product path. There is no durable
 * keep-metadata preference.
 *
 * Format support is deliberately narrow, and the boundary is a real one rather than an
 * ordering of work:
 *
 * | Format | Handling |
 * |--------|----------|
 * | JPEG   | Marker filter, entirely in memory |
 * | PNG    | Chunk filter — drops `eXIf`, `tEXt`, `zTXt`, `iTXt`, `tIME` |
 * | WebP   | RIFF chunk filter — drops `EXIF` and `XMP `, and clears the matching `VP8X` flags |
 * | HEIC/HEIF | **Cannot be stripped in place** — [needsTranscodeToStrip] returns true; see below |
 *
 * HEIF is ISO base media format. Removing an Exif item from a HEIF container means rewriting
 * `meta`/`iloc` and repairing
 * every item offset — the same class of surgery as an MP4, on a file the user usually cannot
 * re-download. So this object does not attempt it, and [strip] returns HEIF bytes unchanged.
 *
 * That is not the end of the story, and it used to be. HEIC is the default still format on Samsung
 * since Android 9 and an option on Pixel, so "unsupported" here described the *likely* gallery
 * selection rather than an edge case, and a photograph of a passport went into the vault with its
 * GPS intact. [needsTranscodeToStrip] exists so a caller can ask the question before it stores anything:
 * [PhotoPickerImport] re-encodes such an image to JPEG (via [HeifTranscoder]) and strips that, and
 * refuses the import outright if the re-encode fails. The quality cost of the re-encode is the
 * price of the claim being true.
 *
 * Every product path is pure in-memory byte surgery before encryption; no plaintext scratch/cache
 * file is created.
 *
 * [sanitize] is fail-closed: a truncated, malformed, oversized, or ambiguous image never returns
 * the original bytes as a successful cleanup. [strip] remains as a compatibility helper for old
 * non-product callers and is deliberately not used for privacy claims.
 */
object ExifStripper {
    /** What [strip] recognised the bytes as. Sniffed from the magic bytes, never from a filename. */
    internal enum class Format { JPEG, PNG, WEBP, HEIF, UNSUPPORTED }

    /**
     * Whether these bytes are a metadata-carrying image that [strip] would hand straight back —
     * i.e. HEIF — and which therefore has to be re-encoded before it can be stripped at all.
     *
     * `strip` is deliberately total: it never emits a half-rewritten image, so a caller cannot tell
     * a successful strip from a pass-through by looking at what comes back. This is the question
     * that distinguishes them, and any caller whose copy promises the user that metadata was
     * removed has to ask it first.
     *
     * Narrower than "everything `strip` cannot handle", on purpose. `Format.UNSUPPORTED` is mostly
     * things that are not stills at all, or formats with no Exif to lose; re-encoding those to JPEG
     * would turn an animated GIF into one frame in the name of removing metadata it never had.
     * HEIF is the one format that is a camera's ordinary output, does carry GPS, and cannot be
     * rewritten in place.
     */
    fun needsTranscodeToStrip(bytes: ByteArray): Boolean = formatOf(bytes) == Format.HEIF

    /** Strict product boundary: success means the returned bytes were actually sanitized. */
    fun sanitize(
        imageBytes: ByteArray,
        mimeHint: String? = null,
    ): SanitizerResult {
        if (imageBytes.size.toLong() > MAX_SOURCE_BYTES) {
            return SanitizerResult.Failed(SanitizerFailureReason.SOURCE_TOO_LARGE)
        }
        val format = formatOf(imageBytes)
        if (format == Format.UNSUPPORTED) return SanitizerResult.Unsupported("unsupported")
        if (!mimeMatches(format, mimeHint)) {
            return SanitizerResult.Failed(SanitizerFailureReason.MIME_MISMATCH)
        }
        // Bounds only — `inJustDecodeBounds`, so this allocates no pixels. Asked of every format
        // because "the platform cannot make sense of these bytes at all" is the fail-closed check;
        // it is the *pixel cap* below that is not universal.
        val dimensions = runCatching { imageDimensions(imageBytes) }.getOrNull()
            ?: return SanitizerResult.Failed(SanitizerFailureReason.MALFORMED_INPUT)
        // [MAX_DECODED_PIXELS] is a decode budget, so only the path that decodes has to answer for
        // it. HEIF is re-encoded through a Bitmap ([HeifTranscoder]), which holds the still twice in
        // memory; JPEG, PNG and WebP are byte surgery — `stripJpeg`/`stripPng`/`stripWebp` never
        // construct a Bitmap — so capping their pixel count guarded an allocation that does not
        // happen.
        //
        // It did not merely fail to protect them, it refused them: `sanitize` returned Failed,
        // `replaceStagedFile` turned that into `UnstrippableImageException`, and the file editor
        // showed "Metadata could not be removed" and would not save at all. That is every photo
        // above 25 MP — a 50 MP Samsung still is 8160x6120 = 49.9 MP — plus panoramas and scans.
        // Measured 2026-08-25 on a 6000x5000 PNG (1.6 MB, so nowhere near [MAX_SOURCE_BYTES]);
        // the identical flow with a small PNG saved, which is what isolated the cap as the cause.
        val (width, height) = dimensions
        if (format == Format.HEIF && !withinPixelLimit(width, height)) {
            return SanitizerResult.Failed(SanitizerFailureReason.DECODED_PIXELS_TOO_LARGE)
        }
        val readable = try {
            if (format == Format.HEIF) {
                HeifTranscoder.toJpeg(imageBytes)
                    ?: return SanitizerResult.Failed(SanitizerFailureReason.MALFORMED_INPUT)
            } else {
                imageBytes
            }
        } catch (_: Throwable) {
            return SanitizerResult.Failed(SanitizerFailureReason.MALFORMED_INPUT)
        }
        val result = runCatching { stripStrict(readable) }.getOrNull()
            ?: return SanitizerResult.Failed(SanitizerFailureReason.MALFORMED_INPUT)
        if (result.size.toLong() > MAX_SANITIZED_OUTPUT_BYTES) {
            return SanitizerResult.Failed(SanitizerFailureReason.SANITIZED_OUTPUT_TOO_LARGE)
        }
        return SanitizerResult.Success(
            bytes = result,
            mime = if (format == Format.HEIF) "image/jpeg" else mimeFor(format),
        )
    }

    fun strip(
        cacheDir: File,
        imageBytes: ByteArray,
        keepAll: Boolean = false,
        stripAllIdentifying: Boolean = true,
    ): ByteArray {
        if (keepAll) return imageBytes
        // Any failure below yields the original bytes. See the class note: a half-rewritten image is
        // worse than an unstripped one, because the user cannot tell and cannot undo it.
        return runCatching { stripStrict(imageBytes, cacheDir, stripAllIdentifying) }.getOrDefault(imageBytes)
    }

    private fun stripStrict(
        imageBytes: ByteArray,
        cacheDir: File = File("."),
        stripAllIdentifying: Boolean = true,
    ): ByteArray = when (formatOf(imageBytes)) {
        // `uprightJpeg` first, always, and the ordering is the whole point: `stripJpeg` deletes the
        // APP1/Exif segment wholesale, and `TAG_ORIENTATION` lives in it. Nothing downstream reads
        // that tag back — the viewer decodes with a bare `BitmapFactory`, which does not apply it,
        // and there is no rotate control anywhere — so a photo whose camera recorded its rotation
        // in Exif rather than in pixels was stored, displayed and exported sideways, permanently.
        // That is the ordinary case on a Samsung or Pixel stock camera, not an edge case.
        //
        // The orientation is *consumed*, never preserved: it is applied to the pixels and then the
        // segment carrying it is deleted with the rest, so the privacy guarantee is byte-identical
        // to what it was. See [uprightJpeg].
        Format.JPEG -> stripJpeg(cacheDir, uprightJpeg(imageBytes), stripAllIdentifying)
        Format.PNG -> stripPng(imageBytes)
        Format.WEBP -> stripWebp(imageBytes)
        Format.HEIF, Format.UNSUPPORTED -> error("unsupported still format")
    }

    private fun mimeMatches(format: Format, hint: String?): Boolean {
        if (hint.isNullOrBlank() || hint == "image/*") return true
        if (!hint.startsWith("image/")) return false
        return when (format) {
            Format.JPEG -> hint == "image/jpeg" || hint == "image/jpg"
            Format.PNG -> hint == "image/png"
            Format.WEBP -> hint == "image/webp"
            // `isHeif` accepts the avif/avis brands, so the hints that name them are HEIF's too.
            Format.HEIF -> hint in setOf(
                "image/heic", "image/heif", "image/heif-sequence", "image/heic-sequence",
                "image/avif", "image/avif-sequence",
            )
            Format.UNSUPPORTED -> false
        }
    }

    private fun mimeFor(format: Format): String = when (format) {
        Format.JPEG -> "image/jpeg"
        Format.PNG -> "image/png"
        Format.WEBP -> "image/webp"
        Format.HEIF -> "image/jpeg"
        Format.UNSUPPORTED -> "application/octet-stream"
    }

    /** Header-read only: `inJustDecodeBounds` allocates no bitmap. Null when nothing decodes. */
    private fun imageDimensions(bytes: ByteArray): Pair<Long, Long>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth.toLong()
        val height = options.outHeight.toLong()
        if (width <= 0L || height <= 0L) return null
        return width to height
    }

    /** Guards the multiply itself: a hostile header can name dimensions whose product overflows. */
    private fun withinPixelLimit(width: Long, height: Long): Boolean =
        width <= Long.MAX_VALUE / height && isAdmissiblePixelCount(width * height)

    internal fun formatOf(bytes: ByteArray): Format = when {
        bytes.startsWith(JPEG_MAGIC) -> Format.JPEG
        bytes.startsWith(PNG_MAGIC) -> Format.PNG
        // RIFF....WEBP — the four size bytes between the two tags are content, so they are skipped
        // rather than matched.
        bytes.startsWith(RIFF_MAGIC) && bytes.startsWith(WEBP_MAGIC, offset = 8) -> Format.WEBP
        isHeif(bytes) -> Format.HEIF
        else -> Format.UNSUPPORTED
    }

    /** Canonical MIME detected from bounded content bytes, independent of provider metadata. */
    internal fun detectedImageMime(bytes: ByteArray): String? = when (formatOf(bytes)) {
        Format.JPEG -> "image/jpeg"
        Format.PNG -> "image/png"
        Format.WEBP -> "image/webp"
        Format.HEIF -> "image/heif"
        Format.UNSUPPORTED -> null
    }

    /**
     * ISO base media `ftyp` box whose brand is one of the HEIF family.
     *
     * The brand is checked rather than `ftyp` alone: MP4 and MOV carry the same box, and calling a
     * video "HEIF" here would send it down the still-image re-encode path, which would silently
     * turn a recording into one frame. Both the major brand (bytes 8..11) and the compatible-brands
     * list that follows it are searched, because a Samsung HEIC commonly declares `mif1` as major
     * with `heic` further down.
     */
    private fun isHeif(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || !bytes.startsWith(FTYP_MAGIC, offset = 4)) return false
        // Never past the ftyp box, and never past the buffer: brands are four bytes each, starting
        // at the major brand and running to the end of the box.
        val boxSize = readIntBe(bytes, 0)
        val end = if (boxSize in 16..bytes.size) boxSize else bytes.size
        var at = 8
        while (at + 4 <= end) {
            if (String(bytes, at, 4, Charsets.US_ASCII) in HEIF_BRANDS) return true
            at += 4
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // JPEG — unchanged behaviour, moved behind the dispatch.
    // ---------------------------------------------------------------------------------------

    /**
     * The transform that turns a pixel grid stored under [orientation] into an upright one, or null
     * when there is nothing to do.
     *
     * All eight Exif values are answered, the four mirrored ones included. A front camera writes 2
     * as readily as a rotated shot writes 6, and treating a mirror as "not a rotation" would leave
     * exactly the images this exists for still wrong — only differently. Anything outside the eight
     * — 0, or a value read out of a damaged tag — is null and therefore left alone: a guess about a
     * broken tag is worse than the original framing.
     */
    private fun uprightMatrix(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply {
            setScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply {
            setRotate(180f)
        }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply {
            setRotate(180f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
            setRotate(90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply {
            setRotate(90f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
            setRotate(-90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply {
            setRotate(-90f)
        }
        else -> null
    }

    /**
     * Bakes a JPEG's Exif orientation into its pixels so that deleting the Exif cannot change how
     * the photo looks. Returns [bytes] unchanged when there is nothing to bake in.
     *
     * The precedent is the camera path: `imageProxyToUprightJpeg` already rotates a capture by
     * CameraX's `rotationDegrees` before anything stores it, so the two ways an image enters the
     * vault now agree instead of one of them being sideways.
     *
     * Three properties this deliberately has:
     *
     * - **No re-encode when orientation is 1, absent, or unreadable.** The overwhelmingly common
     *   case must cost nothing; recompressing every imported photo to fix the minority would trade
     *   a visible bug for an invisible one — a generation of quality lost on every import.
     * - **Best-effort, and it fails *open* rather than refusing the import.** Every way this can
     *   give up — unparseable Exif, absurd dimensions, a decode or an allocation that does not fit
     *   — returns the original bytes, which then go through `stripJpeg` exactly as before. The
     *   worst outcome is the sideways photo that shipped until now; the alternative, refusing to
     *   store a photo because it could not be *rotated*, is plainly worse. Note the asymmetry with
     *   the metadata guarantee, which is fail-closed and stays that way: stripping still happens
     *   whatever this returns.
     * - **Bounded memory, by attempt rather than by prediction.** The rotate holds the still twice
     *   at once, so the config ladder is walked exactly as [HeifTranscoder] walks it — full colour
     *   first, RGB_565 at half the memory as the fallback — and the source bitmap is recycled on
     *   every path, so a failed ARGB_8888 attempt has released its heap before 565 is tried.
     */
    private fun uprightJpeg(bytes: ByteArray): ByteArray {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = uprightMatrix(orientation) ?: return bytes
        // Same arithmetic guard the HEIF path gets, and for the same reason: this is now a path
        // that decodes, so a header claiming billions of pixels has to be refused on multiplication
        // rather than on how much memory happened to be free.
        val (width, height) = imageDimensions(bytes) ?: return bytes
        if (!withinPixelLimit(width, height)) return bytes
        for (config in DECODE_CONFIG_LADDER) {
            // `runCatching` catches `Throwable`, which is the point rather than an accident — an
            // `OutOfMemoryError` at ARGB_8888 is the signal to try the narrower config.
            val rebuilt = runCatching { rotateAndReencode(bytes, matrix, config) }.getOrNull()
            if (rebuilt != null) return rebuilt
        }
        return bytes
    }

    /**
     * The Exif orientation a *display* should honour for [bytes], or `ORIENTATION_NORMAL`.
     *
     * JPEG only, deliberately. `BitmapFactory` ignores the tag on a JPEG, which is the whole
     * reason this exists; a HEIF's rotation lives in the container and the platform decoder has
     * already applied it, so honouring an Exif tag on top would rotate it twice. The stripper
     * consumes the tag at import, so a sanitized image reads as normal here and is not rotated
     * again either.
     */
    internal fun displayOrientation(bytes: ByteArray): Int {
        if (formatOf(bytes) != Format.JPEG) return ExifInterface.ORIENTATION_NORMAL
        return runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    /** Whether showing a picture stored under [orientation] upright swaps its width and height. */
    internal fun orientationSwapsAxes(orientation: Int): Boolean = when (orientation) {
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270,
        -> true
        else -> false
    }

    /**
     * [bitmap], decoded from the JPEG [source], turned upright per that JPEG's Exif orientation.
     *
     * For the display paths -- the viewer and both thumbnail builders. A File item picked from the
     * gallery is stored as the original bytes (metadata removal is an action the user takes, not a
     * side effect of saving), so its orientation is still a tag that `BitmapFactory` ignores and a
     * portrait photo was drawn sideways until "Delete all metadata" happened to bake it in
     * (S3-01, 2026-10-02). Nothing is written back: the stored bytes, the tag and the strip are
     * untouched, so this cannot weaken the metadata guarantee.
     *
     * Fails open to the unrotated [bitmap] -- an allocation that does not fit costs the rotation,
     * not the picture -- and recycles the source only when it hands back a different bitmap.
     */
    internal fun uprightBitmap(bitmap: Bitmap, source: ByteArray): Bitmap {
        val matrix = uprightMatrix(displayOrientation(source)) ?: return bitmap
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull()?.also { if (it !== bitmap) bitmap.recycle() } ?: bitmap
    }

    /** One attempt at [config]: decode, apply [matrix], re-encode. Null if any step declines. */
    private fun rotateAndReencode(bytes: ByteArray, matrix: Matrix, config: Bitmap.Config): ByteArray? {
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = config },
        ) ?: return null
        var upright: Bitmap? = null
        return try {
            upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val out = ByteArrayOutputStream(bytes.size)
            if (upright.compress(Bitmap.CompressFormat.JPEG, UPRIGHT_JPEG_QUALITY, out)) {
                out.toByteArray()
            } else {
                null
            }
        } finally {
            // By identity, because `createBitmap` hands back the *source* rather than a copy when
            // the matrix turns out to be identity — no case reaching here does today, but the
            // check keeps a future one from recycling a bitmap this block also recycles below.
            // Both are released before the caller tries the next, narrower config, which is what
            // makes the RGB_565 retry worth attempting at all.
            if (upright !== decoded) upright?.recycle()
            decoded.recycle()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun stripJpeg(cacheDir: File, bytes: ByteArray, stripAllIdentifying: Boolean): ByteArray {
        // JPEG metadata lives in marker segments before SOS. Removing the complete EXIF/XMP/IPTC
        // Removing complete metadata segments is stricter and safer than a decoder round-trip:
        // the encrypted-import boundary must not create a plaintext artifact, even briefly.
        require(bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 2)
        var pos = 2
        while (pos < bytes.size) {
            require(bytes[pos] == 0xFF.toByte()) { "invalid JPEG marker" }
            val markerStart = pos
            while (pos < bytes.size && bytes[pos] == 0xFF.toByte()) pos++
            require(pos < bytes.size) { "truncated JPEG marker" }
            val marker = bytes[pos++].toInt() and 0xFF
            when {
                marker == 0xD9 -> {
                    out.write(bytes, markerStart, pos - markerStart)
                    return out.toByteArray()
                }
                marker == 0xDA -> {
                    // The scan header is a normal length-prefixed segment; what follows it is the
                    // entropy-coded data, copied as-is up to the next *real* marker. Not "to the
                    // end of the buffer", which is what this did until 2026-10-02 (S3-03): the
                    // bytes after the EOI were kept too, so a Motion Photo's appended MP4, an
                    // Ultra HDR gain map and any vendor trailer survived "Metadata removed".
                    // Stopping at the marker hands control back to this loop, which keeps a
                    // progressive JPEG's later scans and the segments between them, and returns
                    // at the EOI, dropping whatever follows it.
                    require(pos + 2 <= bytes.size) { "truncated JPEG segment length" }
                    val headerLength = ((bytes[pos].toInt() and 0xFF) shl 8) or
                        (bytes[pos + 1].toInt() and 0xFF)
                    require(headerLength >= 2 && pos + headerLength <= bytes.size) { "truncated JPEG segment" }
                    pos += headerLength
                    // Inside the data, FF 00 is a stuffed 0xFF and FF D0..D7 is a restart marker;
                    // any other FFxx ends it.
                    while (pos + 1 < bytes.size) {
                        if (bytes[pos] == 0xFF.toByte()) {
                            val next = bytes[pos + 1].toInt() and 0xFF
                            if (next != 0x00 && next !in 0xD0..0xD7) break
                            pos += 2
                        } else {
                            pos++
                        }
                    }
                    if (pos + 1 >= bytes.size) {
                        // No marker follows: a truncated file, as before. There is no EOI, so there
                        // is no trailer either; keep the rest rather than refuse a damaged photo.
                        out.write(bytes, markerStart, bytes.size - markerStart)
                        return out.toByteArray()
                    }
                    out.write(bytes, markerStart, pos - markerStart)
                }
                marker == 0x01 || marker in 0xD0..0xD7 ->
                    out.write(bytes, markerStart, pos - markerStart)
                else -> {
                    require(pos + 2 <= bytes.size) { "truncated JPEG segment length" }
                    val length = ((bytes[pos].toInt() and 0xFF) shl 8) or
                        (bytes[pos + 1].toInt() and 0xFF)
                    require(length >= 2 && pos + length <= bytes.size) { "truncated JPEG segment" }
                    val payloadStart = pos + 2
                    // An allowlist of the application segments a decoder needs -- JFIF (APP0), the
                    // ICC profile (APP2) and Adobe's colour-transform flag (APP14) -- rather than a
                    // list of the ones known to leak. The list of leaks was APP1/APP13 and Adobe's
                    // XMP, and it missed C2PA/JUMBF (APP11), Multi-Picture Format (APP2 "MPF"),
                    // GoPro (APP6) and every vendor's APP3..APP5, plus COM (S3-03, 2026-10-02).
                    val isApplicationOrComment = marker in 0xE0..0xEF || marker == 0xFE
                    val keep = !isApplicationOrComment || marker == 0xE0 || marker == 0xEE ||
                        (marker == 0xE2 && hasPrefix(bytes, payloadStart, "ICC_PROFILE"))
                    val drop = !keep
                    // `pos`, not `payloadStart`: a JPEG segment is FF, marker, then `length` bytes
                    // — and `length` counts its own two bytes. Measuring from `payloadStart` adds
                    // those two a second time, so every kept segment copied two bytes too many,
                    // dragging in the *next* segment's FF and marker. The loop then emitted that
                    // marker again on its own iteration, producing FF DB FF DB, FF C4 FF C4 and so
                    // on, and a JPEG no decoder would touch.
                    //
                    // It survived because nothing asserted the output was still an image. The
                    // byte-built fixtures check that Exif is gone and that the first and last bytes
                    // are SOI and EOI, all of which stay true of a corrupted file; `strip` swallows
                    // its own failures by design; and the one caller that would have noticed —
                    // thumbnailing the result — only ran on the HEIF path. Found 2026-08-25 when
                    // "Delete all metadata" on a 50 MP HEIC reported success from `sanitize` and
                    // then failed, because the sanitized bytes decoded to -1x-1. Output was
                    // *larger* than input, which for a stripper is the whole tell.
                    // `StripJpegRoundTripTest` is the regression: it decodes what comes back.
                    if (!drop) out.write(bytes, markerStart, length + (pos - markerStart))
                    pos += length
                }
            }
        }
        error("JPEG has no scan or end marker")
    }

    private fun hasPrefix(bytes: ByteArray, offset: Int, value: String): Boolean {
        val prefix = value.toByteArray(Charsets.US_ASCII)
        return offset >= 0 && offset + prefix.size <= bytes.size &&
            prefix.indices.all { bytes[offset + it] == prefix[it] }
    }

    // ---------------------------------------------------------------------------------------
    // PNG — a chunk filter rather than ExifInterface.
    //
    // ExifInterface can write a PNG's `eXIf` chunk, but it does not touch `tEXt`/`zTXt`/`iTXt`,
    // which is where a camera app or editor writes "Software", comments, and often an XMP packet
    // carrying the very GPS this is here to remove. Filtering chunks covers both, needs no scratch
    // file, and cannot re-encode a single pixel.
    // ---------------------------------------------------------------------------------------

    /** Chunk types dropped from a PNG. All ancillary — no critical chunk is ever removed. */
    internal val PNG_DROP = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")

    private fun stripPng(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, PNG_MAGIC.size)
        var pos = PNG_MAGIC.size
        var sawEnd = false
        // length(4, big-endian) + type(4) + data + crc(4)
        while (pos + 8 <= bytes.size) {
            val length = readIntBe(bytes, pos)
            // A length past the end, or a negative one from a >2GiB field, means the file is
            // truncated or lying. Bail to the caller's runCatching rather than emit a prefix.
            require(length >= 0 && pos + 12L + length <= bytes.size) { "truncated PNG chunk" }
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val total = 12 + length
            if (type !in PNG_DROP) out.write(bytes, pos, total)
            pos += total
            if (type == "IEND") {
                sawEnd = true
                break
            }
        }
        require(sawEnd) { "PNG has no IEND" }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------------------------
    // WebP — RIFF chunk filter.
    //
    // Dropping the EXIF/XMP chunks is not enough on its own: an extended-format WebP announces
    // which optional chunks exist in the VP8X flags byte, and a decoder that reads a flag for a
    // chunk that is no longer there is entitled to treat the file as damaged. So the flags are
    // cleared to match, and the RIFF size field is rewritten to the new length.
    // ---------------------------------------------------------------------------------------

    private const val VP8X_FLAG_EXIF = 0x08
    private const val VP8X_FLAG_XMP = 0x04

    private fun stripWebp(bytes: ByteArray): ByteArray {
        val body = ByteArrayOutputStream(bytes.size)
        var pos = 12 // past "RIFF" + size + "WEBP"
        // fourcc(4) + size(4, little-endian) + data + one pad byte when size is odd
        while (pos + 8 <= bytes.size) {
            val fourcc = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = readIntLe(bytes, pos + 4)
            require(size >= 0) { "WebP chunk size overflows" }
            val padded = size + (size and 1)
            require(pos + 8L + padded <= bytes.size) { "truncated WebP chunk" }
            when (fourcc) {
                "EXIF", "XMP " -> Unit // dropped
                "VP8X" -> {
                    body.write(bytes, pos, 8)
                    val flags = bytes[pos + 8].toInt() and
                        VP8X_FLAG_EXIF.inv() and
                        VP8X_FLAG_XMP.inv()
                    body.write(flags)
                    body.write(bytes, pos + 9, padded - 1)
                }
                else -> body.write(bytes, pos, 8 + padded)
            }
            pos += 8 + padded
        }
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream(payload.size + 12)
        out.write(RIFF_MAGIC)
        // RIFF size counts everything after this field — the "WEBP" tag plus every chunk.
        out.write(intLe(payload.size + 4))
        out.write(WEBP_MAGIC)
        out.write(payload)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------------------------

    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val FTYP_MAGIC = "ftyp".toByteArray(Charsets.US_ASCII)

    /** `avif`/`avis` are here for the same reason as the HEIC brands: same container, same
     *  impossibility, and the same wrong answer if they fell through to UNSUPPORTED. */
    private val HEIF_BRANDS = setOf(
        "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs",
        "mif1", "msf1", "heif", "avif", "avis",
    )

    private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)

    private fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean {
        if (size < offset + prefix.size) return false
        return prefix.indices.all { this[offset + it] == prefix[it] }
    }

    private fun readIntBe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun readIntLe(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun intLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )
}

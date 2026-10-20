package com.localkeep.feature.files

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.localkeep.core.model.Attachment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMB_MAX_DIM = 512
private const val IMAGE_SNIFF_BYTES = 64

data class ImportResult(
    val filename: String,
    val mime: String,
    val sizeBytes: Long,
    val blobRef: String,
    val thumbnailRef: String?,
    val originalUri: Uri,
)

/** Imports a Photo Picker selection (image or video) into the encrypted vault (§5.5). */
@Singleton
class PhotoPickerImport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileBlobStore: FileBlobStore,
) {
    /** Stages encrypted source bytes without claiming that image metadata was removed. */
    /**
     * @param fromDocumentPicker true when [uri] came from `ACTION_OPEN_DOCUMENT` rather than the
     *   gallery. Suppresses [StagedFileSource.pendingGalleryDeleteUri]: `createDeleteRequest`
     *   operates on MediaStore URIs, and a `content://com.google.android.apps.docs...` document URI
     *   is not one, so offering to delete the original would be an offer the app cannot honour.
     */
    suspend fun stageFile(
        uri: Uri,
        dek: ByteArray,
        fromDocumentPicker: Boolean = false,
    ): Result<StagedFileSource> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val providerMime = resolver.getType(uri) ?: "application/octet-stream"
                val detectedMime = if (providerMime.startsWith("image/")) {
                    null
                } else {
                    resolver.openInputStream(uri)?.use { it.readPrefix(IMAGE_SNIFF_BYTES) }
                        ?.let(ExifStripper::detectedImageMime)
                }
                val imageMime = providerMime.takeIf { it.startsWith("image/") } ?: detectedMime
                val mime = imageMime ?: providerMime
                val (filename, _) = queryDisplayNameAndSize(resolver, uri)
                val bytes = if (imageMime != null) {
                    resolver.openInputStream(uri)?.use { it.readCapped(MAX_SOURCE_BYTES) }
                        ?: error("cannot open picked file")
                } else {
                    null
                }
                // Counted on the way through, because the plaintext length is otherwise lost on
                // this branch. It used to fall back to `blobSizeBytes(blobRef)` -- the size of the
                // *ciphertext* -- so every streamed attachment (video, PDF, office document; every
                // kind except an image, which is buffered above) reported its encrypted size in the
                // viewer's Details panel and in the editor's subtitle. The provider's own reported
                // size would do for most files and is absent or zero often enough that counting the
                // bytes actually written is the only answer that is always true.
                var streamedBytes = 0L
                val blobRef = if (bytes != null) {
                    fileBlobStore.encryptToBlob(ByteArrayInputStream(bytes), dek)
                } else {
                    resolver.openInputStream(uri)?.use { raw ->
                        val counting = CountingInputStream(raw)
                        fileBlobStore.encryptToBlob(counting, dek)
                            .also { streamedBytes = counting.bytesRead }
                    } ?: error("cannot open picked file")
                }
                // A thumbnail failure must not take the import down with it. `thumbnailRef` is
                // nullable by design — the grid falls back to a type glyph — so the only thing a
                // thrown `encryptThumbnail` used to accomplish was abandoning the full-resolution
                // blob written a few lines above: nothing referenced it, the user saw "couldn't
                // import", and the bytes sat there until the next garbage-collection pass.
                //
                // Swallowed rather than cleaned up after, because the import can simply succeed
                // without a thumbnail, and an import that works is a better outcome than one that
                // fails tidily.
                //
                // The video branch is not symmetry for its own sake. A video is the one kind whose
                // full blob nothing can decode into a picture, so with no stored thumbnail the
                // filmstrip has nothing but an `MP4` badge to draw and the viewer opens on an
                // entirely black surface — `MediaPlayer` renders nothing before the first decoded
                // frame (3C-3, release sweep 2026-09-20). `import()` had grabbed a frame for videos
                // since it was written; every File item goes through *this* function instead, so no
                // video the user could actually open had ever had one.
                val thumbnailRef = if (bytes != null) {
                    runCatching {
                        thumbnailBitmap(bytes)?.let { fileBlobStore.encryptThumbnail(it, dek) }
                    }.getOrNull()
                } else if (mime.startsWith("video/")) {
                    runCatching {
                        videoFrameThumbnail(uri)?.let { fileBlobStore.encryptThumbnail(it, dek) }
                    }.getOrNull()
                } else {
                    null
                }
                StagedFileSource(
                    blobRef = blobRef,
                    thumbnailRef = thumbnailRef,
                    filename = filename,
                    mime = mime,
                    sizeBytes = bytes?.size?.toLong() ?: streamedBytes,
                    generationId = java.util.UUID.randomUUID(),
                    metadataState = if (imageMime != null) MetadataCleanupState.PENDING else MetadataCleanupState.NOT_APPLICABLE,
                    sourceUri = uri,
                    capturedInApp = false,
                    removedFromGallery = false,
                    pendingGalleryDeleteUri = if (!fromDocumentPicker && MediaDeleter.isDeleteRequestSupported()) uri else null,
                    // The same signal with the API-30 half removed. On Android 9/10 nothing can be
                    // deleted programmatically at all, so the line above is null for every import
                    // there — and that is precisely where the editor has to know whether there is a
                    // gallery original worth telling the user about. Finding 24-1, 2026-09-13.
                    hasGalleryOriginal = !fromDocumentPicker,
                    // Free here: an image's plaintext is already in memory for the encrypt above.
                    metadataPresence = bytes?.let { MetadataProbe.probe(ByteArrayInputStream(it)) }
                        ?: MetadataPresence.UNKNOWN,
                    // Asked now, not after Save: the picker's grant and the provider's flags are
                    // facts about this moment, and Save may be minutes and a relock away.
                    //
                    // Gallery picks too, below API 30: there `PickVisualMedia` falls back to the
                    // system document picker, so the URI is a document URI with no
                    // `createDeleteRequest` to raise — and, measured on the API 28 image on
                    // 2026-10-09, `DocumentsContract.deleteDocument` removes it. A URI that is not
                    // a deletable document answers null, and the by-hand note stays.
                    deletableOriginalDocument = if (fromDocumentPicker || !MediaDeleter.isDeleteRequestSupported()) {
                        OriginalDocuments.deletableOrNull(context, uri)
                    } else {
                        null
                    },
                )
            }
        }

    /** Deletes a document-picker original; see [OriginalDocuments.delete]. */
    suspend fun deleteOriginalDocument(uri: Uri): Boolean =
        withContext(Dispatchers.IO) { OriginalDocuments.delete(context, uri) }

    /** Stages an in-app camera capture without making a premature metadata claim. */
    suspend fun stageCapturedFile(jpegBytes: ByteArray, dek: ByteArray): Result<StagedFileSource> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(jpegBytes.size.toLong() <= MAX_SOURCE_BYTES) {
                    "captured image exceeds the source limit"
                }
                val blobRef = fileBlobStore.encryptToBlob(ByteArrayInputStream(jpegBytes), dek)
                val thumbnailRef = try {
                    thumbnailBitmap(jpegBytes)?.let { fileBlobStore.encryptThumbnail(it, dek) }
                } catch (t: Throwable) {
                    fileBlobStore.deleteBlob(blobRef)
                    throw t
                }
                StagedFileSource(
                    blobRef = blobRef,
                    thumbnailRef = thumbnailRef,
                    filename = "capture-${System.currentTimeMillis()}.jpg",
                    mime = "image/jpeg",
                    sizeBytes = jpegBytes.size.toLong(),
                    generationId = java.util.UUID.randomUUID(),
                    metadataState = MetadataCleanupState.PENDING,
                    sourceUri = null,
                    capturedInApp = true,
                    removedFromGallery = true,
                    pendingGalleryDeleteUri = null,
                    metadataPresence = MetadataProbe.probe(ByteArrayInputStream(jpegBytes)),
                )
            }
        }

    /** Sanitizes and atomically replaces a staged image and its thumbnail. */
    suspend fun replaceStagedFile(
        source: StagedFileSource,
        sanitizedBytes: ByteArray,
        dek: ByteArray,
    ): Result<StagedFileSource> = withContext(Dispatchers.IO) {
        runCatching {
            // Hinted by the bytes, not by `source.mime`, which is the provider's claim (S3-06).
            val hint = ExifStripper.detectedImageMime(sanitizedBytes) ?: source.mime
            val sanitized = when (val result = ExifStripper.sanitize(sanitizedBytes, hint)) {
                is SanitizerResult.Success -> result
                is SanitizerResult.Failed -> throw UnstrippableImageException(result.reason.asCleanupFailure())
                is SanitizerResult.Unsupported ->
                    throw UnstrippableImageException(MetadataCleanupFailure.UNSUPPORTED_FORMAT)
            }
            val replacement = fileBlobStore.beginReplacement(
                setOfNotNull(source.blobRef, source.thumbnailRef),
            )
            try {
                val blobRef = replacement.stageBlob(sanitized.bytes, dek)
                val thumbnailRef = thumbnailBitmap(sanitized.bytes)?.let {
                    replacement.stageThumbnail(fileBlobStore.thumbnailJpegBytes(it), dek)
                }
                if (source.thumbnailRef != null && thumbnailRef == null) {
                    throw UnstrippableImageException()
                }
                replacement.commit()
                source.copy(
                    blobRef = blobRef,
                    thumbnailRef = thumbnailRef,
                    filename = if (sanitized.mime == "image/jpeg" && source.filename.substringAfterLast('.', "").lowercase() in setOf("heic", "heif")) {
                        source.filename.substringBeforeLast('.', source.filename) + ".jpg"
                    } else {
                        source.filename
                    },
                    mime = sanitized.mime,
                    sizeBytes = sanitized.bytes.size.toLong(),
                    generationId = java.util.UUID.randomUUID(),
                    metadataState = MetadataCleanupState.REMOVED,
                    metadataPresence = MetadataProbe.probe(ByteArrayInputStream(sanitized.bytes)),
                )
            } catch (t: Throwable) {
                replacement.abort()
                throw t
            }
        }
    }

    /** Discards only uncommitted staged refs. Callers must not pass a vault item's live source. */
    fun discardStagedFile(source: StagedFileSource) {
        fileBlobStore.deleteBlob(source.blobRef)
        source.thumbnailRef?.let(fileBlobStore::deleteBlob)
    }

    fun commitStagedFile(source: StagedFileSource, title: String, folderId: java.util.UUID?): Result<com.localkeep.core.model.File> =
        Result.success(source.toFile(title))

    /** Attachment import keeps its existing immediate-sanitization contract, without staged state. */
    suspend fun importAttachment(uri: Uri, dek: ByteArray): AttachmentImportResult = try {
        val result = import(uri, dek, makeThumbnail = false)
        AttachmentImportResult.Success(
            Attachment(
                id = java.util.UUID.randomUUID(),
                filename = result.filename,
                mime = result.mime,
                sizeBytes = result.sizeBytes,
                blobRef = result.blobRef,
                thumbnailRef = null,
            ),
        )
    } catch (_: UnstrippableImageException) {
        AttachmentImportResult.Failure(AttachmentImportFailureReason.UNSTRIPPABLE_IMAGE)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Throwable) {
        AttachmentImportResult.Failure(AttachmentImportFailureReason.IMPORT_FAILED)
    }

    /**
     * @param makeThumbnail whether to store a downsampled second copy alongside the blob. A
     *   thumbnail is a *second stored artifact* of the same picture, and every question asked of
     *   the full-resolution blob — who may see it, whether it survives a backup — has to be asked
     *   again of it. Callers storing a photographed identity document pass false rather than
     *   answer that question twice: there is no grid for these, so nothing would render it.
     */
    suspend fun import(
        uri: Uri,
        dek: ByteArray,
        makeThumbnail: Boolean = true,
    ): ImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val providerMime = resolver.getType(uri) ?: "application/octet-stream"
        val detectedMime = if (providerMime.startsWith("image/")) {
            null
        } else {
            resolver.openInputStream(uri)?.use { it.readPrefix(IMAGE_SNIFF_BYTES) }
                ?.let(ExifStripper::detectedImageMime)
        }
        val imageMime = providerMime.takeIf { it.startsWith("image/") } ?: detectedMime
        var mime = imageMime ?: providerMime
        var (filename, sizeBytes) = queryDisplayNameAndSize(resolver, uri)
        val isImage = imageMime != null

        var blobRef: String? = null
        var thumbnailRef: String? = null

        try {
            if (isImage) {
                // Cap the in-memory read so a huge/mislabeled image can't OOM the process before
                // Exif-strip/encrypt (readCapped throws ImportTooLargeException past the cap). Videos
                // below stream straight through and are never fully buffered, so they need no cap.
                val original = resolver.openInputStream(uri)?.use { it.readCapped(MAX_IMPORT_IMAGE_BYTES) }
                    ?: error("cannot open picked file")
            // HEIC is the default still format on Samsung since Android 9, so this is the ordinary
            // gallery selection rather than an exotic one — and ExifStripper cannot rewrite a HEIF
            // container, so before this branch existed such a photo went into the vault with its
            // GPS, make, model and capture time intact while the strip call above it looked like it
            // had done something. Re-encoding to JPEG drops every tag by construction; a device
            // that cannot decode its own camera's output gets a refused import rather than a silent
            // pass-through, because the user has no way to discover the difference.
                //
                // The hint is what the *bytes* are, not what the provider called them. A provider's
                // MIME is a guess -- image/avif, or a WebP/PNG/HEIC saved with a .jpg name and so
                // reported as image/jpeg -- and `sanitize` treats a disagreement as MIME_MISMATCH,
                // refusing a perfectly strippable image with advice ("shoot in JPEG") that cannot
                // help (S3-06, 2026-10-02). The stripper already works from the sniffed format.
                val processed = when (
                    val sanitized = ExifStripper.sanitize(original, ExifStripper.detectedImageMime(original) ?: mime)
                ) {
                    is SanitizerResult.Success -> {
                        mime = sanitized.mime
                        if (sanitized.mime == "image/jpeg" && filename.substringAfterLast('.', "").lowercase() in
                            setOf("heic", "heif")
                        ) {
                            filename = filename.substringBeforeLast('.', filename) + ".jpg"
                        }
                        sanitized.bytes
                    }
                    is SanitizerResult.Failed -> throw UnstrippableImageException(sanitized.reason.asCleanupFailure())
                    is SanitizerResult.Unsupported ->
                        throw UnstrippableImageException(MetadataCleanupFailure.UNSUPPORTED_FORMAT)
                }
                sizeBytes = processed.size.toLong()
                blobRef = fileBlobStore.encryptToBlob(ByteArrayInputStream(processed), dek)
                if (makeThumbnail) {
                    thumbnailBitmap(processed)?.let { bmp -> thumbnailRef = fileBlobStore.encryptThumbnail(bmp, dek) }
                }
            } else {
                // Video/other: stream straight through — never fully buffered (§5.5), regardless of size.
                //
                // Video metadata is NOT stripped, and that is a decision rather than an omission. A
                // recorded MP4 carries its location in `moov/udta`, and `moov` sits after `mdat` in most
                // camera output — so finding it means seeking or buffering the whole file, and this path
                // exists precisely so a 4 GB video never lands in memory. Removing bytes from `moov`
                // when it does come first shifts every `mdat` offset, which then means rewriting
                // `stco`/`co64`. That is sample-table surgery on a file the user usually cannot
                // re-record. Deliberately out of scope; revisit only with a streaming box-walk that
                // proves its peak memory in a test.
                blobRef = resolver.openInputStream(uri)?.use { input -> fileBlobStore.encryptToBlob(input, dek) }
                    ?: error("cannot open picked file")
                if (mime.startsWith("video/")) {
                    videoFrameThumbnail(uri)?.let { bmp -> thumbnailRef = fileBlobStore.encryptThumbnail(bmp, dek) }
                }
            }
            ImportResult(filename, mime, sizeBytes, requireNotNull(blobRef), thumbnailRef, uri)
        } catch (t: Throwable) {
            blobRef?.let(fileBlobStore::deleteBlob)
            thumbnailRef?.let(fileBlobStore::deleteBlob)
            throw t
        }
    }

    /** Downsampled (<= [THUMB_MAX_DIM]) thumbnail bitmap, or null if [bytes] can't be decoded.
     *  Public so camera capture (FileItemEditViewModel.onCaptured) reuses the SAME downsampling
     *  instead of encrypting a second full-resolution copy of the photo. */
    fun thumbnailBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_MAX_DIM && bounds.outHeight / (sample * 2) >= THUMB_MAX_DIM) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        // Upright per the JPEG's Exif orientation, which `BitmapFactory` ignores: a File item keeps
        // its original bytes, so a portrait photo's stored thumbnail was sideways (S3-01).
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?.let { ExifStripper.uprightBitmap(it, bytes) }
    }

    /**
     * First video frame, decoded at thumbnail size, or null if the file has none this device can
     * read.
     *
     * Reads the *source* URI, which is the user's own plaintext file the bytes are being streamed
     * from anyway — nothing decrypts a vault blob, and nothing lands in the clear on disk.
     *
     * [MediaMetadataRetriever.getScaledFrameAtTime] rather than `getFrameAtTime`, because the
     * unscaled call returns the frame at full video resolution: 1080p is an 8 MB ARGB_8888 bitmap,
     * which on the 48 MiB heap of a `ro.config.low_ram` device is most of what is left after the
     * UI. The scaled call decodes into the requested size, so a 72 MB clip costs the same as a
     * small one. API 27, below this app's minSdk 28. Falls back to the unscaled call only when the
     * container declares no usable dimensions, since the scaled one needs a target size.
     */
    private fun videoFrameThumbnail(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width > 0 && height > 0) {
                val scale = minOf(1f, THUMB_MAX_DIM.toFloat() / maxOf(width, height))
                retriever.getScaledFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    maxOf(1, (width * scale).toInt()),
                    maxOf(1, (height * scale).toInt()),
                )
            } else {
                retriever.getFrameAtTime(0)
            }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}

/**
 * The longest filename this app will keep.
 *
 * Comfortably under the 255-byte limit every filesystem Android runs on enforces, with room for the
 * `(2)` a MediaStore collision appends and for multi-byte characters, since the limit is bytes and
 * this is a character count.
 */
private const val MAX_FILENAME_CHARS = 120

/**
 * A display name this app is willing to store, show and hand to MediaStore.
 *
 * The name arrives from a `DocumentsProvider`, which is any app on the device that registers as
 * one — so it is untrusted input in the ordinary sense, and it was previously written straight into
 * a `ContentValues` DISPLAY_NAME and rendered throughout the UI with no defence of its own.
 *
 * Three things go, and only three: path separators, because a name is not a path and `../` in one
 * is never anything but an attempt; C0 control characters, which do not render and can reorder or
 * truncate a label visually; and length, which is bounded rather than trusted. Everything else is
 * kept — a document legitimately called `Rechnung (März) #12.pdf` must survive, and a sanitiser
 * that strips punctuation to feel safe just makes the app lose the user's own filenames.
 *
 * The empty result falls back rather than propagating: whitespace and control characters really can
 * leave nothing behind, and an empty DISPLAY_NAME is the one input where keeping the original is
 * worse than admitting we have no name for it. Separators do not reach that fallback - they are
 * replaced, not removed, so "///" becomes "___": an odd name, and a harmless one.
 */
internal fun sanitizedDisplayName(raw: String): String {
    val cleaned = raw
        .replace('\\', '_')
        .replace('/', '_')
        .filter { it.code >= 0x20 && it.code != 0x7F }
        .trim()
        .take(MAX_FILENAME_CHARS)
    return cleaned.ifBlank { "file" }
}

/** Shared by [PhotoPickerImport] and [AttachmentPickerViewModel] (11c) — same-module `internal` helper. */
internal fun queryDisplayNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "file"
    var size = 0L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
        }
    }
    // Sanitised at the one place both callers come through, rather than at each use: a name that
    // has already been stored unsanitised is one the next reader has to remember to defend against.
    return sanitizedDisplayName(name) to size
}

/**
 * Counts the plaintext bytes read through it.
 *
 * Needed because [FileBlobStore.encryptToBlob] returns only a blob reference, and the file it
 * writes is the ciphertext: asking the filesystem how big it is answers a different question from
 * the one the user is asking when they look at a file's size.
 */
private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) bytesRead += it }

    override fun skip(n: Long): Long = super.skip(n).also { bytesRead += it }

    override fun markSupported(): Boolean = false
}

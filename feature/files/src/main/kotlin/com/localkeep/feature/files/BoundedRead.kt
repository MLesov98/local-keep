package com.localkeep.feature.files

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** In-memory cap for a picked IMAGE before Exif-strip/encrypt. Videos stream through
 *  [FileBlobStore.encryptToBlob] and are never fully buffered, so they are uncapped. */
internal const val MAX_IMPORT_IMAGE_BYTES: Long = 64L * 1024 * 1024

/** Thrown when a picked file exceeds [MAX_IMPORT_IMAGE_BYTES] — surfaced to the user as a clean
 *  "too large" message by the import callers instead of an OutOfMemoryError crash.
 *
 *  This message is never shown to the user — callers catch it generically and map it to
 *  [MediaErrors.IMPORT_FAILED] (exception text does not reach the UI by
 *  design), so it stays a plain, lowercase, un-extracted diagnostic string. */
class ImportTooLargeException(limitBytes: Long) :
    IOException("image too large to import (over ${limitBytes / (1024 * 1024)} MB)")

/**
 * Reads the stream fully into memory, but never more than [maxBytes] — throwing
 * [ImportTooLargeException] the moment the source would exceed the cap (so at most one buffer beyond
 * the limit is ever touched, never the whole oversized file).
 *
 * Deliberately in its OWN file: an equivalent helper or top-level `const` placed inside
 * PhotoPickerImport.kt intractably tripped a K2 incremental-compile symbol-resolution quirk that
 * poisoned that whole file.
 * Isolating it in a separate compilation unit sidesteps that entirely.
 */
internal fun InputStream.readCapped(maxBytes: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        if (total + n > maxBytes) throw ImportTooLargeException(maxBytes)
        total += n
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Reads at most [maxBytes] without treating a short prefix as an import failure. */
internal fun InputStream.readPrefix(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val out = ByteArrayOutputStream(maxBytes)
    val buffer = ByteArray(minOf(8 * 1024, maxBytes))
    var remaining = maxBytes
    while (remaining > 0) {
        val n = read(buffer, 0, minOf(buffer.size, remaining))
        if (n < 0) break
        if (n == 0) continue
        out.write(buffer, 0, n)
        remaining -= n
    }
    return out.toByteArray()
}

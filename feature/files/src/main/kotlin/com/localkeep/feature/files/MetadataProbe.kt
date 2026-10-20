package com.localkeep.feature.files

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream

/** What [MetadataProbe] found in a stored image. Transient, never serialized. */
enum class MetadataPresence {
    /** Something [ExifStripper] would remove is there. */
    PRESENT,

    /** Nothing [ExifStripper] would remove is there: stripping would hand back the same bytes. */
    ABSENT,

    /** The bytes could not be read this way — HEIF, an unrecognised format, or a damaged file. */
    UNKNOWN,
}

/**
 * Answers "does this image carry what *Delete all metadata* would remove?" from the bytes.
 *
 * Reported from a phone 2026-10-09: the File editor said *"This image may still carry location and
 * camera data"* on every open — over a screenshot with no metadata at all, and again over a photo the
 * user had already cleaned, because nothing records a cleanup (adding a field would be a vault-format
 * change). A warning that is always on is a warning nobody reads. Reading the bytes needs no format
 * change, and it is true on every open, however long ago the cleanup ran.
 *
 * **It mirrors [ExifStripper]'s rules, not a list of "metadata" in general**, because the note it
 * drives sits above that button: ABSENT means a strip would change nothing, PRESENT means it would
 * change something. `MetadataProbeTest` holds the two to agreement on every fixture. Per format:
 *  - JPEG: any APPn segment the stripper drops (everything but JFIF APP0, an `ICC_PROFILE` APP2 and
 *    Adobe APP14), any COM segment, and any byte after the EOI.
 *  - PNG: an `eXIf`, `tEXt`, `zTXt`, `iTXt` or `tIME` chunk, or bytes after IEND.
 *  - WebP: an `EXIF` or `XMP ` chunk.
 *  - HEIF, anything else, and anything malformed: UNKNOWN — the caller keeps the hedged note.
 *
 * Streaming, so a stored image is read through `FileBlobStore.openDecryptingSource` a frame at a
 * time: a 40 MB photo costs a read, never a 40 MB allocation, which matters on the 48 MiB heap of an
 * API 28 low-RAM device. It stops at the first finding, which for a camera photo is the APP1 segment
 * in its first few hundred bytes; only a clean file is read to the end, since a JPEG trailer can only
 * be ruled out there.
 */
object MetadataProbe {

    fun probe(input: InputStream): MetadataPresence {
        val stream = if (input.markSupported()) input else BufferedInputStream(input)
        return try {
            stream.mark(MAGIC_BYTES)
            val head = ByteArray(MAGIC_BYTES)
            val read = stream.readUpTo(head)
            stream.reset()
            val format = ExifStripper.formatOf(head.copyOf(read))
            when (format) {
                ExifStripper.Format.JPEG -> probeJpeg(stream)
                ExifStripper.Format.PNG -> probePng(stream)
                ExifStripper.Format.WEBP -> probeWebp(stream)
                ExifStripper.Format.HEIF, ExifStripper.Format.UNSUPPORTED -> MetadataPresence.UNKNOWN
            }
        } catch (_: EOFException) {
            MetadataPresence.UNKNOWN
        } catch (_: MalformedImage) {
            MetadataPresence.UNKNOWN
        }
    }

    private fun probeJpeg(input: InputStream): MetadataPresence {
        input.skipExactly(2) // SOI
        // The marker the entropy-coded data of a scan ended on, if a scan has just been read.
        var pending: Int? = null
        while (true) {
            val marker = pending ?: run {
                if (input.readByteOrEof() != 0xFF) throw MalformedImage()
                var next = input.readByteOrEof()
                while (next == 0xFF) next = input.readByteOrEof()
                next
            }
            pending = null
            when {
                marker == 0xD9 -> return if (input.read() == -1) MetadataPresence.ABSENT else MetadataPresence.PRESENT
                marker == 0x01 || marker in 0xD0..0xD7 -> Unit
                marker == 0xDA -> {
                    input.skipExactly(input.readLength() - 2)
                    pending = input.skipEntropyData() ?: return MetadataPresence.ABSENT
                }
                else -> {
                    val payload = input.readLength() - 2
                    val isApplicationOrComment = marker in 0xE0..0xEF || marker == 0xFE
                    if (isApplicationOrComment && marker != 0xE0 && marker != 0xEE) {
                        if (marker != 0xE2) return MetadataPresence.PRESENT
                        val prefix = ByteArray(minOf(ICC_PREFIX.size, payload))
                        input.readExactly(prefix)
                        if (!prefix.contentEquals(ICC_PREFIX)) return MetadataPresence.PRESENT
                        input.skipExactly(payload - prefix.size)
                    } else {
                        input.skipExactly(payload)
                    }
                }
            }
        }
    }

    /**
     * Reads through a scan's entropy-coded data — where FF 00 is a stuffed byte and FF D0..D7 a
     * restart — and returns the marker that ended it, or null when the file ends first. A file that
     * ends inside a scan has no EOI and so no trailer; [ExifStripper] keeps the rest as it is.
     */
    private fun InputStream.skipEntropyData(): Int? {
        var b = read()
        while (b != -1) {
            if (b == 0xFF) {
                var next = read()
                while (next == 0xFF) next = read()
                if (next == -1) return null
                if (next != 0x00 && next !in 0xD0..0xD7) return next
            }
            b = read()
        }
        return null
    }

    private fun probePng(input: InputStream): MetadataPresence {
        input.skipExactly(8)
        val header = ByteArray(8)
        while (true) {
            input.readExactly(header)
            val length = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (length < 0) throw MalformedImage()
            val type = String(header, 4, 4, Charsets.US_ASCII)
            if (type in ExifStripper.PNG_DROP) return MetadataPresence.PRESENT
            input.skipExactly(length.toLong() + 4) // data + CRC
            if (type == "IEND") return if (input.read() == -1) MetadataPresence.ABSENT else MetadataPresence.PRESENT
        }
    }

    private fun probeWebp(input: InputStream): MetadataPresence {
        input.skipExactly(12)
        val header = ByteArray(8)
        while (true) {
            val read = input.readUpTo(header)
            if (read == 0) return MetadataPresence.ABSENT
            if (read < header.size) throw MalformedImage()
            val fourcc = String(header, 0, 4, Charsets.US_ASCII)
            if (fourcc == "EXIF" || fourcc == "XMP ") return MetadataPresence.PRESENT
            val size = (header[4].toLong() and 0xFF) or ((header[5].toLong() and 0xFF) shl 8) or
                ((header[6].toLong() and 0xFF) shl 16) or ((header[7].toLong() and 0xFF) shl 24)
            input.skipExactly(size + (size and 1))
        }
    }

    private class MalformedImage : Exception()

    private fun InputStream.readByteOrEof(): Int = read().also { if (it == -1) throw EOFException() }

    private fun InputStream.readLength(): Int {
        val length = (readByteOrEof() shl 8) or readByteOrEof()
        if (length < 2) throw MalformedImage()
        return length
    }

    private fun InputStream.readExactly(into: ByteArray) {
        if (readUpTo(into) != into.size) throw EOFException()
    }

    private fun InputStream.readUpTo(into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val n = read(into, total, into.size - total)
            if (n == -1) break
            total += n
        }
        return total
    }

    private fun InputStream.skipExactly(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                readByteOrEof()
                remaining--
            }
        }
    }

    private fun InputStream.skipExactly(count: Int) = skipExactly(count.toLong())

    /** Enough for every magic [ExifStripper.formatOf] tests, HEIF's `ftyp` brand included. */
    private const val MAGIC_BYTES = 32

    private val ICC_PREFIX = "ICC_PROFILE".toByteArray(Charsets.US_ASCII)
}

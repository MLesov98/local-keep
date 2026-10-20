package com.localkeep.feature.files

/**
 * What a file's own leading bytes say it is, regardless of what its name or its mime type claimed.
 *
 * The UI/UX spec is explicit (§5.4): *"Attachments are shown by what they are, never by
 * assumption."* [fileKindOf] can only read the two things a picker hands over — a mime type and a
 * filename — and both are hearsay. A document picker reports `image/jpeg` for whatever the sender
 * named `.jpg`, so a PDF under that name reached `BitmapFactory`, came back undecodable, and the
 * viewer told the user *"This image … isn't in a format this device can decode. Export it to your
 * gallery"* — calling a PDF an image, while holding a working PDF viewer, and offering to put a
 * non-image into their photo library (release sweep 2026-09-20, finding 4C.1c-liar).
 *
 * This is the second signal, and the authoritative one when the two disagree: the bytes cannot lie
 * about themselves. Only the header is consulted, so it costs nothing on top of a read the caller
 * has already done.
 *
 * `null` is a real answer and the common one — plain text, CSV, a Word document's own `.docx` under
 * an honest name, anything with no magic number at all. It means *"the bytes say nothing"*, not
 * *"the bytes say unknown"*, and the caller must keep whatever [fileKindOf] decided.
 */
internal fun sniffFileKind(header: ByteArray): FileKind? {
    if (header.size < 4) return null

    if (header.startsWith(PDF)) return FileKind.PDF
    if (header.startsWith(PNG)) return FileKind.IMAGE
    if (header.startsWith(JPEG)) return FileKind.IMAGE
    if (header.startsWith(GIF87A) || header.startsWith(GIF89A)) return FileKind.IMAGE
    if (header.startsWith(BMP)) return FileKind.IMAGE
    if (header.startsWith(RIFF) && header.size >= 12) {
        val form = header.ascii(8, 4)
        if (form == "WEBP") return FileKind.IMAGE
        if (form == "WAVE" || form == "AVI ") return FileKind.PLAYABLE
    }
    // ISO base media: `....ftypBRND`. The brand is the only thing separating a HEIF still from the
    // MP4 that shares its container, and the two need different viewers.
    if (header.size >= 12 && header.ascii(4, 4) == "ftyp") {
        return if (header.ascii(8, 4).lowercase() in STILL_IMAGE_BRANDS) FileKind.IMAGE else FileKind.PLAYABLE
    }
    if (header.startsWith(MATROSKA) || header.startsWith(OGG) || header.startsWith(FLAC)) {
        return FileKind.PLAYABLE
    }
    if (header.startsWith(ID3) || header.startsWith(MP3_FRAME)) return FileKind.PLAYABLE
    // Office Open XML is a zip whose FIRST entry is `[Content_Types].xml`, by the standard's own
    // requirement. An ordinary `.zip` is a zip too and must not be offered a text extraction, so the
    // entry name is read rather than the `PK` alone.
    if (header.startsWith(ZIP)) {
        return if (firstZipEntryName(header) == OOXML_FIRST_ENTRY) FileKind.OFFICE else null
    }
    // SVG is `image/svg+xml` and no bitmap decoder will ever open it. It is text, but showing an
    // author their logo as angle brackets is not showing them their logo, so it takes the honest
    // "no viewer for this, save it to your device" path instead.
    if (looksLikeSvg(header)) return FileKind.OTHER

    return null
}

private val PDF = "%PDF".toBytes()
private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val GIF87A = "GIF87a".toBytes()
private val GIF89A = "GIF89a".toBytes()
private val BMP = "BM".toBytes()
private val RIFF = "RIFF".toBytes()
private val MATROSKA = byteArrayOf(0x1A, 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
private val OGG = "OggS".toBytes()
private val FLAC = "fLaC".toBytes()
private val ID3 = "ID3".toBytes()
private val MP3_FRAME = byteArrayOf(0xFF.toByte(), 0xFB.toByte())
private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

/** ISO-BMFF brands that are a still picture rather than a clip — HEIF and AVIF. */
private val STILL_IMAGE_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "mif1", "msf1", "avif", "avis")

private const val OOXML_FIRST_ENTRY = "[Content_Types].xml"

/** How much of a text file to look at for an `<svg` root; past this it is not an SVG. */
private const val SVG_SCAN_BYTES = 512

private fun String.toBytes(): ByteArray = encodeToByteArray()

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}

private fun ByteArray.ascii(offset: Int, length: Int): String =
    String(this, offset, length, Charsets.ISO_8859_1)

/**
 * The name of the zip's first local file entry, or null if the header is truncated.
 *
 * Local file header layout: signature (4), version (2), flags (2), method (2), time (2), date (2),
 * crc (4), sizes (8), then the name length at offset 26 and the name itself at offset 30.
 */
private fun firstZipEntryName(header: ByteArray): String? {
    if (header.size < 30) return null
    val nameLength = (header[26].toInt() and 0xFF) or ((header[27].toInt() and 0xFF) shl 8)
    if (nameLength <= 0 || header.size < 30 + nameLength) return null
    return header.ascii(30, nameLength)
}

/**
 * An `<svg` root element, possibly behind a BOM, whitespace, an XML declaration or a doctype.
 *
 * Only the first [SVG_SCAN_BYTES] are read: an SVG declares itself at the top of the file, and
 * scanning further would start matching ordinary HTML and text files that happen to mention one.
 */
private fun looksLikeSvg(header: ByteArray): Boolean {
    // A UTF-8 byte-order mark is stepped over at the byte level, not the character level: this
    // decodes as ISO-8859-1 so the mark arrives as three separate characters, never as one.
    val from = if (header.startsWith(UTF8_BOM)) UTF8_BOM.size else 0
    val length = minOf(header.size - from, SVG_SCAN_BYTES)
    if (length <= 0) return false
    val head = header.ascii(from, length).trimStart()
    if (!head.startsWith("<")) return false
    return head.contains("<svg", ignoreCase = true)
}

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

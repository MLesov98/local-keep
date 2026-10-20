package com.localkeep.feature.files

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Known-answer tests for the widened metadata stripper (2026-07-29).
 *
 * Fixtures are built here byte by byte rather than checked in as binaries, because the thing under
 * test *is* the byte layout: a committed `.png` proves nothing about which chunk the stripper
 * dropped, and a reviewer cannot see what a fixture contains. Built in code, every test states its
 * own input in the assertion's own terms.
 *
 * The JPEG path is also built byte-by-byte here so the privacy pass remains JVM-testable and cannot
 * regress back to a plaintext scratch-file implementation.
 */
class ExifStripperFormatTest {

    @Test
    fun `jpeg metadata segments are removed without a scratch file`() {
        val exif = "Exif\u0000\u0000GPSLatitude=51.5".toByteArray()
        val app0 = byteArrayOf(0x01, 0x02, 0x03)
        val input = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            jpegSegment(0xE1, exif) +
            jpegSegment(0xE0, app0) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())

        val stripped = ExifStripper.strip(java.io.File("."), input)

        assertFalse(stripped.toString(Charsets.ISO_8859_1).contains("GPSLatitude"))
        assertTrue(stripped.toList().containsAll(app0.toList()))
        assertEquals(0xFF.toByte(), stripped[0])
        assertEquals(0xD9.toByte(), stripped.last())
    }

    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(), marker.toByte(),
            (length ushr 8).toByte(), length.toByte(),
        ) + payload
    }

    // -------------------------------------------------------------------------------------
    // PNG
    // -------------------------------------------------------------------------------------

    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngChunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(intBe(data.size))
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.write(intBe(crc.value.toInt()))
        return out.toByteArray()
    }

    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pngMagic)
        chunks.forEach(out::write)
        return out.toByteArray()
    }

    private fun intBe(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    /** The chunk types present in [bytes], in order. */
    private fun pngChunkTypes(bytes: ByteArray): List<String> = buildList {
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val length = ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            add(type)
            pos += 12 + length
            if (type == "IEND") break
        }
    }

    @Test
    fun `a PNG loses its GPS-bearing text and Exif chunks and keeps its pixels`() {
        val pixels = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val input = png(
            pngChunk("IHDR", ByteArray(13)),
            pngChunk("eXIf", "GPSLatitude=51.5".toByteArray()),
            pngChunk("tEXt", "Software\u0000SomeCamera".toByteArray()),
            pngChunk("iTXt", "XMP\u0000\u0000\u0000\u0000\u0000geo:51.5,0.1".toByteArray()),
            pngChunk("zTXt", "Comment\u0000\u0000junk".toByteArray()),
            pngChunk("tIME", ByteArray(7)),
            pngChunk("IDAT", pixels),
            pngChunk("IEND"),
        )

        val stripped = ExifStripper.strip(cacheDir = java.io.File("."), imageBytes = input)

        assertEquals(listOf("IHDR", "IDAT", "IEND"), pngChunkTypes(stripped))
        assertFalse(
            stripped.toString(Charsets.ISO_8859_1).contains("51.5"),
            "a coordinate survived the strip",
        )
        // The image data must come through untouched — this is a privacy pass, not a re-encode.
        assertTrue(
            stripped.toString(Charsets.ISO_8859_1)
                .contains(pixels.toString(Charsets.ISO_8859_1)),
            "the IDAT payload was altered",
        )
    }

    @Test
    fun `a PNG with nothing to strip round-trips byte-identical`() {
        val clean = png(
            pngChunk("IHDR", ByteArray(13)),
            pngChunk("IDAT", byteArrayOf(9, 9, 9)),
            pngChunk("IEND"),
        )
        assertContentEquals(clean, ExifStripper.strip(java.io.File("."), clean))
    }

    @Test
    fun `a truncated PNG is returned untouched rather than half-rewritten`() {
        // A corrupt attachment is permanent in this app, so the stripper must never emit a prefix
        // of a file it failed to parse. Unchanged is the honest failure.
        val truncated = png(
            pngChunk("IHDR", ByteArray(13)),
            pngChunk("tEXt", "Software\u0000Camera".toByteArray()),
        ).dropLast(20).toByteArray()

        assertContentEquals(truncated, ExifStripper.strip(java.io.File("."), truncated))
    }

    @Test
    fun `a PNG chunk claiming a length past the end is refused`() {
        val out = ByteArrayOutputStream()
        out.write(pngMagic)
        out.write(intBe(Int.MAX_VALUE))
        out.write("tEXt".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(1, 2, 3))
        val lying = out.toByteArray()

        assertContentEquals(lying, ExifStripper.strip(java.io.File("."), lying))
    }

    @Test
    fun `strict sanitizer fails closed for malformed image bytes`() {
        val malformed = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        val result = ExifStripper.sanitize(malformed, "image/jpeg")

        assertTrue(result is SanitizerResult.Failed)
    }

    @Test
    fun `strict sanitizer rejects mismatched MIME instead of trusting provider metadata`() {
        val pngHeader = png(
            pngChunk("IHDR", ByteArray(13)),
            pngChunk("IEND"),
        )

        val result = ExifStripper.sanitize(pngHeader, "image/jpeg")

        assertEquals(
            SanitizerResult.Failed(SanitizerFailureReason.MIME_MISMATCH),
            result,
        )
    }

    @Test
    fun `bounded content sniff identifies an image even when provider MIME is absent`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        assertEquals("image/jpeg", ExifStripper.detectedImageMime(jpeg))
        assertEquals(null, ExifStripper.detectedImageMime("application/octet-stream".toByteArray()))
    }

    @Test
    fun `strict sanitizer rejects sources over the bounded memory limit`() {
        val oversized = ByteArray((MAX_SOURCE_BYTES + 1).toInt())

        val result = ExifStripper.sanitize(oversized, "image/jpeg")

        assertEquals(
            SanitizerResult.Failed(SanitizerFailureReason.SOURCE_TOO_LARGE),
            result,
        )
    }

    // -------------------------------------------------------------------------------------
    // WebP
    // -------------------------------------------------------------------------------------

    private fun intLe(v: Int) = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
    )

    private fun webpChunk(fourcc: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(intLe(data.size))
        out.write(data)
        if (data.size % 2 == 1) out.write(0) // chunks are padded to an even size
        return out.toByteArray()
    }

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        chunks.forEach(body::write)
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intLe(payload.size + 4))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun webpChunkNames(bytes: ByteArray): List<String> = buildList {
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            add(String(bytes, pos, 4, Charsets.US_ASCII))
            pos += 8 + size + (size and 1)
        }
    }

    /** VP8X payload byte 0: bit 3 announces EXIF, bit 2 announces XMP. */
    private fun vp8xFlags(bytes: ByteArray): Int {
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            if (String(bytes, pos, 4, Charsets.US_ASCII) == "VP8X") {
                return bytes[pos + 8].toInt() and 0xFF
            }
            pos += 8 + size + (size and 1)
        }
        error("no VP8X chunk")
    }

    @Test
    fun `a WebP loses EXIF and XMP and keeps its image data`() {
        // Flags say ICC (0x20) + alpha (0x10) + EXIF (0x08) + XMP (0x04) are all present.
        val vp8xPayload = ByteArray(10).apply { this[0] = 0x3C }
        val input = webp(
            webpChunk("VP8X", vp8xPayload),
            webpChunk("ICCP", byteArrayOf(7, 7)),
            webpChunk("VP8 ", byteArrayOf(4, 4, 4, 4)),
            webpChunk("EXIF", "GPSLatitude=51.5".toByteArray()),
            webpChunk("XMP ", "<x:xmpmeta>geo</x:xmpmeta>".toByteArray()),
        )

        val stripped = ExifStripper.strip(java.io.File("."), input)

        assertEquals(listOf("VP8X", "ICCP", "VP8 "), webpChunkNames(stripped))
        assertFalse(stripped.toString(Charsets.ISO_8859_1).contains("51.5"))
    }

    @Test
    fun `the VP8X flags stop announcing chunks that are no longer there`() {
        // A decoder told a chunk exists and then not finding it is entitled to call the file
        // damaged, so removing the chunks without clearing the bits would trade a privacy leak for
        // a corrupt image.
        val vp8xPayload = ByteArray(10).apply { this[0] = 0x3C }
        val stripped = ExifStripper.strip(
            java.io.File("."),
            webp(
                webpChunk("VP8X", vp8xPayload),
                webpChunk("VP8 ", byteArrayOf(4, 4)),
                webpChunk("EXIF", byteArrayOf(1)),
                webpChunk("XMP ", byteArrayOf(2)),
            ),
        )

        val flags = vp8xFlags(stripped)
        assertEquals(0, flags and 0x08, "the EXIF flag is still set")
        assertEquals(0, flags and 0x04, "the XMP flag is still set")
        assertEquals(0x20, flags and 0x20, "the ICC flag was cleared and should not have been")
        assertEquals(0x10, flags and 0x10, "the alpha flag was cleared and should not have been")
    }

    @Test
    fun `the RIFF size field is rewritten to the new length`() {
        // Left at the original value it points past the end of the file.
        val stripped = ExifStripper.strip(
            java.io.File("."),
            webp(
                webpChunk("VP8 ", byteArrayOf(4, 4)),
                webpChunk("EXIF", ByteArray(40)),
            ),
        )
        val declared = (stripped[4].toInt() and 0xFF) or
            ((stripped[5].toInt() and 0xFF) shl 8) or
            ((stripped[6].toInt() and 0xFF) shl 16) or
            ((stripped[7].toInt() and 0xFF) shl 24)

        assertEquals(stripped.size - 8, declared)
    }

    @Test
    fun `an odd-sized WebP chunk keeps its pad byte`() {
        // Three bytes of payload occupy four. Miscounting the pad shifts every later chunk.
        val input = webp(
            webpChunk("VP8 ", byteArrayOf(1, 2, 3)),
            webpChunk("EXIF", byteArrayOf(9)),
        )
        val stripped = ExifStripper.strip(java.io.File("."), input)

        assertEquals(listOf("VP8 "), webpChunkNames(stripped))
    }

    // -------------------------------------------------------------------------------------
    // Dispatch and the opt-out
    // -------------------------------------------------------------------------------------

    @Test
    fun `keepAll returns the bytes untouched whatever the format`() {
        val input = png(
            pngChunk("IHDR", ByteArray(13)),
            pngChunk("tEXt", "Software\u0000Camera".toByteArray()),
            pngChunk("IEND"),
        )
        assertContentEquals(input, ExifStripper.strip(java.io.File("."), input, keepAll = true))
    }

    @Test
    fun `an unrecognised container is passed through whole`() {
        val gif = "GIF89a".toByteArray(Charsets.US_ASCII)
        assertContentEquals(gif, ExifStripper.strip(java.io.File("."), gif))
    }

    // -------------------------------------------------------------------------------------
    // HEIF — the format the stripper cannot rewrite, and therefore the one a caller has to ask
    // about before it promises the user anything.
    //
    // These matter more than the JPEG/PNG/WebP cases, not less. HEIC is the default still format
    // on Samsung since Android 9, so it is the *likely* gallery selection rather than an exotic
    // one, and `strip` returning it whole looks identical to `strip` having cleaned it. A test
    // that only ever fed this a JPEG would have stayed green forever over exactly that gap.
    // -------------------------------------------------------------------------------------

    /** Minimal `ftyp` box: size, "ftyp", major brand, then compatible brands. */
    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val body = (listOf(major) + compatible).joinToString("").toByteArray(Charsets.US_ASCII)
        return intBe(8 + body.size) + "ftyp".toByteArray(Charsets.US_ASCII) + body
    }

    @Test
    fun `a HEIC is recognised and passed through, not silently treated as clean`() {
        val heic = ftyp("heic", "mif1")
        assertEquals(ExifStripper.Format.HEIF, ExifStripper.formatOf(heic))
        assertTrue(ExifStripper.needsTranscodeToStrip(heic))
        assertContentEquals(heic, ExifStripper.strip(java.io.File("."), heic))
    }

    @Test
    fun `the compatible-brands list is searched, not only the major brand`() {
        // What a Samsung actually writes: major `mif1`, with `heic` further down the list. Reading
        // bytes 8..11 alone would classify this as UNSUPPORTED and send it straight to storage.
        assertTrue(ExifStripper.needsTranscodeToStrip(ftyp("mif1", "miaf", "heic")))
    }

    @Test
    fun `an MP4 is not mistaken for a still`() {
        // Same `ftyp` box, different brand. Calling this HEIF would send a recording down the
        // still-image re-encode path, which would turn a video into one frame in the name of
        // privacy. The brand check is what stops that.
        val mp4 = ftyp("isom", "iso2", "avc1", "mp41")
        assertEquals(ExifStripper.Format.UNSUPPORTED, ExifStripper.formatOf(mp4))
        assertFalse(ExifStripper.needsTranscodeToStrip(mp4))
    }

    @Test
    fun `formats the stripper can rewrite do not ask to be transcoded`() {
        assertFalse(ExifStripper.needsTranscodeToStrip(png(pngChunk("IEND"))))
        assertFalse(ExifStripper.needsTranscodeToStrip(webp(webpChunk("VP8 ", byteArrayOf(1)))))
        assertFalse(
            ExifStripper.needsTranscodeToStrip(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            ),
        )
    }

    @Test
    fun `empty and tiny inputs do not throw`() {
        assertContentEquals(ByteArray(0), ExifStripper.strip(java.io.File("."), ByteArray(0)))
        assertContentEquals(byteArrayOf(1), ExifStripper.strip(java.io.File("."), byteArrayOf(1)))
    }

    @Test
    fun `format detection reads the magic bytes, not a file extension`() {
        assertEquals(ExifStripper.Format.PNG, ExifStripper.formatOf(png(pngChunk("IEND"))))
        assertEquals(ExifStripper.Format.WEBP, ExifStripper.formatOf(webp(webpChunk("VP8 ", byteArrayOf(1)))))
        assertEquals(
            ExifStripper.Format.JPEG,
            ExifStripper.formatOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())),
        )
        assertEquals(ExifStripper.Format.UNSUPPORTED, ExifStripper.formatOf(byteArrayOf(0x47, 0x49, 0x46)))
        assertEquals(ExifStripper.Format.HEIF, ExifStripper.formatOf(ftyp("heix")))
        // "RIFF" alone is a WAV as often as a WebP — the second tag has to be checked too.
        val riffWav = "RIFF".toByteArray() + intLe(4) + "WAVE".toByteArray()
        assertEquals(ExifStripper.Format.UNSUPPORTED, ExifStripper.formatOf(riffWav))
    }
}

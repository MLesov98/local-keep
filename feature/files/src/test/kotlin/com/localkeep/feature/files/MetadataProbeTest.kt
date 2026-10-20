package com.localkeep.feature.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Whether a stored image actually carries what "Delete all metadata" would remove.
 *
 * Reported from a phone 2026-10-09: the File editor said *"This image may still carry location and
 * camera data"* on every open — on a screenshot with no metadata at all, and again on a photo that
 * had already been cleaned, because nothing recorded the cleanup. A warning that is always shown is
 * a warning nobody reads. The probe answers the question from the bytes instead, so the note shows
 * only when it is true.
 *
 * The rule it has to keep is agreement with [ExifStripper]: the probe says [MetadataPresence.ABSENT]
 * exactly when stripping would hand back the same bytes. A probe stricter than the stripper shows a
 * note the button cannot clear; a looser one hides a note over metadata the button would remove.
 * Fixtures are built byte by byte, as `ExifStripperFormatTest`'s are, so each test states its input.
 */
class MetadataProbeTest {

    private fun probe(bytes: ByteArray) = MetadataProbe.probe(ByteArrayInputStream(bytes))

    // --- JPEG ---------------------------------------------------------------------------------

    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    /** A scan header, two bytes of entropy data with a stuffed FF 00 and a restart marker, then EOI. */
    private val scanAndEoi = byteArrayOf(
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
        0x11, 0xFF.toByte(), 0x00, 0x22, 0xFF.toByte(), 0xD3.toByte(), 0x33,
        0xFF.toByte(), 0xD9.toByte(),
    )

    private val jfif = jpegSegment(0xE0, "JFIF\u0000\u0001\u0001".toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `a jpeg with an Exif segment carries metadata`() {
        val exif = jpegSegment(0xE1, "Exif\u0000\u0000GPSLatitude=51.5".toByteArray(Charsets.ISO_8859_1))
        assertEquals(MetadataPresence.PRESENT, probe(soi + exif + scanAndEoi))
    }

    @Test
    fun `a jpeg holding only what a decoder needs carries none`() {
        val icc = jpegSegment(0xE2, "ICC_PROFILE\u0000\u0001\u0001".toByteArray(Charsets.ISO_8859_1))
        val adobe = jpegSegment(0xEE, "Adobe".toByteArray(Charsets.ISO_8859_1))
        val quant = jpegSegment(0xDB, ByteArray(65))
        assertEquals(MetadataPresence.ABSENT, probe(soi + jfif + icc + adobe + quant + scanAndEoi))
    }

    @Test
    fun `a comment, a non-ICC APP2 and a vendor APPn all count`() {
        val comment = jpegSegment(0xFE, "made with SomeEditor".toByteArray())
        val mpf = jpegSegment(0xE2, "MPF\u0000".toByteArray(Charsets.ISO_8859_1))
        val vendor = jpegSegment(0xE6, "GoPro".toByteArray())
        for (segment in listOf(comment, mpf, vendor)) {
            assertEquals(MetadataPresence.PRESENT, probe(soi + jfif + segment + scanAndEoi))
        }
    }

    /** S3-03: a Motion Photo's MP4 or a gain map rides after the EOI, and the stripper drops it. */
    @Test
    fun `bytes after the end of the image count`() {
        val trailer = "ftypmp42".toByteArray()
        assertEquals(MetadataPresence.PRESENT, probe(soi + jfif + scanAndEoi + trailer))
    }

    /** The marker that ends the scan is the next segment, not the end — it must still be read. */
    @Test
    fun `metadata after a first scan is still found`() {
        val secondScanHeader = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x44)
        val comment = jpegSegment(0xFE, "late".toByteArray())
        val firstScan = scanAndEoi.copyOfRange(0, scanAndEoi.size - 2)
        val bytes = soi + jfif + firstScan + comment + secondScanHeader + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertEquals(MetadataPresence.PRESENT, probe(bytes))
    }

    @Test
    fun `a jpeg that is not well formed is unknown, not clean`() {
        val broken = soi + byteArrayOf(0x12, 0x34) + scanAndEoi
        assertEquals(MetadataPresence.UNKNOWN, probe(broken))
        assertEquals(MetadataPresence.UNKNOWN, probe(soi + jpegSegment(0xDB, ByteArray(10)).copyOf(8)))
    }

    // --- PNG ----------------------------------------------------------------------------------

    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun intBe(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

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

    private fun png(vararg chunks: ByteArray): ByteArray = pngMagic + chunks.reduce(ByteArray::plus)

    private val ihdr = pngChunk("IHDR", ByteArray(13))
    private val idat = pngChunk("IDAT", ByteArray(20))
    private val iend = pngChunk("IEND")

    /** The everyday case the report was about: a phone screenshot. */
    @Test
    fun `a plain screenshot png carries none`() {
        assertEquals(MetadataPresence.ABSENT, probe(png(ihdr, pngChunk("sRGB", byteArrayOf(0)), idat, iend)))
    }

    @Test
    fun `every chunk the stripper drops counts`() {
        for (type in listOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")) {
            assertEquals(MetadataPresence.PRESENT, probe(png(ihdr, pngChunk(type, ByteArray(7)), idat, iend)), type)
        }
    }

    @Test
    fun `a png that ends before IEND is unknown`() {
        assertEquals(MetadataPresence.UNKNOWN, probe(png(ihdr, idat)))
    }

    // --- WebP ---------------------------------------------------------------------------------

    private fun intLe(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun webpChunk(fourcc: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(intLe(data.size))
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val payload = chunks.reduce(ByteArray::plus)
        return "RIFF".toByteArray() + intLe(payload.size + 4) + "WEBP".toByteArray() + payload
    }

    @Test
    fun `a webp with EXIF or XMP chunks carries metadata, and one without carries none`() {
        val image = webpChunk("VP8 ", ByteArray(11))
        assertEquals(MetadataPresence.PRESENT, probe(webp(image, webpChunk("EXIF", ByteArray(5)))))
        assertEquals(MetadataPresence.PRESENT, probe(webp(image, webpChunk("XMP ", ByteArray(4)))))
        assertEquals(MetadataPresence.ABSENT, probe(webp(image)))
    }

    // --- Everything else ----------------------------------------------------------------------

    /** HEIF cannot be read without a transcode, so the honest answer is "unknown". */
    @Test
    fun `heif and unrecognised formats are unknown`() {
        val heic = intBe(24) + "ftypheic".toByteArray() + ByteArray(12)
        assertEquals(MetadataPresence.UNKNOWN, probe(heic))
        assertEquals(MetadataPresence.UNKNOWN, probe("GIF89a".toByteArray() + ByteArray(20)))
        assertEquals(MetadataPresence.UNKNOWN, probe(ByteArray(0)))
    }

    // --- Agreement with the stripper ----------------------------------------------------------

    /**
     * The property the probe exists to keep. Every fixture the stripper can handle, run through
     * both: ABSENT must mean strip is a no-op, and PRESENT must mean it changes something.
     */
    @Test
    fun `the probe says absent exactly when stripping would change nothing`() {
        val fixtures = listOf(
            soi + jfif + scanAndEoi,
            soi + jfif + jpegSegment(0xE1, "Exif\u0000\u0000x".toByteArray(Charsets.ISO_8859_1)) + scanAndEoi,
            soi + jfif + jpegSegment(0xFE, "c".toByteArray()) + scanAndEoi,
            soi + jfif + scanAndEoi + "trailer".toByteArray(),
            png(ihdr, idat, iend),
            png(ihdr, pngChunk("tEXt", "Software\u0000x".toByteArray(Charsets.ISO_8859_1)), idat, iend),
            webp(webpChunk("VP8 ", ByteArray(11))),
            webp(webpChunk("VP8 ", ByteArray(11)), webpChunk("EXIF", ByteArray(6))),
        )
        for (bytes in fixtures) {
            val stripped = ExifStripper.strip(java.io.File("."), bytes)
            val unchanged = stripped.contentEquals(bytes)
            when (probe(bytes)) {
                MetadataPresence.ABSENT -> assertContentEquals(bytes, stripped, "probe said absent, strip changed it")
                MetadataPresence.PRESENT -> assertFalse(unchanged, "probe said present, strip changed nothing")
                MetadataPresence.UNKNOWN -> error("a fixture the stripper handles must not be unknown")
            }
        }
    }
}

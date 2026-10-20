package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S3-03, 2026-10-02: the JPEG stripper copied everything from the first SOS to the end of the
 * buffer, and dropped only APP1, APP13 and Adobe's XMP. So bytes after the EOI survived -- a
 * Samsung or Pixel Motion Photo's appended MP4, an Ultra HDR gain map, a vendor trailer -- along
 * with C2PA/JUMBF (APP11), Multi-Picture Format (APP2 "MPF"), COM and the vendor APP3..APP6
 * segments, while the editor said "Metadata removed".
 *
 * Fixtures are built byte by byte, as the rest of this module's stripper tests are. `strip` is the
 * JVM entry point to the same `stripJpeg` that `sanitize` runs; it returns its input unchanged if
 * the filter throws, so a filter that fails reads as "trailer still present" here, never as a pass.
 */
class ExifStripperJpegTrailerTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val soi = bytes(0xFF, 0xD8)
    private val eoi = bytes(0xFF, 0xD9)

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return bytes(0xFF, marker, length ushr 8, length and 0xFF) + payload
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    /** A one-component scan header (length 8) followed by entropy-coded [data]. */
    private fun scan(data: ByteArray) = bytes(0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00) + data

    private val jfif = segment(0xE0, ascii("JFIF\u0000") + bytes(1, 1, 0, 0, 1, 0, 1, 0, 0))

    private fun strip(input: ByteArray): ByteArray = ExifStripper.strip(File("."), input)

    private fun ByteArray.text(): String = toString(Charsets.ISO_8859_1)

    @Test
    fun `bytes after the EOI are dropped`() {
        val image = soi + jfif + scan(bytes(0x12, 0xFF, 0x00, 0x34)) + eoi
        val trailer = ascii("....ftypmp42 MOTION-PHOTO-VIDEO-PAYLOAD")

        assertContentEquals(image, strip(image + trailer))
    }

    @Test
    fun `a second JPEG appended after the EOI is dropped`() {
        // What Multi-Picture Format and Ultra HDR append: a whole further image, with its own Exif.
        val image = soi + jfif + scan(bytes(0x12, 0x34)) + eoi
        val gainMap = soi + segment(0xE1, ascii("Exif\u0000\u0000GPSLatitude=51.5")) + scan(bytes(0x56)) + eoi

        val stripped = strip(image + gainMap)

        assertContentEquals(image, stripped)
        assertFalse(stripped.text().contains("GPSLatitude"))
    }

    @Test
    fun `C2PA, MPF, vendor and comment segments are removed`() {
        val image = soi +
            jfif +
            segment(0xEB, ascii("JP\u0000\u0000c2pa-claim-author=Jane Doe")) +
            segment(0xE2, ascii("MPF\u0000MPImageList")) +
            segment(0xE3, ascii("Samsung-AF-Info")) +
            segment(0xE6, ascii("GoPro-GPMF")) +
            segment(0xFE, ascii("Shot on Jane's phone")) +
            scan(bytes(0x12)) +
            eoi

        val stripped = strip(image)

        listOf("c2pa", "Jane", "MPF", "Samsung", "GoPro", "phone").forEach {
            assertFalse(stripped.text().contains(it), "'$it' survived the strip")
        }
        assertContentEquals(soi + jfif + scan(bytes(0x12)) + eoi, stripped)
    }

    /** Control: removing everything unknown must not remove what a decoder needs to show the picture. */
    @Test
    fun `JFIF, the ICC profile and Adobe's colour flag are kept`() {
        val icc = segment(0xE2, ascii("ICC_PROFILE\u0000") + bytes(1, 1, 9, 9, 9))
        val adobe = segment(0xEE, ascii("Adobe") + bytes(0, 100, 0, 0, 0, 0, 1))
        val quantTable = segment(0xDB, ByteArray(65))
        val image = soi + jfif + icc + adobe + quantTable + scan(bytes(0x12)) + eoi

        assertContentEquals(image, strip(image))
    }

    @Test
    fun `every scan of a progressive JPEG is kept, and so is the data between them`() {
        // Entropy-coded data holds stuffed FF 00 bytes and restart markers, neither of which ends a
        // scan; a second scan follows a DHT. Cutting at the first FFxx would lose half the picture.
        val firstScan = scan(bytes(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56, 0xFF, 0xD7, 0x78))
        val huffman = segment(0xC4, ByteArray(17))
        val secondScan = scan(bytes(0x9A, 0xFF, 0x00, 0xBC))
        val image = soi + jfif + segment(0xC2, ByteArray(15)) + firstScan + huffman + secondScan + eoi

        assertContentEquals(image, strip(image + ascii("TRAILER")))
    }

    @Test
    fun `metadata placed between two scans is removed`() {
        val between = segment(0xE1, ascii("Exif\u0000\u0000GPSLatitude=51.5"))
        val head = soi + jfif + scan(bytes(0x12))
        val tail = scan(bytes(0x34)) + eoi

        val stripped = strip(head + between + tail)

        assertFalse(stripped.text().contains("GPSLatitude"))
        assertContentEquals(head + tail, stripped)
    }

    /** Control: a truncated file has no EOI and so no trailer; it is passed through, as before. */
    @Test
    fun `a JPEG that ends inside its scan is kept whole`() {
        val truncated = soi + jfif + scan(bytes(0x12, 0x34, 0x56))

        assertContentEquals(truncated, strip(truncated))
    }

    @Test
    fun `a JPEG whose last byte is a lone 0xFF is kept whole`() {
        val truncated = soi + jfif + scan(bytes(0x12, 0x34, 0xFF))

        assertContentEquals(truncated, strip(truncated))
    }

    @Test
    fun `the output still starts with SOI and ends with the EOI`() {
        val stripped = strip(soi + jfif + scan(bytes(0x12)) + eoi + ascii("TRAILER"))

        assertTrue(stripped.first() == 0xFF.toByte() && stripped[1] == 0xD8.toByte())
        assertTrue(stripped[stripped.size - 2] == 0xFF.toByte() && stripped.last() == 0xD9.toByte())
    }
}

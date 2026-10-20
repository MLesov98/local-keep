package com.localkeep.feature.files

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression for 4C.1c-liar / 4C.2-svg (release sweep 2026-09-20).
 *
 * A file was routed to a viewer by its mime prefix alone, so a PDF a sender had named `t-liar.jpg`
 * reached `BitmapFactory`, failed, and was reported as *"This image decrypted correctly but isn't in
 * a format this device can decode. Export it to your gallery to open it in another app."* — the app
 * calling a PDF an image while holding a working PDF viewer, and offering to put a non-image into
 * the photo library. UI/UX spec §5.4: attachments are shown by what they are, never by assumption.
 */
class FileSignatureTest {

    @Test
    fun `a pdf under a jpg name is a pdf`() {
        // The exact fixture from the sweep: `%PDF-1.4` bytes, `.jpg` name, `image/jpeg` mime.
        val bytes = "%PDF-1.4\n1 0 obj\n".encodeToByteArray()

        assertEquals(FileKind.IMAGE, fileKindOf("image/jpeg", "t-liar.jpg"), "the claim, unchanged")
        assertEquals(FileKind.PDF, sniffFileKind(bytes), "the bytes, which win")
    }

    @Test
    fun `an svg never reaches a bitmap decoder`() {
        assertEquals(FileKind.OTHER, fileKindOf("image/svg+xml", "logo.svg"))
        assertEquals(FileKind.OTHER, fileKindOf("application/octet-stream", "logo.svg"))
        assertEquals(
            FileKind.OTHER,
            sniffFileKind("""<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"/>""".encodeToByteArray()),
        )
        assertEquals(FileKind.OTHER, sniffFileKind("<svg viewBox=\"0 0 1 1\"/>".encodeToByteArray()))
    }

    @Test
    fun `the real bitmap formats are recognised by their own headers`() {
        assertEquals(FileKind.IMAGE, sniffFileKind(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(FileKind.IMAGE, sniffFileKind(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(FileKind.IMAGE, sniffFileKind("GIF89a....".encodeToByteArray()))
        assertEquals(FileKind.IMAGE, sniffFileKind("RIFF????WEBPVP8 ".encodeToByteArray()))
    }

    @Test
    fun `an iso container is split by brand, so a heif still is not a clip`() {
        assertEquals(FileKind.IMAGE, sniffFileKind(isoBox("heic")))
        assertEquals(FileKind.IMAGE, sniffFileKind(isoBox("mif1")))
        assertEquals(FileKind.PLAYABLE, sniffFileKind(isoBox("isom")))
        assertEquals(FileKind.PLAYABLE, sniffFileKind(isoBox("mp42")))
    }

    @Test
    fun `an office file is a zip whose first entry names it, and a plain zip is not`() {
        assertEquals(FileKind.OFFICE, sniffFileKind(zipWithFirstEntry("[Content_Types].xml")))
        // A `.zip` of holiday photos must not be offered a text extraction.
        assertNull(sniffFileKind(zipWithFirstEntry("holiday/DSC_0001.jpg")))
    }

    @Test
    fun `bytes with nothing to say leave the declared kind alone`() {
        assertNull(sniffFileKind("Just a plain sentence in a text file.".encodeToByteArray()))
        assertNull(sniffFileKind(ByteArray(0)))
        assertNull(sniffFileKind(byteArrayOf(1, 2)))
        // An HTML page mentioning an <svg> further down is not an SVG.
        assertNull(sniffFileKind("Charts are drawn with <svg> elements these days.".encodeToByteArray()))
    }

    /** `size(4) ftyp BRND` — the leading bytes of every ISO base-media file. */
    private fun isoBox(brand: String): ByteArray =
        byteArrayOf(0, 0, 0, 0x20) + "ftyp".encodeToByteArray() + brand.encodeToByteArray() + ByteArray(8)

    private fun zipWithFirstEntry(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write("body".encodeToByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}

package com.localkeep.feature.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stripping a photo's metadata must not silently rotate it.
 *
 * A camera usually records how the phone was held as an Exif tag rather than by writing rotated
 * pixels, so the file on disk is landscape and `TAG_ORIENTATION` says "turn this 90 degrees". The
 * sanitizer deletes the whole APP1/Exif segment, which is exactly right for GPS and exactly wrong
 * for that one tag: nothing downstream reads it back — the viewer decodes with a bare
 * `BitmapFactory`, which ignores it, and there is no rotate control anywhere — so the photo was
 * stored, shown and exported sideways, and no later step could recover the lost angle.
 *
 * The existing JPEG tests could not see this. They assert what was *removed* (no GPS string, no
 * Exif segment) and, since 2026-08-25, that the result still decodes; none of them asks what the
 * decoded pixels look like, and a 90-degree rotation leaves every one of those assertions true.
 *
 * Instrumented rather than JVM because the whole subject is pixels: Robolectric's `BitmapFactory`
 * does not decode a real JPEG, so a `getPixel` here would be asserting against a shadow rather than
 * against the platform decoder that ships.
 *
 * Fixtures are built in code rather than checked in, for the reason the sibling JVM suite gives:
 * the byte layout *is* the thing under test, and a reviewer cannot see what a committed binary
 * contains.
 */
@RunWith(AndroidJUnit4::class)
class ExifOrientationBakedInTest {

    private val width = 64
    private val height = 32

    /** A recognisable payload in a second APP1, so "the metadata still goes" is asserted directly. */
    private val gpsMarker = "GPSLatitude=51.5"

    /**
     * Landscape, left half red, right half blue — asymmetric on the axis a rotation moves, which a
     * gradient or a flat fill would not be. Two flat halves also survive 4:2:0 chroma subsampling
     * anywhere except right at the seam, and every pixel this test reads is far from it.
     */
    private fun halvedJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (x < width / 2) Color.RED else Color.BLUE)
            }
        }
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out), "fixture failed to encode")
        bitmap.recycle()
        return out.toByteArray()
    }

    /**
     * The smallest legal Exif payload that says one thing: a big-endian TIFF block whose IFD0 holds
     * `TAG_ORIENTATION` and nothing else.
     *
     * Written out byte by byte rather than through `ExifInterface.saveAttributes`, which only
     * writes to a file or a descriptor and would drag a plaintext scratch file into a test about
     * the one code path that must never create one.
     */
    private fun exifOrientationPayload(orientation: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("Exif".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x00, 0x00)) // the two pad bytes that close the Exif identifier
        out.write("MM".toByteArray(Charsets.US_ASCII)) // byte order: big-endian
        out.write(byteArrayOf(0x00, 0x2A)) // TIFF magic, 42
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x08)) // IFD0 starts right after this 8-byte header
        out.write(byteArrayOf(0x00, 0x01)) // one directory entry
        out.write(byteArrayOf(0x01, 0x12)) // tag 0x0112, Orientation
        out.write(byteArrayOf(0x00, 0x03)) // type 3, SHORT
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01)) // count 1
        // A SHORT is written into the first two bytes of the four-byte value field, left-aligned;
        // the remaining two are padding, not a second value.
        out.write(byteArrayOf(0x00, orientation.toByte(), 0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // no IFD1
        return out.toByteArray()
    }

    /** `length` counts its own two bytes — the off-by-two that once produced FF DB FF DB output. */
    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(),
            marker.toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        ) + payload
    }

    /** [orientation] null means no Exif segment at all; the GPS payload is always present. */
    private fun fixture(orientation: Int?): ByteArray {
        val jpeg = halvedJpeg()
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2) // SOI
        if (orientation != null) out.write(jpegSegment(0xE1, exifOrientationPayload(orientation)))
        out.write(jpegSegment(0xE1, gpsMarker.toByteArray(Charsets.US_ASCII)))
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap =
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "did not decode" }

    /** JPEG is lossy, so the assertion is on which channel dominates, never on an exact ARGB value. */
    private fun channelAt(bitmap: Bitmap, x: Int, y: Int): String {
        val pixel = bitmap.getPixel(x, y)
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return when {
            r > g + 60 && r > b + 60 -> "red"
            b > r + 60 && b > g + 60 -> "blue"
            else -> "neither (r=$r g=$g b=$b)"
        }
    }

    private fun sanitized(bytes: ByteArray): ByteArray {
        val result = ExifStripper.sanitize(bytes, "image/jpeg")
        assertTrue(result is SanitizerResult.Success, "expected Success, got $result")
        return result.bytes
    }

    /**
     * The fixture control. A hand-built Exif block that no parser accepts would make every
     * assertion below fail for a reason that has nothing to do with the sanitizer.
     */
    @Test
    fun theFixtureCarriesAReadableOrientationTag() {
        val bytes = fixture(ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            "the fixture's Exif block is not readable, so nothing else here proves anything",
        )
        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains(gpsMarker), "GPS payload missing")
    }

    /**
     * The second control, and the reason the defect exists at all: the platform decoder does not
     * apply the tag. If this ever starts failing, the app got an orientation-aware decode from
     * somewhere and the fix below is redundant rather than wrong.
     */
    @Test
    fun theDecoderIgnoresTheOrientationTag() {
        val bitmap = decode(fixture(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(width to height, bitmap.width to bitmap.height)
        assertEquals("red", channelAt(bitmap, width / 4, height / 2), "left half should still be red")
        bitmap.recycle()
    }

    /** The regression: orientation 6 must arrive in the vault as pixels, not as a deleted tag. */
    @Test
    fun aRotatedPhotoIsSanitizedUpright() {
        val bitmap = decode(sanitized(fixture(ExifInterface.ORIENTATION_ROTATE_90)))
        assertEquals(
            height to width,
            bitmap.width to bitmap.height,
            "a 90-degree tag must transpose the stored pixels",
        )
        // Rotating clockwise sends the source's left edge to the top edge.
        assertEquals("red", channelAt(bitmap, height / 2, width / 4), "top half should be red")
        assertEquals("blue", channelAt(bitmap, height / 2, width * 3 / 4), "bottom half should be blue")
        bitmap.recycle()
    }

    /** The mirrored values are not a rotation and are just as common — a front camera writes them. */
    @Test
    fun aMirroredPhotoIsSanitizedUnmirrored() {
        val bitmap = decode(sanitized(fixture(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)))
        assertEquals(width to height, bitmap.width to bitmap.height, "a flip must not transpose")
        assertEquals("blue", channelAt(bitmap, width / 4, height / 2), "left half should be blue")
        assertEquals("red", channelAt(bitmap, width * 3 / 4, height / 2), "right half should be red")
        bitmap.recycle()
    }

    /**
     * The invariant the fix is not allowed to trade away. The orientation is *consumed* — read,
     * applied to the pixels, then discarded with the rest of the segment — so a sanitized image
     * carries no Exif whatever its tag said.
     */
    @Test
    fun noMetadataSurvivesTheOrientationFix() {
        val out = sanitized(fixture(ExifInterface.ORIENTATION_ROTATE_90))
        val text = out.toString(Charsets.ISO_8859_1)
        assertFalse(text.contains(gpsMarker), "the GPS payload survived")
        // The APP1 Exif identifier, escapes and all — four bytes of scan data could spell "Exif"
        // by chance, and the six-byte identifier could not.
        assertFalse(text.contains("Exif\u0000\u0000"), "an Exif segment survived")
        assertEquals(
            0,
            ExifInterface(ByteArrayInputStream(out)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0),
            "the orientation tag was preserved rather than consumed",
        )
    }

    /**
     * The common case must stay free. An image that is already upright is filtered marker by
     * marker, exactly as before — and a re-encode could not produce the same bytes as the filter,
     * so byte equality with the no-tag fixture is what proves nothing was recompressed.
     */
    @Test
    fun anUprightPhotoIsNotRecompressed() {
        assertContentEquals(
            sanitized(fixture(null)),
            sanitized(fixture(ExifInterface.ORIENTATION_NORMAL)),
            "orientation 1 took the decode-and-re-encode path",
        )
    }
}

package com.localkeep.feature.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S3-01, 2026-10-02: a File item picked from the gallery keeps its original bytes, so a portrait
 * photo (landscape pixels plus Exif Orientation=6, what a stock Samsung or Pixel camera writes)
 * was drawn sideways by the viewer and by the stored thumbnail, until "Delete all metadata"
 * happened to bake the rotation in. `BitmapFactory` ignores the tag -- the sibling instrumented
 * `ExifOrientationBakedInTest.theDecoderIgnoresTheOrientationTag` pins that.
 *
 * Native graphics, because the subject is pixels: the legacy shadow `BitmapFactory` does not decode
 * a JPEG, so a `getPixel` here would be asserting against a shadow. Fixtures are built in code, as
 * the rest of this module's image tests are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExifOrientationOnDisplayTest {

    private val width = 64
    private val height = 32

    /** Landscape, left half red and right half blue: asymmetric on the axis a rotation moves. */
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

    /** The smallest Exif payload that says one thing: a big-endian IFD0 holding only the orientation. */
    private fun exifOrientationPayload(orientation: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("Exif".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x00, 0x00))
        out.write("MM".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x00, 0x2A))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x08))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x01, 0x12))
        out.write(byteArrayOf(0x00, 0x03))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
        out.write(byteArrayOf(0x00, orientation.toByte(), 0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        return out.toByteArray()
    }

    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    /** [orientation] null means no Exif segment at all. */
    private fun fixture(orientation: Int?): ByteArray {
        val jpeg = halvedJpeg()
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2) // SOI
        if (orientation != null) out.write(jpegSegment(0xE1, exifOrientationPayload(orientation)))
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }

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

    /**
     * The control: without it every assertion below could fail because the fixture's Exif block is
     * unreadable, or pass because the platform decoder started honouring the tag by itself.
     */
    @Test
    fun `the fixture carries a readable tag that the platform decoder ignores`() {
        val bytes = fixture(ExifInterface.ORIENTATION_ROTATE_90)

        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ExifStripper.displayOrientation(bytes))
        val raw = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertEquals(width to height, raw.width to raw.height, "BitmapFactory started honouring the tag")
    }

    @Test
    fun `the viewer draws a portrait photo upright`() {
        val (bitmap, dimensions) = decodeViewerBitmapWithDimensions(fixture(ExifInterface.ORIENTATION_ROTATE_90))

        val decoded = assertNotNull(bitmap)
        assertEquals(height to width, decoded.width to decoded.height, "the tag was not applied")
        // Rotating clockwise sends the source's left edge to the top edge.
        assertEquals("red", channelAt(decoded, height / 2, width / 4), "top half should be red")
        assertEquals("blue", channelAt(decoded, height / 2, width * 3 / 4), "bottom half should be blue")
        assertEquals("$height × $width", dimensions, "the Details size must describe the upright picture")
    }

    @Test
    fun `the filmstrip fallback draws a portrait photo upright`() {
        val decoded = assertNotNull(decodeThumbnailBitmap(fixture(ExifInterface.ORIENTATION_ROTATE_90)))

        assertEquals(height to width, decoded.width to decoded.height)
        assertEquals("red", channelAt(decoded, height / 2, width / 4), "top half should be red")
    }

    @Test
    fun `the thumbnail stored at import is upright`() {
        val importer = PhotoPickerImport(ApplicationProvider.getApplicationContext(), mock<FileBlobStore>())

        val decoded = assertNotNull(importer.thumbnailBitmap(fixture(ExifInterface.ORIENTATION_ROTATE_90)))

        assertEquals(height to width, decoded.width to decoded.height)
        assertEquals("red", channelAt(decoded, height / 2, width / 4), "top half should be red")
        assertEquals("blue", channelAt(decoded, height / 2, width * 3 / 4), "bottom half should be blue")
    }

    /** A front camera writes the mirrored values as readily as a rotated shot writes 6. */
    @Test
    fun `a mirrored photo is drawn unmirrored`() {
        val decoded = assertNotNull(
            decodeViewerBitmapWithDimensions(fixture(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)).first,
        )

        assertEquals(width to height, decoded.width to decoded.height, "a flip must not transpose")
        assertEquals("blue", channelAt(decoded, width / 4, height / 2), "left half should be blue")
        assertEquals("red", channelAt(decoded, width * 3 / 4, height / 2), "right half should be red")
    }

    /** Control: an upright photo, with or without a tag, is not touched. */
    @Test
    fun `a photo with no rotation is drawn as stored`() {
        listOf(null, ExifInterface.ORIENTATION_NORMAL).forEach { orientation ->
            val decoded = assertNotNull(decodeViewerBitmapWithDimensions(fixture(orientation)).first)

            assertEquals(width to height, decoded.width to decoded.height, "orientation=$orientation")
            assertEquals("red", channelAt(decoded, width / 4, height / 2), "orientation=$orientation")
        }
    }

    /**
     * Control for the other direction: the sanitizer consumes the tag when it bakes the rotation
     * into the pixels, so what it returns must not be turned a second time on display. Also the
     * proof that the strip is untouched -- the tag is gone from what it returns.
     */
    @Test
    fun `a sanitized photo is not rotated twice`() {
        val result = ExifStripper.sanitize(fixture(ExifInterface.ORIENTATION_ROTATE_90), "image/jpeg")
        assertTrue(result is SanitizerResult.Success, "expected Success, got $result")

        // ExifInterface reports an Exif-less JPEG as UNDEFINED (0) rather than NORMAL; neither rotates.
        assertTrue(
            ExifStripper.displayOrientation(result.bytes) in
                setOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED),
            "the orientation tag survived the sanitizer",
        )
        val decoded = assertNotNull(decodeViewerBitmapWithDimensions(result.bytes).first)
        assertEquals(height to width, decoded.width to decoded.height)
        assertEquals("red", channelAt(decoded, height / 2, width / 4), "top half should be red")
    }
}

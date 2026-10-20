package com.localkeep.feature.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S3-03 at the level the user sees it: `sanitize` is what the editor's "Metadata removed" rests on,
 * and its output has to be a picture as well as a clean one. The byte-built fixtures in
 * [ExifStripperJpegTrailerTest] cannot say whether a real encoder's JPEG still decodes after the
 * scan is cut at the EOI -- the 2026-08-25 corruption (FF DB FF DB) passed every test that only
 * looked for what was removed -- so this one runs `Bitmap.compress` output through the same path
 * and decodes what comes back.
 *
 * Native graphics, because the claim is about what the platform decoder accepts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SanitizedJpegDropsTrailerAndStillDecodesTest {

    private val width = 64
    private val height = 32

    private fun encoderJpeg(): ByteArray {
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

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    @Test
    fun `a landscape photo with an appended clip comes back clean and decodable`() {
        val jpeg = encoderJpeg()
        val trailer = "....ftypmp42 MOTION-PHOTO-VIDEO-PAYLOAD".toByteArray(Charsets.US_ASCII)
        // Metadata after the SOI: a C2PA box and a comment, as a camera or editor writes them.
        val withMetadata = jpeg.copyOfRange(0, 2) +
            segment(0xEB, "JP..c2pa-claim-author=Jane Doe".toByteArray(Charsets.US_ASCII)) +
            segment(0xFE, "Shot on Jane's phone".toByteArray(Charsets.US_ASCII)) +
            jpeg.copyOfRange(2, jpeg.size)

        val result = ExifStripper.sanitize(withMetadata + trailer, "image/jpeg")

        assertTrue(result is SanitizerResult.Success, "expected Success, got $result")
        val text = result.bytes.toString(Charsets.ISO_8859_1)
        listOf("MOTION-PHOTO", "ftypmp42", "c2pa", "Jane").forEach {
            assertFalse(text.contains(it), "'$it' survived 'Metadata removed'")
        }
        assertEquals(0xD9.toByte(), result.bytes.last(), "the output must end at the EOI")
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)) {
            "the sanitized bytes no longer decode"
        }
        assertEquals(width to height, decoded.width to decoded.height)
        val left = decoded.getPixel(width / 4, height / 2)
        assertTrue(Color.red(left) > Color.blue(left) + 60, "left half should still be red")
        decoded.recycle()
    }
}

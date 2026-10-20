package com.localkeep.feature.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A sanitized JPEG has to still be a JPEG.
 *
 * Every existing test of this path asserts what was *removed* — no GPS string, no Exif segment —
 * and none asserts that what comes back can still be decoded. So a strip that produced structurally
 * broken output passed the whole suite. It reached a device: on 2026-08-25 "Delete all metadata" on
 * a 50 MP HEIC logged `sanitize -> Success` and then failed one line later, because the thumbnail
 * re-decode of the sanitized bytes returned `-1x-1`, i.e. the platform could not parse the image the
 * sanitizer had just declared good.
 *
 * The fixture is a `Bitmap.compress` JPEG because that is exactly what the HEIF path feeds back in:
 * HEIF cannot be stripped in place, so it is decoded and re-encoded, and the re-encoded bytes then
 * go through `stripJpeg` like any other JPEG. It is also the shape least covered by the byte-built
 * fixtures in the sibling JVM suite, which construct their JPEGs by hand and therefore only ever
 * exercise the marker layout the test author already had in mind.
 */
@RunWith(AndroidJUnit4::class)
class StripJpegRoundTripTest {

    private fun encoderJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // A gradient rather than a flat fill: a single colour compresses to a trivial scan and can
        // hide a defect that only shows up with real entropy in the data.
        for (x in 0 until width step 8) {
            for (y in 0 until height step 8) {
                bitmap.setPixel(x, y, (0xFF000000 or ((x * 7 + y * 13) and 0xFFFFFF).toLong()).toInt())
            }
        }
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out), "fixture failed to encode")
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun decodedSize(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    /** The control: the fixture itself must decode, or the assertion below proves nothing. */
    @Test
    fun theEncoderFixtureDecodes() {
        assertEquals(640 to 480, decodedSize(encoderJpeg(640, 480)))
    }

    @Test
    fun aSanitizedEncoderJpegStillDecodes() {
        val original = encoderJpeg(640, 480)
        val result = ExifStripper.sanitize(original, "image/jpeg")
        assertTrue(result is SanitizerResult.Success, "expected Success, got $result")
        assertEquals(
            640 to 480,
            decodedSize(result.bytes),
            "the sanitizer returned Success but the bytes no longer decode — " +
                "${original.size} bytes in, ${result.bytes.size} out",
        )
    }
}

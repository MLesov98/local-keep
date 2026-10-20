package com.localkeep.feature.files

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A photograph bigger than 25 megapixels can be stripped, and therefore saved.
 *
 * `sanitize` used to ask [MAX_DECODED_PIXELS] of every format. That is a *decode* budget: HEIF is
 * re-encoded through a Bitmap and has to answer for it, but JPEG, PNG and WebP are byte surgery and
 * never construct one. So the cap guarded an allocation that does not happen, and the way it
 * "guarded" it was to refuse the image — `Failed` became `UnstrippableImageException` became
 * "Metadata could not be removed", and the file could not be saved at all. Every still above 25 MP:
 * a 50 MP Samsung frame is 8160x6120, and panoramas and flatbed scans go further.
 *
 * Instrumented rather than JVM, deliberately. `BitmapFactory` is stubbed in unit tests and reports
 * 0x0, so *every* image reads as malformed there and no unit test can observe a successful
 * `sanitize` at all — which is exactly why the JVM suite stayed green while this shipped.
 *
 * The fixture declares its size in the IHDR instead of allocating 30 million pixels: bounds decoding
 * reads the header and stops, which is all the gate under test consults. [theFixtureReallyIsOversized]
 * asserts that premise rather than assuming it, so a platform decoder that refused the fixture would
 * fail loudly here instead of making [anOversizedPngIsStrippedRatherThanRefused] pass for the wrong
 * reason.
 */
@RunWith(AndroidJUnit4::class)
class ExifStripperLargeImageTest {

    private val width = 6000
    private val height = 5000 // 30.0 MP, against a 25 MP cap.

    @Test
    fun theFixtureReallyIsOversized() {
        val bytes = oversizedPng()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(width, bounds.outWidth, "fixture header not readable — the test below proves nothing")
        assertEquals(height, bounds.outHeight, "fixture header not readable — the test below proves nothing")
        assertTrue(
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > 25_000_000L,
            "fixture is not over the 25 MP cap this test exists to prove is gone",
        )
    }

    @Test
    fun anOversizedPngIsStrippedRatherThanRefused() {
        val result = ExifStripper.sanitize(oversizedPng(), "image/png")
        assertTrue(
            result is SanitizerResult.Success,
            "a 30 MP PNG must sanitize; got $result",
        )
        assertEquals("image/png", result.mime)
        assertFalse(
            result.bytes.toString(Charsets.ISO_8859_1).contains("GPSLatitude"),
            "the oversized image was accepted but its GPS was left in place",
        )
    }

    /**
     * The case that shipped broken to the principal's phone on 2026-08-25.
     *
     * HEIF is the one format that *is* decoded — it cannot be stripped in place, so it is re-encoded
     * through a Bitmap — and it is also what a Samsung shoots by default, at 50 MP. Scoping the old
     * flat 25 MP cap to the decoding path therefore fixed nothing for the images that matter most:
     * 8160x6120 is 49,939,200 pixels, and the button still answered "Metadata could not be removed".
     * Measured that day: `sanitize` returned `Failed(DECODED_PIXELS_TOO_LARGE)` and
     * `HeifTranscoder.toJpeg` returned null for a real 50 MP HEIC.
     *
     * The cap is now a memory question rather than a constant, and a 50 MP still is answerable on
     * any device that can hold it at RGB_565 — ~100 MB rather than the ~200 MB ARGB_8888 wants.
     * This asserts the answer is yes here. It is heap-dependent by design: on a device genuinely too
     * small the honest answer is null, and this test would fail loudly rather than a user finding
     * out through a dead button.
     */
    @Test
    fun aFiftyMegapixelStillIsAdmitted() {
        val fiftyMegapixels = 8160L * 6120L // 49,939,200 — a Samsung 50 MP frame.
        assertTrue(
            isAdmissiblePixelCount(fiftyMegapixels),
            "a 50 MP still ($fiftyMegapixels px) must not be refused before anything is attempted",
        )
        assertTrue(
            DECODE_CONFIG_LADDER.isNotEmpty(),
            "no decode config to fall back to means a large still can never be stripped",
        )
    }

    /**
     * Arithmetic nonsense is still refused. This is the control for the assertion above: a gate
     * rewritten to admit everything would pass that one while removing the guard entirely.
     */
    @Test
    fun anAbsurdPixelCountIsStillRefused() {
        assertFalse(isAdmissiblePixelCount(MAX_DECODED_PIXELS + 1L))
        assertFalse(isAdmissiblePixelCount(0L))
        assertFalse(isAdmissiblePixelCount(-1L))
    }

    /**
     * The control. If the change had simply deleted every guard, the assertion above would pass for
     * the wrong reason — so the one cap that is *not* about decoding must still refuse.
     */
    @Test
    fun theSourceSizeCapStillRefuses() {
        val huge = ByteArray((MAX_SOURCE_BYTES + 1L).toInt())
        val result = ExifStripper.sanitize(huge, "image/png")
        assertEquals(
            SanitizerResult.Failed(SanitizerFailureReason.SOURCE_TOO_LARGE),
            result,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Fixture. Built byte by byte for the same reason the sibling JVM suite builds its own: the
    // thing under test is the byte layout, and a checked-in binary hides what it contains.
    // ---------------------------------------------------------------------------------------

    private fun oversizedPng(): ByteArray {
        val ihdr = ByteArrayOutputStream().apply {
            write(intBe(width))
            write(intBe(height))
            write(byteArrayOf(8, 6, 0, 0, 0)) // 8-bit RGBA, no interlace
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            write(chunk("IHDR", ihdr))
            write(chunk("eXIf", "Exif\u0000\u0000GPSLatitude=51.5".toByteArray(Charsets.ISO_8859_1)))
            write(chunk("IDAT", ByteArray(16)))
            write(chunk("IEND"))
        }.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
        return ByteArrayOutputStream().apply {
            write(intBe(data.size))
            write(typeBytes)
            write(data)
            write(intBe(crc))
        }.toByteArray()
    }

    private fun intBe(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )
}

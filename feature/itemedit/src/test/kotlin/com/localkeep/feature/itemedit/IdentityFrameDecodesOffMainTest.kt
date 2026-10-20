package com.localkeep.feature.itemedit

import android.graphics.Bitmap
import android.os.Looper
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The identity camera decodes each still off the main thread.
 *
 * The capture callback used to run `BitmapFactory.decodeByteArray` on a full 12 MP JPEG, plus the rail
 * thumbnail, on the main thread — about a second of frozen viewfinder per shot (QA S3-07,
 * 2026-10-02). Under Robolectric the test thread *is* the main looper, so a decode that stayed on the
 * caller's thread is seen here as a decode on main.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentityFrameDecodesOffMainTest {

    @Test
    fun theCapturedStillIsDecodedOffTheMainThread() {
        var decodedOnMain: Boolean? = null
        runBlocking {
            decodeCapturedFrame(ByteArray(8)) {
                decodedOnMain = Looper.myLooper() == Looper.getMainLooper()
                Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            }
        }
        assertEquals(false, decodedOnMain, "the 12 MP decode ran on the main thread")
    }

    @Test
    fun anUndecodableStillYieldsNoFrame() = runBlocking {
        assertNull(decodeCapturedFrame(ByteArray(8)) { null })
    }
}

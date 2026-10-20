package com.localkeep.feature.files

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S3-07, 2026-10-02: `CameraCaptureController` handed CameraX the main executor, so
 * `onCaptureSuccess` -- and with it `imageProxyToUprightJpeg`, a full-resolution decode, a rotated
 * copy and a q92 re-encode -- ran on the UI thread for every portrait shot. The Identity wizard then
 * decoded the same still again in its callback.
 *
 * A stand-in "main" thread is a single named executor; the real `ImageCapture` is a mock whose
 * `takePicture` runs the callback on whichever executor it was given, as CameraX does. The rotation
 * is 0, so the JPEG path returns the buffer without touching `BitmapFactory`: what is asserted is
 * *where* the work runs, not what it produces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureWorkRunsOffMainTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun namedExecutor(name: String) = Executors.newSingleThreadExecutor { Thread(it, name) }

    @Test
    fun `a capture is processed off the main executor and delivered on it`() {
        val main = namedExecutor("fake-main")
        val processedOn = AtomicReference<String>()
        val deliveredOn = AtomicReference<String>()
        val delivered = AtomicReference<ByteArray?>()
        val done = CountDownLatch(1)

        val image = mock<ImageProxy> {
            on { planes } doAnswer {
                processedOn.set(Thread.currentThread().name)
                arrayOf(
                    mock<ImageProxy.PlaneProxy> { on { buffer } doAnswer { ByteBuffer.wrap(jpeg) } },
                )
            }
            on { imageInfo } doAnswer { mock<ImageInfo> { on { rotationDegrees } doAnswer { 0 } } }
        }
        val capture = mock<ImageCapture>()
        doAnswer {
            val executor = it.getArgument<Executor>(0)
            val callback = it.getArgument<ImageCapture.OnImageCapturedCallback>(1)
            executor.execute { callback.onCaptureSuccess(image) }
            null
        }.whenever(capture).takePicture(any<Executor>(), any<ImageCapture.OnImageCapturedCallback>())
        val controller = CameraCaptureController().also {
            it.imageCapture = capture
            it.executor = main
        }

        controller.capture { bytes ->
            deliveredOn.set(Thread.currentThread().name)
            delivered.set(bytes)
            done.countDown()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "the capture never completed")
        assertNotEquals("fake-main", processedOn.get(), "decode/rotate/re-encode ran on the main executor")
        assertEquals("fake-main", deliveredOn.get(), "the result must be delivered on the main executor")
        assertContentEquals(jpeg, delivered.get())
        main.shutdownNow()
    }

    @Test
    fun `a failed capture is reported on the main executor`() {
        val main = namedExecutor("fake-main")
        val deliveredOn = AtomicReference<String>()
        val delivered = AtomicReference<ByteArray?>(byteArrayOf(1))
        val done = CountDownLatch(1)
        val capture = mock<ImageCapture>()
        doAnswer {
            val executor = it.getArgument<Executor>(0)
            val callback = it.getArgument<ImageCapture.OnImageCapturedCallback>(1)
            executor.execute {
                callback.onError(
                    androidx.camera.core.ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "boom", null),
                )
            }
            null
        }.whenever(capture).takePicture(any<Executor>(), any<ImageCapture.OnImageCapturedCallback>())
        val controller = CameraCaptureController().also {
            it.imageCapture = capture
            it.executor = main
        }

        controller.capture { bytes ->
            deliveredOn.set(Thread.currentThread().name)
            delivered.set(bytes)
            done.countDown()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "the error never reached the caller")
        assertEquals("fake-main", deliveredOn.get())
        assertEquals(null, delivered.get())
        main.shutdownNow()
    }
}

package com.localkeep.feature.files

import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * S3-02, 2026-10-02: `CameraPreview` bound CameraX inside a listener posted to the main looper with
 * no failure handling at all. On a device with no back camera, or one where the camera is blocked by
 * device or work-profile policy, `bindToLifecycle` throws `IllegalArgumentException` (and the
 * provider future fails with `ExecutionException`); an exception from a posted runnable ends the
 * process, so three screens -- the File editor, Attachments and the Identity wizard -- crashed
 * mid-form instead of saying the camera is unavailable, as `QrScanner` already does.
 *
 * The listener's body is [CameraPreviewSession.onProviderReady] / [CameraPreviewSession.onProviderFailed],
 * so the failure paths are driven there, without a camera.
 */
class CameraUnavailableTest {

    @Test
    fun `a bind that throws marks the camera unavailable instead of crashing`() {
        val controller = CameraCaptureController()
        val session = CameraPreviewSession(controller)
        val provider = mock<ProcessCameraProvider>()

        session.onProviderReady(provider) {
            throw IllegalArgumentException("Provided camera selector unable to resolve a camera")
        }

        assertTrue(controller.unavailable, "the failure must be reported, not thrown or swallowed")
        assertFalse(controller.isReady, "the shutter must stay disabled")
        verify(provider).unbindAll()
    }

    @Test
    fun `a provider that never started marks the camera unavailable`() {
        val controller = CameraCaptureController()
        val session = CameraPreviewSession(controller)

        session.onProviderFailed()

        assertTrue(controller.unavailable)
        assertFalse(controller.isReady)
    }

    /** Control: a bind that works is not reported as a failure. */
    @Test
    fun `a bind that succeeds leaves the camera available`() {
        val controller = CameraCaptureController()
        val session = CameraPreviewSession(controller)

        session.onProviderReady(mock<ProcessCameraProvider>()) { controller.isReady = true }

        assertFalse(controller.unavailable)
        assertTrue(controller.isReady)
    }

    /** Control: a failure that arrives after the screen has gone must not touch the next one's state. */
    @Test
    fun `a provider failure after disposal is ignored`() {
        val controller = CameraCaptureController()
        val session = CameraPreviewSession(controller)

        session.dispose()
        session.onProviderFailed()

        assertFalse(controller.unavailable)
    }

    @Test
    fun `disposing clears an unavailable flag so a reused controller starts clean`() {
        val controller = CameraCaptureController()
        val session = CameraPreviewSession(controller)
        session.onProviderFailed()

        session.dispose()

        assertFalse(controller.unavailable)
    }
}

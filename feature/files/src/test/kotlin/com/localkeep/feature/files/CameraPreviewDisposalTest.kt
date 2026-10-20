package com.localkeep.feature.files

import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class CameraPreviewDisposalTest {

    @Test
    fun `disposing an active preview unbinds provider and resets controller`() {
        val controller = CameraCaptureController().also { it.isReady = true }
        val session = CameraPreviewSession(controller)
        val provider = mock<ProcessCameraProvider>()

        session.onProviderReady(provider) {}
        session.dispose()

        verify(provider).unbindAll()
        assertFalse(controller.isReady)
    }

    @Test
    fun `provider completing after disposal is unbound without binding`() {
        val controller = CameraCaptureController().also { it.isReady = true }
        val session = CameraPreviewSession(controller)
        val provider = mock<ProcessCameraProvider>()
        var bound = false

        session.dispose()
        session.onProviderReady(provider) { bound = true }

        assertFalse(bound)
        verify(provider).unbindAll()
        assertFalse(controller.isReady)
    }
}

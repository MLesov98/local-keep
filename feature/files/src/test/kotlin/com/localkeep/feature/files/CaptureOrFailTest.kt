package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CaptureToVaultScreen] used to silently drop a null capture result (`ImageCaptureException` or
 * a failed JPEG encode): the user tapped Capture and nothing visibly happened.
 * `IdentityCapture` handles the same null correctly. This tests the seam [captureOrFail] was
 * extracted for — same shape as the fix, without needing a Composable or a bound camera.
 */
class CaptureOrFailTest {

    @Test
    fun `a null capture result reports failure instead of doing nothing`() {
        // An unbound controller — no imageCapture, no executor — is exactly the null-result case:
        // CameraCaptureController.capture() calls onResult(null) synchronously (see its own KDoc).
        val controller = CameraCaptureController()
        var captured: ByteArray? = null
        var failed = false

        captureOrFail(controller, onCaptured = { captured = it }, onFailed = { failed = true })

        assertTrue(failed, "a null capture result must be reported, not swallowed")
        assertNull(captured)
    }
}

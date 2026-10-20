package com.localkeep.feature.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * A live camera preview with no chrome of its own, plus a handle to fire the shutter.
 *
 * Split out of [CaptureToVaultScreen] on 2026-08-19 because the Identity capture flow needs the
 * same viewfinder underneath a completely different set of controls — a step prompt, a skip, a
 * retake — and the only way to reuse a screen that owns its own Cancel/Capture row is to keep
 * bolting parameters onto it until it belongs to nobody. The preview is the reusable part; the
 * chrome never is.
 *
 * Nothing here touches disk. The frame arrives as JPEG bytes in memory and leaves the same way.
 */
class CameraCaptureController internal constructor() {
    internal var imageCapture: ImageCapture? = null
    internal var executor: Executor? = null

    /** True once the provider has bound and the shutter can actually fire. */
    var isReady by mutableStateOf(false)
        internal set

    /**
     * True when the camera cannot be used at all: no back camera, blocked by device or work-profile
     * policy, or the provider failed to start. [CameraPreview] says so in place of the viewfinder
     * and the shutter stays disabled (S3-02, 2026-10-02) -- the same answer [QrScanner] gives.
     */
    var unavailable by mutableStateOf(false)
        internal set

    /**
     * Fires the shutter. [onResult] gets upright JPEG bytes, or null if the capture failed.
     *
     * A failure is reported rather than swallowed: this is the one control on the screen, and a
     * shutter that does nothing and says nothing is indistinguishable from a frozen app.
     */
    fun capture(onResult: (ByteArray?) -> Unit) {
        val capture = imageCapture
        val exec = executor
        if (capture == null || exec == null) {
            onResult(null)
            return
        }
        // The callback runs on a worker, not on [exec] (the main executor). A still is 12 MP: the
        // decode, the rotated copy and the q92 re-encode in `imageProxyToUprightJpeg` froze the UI
        // for the length of every portrait shot (S3-07, 2026-10-02). Only the result is posted
        // back to [exec], where every caller updates its state.
        capture.takePicture(
            captureWorker,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = runCatching { imageProxyToUprightJpeg(image) }.getOrNull()
                    image.close()
                    exec.execute { onResult(bytes) }
                }

                override fun onError(exception: ImageCaptureException) = exec.execute { onResult(null) }
            },
        )
    }

    internal fun reset() {
        imageCapture = null
        executor = null
        isReady = false
        unavailable = false
    }

}

/** Owns the provider binding lifetime independently of the asynchronous provider future. */
internal class CameraPreviewSession(private val controller: CameraCaptureController) {
    private var disposed = false
    private var provider: ProcessCameraProvider? = null

    fun onProviderReady(provider: ProcessCameraProvider, bind: (ProcessCameraProvider) -> Unit) {
        if (disposed) {
            provider.unbindAll()
            return
        }
        this.provider = provider
        // `bindToLifecycle` throws on a device with no back camera, and this runs from a listener
        // posted to the main looper, where an exception ends the process and the unsaved form with
        // it (S3-02, 2026-10-02). Reported as an unavailable camera instead, as QrScanner does.
        runCatching { bind(provider) }.onFailure {
            runCatching { provider.unbindAll() }
            controller.unavailable = true
        }
    }

    /** The provider never started: camera disabled by policy, or CameraX failed to initialise. */
    fun onProviderFailed() {
        if (!disposed) controller.unavailable = true
    }

    fun dispose() {
        disposed = true
        provider?.unbindAll()
        provider = null
        controller.reset()
    }
}

@Composable
fun rememberCameraCaptureController(): CameraCaptureController = remember { CameraCaptureController() }

/**
 * The viewfinder. Give it a bounded [modifier] — `PreviewView` reports no intrinsic height, so a
 * bare `fillMaxWidth()` measures to zero and the preview is not small but absent (measured
 * 2026-08-19, when exactly that shipped for a day).
 */
@Composable
fun CameraPreview(controller: CameraCaptureController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val session = remember(controller) { CameraPreviewSession(controller) }
    DisposableEffect(session) {
        onDispose { session.dispose() }
    }
    // A Box around the viewfinder so the failure can be drawn in its place at the same size, rather
    // than leaving a black rectangle that reads as a frozen app. The caller's bounds apply to the
    // Box; the view fills it.
    Box(modifier) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            // The same pair the QR scanner sets, for the same reason and with the same consequence
            // if it is dropped. `PreviewView`'s default `PERFORMANCE` mode draws onto a
            // `SurfaceView`, which the system composites in its own layer where no ancestor clip can
            // reach it — the preview then spills out of whatever rounded container it was put in,
            // on every device rather than on some. `COMPATIBLE` draws onto a `TextureView` inside
            // this app's hierarchy, which clips normally; `FILL_CENTER` covers the box whatever
            // aspect ratio the sensor returns. The long form of the argument is in QrScanner.
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = runCatching { providerFuture.get() }.getOrNull()
                if (provider == null) {
                    session.onProviderFailed()
                    return@addListener
                }
                session.onProviderReady(provider) { activeProvider ->
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    activeProvider.unbindAll()
                    activeProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    controller.imageCapture = capture
                    controller.executor = executor
                    controller.isReady = true
                }
            }, executor)
            previewView
        },
    )
    if (controller.unavailable) {
        val message = stringResource(R.string.files_capture_camera_unavailable)
        Box(
            Modifier.fillMaxSize().background(LkTheme.colors.ground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                message,
                style = LkText.body,
                color = LkTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = LkSpace.lg).announce(message),
            )
        }
    }
    }
}

/**
 * Where a capture is decoded, rotated and re-encoded: one daemon thread, started on the first shot.
 * Shared rather than per-controller, because a controller is disposed with its screen and CameraX
 * delivers a cancelled capture's error to its executor afterwards -- an executor shut down by then
 * would reject it. Only one capture is ever in flight (the shutter is disabled meanwhile).
 */
private val captureWorker: Executor by lazy {
    Executors.newSingleThreadExecutor { task -> Thread(task, "lk-capture").apply { isDaemon = true } }
}

/** Converts a JPEG-format [ImageProxy] (CameraX's in-memory capture path) to upright JPEG bytes.
 *  Decode/rotate/recompress happens entirely in memory — no file, ever. */
internal fun imageProxyToUprightJpeg(image: ImageProxy): ByteArray {
    val buffer = image.planes[0].buffer
    val jpegBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) return jpegBytes

    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    val out = ByteArrayOutputStream()
    rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
    bitmap.recycle()
    rotated.recycle()
    return out.toByteArray()
}

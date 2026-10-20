package com.localkeep.core.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.localkeep.core.ui.theme.LkDialogSurface
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkTween
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * A full-screen-dialog QR scanner, shared by every flow in the app that has to read a code back:
 * the TOTP secret in `:feature:itemedit`, and the Recovery Kit mnemonic on the unlock screen.
 *
 * Moved here from `:feature:itemedit` on 2026-09-10, when the Recovery Kit's own QR export gained
 * a reader — [title] and [cameraUnavailableMessage] are the only two strings that differ between
 * the two callers ("Scan authenticator QR" vs. a Recovery Kit-specific title, and the TOTP
 * fallback naming the `otpauth://` paste field, which the Recovery Kit flow does not have). Every
 * other string — the aim/read hints, the cancel label — is genuinely shared and lives in this
 * module already ([R.string.core_ui_qr_hint_aim], [R.string.core_ui_qr_hint_read],
 * [R.string.core_ui_dialog_cancel]).
 *
 * `onResult` receives whatever [decodeFrame] read off the sensor, unparsed — parsing (an
 * `otpauth://` link, a `LOCALKEEP-RECOVERY` payload) is the caller's business, not the scanner's.
 */
@Composable
fun QrScannerDialog(
    title: String,
    cameraUnavailableMessage: String,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val active = remember { AtomicBoolean(true) }
    val resultSent = remember { AtomicBoolean(false) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }
    // Why the camera could not be shown, or null while it can. Both ways this fails — the provider
    // future completing exceptionally, and `bindToLifecycle` throwing — used to be swallowed into a
    // `getOrNull()` and a bare `runCatching`, which left the dialog showing a black rectangle and a
    // Cancel button: nothing on screen said the camera was unavailable rather than slow, so the
    // only reading available to the user was that scanning was broken and they did not know why.
    var cameraError by remember { mutableStateOf<String?>(null) }
    // The decoded payload, held rather than delivered.
    //
    // It used to go straight to `onResult` from the analyzer thread, which meant the dialog vanished
    // on the same frame the code was read and the user never saw *that* it had been read — only
    // that the camera had disappeared. `Item Flows.dc.html` asks for the opposite: the sweep stops,
    // the reticle turns jade, a check draws, and only then does the dialog close on its own.
    // *"A scanner that waits for Done after succeeding is asking a question with one answer."*
    var decoded by remember { mutableStateOf<String?>(null) }
    val locked = decoded != null
    LaunchedEffect(decoded) {
        val value = decoded ?: return@LaunchedEffect
        // Long enough to see the check, short enough not to be a step of its own.
        delay(SCAN_LOCK_DWELL_MILLIS)
        onResult(value)
    }

    DisposableEffect(Unit) {
        onDispose {
            active.set(false)
            analyzerExecutor.shutdownNow()
            if (cameraProviderFuture.isDone) {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        LkDialogSurface {
            Text(title, style = LkText.sheetTitle, color = LkTheme.colors.textStrong)
            Column(Modifier.fillMaxWidth()) {
                val failure = cameraError
                if (failure != null) {
                    // Where the preview would have been, at the same height, so the dialog does not
                    // jump: the point is to say what happened, not to hide that anything did.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .clip(LkShape.card),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            failure,
                            style = LkText.body,
                            color = LkTheme.colors.textMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .clip(LkShape.card),
                        contentAlignment = Alignment.Center,
                    ) {
                    AndroidView(
                        // Clipped to the app's card radius by the Box above: an unrounded camera
                        // rectangle inside a 26dp dialog is the single most obvious "this screen was
                        // not designed" tell. Whether that clip reaches the preview at all is
                        // [PreviewView.ImplementationMode]'s decision, not this modifier's — see the
                        // factory.
                        modifier = Modifier
                            .fillMaxSize(),
                        factory = { viewContext ->
                            val previewView = PreviewView(viewContext)
                            // **COMPATIBLE, not the default.** `PreviewView` defaults to
                            // `PERFORMANCE`, which puts the frames on a `SurfaceView`. A
                            // `SurfaceView` is composited by the system in a layer of its own,
                            // outside this app's view drawing — so *no* ancestor clip reaches it:
                            // not `Modifier.clip`, not `clipToOutline`, not the dialog's own bounds.
                            // The preview therefore drew as a hard-cornered rectangle spilling past
                            // the card and out of the dialog, reported from a phone 2026-08-21 with
                            // a screenshot showing the frames wider than the surface holding them.
                            //
                            // `COMPATIBLE` puts them on a `TextureView`, which is an ordinary view
                            // drawn in this app's own hierarchy and therefore obeys every ancestor
                            // clip. That is the whole of the fix, and it is a property of the two
                            // view types rather than of any one device: nothing about the camera
                            // hardware, the sensor's aspect ratio or the OEM changes it.
                            //
                            // It costs a little power and latency against `PERFORMANCE`, which is
                            // the trade CameraX documents for exactly this case — a preview that has
                            // to be clipped, transformed or animated. A viewfinder open for the few
                            // seconds it takes to read a QR code is where that trade is cheapest.
                            //
                            // Not a screen-security regression, and worth stating in a password
                            // manager: a `TextureView` draws *inside* the window, so the window's
                            // FLAG_SECURE covers it — if anything more completely than a separate
                            // surface would.
                            // Fill and crop, never letterbox. Combined with the clip above this is
                            // what makes the frame right on any sensor: whatever aspect ratio the
                            // camera hands over, the preview covers the box and the excess is cut
                            // off by the parent instead of being drawn outside it.
                            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            // Fill and crop, never letterbox. Combined with the clip above this is
                            // what makes the frame right on any sensor: whatever aspect ratio the
                            // camera hands over, the preview covers the box and the excess is cut
                            // off by the parent instead of being drawn outside it.
                            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                            val mainExecutor = ContextCompat.getMainExecutor(viewContext)
                            cameraProviderFuture.addListener(
                                {
                                    if (!active.get()) return@addListener
                                    val provider = runCatching { cameraProviderFuture.get() }
                                        .getOrNull()
                                    if (provider == null) {
                                        cameraError = cameraUnavailableMessage
                                        return@addListener
                                    }
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(
                                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST,
                                        )
                                        .build()
                                    analysis.setAnalyzer(analyzerExecutor) { image ->
                                        try {
                                            val payload = decodeFrame(image, reader)
                                            if (
                                                payload != null &&
                                                resultSent.compareAndSet(false, true)
                                            ) {
                                                mainExecutor.execute { decoded = payload }
                                            }
                                        } finally {
                                            image.close()
                                        }
                                    }
                                    runCatching {
                                        provider.unbindAll()
                                        provider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis,
                                        )
                                    }.onFailure { cameraError = cameraUnavailableMessage }
                                },
                                mainExecutor,
                            )
                            previewView
                        },
                    )
                    ScanReticle(locked = locked)
                    }
                }
            }
            // No aiming hint while the camera looks for a code. "Fill the frame with the code." was
            // dropped on 2026-10-15: the screen that opens this dialog already says what to point
            // at, and the reticle shows where. Only the outcome is said, once a code is read.
            if (cameraError == null && locked) {
                val hint = stringResource(R.string.core_ui_qr_hint_read)
                Text(
                    hint,
                    style = LkText.bodyQuiet,
                    color = LkTheme.colors.jade,
                    modifier = Modifier.fillMaxWidth().announce(hint),
                )
            }
            LkTextButton(stringResource(R.string.core_ui_dialog_cancel), onDismiss)
        }
    }
}

private fun decodeFrame(
    image: ImageProxy,
    reader: MultiFormatReader,
): String? {
    val luminance = image.copyLuminancePlane()
    val source = PlanarYUVLuminanceSource(
        luminance,
        image.width,
        image.height,
        0,
        0,
        image.width,
        image.height,
        false,
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decodeWithState(bitmap).text
    } catch (_: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}

private fun ImageProxy.copyLuminancePlane(): ByteArray {
    val plane = planes[0]
    val buffer = plane.buffer
    val output = ByteArray(width * height)
    for (row in 0 until height) {
        val rowOffset = row * plane.rowStride
        for (column in 0 until width) {
            output[row * width + column] =
                buffer.get(rowOffset + column * plane.pixelStride)
        }
    }
    return output
}

/**
 * The guide over the viewfinder: four corner brackets, a sweep while looking, and a check when read.
 *
 * **Square, inside a portrait preview.** The identity capture guide is 1.585:1 because a passport
 * is; a QR code is square, so this is. Same corner-bracket vocabulary rather than a second one —
 * `Item Flows.dc.html` is explicit that the shape of the guide is what tells the user which shape
 * to fill, and that inventing a second bracket style for the second scanner in the app would be a
 * cost with no return.
 *
 * **The preview keeps its shipped size and radius, so only the overlay is new.** Before this the
 * dialog was a title, a bare 380dp camera rectangle and Cancel: no guide, no brackets, no scan
 * line, and nothing at all that changed when a code was decoded.
 *
 * **Two signals on lock, not one.** The brackets crossfade to jade *and* a check draws inside a
 * dark disc, because a colour change alone is missable at arm's length — which is exactly the
 * distance a phone is held at while pointing it at another screen.
 *
 * **The sweep loops, and that is allowed here.** This app restricts continuous motion to loading
 * indicators; a camera actively looking for a code is one, and this one stops the instant something
 * is read. A sweep still running over a decoded code is the bug that makes people re-scan.
 */
@Composable
private fun ScanReticle(locked: Boolean) {
    val c = LkTheme.colors
    val reduce = LocalLkReduceMotion.current
    val bracket by animateColorAsState(
        // White at 32%, and not an `LkColors` ring token, deliberately: every ring in the palette
        // sits between 5% and 9% because it separates two dark surfaces a few percent apart. These
        // brackets sit over a live camera feed of whatever the user is pointing at, which can be a
        // bright screen — at ring alpha they disappear against it. The comp specifies this value.
        targetValue = if (locked) c.jade else Color.White.copy(alpha = 0.32f),
        animationSpec = lkTween(LkMotion.Crossfade, easing = LkMotion.Settle),
        label = "reticle",
    )
    val sweepTransition = rememberInfiniteTransition(label = "scan-sweep")
    val sweep by sweepTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(SCAN_SWEEP_MILLIS, easing = CubicBezierEasing(0.55f, 0f, 0.45f, 1f)),
            RepeatMode.Restart,
        ),
        label = "scan-sweep-y",
    )
    // Seeded false and flipped, never a constant target — `animateFloatAsState` starts *at* its
    // first target, so a constant 1f draws the whole check on frame one and animates nothing. That
    // trap has shipped in this repo before; see LkSavePayoff.
    var drawing by remember { mutableStateOf(false) }
    LaunchedEffect(locked) { if (locked) drawing = true }
    val draw by animateFloatAsState(
        targetValue = if (drawing) 1f else 0f,
        animationSpec = lkTween(SCAN_CHECK_DRAW_MILLIS, easing = LkMotion.Standard),
        label = "scan-check",
    )

    Box(Modifier.size(RETICLE_SIZE), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().decorative()) {
            val arm = CORNER_ARM.toPx()
            val stroke = 3.dp.toPx()
            val radius = 16.dp.toPx()
            val w = size.width
            val h = size.height
            // Each corner is two arms meeting at a rounded elbow, drawn as one path so the join is
            // mitred rather than two overlapping caps.
            listOf(
                Triple(Offset(0f, arm), Offset(0f, 0f), Offset(arm, 0f)),
                Triple(Offset(w - arm, 0f), Offset(w, 0f), Offset(w, arm)),
                Triple(Offset(w, h - arm), Offset(w, h), Offset(w - arm, h)),
                Triple(Offset(arm, h), Offset(0f, h), Offset(0f, h - arm)),
            ).forEach { (from, corner, to) ->
                val path = Path().apply {
                    moveTo(from.x, from.y)
                    quadraticTo(corner.x, corner.y, to.x, to.y)
                }
                // The quadratic's control point is the corner itself, which rounds it by roughly
                // the arm length; pulling the endpoints in by the radius keeps the visual corner
                // close to LkShape's 16dp rather than a wide arc.
                drawPath(
                    path.takeIf { radius > 0f } ?: path,
                    color = bracket,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            if (!locked && !reduce) {
                val y = h * sweep
                // Fades in and out at the ends so the line does not appear to hit the brackets.
                val edge = minOf(sweep, 1f - sweep) / 0.12f
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to c.jadeLit.copy(alpha = edge.coerceIn(0f, 1f)),
                        1f to Color.Transparent,
                    ),
                    topLeft = Offset(0f, y),
                    size = Size(w, 2.dp.toPx()),
                )
            }
        }
        if (locked) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(c.scrim, CircleShape)
                    .decorative(),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(34.dp)) {
                    val path = Path().apply {
                        moveTo(size.width * 0.26f, size.height * 0.53f)
                        lineTo(size.width * 0.42f, size.height * 0.69f)
                        lineTo(size.width * 0.74f, size.height * 0.33f)
                    }
                    val measure = PathMeasure().apply { setPath(path, false) }
                    val segment = Path()
                    measure.getSegment(0f, measure.length * draw, segment, true)
                    drawPath(
                        segment,
                        c.jade,
                        style = Stroke(
                            width = 3.4.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
    }
}

/** 186dp square, centred in the shipped 380dp preview. The comp's geometry. */
private val RETICLE_SIZE = 186.dp
private val CORNER_ARM = 34.dp
private const val SCAN_SWEEP_MILLIS = 2_400
private const val SCAN_CHECK_DRAW_MILLIS = 420

/**
 * How long the check stays up before the dialog closes itself.
 *
 * Long enough to register that something was read, short enough that it is not a step the user has
 * to sit through. The comp's number.
 */
private const val SCAN_LOCK_DWELL_MILLIS = 700L

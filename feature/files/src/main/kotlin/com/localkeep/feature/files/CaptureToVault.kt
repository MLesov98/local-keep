package com.localkeep.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkTheme

/** In-app camera capture straight to the vault (§5.5) — the original frame never touches the gallery
 *  and never touches disk, encrypted or not (in-memory capture; see [CameraCapture]).
 *
 *  The viewfinder and the shutter plumbing live in [CameraPreview] / [CameraCaptureController];
 *  this is just the Cancel/Capture chrome the standalone File editor embeds. The Identity flow
 *  builds its own chrome on the same two pieces. */
@Composable
fun CaptureToVaultScreen(
    onCaptured: (ByteArray) -> Unit,
    onCancel: () -> Unit,
    // 15-3: while the strip-plus-encrypt pipeline runs after a shutter tap, the viewfinder sits
    // there with no progress indicator; without this a second tap fires the camera again and its
    // bytes are silently dropped. Mirrors IdentityCapture's `!state.identityPhotoSaving` gate.
    isProcessing: Boolean = false,
) {
    val controller = rememberCameraCaptureController()
    // Mirrors IdentityCapture's captureFailed: a null result (ImageCaptureException or a failed
    // JPEG encode) must be reported, not swallowed — see the KDoc on [CameraCaptureController].
    var captureFailed by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(LkTheme.colors.ground)) {
        CameraPreview(
            controller = controller,
            // The aspect ratio is load-bearing, not styling — see the note on [CameraPreview].
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .padding(horizontal = 16.dp)
                .clip(LkShape.card),
        )
        if (captureFailed) {
            val message = stringResource(R.string.files_capture_failed)
            LkInlineError(
                message,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).announce(message),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LkSecondaryButton(
                text = stringResource(R.string.files_capture_cancel),
                modifier = Modifier.weight(1f),
                onClick = onCancel,
            )
            LkPrimaryButton(
                text = stringResource(R.string.files_capture_action),
                modifier = Modifier.weight(1f),
                enabled = controller.isReady && !isProcessing,
                onClick = {
                    captureFailed = false
                    captureOrFail(controller, onCaptured) { captureFailed = true }
                },
            )
        }
    }
}

/**
 * The shutter's result handling, factored out of the `onClick` lambda so it is a plain function a
 * JVM test can call directly — [CameraCaptureController]'s own KDoc says a failure must be
 * reported, not swallowed, and this is the seam where that either happens or doesn't.
 */
internal fun captureOrFail(controller: CameraCaptureController, onCaptured: (ByteArray) -> Unit, onFailed: () -> Unit) {
    controller.capture { bytes -> if (bytes != null) onCaptured(bytes) else onFailed() }
}

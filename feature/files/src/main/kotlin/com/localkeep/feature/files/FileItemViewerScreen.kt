package com.localkeep.feature.files

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.localkeep.core.model.Attachment
import com.localkeep.core.ui.RevealAuth
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSkeleton
import com.localkeep.core.ui.theme.LkTheme

/** The only branch allowed to mount a decrypting renderer. */
internal fun viewerContentMayCompose(
    state: FileItemViewerUiState,
    dekAvailable: Boolean,
): Boolean = state.phase == FileViewerPhase.Ready &&
    dekAvailable &&
    state.entries.isNotEmpty() &&
    !state.exitRequested

/** Read-only standalone file route. Settings is deliberately a separate navigation destination. */
@Composable
fun FileItemViewerScreen(
    viewModel: FileItemViewerViewModel,
    itemId: String,
    onBack: () -> Unit,
    onSettings: (String) -> Unit,
    onViewerError: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val revealTitle = stringResource(R.string.files_edit_viewer_reveal_title)
    val revealSubtitle = stringResource(R.string.files_edit_viewer_reveal_subtitle)

    BackHandler(enabled = true) { viewModel.close() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.revalidate()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.pendingReveal) {
        if (!state.pendingReveal) return@LaunchedEffect
        val token = viewModel.beginReveal()
        if (token == null) {
            viewModel.onVaultLocked()
            return@LaunchedEffect
        }
        val activity = context as? FragmentActivity
        val outcome = when {
            activity == null -> RevealAuth.RevealOutcome.Error(-1)
            !RevealAuth.isAvailable(context) -> RevealAuth.RevealOutcome.Unavailable
            else -> RevealAuth.authenticate(
                activity = activity,
                title = revealTitle,
                subtitle = revealSubtitle,
            )
        }
        viewModel.completeReveal(token, outcome)
    }

    LaunchedEffect(state.exitRequested, state.outcomeMessage) {
        if (!state.exitRequested) return@LaunchedEffect
        state.outcomeMessage?.let(onViewerError)
        onBack()
    }

    LaunchedEffect(state.actionError) {
        state.actionError?.let {
            onViewerError(it)
            viewModel.clearActionError()
        }
    }

    when (state.phase) {
        FileViewerPhase.Ready -> {
            val key = viewModel.viewerSessionKeyOrNull()
            if (!viewerContentMayCompose(state, key != null)) {
                LkSkeleton(Modifier.fillMaxSize())
            } else {
                val readyKey = checkNotNull(key)
                val attachments = state.entries.map { entry ->
                    Attachment(
                        id = entry.item.id,
                        filename = entry.file.filename,
                        mime = entry.file.mime,
                        sizeBytes = entry.file.sizeBytes,
                        blobRef = entry.file.blobRef,
                        thumbnailRef = entry.file.thumbnailRef,
                    )
                }
                val separator = stringResource(R.string.files_viewer_folder_separator)
                val pageDetails = state.entries.associate { entry ->
                    entry.item.id.toString() to entry.folderPath.takeIf { it.isNotEmpty() }?.joinToString(separator)
                }
                val pageTitles = state.entries.associate { entry ->
                    entry.item.id.toString() to entry.item.title
                }
                // Only this mount has them: `notes` is a field on the vault's File item, which the
                // attachment mount has no equivalent of.
                val pageNotes = state.entries.associate { entry ->
                    entry.item.id.toString() to entry.file.notes.takeIf { it.isNotBlank() }
                }
                SecureMediaViewer(
                    attachments = attachments,
                    startId = state.selectedPageId ?: itemId,
                    preferredPageId = state.selectedPageId,
                    onPageIdChanged = viewModel::updateSelectedPage,
                    pageDetailsById = pageDetails,
                    pageTitlesById = pageTitles,
                    pageNotesById = pageNotes,
                    sessionKey = readyKey,
                    fileBlobStore = viewModel.viewerBlobStore(),
                    onClose = viewModel::close,
                    onSettings = { attachment -> onSettings(attachment.id.toString()) },
                    onDelete = { attachment -> viewModel.deletePage(attachment.id.toString()) },
                    // `deletePage` is `moveToTrash` — recoverable — so this is the one mount site
                    // that gets the trash word. The picker's delete is permanent and keeps "Delete".
                    deleteLabel = R.string.files_viewer_trash,
                )
            }
        }

        FileViewerPhase.Loading,
        FileViewerPhase.Authenticating,
        -> LkSkeleton(Modifier.fillMaxSize())

        FileViewerPhase.Denied,
        FileViewerPhase.Unavailable,
        -> ViewerGate(
            unavailable = state.phase == FileViewerPhase.Unavailable,
            // The ViewModel already worked out which of cancelled / denied / hardware-error
            // happened and put it in `outcomeMessage`; the gate used to throw that away and pick
            // its body from the phase alone. Cancelling the prompt then read "Authentication was
            // not accepted" — telling the user their credential was rejected when it was never
            // checked. Confirmed on device, 2026-08-25.
            message = viewerGateMessage(state),
            onRetry = viewModel::retry,
            onBack = viewModel::close,
        )

        FileViewerPhase.Locked,
        FileViewerPhase.Error,
        -> Unit
    }
}

/**
 * What the gate says happened.
 *
 * The ViewModel already classifies the attempt — cancelled, denied, hardware error, no screen lock
 * — and records the matching string in [FileItemViewerUiState.outcomeMessage]. The gate used to
 * throw that away and choose from the phase alone, so pressing Cancel on the prompt produced
 * "Authentication was not accepted": the app telling the user their credential was rejected when it
 * had never been checked. Confirmed on a device 2026-08-25.
 *
 * The phase is kept only as the fallback for a gate reached without a recorded outcome.
 */
@StringRes
internal fun viewerGateMessage(state: FileItemViewerUiState): Int =
    state.outcomeMessage ?: if (state.phase == FileViewerPhase.Unavailable) {
        R.string.files_viewer_auth_unavailable
    } else {
        R.string.files_viewer_auth_denied
    }

@Composable
private fun ViewerGate(
    unavailable: Boolean,
    @StringRes message: Int,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val title = stringResource(R.string.files_viewer_auth_title)
    val body = stringResource(message)
    LkEmptyState(
        icon = if (unavailable) LkIcons.Warning else LkIcons.Lock,
        title = title,
        body = body,
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        action = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LkPrimaryButton(
                    text = stringResource(R.string.files_viewer_retry),
                    onClick = onRetry,
                    modifier = Modifier.lkTag("file-viewer-retry"),
                )
                LkSecondaryButton(
                    text = stringResource(R.string.files_close),
                    onClick = onBack,
                    modifier = Modifier.lkTag("file-viewer-back"),
                )
            }
        },
    )
}

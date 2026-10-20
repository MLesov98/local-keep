package com.localkeep.feature.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Card
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.PermissionDenial
import com.localkeep.core.ui.PermissionDenialSaver
import com.localkeep.core.ui.RevealAuth
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.openAppSettings
import com.localkeep.core.ui.rememberExternalPickerLauncher
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkGroupRow
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkShimmerBar
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkArrival
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkScaledMaxLines
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.SessionKey
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AttachmentPickerUiState(
    val itemId: UUID = UUID.randomUUID(),
    /**
     * How many stored files have to go before another will fit, for the quota refusal only.
     *
     * The refusal said "Remove one" whatever the vault held. That is true at exactly the cap and
     * false above it, and a vault can sit above it — import is never charged against the cap. See
     * [AttachmentQuota.mustRemove]. Unread by every other error, which simply ignores the argument.
     */
    val quotaMustRemove: Int = 1,
    /**
     * Whether this build can sell the unlock, for the quota refusal's last clause only. False is the
     * safe default: a screen that cannot tell must not name a purchase it may not be able to offer.
     */
    val canPurchase: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    /**
     * The owning item's reprompt flag. A reprompt item re-asks before *any* of its secrets are
     * released, and an attachment's blob is the item's content — without this the attachments
     * viewer was the one unguarded route to a reprompt item's data.
     */
    val reprompt: Boolean = false,
    /**
     * Whether this item's attachments are high-value *by type*, independent of any per-item flag.
     *
     * True for every item type carrying a field in `ALWAYS_REAUTH_FIELDS`. A photographed passport
     * is at least as high-value as the number typed off it, and that number is already gated:
     * `ALWAYS_REAUTH_FIELDS` re-asks before releasing it whatever the item's own settings say. The
     * attachment gate was item-scoped only — `reprompt`, which defaults to false — so on an
     * ordinary Identity the photograph of the document opened with no challenge at all while the
     * digits beside it did not. That is the editor being a way around the reveal prompt, which is
     * the mistake the coordinate preview and the TOTP preview each had to fix once already.
     *
     * It read `content is Identity` until 2026-08-27, which made the argument above true only for
     * the one type it was written against. It transfers verbatim to the others: a Card carries
     * `cardCvv`/`cardPin`, a Coordinate carries `COORDINATE_FIELD`, a SeedPhrase carries
     * `PHRASE_FIELD` and `PHRASE_PASSPHRASE_FIELD`, and all six are in that set. So on a
     * non-reprompt Card the CVV re-asked while a photograph of the card front — showing that same
     * CVV — opened with nothing, and a photographed seed phrase behaved the same way. The rule is
     * that an attachment costs what its owning item's strictest field costs, which is what the rest
     * of the app already does; this is not a new gate, it is the existing one applied evenly.
     *
     * The type test alone was not the whole rule, which is why the name is now only mostly true: a
     * Login carrying an embedded second factor holds `totpSecret` as surely as a standalone Totp
     * does, and left the same hole for exactly that item. See `isHighValueByType`.
     */
    val highValue: Boolean = false,
    val folderPath: String? = null,
    /** Capture UI is up. Mirrors FileItemEditUiState.showCapture. */
    val showCapture: Boolean = false,
    /**
     * In-flight reveal challenge for a reprompt item's attachment viewer, held in the ViewModel
     * (not rememberSaveable/SavedStateHandle) so it survives a configuration change but not
     * process death — a cold return must not auto-raise a biometric prompt. Mirrors
     * FileItemEditViewModel.pendingViewerReveal; [pendingViewId] additionally carries which
     * attachment the challenge is for, resolved against the current list only once it passes.
     */
    val pendingViewerReveal: Boolean = false,
    val pendingViewId: String? = null,
    /**
     * Which of cancelled / not-accepted / no-screen-lock ended the last attachment challenge.
     *
     * Separate from [error] rather than folded into it, because [error] is rendered with two format
     * arguments for the quota refusal and these strings take none. A refusal used to say nothing at
     * all: the row went back to looking tappable, which reads as a broken app rather than a
     * declined challenge — the same gap `FileItemEditViewModel.onViewerRevealDenied` closed on the
     * sibling screen, using the same four strings over the same bytes.
     */
    @StringRes val viewerAuthMessage: Int? = null,
    @StringRes val error: Int? = null,
    /**
     * The name of the file currently being encrypted, when it is known.
     *
     * Null through the whole picker path, and that is a limitation rather than an oversight: the
     * display name comes from a `ContentResolver` query that runs on the IO dispatcher interleaved
     * with the encrypt itself, so there is no point before the work starts at which this app knows
     * what the user picked. The camera path does know, immediately, and passes it. A pending row
     * without a name still says the true and useful thing — that something is being encrypted, and
     * where it will land.
     */
    val workingName: String? = null,
    // True while a picked file is being read and encrypted.
    //
    // The picker accepts any MIME type, so this is a chunked-GCM encrypt of anything up to a
    // half-gigabyte video. It runs on Dispatchers.IO — the UI stays responsive — which is precisely
    // the problem: nothing moved, nothing said anything, and the attachment appeared minutes later
    // or not at all. (Line comments, not KDoc: the wildcard this describes contains the characters
    // that close a block comment.)
    val isWorking: Boolean = false,
    /**
     * True while the work [isWorking] describes is a picked file's read from its provider, which
     * the pending row lets the user abandon. Not the in-app capture, which is local CPU and ends on
     * its own. See [AttachmentPickerViewModel.cancelAttach].
     */
    val cancellable: Boolean = false,
    /**
     * True while a confirmed removal is committing, which disables every row's trash button.
     *
     * Separate from [isWorking] on purpose, and not merged into it: [isWorking] is the *encryption*
     * flag, and raising it renders `PendingAttachmentRow` — "Encrypting this file. Large videos can
     * take a while." — which is the wrong sentence and the wrong act for a deletion. This one shows
     * no label at all; it exists to stop a second removal starting while the first rewrites the
     * vault file, not to narrate one.
     */
    val isRemoving: Boolean = false,
    /**
     * Whether a free vault has already reached [AttachmentQuota.FREE_LIMIT], kept current by
     * [load] and by every mutation that changes the vault-wide count (attach, capture, remove —
     * see `persist`). Read by the three add buttons before they launch anything: see 3-2, where
     * they used to launch the picker or the camera unconditionally and let `attach`/
     * `attachCapture` refuse only after the user had already chosen a file or taken a photograph.
     */
    val quotaFull: Boolean = false,
)

private const val CAPTURED_PHOTO_PREFIX = "photo-"

/**
 * The string ids that make up one rendered refusal, in order.
 *
 * The quota refusal is a count sentence plus a clause about getting past it, and the clause is only
 * true where a purchase exists. `foss` has none ([com.localkeep.core.billing.Entitlement.canPurchase]
 * is permanently false there), so naming one would send the user looking for a button that does not
 * exist; it says where Pro can be bought instead, as the vault list's dialog does. Every other
 * error is a single self-contained sentence.
 */
internal fun refusalStringIds(@StringRes error: Int, canPurchase: Boolean): List<Int> =
    if (error == R.string.files_error_quota_exceeded) {
        listOf(
            error,
            if (canPurchase) {
                R.string.files_error_quota_exceeded_suffix_purchase
            } else {
                R.string.files_error_quota_exceeded_suffix_no_purchase
            },
        )
    } else {
        listOf(error)
    }

/** Renders [refusalStringIds]; the two format arguments are read only by the quota sentence. */
@Composable
internal fun refusalText(@StringRes error: Int, mustRemove: Int, canPurchase: Boolean): String {
    // A loop rather than joinToString: stringResource is composable and joinToString's lambda is not
    // inline.
    val out = StringBuilder()
    for (id in refusalStringIds(error, canPurchase)) {
        out.append(stringResource(id, AttachmentQuota.FREE_LIMIT, mustRemove))
    }
    return out.toString()
}

/**
 * The owning item is no longer in the visible projection, so nothing was written.
 *
 * Carries no message: it is caught by each caller's generic arm and turned into that caller's own
 * resource id, and this class's rule is that an exception's text never reaches the screen.
 */
private class ItemNotVisibleException : Exception()

@HiltViewModel
class AttachmentPickerViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val lockManager: VaultLockManager,
    val fileBlobStore: FileBlobStore, // exposed for the inline viewer, mirrors FileItemEditViewModel (Task 9)
    private val photoPickerImport: PhotoPickerImport, // image imports strip metadata — see attach()
    private val entitlement: Entitlement, // 14b/§G — attachments are a Pro (Files & Media) feature
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AttachmentPickerUiState(canPurchase = entitlement.canPurchase))
    val state: StateFlow<AttachmentPickerUiState> = _state.asStateFlow()

    /**
     * A borrow of the session key for the viewer, not the key itself: the viewer stays mounted
     * across an auto-lock, and `lock()` zeroes the manager's own array in place. See [SessionKey].
     */
    fun sessionKeyOrNull(): SessionKey? = lockManager.sessionKeyOrNull()

    /**
     * The system picker is a task the user is in the middle of, not a departure. Without this the
     * background grace timer — thirty seconds on Aggressive — runs while they browse for a file,
     * and the result callback returns to a locked vault, which drops the operation silently.
     * See [com.localkeep.core.ui.rememberExternalPickerLauncher], which pairs these for the caller.
     */
    fun onPickerOpened() = lockManager.beginExternalPicker()

    fun onPickerClosed() = lockManager.endExternalPicker()

    /** A reprompt item's attachment tap parks a fresh biometric/device-credential challenge here. */
    fun requestViewReveal(attachmentId: String) {
        _state.value = _state.value.copy(
            pendingViewerReveal = true,
            pendingViewId = attachmentId,
            // The previous refusal is answered by trying again; leaving it up beside a prompt that
            // is now on screen would report the wrong attempt.
            viewerAuthMessage = null,
        )
    }

    fun onViewerRevealAuthenticated() {
        _state.value = _state.value.copy(pendingViewerReveal = false, viewerAuthMessage = null)
    }

    /**
     * [message] says which of cancelled / not-accepted / no-screen-lock happened, or is null where
     * the screen already has its own sentence for the cause — the device with no credential
     * enrolled at all, which `gateUnavailable` explains in terms of what to do about it.
     *
     * Nothing is released by saying so: the challenge stays refused, `pendingViewId` is still
     * cleared, and the strings name no vault content. What it stops is a confirmed tap that
     * produces neither a viewer nor a word.
     */
    fun onViewerRevealDenied(@StringRes message: Int? = null) {
        _state.value = _state.value.copy(
            pendingViewerReveal = false,
            pendingViewId = null,
            viewerAuthMessage = message,
        )
    }

    fun clearPendingViewId() {
        _state.value = _state.value.copy(pendingViewId = null)
    }

    fun load(itemId: String) {
        val id = UUID.fromString(itemId)
        // Through the projection: a concealed item's attachment list must not be readable from a
        // picker route restored onto the back stack.
        val snapshot = repository.visibilitySnapshot()
        val item = snapshot.liveItems.find { it.id == id } ?: return
        val path = item.folderId?.let { fid ->
            FolderTree.pathOf(snapshot.liveFolders, fid)?.joinToString(" / ") { it.name }
        }
        _state.value = _state.value.copy(
            itemId = id,
            attachments = item.attachments,
            reprompt = item.reprompt,
            highValue = isHighValueByType(item.content),
            folderPath = path,
            quotaFull = AttachmentQuota.exceedsFreeLimit(repository, entitlement.isPro.value),
        )
    }

    fun showCapture() { _state.value = _state.value.copy(showCapture = true) }

    internal companion object {
        /**
         * Whether this item owns a field that always re-asks, regardless of `reprompt`.
         *
         * The list mirrors `ItemEditViewModel.ALWAYS_REAUTH_FIELDS`: Totp holds `totpSecret`, Card
         * holds `cardCvv`/`cardPin`, Identity holds `ssn`/`passport`/`license`, Coordinate holds
         * `COORDINATE_FIELD`, SeedPhrase holds `PHRASE_FIELD` and `PHRASE_PASSPHRASE_FIELD`.
         *
         * **This comment used to justify the copy by saying that set is `internal` to
         * `:feature:itemedit`. That has not been true since it moved to
         * `core/model/ReauthFields.kt`, where it is public and says "Never copy this set"**
         * (V28, 2026-09-12). The copy survives because the two express different things — that set
         * names *fields*, this asks whether a content *type* owns one — but they are still two
         * halves of one rule, so `AttachmentReauthParityTest` now fails the build if a field is
         * added there without a type covering it here. Erring toward including a case is the safe
         * direction: it costs a prompt, where omitting one releases a photograph of the very secret
         * the field beside it protects.
         *
         * Mostly by type, and deliberately not entirely: a Login carrying an embedded second factor
         * holds `totpSecret` exactly as a standalone Totp does, and `ItemDetailScreen` gates it
         * unconditionally on both. Testing only the type left that one item's attachments opening
         * with no challenge while its own seed always demanded one — the same unevenness the
         * widening from `content is Identity` closed for Card, Coordinate and SeedPhrase on
         * 2026-08-27. Keyed on the *stored* `totp`, which is what `load` reads: dropping a seed onto
         * a Login and taking it off again clears the field, and the gate should follow the vault
         * rather than a form.
         */
        fun isHighValueByType(content: ItemContent): Boolean =
            content is Identity ||
                content is Card ||
                content is Coordinate ||
                content is SeedPhrase ||
                content is Totp ||
                (content is Login && content.totp != null)

    }

    fun dismissCapture() { _state.value = _state.value.copy(showCapture = false) }

    fun attach(uri: Uri) {
        // Defense in depth (14b, §G): attachments are quota-limited on the free tier. The UI entry
        // point checks too, but re-check here so no alternate/replayed call can persist past the
        // cap. A count check, not a tier check — see [AttachmentQuota] for why the free tier holds
        // some rather than none, and for the invariant that this is an *add*-path question only.
        // The number itself lives in AttachmentQuota.FREE_LIMIT and is deliberately not repeated
        // here: it was five until 2026-09-03 and is seven now, and this comment said five for
        // four days after the constant moved.
        val used = AttachmentQuota.used(repository)
        if (AttachmentQuota.exceedsFreeLimit(used, entitlement.isPro.value)) {
            _state.value = _state.value.copy(
                error = R.string.files_error_quota_exceeded,
                quotaMustRemove = AttachmentQuota.mustRemove(used),
            )
            return
        }
        if (_state.value.isWorking) return
        val generation = ++attachGeneration
        _state.value = _state.value.copy(isWorking = true, error = null, cancellable = true)
        attachJob = viewModelScope.launch {
            try {
                // A private copy of the DEK, not the manager's live array: the provider IPC and
                // file read ahead of the first HKDF derive can outlive the auto-lock grace timer,
                // and lock() zeroes the manager's own array underneath it — sealing the blob under
                // a key derived from an all-zero ikm. See [VaultLockManager.sessionSnapshotOrNull].
                val session = lockManager.sessionSnapshotOrNull()
                if (session == null) {
                    _state.value = _state.value.copy(
                        error = R.string.files_attachments_error_attach_failed,
                    )
                    return@launch
                }
                try {
                    // Off the main thread. viewModelScope is Dispatchers.Main.immediate, so without
                    // this the whole ContentResolver read and chunked-GCM encrypt ran on the UI thread
                    // — and the picker accepts */*, so a 500 MB video from Drive froze the app into an
                    // ANR. Every sibling path in this module already dispatches; this one was missed.
                    //
                    // The boundary starts here and not at `openInputStream`, which is where it used to.
                    // `getType` and the display-name query are IPC to whichever app provided the file,
                    // and for a cloud provider that call may go on to hit the network before it answers
                    // — three main-thread round-trips to a remote process, ahead of the read they were
                    // meant to prepare for.
                    val attachment = withContext(Dispatchers.IO) {
                        when (val result = photoPickerImport.importAttachment(uri, session.dek)) {
                            is AttachmentImportResult.Success -> {
                                // The user cancelled while the provider was still answering and
                                // the read has now finished anyway: nothing will reference this
                                // blob, so remove it here rather than leave it for the sweep.
                                if (!isActive) fileBlobStore.deleteBlob(result.attachment.blobRef)
                                result.attachment
                            }
                            is AttachmentImportResult.Failure -> when (result.reason) {
                                AttachmentImportFailureReason.UNSTRIPPABLE_IMAGE -> throw UnstrippableImageException()
                                AttachmentImportFailureReason.IMPORT_FAILED -> error("attachment import failed")
                            }
                        }
                    }
                    persist { it.copy(attachments = it.attachments + attachment) }
                } finally {
                    Zeroizer.wipe(session.dek)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnstrippableImageException) {
                // Its own message, because the user can act on this one: the import was refused,
                // nothing was stored, and re-shooting in JPEG or turning off the phone's HEIF
                // setting is a real way forward. Folding it into "couldn't attach" would read as a
                // transient failure worth retrying, and the retry would fail identically.
                _state.value = _state.value.copy(
                    error = R.string.files_attachments_error_unstrippable_image,
                )
            } catch (e: Exception) {
                // Deliberately not `e.message`. That text is written for a log, and here it was
                // rendered to the user: the ContentResolver failures alone put a full provider URI
                // on screen, which is a filesystem path the user did not ask to see and we have no
                // reason to show. `attachFailed` reads that message without displaying it, to tell
                // a full disk — which fails the same way on every retry — from the transient
                // failures the generic sentence is written for.
                _state.value = _state.value.copy(error = MediaErrors.attachFailed(e))
            } finally {
                // Only the attach that is still the current one may clear the flags. A cancelled
                // attach is abandoned rather than awaited -- its read may not return for as long as
                // the provider stalls -- and by the time it does, a new attach can be running.
                if (generation == attachGeneration) {
                    _state.value = _state.value.copy(isWorking = false, workingName = null, cancellable = false)
                }
            }
        }
    }

    private var attachGeneration = 0
    private var attachJob: Job? = null

    /**
     * Stops waiting for a picked file (S3-13, 2026-10-02).
     *
     * Back stays blocked while a file is being read: the comment on the screen's `BackHandler`
     * reasons that the encrypt "is about to finish on its own", and for a local file it is. For a
     * not-yet-downloaded file from a cloud provider on a bad connection, or a huge one from a slow
     * provider, it is not -- `openInputStream` and the chunked read have no timeout -- and the only
     * exit was to background the app and wait for auto-lock. This is the explicit exit: the job is
     * cancelled and the screen is released immediately. The read itself cannot be interrupted, so
     * it runs to the end on its worker and, if it completes, its blob is deleted unreferenced.
     */
    fun cancelAttach() {
        if (!_state.value.cancellable) return
        attachGeneration++
        attachJob?.cancel()
        attachJob = null
        _state.value = _state.value.copy(isWorking = false, workingName = null, cancellable = false)
    }

    /**
     * Stores an in-app camera capture as an attachment.
     *
     * Separate from [attach] because there is no `Uri` and no content provider in the way: CameraX
     * hands over JPEG bytes that never touched the gallery and never touched disk. They still go
     * through [ExifStripper] — capture is the route the copy presents as the *more* private one,
     * which makes it the worst place to leave TAG_MAKE/TAG_MODEL/TAG_DATETIME_ORIGINAL in, and
     * `FileItemEditViewModel.onCaptured` had to learn that once already.
     */
    fun attachCapture(jpegBytes: ByteArray) {
        val used = AttachmentQuota.used(repository)
        if (AttachmentQuota.exceedsFreeLimit(used, entitlement.isPro.value)) {
            _state.value = _state.value.copy(
                error = R.string.files_error_quota_exceeded,
                quotaMustRemove = AttachmentQuota.mustRemove(used),
                showCapture = false,
            )
            return
        }
        if (_state.value.isWorking) return
        // Hoisted out of the coroutine so the pending row can name the photograph from the first
        // frame. The value is a timestamped constant, not a query.
        val capturedName = capturedPhotoFilename()
        _state.value = _state.value.copy(
            isWorking = true,
            error = null,
            showCapture = false,
            workingName = capturedName,
        )
        viewModelScope.launch {
            try {
                val session = lockManager.sessionSnapshotOrNull()
                if (session == null) {
                    _state.value = _state.value.copy(
                        error = R.string.files_attachments_error_attach_failed,
                    )
                    return@launch
                }
                try {
                    val attachment = withContext(Dispatchers.IO) {
                        val stripped = when (val sanitized = ExifStripper.sanitize(jpegBytes, "image/jpeg")) {
                            is SanitizerResult.Success -> sanitized.bytes
                            is SanitizerResult.Failed,
                            is SanitizerResult.Unsupported,
                            -> throw UnstrippableImageException()
                        }
                        val blobRef = fileBlobStore.encryptToBlob(ByteArrayInputStream(stripped), session.dek)
                        Attachment(
                            id = UUID.randomUUID(),
                            // No timestamp in the name. The whole point of the strip above is that
                            // when the photograph was taken is not stored, and a filename is the
                            // one part of an attachment this screen shows in clear.
                            filename = capturedName,
                            mime = "image/jpeg",
                            sizeBytes = stripped.size.toLong(),
                            blobRef = blobRef,
                        )
                    }
                    persist { it.copy(attachments = it.attachments + attachment) }
                } finally {
                    Zeroizer.wipe(session.dek)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnstrippableImageException) {
                // The same arm `attach` carries, for the same throw from the same sanitizer. Without
                // it this path fell through to the generic catch below and reported a refusal the
                // user can act on as "couldn't attach" — the transient-sounding message `attach`
                // documents as the wrong answer for this cause, on the capture flow where the fix
                // (re-shoot in JPEG) is most immediately available.
                _state.value = _state.value.copy(
                    error = R.string.files_attachments_error_unstrippable_image,
                )
            } catch (e: Exception) {
                // Same split as `attach`: the capture writes its blob through the same
                // FileOutputStream, so it meets ENOSPC the same way.
                _state.value = _state.value.copy(error = MediaErrors.attachFailed(e))
            } finally {
                _state.value = _state.value.copy(isWorking = false, workingName = null)
            }
        }
    }

    /** `photo-1.jpg`, `photo-2.jpg`, … counted against what this item already holds. */
    private fun capturedPhotoFilename(): String {
        val taken = _state.value.attachments.count { it.filename.startsWith(CAPTURED_PHOTO_PREFIX) }
        return "$CAPTURED_PHOTO_PREFIX${taken + 1}.jpg"
    }

    /**
     * Drops the reference first and leaves the bytes to the sweep.
     *
     * Deleting the blob before the vault write was destructive-first: [persist] refuses outright
     * when the item is no longer there — trashed from another surface, or the scope cancelled by an
     * auto-lock mid-confirm — and by then the file was already gone, leaving an attachment row
     * pointing at nothing and no way to clear it. `BlobGarbageCollector` exists for exactly this
     * ordering: deleting only what nothing points at is idempotent, and the next unlock reclaims
     * the bytes for free.
     */
    fun remove(attachment: Attachment) {
        // Nothing gated these taps on anything, so a second confirmed removal could start while the
        // first was still committing: two `updateWith` calls, each mutating the stored item and
        // rewriting the whole vault file, for one user intent. `TrashViewModel` documents that
        // double commit as worth guarding, and this is the same shape.
        //
        // Its own flag on purpose. `isWorking` is *encryption*: raising it draws PendingAttachmentRow
        // — a ring, a shimmer and the sentence "Encrypting this file" — over a row that is being
        // deleted, which names the wrong act entirely.
        if (_state.value.isRemoving) return
        _state.value = _state.value.copy(isRemoving = true)
        viewModelScope.launch {
            // Uncaught, this was the worst kind of failure: `persist` writes the new list into the
            // UI state, so a throwing `updateItem` left the attachment gone from the screen and
            // still in the vault. The user sees a successful delete, locks, comes back, and the
            // file is there again — with nothing having reported an error.
            try {
                persist { it.copy(attachments = it.attachments.filter { a -> a.id != attachment.id }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = R.string.files_attachments_error_remove_failed,
                )
            } finally {
                _state.value = _state.value.copy(isRemoving = false)
            }
        }
    }

    private suspend fun persist(mutate: (VaultItem) -> VaultItem) {
        val id = _state.value.itemId
        // Throws rather than returning, which is the whole change here. The refusal itself stays
        // exactly as narrow as it was — an item that has left the visible projection must not be
        // written through from this screen — but a bare `return` made every caller's error handling
        // unreachable: `remove`'s own catch could not see it, so a confirmed removal on an item
        // whose reveal grant had expired closed the dialog and did nothing at all, and the two
        // attach paths reported a stored attachment that was never stored. Each caller's existing
        // `catch (e: Exception)` arm now says the true thing for its own action.
        if (repository.visibilitySnapshot().liveItems.none { it.id == id }) throw ItemNotVisibleException()
        // `mutate` runs against the *stored* item inside the vault lock. Applying it to a copy read
        // beforehand meant an attachment added by one screen and removed by another raced: whichever
        // wrote second wrote its own whole list and dropped the other's change silently.
        // Written to the vault *before* the UI is told, so the screen can never show a change that
        // did not land.
        val updated = repository.updateWith(id, mutate)
        _state.value = _state.value.copy(
            attachments = updated.attachments,
            error = null,
            quotaFull = AttachmentQuota.exceedsFreeLimit(repository, entitlement.isPro.value),
        )
    }

    /**
     * Raises the same refusal `attach`/`attachCapture` raise internally, but before any launcher
     * or camera-permission request runs — see 3-2. Returns true when the tap was refused, so the
     * caller can skip launching anything.
     */
    fun refuseIfQuotaFull(): Boolean {
        if (!_state.value.quotaFull) return false
        val used = AttachmentQuota.used(repository)
        _state.value = _state.value.copy(
            error = R.string.files_error_quota_exceeded,
            quotaMustRemove = AttachmentQuota.mustRemove(used),
        )
        return true
    }
}

/**
 * Whether the header prints "N encrypted files" under the title.
 *
 * Not at zero: the empty state directly below already says "Nothing attached", so "0 encrypted
 * files" was the same fact twice, and the header is the only line that can be dropped without
 * losing the invitation to add one.
 */
internal fun attachmentCountShown(count: Int): Boolean = count > 0

@Composable
fun AttachmentPickerScreen(viewModel: AttachmentPickerViewModel, itemId: String, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Saveable by id, for the same reason — and in the same shape — as `confirmRemoveId` below.
    // Held as plain `remember`, a rotation while viewing an attachment silently closed the viewer
    // and dropped the user back on the list; the sibling `FileItemEditScreen` does not have this
    // bug only because its equivalent state lives in a ViewModel. `Attachment` carries metadata
    // only (no secret bytes), and resolving by id against the current list means a viewer for an
    // attachment that has since gone simply does not come back.
    var viewingId by rememberSaveable { mutableStateOf<String?>(null) }
    val viewing = viewingId?.let { id -> state.attachments.firstOrNull { it.id.toString() == id } }

    LaunchedEffect(itemId) { viewModel.load(itemId) }

    // A reprompt item re-asks before any of its secrets are released; an attachment is the item's
    // content, so opening one on a reprompt item parks a biometric challenge here — mirroring
    // FileItemEditScreen's viewer gate and the item editor's field reveals. Held in the ViewModel
    // (state.pendingViewerReveal / state.pendingViewId), not rememberSaveable, so the in-flight
    // challenge survives a configuration change but is dropped on process death rather than
    // auto-raising a biometric prompt on a cold return.
    val revealTitle = stringResource(R.string.files_edit_viewer_reveal_title)
    val revealSubtitle = stringResource(R.string.files_edit_viewer_reveal_subtitle)
    // A device with no screen lock and no enrolled biometric cannot be challenged at all, so the
    // gate below refuses — correctly, because opening a photographed passport with no challenge on
    // the least protected device there is would be the wrong way to fail. But refusing in silence
    // is its own defect: the row is tappable, the tap does nothing, and nothing says why. Measured
    // on `emulator-5554` 2026-08-19, where `locksettings get-disabled` is true.
    var gateUnavailable by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.pendingViewerReveal) {
        if (!state.pendingViewerReveal) return@LaunchedEffect
        val activity = context as? FragmentActivity
        val canChallenge = activity != null && RevealAuth.isAvailable(context)
        gateUnavailable = !canChallenge
        val outcome = if (activity != null && canChallenge) {
            RevealAuth.authenticate(activity, revealTitle, revealSubtitle)
        } else {
            RevealAuth.RevealOutcome.Unavailable
        }
        if (outcome is RevealAuth.RevealOutcome.Success) {
            viewModel.onViewerRevealAuthenticated()
            viewingId = state.pendingViewId
            viewModel.clearPendingViewId()
        } else {
            // Mapped to the same four sentences the file editor and the standalone viewer route
            // use for the same outcomes over the same bytes — cancelling a prompt is not being
            // rejected by one. Suppressed when the device could not be challenged at all, because
            // `gateUnavailable` below already says that, and better: two messages for one refusal
            // is worse than the silence this replaces.
            viewModel.onViewerRevealDenied(if (canChallenge) viewerRevealMessage(outcome) else null)
        }
    }

    // Paired with the lock manager. Attaching means browsing the document picker, which
    // backgrounds the app: on the shorter presets the vault relocked while the user was still
    // looking, and `attach` came back to a locked vault and dropped the file.
    val pickLauncher = rememberExternalPickerLauncher(
        contract = ActivityResultContracts.OpenDocument(),
        onPickerOpened = viewModel::onPickerOpened,
        onPickerClosed = viewModel::onPickerClosed,
    ) { uri ->
        if (uri != null) viewModel.attach(uri)
    }

    // The photo pair, offered only where a photograph is what the item is *for* — see
    // AttachmentPickerUiState.highValue. Two entry points rather than one, because "photograph the
    // document" and "the photo I already took" are different situations and neither is a fallback
    // for the other.
    //
    // The system photo picker, not OpenDocument: it needs no storage permission at all, so this
    // route adds nothing to the merged manifest. If a future change reaches for an API that does,
    // `checkFlavorPermissions` fails the build, and that failure is correct.
    val photoLauncher = rememberExternalPickerLauncher(
        contract = ActivityResultContracts.PickVisualMedia(),
        onPickerOpened = viewModel::onPickerOpened,
        onPickerClosed = viewModel::onPickerClosed,
    ) { uri ->
        if (uri != null) viewModel.attach(uri)
    }
    // Saveable, both — a rotation mid-dialog used to drop the disclosure back to a button that had
    // apparently done nothing. Same shape and same reason as FileItemEditScreen's pair.
    var showCameraDisclosure by rememberSaveable { mutableStateOf(false) }
    var showSourceChooser by rememberSaveable { mutableStateOf(false) }
    var cameraDenial by rememberSaveable(stateSaver = PermissionDenialSaver) {
        mutableStateOf<PermissionDenial?>(null)
    }
    val cameraDeniedSentence = stringResource(R.string.files_edit_camera_denied_sentence)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraDenial = null
            viewModel.showCapture()
        } else {
            cameraDenial = PermissionDenial.after(
                context = context,
                permission = Manifest.permission.CAMERA,
                featureSentence = cameraDeniedSentence,
            )
        }
    }

    if (state.showCapture) {
        BackHandler(enabled = true) { viewModel.dismissCapture() }
        CaptureToVaultScreen(
            onCaptured = viewModel::attachCapture,
            onCancel = viewModel::dismissCapture,
        )
        return
    }

    viewing?.let { att ->
        val sessionKey = viewModel.sessionKeyOrNull()
        if (sessionKey != null) {
            // The viewer replaces this whole screen rather than layering over it, so without its own
            // handler the system Back gesture goes past it to the nav host and pops the attachments
            // destination entirely — closing the viewer and the list in one gesture.
            // `FileItemEditScreen` hit exactly this and guards its identical viewer the same way.
            BackHandler(enabled = true) { viewingId = null }
            SecureMediaViewer(
                attachments = state.attachments,
                startId = att.id.toString(),
                sessionKey = sessionKey,
                fileBlobStore = viewModel.fileBlobStore,
                folderPath = state.folderPath,
                onClose = { viewingId = null },
                onDelete = { toDelete ->
                    viewModel.remove(toDelete)
                },
            )
            return
        }
    }

    val c = LkTheme.colors
    // Saveable, and by id rather than by the attachment: this is the app's only destructive
    // confirmation held in composable state, so a rotation or a process death used to drop the
    // dialog and leave the user looking at the list wondering whether the file went. The id also
    // means the dialog is resolved against the *current* list — if the attachment has since gone,
    // the dialog simply does not come back, which is the rule `TrashScreen` already follows.
    var confirmRemoveId by rememberSaveable { mutableStateOf<String?>(null) }

    LkScreen(accent = c.jade, auraAlpha = 0.07f, auraBias = 0.5f) {
        AttachmentPickerContent(
            state = state,
            cameraDenial = cameraDenial,
            gateUnavailable = gateUnavailable,
            onBack = onBack,
            onAttach = {
                if (!state.isWorking && !viewModel.refuseIfQuotaFull()) showSourceChooser = true
            },
            onCancelAttach = if (state.cancellable) viewModel::cancelAttach else null,
            onOpenSettings = { context.openAppSettings() },
            onOpen = { att ->
                // `highValue` as well as `reprompt` — see AttachmentPickerUiState.
                if (state.reprompt || state.highValue) {
                    viewModel.requestViewReveal(att.id.toString())
                } else {
                    viewingId = att.id.toString()
                }
            },
            onRemove = { att -> confirmRemoveId = att.id.toString() },
        )
    }

    // Camera consent is a dialog, not prose further down a screen the user is already scrolling —
    // the same treatment FileItemEditScreen and the TOTP scanner both give it.
    if (showCameraDisclosure) {
        LkDialog(
            onDismiss = { showCameraDisclosure = false },
            title = stringResource(R.string.files_edit_camera_dialog_title),
            body = stringResource(R.string.files_edit_camera_dialog_body),
            icon = LkIcons.Camera,
            confirm = LkDialogAction(
                label = stringResource(R.string.files_edit_camera_dialog_confirm),
                onClick = {
                    showCameraDisclosure = false
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
            ),
            dismissLabel = stringResource(R.string.files_edit_camera_dialog_dismiss),
        )
    }

    // The one door in. Gallery takes video as well as images: the video note on this screen is about
    // exactly those, and the generic picker was the only way to attach one before.
    if (showSourceChooser) {
        FileSourceChooserSheet(
            replacing = false,
            onGallery = {
                showSourceChooser = false
                photoLauncher(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onCapture = {
                showSourceChooser = false
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) viewModel.showCapture() else showCameraDisclosure = true
            },
            onDocuments = {
                showSourceChooser = false
                pickLauncher(arrayOf("*/*"))
            },
            onDismiss = { showSourceChooser = false },
        )
    }

    // Removing an attachment cannot be undone from this screen, and it used to happen on a single
    // tap of a text button sitting inside a row whose own tap opens the viewer — which is what the
    // confirmation is here for. What it does *not* do is destroy the bytes on the spot: `remove`
    // drops the reference first and leaves the blob to `BlobGarbageCollector` (see its KDoc), which
    // is precisely what makes an interrupted removal safe. This comment claimed the opposite until
    // 2026-09-07, three lines from the KDoc that contradicts it.
    state.attachments.firstOrNull { it.id.toString() == confirmRemoveId }?.let { att ->
        LkDialog(
            onDismiss = { confirmRemoveId = null },
            title = stringResource(R.string.files_attachments_remove_dialog_title, att.filename),
            body = stringResource(R.string.files_attachments_remove_dialog_body),
            icon = LkIcons.Trash,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.files_remove),
                onClick = {
                    confirmRemoveId = null
                    viewModel.remove(att)
                },
                emphasis = LkActionEmphasis.Destructive,
            ),
        )
    }
}

/**
 * The id of the attachment that most recently joined the list, or null when nothing has.
 *
 * Derived here rather than held in the ViewModel: an arrival flash is presentation, it is over in
 * 1.4 seconds, and a rotation that loses it is the right outcome rather than something to spend a
 * saveable on.
 */
@Composable
private fun rememberNewestAttachmentId(ids: List<String>): String? {
    var known by remember { mutableStateOf(ids.toSet()) }
    var newest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ids) {
        val added = ids.filterNot { it in known }
        if (added.isNotEmpty()) newest = added.last()
        known = ids.toSet()
    }
    return newest
}

/**
 * The file being encrypted, drawn where the finished file will be.
 *
 * **The row is the progress indicator.** Pending and attached are the same row in the same place,
 * so nothing is inserted or reflowed when the encryption finishes — the file does not jump, it
 * stops being pending. `Item Flows.dc.html`: *"The pending row is where the finished row will be,
 * with the shipped note beside it rather than under the buttons."*
 *
 * **Two signals, deliberately.** A ring on the tile and a shimmer under the text. Encryption of a
 * large video can stall for a long time without failing, and a single indicator leaves the user
 * unable to tell "slow" from "stuck". Both are indeterminate, because the chunked-GCM encrypt
 * reports no progress worth showing — a determinate bar over an unknown duration would be a lie,
 * and this app's own rule is that a progress indicator is never padded.
 *
 * The shipped `files_attachments_encrypting_note` is unchanged and now sits beside the name it
 * describes.
 */
@Composable
private fun PendingAttachmentRow(name: String?, onCancel: (() -> Unit)? = null) {
    val c = LkTheme.colors
    val reduce = LocalLkReduceMotion.current
    val note = stringResource(R.string.files_attachments_encrypting_note)
    val announceText = stringResource(R.string.files_attachments_encrypting_announce)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = LkSpace.sm)
            .lkSurface(LkShape.xl, c.surface, c.jade.copy(alpha = 0.20f))
            .padding(LkSpace.md)
            .announce(announceText),
        verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .lkSurface(LkShape.sm, c.jade.copy(alpha = 0.10f), c.jade.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                // A sweeping arc rather than a filled ring: there is no fraction to report, and a
                // ring that sat at some arbitrary fill would be inventing one. Stops dead when the
                // row stops being pending, which is what keeps it a loading indicator rather than
                // the looping attention motion this app forbids.
                val spin = rememberInfiniteTransition(label = "encrypting")
                val angle by spin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        tween(LkMotion.Draw, easing = LkMotion.Progress),
                        RepeatMode.Restart,
                    ),
                    label = "encrypting-angle",
                )
                Canvas(Modifier.size(20.dp).decorative()) {
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2f
                    drawArc(
                        color = c.ring,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke),
                    )
                    drawArc(
                        color = c.jade,
                        startAngle = if (reduce) -90f else angle - 90f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                if (name != null) {
                    Text(
                        name,
                        style = LkText.rowTitle,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(note, style = LkText.caption, color = c.textMuted)
            }
        }
        LkShimmerBar(reduce, width = null)
        // The way out when a provider stalls: Back is blocked while a file is read (see the
        // BackHandler above), and a cloud file that never arrives would otherwise hold the screen.
        if (onCancel != null) {
            LkTextButton(
                text = stringResource(com.localkeep.core.ui.R.string.core_ui_dialog_cancel),
                color = c.textMuted,
                onClick = onCancel,
            )
        }
    }
}

/**
 * A three-letter type tile — jade for media, neutral for everything else.
 *
 * **No thumbnails, and that is the point.** Drawing a preview would mean decrypting a blob to
 * populate a list, which is the opposite of what an encrypted attachment store is for; it would
 * also need its own answer to the reveal gate that governs opening the file. Three letters say what
 * kind of thing a row holds at a glance and cost nothing.
 */
@Composable
private fun FileTypeTile(filename: String) {
    val c = LkTheme.colors
    val ext = filename.substringAfterLast('.', "").uppercase().take(4)
    val media = ext in MEDIA_EXTENSIONS
    Box(
        Modifier
            .size(40.dp)
            .lkSurface(
                LkShape.sm,
                if (media) c.jade.copy(alpha = 0.10f) else c.wash,
                if (media) c.jade.copy(alpha = 0.22f) else c.ring,
            )
            .decorative(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ext,
            style = LkText.badge,
            color = if (media) c.jade else c.textMuted,
            maxLines = 1,
        )
    }
}

/** Extensions that get the jade tile. Everything else is a document. */
private val MEDIA_EXTENSIONS = setOf("JPG", "JPEG", "PNG", "GIF", "WEBP", "HEIC", "MP4", "MOV", "MKV", "WEBM")


/**
 * The Files & media body: header, add button, the two disclosures, errors, then the list — as ONE
 * [LazyColumn], so the whole screen scrolls.
 *
 * It was a plain `Column` with a `LazyColumn` for the files at the bottom, and nothing above that list
 * could scroll. At a large font size or on a 308dp window the header, button and the amber disclosure
 * already fill the screen, so the Tier G video-metadata sentence was cut off at 1.45x and gone at 1.8x,
 * and the empty state's title fell off the bottom (scaling pass SCL-002, 2026-10-19). A
 * `verticalScroll` over the old shape would not do: a `LazyColumn` inside it is measured with
 * unbounded height and throws. So the fixed block became the first items of the one list.
 *
 * Stateless on purpose, so a test can put it in a constrained window without a ViewModel.
 * [onAttach] is the one door in (the button and the empty-state icon both call it).
 */
@Composable
internal fun AttachmentPickerContent(
    state: AttachmentPickerUiState,
    cameraDenial: PermissionDenial?,
    gateUnavailable: Boolean,
    onBack: () -> Unit,
    onAttach: () -> Unit,
    onCancelAttach: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onOpen: (Attachment) -> Unit,
    onRemove: (Attachment) -> Unit,
) {
    val c = LkTheme.colors
    // "Add a file", the File editor's wording and this screen's own sheet title.
    val attachLabel = stringResource(R.string.files_edit_empty_icon_tap_label)
    val workingOrAttachLabel =
        if (state.isWorking) stringResource(R.string.files_encrypting_ellipsis) else attachLabel
    // The label changes with the state, so it has to be named for a screen reader.
    val attachA11y = Modifier.labelled(
        if (state.isWorking) stringResource(R.string.files_attachments_attach_progress_label) else attachLabel,
    )
    // The attachment that just finished encrypting, for one arrival — the same jade flash a new
    // folder row gets. `lkArrival` latches it, so this only has to name the newest.
    val arrivedId = rememberNewestAttachmentId(state.attachments.map { it.id.toString() })

    // Leaving mid-encrypt strands the work: `attach`/`attachCapture` run in the screen's
    // `viewModelScope`, so popping the destination cancels them and the picked file is silently not
    // attached. Blocked rather than confirmed — there is no half-finished form to keep, only an
    // encrypt about to finish; a picked file's pending row carries an explicit Cancel (S3-13).
    BackHandler(enabled = state.isWorking) { /* the encrypt in flight owns this screen */ }

    // `sm` is the gap between file cards. Every block above them is one item with `sm` of its own
    // below it, which makes the 16dp (`md`) screen rhythm the screen had as a Column. Not a Spacer:
    // a Spacer inside a spacedBy Column is spaced too. A conditional block is guarded *outside* its
    // `item {}`, because an empty item still gets a gap.
    val blockGap = Modifier.padding(bottom = LkSpace.sm)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = LkFrame.screenHorizontal,
            end = LkFrame.screenHorizontal,
            top = LkFrame.screenTop,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        item(key = "header") {
            LkScreenHeader(
                title = stringResource(R.string.files_attachments_title),
                subtitle = if (attachmentCountShown(state.attachments.size)) {
                    pluralStringResource(
                        R.plurals.files_attachments_count, state.attachments.size, state.attachments.size,
                    )
                } else {
                    null
                },
                modifier = Modifier.lkEnter().then(blockGap),
                trailing = {
                    LkIconButton(
                        icon = LkIcons.ChevronLeft,
                        label = stringResource(R.string.files_back),
                        onClick = onBack,
                        enabled = !state.isWorking,
                        plateAlignment = Alignment.CenterEnd,
                    )
                },
            )
        }

        // One primary action per screen, and one door: this button and the empty-state icon both open
        // the Gallery / Camera / Documents sheet the File editor uses.
        item(key = "attach") {
            LkPrimaryButton(
                text = workingOrAttachLabel,
                icon = LkIcons.Plus,
                enabled = !state.isWorking,
                onClick = onAttach,
                modifier = attachA11y.then(blockGap),
            )
        }

        if (cameraDenial != null) {
            item(key = "cameraDenial") {
                LkInlineError(
                    message = cameraDenial.message,
                    modifier = Modifier.announce(cameraDenial.message).then(blockGap),
                    actionLabel = cameraDenial.settingsActionLabel,
                    onAction = cameraDenial.settingsActionLabel?.let { { onOpenSettings() } },
                )
            }
        }

        // Two sentences, one card, on every item type: metadata is removed from an image on the way
        // in, the stored file is NOT in Google's backup set, and nothing here ever strips a video.
        // Silence about video would read as the image sentence covering it, so it keeps its own
        // paragraph; the strings stay separate because both are shared and held by security-copy
        // tests. Amber and an info glyph: half of this is a limitation the user has to act on, and a
        // green tick would say "nothing to do here".
        item(key = "notes") {
            LkInlineNote(
                stringResource(R.string.files_attachments_photo_note) + "\n\n" +
                    stringResource(R.string.files_attachments_video_metadata_note),
                accent = c.amber,
                icon = LkIcons.Info,
                modifier = blockGap,
            )
        }

        if (state.error != null) {
            item(key = "error") {
                val message = refusalText(state.error, state.quotaMustRemove, state.canPurchase)
                LkInlineError(message, Modifier.announce(message).then(blockGap))
            }
        }
        if (gateUnavailable) {
            item(key = "gateUnavailable") {
                val message = stringResource(R.string.files_attachments_reveal_unavailable)
                LkInlineError(message, Modifier.announce(message).then(blockGap))
            }
        }
        if (state.viewerAuthMessage != null) {
            item(key = "viewerAuth") {
                val message = stringResource(state.viewerAuthMessage)
                LkInlineError(message, Modifier.announce(message).then(blockGap))
            }
        }

        if (state.isWorking) {
            item(key = "pending") {
                Box(blockGap) {
                    PendingAttachmentRow(name = state.workingName, onCancel = onCancelAttach)
                }
            }
        }

        if (state.attachments.isEmpty() && !state.isWorking) {
            item(key = "empty") {
                LkEmptyState(
                    icon = LkIcons.Attachment,
                    title = stringResource(R.string.files_attachments_empty_title),
                    body = stringResource(R.string.files_attachments_empty_body),
                    modifier = Modifier.padding(top = 40.dp),
                    // An inert plate on an empty screen reads as the primary control, so it opens the
                    // same sheet "Add a file" does, and refuses while a file is encrypting.
                    onIconTap = onAttach,
                    iconTapLabel = attachLabel,
                )
            }
        } else {
            items(state.attachments, key = { att -> att.id.toString() }) { att ->
                // A card per file, the way Trash and Hidden items draw theirs.
                LkGroup(
                    modifier = Modifier.lkArrival(
                        arrived = att.id.toString() == arrivedId,
                        shape = LkShape.xxl,
                        key = att.id,
                    ),
                ) {
                    LkGroupRow(
                        first = true,
                        // The type tile and the filename are adjacent children with nothing between
                        // them; a token keeps "JPG" from sitting welded to the name.
                        horizontalArrangement = Arrangement.spacedBy(LkSpace.md),
                        onClick = { onOpen(att) },
                    ) {
                        FileTypeTile(att.filename)
                        Column(Modifier.weight(1f)) {
                            Text(
                                att.filename,
                                style = LkText.rowTitle,
                                color = c.textPrimary,
                                // Filenames share prefixes far more often than item titles do, so the
                                // tail identifies the file; at one line the largest font size cut it.
                                maxLines = lkScaledMaxLines(),
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${fileTypeLabel(att.filename, att.mime)} · ${att.sizeBytes.readableSize()}",
                                style = LkText.subtitle,
                                color = c.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // Named per row: forty identical "Remove" buttons tell a screen reader nothing.
                        LkIconButton(
                            icon = LkIcons.Trash,
                            label = stringResource(R.string.files_attachments_remove_label, att.filename),
                            tint = c.coral,
                            // Every row's, not just the one being removed: the commit rewrites the
                            // whole vault file, so a second removal is the same double commit.
                            enabled = !state.isRemoving,
                            onClick = { onRemove(att) },
                        )
                    }
                }
            }
        }
    }
}

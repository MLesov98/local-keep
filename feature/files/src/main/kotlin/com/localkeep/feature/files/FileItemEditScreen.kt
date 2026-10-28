package com.localkeep.feature.files

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import android.Manifest
import android.app.Activity
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.localkeep.core.common.sortedByDisplayName
import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderPickerState
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.VaultItem
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.PermissionDenial
import com.localkeep.core.ui.PermissionDenialSaver
import com.localkeep.core.ui.RevealAuth
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.nav.PendingFolderSelection
import com.localkeep.core.ui.openAppSettings
import com.localkeep.core.ui.rememberExternalPickerLauncher
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkCard
import com.localkeep.core.ui.theme.LkCheckRow
import com.localkeep.core.ui.theme.LkChip
import com.localkeep.core.ui.theme.LkCollapsedFoldersSaver
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFolderNameStatus
import com.localkeep.core.ui.theme.LkFolderField
import com.localkeep.core.ui.theme.LkFolderPickerScreen
import com.localkeep.core.ui.theme.LkFolderRow
import com.localkeep.core.ui.theme.folderPickerLeavesOnDismiss
import com.localkeep.core.ui.theme.LkFormField
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkGroupRow
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkNamingSheet
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkActionScaffold
import com.localkeep.core.ui.theme.LocalLkChromeUnpinned
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSheet
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.vault.AddItemOutcome
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.LocalKeepSettings
import com.localkeep.core.vault.SessionKey
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileItemEditUiState(
    val isNew: Boolean = true,
    /** How many stored files have to go before another fits — quota refusal only. See
     * [AttachmentQuota.mustRemove] and the twin field on `AttachmentPickerUiState`. */
    val quotaMustRemove: Int = 1,
    /** Whether this build can sell the unlock; see the twin field on `AttachmentPickerUiState`. */
    val canPurchase: Boolean = false,
    val itemId: UUID = UUID.randomUUID(),
    val title: String = "",
    val filename: String = "",
    val mime: String = "",
    val sizeBytes: Long = 0L,
    val blobRef: String? = null,
    val thumbnailRef: String? = null,
    val capturedInApp: Boolean = false,
    val removedFromGallery: Boolean = false,
    /**
     * Whether the staged file has an original still sitting in the device gallery. Import-session
     * only — see [StagedFileSource.hasGalleryOriginal] for why it is not persisted, and
     * [legacyGalleryDeleteNoteVisible] for the one thing that reads it.
     */
    val hasGalleryOriginal: Boolean = false,
    /** Transient metadata state; never persisted into the vault schema. */
    val metadataCleanupState: MetadataCleanupState = MetadataCleanupState.NOT_APPLICABLE,
    val reprompt: Boolean = false,
    /**
     * Where the file gets filed, and everything the picker needs to ask.
     *
     * Added 2026-08-19. Until then a File was the one item type that could not be put in a folder
     * at all — not here and not afterwards — so a vault organised into folders had its scans and
     * photographs sitting loose outside every one of them. The fields mirror the item editor's
     * because they drive the same control ([com.localkeep.core.ui.theme.LkFolderPicker]).
     */
    val folderId: UUID? = null,
    val folders: List<Folder> = emptyList(),
    /** How many live items each folder holds, for the chooser's row names ("Personal (12)"). */
    val folderItemCounts: Map<UUID, Int> = emptyMap(),
    val newFolderName: String = "",
    val showNewFolderInput: Boolean = false,
    /** Where a folder created from the picker lands — the folder currently expanded, or the root. */
    val newFolderParentId: UUID? = null,
    /**
     * What this file is tagged with, and what is half-typed into the add-tag box.
     *
     * Added 2026-09-20 (4C-11). A File was the one type with no route to a tag at all — not in this
     * editor and not in the row menu — while the vault list's own search hint promises it searches
     * "tags, folders", so a vault organised by tag had its scans and photographs unreachable by the
     * only handle the user had given everything else. [VaultItem.tags] is indexed generically by
     * `VaultRepository.searchHaystack`, so carrying one here is the whole of what was missing.
     */
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
    /**
     * The favourite flag, reachable from the item itself rather than only from its row.
     *
     * The same gap 4A-F5/2B-6 closed on the item editor on 2026-09-20, in the editor that fix did
     * not reach: the flag was settable only from the vault list's row menu, so a file already open
     * in front of the user could not be favourited without leaving it.
     */
    val favorite: Boolean = false,
    /**
     * The user's own note about this file — what the scan is of, why it was kept.
     *
     * Added 2026-09-21 (4C-11's second half). A File was the only type a person could store but
     * not annotate: its title was the whole of the writable text on it. Unlike the tags and the
     * favourite flag, which live on [com.localkeep.core.model.VaultItem] and needed no format
     * change, this is a new field on [com.localkeep.core.model.File] — schema 7 to 8, see the
     * schema history in `Vault.kt`.
     */
    val notes: String = "",
    /**
     * The gallery original Android may be asked to delete once the file is saved (API 30+), or
     * null. Its document-picker twin is [pendingDocumentDeleteUri].
     */
    val pendingDeleteUri: Uri? = null,
    /** A document-picker original this app may delete after save — see [OriginalDocuments]. */
    val pendingDocumentDeleteUri: Uri? = null,
    /**
     * What [MetadataProbe] found in the current file's bytes, or null while the probe of a stored
     * file is still running. Drives the metadata note — see [metadataNoteFor].
     */
    val metadataPresence: MetadataPresence? = null,
    /** The post-save question about the original, while it is waiting for an answer. */
    val originalPrompt: OriginalPrompt? = null,
    /**
     * True while the original is being deleted — Android's dialog is up, or a document delete is
     * running — so the screen waits for the outcome before it leaves.
     */
    val originalDeleteInFlight: Boolean = false,
    /** How many attachments the stored item carries — read-only here; see [manageAttachmentsRowVisible]. */
    val attachmentCount: Int = 0,
    val showCapture: Boolean = false,
    val showViewer: Boolean = false,
    /**
     * A viewer open requested on a `reprompt` item, parked here until the biometric challenge
     * passes. Reprompt items re-ask before *any* of their secrets are released (spec §5.2), and the
     * file/attachment blob is the item's content — without this gate it was the one unguarded
     * route to a reprompt item's data.
     */
    val pendingViewerReveal: Boolean = false,
    val saved: Boolean = false,
    @StringRes val error: Int? = null,
    /** True while the vault write is in flight — see the Save button, which it also disables. */
    val isWorking: Boolean = false,
    /**
     * Set when the open file stopped being visible under us, which sends the screen back.
     *
     * The twin of [com.localkeep.feature.itemedit.ItemEditUiState.concealed], for the same reason and
     * with the same narrowness: a reveal session ended — Hide again now, a lock, or the background
     * grace expiring — while this screen was holding a concealed file's title, filename, thumbnail
     * and a viewer one tap from decrypting its bytes. Lease *expiry* is not this case: expiry makes
     * the record ordinary and visible, so an open editor stays open.
     */
    val concealed: Boolean = false,
)

/**
 * The parts of the form that represent user work, with the transient view state stripped out, so
 * two snapshots can be compared to answer "are there unsaved edits?".
 */
internal fun FileItemEditUiState.editableOnly(): FileItemEditUiState = copy(
    pendingDeleteUri = null,
    pendingDocumentDeleteUri = null,
    // Measured, not edited: a stored file's probe lands after load, and counting it would make every
    // reopened image "dirty" before the user touched anything.
    metadataPresence = null,
    // A refused cleanup rewrote nothing: blobRef, size and thumbnail are all as they were, and the
    // refusal is already on screen as an error. Counting FAILED made Back ask "Discard changes?"
    // over an untouched file. Folded back to PENDING, the state it was in before the attempt.
    metadataCleanupState = if (metadataCleanupState == MetadataCleanupState.FAILED) {
        MetadataCleanupState.PENDING
    } else {
        metadataCleanupState
    },
    originalPrompt = null,
    originalDeleteInFlight = false,
    // Read from the stored item, never edited here.
    attachmentCount = 0,
    showCapture = false,
    showViewer = false,
    saved = false,
    error = null,
    isWorking = false,
    concealed = false,
    // `folderId` is deliberately *not* stripped: moving a file to another folder is an edit, and
    // backing out of it without warning would lose the move. Everything else the picker holds is
    // in-flight UI — which rows are open, what is half-typed into the name field — and a form that
    // asked "discard changes?" because a disclosure was expanded would be crying wolf.
    folders = emptyList(),
    // Stripped for the same reason `folders` is, and its absence was a live defect rather than a
    // tidiness point: `baseline` is anchored at construction, `init` then runs `loadFolders()`, and
    // the counts it writes are the *only* thing that differs. So every "New file" opened in a vault
    // whose folders hold anything was dirty before the first frame, and backing out of it — which
    // is now the ordinary way to leave the add sheet — asked to discard a file that was never
    // imported. A vault with no foldered items has empty counts either way, which is why this
    // survived: it cannot reproduce on a fresh install.
    folderItemCounts = emptyMap(),
    newFolderName = "",
    showNewFolderInput = false,
    newFolderParentId = null,
    // `tags`, `favorite` and `notes` are deliberately kept, for the reason `folderId` is: all three
    // are user work, and backing out of a tag without a warning would lose it. `tagInput` is the half-typed
    // box — in-flight UI, and a form that asked "discard changes?" because a word was sitting
    // unsubmitted in an input would be crying wolf.
    tagInput = "",
)

/**
 * Whether the naming sheet (§ `showNamingSheet`) must close because [error] just arrived.
 *
 * Pulled out of the `LaunchedEffect` that calls it so the decision is a plain, unit-testable
 * function rather than only visible through a live Composable — see `ErrorRevealPinTest` in
 * `:core:ui` for why a Compose test is the wrong tool for pinning a UI-state decision like this
 * one (a bare `createComposeRule` window has no scrim/IME to reproduce the failure against, so a
 * naive Compose test for this would pass identically with the bug still in place).
 */
internal fun namingSheetShouldCloseOnError(error: Int?): Boolean = error != null

/**
 * Whether the naming sheet is drawn: requested, and not while the question about the original is.
 *
 * Seen on emulator-5554 2026-10-09: the "Name it" sheet stayed on screen, its Save dimmed, under
 * Android's "Allow LocalKeep to delete this photo?". Nothing closed it on success, because until a
 * post-save step existed the screen was popped the instant the save landed. Derived rather than
 * cleared: closing the sheet and then leaving the screen are two teardowns of one intent and play
 * as a flicker of the form between them (`checkNoDismissThenNavigate`), so with no question to ask
 * the sheet stays up and leaves with the screen.
 */
internal fun namingSheetVisible(requested: Boolean, prompt: OriginalPrompt?): Boolean =
    requested && prompt == null

/** Which original the post-save question is about. */
enum class OriginalKind { PHOTO, VIDEO, DOCUMENT }

/** The question asked after a save: delete the [kind] original of the item called [name]? */
data class OriginalPrompt(val kind: OriginalKind, val name: String)

/**
 * The question to ask once [state] has been saved, or null when there is no original to offer.
 *
 * Reported from a phone 2026-10-09: right after Save, Android's own *"Allow LocalKeep to delete
 * this photo?"* appeared with nothing before it, so it read as the app demanding to destroy
 * something — and did not say whether the photo meant was the gallery's or the one just saved.
 * Android owns that sentence and it cannot be reworded, so the app asks first in its own words, and
 * Android's dialog becomes the confirmation of an answer the user has already given. Documents have
 * no system dialog at all, so for them this question is the only confirmation.
 */
internal fun originalPromptFor(state: FileItemEditUiState): OriginalPrompt? {
    val name = state.title.ifBlank { state.filename }
    return when {
        state.pendingDocumentDeleteUri != null -> OriginalPrompt(OriginalKind.DOCUMENT, name)
        state.pendingDeleteUri != null -> OriginalPrompt(
            if (state.mime.startsWith("video/")) OriginalKind.VIDEO else OriginalKind.PHOTO,
            name,
        )
        else -> null
    }
}

/** What the metadata section of the File editor says, if anything. */
internal enum class MetadataNote {
    /** Nothing: not an image, the probe is still running, or the image carries nothing to remove. */
    NONE,

    /** Removed during this session. */
    REMOVED,

    /** Measured: the bytes carry something "Delete all metadata" would remove. */
    FOUND,

    /** Unknowable from the bytes (HEIF, a damaged file): the hedged sentence, and the button. */
    MAYBE,
}

/**
 * Which metadata note to draw for an image.
 *
 * It used to be one sentence for every image, on every open — *"This image may still carry location
 * and camera data"* — over a screenshot with none and over a photo cleaned an hour earlier, because
 * cleanup is not recorded anywhere (2026-10-09). The bytes now answer instead ([MetadataProbe]), and
 * the hedge is kept only for the files whose bytes cannot.
 */
internal fun metadataNoteFor(
    cleanup: MetadataCleanupState,
    presence: MetadataPresence?,
): MetadataNote = when {
    cleanup == MetadataCleanupState.REMOVED -> MetadataNote.REMOVED
    presence == null -> MetadataNote.NONE
    presence == MetadataPresence.ABSENT -> MetadataNote.NONE
    presence == MetadataPresence.PRESENT -> MetadataNote.FOUND
    else -> MetadataNote.MAYBE
}

/** Which metadata note (if any) the File editor's metadata section shows for a given mime. */
internal enum class FileMetadataNoteKind { IMAGE, VIDEO, NONE }

/**
 * Found 2026-09-09: this whole section used to be `if (mime.startsWith("image/"))`,
 * so a video fell through to `NONE` in effect but by omission rather than by decision — nothing
 * else in the mime space was ever considered. Pulled out for the same reason
 * [namingSheetShouldCloseOnError] was: a plain, unit-testable function beats only being visible
 * through a live Composable.
 *
 * `VIDEO` gets a disclosure note, never a cleanup button: [PhotoPickerImport.stageFile] only
 * strips metadata when `imageMime != null`, so a video's bytes are encrypted exactly as picked and
 * there is nothing a "Delete metadata" press could do. Everything else (documents, audio, …) stays
 * `NONE` — this screen has no metadata story for those formats at all, and a note asserting
 * something about metadata that format does not carry would be its own false claim.
 */
internal fun fileMetadataNoteKindFor(mime: String): FileMetadataNoteKind = when {
    // SVG is XML text. It has no EXIF block for [ExifStripper] to remove (the cleaner refuses it as
    // an unsupported format), so the photo warning and its "Delete all metadata" button promised
    // something the editor cannot do. Silence is the honest answer for a format with no story.
    mime.equals("image/svg+xml", ignoreCase = true) -> FileMetadataNoteKind.NONE
    mime.startsWith("image/") -> FileMetadataNoteKind.IMAGE
    mime.startsWith("video/") -> FileMetadataNoteKind.VIDEO
    else -> FileMetadataNoteKind.NONE
}

/**
 * What the empty file card says, as (title, body) string resources.
 *
 * While a pick is being staged the card still has no file, so it kept inviting the user to import
 * one under a greyed-out button, with nothing saying the import they had just started was running.
 * It now says so, in the words the attachments list uses for the same work, so a large video's
 * wait reads as a wait.
 */
internal fun fileEmptyStateCopy(isWorking: Boolean): Pair<Int, Int> =
    if (isWorking) {
        R.string.files_encrypting_ellipsis to R.string.files_attachments_encrypting_note
    } else {
        R.string.files_edit_empty_title to R.string.files_edit_empty_body
    }

/**
 * Whether the editor offers "Manage attachments".
 *
 * A File item *is* a file, so the row used to lead to a second list with its own "Add a file"
 * control — the same job in two places (reported 2026-10-12). It now shows only where there is
 * something to manage: a File item that already carries attachments keeps the way to them, and one
 * with none no longer offers a second place to add. Never on an unsaved item, whose id is not in the
 * vault yet, so anything attached through the picker was silently dropped.
 */
internal fun manageAttachmentsRowVisible(isNew: Boolean, attachmentCount: Int): Boolean =
    !isNew && attachmentCount > 0

/**
 * The nav argument the file routes carry — `file/{itemId}`.
 *
 * Spelled out here rather than imported, for the same reason `:feature:itemedit` spells out its
 * own: a feature module cannot depend on `:app`, where the routes are declared. A rename there
 * degrades rather than breaks — the construction-time load finds nothing and the screen's effect
 * still loads the item, which is what happened on every entry before this existed.
 */
internal const val ROUTE_FILE_ITEM_ID = "itemId"

/**
 * Whether this state describes some *other* item than the one the route asked for.
 *
 * The file editor has no skeleton branch, so before the item arrived it rendered the *new file*
 * form — empty title, no filename, no thumbnail — which is not a neutral placeholder but a
 * confident statement of the wrong thing. Same defect, same shape, and the same cure as
 * `ItemEditViewModel`: load at construction so there is no such frame, and keep this only to decide
 * whether the screen's effect still has anything to do.
 */
internal fun isShowingWrongFileItem(state: FileItemEditUiState, requestedId: String): Boolean =
    state.itemId.toString() != requestedId

/**
 * What to say when metadata cleanup refuses.
 *
 * The sanitizer distinguishes five reasons and every one of them used to reach the screen as
 * "Metadata could not be removed" — so an image over the decode budget, an image whose bytes are
 * not the format the file claims, and a disk that ran out all read identically, though only the
 * first is fixed by choosing a smaller picture. The generic sentence stays for the failures that
 * genuinely are generic, above all the storage ones.
 */
@StringRes
internal fun metadataCleanupMessage(e: Throwable): Int =
    when ((e as? UnstrippableImageException)?.failure) {
        MetadataCleanupFailure.TOO_LARGE -> R.string.files_edit_metadata_cleanup_too_large
        MetadataCleanupFailure.MALFORMED -> R.string.files_edit_metadata_cleanup_malformed
        MetadataCleanupFailure.UNSUPPORTED_FORMAT -> R.string.files_edit_metadata_cleanup_unsupported
        MetadataCleanupFailure.UNKNOWN, null -> R.string.files_edit_metadata_cleanup_failed
    }

/**
 * What a refused file-view challenge says happened — the editor's half of [viewerGateMessage].
 *
 * Cancelling a prompt is not the same as being rejected by it, and telling someone their credential
 * was not accepted when it was never checked is the defect this mapping exists to avoid. Same four
 * strings the standalone viewer route uses, because it is the same challenge over the same bytes.
 */
@StringRes
internal fun viewerRevealMessage(outcome: RevealAuth.RevealOutcome): Int = when (outcome) {
    RevealAuth.RevealOutcome.UserCancelled -> R.string.files_viewer_auth_cancelled
    RevealAuth.RevealOutcome.Denied -> R.string.files_viewer_auth_denied
    RevealAuth.RevealOutcome.Unavailable -> R.string.files_viewer_auth_unavailable
    is RevealAuth.RevealOutcome.Error,
    RevealAuth.RevealOutcome.Success,
    -> R.string.files_viewer_auth_failed
}

/**
 * Whether the "Android 9/10 cannot delete the original for you" note applies to what is on screen.
 *
 * Three conditions, and the third was missing until 2026-09-13: the note fired on every picker-imported file below API 30, so a PDF or a text file brought
 * in through "From documents" — which never had a gallery original, and which
 * [PhotoPickerImport.stageFile] already knows has none, since it suppresses
 * [StagedFileSource.pendingGalleryDeleteUri] on exactly that signal — was told to go and remove
 * something from the Gallery app that was never there. An instruction the reader cannot carry out is
 * worse than no instruction. The comment above the block this gates says "every gallery-imported
 * image", which is what was meant; the document route arrived on 2026-09-08 and the gate was not
 * widened with it.
 *
 * [hasGalleryOriginal] is import-session state, so a reopened item shows no note: the persisted
 * `File` records `capturedInApp` and `removedFromGallery` and nothing about which picker it came
 * from, and teaching it would be a vault-format change.
 */
internal fun legacyGalleryDeleteNoteVisible(
    sdkInt: Int,
    capturedInApp: Boolean,
    hasGalleryOriginal: Boolean,
    // Measured on the API 28 image, 2026-10-09: below API 30 a gallery pick comes back through the
    // system document picker as a deletable document URI, and the app then asks about it and
    // deletes it itself ([OriginalDocuments]). The by-hand note is for the cases where it cannot.
    originalDeletable: Boolean = false,
): Boolean = sdkInt < Build.VERSION_CODES.R && !capturedInApp && hasGalleryOriginal && !originalDeletable

/**
 * The discard prompt's title and body for a file editor holding unsaved work.
 *
 * The import wording — "The file you imported and its details haven't been saved to the vault yet"
 * — is true only of a new file. On a file already in the vault, Discard drops the edits and nothing
 * else, and telling that user their file was never saved is false in the alarming direction (C1
 * device pass, 2026-10-04). Same words the item editor uses for its own edits.
 */
internal fun fileDiscardDialogCopy(isNew: Boolean): Pair<Int, Int> = if (isNew) {
    R.string.files_edit_discard_dialog_title to R.string.files_edit_discard_dialog_body
} else {
    R.string.files_edit_discard_changes_dialog_title to R.string.files_edit_discard_changes_dialog_body
}

/**
 * The title once a file named [newFilename] is staged.
 *
 * A title that is blank, or is only the name of the file being replaced, is a suggestion and
 * follows the new file; anything else was typed and stays. It used to be `title.ifBlank`, so the
 * first import's filename stuck: `empty.csv` replaced by `18.mp4` was offered for naming as
 * "empty.csv" (C1 device pass, 2026-10-04).
 */
internal fun FileItemEditUiState.titleFor(
    newFilename: String,
    newMime: String = "",
    generatedPhotoTitle: String = "",
): String =
    if (title.isBlank() || title == filename || title == suggestedTitle(filename, mime, generatedPhotoTitle)) {
        suggestedTitle(newFilename, newMime, generatedPhotoTitle)
    } else {
        title
    }

/**
 * What a staged file is offered as a title: its filename, unless that is no name at all.
 *
 * The system photo picker reports the media-store row id as the display name — "1000000206.jpg" —
 * which says nothing and is what a new File's title field opened with. An image whose name stem is
 * only digits is offered [generatedPhotoTitle] ("Photo 16 Oct 2026", resolved by the screen)
 * instead. Images only, since that title says "Photo". An empty [generatedPhotoTitle] keeps the
 * filename, so a caller with no string to offer behaves as before. Applies to a freshly staged
 * file's suggestion only; a stored item's title is never rewritten.
 */
internal fun suggestedTitle(filename: String, mime: String, generatedPhotoTitle: String): String {
    val stem = filename.substringBeforeLast('.', filename)
    val platformId = stem.isNotEmpty() && stem.all { it in '0'..'9' }
    return if (generatedPhotoTitle.isNotBlank() && mime.startsWith("image/") && platformId) {
        generatedPhotoTitle
    } else {
        filename
    }
}

@HiltViewModel
class FileItemEditViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val lockManager: VaultLockManager,
    private val photoPickerImport: PhotoPickerImport,
    val fileBlobStore: FileBlobStore, // exposed for the inline viewer (Task 9 Step 2) — see Self-Review
    private val entitlement: com.localkeep.core.billing.Entitlement, // 14b/§G — Files & Media is Pro
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(FileItemEditUiState(canPurchase = entitlement.canPurchase))
    val state: StateFlow<FileItemEditUiState> = _state.asStateFlow()

    /** The form as last loaded or saved; anything different from this is an unsaved edit. */
    private var baseline: FileItemEditUiState = _state.value.editableOnly()

    /**
     * The encrypted source currently being edited. This is deliberately process-local: the vault
     * stores only the committed [com.localkeep.core.model.File], never staging or cleanup state.
     */
    private var stagedSource: StagedFileSource? = null
    private var stagedSourceIsCommitted = false

    /** The route id [loadItem] has already run a lookup for, whatever the answer was. */
    private var attemptedLoadOf: String? = null

    /** The in-flight [onCaptured] pipeline, if one is running — cancelled by [dismissCapture]. */
    private var captureJob: kotlinx.coroutines.Job? = null

    /**
     * Set by [dismissCapture] while [onCaptured]'s pipeline is still running, so it can tell its
     * result is stale and drop it instead of overwriting whatever the user returned to. [captureJob]
     * cancellation alone is not enough: the strip/encrypt work inside the pipeline is not
     * cooperative, so cancellation may not preempt a step already in progress — see 15-3.
     */
    private var captureCancelled = false

    init {
        restoreRequestedItem()
        // The projection is read once, in `loadItem`, and that read is the only thing standing
        // between this screen and a concealed file — so `file/{itemId}/settings` was correct at the
        // instant it opened and stale for as long as it stayed open. Open a hidden file's settings
        // from a reveal session, keep interacting, and the 120 s window closes underneath: the vault
        // list conceals the file, the standalone viewer now drops it, and this screen carried on
        // showing its name, its folder and a thumbnail with a viewer one tap away.
        //
        // Copied from `ItemEditViewModel`, including the narrowness. *Concealed*, not merely absent:
        // absent is also true of a save in flight and of a screen that subscribed before this
        // StateFlow's `Locked` seed was replaced. Both conditions together can only mean the file is
        // under a lease and the projection is not currently showing it, which no ordinary file can
        // satisfy. A new file is not in the vault yet and a save in flight is momentarily neither
        // here nor there; neither is a concealment.
        viewModelScope.launch {
            repository.presentationState.collect { presentation ->
                val current = _state.value
                if (current.isNew || current.isWorking || current.concealed) return@collect
                val concealedNow = presentation is VaultPresentationState.Unlocked &&
                    current.itemId in presentation.snapshot.concealedItemIds &&
                    presentation.snapshot.liveItems.none { it.id == current.itemId }
                if (!concealedNow) return@collect
                // Replaced wholesale rather than field by field: the state holds the filename, the
                // folder path and the blob reference the viewer decrypts from, and enumerating them
                // is how one gets missed the next time a field is added. An uncommitted staged blob
                // is left to the on-unlock sweep, exactly as a save that fails already leaves it.
                _state.value = FileItemEditUiState(
                    isNew = false, itemId = current.itemId, concealed = true, canPurchase = current.canPurchase,
                )
                // Otherwise the emptied form differs from the baseline anchored at load, `isDirty()`
                // says true, and the Back the effect below fires races a "discard your changes?"
                // dialog over a file that is no longer there to change.
                baseline = _state.value.editableOnly()
            }
        }
    }

    /**
     * Loads the item the route names, at construction, before anything is composed.
     *
     * `LaunchedEffect` runs after the frame that created it has been drawn, so starting the lookup
     * from the screen meant every entry painted one frame of the empty *new file* form and only
     * then the file. Measured in `:feature:itemedit`, which had the identical defect: the
     * placeholder drew 42 ms after composition and the real content 34 ms after that. Invisible on
     * an ordinary open, because the screen's fade has barely started; plainly visible when you go
     * back and open the same item again straight away.
     */
    private fun restoreRequestedItem() {
        // Before the id check, because a *new* file needs the folder list too — it is the one route
        // with nothing to load and a picker to fill.
        loadFolders()
        val id = savedStateHandle.get<String>(ROUTE_FILE_ITEM_ID) ?: return
        // `file/new` has no id argument, but a route rename could hand a non-UUID through here. A
        // UUID is the only thing that names a stored item.
        if (runCatching { UUID.fromString(id) }.isFailure) return
        loadItem(id)
    }

    /**
     * Whether the screen's effect still has work to do.
     *
     * [restoreRequestedItem] covers every entry but one — a back stack restored while the vault was
     * sealed, where [loadItem] read nothing and left no trace. Asking only "is the state wrong?"
     * would also re-run the lookup for an item that no longer exists, scanning the whole vault a
     * second time to reach the answer construction already had.
     */
    fun needsInitialLoad(requestedId: String): Boolean =
        attemptedLoadOf != requestedId && isShowingWrongFileItem(_state.value, requestedId)

    /**
     * Whether leaving now would discard work (UI/UX spec §5.1 unsaved-change protection).
     *
     * Snapshot comparison rather than a dirty flag per change handler, matching
     * [com.localkeep.feature.itemedit.ItemEditViewModel]: a newly added editable field is then
     * covered automatically, and the failure mode is an unnecessary prompt rather than silently
     * binning an imported photo — the right way round to be wrong.
     */
    fun isDirty(): Boolean = _state.value.editableOnly() != baseline

    fun loadItem(id: String) {
        // A sealed vault has nothing to read, and `getAllItems` goes through `requireVault`, which
        // throws rather than saying so. Reproduced on the item editor's identical path: open an
        // item, background the app, `am kill` it, relaunch — NavController restores the back stack,
        // this screen composes while the vault is still sealed, and the app dies before the unlock
        // observer has moved the user on. `file/{itemId}` is restored exactly the same way.
        if (lockManager.lockState.value !is LockState.Unlocked) return
        // Before the answer: the question is "did a lookup run?", not "did one succeed?". A sealed
        // vault returns above and leaves no trace, so the screen still retries once it is open.
        attemptedLoadOf = id
        // Through the projection: a concealed file must not open, and a restored back stack carrying
        // a `file/{itemId}` route from before a lock is exactly how a stale id arrives here.
        val item = repository.visibilitySnapshot().liveItems.find { it.id.toString() == id } ?: return
        val c = item.content as? com.localkeep.core.model.File ?: return
        _state.value = _state.value.copy(
            isNew = false, itemId = item.id, title = item.title,
            filename = c.filename, mime = c.mime, sizeBytes = c.sizeBytes,
            blobRef = c.blobRef, thumbnailRef = c.thumbnailRef,
            capturedInApp = c.capturedInApp, removedFromGallery = c.removedFromGallery,
            // False on every reopened item, and deliberately: the vault model records nothing about
            // which picker a file arrived through, so the honest answer here is "unknown", and a
            // note telling someone to delete an original that may not exist is the defect 24-1
            // fixed. See [StagedFileSource.hasGalleryOriginal] (2026-09-13).
            hasGalleryOriginal = false,
            pendingDeleteUri = null,
            pendingDocumentDeleteUri = null,
            metadataCleanupState = if (c.mime.startsWith("image/")) {
                MetadataCleanupState.PENDING
            } else {
                MetadataCleanupState.NOT_APPLICABLE
            },
            // Null until the probe below answers, so nothing is said in the meantime rather than
            // the hedged sentence appearing and then vanishing.
            metadataPresence = null,
            reprompt = item.reprompt,
            folderId = item.folderId,
            tags = item.tags,
            favorite = item.favorite,
            notes = c.notes,
            attachmentCount = item.attachments.size,
        )
        stagedSource = StagedFileSource(
            blobRef = c.blobRef,
            thumbnailRef = c.thumbnailRef,
            filename = c.filename,
            mime = c.mime,
            sizeBytes = c.sizeBytes,
            generationId = UUID.randomUUID(),
            metadataState = if (c.mime.startsWith("image/")) {
                MetadataCleanupState.PENDING
            } else {
                MetadataCleanupState.NOT_APPLICABLE
            },
            sourceUri = null,
            capturedInApp = c.capturedInApp,
            removedFromGallery = c.removedFromGallery,
            pendingGalleryDeleteUri = null,
            hasGalleryOriginal = false,
        )
        stagedSourceIsCommitted = true
        loadFolders()
        // Anchor the unsaved-change baseline to what was actually loaded — after loadFolders, so
        // the folder list arriving is not itself an "unsaved change".
        baseline = _state.value.editableOnly()
        if (c.mime.startsWith("image/")) probeStoredMetadata(c.blobRef)
    }

    /**
     * Reads a stored image's bytes to learn whether it carries metadata — see [MetadataProbe].
     *
     * Streamed through [FileBlobStore.openDecryptingSource] a frame at a time, so a large photo
     * costs a read, never an allocation its size. Any failure answers UNKNOWN, which keeps the
     * hedged note: the one outcome this must never produce is "nothing to remove" from a read that
     * did not finish. The answer is dropped if the file was replaced while it ran.
     */
    private fun probeStoredMetadata(blobRef: String) {
        viewModelScope.launch {
            val presence = try {
                val session = lockManager.sessionSnapshotOrNull()
                if (session == null) {
                    MetadataPresence.UNKNOWN
                } else {
                    try {
                        withContext(Dispatchers.IO) {
                            fileBlobStore.openDecryptingSource(blobRef, session.dek).use(MetadataProbe::probe)
                        }
                    } finally {
                        Zeroizer.wipe(session.dek)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MetadataPresence.UNKNOWN
            }
            if (_state.value.blobRef == blobRef) _state.value = _state.value.copy(metadataPresence = presence)
        }
    }

    /**
     * A borrow of the session key for the viewer, not the key itself: the viewer stays mounted
     * across an auto-lock, and `lock()` zeroes the manager's own array in place. See [SessionKey].
     */
    fun sessionKeyOrNull(): SessionKey? = lockManager.sessionKeyOrNull()

    /**
     * The photo picker and the system delete dialog both background the app. Without this the
     * grace timer runs while the user is choosing, and the import lands on a locked vault.
     * See [com.localkeep.core.ui.rememberExternalPickerLauncher], which pairs these for the caller.
     */
    fun onPickerOpened() = lockManager.beginExternalPicker()

    fun onPickerClosed() = lockManager.endExternalPicker()

    fun onTitleChanged(v: String) { _state.value = _state.value.copy(title = v) }

    // --- Filing -------------------------------------------------------------
    // Deliberately the same behaviour as the item editor's, method for method, because both drive
    // one shared control and a picker that created folders differently depending on which screen
    // opened it would be worse than either behaviour on its own.

    fun onFolderSelected(value: UUID?) { _state.value = _state.value.copy(folderId = value) }

    /**
     * Starts a new file in the folder the vault list was browsing — the item editor's method of the
     * same name, called the same way: from the `FILE_NEW` route only, after construction, never from
     * `init` (this ViewModel serves an existing file's settings too). See [PendingFolderSelection].
     *
     * The file path was left out of that hand-over on 2026-08-12 because this editor had no folder
     * field to receive it, and stayed out after the field arrived on 2026-08-19 — so "Add to My
     * Folder" saved the file at the top level (device, 2026-10-04).
     *
     * The baseline moves with the seed when nothing has been touched yet: the folder is where the
     * add flow pointed, not something the user chose, and an untouched new file must still leave
     * without a discard prompt.
     */
    fun seedFolderFromPending() {
        val pending = PendingFolderSelection.take() ?: return
        if (!_state.value.isNew || _state.value.folders.none { it.id == pending }) return
        val untouched = isDirty().not()
        _state.value = _state.value.copy(folderId = pending)
        if (untouched) baseline = _state.value.editableOnly()
    }

    fun onTagInputChanged(value: String) { _state.value = _state.value.copy(tagInput = value) }

    /**
     * Adds the typed tag, the same way the item editor's does — including what it does about a
     * duplicate, which is say so rather than refuse in silence.
     *
     * Case-insensitive, because "Work" and "work" are one tag and nothing on screen says so. The
     * item editor names the tag it already has (`itemedit_error_already_tagged`, with the existing
     * spelling as an argument); this screen's single error slot is formatted with the quota
     * arguments for every message it carries, so the sentence here carries no placeholder rather
     * than one that would be filled with a file count.
     */
    fun addTag() {
        val current = _state.value
        val tag = current.tagInput.trim()
        if (tag.isEmpty()) return
        if (current.tags.any { it.equals(tag, ignoreCase = true) }) {
            _state.value = current.copy(tagInput = "", error = R.string.files_edit_error_already_tagged)
            return
        }
        _state.value = current.copy(tags = current.tags + tag, tagInput = "", error = null)
    }

    fun removeTag(tag: String) { _state.value = _state.value.copy(tags = _state.value.tags - tag) }

    fun onFavoriteChanged(value: Boolean) { _state.value = _state.value.copy(favorite = value) }

    fun onNotesChanged(value: String) { _state.value = _state.value.copy(notes = value) }

    fun onNewFolderNameChanged(value: String) { _state.value = _state.value.copy(newFolderName = value) }

    fun showNewFolderInput() {
        // At the top level, matching the comp's card and the item editor's: the chooser draws the
        // tree flat, so there is no expanded folder to create inside. Nesting is done from the
        // Folders screen, which owns a parent picker.
        _state.value = _state.value.copy(showNewFolderInput = true, newFolderParentId = null)
    }

    /**
     * Abandons the new-folder name without creating it.
     *
     * Clears the text as well as the card: [save] commits a pending name, so a name left behind by
     * a cancelled create would be made into a real folder by the next Save.
     */
    fun hideNewFolderInput() {
        _state.value = _state.value.copy(showNewFolderInput = false, newFolderName = "")
    }

    fun createFolder() { viewModelScope.launch { createFolderInternal(keepNaming = false) } }

    /**
     * Commits the pending folder name. True when committed or nothing was pending; false when
     * refused (error already set).
     *
     * Extracted for the same reason the item editor's is: [save] runs it too, because the small
     * Create button is otherwise the only control that commits a typed name and the screen's
     * prominent Save would silently discard it.
     *
     * [keepNaming] leaves the card open and empty for the next name. Nothing passes true now: the
     * picker's Create collapses the card after each folder (2026-10-14, reversing the 2026-10-12
     * run-of-folders behaviour), and [save] is leaving.
     */
    private suspend fun createFolderInternal(keepNaming: Boolean = false): Boolean {
        val name = _state.value.newFolderName.trim()
        if (name.isEmpty()) return true
        // Siblings only. "Bank" inside Work and "Bank" inside Personal are two different places.
        val parentId = _state.value.newFolderParentId
        if (_state.value.folders.any { it.parentId == parentId && it.name.equals(name, ignoreCase = true) }) {
            _state.value = _state.value.copy(error = R.string.files_error_folder_name_exists)
            return false
        }
        return runCatching {
            // Through the repository, so the hierarchy's own rules — sibling names, the depth
            // limit — apply here exactly as they do on the management screen.
            val folder = repository.createFolder(name, parentId)
            loadFolders()
            _state.value = _state.value.copy(
                folderId = folder.id,
                newFolderName = "",
                showNewFolderInput = keepNaming,
            )
        }.onFailure {
            _state.value = _state.value.copy(error = R.string.files_error_folder_action_failed)
        }.isSuccess
    }

    private fun loadFolders() {
        // Through the vault list's own projection, not the raw vault. The picker offers places to
        // file an item, and a trashed folder is not one: filing there would put the file somewhere
        // the list cannot show and Trash does not list either. Neither is an invisible folder outside
        // a reveal — read raw, the picker named one ("Personal (3)") while the list hid it.
        val visible = runCatching { repository.visibilitySnapshot() }.getOrNull()
        val folders = visible?.liveFolders.orEmpty().sortedByDisplayName { it.name }
        // Live, visible items only, matching the folder list: a folder the user just emptied should
        // not still read as full because its items are in Trash, and counting a concealed item would
        // say a hidden entry is in there.
        //
        // Whole-subtree counts, as the item editor's picker shows them: counted direct, a folder
        // whose items all sat in a subfolder read "(0)". Each filed folder climbs its own ancestor
        // chain once, bounded by a visited set for a cyclic list on disk.
        val parentOf = folders.associate { it.id to it.parentId }
        val counts = HashMap<UUID, Int>()
        visible?.liveItems.orEmpty().mapNotNull { it.folderId }.groupingBy { it }.eachCount()
            .forEach { (folderId, n) ->
                val seen = HashSet<UUID>()
                var current: UUID? = folderId.takeIf { it in parentOf }
                while (current != null && seen.add(current)) {
                    counts[current] = (counts[current] ?: 0) + n
                    current = parentOf[current]
                }
            }
        val s = _state.value
        _state.value = s.copy(
            folders = folders,
            folderItemCounts = counts,
        )
    }

    /**
     * Save under the name chosen in the naming step ([LkNamingSheet]).
     *
     * One call rather than `onTitleChanged` then `save()`: those are two separate writes and
     * [save] re-reads `_state.value`, so anything landing between them would save the old title.
     */
    fun saveWithTitle(title: String) {
        _state.value = _state.value.copy(title = title)
        save()
    }
    fun showCapture() {
        captureCancelled = false
        _state.value = _state.value.copy(showCapture = true)
    }

    /**
     * See 15-3: this used to only hide the capture screen while [onCaptured]'s pipeline kept
     * running underneath it, so a Cancel tap during a slow capture was later silently overwritten
     * by the very result the user had just cancelled. Now it also cancels the in-flight job and
     * marks any result still on the way as stale.
     */
    fun dismissCapture() {
        captureCancelled = true
        captureJob?.cancel()
        _state.value = _state.value.copy(showCapture = false)
    }
    fun showViewer() {
        // Every open, not just a reprompt item's. The standalone viewer route challenges on every
        // load whatever the item says (FileItemViewerViewModel starts in Authenticating), and this
        // was the second door to the same bytes at a different price: Settings -> View reached a
        // non-reprompt file's content with nothing asked. A gate one route enforces and another
        // does not is a gate nobody can rely on, so the cheaper route is raised to the stricter one
        // rather than the other way round.
        _state.value = _state.value.copy(pendingViewerReveal = true)
    }
    fun dismissViewer() { _state.value = _state.value.copy(showViewer = false) }
    fun removeFile() {
        discardUncommittedSource()
        stagedSource = null
        _state.value = _state.value.copy(
            blobRef = null,
            filename = "",
            mime = "",
            sizeBytes = 0L,
            thumbnailRef = null,
            metadataCleanupState = MetadataCleanupState.NOT_APPLICABLE,
            metadataPresence = null,
            pendingDeleteUri = null,
            pendingDocumentDeleteUri = null,
            showViewer = false,
        )
    }

    fun onViewerRevealAuthenticated() {
        _state.update { it.copy(pendingViewerReveal = false, showViewer = true) }
    }

    /**
     * [message] says which of cancelled / denied / no-screen-lock happened.
     *
     * Silence was survivable while only reprompt items were gated — someone who had just set that
     * flag knew why the prompt appeared. Now that every open is challenged, a refused view with no
     * word at all is a View button that does nothing, which reads as a broken app rather than a
     * declined challenge.
     */
    fun onViewerRevealDenied(@StringRes message: Int? = null) {
        _state.update { it.copy(pendingViewerReveal = false, error = message) }
    }

    /** Rewrites an existing image in place, with no plaintext staging file and no schema change. */
    fun deleteAllMetadata() {
        val snapshot = _state.value
        val source = stagedSource ?: sourceForState(snapshot)
        if (source == null) return
        if (snapshot.isWorking || !snapshot.mime.startsWith("image/")) return
        _state.value = snapshot.copy(isWorking = true, error = null)
        viewModelScope.launch {
            val session = lockManager.sessionSnapshotOrNull()
            if (session == null) {
                _state.value = _state.value.copy(
                    isWorking = false,
                    error = R.string.files_edit_metadata_cleanup_failed,
                )
                return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    val original = fileBlobStore.decryptToBytes(source.blobRef, session.dek)
                    photoPickerImport.replaceStagedFile(source, original, session.dek).getOrThrow()
                }
                stagedSource = result
                // From `_state.value`, not the `snapshot` captured before the write — see 15-1.
                // importFromPicker, onCaptured and save() all re-read live state after their own
                // suspension for the same reason: an edit made while this multi-second IO ran (a
                // typed title, a folder chosen or created) must not be reverted by a stale copy.
                _state.value = stateWithSource(_state.value, result).copy(isWorking = false, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    metadataCleanupState = MetadataCleanupState.FAILED,
                    isWorking = false,
                    error = metadataCleanupMessage(e),
                )
            } finally {
                Zeroizer.wipe(session.dek)
            }
        }
    }

    private fun MutableStateFlow<FileItemEditUiState>.update(
        transform: (FileItemEditUiState) -> FileItemEditUiState,
    ) {
        this.value = transform(this.value)
    }

    /**
     * Stage a file the user picked, from the gallery or the document picker.
     *
     * Was `importFromGallery`, and was already source-agnostic when it had that name — it only
     * calls [PhotoPickerImport.stageFile], which classifies by the provider's MIME. Renamed when
     * the document route arrived (2026-09-08) so the name stops naming one of its two callers.
     */
    fun importFromPicker(uri: Uri, fromDocumentPicker: Boolean = false, generatedPhotoTitle: String = "") {
        if (_state.value.isWorking) return
        _state.value = _state.value.copy(isWorking = true, error = null)
        viewModelScope.launch {
            try {
                // A private copy of the DEK, not the manager's live array. The provider IPC and
                // file read ahead of the first HKDF derive can outlive the auto-lock grace timer,
                // and lock() zeroes the manager's own array underneath it — sealing the blob under
                // a key derived from an all-zero ikm. See [VaultLockManager.sessionSnapshotOrNull].
                val session = lockManager.sessionSnapshotOrNull()
                if (session == null) {
                    _state.value = _state.value.copy(error = MediaErrors.IMPORT_FAILED)
                    return@launch
                }
                try {
                    val result = photoPickerImport.stageFile(uri, session.dek, fromDocumentPicker).getOrThrow()
                    replaceStagedSource(result)
                    val current = _state.value
                    _state.value = stateWithSource(current, result).copy(
                        title = current.titleFor(result.filename, result.mime, generatedPhotoTitle),
                        error = null,
                    )
                } finally {
                    Zeroizer.wipe(session.dek)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = MediaErrors.IMPORT_FAILED)
            } finally {
                _state.value = _state.value.copy(isWorking = false)
            }
        }
    }

    fun onCaptured(jpegBytes: ByteArray) {
        if (_state.value.isWorking) return
        _state.value = _state.value.copy(isWorking = true, error = null)
        captureJob = viewModelScope.launch {
            try {
                // Same aliased-DEK hazard as importFromPicker: the EXIF strip and the thumbnail
                // downsample run before the first HKDF derive, and an auto-lock landing in that
                // window zeroes the manager's array — the blob would be sealed under an all-zero
                // key. Take a private copy and wipe it when done.
                val session = lockManager.sessionSnapshotOrNull()
                if (session == null) {
                    _state.value = _state.value.copy(error = MediaErrors.CAPTURE_FAILED, showCapture = false)
                    return@launch
                }
                try {
                    // One IO block for the whole pipeline. The strip was moved off the main thread in an
                    // earlier pass but the two encrypts and the downsample beside it were left on it,
                    // so a modern phone's 12 MP capture still froze the UI for seconds — the ANR the
                    // dispatch was supposed to fix, minus only the cheapest step. Every sibling path in
                    // this module (PhotoPickerImport, AttachmentPicker) encrypts on IO. The strip used
                    // to hang off the *import* branch rather than off the blob write, so the camera
                    // JPEG went in with TAG_MAKE/TAG_MODEL/TAG_SOFTWARE/TAG_DATETIME_ORIGINAL intact;
                    // capture is also the route the copy presents as the *more* private one, which
                    // made it the worst place to be keeping device metadata.
                    val result = photoPickerImport.stageCapturedFile(jpegBytes, session.dek).getOrThrow()
                    // The user may have tapped Cancel while the pipeline above was running — see
                    // 15-3. Its result is stale in that case: drop it rather than reviving the
                    // capture screen's outcome onto a form the user already returned to, and
                    // discard the encrypted bytes so they don't sit as an orphan.
                    if (captureCancelled) {
                        photoPickerImport.discardStagedFile(result)
                    } else {
                        replaceStagedSource(result)
                        val current = _state.value
                        _state.value = stateWithSource(current, result).copy(
                            title = current.titleFor(result.filename),
                            showCapture = false,
                            error = null,
                        )
                    }
                } finally {
                    Zeroizer.wipe(session.dek)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = MediaErrors.CAPTURE_FAILED, showCapture = false)
            } finally {
                _state.value = _state.value.copy(isWorking = false)
            }
        }
    }

    /**
     * The gallery original is gone — record that on the item that is already saved.
     *
     * Called after [save], not before, so the flag has to be written through to the vault rather
     * than merely set in state: the item was persisted a moment ago with `removedFromGallery =
     * false`, and leaving it there would have the file screen tell the user a second copy still
     * exists in their gallery when it does not. Fails soft — the bytes are safe either way, and a
     * lost flag is not worth failing a save the user has already been told succeeded.
     */
    fun markOriginalRemoved() {
        _state.value = _state.value.copy(removedFromGallery = true, pendingDeleteUri = null)
        val id = _state.value.itemId
        viewModelScope.launch {
            runCatching {
                if (repository.visibilitySnapshot().liveItems.none { it.id == id }) return@runCatching
                // Re-read under the vault lock: this write lands after the save, so a rename or a
                // reprompt change committed in between must not be reverted by a stale copy.
                repository.updateWith(id) { existing ->
                    val content = existing.content as? com.localkeep.core.model.File
                        ?: return@updateWith existing
                    existing.copy(content = content.copy(removedFromGallery = true))
                }
            }
        }
    }

    fun dismissPendingDelete() { _state.value = _state.value.copy(pendingDeleteUri = null) }

    /**
     * The original stays where it is: the user answered Keep, the offer is switched off, or
     * Android cannot be asked about this item. Clears the question, which lets the screen leave.
     */
    fun keepOriginal() {
        _state.value = _state.value.copy(
            originalPrompt = null,
            pendingDeleteUri = null,
            pendingDocumentDeleteUri = null,
        )
    }

    /** Android's own confirmation is opening for the gallery original; wait for its answer. */
    fun onGalleryDeleteLaunched() {
        _state.value = _state.value.copy(originalPrompt = null, originalDeleteInFlight = true)
    }

    /** Android's confirmation answered: [deleted] when the user allowed it. */
    fun onGalleryDeleteResult(deleted: Boolean) {
        if (deleted) markOriginalRemoved() else dismissPendingDelete()
        LkNotices.post(
            if (deleted) R.string.files_notice_original_deleted_gallery else R.string.files_notice_original_kept_gallery,
        )
        _state.value = _state.value.copy(originalDeleteInFlight = false)
    }

    /**
     * Deletes a document-picker original after the user confirmed it.
     *
     * The stored copy is authenticated first — decrypted end to end against its tag — so the one
     * file outside the vault is never deleted on the strength of a vault copy that cannot be read
     * back. There is no system dialog and no bin on this route, which is why the question before it
     * says "permanently".
     */
    fun deleteOriginalDocument() {
        val current = _state.value
        val uri = current.pendingDocumentDeleteUri
        val blobRef = current.blobRef
        if (uri == null || blobRef == null) {
            keepOriginal()
            return
        }
        _state.value = current.copy(originalPrompt = null, originalDeleteInFlight = true)
        viewModelScope.launch {
            val deleted = try {
                val session = lockManager.sessionSnapshotOrNull()
                val vaultCopyReads = session != null && try {
                    withContext(Dispatchers.IO) { fileBlobStore.authenticates(blobRef, session.dek) }
                } finally {
                    Zeroizer.wipe(session.dek)
                }
                vaultCopyReads && photoPickerImport.deleteOriginalDocument(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (deleted) markOriginalRemoved()
            if (deleted) {
                LkNotices.post(R.string.files_notice_original_deleted_document)
            } else {
                LkNotices.postRefusal(R.string.files_notice_original_delete_failed)
            }
            _state.value = _state.value.copy(pendingDocumentDeleteUri = null, originalDeleteInFlight = false)
        }
    }

    /** Removes encrypted refs that have never been committed to a vault item. */
    fun discardUncommittedSource() {
        if (!stagedSourceIsCommitted) {
            stagedSource?.let(photoPickerImport::discardStagedFile)
            stagedSource = null
        }
    }

    fun save() {
        val s = _state.value
        // Defense in depth (14b, §G): creating a vault File consumes a free-tier slot. The nav entry
        // checks first so the user is not dropped into a form they cannot save; re-check here so no
        // alternate path writes past the cap. Editing an existing File must not be blocked — it
        // occupies its slot already — so the check is scoped to new items.
        val used = AttachmentQuota.used(repository)
        if (s.isNew && AttachmentQuota.exceedsFreeLimit(used, entitlement.isPro.value)) {
            _state.value = s.copy(
                error = R.string.files_error_quota_exceeded,
                quotaMustRemove = AttachmentQuota.mustRemove(used),
            )
            return
        }
        val blobRef = s.blobRef
        if (blobRef == null) {
            _state.value = s.copy(error = R.string.files_edit_error_missing_file)
            return
        }
        if (s.isWorking) return
        _state.value = s.copy(isWorking = true, error = null)
        viewModelScope.launch {
            // Guarded like every other repository call in this class. This one was not, and the
            // failure it most invites is the ordinary one: the vault auto-locks while a file is
            // being named, `requireVault()` throws, the coroutine dies against the scope's handler,
            // and the screen shows neither an error nor a saved state — the Save button simply does
            // nothing. The user leaves believing the file is in the vault.
            try {
                // Save no longer strips. Metadata removal is an action the user takes — the
                // "Delete all metadata" button — not something a save does on their
                // behalf (2026-08-25, at the principal's direction). Stripping here had two costs
                // that were not worth the guarantee: it made a routine save fail outright whenever
                // cleanup could not run, and it removed data from the user's own photograph without
                // being asked. The screen now says plainly that the metadata may still be there.
                val current = _state.value
                // Asked *before* the commit below, and answered out loud.
                //
                // It used to sit between `commitStagedFile` and `updateWith` as a bare
                // `return@launch`, which was wrong twice over. It left a freshly written encrypted
                // blob behind that nothing would ever reference — reclaimed only by the on-unlock
                // sweep — and it refused the save in complete silence: the error was nulled a few
                // lines above, the notice and the navigation were skipped, and only the `finally`
                // ran, so the Save button re-enabled and nothing whatever appeared to have happened.
                //
                // Deliberately still the *narrow* check, not a wider one. `make-invisible-design.md`
                // names this screen as a caller that must not see concealed entries; letting a save
                // write through would release an edit to a record whose reveal grant has expired.
                // The refusal keeps the form — the message says so, and it is true: the edits are
                // still in `_state`, and leaving still raises the unsaved-changes dialog.
                if (!current.isNew && repository.visibilitySnapshot().liveItems.none { it.id == current.itemId }) {
                    _state.value = _state.value.copy(error = R.string.files_edit_error_item_gone)
                    return@launch
                }
                val source = stagedSource ?: sourceForState(current)
                    ?: throw IllegalStateException("missing staged source")
                // `commitStagedFile` builds the File from the staged blob, which knows nothing
                // about the note — that is form state, not import state — so it is written on
                // here, on both the add and the edit path below.
                val content = photoPickerImport.commitStagedFile(source, current.filename, current.folderId)
                    .getOrThrow()
                    .copy(notes = current.notes)
                // A pending folder name is visible work in progress; the prominent Save must not
                // silently discard what the small Create button would have kept.
                if (current.showNewFolderInput && current.newFolderName.isNotBlank()) {
                    if (!createFolderInternal()) {
                        _state.value = _state.value.copy(isWorking = false)
                        return@launch
                    }
                }
                // Read after the commit above, never from `s`: the folder the file belongs in may
                // have been created a line ago and `s` predates it.
                val folderId = _state.value.folderId
                val now = Instant.now()
                // Raised *before* the write below, not after it. `VaultLockManager.commit`'s
                // critical section has no suspension points, so once it starts it runs to
                // completion even if this scope is cancelled — and a Discard tap landing in that
                // window used to leave the flag false, so `discardUncommittedSource()` deleted the
                // blob and thumbnail of an item that had just been committed. The result was a
                // readable vault item whose `blobRef` pointed at nothing, with no way to clear it.
                // Ordered the way `AttachmentPickerViewModel.remove` documents for the same hazard:
                // the failure mode of the safe ordering is a blob nothing references, which
                // `BlobGarbageCollector` reclaims on the next unlock for free.
                stagedSourceIsCommitted = true
                // Inspected, not discarded — the same mapping ItemEditViewModel makes for the same
                // outcome. The folder can be deleted from the drawer while this editor is open, and
                // "filed nowhere" is the one thing the user cannot deduce from the list they land
                // back on: the file is in the vault either way, just not where they put it.
                val folderAbsent = if (current.isNew) {
                    repository.addItem(
                        VaultItem(
                            id = current.itemId, title = current.title.ifBlank { current.filename }, createdAt = now, updatedAt = now,
                            reprompt = current.reprompt, content = content, folderId = folderId,
                            tags = current.tags, favorite = current.favorite,
                        ),
                    ) == AddItemOutcome.FOLDER_ABSENT
                } else {
                    // `updateWith` nulls a dead folderId silently, and its KDoc justifies that by
                    // naming this screen as a caller carrying no folder intent — which stopped
                    // being true once the folder field was drawn on existing files too. It returns
                    // what it stored, so the intent it dropped is still answerable here without
                    // widening the repository's contract for every other caller of it.
                    val filed = repository.updateWith(current.itemId) { existing ->
                        existing.copy(
                            title = current.title.ifBlank { current.filename }, updatedAt = now,
                            reprompt = current.reprompt, content = content, folderId = folderId,
                            tags = current.tags, favorite = current.favorite,
                        )
                    }
                    folderId != null && filed.folderId == null
                }
                // Note: blobs orphaned by a replaced-before-save photo are reclaimed by the app-level
                // on-unlock sweep (LocalKeepApplication → BlobGarbageCollector), so no per-save sweep here.
                // From `_state.value`, not the `s` captured before the write: a folder created
                // during this very save (above) lives in the current state, and copying the stale
                // snapshot forward would drop it from the form and from the baseline a line later
                // — leaving the screen claiming no folder for a file that is filed in one.
                _state.value = _state.value.copy(
                    saved = true,
                    isWorking = false,
                    // The form must stop claiming a folder the vault does not have, or the file
                    // screen goes on naming one the file is not in.
                    folderId = if (folderAbsent) null else _state.value.folderId,
                    // In the same write as `saved`, so no frame exists in which the screen could
                    // read "saved, nothing to ask" and leave before the question is posed.
                    originalPrompt = originalPromptFor(_state.value),
                )
                // The file editor was the one commit path in the app that succeeded silently: the
                // item editor has posted "Item saved" through the shell for a while, and this screen
                // just navigated away, leaving the user to infer from the list that anything had
                // happened. Same words on purpose — a file IS an item, and two phrasings for one
                // outcome is how the same event starts reading as two different ones.
                //
                // "Item saved" is true but useless when the folder went: it is the confirmation the
                // user expected, and it hides the one fact they did not.
                // A lost folder is a degraded save, so it reads as a refusal (as the item editor's
                // twin does); the plain confirmation stays a success.
                if (folderAbsent) {
                    LkNotices.postRefusal(R.string.files_notice_folder_absent)
                } else {
                    LkNotices.post(R.string.files_notice_item_saved)
                }
                // The work is in the vault now, so backing out is no longer a loss.
                baseline = _state.value.editableOnly()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Split rather than collapsed: the generic sentence offers a retry, and a device
                // with no room left refuses this write identically every time it is pressed.
                _state.value = _state.value.copy(error = MediaErrors.saveFailed(e))
            } finally {
                _state.value = _state.value.copy(isWorking = false)
            }
        }
    }

    private fun replaceStagedSource(source: StagedFileSource) {
        val previous = stagedSource
        val previousWasCommitted = stagedSourceIsCommitted
        stagedSource = source
        stagedSourceIsCommitted = false
        if (previous != null && !previousWasCommitted) {
            photoPickerImport.discardStagedFile(previous)
        }
    }

    private fun sourceForState(state: FileItemEditUiState): StagedFileSource? {
        val blobRef = state.blobRef ?: return null
        return StagedFileSource(
            blobRef = blobRef,
            thumbnailRef = state.thumbnailRef,
            filename = state.filename,
            mime = state.mime,
            sizeBytes = state.sizeBytes,
            generationId = UUID.randomUUID(),
            metadataState = state.metadataCleanupState,
            sourceUri = null,
            capturedInApp = state.capturedInApp,
            removedFromGallery = state.removedFromGallery,
            pendingGalleryDeleteUri = state.pendingDeleteUri,
            hasGalleryOriginal = state.hasGalleryOriginal,
            metadataPresence = state.metadataPresence ?: MetadataPresence.UNKNOWN,
            deletableOriginalDocument = state.pendingDocumentDeleteUri,
        )
    }

    private fun stateWithSource(
        state: FileItemEditUiState,
        source: StagedFileSource,
    ): FileItemEditUiState = state.copy(
        blobRef = source.blobRef,
        thumbnailRef = source.thumbnailRef,
        filename = source.filename,
        mime = source.mime,
        sizeBytes = source.sizeBytes,
        capturedInApp = source.capturedInApp,
        removedFromGallery = source.removedFromGallery,
        hasGalleryOriginal = source.hasGalleryOriginal,
        metadataCleanupState = source.metadataState,
        pendingDeleteUri = source.pendingGalleryDeleteUri,
        pendingDocumentDeleteUri = source.deletableOriginalDocument,
        metadataPresence = source.metadataPresence,
    )
}

@Composable
fun FileItemEditScreen(
    viewModel: FileItemEditViewModel,
    itemId: String?,
    onManageAttachments: (String) -> Unit,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Saveable, both of them. The disclosure was plain `remember`, so a rotation mid-dialog dropped
    // it back to a button that had apparently done nothing — the same shape as the missing denial
    // branch below, and worth fixing in the same pass.
    var showCaptureDisclosure by rememberSaveable { mutableStateOf(false) }
    var cameraDenial by rememberSaveable(stateSaver = PermissionDenialSaver) {
        mutableStateOf<PermissionDenial?>(null)
    }
    val cameraDeniedSentence = stringResource(R.string.files_edit_camera_denied_sentence)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // The `else` this used to lack entirely — the identical gap as the TOTP QR scanner's, which
        // is why both now go through the same shared helper.
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

    // A fallback now, not the load path: the ViewModel reads the route argument at construction
    // (restoreRequestedItem), so by the time this composes the state already describes the file.
    // It stays for the one case construction cannot cover, a back stack restored while the vault
    // was locked.
    LaunchedEffect(itemId) {
        if (itemId != null && viewModel.needsInitialLoad(itemId)) viewModel.loadItem(itemId)
    }

    // The file stopped being visible while this screen held it — see the projection collector in
    // FileItemEditViewModel's `init`. Straight back, with no dialog and no explanation: the same
    // exit ItemDetailScreen takes, and for the same reason. Naming the cause on the way out would
    // announce that a hidden file exists to whoever is holding the phone, which is the one thing
    // concealment is for.
    LaunchedEffect(state.concealed) { if (state.concealed) onBack() }

    // Opening the file's content parks a challenge here (see FileItemEditViewModel.showViewer).
    // Same prompt the item editor uses for field reveals, and the same one the standalone viewer
    // route shows — the two doors to a file's bytes now cost the same.
    val viewerPromptTitle = stringResource(R.string.files_edit_viewer_reveal_title)
    val viewerPromptSubtitle = stringResource(R.string.files_edit_viewer_reveal_subtitle)
    LaunchedEffect(state.pendingViewerReveal) {
        if (!state.pendingViewerReveal) return@LaunchedEffect
        val activity = context as? FragmentActivity
        when {
            activity == null -> viewModel.onViewerRevealDenied(R.string.files_viewer_auth_failed)
            !RevealAuth.isAvailable(context) ->
                viewModel.onViewerRevealDenied(R.string.files_viewer_auth_unavailable)
            else -> when (
                val outcome = RevealAuth.authenticate(activity, viewerPromptTitle, viewerPromptSubtitle)
            ) {
                RevealAuth.RevealOutcome.Success -> viewModel.onViewerRevealAuthenticated()
                else -> viewModel.onViewerRevealDenied(viewerRevealMessage(outcome))
            }
        }
    }

    // What a photo the picker reports only by its media id is titled — see [suggestedTitle].
    // Resolved here at composition, not in the ViewModel (no display strings there), with the date
    // in the device's own locale so the title reads like the rest of the screen.
    val dateLocale = LocalConfiguration.current.locales[0]
    val generatedPhotoTitle = stringResource(
        R.string.files_edit_generated_photo_title,
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            .withLocale(dateLocale)
            .format(java.time.LocalDate.now()),
    )

    // Paired with the lock manager: choosing a photo backgrounds the app, and importing into a
    // vault that relocked underneath the picker fails at the moment the user thinks they succeeded.
    val galleryLauncher = rememberExternalPickerLauncher(
        contract = ActivityResultContracts.PickVisualMedia(),
        onPickerOpened = viewModel::onPickerOpened,
        onPickerClosed = viewModel::onPickerClosed,
    ) { uri ->
        if (uri != null) viewModel.importFromPicker(uri, generatedPhotoTitle = generatedPhotoTitle)
    }
    // Note: the AndroidX PickVisualMedia contract self-falls-back to the framework photo/document picker below API 33 —
    // no manual API-level gate needed here, but pre-33 devices see the system's regular picker UI, not the modern Photo Picker.

    // The document route. Until 2026-09-08 this screen offered the gallery and the camera only, so
    // a PDF, a spreadsheet or a text file could not be stored as a File item at all — while the
    // sibling attachment screen has shipped `OpenDocument()` the whole time
    // (AttachmentPicker.kt). Reported from a phone: "what if I want to save a PDF file??? Or
    // .xlsx file??"
    //
    // `*/*`, not a curated MIME list. Filtering to application/pdf plus the Office types and
    // text/* silently hides every file whose provider reports application/octet-stream, which
    // Drive and OneDrive routinely do — and that is the exact case `fileKindOf`'s extension
    // fallback exists to rescue. What makes `*/*` safe is the viewer's `else ->` branch: an
    // unrecognised kind draws a named, translated empty state, not a blank screen.
    //
    // No `takePersistableUriPermission`, here or anywhere in this repo. The transient grant is
    // enough by construction — `stageFile` streams the bytes into the encrypted blob inside this
    // callback — and a persistable grant would leave LocalKeep holding a standing handle on a file
    // *outside* the vault, listed in getPersistedUriPermissions() and surviving vault lock.
    val documentLauncher = rememberExternalPickerLauncher(
        contract = ActivityResultContracts.OpenDocument(),
        onPickerOpened = viewModel::onPickerOpened,
        onPickerClosed = viewModel::onPickerClosed,
    ) { uri ->
        if (uri != null) viewModel.importFromPicker(uri, fromDocumentPicker = true, generatedPhotoTitle = generatedPhotoTitle)
    }
    val deleteOriginalLauncher = rememberExternalPickerLauncher(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onPickerOpened = viewModel::onPickerOpened,
        onPickerClosed = viewModel::onPickerClosed,
    ) { result ->
        // The screen leaves once the outcome is recorded — see the post-save effect below.
        viewModel.onGalleryDeleteResult(deleted = result.resultCode == Activity.RESULT_OK)
    }

    // The question about the original — but ONLY once the item is committed.
    //
    // Android's delete request used to fire the moment an import finished, keyed on
    // `state.pendingDeleteUri`. At that point the encrypted blob existed and nothing referenced it:
    // the vault item is written by `save()`, which the user had not reached. So the app asked
    // permission to destroy the original, got it, and then — if the user backed out, or the
    // auto-lock fired, or the process was killed — left the staged blob unreferenced, where the
    // on-unlock sweep collects it. Both copies gone, on a screen whose entire promise is that the
    // vault is now the safe place for it. Committing first makes the ordering match the guarantee.
    //
    // Since 2026-10-09 the app asks first, in its own words ([originalPromptFor]), and Android's
    // dialog only confirms an answer already given. `save()` sets the question in the same write as
    // `saved`; this leaves the screen only once there is nothing left to ask or wait for.
    //
    // Read once per composition of this screen rather than observed: the switch lives in Settings,
    // which cannot be open at the same time as this editor.
    val offerOriginals = remember { LocalKeepSettings.isOriginalDeleteOffered(context) }
    // Android's request for a gallery original, built *before* the question is shown, so the app
    // never offers a delete that cannot be carried out — a cloud photo or another profile's has no
    // MediaStore row here, and `resolveMediaStoreUri` answers null for it.
    var gallerySender by remember { mutableStateOf<IntentSender?>(null) }
    LaunchedEffect(state.saved, state.originalPrompt, state.originalDeleteInFlight) {
        if (!state.saved) return@LaunchedEffect
        val prompt = state.originalPrompt
        when {
            prompt == null -> if (!state.originalDeleteInFlight) onSaved()
            !offerOriginals -> viewModel.keepOriginal()
            prompt.kind == OriginalKind.DOCUMENT -> Unit
            else -> {
                val uri = state.pendingDeleteUri
                gallerySender = if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        MediaDeleter.resolveMediaStoreUri(context, uri, state.mime)
                            ?.let { MediaDeleter.createDeleteRequest(context, listOf(it)) }
                    }.getOrNull()
                } else {
                    null
                }
                if (gallerySender == null) viewModel.keepOriginal()
            }
        }
    }

    // The three source actions, hoisted so the empty-state chooser sheet (below) and the row of
    // buttons that already ran them can share one definition apiece rather than the sheet
    // reimplementing what "Capture"'s permission dance already does.
    val onGallerySelected = {
        galleryLauncher(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    val onCaptureSelected = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.showCapture() else showCaptureDisclosure = true
    }
    val onDocumentsSelected = { documentLauncher(arrayOf("*/*")) }

    if (state.showCapture) {
        // Both sub-screens replace the form by returning early, so without their own handler the
        // system Back gesture went past them to the nav host and popped the whole editor —
        // closing the camera and binning the half-filled form in one press.
        BackHandler(enabled = true) { viewModel.dismissCapture() }
        CaptureToVaultScreen(
            onCaptured = { jpeg -> viewModel.onCaptured(jpeg) },
            onCancel = { viewModel.dismissCapture() },
            isProcessing = state.isWorking,
        )
        return
    }
    if (state.showViewer && state.blobRef != null) {
        val sessionKey = viewModel.sessionKeyOrNull()
        if (sessionKey != null) {
            val att = Attachment(
                id = state.itemId,
                filename = state.filename.ifBlank { "file" },
                mime = state.mime,
                sizeBytes = state.sizeBytes,
                blobRef = state.blobRef!!,
            )
            val folderPath = state.folderId?.let { fid ->
                FolderTree.pathOf(state.folders, fid)?.joinToString(" / ") { it.name }
            }
            BackHandler(enabled = true) { viewModel.dismissViewer() }
            SecureMediaViewer(
                attachments = listOf(att),
                startId = att.id.toString(),
                sessionKey = sessionKey,
                fileBlobStore = viewModel.fileBlobStore,
                folderPath = folderPath,
                onClose = { viewModel.dismissViewer() },
                onDelete = { viewModel.removeFile() },
            )
            return
        }
    }

    val c = LkTheme.colors

    // Unsaved-change protection (§5.1), matching ItemEditScreen. This screen had none: a captured
    // photo, a typed title and the metadata toggles all went in one press, and the encrypted blob
    // that had already been written was left for the on-unlock sweep.
    // rememberSaveable, not remember: a rotation while the prompt is up must not dismiss the one
    // guard standing between the user and losing the import.
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    // Saveable: a rotation while choosing a folder should not drop the user back into the form.
    var showFolderPicker by rememberSaveable { mutableStateOf(false) }
    // The "Folder, tags and more" disclosure, closed on arrival like the item editor's and saveable
    // for the same reason as its neighbours: a rotation must not fold away a section the user
    // opened to tag something.
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    // The empty-state plate's chooser (finding files_edit_empty_icon_tap_label, 2026-09-15). Saveable
    // for the same reason as the pair above: a rotation with the sheet up should not drop it.
    var showSourceChooser by rememberSaveable { mutableStateOf(false) }
    val formScroll = rememberScrollState()
    val leave = {
        // While an import or capture is in flight, Back would silently strand the encrypt:
        // `isDirty()` is false mid-import, so the guard below let the user leave and the
        // blocking encrypt kept running to completion anyway, discarding its result. The
        // in-flight state is the dirty state.
        if (state.isWorking || viewModel.isDirty()) confirmDiscard = true else onBack()
    }
    // With the chooser up, back closes the chooser; the discard guard only applies to leaving the
    // editor itself. Disabled rather than left to dispatcher ordering — "which BackHandler wins" is
    // not something to leave implicit on the key standing between the user and losing an import.
    BackHandler(enabled = !showFolderPicker) { leave() }

    // Composed instead of the editor rather than over it: an overlay leaks taps through its gaps.
    // The form's scroll position is hoisted above this branch so returning lands where the user
    // left off.
    if (showFolderPicker) {
        FileFolderPicker(state, viewModel) { showFolderPicker = false }
        return
    }

    if (confirmDiscard) {
        LkDialog(
            onDismiss = { confirmDiscard = false },
            title = stringResource(fileDiscardDialogCopy(state.isNew).first),
            body = stringResource(fileDiscardDialogCopy(state.isNew).second),
            icon = LkIcons.Warning,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.files_edit_discard_confirm),
                onClick = {
                    // The flag stays set on purpose — see the twin of this dialog in
                    // ItemEditScreen. Closing the dialog and popping the screen are two window
                    // teardowns of one intent, and as two they play in sequence: the abandoned
                    // form is redrawn undimmed and complete in between. The pop takes the dialog
                    // with it. `checkNoDismissThenNavigate` fails the build on the other shape.
                    viewModel.discardUncommittedSource()
                    onBack()
                },
                emphasis = LkActionEmphasis.Destructive,
            ),
            dismissLabel = stringResource(R.string.files_edit_keep_editing),
        )
    }

    // The naming step for a new file. `rememberSaveable`, because it holds a decision the user is
    // part-way through and a rotation that closed it would drop them back on the form with no sign
    // of why. The title itself is local to the sheet and seeded from the filename — which is what
    // `save()` has always fallen back to, made visible and editable before the write instead of
    // applied silently after it.
    var showNamingSheet by rememberSaveable { mutableStateOf(false) }
    // A save failure (quota exceeded, missing file) has no error slot inside the sheet itself —
    // it renders in the form below (§ errorReveal), which the still-open modal sheet's scrim
    // hides. Close the sheet so the already-rendered error becomes visible instead of silent.
    LaunchedEffect(state.error) { if (namingSheetShouldCloseOnError(state.error)) showNamingSheet = false }
    if (namingSheetVisible(showNamingSheet, state.originalPrompt)) {
        var typed by rememberSaveable(state.itemId) {
            mutableStateOf(state.title.ifBlank { state.filename })
        }
        LkNamingSheet(
            title = stringResource(R.string.files_edit_naming_title),
            subtitle = stringResource(R.string.files_edit_naming_subtitle),
            fieldLabel = stringResource(R.string.files_edit_field_title),
            confirmLabel = stringResource(R.string.files_edit_naming_confirm),
            value = typed,
            onValueChange = { typed = it },
            placeholder = state.filename.ifBlank { stringResource(R.string.files_edit_title_placeholder) },
            onDismiss = { showNamingSheet = false },
            onConfirm = { viewModel.saveWithTitle(typed.trim()) },
            enabled = typed.isNotBlank() && !state.isWorking,
        )
    }

    // Save, defined once because it has two homes: pinned over the keyboard in the scaffold's action
    // bar, or — while the keyboard leaves the window too little room (see [LocalLkChromeUnpinned]) —
    // at the end of the scroll, so the title field being typed into gets everything above the keys.
    val saveBar: @Composable () -> Unit = SaveBar@{
        // Not drawn on a new file with nothing in it. A disabled Save beneath the empty state's
        // own "Add a file" was two commit-shaped plates, one of which could not be pressed —
        // the shape reported on the Location screen the same day (2026-10-09). An existing
        // file whose content was removed in the viewer keeps it, disabled and labelled with
        // the reason, because there the form holds edits that Save is still the way to keep.
        if (state.isNew && state.blobRef == null) return@SaveBar
        val saveLabel = stringResource(R.string.files_edit_save)
        LkPrimaryButton(
            text = if (state.isWorking) stringResource(R.string.files_encrypting_ellipsis) else saveLabel,
            // A new file's title is on the form now, filled in from the filename at import, so
            // Save writes. The naming step is kept for the one case it still answers: a title
            // the user cleared. The `enabled` line below guarantees there is a file to name.
            onClick = { if (state.isNew && state.title.isBlank()) showNamingSheet = true else viewModel.save() },
            enabled = state.blobRef != null && !state.isWorking,
            // A button whose text becomes a progress word is nameless to a screen reader
            // unless it says so — same treatment as ItemEditScreen's Save.
            //
            // The blocked case says why, the way the unlock button does ("Unlock, enter your
            // master password first"). Without it, Save on an empty file form is a control
            // that reads as available and does nothing when pressed — the 2026-07-29 device
            // pass mistook it for the below-the-fold error defect, which is exactly the
            // reading a first-time user would take.
            modifier = Modifier
                .labelled(
                    when {
                        state.isWorking -> stringResource(R.string.files_edit_save_label_saving)
                        state.blobRef == null -> stringResource(R.string.files_edit_save_label_blocked)
                        else -> saveLabel
                    },
                )
                .lkTag("file-settings-save"),
        )
    }

    LkActionScaffold(
        accent = c.jade,
        auraAlpha = 0.07f,
        // Centred, as the comp grounds every item flow. Was 0.35.
        auraBias = 0.5f,
        // Empty while unpinned on purpose rather than skipped: the empty bar's `imePadding()` is what
        // reserves the keyboard's height for the scroll region above it.
        action = { if (!LocalLkChromeUnpinned.current) saveBar() },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(formScroll)
                .padding(horizontal = LkFrame.screenHorizontal)
                .padding(top = LkFrame.screenTop, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LkScreenHeader(
                title = if (state.isNew) stringResource(R.string.files_edit_title_new) else stringResource(R.string.files_edit_title_existing),
                subtitle = stringResource(R.string.files_edit_subtitle),
                modifier = Modifier.lkEnter(),
                trailing = {
                    LkIconButton(icon = LkIcons.ChevronLeft, label = stringResource(R.string.files_back), onClick = leave, plateAlignment = Alignment.CenterEnd)
                },
            )

            // The order a person reads this screen in, reported from a phone 2026-10-09 as "so much
            // that my head hurts": what it is called, the file itself, then where it is filed. The
            // file used to sit under "Folder, tags and more" and above ten controls of equal weight
            // — three of them *From gallery / Capture / From documents*, which on an existing file
            // silently replaced it. Filing goes last, as it does in every other item editor.
            //
            // The title shows on a new file too, once there is a file: the name is the filename the
            // import just reported, and asking for it afterwards in a sheet only repeated it (the
            // "Name it" step kept "91.png" for every screenshot). Before a pick there is nothing to
            // name, which is why the empty state still has no title field.
            if (!state.isNew || state.blobRef != null) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val titleLabel = stringResource(R.string.files_edit_field_title)
                    LkFieldLabel(titleLabel)
                    LkTextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChanged,
                        label = titleLabel,
                        placeholder = state.filename.ifBlank { stringResource(R.string.files_edit_title_placeholder) },
                        modifier = Modifier.lkTag("file-settings-title"),
                    )
                }
            }

            if (state.blobRef != null) {
                FileCard(
                    state = state,
                    onView = viewModel::showViewer,
                    onDeleteMetadata = viewModel::deleteAllMetadata,
                    onReplace = { showSourceChooser = true },
                )
                if (legacyGalleryDeleteNoteVisible(
                        Build.VERSION.SDK_INT,
                        state.capturedInApp,
                        state.hasGalleryOriginal,
                        originalDeletable = state.pendingDocumentDeleteUri != null,
                    )
                ) {
                    // A note, not an error. This is an unconditional statement of what Android 9
                    // and 10 cannot do — minSdk is 28, so on those releases it showed on every
                    // gallery-imported image — and LkInlineError paints coral fill, ring, text and a
                    // Warning icon. Nothing has failed; the user is being told to do one thing by
                    // hand. Same colour law as the caveat above, opposite direction.
                    LkInlineNote(
                        stringResource(R.string.files_edit_legacy_delete_note),
                        accent = c.amber,
                        icon = LkIcons.Info,
                    )
                }
                // On a new (unsaved) item the id is not in the vault yet, and the attachment
                // picker silently drops anything added through it — every attach ran its
                // encrypt-and-persist spinner, wrote an orphaned blob, and never set an error
                // or a row. The picker is only coherent once the item exists, so the entry is
                // hidden until the editor has been saved at least once. (The sibling item
                // editor guards the same button with `if (!state.isNew)`.)
                //
                // A navigation row, not a second full-width button under View: it leads somewhere
                // else, and drawn as a button it was the same weight as the file's own actions.
                // Only where there is something to manage — see [manageAttachmentsRowVisible].
                if (manageAttachmentsRowVisible(state.isNew, state.attachmentCount)) {
                    FileNavRow(
                        label = stringResource(R.string.files_edit_manage_attachments),
                        icon = LkIcons.Layers,
                        onClick = { onManageAttachments(state.itemId.toString()) },
                        modifier = Modifier.lkTag("file-settings-attachments"),
                    )
                }
            } else {
                LkEmptyState(
                    icon = LkIcons.Attachment,
                    title = stringResource(fileEmptyStateCopy(state.isWorking).first),
                    body = stringResource(fileEmptyStateCopy(state.isWorking).second),
                    // The plate is the biggest thing on the screen and reads as the way in; it did
                    // nothing, and the work was done by a smaller button below (2026-07-29). It used
                    // to run the gallery import only — the same thing "From gallery" runs — though
                    // three sources exist here (gallery, camera, documents). Opens the same chooser
                    // now, so the plate's one action matches what the label promises instead of
                    // picking one of the three sources on the label's behalf.
                    onIconTap = { showSourceChooser = true },
                    iconTapLabel = stringResource(R.string.files_edit_empty_icon_tap_label),
                    // The one visible way in now that the three source buttons are gone: the plate
                    // is a shortcut to the same chooser, never the only route.
                    action = {
                        LkPrimaryButton(
                            text = stringResource(R.string.files_edit_empty_icon_tap_label),
                            icon = LkIcons.Plus,
                            onClick = { showSourceChooser = true },
                            enabled = !state.isWorking,
                            modifier = Modifier.lkTag("file-settings-add"),
                        )
                    },
                )
            }

            // New and existing alike: a file being imported is exactly when the user knows where it
            // belongs, and `Item Flows.dc.html` puts filing on the form of an item that has no name
            // yet. Last, after the file, which is where every other item editor puts it.
            FileMoreSection(
                state = state,
                viewModel = viewModel,
                open = moreOpen,
                onOpenChange = { moreOpen = it },
                onChangeFolder = { showFolderPicker = true },
            )

            cameraDenial?.let { denial ->
                LkInlineError(
                    message = denial.message,
                    modifier = Modifier.announce(denial.message),
                    actionLabel = denial.settingsActionLabel,
                    onAction = denial.settingsActionLabel?.let { { context.openAppSettings() } },
                )
            }

            // Scrolled to, not merely rendered — §2 "a refused submission is visible from the
            // control that refused it". Save is a fixed bottom bar and this is the last thing in a
            // scrolling column, so "Import or capture a file first" sat below the fold: pressing
            // Save on an empty form changed nothing the user could see. Found on the 2026-07-29
            // device pass, in the screen the fix wave for the identical ItemEditScreen bug had
            // not touched.
            val errorReveal = remember { BringIntoViewRequester() }
            LaunchedEffect(state.error) { if (state.error != null) errorReveal.bringIntoView() }
            state.error?.let {
                val message = refusalText(it, state.quotaMustRemove, state.canPurchase)
                LkInlineError(message, Modifier.bringIntoViewRequester(errorReveal).announce(message))
            }

            if (LocalLkChromeUnpinned.current) saveBar()
        }
    }

    // Camera consent is a dialog, not prose that appears further down a form the user is already
    // scrolling — the same treatment biometric enrolment gets in Settings.
    if (showCaptureDisclosure) {
        LkDialog(
            onDismiss = { showCaptureDisclosure = false },
            title = stringResource(R.string.files_edit_camera_dialog_title),
            body = stringResource(R.string.files_edit_camera_dialog_body),
            icon = LkIcons.Camera,
            confirm = LkDialogAction(
                label = stringResource(R.string.files_edit_camera_dialog_confirm),
                onClick = {
                    showCaptureDisclosure = false
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
            ),
            dismissLabel = stringResource(R.string.files_edit_camera_dialog_dismiss),
        )
    }

    // Asked after a save that left an original outside the vault — see [originalPromptFor]. Drawn
    // only once the answer can be carried out: a gallery item needs the request Android will
    // confirm, built by the post-save effect above.
    val prompt = state.originalPrompt
    if (state.saved && prompt != null && offerOriginals &&
        (prompt.kind == OriginalKind.DOCUMENT || gallerySender != null)
    ) {
        OriginalDeleteDialog(
            prompt = prompt,
            onKeep = viewModel::keepOriginal,
            onKeepAndStopAsking = {
                LocalKeepSettings.setOriginalDeleteOffered(context, false)
                viewModel.keepOriginal()
            },
            onDelete = {
                if (prompt.kind == OriginalKind.DOCUMENT) {
                    viewModel.deleteOriginalDocument()
                } else {
                    gallerySender?.let { sender ->
                        viewModel.onGalleryDeleteLaunched()
                        deleteOriginalLauncher(IntentSenderRequest.Builder(sender).build())
                    }
                }
            },
        )
    }

    // The empty-state plate's chooser. A `ModalBottomSheet` is its own window (see LkSheet's KDoc),
    // so it needs no `BackHandler` guard here — its own back dispatch dismisses it before the
    // `leave()` handler above ever sees the press, exactly like `confirmDiscard` and
    // `showCaptureDisclosure` above it.
    if (showSourceChooser) {
        FileSourceChooserSheet(
            replacing = state.blobRef != null,
            onGallery = { showSourceChooser = false; onGallerySelected() },
            onCapture = { showSourceChooser = false; onCaptureSelected() },
            onDocuments = { showSourceChooser = false; onDocumentsSelected() },
            onDismiss = { showSourceChooser = false },
        )
    }
}

/**
 * The closed "Folder, tags and more" row's label and summary. Split out so its wrapping can be
 * measured without a view model.
 */
@Composable
internal fun RowScope.FileMoreHeadlineText(label: String, summary: String) {
    val c = LkTheme.colors
    // A flow row, as the item editor's headline is (`MoreSectionHeadline`): with the label in
    // `weight(1f)` and the summary measured first, "Folder, tags and more" was set one word
    // to a line in a sliver beside "none set" at 308dp / font 2.0 (final sweep, 2026-10-28).
    // The summary now stays beside the label where both fit and drops under it where not.
    FlowRow(
        Modifier.weight(1f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LkText.navLabel, color = c.textSecondary, modifier = Modifier.padding(end = LkSpace.sm))
        // Max intrinsic: a flow row breaks on a child's minimum width, which for text is its
        // longest word, and would keep "none set" beside the label squeezed to an ellipsis.
        Text(summary, style = LkText.monoCaption, color = c.textFaint, modifier = Modifier.width(IntrinsicSize.Max))
    }
}

/**
 * Folder, tags and the favourite flag, behind one disclosure — the File editor's half of the
 * "Folder, tags and more" row every other item type already draws (4C-11, 2026-09-20).
 *
 * **Why the File editor did not have one.** Filing arrived here on 2026-08-19 as a bare
 * [LkFolderField], because a folder was the only one of these a File could carry at the time. The
 * result was the one item type in the vault with no route to a tag at all — not from this screen
 * and not from the row menu — while the vault list's search hint promises it searches "tags,
 * folders". A user who had organised a vault by tag could not reach their own scans with it.
 *
 * **A copy of [com.localkeep.feature.itemedit] `MoreSection`, not a shared control.** That one is
 * written against `ItemEditUiState` and its ViewModel; lifting it into `core:ui` would mean handing
 * a design-system module a vault-item state class, which is the boundary that file's own KDoc draws
 * around the folder picker ("`core:ui` knows nothing about vaults"). The visible contract — header
 * row, live summary, chevron, expanded card — matches it row for row so that the two screens do not
 * teach different habits.
 *
 * **Custom fields and the reprompt toggle are deliberately absent.** Neither has ever been on this
 * screen: a File's `reprompt` is carried through a save but never offered, and custom fields belong
 * to the item editor's own linked-field machinery. Adding either here would be a new capability,
 * not the missing route this fixes.
 */
@Composable
private fun FileMoreSection(
    state: FileItemEditUiState,
    viewModel: FileItemEditViewModel,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onChangeFolder: () -> Unit,
) {
    val c = LkTheme.colors
    val chevron by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = lkTween(LkMotion.Crossfade, easing = LkMotion.Settle),
        label = "file-more-chevron",
    )

    // The summary is what makes collapsing safe: a disclosure that hides its own state is how a
    // user loses a setting they made. Same three facts, same order, same separator as the item
    // editor's.
    val summary = buildList {
        state.folders.firstOrNull { it.id == state.folderId }?.let { add(it.name) }
        if (state.tags.isNotEmpty()) {
            add(pluralStringResource(R.plurals.files_edit_more_summary_tags, state.tags.size, state.tags.size))
        }
        if (state.notes.isNotBlank()) add(stringResource(R.string.files_edit_more_summary_note))
        if (state.favorite) add(stringResource(R.string.files_edit_more_summary_favourite))
    }.joinToString(" · ").ifEmpty { stringResource(R.string.files_edit_more_summary_none) }

    val label = stringResource(R.string.files_edit_more_label)
    val toggleLabel = stringResource(
        if (open) R.string.files_edit_more_collapse_a11y else R.string.files_edit_more_expand_a11y,
    )
    // A state word, not a second name — `onClickLabel` is the "double tap to…" hint and says
    // nothing about whether the section under it is already showing. Same treatment, and the same
    // reason, as the item editor's row.
    val stateWord = stringResource(
        if (open) R.string.files_edit_more_state_expanded else R.string.files_edit_more_state_collapsed,
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LkShape.xl)
                .lkSurface(LkShape.xl, Color.Transparent, c.ring)
                .minTouchTarget()
                .semantics { stateDescription = stateWord }
                .clickable(onClickLabel = toggleLabel) { onOpenChange(!open) }
                .padding(horizontal = LkSpace.md, vertical = LkSpace.sm)
                .lkTag("file-settings-more"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            FileMoreHeadlineText(label, summary)
            Icon(
                LkIcons.ChevronRight,
                null,
                tint = c.textFaint,
                modifier = Modifier.size(18.dp).rotate(chevron),
            )
        }
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = LkSpace.xs)
                    .lkSurface(LkShape.card, c.surface, c.ring)
                    .padding(LkSpace.md),
                // Safe only because each child below is exactly *one* composable: LkFolderField,
                // FileTagEditor (which wraps itself in a Column) and LkGroup. A composable emitting
                // several children into a `spacedBy` Column gets its own internal gaps spaced too.
                verticalArrangement = Arrangement.spacedBy(LkSpace.md),
            ) {
                // Where it goes, what it is tagged, what else is true of it — the item editor's
                // order, filing before options.
                LkFolderField(
                    selectedName = state.folders.firstOrNull { it.id == state.folderId }?.name,
                    onChange = onChangeFolder,
                )
                FileTagEditor(state, viewModel)
                // One composable emitting one child, which is what keeps it safe inside the
                // `spacedBy` Column above: LkFormField is itself a Column of label + well.
                //
                // `KeyboardType.Password` for the reason `SecretFreeTextWellsSuppressImeLearningTest`
                // gives for every other notes well in the app: a notes field is where the things
                // with no dedicated box get typed, and an IME that autocorrects and *learns* the
                // value is the wrong keyboard for it. It changes no masking — that is
                // `visualTransformation`, which this does not set.
                LkFormField(
                    value = state.notes,
                    onValueChange = viewModel::onNotesChanged,
                    label = stringResource(R.string.files_edit_notes_label),
                    placeholder = stringResource(R.string.files_edit_notes_placeholder),
                    modifier = Modifier.fillMaxWidth().lkTag("file-settings-notes"),
                    singleLine = false,
                    keyboardType = KeyboardType.Password,
                    minHeight = 86.dp,
                    maxHeight = 220.dp,
                )
                LkGroup {
                    LkCheckRow(
                        label = stringResource(R.string.files_edit_favourite_checkbox_label),
                        checked = state.favorite,
                        onToggle = { viewModel.onFavoriteChanged(!state.favorite) },
                        first = true,
                    )
                }
            }
        }
    }
}

/** The tag chips and the add-tag box, drawn the way the item editor draws them. */
@Composable
private fun FileTagEditor(state: FileItemEditUiState, viewModel: FileItemEditViewModel) {
    val c = LkTheme.colors
    val addTagLabel = stringResource(R.string.files_edit_tag_add_label)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        LkFieldLabel(stringResource(R.string.files_edit_tags_label))
        // Chips before the input, the item editor's order: what this file already carries is what
        // you check before deciding whether to add another.
        if (state.tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.tags.forEach { tag ->
                    LkChip(
                        text = tag,
                        selected = true,
                        icon = LkIcons.Close,
                        // The chip *is* the remove control; the label says so, because a chip that
                        // looks selected but deletes on tap is otherwise a trap.
                        onClick = { viewModel.removeTag(tag) },
                        modifier = Modifier.labelled(stringResource(R.string.files_edit_tag_remove_a11y, tag)),
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            LkTextField(
                value = state.tagInput,
                onValueChange = viewModel::onTagInputChanged,
                label = addTagLabel,
                placeholder = stringResource(R.string.files_edit_tag_add_placeholder),
                shape = LkShape.xl,
                modifier = Modifier.weight(1f).lkTag("file-settings-tag-input"),
                // Enter adds the tag, as it does in the item editor and the bulk-tag dialog. Two
                // places you type a tag behaving differently is how the habit one teaches fails in
                // the other.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.addTag() }),
            )
            LkIconButton(
                icon = LkIcons.Plus,
                label = addTagLabel,
                onClick = viewModel::addTag,
                tint = c.jade,
                shape = LkShape.sm,
            )
        }
    }
}

/**
 * The empty-state plate's three-way chooser (gallery / capture / documents).
 *
 * Exists because the plate used to run the gallery import unconditionally — the same thing "From
 * gallery" runs — though the form offers three sources. Reuses the exact row of buttons' own
 * actions (passed in by the caller), rather than a fourth copy of the permission dance "Capture"
 * already does.
 */
@Composable
internal fun FileSourceChooserSheet(
    replacing: Boolean,
    onGallery: () -> Unit,
    onCapture: () -> Unit,
    onDocuments: () -> Unit,
    onDismiss: () -> Unit,
) {
    // [replacing] says out loud what choosing here does to the file already on the form. The three
    // buttons this sheet replaced were labelled *From gallery / Capture / From documents* on an
    // existing file too, and silently swapped it — read as "add another" (2026-10-09).
    LkSheet(
        onDismiss = onDismiss,
        title = stringResource(
            if (replacing) R.string.files_edit_source_sheet_title_replace else R.string.files_edit_source_sheet_title,
        ),
        subtitle = if (replacing) stringResource(R.string.files_edit_source_sheet_replace_body) else null,
    ) {
        LkGroup {
            FileSourceRow(
                first = true,
                icon = LkIcons.Gallery,
                label = stringResource(R.string.files_edit_from_gallery),
                onClick = onGallery,
            )
            FileSourceRow(
                first = false,
                icon = LkIcons.Camera,
                label = stringResource(R.string.files_capture_action),
                onClick = onCapture,
            )
            FileSourceRow(
                first = false,
                // Matches the "From documents" button's own icon choice: LkIcons binds `Camera` to
                // taking a picture and `Gallery` to the photo gallery, so neither is free to mean
                // "browse documents".
                icon = LkIcons.Attachment,
                label = stringResource(R.string.files_edit_from_documents),
                onClick = onDocuments,
            )
        }
        Spacer(Modifier.height(LkSpace.sm))
    }
}

/**
 * The file itself: what kind it is and how big, the way into the viewer, and what can be done to
 * its contents. One card, so everything that acts on the file sits with the file.
 *
 * The whole header row opens the viewer, rather than a full-width View button under it. The tile
 * stays a kind glyph, not a picture: every route to a file's content is behind the reveal
 * challenge ([FileItemEditViewModel.showViewer]), and a thumbnail here would be a second, ungated
 * one.
 *
 * The filename is shown only when it says something the title above does not. On a new file the
 * two are the same string, and printing it twice was part of what made the screen read as
 * cluttered.
 */
@Composable
private fun FileCard(
    state: FileItemEditUiState,
    onView: () -> Unit,
    onDeleteMetadata: () -> Unit,
    onReplace: () -> Unit,
) {
    val c = LkTheme.colors
    val viewLabel = stringResource(R.string.files_edit_view)
    LkCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(LkShape.md)
                .minTouchTarget()
                // Disabled while an import, a capture or a metadata cleanup is running. The viewer's
                // own delete calls `removeFile()`, and every one of those pipelines ends by writing
                // its result over the form — so a delete taken mid-import cleared the file, the
                // import landed seconds later, and the record came back as a different file than
                // the user had deleted or intended to keep, with nothing saying so.
                .clickable(enabled = !state.isWorking, onClickLabel = viewLabel, onClick = onView)
                .labelled(viewLabel)
                .lkTag("file-settings-view"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .lkSurface(LkShape.md, c.jade.copy(alpha = 0.08f), c.jade.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    fileKindIcon(state.mime),
                    contentDescription = null,
                    tint = c.jade,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${fileTypeLabel(state.filename, state.mime)} · ${state.sizeBytes.readableSize()}",
                    style = LkText.rowTitle,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.filename.isNotBlank() && state.filename != state.title.trim()) {
                    Text(
                        state.filename,
                        style = LkText.subtitle,
                        color = c.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(LkIcons.ChevronRight, contentDescription = null, tint = c.textFaint, modifier = Modifier.size(18.dp))
        }

        when (fileMetadataNoteKindFor(state.mime)) {
            FileMetadataNoteKind.IMAGE -> when (metadataNoteFor(state.metadataCleanupState, state.metadataPresence)) {
                MetadataNote.NONE -> Unit
                MetadataNote.REMOVED -> {
                    Spacer(Modifier.height(LkSpace.md))
                    LkInlineNote(
                        stringResource(R.string.files_edit_metadata_removed),
                        modifier = Modifier.lkTag("file-settings-metadata-removed"),
                    )
                }
                // Amber and Info, not the defaults: LkInlineNote defaults to jade + Check, the
                // all-clear treatment. LkColors binds amber to "worth looking at, nothing is wrong",
                // which is exactly this; coral would claim a failure.
                MetadataNote.FOUND, MetadataNote.MAYBE -> {
                    Spacer(Modifier.height(LkSpace.md))
                    LkInlineNote(
                        stringResource(
                            if (metadataNoteFor(state.metadataCleanupState, state.metadataPresence) == MetadataNote.FOUND) {
                                R.string.files_edit_metadata_found_note
                            } else {
                                R.string.files_edit_metadata_present_note
                            },
                        ),
                        modifier = Modifier.lkTag("file-settings-metadata-pending"),
                        accent = c.amber,
                        icon = LkIcons.Info,
                    )
                    Spacer(Modifier.height(LkSpace.sm))
                    // Not the Trash glyph: Trash is how this app draws deleting the *file*, and on
                    // a phone the button read as exactly that (2026-10-09).
                    LkSecondaryButton(
                        text = stringResource(R.string.files_edit_delete_metadata),
                        icon = LkIcons.Erase,
                        onClick = onDeleteMetadata,
                        enabled = !state.isWorking,
                        modifier = Modifier.lkTag("file-settings-delete-metadata"),
                    )
                }
            }
            // Until 2026-09-09 a video imported here used to carry no metadata note
            // at all. `PhotoPickerImport.stageFile` only strips for images; a video's bytes are
            // encrypted as picked and there is no re-mux path to remove container-level location
            // or camera tags without risking a corrupted video, so this discloses instead. No
            // "Delete metadata" button: there is nothing behind it to press.
            FileMetadataNoteKind.VIDEO -> {
                Spacer(Modifier.height(LkSpace.md))
                LkInlineNote(
                    stringResource(R.string.files_edit_metadata_video_note),
                    modifier = Modifier.lkTag("file-settings-metadata-video"),
                    accent = c.amber,
                    icon = LkIcons.Info,
                )
            }
            FileMetadataNoteKind.NONE -> Unit
        }

        Spacer(Modifier.height(LkSpace.md))
        LkSecondaryButton(
            text = stringResource(R.string.files_edit_replace_file),
            icon = LkIcons.Refresh,
            onClick = onReplace,
            enabled = !state.isWorking,
            height = 48.dp,
            modifier = Modifier.lkTag("file-settings-replace"),
        )
    }
}

/** A row that leads to another screen, drawn like the "Folder, tags and more" row beside it. */
@Composable
private fun FileNavRow(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(LkShape.xl)
            .lkSurface(LkShape.xl, Color.Transparent, c.ring)
            .minTouchTarget()
            .clickable(onClick = onClick)
            .padding(horizontal = LkSpace.md, vertical = LkSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Icon(icon, contentDescription = null, tint = c.textFaint, modifier = Modifier.size(18.dp))
        Text(label, style = LkText.navLabel, color = c.textSecondary, modifier = Modifier.weight(1f))
        Icon(LkIcons.ChevronRight, contentDescription = null, tint = c.textFaint, modifier = Modifier.size(18.dp))
    }
}

/** The kind glyph on the file card: a picture for an image or a video, a paperclip for the rest. */
private fun fileKindIcon(mime: String): ImageVector = when {
    mime.startsWith("image/") -> LkIcons.Gallery
    mime.startsWith("video/") -> LkIcons.Play
    else -> LkIcons.Attachment
}

/**
 * The file's type as a person reads it — "PNG", "PDF", "XLSX" — in place of the MIME string the
 * card used to print (`image/png`, `application/vnd.openxmlformats-…`). The extension when there
 * is one, because it is what the user sees in every other app; otherwise the MIME subtype.
 * Format names are not translated, so this needs no resource.
 */
internal fun fileTypeLabel(filename: String, mime: String): String {
    val ext = filename.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 && it != filename }
    if (ext != null) return ext.uppercase(Locale.ROOT)
    val subtype = mime.substringAfter('/', "").substringBefore(';').substringAfterLast('.').substringAfterLast('+')
    return subtype.takeIf { it.isNotBlank() && it != "octet-stream" }?.uppercase(Locale.ROOT) ?: "FILE"
}

/**
 * The question asked after saving, about the copy left outside the vault.
 *
 * Android's own dialog for a gallery item says *"Allow LocalKeep to delete this photo?"* — a
 * sentence the app cannot change, which on its own read as the app demanding to destroy something
 * and did not say which copy (2026-10-09). This comes first and says what is true: the item is
 * saved, the original is still outside LocalKeep, and Android will confirm. For a document there is
 * no system dialog and no bin, so this is the only confirmation and it says "permanently".
 *
 * Back and a tap outside keep the original. "Don't ask again" is offered on the keep path only:
 * deleting is never remembered, because each deletion is of a different file.
 */
@Composable
private fun OriginalDeleteDialog(
    prompt: OriginalPrompt,
    onKeep: () -> Unit,
    onKeepAndStopAsking: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LkTheme.colors
    var stopAsking by rememberSaveable { mutableStateOf(false) }
    val document = prompt.kind == OriginalKind.DOCUMENT
    // The checkbox belongs to Keep: ticking it greys out Delete, and Keep then also switches the
    // offer off. Through `onDismiss`, so the Keep button, Back and a tap outside all honour it.
    LkDialog(
        onDismiss = { if (stopAsking) onKeepAndStopAsking() else onKeep() },
        title = stringResource(
            if (document) R.string.files_original_prompt_title_document else R.string.files_original_prompt_title_gallery,
        ),
        body = stringResource(
            when (prompt.kind) {
                OriginalKind.PHOTO -> R.string.files_original_prompt_body_photo
                OriginalKind.VIDEO -> R.string.files_original_prompt_body_video
                OriginalKind.DOCUMENT -> R.string.files_original_prompt_body_document
            },
            prompt.name,
        ),
        icon = LkIcons.Trash,
        accent = c.amber,
        confirm = LkDialogAction(
            label = stringResource(
                if (document) R.string.files_original_prompt_confirm_document else R.string.files_original_prompt_confirm_gallery,
            ),
            onClick = onDelete,
            emphasis = LkActionEmphasis.Destructive,
            enabled = !stopAsking,
        ),
        dismissLabel = stringResource(R.string.files_original_prompt_keep),
        modifier = Modifier.lkTag("file-original-prompt"),
    ) {
        LkGroup {
            LkCheckRow(
                label = stringResource(R.string.files_original_prompt_dont_ask),
                checked = stopAsking,
                onToggle = { stopAsking = !stopAsking },
                first = true,
                modifier = Modifier.lkTag("file-original-dont-ask"),
            )
        }
        // A caption under the row, not the row's `hint`: LkCheckRow draws its hint as a trailing
        // value, which on a dialog's width squeezed the label into a one-letter column
        // (emulator-5554, 2026-10-09).
        Text(
            stringResource(R.string.files_original_prompt_dont_ask_hint),
            style = LkText.caption,
            color = c.textMuted,
            modifier = Modifier.padding(top = LkSpace.xs, start = LkSpace.xs),
        )
    }
}

/** One row of [FileSourceChooserSheet]: a leading icon plate plus a label — the same leading-icon
 *  row shape [LkGroupRow]'s `horizontalArrangement` doc describes (HiddenItemsScreen draws it too). */
@Composable
private fun FileSourceRow(first: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = LkTheme.colors
    LkGroupRow(first = first, onClick = onClick, horizontalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .lkSurface(LkShape.md, c.jade.copy(alpha = 0.08f), c.jade.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.jade, modifier = Modifier.size(16.dp))
        }
        Text(label, style = LkText.rowTitle, color = c.textPrimary, modifier = Modifier.weight(1f))
    }
}

/**
 * Bytes as a person reads them, for everything at or above 1 KB — `null` below it.
 *
 * `sizeBytes / 1024` reported a 4 MB photo as "4096 KB", and a 900-byte note as "0 KB" — which reads
 * as an import that silently produced nothing.
 *
 * KB/MB/GB are international symbols and stay as they are; the sub-KB case is the only branch with
 * an actual *word* in it, so it is the only one that needs a resource — and it returns `null` here
 * rather than an English literal, which is what it did until 2026-07-30. A French device showed
 * "image/png · 70 bytes". Kept pure so the arithmetic stays testable on the JVM; [readableSize]
 * is the composable that fills the hole.
 */
internal fun Long.asReadableSize(): String? = when {
    // Nothing caps attachment size, so the MB branch alone eventually reads "2048.0 MB".
    // Locale.ROOT, not the device locale — matching every other formatted value in this codebase
    // (VaultRepository, RecoveryKit, etc.) — so the decimal separator doesn't drift with locale.
    this >= 1_073_741_824 -> "%.1f GB".format(Locale.ROOT, this / 1_073_741_824.0)
    this >= 1_048_576 -> "%.1f MB".format(Locale.ROOT, this / 1_048_576.0)
    this >= 1_024 -> "${this / 1_024} KB"
    else -> null
}

/** [asReadableSize], with the sub-KB case resolved against the current locale. */
@Composable
internal fun Long.readableSize(): String =
    asReadableSize() ?: pluralStringResource(R.plurals.files_size_bytes, toInt(), this)

/**
 * The file editor's folder chooser: [LkFolderPickerScreen] wired to this screen's ViewModel.
 *
 * A near-twin of the item editor's adapter, deliberately. Both hand the same control the same
 * things; what differs is only which ViewModel the callbacks land on. The alternative — a reduced
 * picker here, "just for files" — is how one app ends up with two folder lists that disagree about
 * whether you can make a folder from them.
 *
 * The delete confirmation is rendered here rather than by the caller: the caller composes this
 * screen *instead of* the form, so a dialog left behind on the form would never be seen.
 */
@Composable
private fun FileFolderPicker(
    state: FileItemEditUiState,
    viewModel: FileItemEditViewModel,
    onBack: () -> Unit,
) {
    // One arrival flash for a folder just created. Presentation, so deliberately not saveable.
    var arrivedFolderId by remember { mutableStateOf<UUID?>(null) }
    var knownFolderCount by remember { mutableIntStateOf(state.folders.size) }
    LaunchedEffect(state.folders.size) {
        if (state.folders.size > knownFolderCount) arrivedFolderId = state.folderId
        knownFolderCount = state.folders.size
    }
    // Two stages, innermost first: the name being typed, then the screen. The comp's list has no
    // disclosure to collapse and no trash, so there is nothing else to unwind here.
    //
    // Making a folder leaves the card open and empty for the next one, so once something was made
    // and the field is empty, Back leaves rather than closing an empty card first. Leaving closes
    // the card too, so the next visit does not open on one.
    val leave = {
        if (state.showNewFolderInput) viewModel.hideNewFolderInput()
        onBack()
    }
    BackHandler(enabled = true) {
        if (folderPickerLeavesOnDismiss(state.showNewFolderInput, state.newFolderName, arrivedFolderId != null)) {
            leave()
        } else {
            viewModel.hideNewFolderInput()
        }
    }

    // Every folder, at its own depth: `rows` gates children on the expanded set, so passing every id
    // asks it for the whole tree — flat and indented, which is what the comp draws.
    // Minus whatever the user has folded away — the list still opens flat (2026-08-20).
    var collapsedFolders by rememberSaveable(stateSaver = LkCollapsedFoldersSaver) {
        mutableStateOf(emptySet<UUID>())
    }
    val allFolderIds = state.folders.mapTo(mutableSetOf()) { it.id }
    val rows = FolderPickerState.rows(state.folders, allFolderIds - collapsedFolders).map { row ->
        // Two folders may legally share a name under different parents — a merge of two vaults
        // produces exactly that — and a picker offering the same word twice makes the choice
        // unknowable. `map` and `let` are inline, so these resolve in the caller's composition.
        val base = row.disambiguateBy
            ?.let { stringResource(R.string.files_folder_picker_subfolder, row.folder.name, it) }
            ?: row.folder.name
        LkFolderRow(
            id = row.folder.id,
            name = stringResource(
                R.string.files_folder_name_suffix,
                base,
                state.folderItemCounts[row.folder.id] ?: 0,
            ),
            depth = row.depth,
            hasChildren = row.hasChildren,
            expanded = row.folder.id !in collapsedFolders,
        )
    }

    val typed = state.newFolderName.trim()
    // Siblings only, case-insensitively — the same comparison createFolderInternal makes, so this
    // cannot say "fine" about a name the commit will refuse.
    val status = when {
        typed.isEmpty() -> LkFolderNameStatus.Free
        state.folders.any { it.parentId == state.newFolderParentId && it.name.equals(typed, true) } ->
            LkFolderNameStatus.TakenHere
        state.folders.any { it.name.equals(typed, ignoreCase = true) } -> LkFolderNameStatus.TakenElsewhere
        else -> LkFolderNameStatus.Free
    }

    LkFolderPickerScreen(
        rows = rows,
        selectedId = state.folderId,
        onPick = { id ->
            viewModel.onFolderSelected(id)
            onBack()
        },
        onBack = leave,
        onToggleExpanded = { id ->
            collapsedFolders = if (id in collapsedFolders) collapsedFolders - id else collapsedFolders + id
        },
        onCollapseAll = { collapsedFolders = allFolderIds },
        onExpandAll = { collapsedFolders = emptySet() },
        onCreateStart = viewModel::showNewFolderInput,
        onCreateCancel = viewModel::hideNewFolderInput,
        newFolderName = state.newFolderName.takeIf { state.showNewFolderInput },
        onNewFolderNameChange = viewModel::onNewFolderNameChanged,
        onCreateConfirm = viewModel::createFolder,
        newFolderStatus = status,
        arrivedId = arrivedFolderId,
    )
}

package com.localkeep.feature.vaultlist

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.snap
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.common.sortedByDisplayName
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderPickerState
import com.localkeep.core.model.FolderTree
import com.localkeep.core.totp.TotpGenerator
import com.localkeep.core.ui.openPlayListing
import com.localkeep.core.model.MediaViewerFocusRequest
import com.localkeep.core.model.MediaViewerScope
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.ProTypeQuota
import com.localkeep.core.vault.MakeInvisibleAvailability
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.ui.ClipboardHelper
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.nav.PendingFolderSelection
import androidx.compose.animation.core.animateDpAsState
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.shellOverlayInset
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.rememberHapticClick
import com.localkeep.core.ui.rememberHaptics
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkCollapsedFoldersSaver
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkDragDwellClock
import com.localkeep.core.ui.theme.LkDragOverlay
import com.localkeep.core.ui.theme.LkDragPayload
import com.localkeep.core.ui.theme.LkDragSession
import com.localkeep.core.ui.theme.LkDropAcceptance
import com.localkeep.core.ui.theme.LkDropTargetKey
import com.localkeep.core.ui.theme.lkDragTracker
import com.localkeep.core.ui.theme.lkDropTarget
import com.localkeep.core.ui.theme.lkDwellProgress
import com.localkeep.core.ui.theme.lkPressAndDrag
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkFootnote
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkGroupRow
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkLockButton
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.core.ui.theme.LkFolderNameStatus
import com.localkeep.core.ui.theme.LkFolderPickerScreen
import com.localkeep.core.ui.theme.LkFolderRow
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkSheet
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkFocusRing
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkRing
import com.localkeep.core.ui.theme.lkStaggerDelay
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.common.PendingReveal
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * T4 — where the FAB was, coral, for the whole duration of a drag.
 *
 * Accepts both payload kinds and routes them differently: items go to the trash one by one through
 * the existing per-item path (which already carries an undo), a folder goes through `trashFolder`,
 * which takes its whole subtree. Both are reversible, which is why this is coral rather than a
 * confirmation — the dock is not the purge.
 */
@Composable
private fun TrashDock(
    session: LkDragSession,
    onDropItems: (ItemsDragPayload) -> Unit,
    onDropFolder: (FolderDragPayload) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val key = LkDropTargetKey("drag-trash-dock")
    val hovered = session.hovered == key
    val label = stringResource(R.string.vaultlist_drag_trash_dock)
    Box(
        modifier = modifier
            .size(56.dp)
            .lkDropTarget(
                key = key,
                session = session,
                accepts = { LkDropAcceptance.Accepts },
                onDrop = { payload ->
                    when (payload) {
                        is ItemsDragPayload -> onDropItems(payload)
                        is FolderDragPayload -> onDropFolder(payload)
                    }
                },
            )
            .lkSurface(
                LkShape.lg,
                c.coral.copy(alpha = if (hovered) 0.18f else 0.10f),
                c.coral.copy(alpha = if (hovered) 1f else 0.4f),
            )
            .labelled(label),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LkIcons.Trash,
            contentDescription = null,
            tint = c.coral,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * What the ghost says about the payload, captured when the drag starts.
 *
 * Captured rather than looked up while the ghost is on screen, for the reason
 * [VaultListViewModel.acceptanceFor] reads the vault instead of the visible list: a spring-load
 * navigates away from the list the payload came from, and a ghost reading `state.items` went blank
 * — it announced "Untitled" for the rest of every drag that opened a folder.
 *
 * The icon is the item's own type, or a folder. It was a right-chevron for every item, which is the
 * row's "go" affordance and says nothing about what is in the user's hand.
 */
private data class GhostLabel(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/**
 * What the finger is carrying.
 *
 * A compact plate rather than a clone of the row: at 1.03 scale a full-width row under a finger
 * covers most of the list it is being aimed at, and the drop targets are the thing that needs to be
 * visible. The count is the payload's own, so a multi-item drag says how many.
 */
@Composable
private fun DragGhost(payload: LkDragPayload, label: GhostLabel?) {
    val c = LkTheme.colors
    val title = label?.title.orEmpty()
    Row(
        modifier = Modifier
            .lkSurface(LkShape.lg, c.surface, c.jade)
            .padding(horizontal = LkSpace.md, vertical = LkSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        label?.icon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = c.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = title.ifBlank { stringResource(R.string.vaultlist_item_untitled) },
            style = LkText.rowTitle,
            color = c.textPrimary,
            maxLines = 1,
        )
        if (payload.count > 1) {
            Text(
                text = payload.count.toString(),
                style = LkText.badge,
                color = c.jade,
            )
        }
    }
}

/**
 * A full-width destination that appears only during a drag: T3 at the head of the list, T5 at its
 * tail.
 *
 * 56 dp, comfortably over the 48 dp floor, because both of these are aiming targets rather than
 * rows — the alternative for T5 was the list's own background, and with `Arrangement.spacedBy(2.dp)`
 * and a populated vault there is no background left to aim at.
 */
@Composable
private fun DragDestinationStrip(
    icon: ImageVector,
    label: String,
    session: LkDragSession,
    targetKey: LkDropTargetKey,
    accepts: (LkDragPayload) -> LkDropAcceptance,
    onDrop: (LkDragPayload) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val hovered = session.hovered == targetKey
    val accepted = hovered && session.hoveredAcceptance == LkDropAcceptance.Accepts
    val refused = hovered && session.hoveredAcceptance == LkDropAcceptance.Rejects
    Row(
        // Width is the call site's to state: T5 is a full-width strip at the end of the list, T3 a
        // pill floating beside the bin, and the only difference between them is this modifier.
        modifier = modifier
            .height(56.dp)
            .padding(vertical = 4.dp)
            .lkDropTarget(
                key = targetKey,
                session = session,
                accepts = accepts,
                onDrop = onDrop,
            )
            .lkSurface(
                LkShape.lg,
                if (accepted) c.jade.copy(alpha = 0.10f) else c.surface,
                when {
                    accepted -> c.jade
                    refused -> c.coral
                    else -> c.ring
                },
            )
            .padding(horizontal = LkSpace.md)
            .labelled(label),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (accepted) c.jade else c.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = LkText.rowTitle,
            color = if (accepted) c.jade else c.textSecondary,
            // A folder name is user text of any length, and the pill has a hard width. `labelled`
            // above carries the whole sentence, so what a screen reader announces is unaffected.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * "Move this folder to Trash", with a snackbar that names what went with it.
 *
 * The counts are resolved **at composition**, because `pluralStringResource` only works during
 * composition and a callback cannot resolve one.
 *
 * They come from [VaultListUiState.folderSubtreeItemCounts] — the unnarrowed count — and not from
 * the number this row is showing. Those two are the same only when no type chip is on. The row's
 * count is narrowed by the chip on purpose, while `trashFolder` moves the whole subtree, so a
 * folder holding 1 TOTP and 40 logins trashed under the 2FA chip announced "1 folder, 1 item moved
 * to Trash" over 41 items that moved. This path posts a plain message with no undo, so that
 * sentence is the only report of the operation the user gets.
 *
 * This is also §8's accessible equivalent of dropping a folder on the Trash dock — the one drag
 * capability with no existing non-gesture route. Tapping a folder already navigates into it, the
 * breadcrumb already navigates out, and `BulkMoveSheet` already lists the whole tree plus "No
 * folder"; folder-trash had nothing.
 */
@Composable
private fun trashFolderAction(
    folder: Folder,
    folderCount: Int,
    state: VaultListUiState,
    viewModel: VaultListViewModel,
    session: LkDragSession,
): () -> Unit {
    val itemCount = state.folderSubtreeItemCounts[folder.id] ?: 0
    val notice = stringResource(
        R.string.vaultlist_drag_trashed_folder,
        pluralStringResource(R.plurals.vaultlist_trash_folder_folder_count, folderCount, folderCount),
        pluralStringResource(R.plurals.vaultlist_trash_folder_item_count, itemCount, itemCount),
    )
    val undoLabel = stringResource(R.string.vaultlist_notice_undo)
    val restoredNotice = stringResource(R.string.vaultlist_notice_folder_restored)
    return {
        session.committing = true
        viewModel.trashFolderFromDrag(
            folder.id,
            { _, _ -> LkNotices.Message.Text(notice) },
            undoLabel,
            restoredNotice,
        ) {
            session.committing = false
        }
    }
}

/**
 * The folders [trashFolderAction] must be built for: the rows on screen, plus the one being carried.
 *
 * The carried folder is looked up in [all], not in [visible], because by the time the finger reaches
 * the Trash dock it may not be a row any more — resting on a sibling for the spring-load opens that
 * sibling with the payload still in hand, and the list is then showing the sibling's children.
 * Nothing is added when the carried folder is already a row, or is not a folder this vault has.
 */
internal fun foldersWithTrashAction(
    visible: List<Folder>,
    all: List<Folder>,
    carriedFolderId: UUID?,
): List<Folder> {
    if (carriedFolderId == null || visible.any { it.id == carriedFolderId }) return visible
    val carried = all.firstOrNull { it.id == carriedFolderId } ?: return visible
    return visible + carried
}

/**
 * How long a just-saved row stays emphasised before the wash fades.
 *
 * Not a motion token: this is a reading time, not a transition. It has to outlast the glance of
 * someone whose eyes are still on the Save button they pressed on the previous screen.
 */
private const val REVEAL_HOLD_MS = 1_400L

/**
 * The same hold under reduced motion, where the wash has no fade at either end and is therefore a
 * flat block of colour for its whole duration rather than a ramp.
 */
private const val REVEAL_HOLD_REDUCED_MS = 600L

@Composable
fun VaultListScreen(
    viewModel: VaultListViewModel,
    onItemClick: (String) -> Unit,
    onFileItemClick: (String, MediaViewerFocusRequest) -> Unit,
    onItemSettingsClick: (String) -> Unit = {},
    onFileSettingsClick: (String) -> Unit = {},
    /**
     * Open the editor for a new item of this type. The flag is the door the user came through:
     * true from the add sheet, false from an empty state's own "Add a login" offer.
     *
     * The shell needs it because cancelling out of the editor has to land back on whatever raised
     * it — the sheet, or the bare list — exactly as the folder chooser already did. Passed as an
     * argument rather than inferred at the wiring layer because both doors reach the same route,
     * and only this screen knows which one was used.
     */
    onAddItem: (NewItemType, Boolean) -> Unit,
    onAddFile: (Boolean) -> Unit,
    typeFilter: ItemTypeFilter? = null,
    onOpenDrawer: () -> Unit = {},
    /**
     * Open Settings on the "Change master password" row, for the session that came in through the
     * Recovery Kit. A callback rather than a branch here because the route and the focus request
     * belong to the navigation layer — see the note this raises.
     */
    onSetNewMasterPassword: () -> Unit = {},
    /**
     * Make a folder. Takes the folder currently being browsed, which seeds the new one's parent.
     *
     * A callback rather than a branch inside this screen: the chooser is a focused task and gets a
     * destination of its own, so the shell drops the tab bar for it exactly as it does for the item
     * editor. Rendered in place, the same screen carried a nav bar when reached from the vault list
     * and none when reached from the editor.
     *
     * The Boolean is *which door*: true when the add sheet's "New folder" row navigated, false for
     * the folder-chip strip's own "+". The wiring layer routes it into the destination so that
     * backing out of the chooser can land where the user actually was — see [reopenAddSheet].
     */
    onNewFolder: (java.util.UUID?, Boolean) -> Unit = { _, _ -> },
    /**
     * One-shot request to reopen the add sheet, raised by the navigation layer when the folder
     * chooser that the *sheet* opened is cancelled. Backing out used to land on the bare list —
     * the sheet the user was mid-way through was gone, and "back" had quietly meant "home".
     * Consumed via [onReopenAddSheetHandled] so a configuration change cannot replay it.
     */
    reopenAddSheet: Boolean = false,
    onReopenAddSheetHandled: () -> Unit = {},
    /**
     * Put the caret in the search field on arrival — the app shortcut's "Search" entry.
     *
     * Consumed via [onSearchFocusHandled] for the same reason [reopenAddSheet] is: the flag lives in
     * a `SavedStateHandle`, so an unconsumed one replays on every configuration change and the
     * keyboard comes back up each time the user rotates away from it.
     */
    focusSearch: Boolean = false,
    onSearchFocusHandled: () -> Unit = {},
) {
    val c = LkTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val clipboardPasswordLabel = stringResource(R.string.vaultlist_clipboard_label_password)
    val clipboardTotpLabel = stringResource(R.string.vaultlist_clipboard_label_totp)
    val totpGenerateError = stringResource(R.string.vaultlist_totp_generate_error)

    // FolderBrowseRow's own Rename/Delete menu entries drive this FolderViewModel directly — a
    // second instance from the one FolderScreen gets, scoped to this destination's own back-stack
    // entry via hiltViewModel(). Its rename/delete already validate against the vault (sibling
    // clashes, the promote-on-delete plan) exactly as FolderScreen's do; reusing its state here is
    // what makes the row menu correct rather than a second, thinner implementation of the same rules.
    val folderVm: FolderViewModel = hiltViewModel()
    val folderState by folderVm.state.collectAsStateWithLifecycle()
    // Resolved here, once, for the LkNotices confirmations the ViewModel cannot resolve itself
    // (LkNotices.post takes a plain String; see VaultListViewModel's kdoc on moveToTrash/bulkEdit).
    val movedToTrashNotice = stringResource(R.string.vaultlist_notice_moved_to_trash)
    val undoNoticeLabel = stringResource(R.string.vaultlist_notice_undo)
    val restoredNotice = stringResource(R.string.vaultlist_notice_restored)
    val noBulkChangeNotice = stringResource(R.string.vaultlist_notice_no_bulk_change)
    // Which Pro-gated type the user just reached for, or null when no prompt is showing. Holding
    // the type (rather than a bare flag) lets the dialog name what is actually locked.
    // All `rememberSaveable`: every one of these is a sheet or dialog the user deliberately opened,
    // and a rotation used to close all of them. The selection they act on lives in the ViewModel and
    // already survived, so the effect was a confirmation for a bulk delete quietly disappearing
    // while the items stayed selected — indistinguishable from having answered it.
    var quotaBlockedType by rememberSaveable { mutableStateOf<NewItemType?>(null) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    // True only for the sheet that is coming *back* — the user picked a row, looked at it, and
    // backed out. It was never dismissed as far as they are concerned, so it must not play its
    // entrance. Every other route to the sheet is a genuine open and leaves this false.
    var addSheetRestored by rememberSaveable { mutableStateOf(false) }
    // The sheet that has been answered and is sliding away — see [LkSheet.closing]. Deliberately a
    // plain `remember`: a sheet caught mid-exit by a rotation has no exit left to play, and the
    // saveable flag above it is already false by then, so it comes back not at all rather than
    // coming back to finish leaving.
    var addSheetLeaving by remember { mutableStateOf(false) }
    // Of those, the ones that must play their exit rather than simply staying put. A sheet the user
    // answered with a navigation does NOT: the screen it chose replaces this whole composition, and
    // the sheet goes with it, so animating it away first is a change the user has to watch before
    // the one they asked for. A sheet answered with a Pro or quota prompt does, because nothing is
    // coming to cover it.
    var addSheetClosing by remember { mutableStateOf(false) }
    var showMoveSheet by rememberSaveable { mutableStateOf(false) }
    // The filter sheet. Saveable for the same reason the add sheet is: a rotation mid-choice must
    // not close it. Every filter it applies lives in the ViewModel, so there is nothing else to keep.
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    // The accessibility route into that same sheet, for one row rather than a selection.
    //
    // Held as a prefixed string rather than an `LkDragPayload` so it is saveable without a custom
    // Saver — and saveable it must be, for the reason the flags around it are: a rotation with this
    // sheet open would otherwise drop the move the user was halfway through choosing. "f:" is a
    // folder, "i:" a single item; the payload is rebuilt from it below.
    var moveRequestKey by rememberSaveable { mutableStateOf<String?>(null) }
    // See [GhostLabel]. Plain `remember`: a drag cannot survive a rotation anyway (the gesture's
    // pointer stream ends with the Activity), so there is nothing here worth restoring.
    var ghostLabel by remember { mutableStateOf<GhostLabel?>(null) }
    var showTagDialog by rememberSaveable { mutableStateOf(false) }
    var confirmBulkTrash by rememberSaveable { mutableStateOf(false) }
    // Where the FAB currently sits, in window coordinates, for the one row under it to dodge.
    // Plain `remember`: it is re-measured on the first layout after any restoration.
    var fabWindowBounds by remember { mutableStateOf<Rect?>(null) }
    // Task 6: folder-create entry points. Both hand the browsed folder to [onNewFolder], which is
    // the parent the new folder lands under; the chooser it navigates to lets the user move it.
    // Marked like the add-item path below, and for its reason: returning from the chooser otherwise
    // reads to `onEnteredList` as returning from another tab, which resets the list to the root —
    // a subfolder made from inside a folder threw the user out of it (QA 2026-10-03).
    val newFolderHere = { fromAddSheet: Boolean ->
        viewModel.noteOpeningItem()
        onNewFolder(state.activeFolder?.id, fromAddSheet)
    }

    LaunchedEffect(reopenAddSheet) {
        if (reopenAddSheet) {
            addSheetRestored = true
            showAddSheet = true
            onReopenAddSheetHandled()
        }
    }

    // The search field's own requester, so the shortcut can put the caret in it. Only ever used by
    // [focusSearch] — nothing else in this screen steals focus, and a screen that grabs it on
    // arrival raises the keyboard over the list the user came to read.
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            // The field is the first list item's child now, so it is only attached while the header
            // is on screen. Bring the header back and let it compose before asking for focus —
            // `requestFocus` on a field that is not in the tree throws.
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.scrollToItem(0)
                withFrameNanos { }
            }
            searchFocus.requestFocus()
            onSearchFocusHandled()
        }
    }

    // One decision for every add entry point. The empty state used to call onAddItem directly,
    // which skipped the Pro gate and sent a FILE add to the shared editor — where it opened as a
    // Login (2026-07-29 hand-test).
    // `fromSheet` travels with the choice rather than being decided here, because the two callers
    // came through different doors: the sheet, which cancelling must put back, and an empty state's
    // own offer, which was never a sheet and must not conjure one.
    // Returns true when it actually navigated. The add sheet needs the answer: a screen is about to
    // replace this one and will take the sheet with it, or a prompt is about to open over a screen
    // that is staying, and then the sheet has to remove itself.
    val addOfType: (NewItemType, Boolean) -> Boolean = { type, fromSheet ->
        val typeQuotaFull = when (type) {
            NewItemType.CARD -> state.cardQuotaFull
            NewItemType.IDENTITY -> state.identityQuotaFull
            else -> false
        }
        when (val dest = type.addDestination(state.isPro, state.attachmentQuotaFull, typeQuotaFull)) {
            is AddDestination.QuotaBlocked -> { quotaBlockedType = dest.type; false }
            // Carries the pending like the item branch below. It was left out on 2026-08-12, when
            // the files editor had no folder field and a request it could not consume would have
            // filed whatever the user added next; the field arrived on 2026-08-19 and this branch
            // did not follow, so "Add to My Folder" saved a file at the top level (device,
            // 2026-10-04). `FILE_NEW` takes the request as the editor opens, so none is left over.
            AddDestination.FileEditor -> {
                state.folderStack.lastOrNull()?.let(PendingFolderSelection::request)
                viewModel.noteOpeningItem()
                onAddFile(fromSheet)
                true
            }
            is AddDestination.Editor -> {
                state.folderStack.lastOrNull()?.let(PendingFolderSelection::request)
                // Same call the row taps make, so returning from the add does not drop the browse
                // path — `onEnteredList` cannot otherwise tell "came back from the editor" from
                // "came back from another tab", and the second one resets to the root.
                viewModel.noteOpeningItem()
                onAddItem(dest.type, fromSheet)
                true
            }
        }
    }

    // The destination *seeds* the filter; it does not own it. This effect restarts whenever the
    // destination is recomposed — which includes every pop back from an item — so pushing the
    // argument in each time silently undid whatever chip the user had tapped, while the search text
    // beside it survived. The ViewModel applies it once and the chips own it from then on.
    LaunchedEffect(typeFilter) { viewModel.applyInitialTypeFilter(typeFilter) }

    // What the bulk-action bar is taking at the bottom while a selection is live, so the list can
    // reserve it instead of assuming. See [bulkBarListInset].
    var bulkBarHeightPx by remember { mutableIntStateOf(0) }

    // The sticky breadcrumb's measured height, 0 when there is none — see [VaultListBody]. The
    // reveal scroll reads it so the row it brings into view is not parked under the crumb.
    var crumbHeightPx by remember { mutableIntStateOf(0) }

    // The fold, kept per scope: walking into a folder, or switching the type filter, is a different
    // folder list and starts folded again. `rememberSaveable` with the scope as its input does both
    // halves of that — it survives a rotation and resets when the scope changes — which a plain
    // `remember` in a `key` block would not.
    //
    // Declared up here, above the list it belongs to, because the post-save scroll needs it: how
    // many folder rows the LazyColumn is drawing is part of where an item's row actually sits.
    var foldersExpanded by rememberSaveable(
        state.folderStack.lastOrNull()?.toString().orEmpty(),
        state.typeFilter?.name.orEmpty(),
        state.showFavoritesOnly,
    ) { mutableStateOf(false) }

    // Fires on every entry to the list — both the pop back from an item and the return from another
    // tab. The ViewModel tells those two apart (see onEnteredList) and only drops the narrowing for
    // the second, because coming back to a tab and finding the list still filtered by a chip tapped
    // minutes ago just reads as an empty vault.
    //
    // A configuration change — rotation, dark mode, font size, a multi-window resize — is neither,
    // and from inside composition it is indistinguishable from a tab switch: both dispose this
    // composable and re-run the effect, and both restore whatever `rememberSaveable` held, so a
    // flag cannot tell them apart either. The chip the user tapped a second before rotating was
    // wiped along with their search text, while the ViewModel holding both survived intact.
    //
    // `isChangingConfigurations` is the one signal that does distinguish them, and it is only true
    // on the way out, so the answer is recorded at disposal and read on the way back in.
    DisposableEffect(Unit) {
        onDispose { viewModel.noteLeavingList(recreating = activity?.isChangingConfigurations == true) }
    }
    LaunchedEffect(Unit) { viewModel.onEnteredList() }

    // Ordered after onEnteredList deliberately: that call is what drops a stale narrowing on a tab
    // return, so applying the folder first would have it cleared a line later by the very effect
    // meant to give the user a clean list. See [FolderFocus].
    LaunchedEffect(Unit) { FolderFocus.take()?.let(viewModel::openFolderPath) }

    // The editor has just saved something. Collected here rather than passed down a route argument
    // because it has to work for a pop back from the FAB and a `navigate` from the generator alike,
    // and it is consumed on arrival so a later return to this list is not highlighted again.
    LaunchedEffect(Unit) {
        PendingReveal.take()
            ?.let { id -> runCatching { UUID.fromString(id) }.getOrNull() }
            ?.let(viewModel::revealItem)
    }

    // A new narrowing is a new list, and a `LazyListState` carries an index, not a place. Put the
    // viewport back at the top so what the change produced is what the user is looking at — folder
    // rows, FAVORITES and ALL ITEMS included (F-10). Keyed on the counter, so two changes in a row
    // are two scrolls and a refresh that changed nothing is none.
    //
    // `scrollToItem`, not `animateScrollToItem`: the list under it has already been replaced, so an
    // animation would be a smooth travel across rows that have nothing to do with either state.
    // Skipped while a reveal is pending — `revealItem` drops the filters, which is a narrowing
    // change, and the row it is about to scroll to is the whole point of that change.
    LaunchedEffect(state.scrollToTopRequest) {
        if (state.scrollToTopRequest == 0L) return@LaunchedEffect
        if (state.revealedItemId != null) return@LaunchedEffect
        listState.scrollToItem(0)
    }

    // Scroll to it once it is actually in the list — `revealItem` drops the filters and re-reads the
    // vault, so the row does not exist yet at the moment the id arrives. Keying on the item list as
    // well as the id is what waits for that second pass.
    val revealedId = state.revealedItemId
    val reduceMotion = LocalLkReduceMotion.current
    LaunchedEffect(revealedId, state.items) {
        if (revealedId == null) return@LaunchedEffect
        // `orderedItems`, not `items`: the list renders the browsed folder's own rows before the
        // "In subfolders" section, so a revealed row that lives further down is at a different index
        // than the unordered list would report.
        val index = state.orderedItems.indexOfFirst { it.id == revealedId }
        if (index < 0) return@LaunchedEffect
        // Through the item list's index *plus the rows drawn above it*. It was the bare index until
        // 2026-09-16, and a `LazyColumn` index is not an item index: the folder rows and the fold's
        // own control are `item`s of the same list, so with three folders at the root the list
        // scrolled three rows short of the thing it had just told the user it was showing. That is
        // the second half of F-10 — a saved item that lands off screen — and it is why the first
        // half (`shots/056-after-save-empty-login.png`) looked like an arbitrary offset.
        listState.animateScrollToItem(
            listRowIndexOfItem(
                itemIndex = index,
                foldersShown = foldersShown(state.childFolders, foldersExpanded).size,
                folderOverflowRow = folderOverflowVisible(state.childFolders.size),
                noItemsRow = state.hasOnlySubfolders,
                leadingRows = leadingListRows(
                    hasCrumb = state.folderStack.isNotEmpty(),
                    hasNotice = state.error != null,
                ),
            ),
            // The breadcrumb sticks to the top of the viewport, so a row scrolled "to the top"
            // would land under it. Negative: the row ends up that far *below* the edge.
            scrollOffset = if (state.folderStack.isNotEmpty()) -crumbHeightPx else 0,
        )
        // Long enough to be seen by someone whose eyes are still on the Save button, then cleared so
        // the wash fades rather than staying on as a second, permanent-looking selection state.
        //
        // Shorter under reduced motion, and this is not a nicety. `lkTween` collapses to `tween(0)`
        // there, so the wash has no ramp at either end — it snaps to full jade, sits as a flat block
        // and snaps off. At the full hold that is a harder, more attention-grabbing event than the
        // one everyone else gets, which is backwards for a setting that asks for less. The hold
        // still has to outlast a glance, so it shortens rather than disappearing.
        delay(if (reduceMotion) REVEAL_HOLD_REDUCED_MS else REVEAL_HOLD_MS)
        viewModel.clearReveal()
    }

    // The drawer's "All items" clears the filters even when it cannot move the navigation — tapping
    // it while already on the plain vault list is the case that did nothing. Collected here rather
    // than taken on entry because that case never re-enters: the list stays composed under the open
    // drawer, so a live collector is the only thing the reset can reach. See [HomeFilter].
    LaunchedEffect(Unit) { HomeFilter.resets.collect { viewModel.clearTransientFilters() } }

    // Back undoes what is on screen before it leaves it, in the order the user built it: the
    // selection, then the search, then the filter tokens, then the folder. Without this, backing out
    // of a half-made selection exits the vault list entirely, which is the wrong answer to
    // "never mind" — and until 2026-09-16 the same was true of a Favorites token and a live search
    // (F-08), which closed the app outright.
    //
    // One handler over [resolveListBack] rather than one per stage: the stages are ordered, and four
    // independent `enabled` flags are four chances for two of them to be true at once. An open
    // keyboard goes before all of them — see [ListBackHandler].
    val backAction = state.resolveListBack(viewModel.initialTypeFilter)
    ListBackHandler(backAction) { viewModel.onBack() }

    // The drag session. Deliberately *not* `rememberSaveable`: MainActivity declares no
    // `configChanges`, so a rotation recreates it and a drag in flight is abandoned. Nothing is lost
    // — no write has landed — but the abort has to run the cancel path rather than leaving a ghost
    // and a collapsed origin row behind, which is what `DisposableEffect` below is for.
    val dragSession = remember { LkDragSession() }
    DisposableEffect(Unit) { onDispose { dragSession.clear() } }
    LkDragDwellClock(dragSession)

    /**
     * Where the list itself sits on screen, in the same root coordinates the finger is reported in.
     *
     * Auto-scroll needs the finger's position *within the list*, and the two obvious sources for
     * that are in different spaces: `fingerPosition` is root-relative (`lkDragTracker` converts it
     * on the screen's root node), while `listState.layoutInfo`'s offsets are relative to the
     * LazyColumn's own box. Subtracting one from the other type-checks and is meaningless — on this
     * screen the list starts around 900 px down, so every root y read as ~900 px too far into a
     * viewport ~1250 px tall, putting the whole list inside the bottom band. Measured on
     * emulator-5554 (2026-08-18): a drag held perfectly still at the vertical middle of the list
     * scrolled it to the end, and the drop landed on whatever had moved under the finger.
     */
    var listBounds by remember { mutableStateOf(Rect.Zero) }

    // Edge auto-scroll. Driven from a LaunchedEffect against the frame clock rather than from the
    // pointer stream, because a finger held still at the edge produces no further pointer events —
    // scroll rate would fall to zero exactly when the user is asking for it. The rate ramps across
    // the band so the edge is approachable rather than a cliff.
    val density = LocalDensity.current
    LaunchedEffect(dragSession.isDragging) {
        if (!dragSession.isDragging) return@LaunchedEffect
        val band = with(density) { 72.dp.toPx() }
        val maxRate = with(density) { 1200.dp.toPx() }
        var previousFrame = 0L
        while (true) {
            val now = withFrameNanos { it }
            val seconds = if (previousFrame == 0L) 0f else (now - previousFrame) / 1_000_000_000f
            previousFrame = now
            val viewport = listBounds.height
            if (viewport <= 0f || seconds <= 0f) continue
            val y = dragSession.fingerPosition.y - listBounds.top
            val rate = when {
                y < band -> -(1f - (y / band).coerceIn(0f, 1f)) * maxRate
                y > viewport - band -> ((y - (viewport - band)) / band).coerceIn(0f, 1f) * maxRate
                else -> 0f
            }
            if (rate != 0f) listState.scrollBy(rate * seconds)
        }
    }

    // One clock read for the whole list: every row asks the same session, and only the hovered one
    // is passed a non-zero value.
    val dwellProgress = lkDwellProgress(dragSession)

    val untitledLabel = stringResource(R.string.vaultlist_item_untitled)
    val ellipsis = stringResource(R.string.vaultlist_row_folder_trail_ellipsis)
    val dragUndoLabel = stringResource(R.string.vaultlist_drag_undo)
    val dragUndoneNotice = stringResource(R.string.vaultlist_drag_undone)
    val dragMovedFolderNotice = stringResource(R.string.vaultlist_drag_moved_folder)
    val dragUnfiledFolderNotice = stringResource(R.string.vaultlist_drag_unfiled_folder)
    // One trash action per visible folder, resolved here because the snackbar names counts and
    // `pluralStringResource` only works during composition. Both routes to a folder-trash — the
    // dock and the row's accessibility action — take the same entry, so their wording cannot drift.
    //
    // The folder in the finger gets one too, wherever it now is. A spring-load navigates away from
    // the list the drag started in, so the folder being carried is no longer among the rows, and the
    // dock — which accepts any payload — looked its action up, found none, did nothing and still
    // played the swallow (S1-03, 2026-10-02).
    //
    // Subtree sizes come from one index of the tree for the whole level: asking per folder rebuilt
    // it for each row, so a level of a large import cost one walk of every folder per row on every
    // composition (S1-09, 2026-10-02).
    val trashActionFolders = foldersWithTrashAction(
        visible = state.childFolders,
        all = state.folders,
        carriedFolderId = (dragSession.payload as? FolderDragPayload)?.folderId,
    )
    val subtreeSizes = remember(state.folders, trashActionFolders) {
        FolderTree.subtreeSizes(state.folders, trashActionFolders.map { it.id })
    }
    val folderTrashActions: Map<UUID, () -> Unit> = trashActionFolders.associate { folder ->
        folder.id to trashFolderAction(folder, subtreeSizes[folder.id] ?: 0, state, viewModel, dragSession)
    }
    val trashDraggedFolder: (UUID) -> Unit = { id -> folderTrashActions[id]?.invoke() }

    /**
     * Dropping items on the dock reuses the per-item trash path, one at a time.
     *
     * Not a new bulk operation: `moveToTrash` already carries the undo, the notice and the restore,
     * and a second implementation of "trash these" is a second place for those three to drift.
     */
    val trashDroppedItems: (ItemsDragPayload) -> Unit = { payload ->
        payload.ids.forEach { id ->
            viewModel.moveToTrash(id, movedToTrashNotice, undoNoticeLabel, restoredNotice)
        }
    }

    /**
     * Every writing drop goes through here, so the undo, the error path and the in-flight block
     * cannot differ between the five targets.
     *
     * `committing` is set before the write and cleared by the ViewModel once `refresh()` has run:
     * a second drag started in between would hit-test against target bounds the list has not
     * relaid out yet, and land the payload where those bounds used to be.
     */
    val commitDrag: (LkDragPayload, UUID?) -> Unit = { payload, target ->
        dragSession.committing = true
        viewModel.commitDrag(
            payload = payload,
            targetFolderId = target,
            movedNotice = when (payload) {
                is FolderDragPayload ->
                    LkNotices.Message.Text(if (target == null) dragUnfiledFolderNotice else dragMovedFolderNotice)
                else -> LkNotices.Message.Plural(R.plurals.vaultlist_drag_moved_items, payload.count)
            },
            undoLabel = dragUndoLabel,
            undoneNotice = dragUndoneNotice,
        ) { dragSession.committing = false }
    }

    /**
     * [moveRequestKey] rebuilt as a payload, so the accessible route and the drag commit through
     * exactly the same call.
     *
     * That sameness is the point: the undo, the notice, the error path and the acceptance rules are
     * written once. An accessible equivalent that re-implemented the move would be a second set of
     * all four, and the one nobody exercises by hand is the one that rots.
     */
    val moveRequest: LkDragPayload? = remember(moveRequestKey) {
        val key = moveRequestKey ?: return@remember null
        val id = runCatching { UUID.fromString(key.substring(2)) }.getOrNull() ?: return@remember null
        if (key.startsWith("f:")) FolderDragPayload(id) else ItemsDragPayload(setOf(id))
    }


    // A landscape phone is about 411dp tall and the keyboard takes some 260dp of it. Measured on
    // this screen rotated with the search field focused: the field's own top sat at y=389 against
    // an IME edge at y≈394, so the user saw the top 15dp of the well, none of the text they were
    // typing and none of the matches. The y-origin was *identical* with the keyboard up and down,
    // which is what says covered rather than scrolled — nothing moved out of the way, because
    // nothing here can. `MainActivity` insets by the system bars and the cutout and deliberately
    // not by the IME (a bounded column that cannot scroll does not clip under `safeDrawing`, it
    // crushes), leaving every screen to say for itself what has to stay above the keyboard.
    //
    // This screen says: the search field and the results. The rest of the header — the title row
    // and the count — is chrome, and in a window this short it is either the chrome or the thing
    // the user is actually doing. 480dp is Material's own compact-height breakpoint, so no phone
    // in portrait and no tablet in any orientation is touched by this.
    val shortWindow = LocalConfiguration.current.screenHeightDp < 480
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0


    /**
     * The title row yields in a short window whether or not the keyboard is up.
     *
     * Measured on the emulator in landscape at `font_scale 2.0`, 2026-08-30: fixed chrome took 955
     * of 1080px — status bar 74, title row 126, search 167, chips 100, bottom bar 173, gesture nav
     * 89 — leaving the list a **125px viewport**, under one row. A row's layout bounds then extend
     * past the viewport, so it paints a 17px sliver and a tap at its own reported centre lands
     * outside the scroll area entirely: invisible *and* untappable.
     *
     * Only the title row yields. The filter controls are not chrome — hiding them whenever the
     * window is short would take filtering away from anyone holding the phone in landscape, which
     * is a worse defect than the one being fixed — so they sit in the search row, which is never
     * dropped, rather than in this header. That row used to be a separate chip strip gated on the
     * keyboard; the chips went on 2026-09-02, in favour of the filter bar,
     * which also returned their 100px to the landscape window in the unfiltered case.
     *
     * What yields is the *stacked* header — 21sp title over a count line, 126px of it. The Menu and
     * Lock buttons stay, and since 2026-09-16 so do the title and the count: the `else` branch below
     * carries all four on one line, the title at [LkText.cardTitle] and the count at
     * [LkText.subtitle]. Dropping the buttons too is what this screen did until 2026-09-11, and it
     * took the drawer's only visible entry point with it: nine destinations and the lock-on-demand
     * button were unreachable in landscape, with no edge-swipe fallback because the system back
     * gesture claims that gesture first. Dropping the title and count was the 2026-09-11 fix's own
     * remaining cost, and F-34 is what named it — a landscape vault list was the only screen in the
     * app with no name on it, and the count line is the only place the vault's size is stated.
     *
     * This still recovers most of the 126px: one row of text between two buttons that were being
     * drawn anyway costs nothing the touch target was not already spending. It does not, on its own,
     * make the list comfortable at font scale 2.0 in landscape; the remaining answer was a header
     * that scrolls with the list, which it now does (SCL-007, [VaultListBody]) — this compact row
     * stays because a shorter header is still more room for rows before the first scroll.
     *
     * The filter controls below are untouched — they are not chrome.
     */
    val compactTitle = shortWindow

    // Drop the keyboard the moment a reveal lands.
    //
    // `LkSearchField` clears focus on the Search key, and that is the only path that
    // ever did. A reveal does not go through it: it fires from the debounce timer
    // inside `maybeAttemptReveal`, which clears `searchQuery` itself. So the phrase
    // matched, the list rebuilt behind the IME, and the caret stayed blinking in a box
    // that had already emptied — the one moment in the app where the user has
    // unambiguously finished typing, and the only one that kept the keyboard up.
    //
    // Both calls, deliberately. `clearFocus()` is what actually ends the editing
    // session, and it usually takes the IME with it; `hide()` alone would leave the
    // field focused and the keyboard one tap from returning. The explicit `hide()` is
    // the belt to that braces — this app has already learned twice that an IME which
    // looks dismissed can still report `mInputShown=true`.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.revealSessionActive) {
        if (state.revealSessionActive) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }
    // And the moment a selection starts. The bulk bar floats at the bottom with no IME
    // inset, so a search field still focused from before left the keyboard over Move,
    // Tag and Trash — a tap aimed at Trash typed a letter into the search (device pass,
    // release build, 2026-10-04). Here rather than beside the bar so it also runs in a
    // short window, where the actions ride in the header and the keyboard covers the
    // list instead. Search stays usable mid-selection; tapping it brings the keyboard
    // back by choice.
    LaunchedEffect(state.selecting) {
        if (state.selecting) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    LkScreen(accent = c.jade, modifier = Modifier.lkDragTracker(dragSession)) {
        // The header, the breadcrumb and the error line are the first rows of the list, not a
        // column above it. They used to sit outside any scroll container, so at the narrowest
        // window with the largest text — German, 308dp at 2.0, a type chip on — they took the
        // whole viewport and the rows had 0dp (SCL-007). Now the one list scrolls, the
        // breadcrumb sticks to its top, and `imePadding()` on it bounds the *scrolling* region
        // to the space above the keyboard, which is what a bounded region is for: it scrolls
        // where the old pinned column would have crushed the search well (the 126px-to-42px
        // failure MainActivity records from onboarding step 2). See [VaultListBody].
        /**
         * T6. The folder being browsed, as a place to drop.
         *
         * The five targets in the design are all somewhere *else* — a folder row, a crumb, the
         * parent, the bin, the root. There was no target for **here**, which is the one a
         * spring-load lands you on: rest on a folder, it opens with the payload still in hand,
         * and then releasing did nothing at all. In an empty folder that was a dead end — no
         * rows, and the parent and unfile strips live in the populated branch — so the only
         * thing a held item could do was go in the bin. Reported from a phone, 2026-08-18:
         * "I'm inside that folder and the item is not there."
         *
         * Registered on the body, so it covers the list and the empty panel alike, and first, so
         * every other target composes after it and wins the overlap by ordinal. The folder is
         * read from the ViewModel **at drop time** rather than captured: this target is
         * registered once and outlives the navigations a spring-load causes, so a captured id
         * would be whichever folder the drag started in.
         */
        // `weight(1f)`, not `fillMaxSize()`. In a Column `fillMaxSize` asks for the *parent's*
        // height rather than what is left of it — the trap `GeneratorScreen.kt:135` already
        // carries a comment about — so this Box, and everything measured inside it, overflowed
        // the window by exactly the height of the header above (found 2026-08-27).
        //
        // Not a free change, and what it moves is the point. `listBounds` — the LazyColumn's
        // own rect, which drag autoscroll reads to find the edge of the viewport — was measured
        // inside the overflowing box, so autoscroll's bottom edge sat below the screen and the
        // list had to be dragged past the visible end before it began to scroll. `weight(1f)`
        // is the true remainder, so that edge is now where the list actually ends. It also puts
        // `imePadding()` back on its feet: subtracting the keyboard from a box that overflows
        // subtracts the whole IME rather than the part that overlaps, which is what took the
        // search well from 126px to 42px the one time bounding was tried from the inside.
        Box(
            Modifier
                .fillMaxSize()
                .lkDropTarget(
                    key = LkDropTargetKey("drop-here"),
                    session = dragSession,
                    accepts = { payload -> viewModel.backgroundAcceptanceFor(payload) },
                    onDrop = { payload -> commitDrag(payload, viewModel.browsedFolderId) },
                ),
        ) {
            VaultListBody(
                listState = listState,
                onCrumbHeight = { crumbHeightPx = it },
                header = {
                    // The same header, re-labelled, rather than a second bar sliding in over it. What
                    // changes is what the screen is *for* right now, and the title is where that is
                    // said. Search and the chips stay put underneath deliberately: they are what
                    // "select all" means, so they have to remain visible and usable while selecting.
                    if (state.selecting) {
                        val selectionTitle = pluralStringResource(
                            R.plurals.vaultlist_selection_count_title,
                            state.selectedIds.size,
                            state.selectedIds.size,
                        )
                        // A filter change can now leave part of the selection off screen (see
                        // VaultListViewModel.refresh) — this is the one place that says so, so it has
                        // to be announced whenever the hidden count moves, not just read visually.
                        val hiddenSubtitle = state.hiddenSelectionSubtitle
                        val selectionSubtitle = if (hiddenSubtitle != null) {
                            pluralStringResource(
                                hiddenSubtitle,
                                state.hiddenSelectedCount,
                                state.hiddenSelectedCount,
                            )
                        } else {
                            stringResource(R.string.vaultlist_selection_subtitle)
                        }
                        VaultTitleBar(
                            modifier = Modifier.announce("$selectionTitle. $selectionSubtitle"),
                            title = selectionTitle,
                            subtitle = selectionSubtitle,
                            leading = {
                                LkIconButton(
                                    icon = LkIcons.Close,
                                    label = stringResource(R.string.vaultlist_selection_cancel),
                                    onClick = viewModel::clearSelection,
                                    plateAlignment = Alignment.CenterStart,
                                )
                            },
                            trailing = {
                                // In a short window the bulk actions ride here rather than floating over
                                // a list that has no room for them — see [HeaderBulkActions].
                                if (shortWindow) {
                                    HeaderBulkActions(
                                        count = state.selectedIds.size,
                                        working = state.bulkEditRunning,
                                        onMove = { showMoveSheet = true },
                                        onTag = { showTagDialog = true },
                                        onTrash = { confirmBulkTrash = true },
                                    )
                                }
                                LkIconButton(
                                    icon = LkIcons.Check,
                                    label = if (state.allVisibleSelected) {
                                        stringResource(R.string.vaultlist_selection_deselect_all)
                                    } else {
                                        stringResource(R.string.vaultlist_selection_select_all)
                                    },
                                    onClick = viewModel::selectAllVisible,
                                    tint = if (state.allVisibleSelected) c.jade else c.textMuted,
                                    plateAlignment = Alignment.CenterEnd,
                                )
                            },
                        )
                        // Gated here rather than around the whole block, and the difference is a
                        // regression this file already shipped once: wrapping both branches dropped the
                        // *selection* header too, and `selectAllVisible` has no other call site — so in
                        // any landscape window select-all became unreachable, and the "n selected" count
                        // invisible, while the user was mid-selection. Cancel survived only because
                        // BackHandler covers it. Only the ordinary title is chrome; the selection header
                        // is the mode's controls.
                    } else if (!compactTitle) {
                        VaultTitleBar(
                            // From the live filter, not the navigation argument. Reading the argument
                            // meant tapping "Logins" on the drawer's Cards destination left a screen
                            // titled "Cards" listing logins.
                            title = state.typeFilter?.let { stringResource(it.labelRes) }
                                ?: stringResource(R.string.vaultlist_screen_title),
                            // Always the count here. This read `if (shortWindow) null else …` until
                            // 2026-09-16, which was dead by construction — `compactTitle == shortWindow`,
                            // and this branch only runs when `!compactTitle` — and it was the line F-34
                            // was reading when it reported the count "dropped in landscape". The short
                            // window really does drop this header, and the count now travels with the
                            // title into the compact row below rather than being lost with it.
                            subtitle = state.countLabel(),
                            leading = {
                                // Visible entry to the command drawer — an edge swipe alone is
                                // undiscoverable.
                                LkIconButton(
                                    icon = LkIcons.Menu,
                                    label = stringResource(R.string.vaultlist_menu_open),
                                    onClick = onOpenDrawer,
                                    modifier = Modifier.lkTag("nav-open-drawer"),
                                    plateAlignment = Alignment.CenterStart,
                                )
                            },
                            // No `onLocked`: the button locks through `LocalVaultLocker` itself, and
                            // this screen has nothing to do afterwards. The generator and the security
                            // dashboard call it the same way. A parameter threaded down to a no-op
                            // reads as the hook for post-lock behaviour and is not one.
                            trailing = { LkLockButton() },
                        )
                    } else {
                        // A short window drops the *title*, never the two controls that live beside it.
                        // Dropping the whole header is what this screen used to do, and it cost more
                        // than chrome: the Menu button is the drawer's only visible entry point, and
                        // the drawer is the only route to Folders, Import, Backup, Restore, Invisible
                        // items, Duress, Recovery Kit, Trash and Settings — nine destinations — while
                        // LkLockButton is the only way to lock on demand. In landscape all ten became
                        // unreachable, and the usual fallback does not rescue it: an edge swipe from
                        // the left is taken by the system back gesture and leaves the app entirely
                        // (measured 2026-09-11 — the foreground package became the launcher).
                        //
                        // What made dropping the header look safe was that the *title* really is
                        // chrome at 21sp on two lines with a count under it. At 15sp on one line,
                        // between two buttons that were going to be drawn anyway, it costs nothing —
                        // the row's height is the touch target either way. So since 2026-09-16 (F-34)
                        // the title and the count line ride along here rather than disappearing: the
                        // landscape screen was the only one in the app with no name on it, and the
                        // count was the only statement of how large the vault is.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
                        ) {
                            LkIconButton(
                                icon = LkIcons.Menu,
                                label = stringResource(R.string.vaultlist_menu_open),
                                onClick = onOpenDrawer,
                                modifier = Modifier.lkTag("nav-open-drawer"),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    // From the live filter, not the navigation argument — the same
                                    // rule the full header follows, and for the same reason.
                                    text = state.typeFilter?.let { stringResource(it.labelRes) }
                                        ?: stringResource(R.string.vaultlist_screen_title),
                                    style = LkText.cardTitle,
                                    color = c.textStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.lkTag("vaultlist-compact-title"),
                                )
                                Text(
                                    text = state.countLabel(),
                                    style = LkText.subtitle,
                                    color = c.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.lkTag("vaultlist-compact-count"),
                                )
                            }
                            LkLockButton()
                        }
                    }

                    // Shown to the session that came in through the Recovery Kit, and only that one.
                    //
                    // The user has just proved they do not know the master password; the next lock asks
                    // for it again, or for the 24 words again. The reset exists and works, but it is two
                    // levels deep behind a row whose summary reads "Needs your current one" — which
                    // reads as "not for you" to precisely the person it is for. The route was built and
                    // the signpost never was (G3).
                    //
                    // Amber, not coral: `LkColors` reserves coral for a failure or a compromised
                    // environment, and nothing here is wrong. One tap takes the offer and dismisses it;
                    // it is gone for the rest of the session either way, since the flag is cleared on
                    // lock with everything else.
                    if (state.openedWithRecoveryKit) {
                        RecoveryKitSetPasswordNote(
                            onAction = {
                                viewModel.dismissRecoveryKitNote()
                                onSetNewMasterPassword()
                            },
                        )
                    }

                    // The search row and the active-filter strip under it move as one block, so a
                    // token arriving or leaving animates the list's edge rather than snapping it.
                    // `animateContentSize` is a one-shot with a cause — a filter changed — and it
                    // collapses to an instant swap under reduced motion.
                    val activeFilters = state.activeFilters()
                    // The strip lingers empty for a beat after its last token goes, and that is a
                    // touch-safety measure, not polish. A token's tap fires on touch-up; if the strip
                    // collapsed in the same frame, the first list row would slide up into the spot the
                    // finger just left, and the next tap — or a second finger still resting — lands on
                    // an item detail instead of on nothing. Adding a token is survivable; removing the
                    // last one was not, until the strip learned to wait.
                    var stripShown by remember { mutableStateOf(activeFilters.isNotEmpty()) }
                    LaunchedEffect(activeFilters.isNotEmpty()) {
                        if (activeFilters.isNotEmpty()) {
                            stripShown = true
                        } else {
                            delay(FilterStripLingerMillis)
                            stripShown = false
                        }
                    }
                    Column(
                        modifier = Modifier.animateContentSize(
                            if (reduceMotion) snap() else lkTween(LkMotion.Quick),
                        ),
                        verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
                    ) {
                        VaultSearchRow(
                            query = state.searchQuery,
                            onQueryChange = viewModel::onSearchChanged,
                            favouritesOn = state.showFavoritesOnly,
                            onToggleFavourites = viewModel::toggleFavorites,
                            sheetFilterOn = state.typeFilter != null || state.tagFilter != null || state.showHiddenOnly,
                            onOpenFilter = { showFilterSheet = true },
                            searchModifier = Modifier.focusRequester(searchFocus),
                            // The header scrolls with the list, so Filter can come to rest under the
                            // resting FAB: the row ends clear of it instead. Null for the whole of a
                            // scroll, like the rows' own dodge (SCL-007, P1-de first paint).
                            fab = { fabDodgeBounds(fabWindowBounds, listState.isScrollInProgress) },
                        )
                        if (stripShown) {
                            ActiveFilterStrip(
                                filters = activeFilters,
                                onRemove = { filter ->
                                    when (filter) {
                                        is ActiveFilter.Type -> viewModel.setTypeFilter(null)
                                        ActiveFilter.Favourites -> viewModel.toggleFavorites()
                                        is ActiveFilter.Tag -> viewModel.setTagFilter(null)
                                        ActiveFilter.Hidden -> viewModel.toggleHiddenOnly()
                                    }
                                },
                            )
                        }
                    }
                    if (state.revealSessionActive) {
                        RevealedSessionHeader(
                            secondsRemaining = state.revealSecondsRemaining,
                            onHideAgain = viewModel::hideAgainNow,
                        )
                    }
                },
                crumb = if (state.folderStack.isNotEmpty()) {
                    {
                    FolderBreadcrumb(
                        crumbTarget = { crumbId ->
                            Modifier.lkDropTarget(
                                key = LkDropTargetKey("crumb-${crumbId ?: "root"}"),
                                session = dragSession,
                                // Neutral, never Accepts: a crumb is a place to go, not a container
                                // to drop into, and an accepting ring would promise a write that
                                // pointing at a breadcrumb does not perform. Spring-load is the
                                // whole interaction.
                                accepts = { LkDropAcceptance.Neutral },
                                springLoad = {
                                    if (crumbId == null) {
                                        viewModel.openFolderPath(emptyList())
                                    } else {
                                        viewModel.openFolderPath(
                                            state.folderStack.take(state.folderStack.indexOf(crumbId) + 1),
                                        )
                                    }
                                },
                                onDrop = { },
                            )
                        },
                        path = state.breadcrumb,
                        onCrumb = { folder -> viewModel.openFolderPath(state.folderStack.take(state.folderStack.indexOf(folder.id) + 1)) },
                        onRoot = viewModel::closeToRoot,
                        modifier = Modifier.lkTag("vaultlist-breadcrumb"),
                    )
                    }
                } else {
                    null
                },
                notice = state.error?.let { errorRes ->
                    {
                    val message = stringResource(errorRes)
                    LkInlineError(
                        message = message,
                        // The component's own contract: an error that exists only visually is, to a
                        // screen reader, a button that did nothing. This call site never announced.
                        modifier = Modifier.announce(message),
                        // Only the read failure gets a control, because only it leaves the screen
                        // with nothing else to press — a failed trash or favourite leaves the row
                        // and its icon exactly where they were.
                        actionLabel = stringResource(R.string.vaultlist_error_retry).takeIf { state.loadFailed },
                        onAction = viewModel::retryLoad.takeIf { _ -> state.loadFailed },
                    )
                    }
                },
                // Bottom padding clears the floating bar and the FAB: the last row must be
                // fully readable when scrolled to the end, not tucked under the chrome.
                contentPadding = PaddingValues(
                    start = ListSideInset,
                    end = ListSideInset,
                    // The FAB's gutter at rest; the bulk bar's measured height while one is
                    // live, because the bar takes the FAB's place and is taller than it. The
                    // constant is right for the FAB and was simply inherited by the bar — at
                    // large font scales the three labels grow past it and the last row goes
                    // under the bar with no way to scroll it out. Measured rather than guessed,
                    // for the reason LkShellOverlays gives: a bottom-anchored control that
                    // guesses at the size of what is on top of it is a control that is wrong on
                    // some device. This is reach, not occlusion — a floating bar covers rows
                    // mid-scroll by design, and only the *end* of the list can be made safe.
                    bottom = listFootInset(
                        keyboardUp = keyboardUp,
                        selecting = state.selecting,
                        resting = bulkBarListInset(state.selecting, bulkBarHeightPx),
                    ),
                ),
                // The keyboard's bound is applied inside [VaultListBody] (`aboveKeyboard`), not here:
                // `imePadding()` on this node counted the keyboard twice over the shell's tab-bar
                // slot (L-02).
                modifier = Modifier.onGloballyPositioned { listBounds = it.boundsInRoot() },
                // The header is inside the whole-screen drop target now, and it must not be part of
                // it: letting go over the search field would file a dragged row into the browsed
                // folder, or unfile it at the root, by a gesture that was aimed at nothing. A
                // `Neutral` target of its own wins the overlap by ordinal and writes nothing.
                headerModifier = Modifier.lkDropTarget(
                    key = LkDropTargetKey("drop-header"),
                    session = dragSession,
                    accepts = { LkDropAcceptance.Neutral },
                    onDrop = { },
                ),
            ) {
                // Not `isEmpty()` alone: a failed read also produces an empty list, and an empty-vault
                // panel above a "couldn't read the vault" banner is the app asserting something it does
                // not know — on the one screen where an apparently empty vault reads as lost data.
                // Emptiness and nothing else. A second branch used to force this panel whenever the
                // Cards or Identities chip was on and the vault was not Pro — the binary gate those two
                // types carried until 2026-09-03, when each became a one-item free allowance instead
                // (`ProTypeQuota`). Left in place it hid rows the vault HOLDS: the projection keeps the
                // card, so a free user who had created their one allowed card, or imported twenty from
                // KDBX, read "1 item" in the header above a panel saying "No cards yet" and offering an
                // "Add a card" that then refused. In `foss` — `isPro` permanently false — that was
                // every user, always. `ProTypeQuota`'s invariant is the rule: the cap limits creation,
                // never import and never display, so anything enforcing it is a check at the add path
                // and never a filter on a list.
                // `hasScanned` joins the same family of "do not assert what has not been established"
                // terms as `loadFailed`. An unscanned list is empty by default, not by fact, so the
                // first composition after unlock — which happens before `scanAndPublish` lands on
                // Dispatchers.Default — asserted "Nothing here yet" about a vault it had not read. Not
                // a tier question and never one: it is a fact about this screen's own progress. 45-1.
                if (!state.loadFailed &&
                    state.hasScanned &&
                    state.items.isEmpty() &&
                    state.childFolders.isEmpty()
                ) {
                    item(key = "empty-vault") {
                        EmptyVault(
                            query = state.searchQuery,
                            // Folder presence is deliberately NOT folded in here any more. It used to be,
                            // which made "inside an empty folder" indistinguishable from "a filter matched
                            // nothing" — the empty folder said "No matching items / Clear filters", and
                            // Clear filters pops to root, which is the wrong verb for a folder that simply
                            // has nothing in it yet. `insideEmptyFolder` below is the real state.
                            filtered = state.showFavoritesOnly || state.typeFilter != null ||
                                state.tagFilter != null || state.showHiddenOnly,
                            insideEmptyFolder = state.folderStack.isNotEmpty() &&
                                state.searchQuery.isBlank() &&
                                !state.showFavoritesOnly &&
                                state.typeFilter == null &&
                                state.tagFilter == null &&
                                !state.showHiddenOnly,
                            onClear = {
                                viewModel.onSearchChanged("")
                                if (state.showFavoritesOnly) viewModel.toggleFavorites()
                                viewModel.setTypeFilter(null)
                                viewModel.setTagFilter(null)
                                if (state.showHiddenOnly) viewModel.toggleHiddenOnly()
                                // Pops to the root with the rest: "Clear filters" that leaves the user
                                // inside a folder is the control that made the empty state look broken in
                                // the first place.
                                viewModel.closeToRoot()
                            },
                            onBackToRoot = viewModel::closeToRoot,
                            // Compact while the keyboard is up, and that alone is the fix here. The panel
                            // is top-anchored, so its 96dp plate pushed the sentence and the "Search
                            // outside <Folder>" button — the only way out of a search that matched nothing
                            // where it was looking — down under the IME, where a tap at the button's own
                            // reported position appended a character to the query instead. Without the
                            // plate the whole panel ends around y=1500 against a keyboard edge at 1580, so
                            // nothing needs to be scrolled to and nothing needs to be bounded. Bounding it
                            // was tried and was worse at the time: the Box around it was `fillMaxSize` in a
                            // Column, so it overflowed the window and `imePadding` subtracted the whole
                            // keyboard rather than the part that actually overlaps — measured on the
                            // emulator, that cut the panel off mid-sentence. Found by the F03 QA persona,
                            // 2026-08-29. That Box takes the Column's remainder now, so the arithmetic
                            // would work; the panel still does not need it, and this stays unbounded.
                            compact = keyboardUp,
                            fab = { fabDodgeBounds(fabWindowBounds, listState.isScrollInProgress) },
                            // Scrolls with the list it is an item of — it used to carry its own
                            // `verticalScroll`, back when the region under a pinned header did not
                            // scroll and a state whose whole purpose is one button could not be
                            // allowed to push it off the bottom of a small phone. The list does that
                            // now, so a second scroller here would be a scroller inside a scroller.
                            // The bleed puts back the 12dp the list's own side padding takes: this
                            // panel was full-width and its copy wraps on that width.
                            modifier = Modifier
                                .bleedPast(ListSideInset)
                                // The same reservation the list makes, keyboard included. It was 24dp
                                // here against the list's 120dp, so the empty panel's own CTA — the only
                                // control it has — could come to rest under the FAB. Whether it did
                                // depended on how tall the panel was against the viewport, which is why it
                                // showed on a phone and not on this emulator (reported 2026-08-18). The
                                // keyboard half is the same defect a second time: searching inside a folder
                                // for something that lives elsewhere puts "Search outside <Folder>" — the
                                // only way out of that dead end — under the IME, where a tap at its own
                                // reported position lands in the search field and appends a character.
                                .padding(top = 48.dp, bottom = LkFrame.gutterFab),
                            typeFilter = state.typeFilter.takeIf {
                                !state.showFavoritesOnly && state.tagFilter == null && !state.showHiddenOnly
                            },
                            onAddFirst = { addSheetRestored = false; showAddSheet = true },
                            // Not from the sheet: this is the empty panel's own offer, so cancelling out of
                            // the editor returns to the panel rather than raising a chooser the user never
                            // opened.
                            onAddOfType = { type -> addOfType(type, false) },
                            matchesOutsideFilter = state.matchesOutsideFilter,
                            onSearchEverywhere = viewModel::searchEverywhere,
                            folderScope = state.activeFolder?.name,
                        )
                    }
                } else {
                    // T1. One drop target per folder row, plus the spring-load: rest on a folder for
                    // 600 ms and it opens with the payload still in hand, exactly as a tap would open
                    // it. A rejecting target does not spring-load — navigating somewhere the payload
                    // cannot land is a dead end.
                    val folderDropModifier: @Composable (Folder) -> Modifier = { folder ->
                        Modifier.lkDropTarget(
                            key = LkDropTargetKey("folder-${folder.id}"),
                            session = dragSession,
                            accepts = { payload -> viewModel.acceptanceFor(payload, folder.id) },
                            springLoad = { viewModel.openFolder(folder.id) },
                            onDrop = { payload -> commitDrag(payload, folder.id) },
                        )
                    }
                    val shownFolders = foldersShown(state.childFolders, foldersExpanded)
                    val foldedAwayFolders = foldersFoldedAway(state.childFolders.size, foldersExpanded)
                    // Navigation, above the results. Hidden while a query is running (see
                    // `childFolders`): a folder row under a search would be claiming a match the
                    // query does not have.
                    items(shownFolders, key = { "folder-${it.id}" }) { folder ->
                        val hovered = dragSession.hovered == LkDropTargetKey("folder-${folder.id}")
                        FolderBrowseRow(
                            folder = folder,
                            itemCount = state.folderItemCounts[folder.id] ?: 0,
                            onClick = { viewModel.openFolder(folder.id) },
                            disambiguateBy = state.disambiguatorFor(folder),
                            // The same FAB rect the item rows dodge. Folder rows never took it, so
                            // in landscape the resting FAB sat on top of a folder's count and
                            // chevron (2A-F13).
                            avoidanceBounds = {
                                fabDodgeBounds(fabWindowBounds, listState.isScrollInProgress)
                            },
                            dropTarget = folderDropModifier(folder),
                            dwellProgress = if (hovered) dwellProgress else 0f,
                            hoverState = when {
                                !hovered -> FolderRowHover.None
                                dragSession.hoveredAcceptance == LkDropAcceptance.Accepts ->
                                    FolderRowHover.Accepts
                                dragSession.hoveredAcceptance == LkDropAcceptance.Rejects ->
                                    FolderRowHover.Rejects
                                // NoOp reads as "nothing would happen", which is what no ring says.
                                else -> FolderRowHover.None
                            },
                            onLongPress = folderTrashActions[folder.id],
                            onMove = { moveRequestKey = "f:${folder.id}" },
                            onRename = { folderVm.startRenaming(folder.id) },
                            // Null, not disabled, when the rollback policy is off: the row's menu builds
                            // its Hide entry from this lambda's presence, so an emergency build
                            // simply does not offer the action. Stop hiding beside it is
                            // deliberately not gated — see MakeInvisibleAvailability.
                            onHide = if (MakeInvisibleAvailability.HIDE_ACTIONS_ENABLED) {
                                { viewModel.beginHide(HiddenTarget.Folder(folder.id), folder.name) }
                            } else {
                                null
                            },
                            hidden = folder.id in state.concealedFolderIds,
                            // Only a directly-leased folder can be un-hidden; an ancestry-concealed
                            // one carries the marker but no stop action that would do nothing.
                            onStopHiding = if (folder.id in state.directlyHiddenFolderIds) {
                                { viewModel.stopHiding(HiddenTarget.Folder(folder.id)) }
                            } else {
                                null
                            },
                            // Folders are single-drag (§4.1): there is no folder selection anywhere
                            // on this screen, so a plural folder payload would be a count the UI
                            // cannot produce.
                            onDragArmed = { grab, origin ->
                                dragSession.ghostOrigin = origin
                                ghostLabel = GhostLabel(folder.name, LkIcons.Folder)
                                dragSession.payload = FolderDragPayload(folder.id)
                                dragSession.grabOffset = grab - origin.topLeft
                                dragSession.fingerPosition = grab
                                dragSession.pendingMove = true
                            },
                            onDragStart = { grab, origin ->
                                dragSession.ghostOrigin = origin
                                dragSession.payload = FolderDragPayload(folder.id)
                                dragSession.grabOffset = grab - origin.topLeft
                                dragSession.fingerPosition = grab
                                dragSession.pendingMove = false
                            },
                            onDragCancel = { dragSession.clear() },
                            dragEnabled = viewModel.dragEnabled && !dragSession.committing,
                            modifier = Modifier
                                .padding(vertical = 1.dp)
                                .lkTag("vaultlist-folder-row"),
                        )
                    }
                    // The fold's one control, directly under the last folder row it shows.
                    if (folderOverflowVisible(state.childFolders.size)) {
                        item(key = "folder-overflow") {
                            FolderOverflowRow(
                                foldedAway = foldedAwayFolders,
                                expanded = foldersExpanded,
                                onToggle = { foldersExpanded = !foldersExpanded },
                                modifier = Modifier.lkTag("vaultlist-folder-overflow"),
                            )
                        }
                    }
                    // The quiet line for a folder that holds subfolders but no items of its own.
                    // Not the empty state: that would claim there is nothing here while listing
                    // several things.
                    if (state.hasOnlySubfolders) {
                        item(key = "folder-no-items") {
                            Text(
                                text = stringResource(R.string.vaultlist_folder_no_items),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LkTheme.colors.textMuted,
                                modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 6.dp),
                            )
                        }
                    }
                    // Counted over the rows this heading actually governs. The favourites run is
                    // a property of the folder you are in, not of everything under it.
                    val favourites = state.directItems.count { it.favorite }
                    val nestedStart = state.directItems.size
                    // Hoisted for exactly the reason the comment below gives for passing `index`
                    // rather than searching for it. Read inside the row lambda, `state.nestedItems`
                    // re-ran its getter — `items.filter { it.folderId != folderStack.last() }`, a
                    // whole pass over the scoped list plus a fresh List — once per composed row, to
                    // answer a question about emptiness. Answered once, here.
                    val hasNestedItems = state.nestedItems.isNotEmpty()
                    // itemsIndexed, because the index is already known here. Composing a row used
                    // to end with `state.items.indexOf(item)`, which walks the list comparing whole
                    // VaultItems — content, attachments and all — until it finds the one it was
                    // handed. Every row paid for a scan of the list to learn a number the lambda
                    // could have been given, and the cost of it grew with the square of the vault.
                    itemsIndexed(state.orderedItems, key = { _, item -> item.id.toString() }) { index, item ->
                        // Where the browsed folder's own rows end and its subtree's begin.
                        //
                        // A folder list shows the whole subtree, which is the right default — a
                        // parent reading "nothing here" while holding forty items one level down
                        // sends people looking for data they still have. What it never said was
                        // which rows were actually *here*: the only cue was a trail on the row, and
                        // rows without one looked no different from rows with one further down.
                        // Asked for on 2026-08-18 as a labelled section at the foot of the list.
                        if (hasNestedItems && index == nestedStart) {
                            LkSectionLabel(
                                stringResource(R.string.vaultlist_section_in_subfolders),
                                Modifier.padding(start = 10.dp, top = 16.dp),
                            )
                        }
                        // A single "All items" heading appears once the favourites run ends, so
                        // the pinned block reads as deliberate rather than as an arbitrary order.
                        // Suppressed when the favourites fill this folder exactly, which would
                        // otherwise stack it on the section label above at the same index.
                        if (favourites > 0 && favourites < nestedStart && index == favourites && state.searchQuery.isBlank()) {
                            LkSectionLabel(
                                stringResource(R.string.vaultlist_section_all_items),
                                Modifier.padding(start = 10.dp, top = 16.dp),
                            )
                        }
                        if (favourites > 0 && index == 0 && state.searchQuery.isBlank()) {
                            LkSectionLabel(
                                stringResource(R.string.vaultlist_section_favourites),
                                Modifier.padding(start = 10.dp, top = 8.dp),
                            )
                        }
                        val isRevealed = item.id == state.revealedItemId
                        VaultItemRow(
                            item = item,
                            // Resolved here because the row is given a VaultItem, which carries an
                            // id and not a name; the folder list lives on the list's own state.
                            folderName = state.folderTrailFor(item, ellipsis),
                            // The revealed row skips the entrance. It is by construction a row that
                            // has never been composed in this list — new, or hidden by the filters
                            // `revealItem` has just dropped — so `lkEnter` would fire on it, ramping
                            // the whole row's alpha and sliding it 10dp while the jade wash ramps
                            // inside it and the list animates a scroll to bring it into view. Three
                            // motion systems converging on the one row the eye is being sent to.
                            // The wash is its entrance.
                            //
                            // Said with `enabled`, never by swapping the call for a bare `Modifier`.
                            // That swap is what this line used to be, and it made the bug it was
                            // written to prevent arrive 1.4 s late instead: `clearReveal()` flips
                            // `isRevealed` back, the `lkEnter` branch composes for the first time on
                            // a row that has been on screen since the save, and its freshly
                            // remembered state restarts the entrance from alpha 0, ten dp low. The
                            // row the user had just created blinked out and jumped. Keeping the call
                            // unconditional keeps one `remember` for the life of the row, which is
                            // the only thing that makes "it already arrived" survivable. See
                            // [lkEnter].
                            modifier = Modifier.lkEnter(
                                delayMillis = lkStaggerDelay(index),
                                enabled = !isRevealed,
                            ),
                            selecting = state.selecting,
                            selected = item.id in state.selectedIds,
                            revealed = isRevealed,
                            onToggleSelected = { viewModel.toggleSelected(item.id) },
                            onStartSelecting = { viewModel.toggleSelected(item.id) },
                            onClick = {
                                if (item.content is File) {
                                    // Files are quota-limited, not Pro-gated (FILE.requiresPro =
                                    // false): a free vault legitimately holds up to
                                    // AttachmentQuota.FREE_LIMIT of them, and import/downgrade/
                                    // restore can put them there. Opening an item that already
                                    // exists satisfies any cap the editor would re-check, so open
                                    // it regardless of tier — the Pro dialog is for CARD/IDENTITY,
                                    // where requiresPro is actually true.
                                    viewModel.noteOpeningItem()
                                    val scope = state.folderStack.lastOrNull()?.let(MediaViewerScope::Folder)
                                        ?: if (state.searchQuery.isNotBlank()) {
                                            MediaViewerScope.GlobalSearch
                                        } else {
                                            MediaViewerScope.RootUnfiled
                                        }
                                    onFileItemClick(
                                        item.id.toString(),
                                        MediaViewerFocusRequest(
                                            scope = scope,
                                            visibleOrderedItemIds = state.orderedItems.map { it.id },
                                            projectionVersion = state.projectionVersion,
                                        ),
                                    )
                                } else {
                                    viewModel.noteOpeningItem()
                                    onItemClick(item.id.toString())
                                }
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(item.id) },
                            onTrash = {
                                viewModel.moveToTrash(item.id, movedToTrashNotice, undoNoticeLabel, restoredNotice)
                            },
                            onQuickCopy = { quick ->
                                quick.perform(context, clipboardPasswordLabel, clipboardTotpLabel, totpGenerateError)
                            },
                            // The FAB's window rect while the list is at rest, and null for the
                            // whole of a scroll — see [fabDodgeBounds]. A lambda over state the row
                            // reads only at placement, never at composition.
                            avoidanceBounds = {
                                fabDodgeBounds(fabWindowBounds, listState.isScrollInProgress)
                            },
                            // The whole selection when one is running and this row is in it,
                            // otherwise just this row. Grabbing a selected row and moving one item
                            // would read as the selection having been silently dropped.
                            // Arming and starting set up the same session; only `pendingMove`
                            // differs, and it is what stops a hold-and-release from dropping.
                            onDragArmed = { grab, origin ->
                                dragSession.ghostOrigin = origin
                                ghostLabel = GhostLabel(
                                    title = item.title.ifBlank { untitledLabel },
                                    icon = item.content.typeIcon(),
                                )
                                dragSession.payload = ItemsDragPayload(
                                    if (state.selecting && item.id in state.selectedIds) {
                                        state.selectedIds
                                    } else {
                                        setOf(item.id)
                                    },
                                )
                                dragSession.grabOffset = grab - origin.topLeft
                                dragSession.fingerPosition = grab
                                dragSession.pendingMove = true
                            },
                            onDragStart = { grab, origin ->
                                dragSession.ghostOrigin = origin
                                dragSession.payload = ItemsDragPayload(
                                    if (state.selecting && item.id in state.selectedIds) {
                                        state.selectedIds
                                    } else {
                                        setOf(item.id)
                                    },
                                )
                                // `grab` is already in root coordinates — the gesture's own parameter
                                // is named `grabInRoot`. Adding the origin to it a second time put
                                // the finger at roughly twice its real depth (measured on
                                // emulator-5554: a press at y=1830 in a row whose top was 1793 was
                                // recorded as y=3623), which drew the ghost off the top of the
                                // screen and made the auto-scroll band fire at full rate for the
                                // first frames of every drag. `grabOffset` is documented as the
                                // grab point's offset *inside* the ghost, so it is the difference.
                                dragSession.grabOffset = grab - origin.topLeft
                                dragSession.fingerPosition = grab
                                dragSession.pendingMove = false
                            },
                            onDragCancel = { dragSession.clear() },
                            dragEnabled = viewModel.dragEnabled && !dragSession.committing,
                            onMoveToFolder = { moveRequestKey = "i:${item.id}" },
                            onHide = if (MakeInvisibleAvailability.HIDE_ACTIONS_ENABLED) {
                                { viewModel.beginHide(HiddenTarget.Item(item.id), item.title.ifBlank { untitledLabel }) }
                            } else {
                                null
                            },
                            hidden = item.id in state.concealedItemIds,
                            // Only a directly-leased item can be un-hidden; an ancestry-concealed one
                            // (hidden because its folder is) carries the marker but no stop action.
                            onStopHiding = if (item.id in state.directlyHiddenItemIds) {
                                { viewModel.stopHiding(HiddenTarget.Item(item.id)) }
                            } else {
                                null
                            },
                            onSettings = {
                                if (item.content is File) {
                                    onFileSettingsClick(item.id.toString())
                                } else {
                                    onItemSettingsClick(item.id.toString())
                                }
                            },
                        )
                    }
                    // T5. "Unfile" needs a real target and the list's own background is not one:
                    // rows are spaced 2dp apart, an order of magnitude under the touch floor, and a
                    // populated vault fills the viewport so there is no background left to aim at.
                    // A 56dp strip at the end of the list, during a drag only.
                    //
                    // Only below the first level. One level down the parent *is* the root, so this
                    // strip and the "Move to the top level" pill perform the identical write, and
                    // both were on screen at once under two different names — reported 2026-08-18.
                    // The pill is the one that stays: it is the aimed target, and it is in reach.
                    if (dragSession.isMoving && state.folderStack.size > 1) {
                        item(key = "drag-unfile") {
                            DragDestinationStrip(
                                icon = LkIcons.FolderOpen,
                                label = stringResource(R.string.vaultlist_drag_remove_from_folder),
                                session = dragSession,
                                targetKey = LkDropTargetKey("drag-unfile"),
                                accepts = { payload -> viewModel.acceptanceFor(payload, null) },
                                onDrop = { payload -> commitDrag(payload, null) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        // The bulk bar takes the FAB's place rather than sitting alongside it: "add an item" is not
        // an action you want mid-selection, and two floating controls fighting for the same thumb
        // is how a stray tap adds a blank login to a vault you were about to re-file.
        // T3. The "move up a level" affordance the request asked for as a `<-` sign, present only
        // while a drag is in flight inside a folder. Unlike a crumb this one *takes* the drop and
        // ends the drag: it names a single destination — the parent — so there is nothing further
        // to aim at.
        //
        // **Floating opposite the bin, in neither the list nor the layout above it.** Three
        // placements were measured on a device and the first two both failed, for opposite reasons:
        //
        //  - *In* the list, as a `LazyColumn` item, it pushed every row down the instant the drag
        //    armed — moving the grabbed row out from under a finger that had not moved.
        //  - *Over* the list's first row it shifted nothing, and hid that row instead: `hitTest`
        //    resolves an overlap by registration order, so the row underneath could not be aimed at
        //    either. Reported from a phone on 2026-08-18 — two folders and an item, hold the item,
        //    and the upper folder is gone.
        //  - In the header under the crumbs it hid nothing and shifted the list by its own height,
        //    which sounds harmless and is not. On emulator-5554 the same day: grab the first row,
        //    jiggle 8px, release — the strip had arrived under the finger during the shift, and the
        //    item was unfiled to the top level by a gesture that never left the row it started on.
        //
        // Out of the flow there is nothing to shift and nothing to cover, and a target that never
        // moves cannot arrive underneath a stationary finger. It sits low for the same reason the
        // bin does: mid-drag the thumb is already committed, and the top of a tall phone is the one
        // place it cannot reach.
        if (dragSession.isMoving && state.folderStack.isNotEmpty()) {
            val parentId = state.folderStack.dropLast(1).lastOrNull()
            val parentName = parentId?.let { id -> state.folders.firstOrNull { it.id == id }?.name }
            DragDestinationStrip(
                icon = LkIcons.ChevronLeft,
                label = parentName
                    ?.let { stringResource(R.string.vaultlist_drag_to_parent, it) }
                    ?: stringResource(R.string.vaultlist_drag_to_root),
                session = dragSession,
                targetKey = LkDropTargetKey("drag-to-parent"),
                accepts = { payload -> viewModel.acceptanceFor(payload, parentId) },
                onDrop = { payload -> commitDrag(payload, parentId) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .imePadding()
                    // The end padding is the bin's column reserved, not a guess at a maximum width:
                    // 20dp of dock margin, its 48dp target and a 16dp gap. A folder name is
                    // arbitrary user text, so a fixed cap that clears the bin at 360dp wide fails on
                    // a 320dp phone; a reservation cannot, because it is subtracted from whatever
                    // width the screen turns out to have. The label ellipsizes into what is left.
                    .padding(start = 20.dp, end = 84.dp, bottom = 24.dp),
            )
        }

        if (state.selecting && !shortWindow) {
            BulkActionBar(
                count = state.selectedIds.size,
                working = state.bulkEditRunning,
                onMove = { showMoveSheet = true },
                onTag = { showTagDialog = true },
                onTrash = { confirmBulkTrash = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bulkBarHeightPx = it.height }
                    .padding(start = 16.dp, end = 16.dp, bottom = BulkBarBottomMargin),
            )
            // The branch leaving composition is the one moment a size callback cannot report, so the
            // list would otherwise keep reserving room for a bar that is gone.
            DisposableEffect(Unit) { onDispose { bulkBarHeightPx = 0 } }
        } else if (state.selecting) {
            // A short window: the actions are in the selection header and nothing floats here — not
            // the bar, and not the FAB it replaces either.
        } else if (dragSession.isMoving) {
            // T4. The dock takes the FAB's place rather than sitting beside it, for the reason the
            // bulk bar does the same: two floating controls fighting for the same thumb is how a
            // stray tap adds a blank login to a vault you were about to re-file. Mid-drag that
            // matters more, not less — the thumb is already committed to a gesture.
            //
            // `isMoving`, not `isDragging`: the bin belongs to a drag in motion, not to the hold.
            // A held row that is only opening its context menu must not flash a coral bin under a
            // stationary finger — the lift alone says "picked up", the bin waits for movement.
            TrashDock(
                session = dragSession,
                onDropItems = trashDroppedItems,
                onDropFolder = { payload -> trashDraggedFolder(payload.folderId) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .imePadding()
                    .padding(end = 20.dp, bottom = 24.dp),
            )
        } else {
            // Single, discoverable add entry point (§4.4). Sits over the list rather than in the
            // header so it stays in thumb reach on tall phones (§2 one-handed reach).
            //
            // Not while the IME is up, and that condition is the fix for a measured overlap rather
            // than a preference. The window does not resize for the keyboard, so the FAB lifted with
            // `imePadding()` — unbounded, because the `Box` it sits in has a fixed height and does
            // not clip. With a tall IME it rose clean out of the list and onto the folder strip:
            // measured on a Pixel 8 emulator, `folder-strip-add` is 126px tall with the keyboard
            // down and reports 46px with it up, the FAB covering the other 80. That is 63% of a
            // control barely above the 48dp floor. Found 2026-08-19.
            //
            // Hiding it is the smaller change than bounding the lift, and it is also the truer one:
            // the only thing that raises the IME here is the search field, and a user narrowing a
            // list is not adding to it.
            //
            // And not while the list is scrolling, which is the same argument on a second axis and
            // is what replaced the per-row dodge (F-12). See [addFabVisible].
            //
            // `AnimatedVisibility` rather than the bare `if` this used to be, and the animation is
            // the point rather than decoration: `isScrollInProgress` flips on every flick, and a
            // control that hard-cuts in and out on a repeatedly-flicked list reads as a rendering
            // fault rather than as a control getting out of the way. A 120ms scale-and-fade
            // (LkMotion.Instant, the shortest step the scale offers) is long enough to be read as
            // movement and short enough not to lag the thumb. The condition has to sit *inside* the
            // composable for the exit to have anything to play — an `if` that stops composing it
            // takes the animation with it — which is why this branch is now a plain `else`.
            //
            // Reduced motion needs no branch here: `lkTween` collapses to `tween(0)` when
            // `LocalLkReduceMotion` is set, so the swap is instant and the composition/disposal
            // still happens in the same order. That is the whole reason every animated surface in
            // this app routes through it rather than through a raw `tween`.
            val overlayInset = shellOverlayInset()
            val fabTapGuard = remember { FabTapGuard() }
            LaunchedEffect(overlayInset) { fabTapGuard.observe(overlayInset, SystemClock.uptimeMillis()) }
            AnimatedVisibility(
                visible = addFabVisible(keyboardUp = keyboardUp, listScrolling = listState.isScrollInProgress),
                // Scaled from its own centre, which is where the thumb is looking. It shrinks
                // towards the corner it lives in rather than sliding off an edge: a slide would
                // cross the rows it is trying to get out of the way of.
                enter = fadeIn(lkTween(LkMotion.Instant)) +
                    scaleIn(lkTween(LkMotion.Instant), initialScale = FabHideScale),
                exit = fadeOut(lkTween(LkMotion.Instant)) +
                    scaleOut(lkTween(LkMotion.Instant), targetScale = FabHideScale),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // The app no longer resizes its window for the keyboard (see MainActivity), so
                    // anything pinned to the bottom stays pinned to the bottom of the *screen* —
                    // which is under the IME once the search field has focus. The FAB lifts with it
                    // rather than disappearing behind it.
                    .imePadding()
                    .padding(end = 20.dp, bottom = 24.dp)
                    // And it lifts over the shell's floating overlays for the same reason: the
                    // clipboard countdown covered the FAB outright, putting its "Clear now" button
                    // exactly where Add had been a second earlier. Animated because the FAB has to
                    // arrive with the card it is avoiding, not before it.
                    .padding(bottom = animateDpAsState(overlayInset, lkTween(LkMotion.Base), label = "fab-overlay-lift").value),
            ) {
                // Gone means gone for the rows too: the branch leaving composition is the one moment
                // positioning callbacks cannot report, so the dodge would otherwise keep avoiding a
                // FAB that is no longer there.
                DisposableEffect(Unit) { onDispose { fabWindowBounds = null } }
                AddFab(
                    // Not while the FAB is still settling back out of a notice's slot: see [FabTapGuard].
                    onClick = {
                        if (fabTapGuard.accepts(SystemClock.uptimeMillis())) {
                            addSheetRestored = false
                            showAddSheet = true
                        }
                    },
                    folderName = state.activeFolder?.name,
                    modifier = Modifier.onGloballyPositioned { fabWindowBounds = it.boundsInWindow() },
                )
            }
        }

        // Last child of the root Box: above the list and the FAB, and never inside the LazyColumn,
        // which would clip it at the viewport edge and destroy it the moment the origin row
        // recycled — the ghost would vanish mid-drag with the finger still down.
        LkDragOverlay(dragSession) { payload ->
            DragGhost(payload = payload, label = ghostLabel)
        }

        if (showFilterSheet) {
            FilterSheet(
                state = state,
                // Closes on a pick: single-select has nothing further to say, and the strip under
                // the search field is where the choice is confirmed.
                onPickType = { type ->
                    viewModel.setTypeFilter(type)
                    showFilterSheet = false
                },
                onPickTag = { tag ->
                    viewModel.setTagFilter(tag)
                    showFilterSheet = false
                },
                onToggleHiddenOnly = {
                    viewModel.toggleHiddenOnly()
                    showFilterSheet = false
                },
                // The successor of the "All" chip, which owned both axes in its row: it drops the
                // type and Favourites together and pops to the root, so "clear all" means all.
                onClearAll = {
                    viewModel.showEveryType()
                    showFilterSheet = false
                },
                onDismiss = { showFilterSheet = false },
            )
        }
        if (addSheetVisible(showAddSheet, addSheetLeaving, reopenAddSheet)) {
            AddItemSheet(
                isPro = state.isPro,
                spentAllowances = spentAllowances(
                    cards = state.cardQuotaUsed.takeIf { state.cardQuotaFull },
                    identities = state.identityQuotaUsed.takeIf { state.identityQuotaFull },
                    attachments = state.attachmentQuotaUsed.takeIf { state.attachmentQuotaFull },
                ),
                restored = addSheetAlreadyOpen(addSheetRestored, reopenAddSheet),
                closing = addSheetClosing,
                // Reached two ways: the user dismissed the sheet themselves, or the exit that
                // [addSheetLeaving] started has finished. Both mean the same thing — nothing of the
                // sheet is on screen any more — so both clear the same pair of flags.
                onDismiss = {
                    showAddSheet = false
                    addSheetLeaving = false
                    addSheetClosing = false
                },
                // `showAddSheet` goes false here and not when the exit finishes, because it is the
                // saveable one: leaving it true would put the sheet back on top of the item the user
                // just saved. `addSheetLeaving` is what keeps the sheet drawn until it has gone.
                onSelect = { type ->
                    showAddSheet = false
                    addSheetLeaving = true
                    // Stays put when a screen is coming to replace it; plays its exit when the
                    // answer was a prompt instead, because then nothing covers it.
                    addSheetClosing = !addOfType(type, true)
                },
                onNewFolder = {
                    showAddSheet = false
                    addSheetLeaving = true
                    addSheetClosing = false
                    newFolderHere(true)
                },
            )
        }

        // Rename, as a dialog rather than the inline field FolderScreen's rows use. Same
        // ViewModel and therefore the same sibling-name check; a row on this screen has no spare
        // width to become a text field. Delete drives the same `folderVm`, whose promote-on-delete
        // plan this dialog surfaces exactly as FolderScreen's does.
        folderState.renamingId
            ?.let { id -> state.folders.firstOrNull { it.id == id } }
            ?.let { folder ->
                RenameFolderDialog(
                    folder = folder,
                    value = folderState.renameText,
                    onValueChange = folderVm::onRenameTextChanged,
                    onDismiss = folderVm::cancelRenaming,
                    onConfirm = folderVm::commitRename,
                    // Until 2026-09-13 this screen never read
                    // folderState.error, so renaming to a sibling's name failed with nothing on
                    // screen — see the dialog's own KDoc below.
                    error = folderState.error,
                )
            }

        folderState.pendingDeletionRow?.let { row ->
            LkDialog(
                onDismiss = folderVm::cancelDelete,
                title = stringResource(R.string.vaultlist_folders_delete_title, row.folder.name),
                body = folderDeleteBody(
                    row.itemCount,
                    hasSubfolders = folderState.folders.any { it.folder.parentId == row.folder.id },
                ),
                icon = LkIcons.Trash,
                accent = c.coral,
                confirm = LkDialogAction(
                    label = stringResource(R.string.vaultlist_folders_delete_confirm),
                    onClick = { folderVm.confirmDelete() },
                    emphasis = LkActionEmphasis.Destructive,
                ),
                dismissLabel = stringResource(R.string.vaultlist_folders_delete_dismiss),
            )
        }

        if (showMoveSheet) {
            BulkMoveSheet(
                folders = state.folders,
                count = state.selectedIds.size,
                onDismiss = { showMoveSheet = false },
                onSelect = { folderId ->
                    showMoveSheet = false
                    viewModel.bulkMoveToFolder(folderId, noBulkChangeNotice, undoNoticeLabel, dragUndoneNotice)
                },
                onCreateAndMove = { name ->
                    showMoveSheet = false
                    // Created under the folder being browsed, which is where the user is looking.
                    viewModel.createFolderAndMoveSelection(name, noBulkChangeNotice, undoNoticeLabel, dragUndoneNotice)
                },
            )
        }

        state.hideFlow?.let { flow ->
            HideFlow(
                flow = flow,
                onDismiss = viewModel::dismissHide,
                onPickDuration = { until -> viewModel.confirmHide(until, null) },
                onSubmitPhrase = { until, phrase -> viewModel.confirmHide(until, phrase) },
                onPhraseEdited = viewModel::onHidePhraseEdited,
            )
        }

        // The same sheet reached by a custom action instead of by a selection. No "new folder"
        // field here: that row creates under the *browsed* scope and moves the selection, which is
        // not what this caller is moving.
        moveRequest?.let { payload ->
            // Resolved once per payload and per projection, not on every recomposition the sheet
            // survives: for an item payload this reads the vault, and it used to do so once per
            // folder, inside composition (S1-08, 2026-10-02). `projectionVersion` is the key that
            // says the vault under it has changed.
            val destinations = remember(payload, state.folders, state.projectionVersion) {
                viewModel.moveDestinationsFor(payload)
            }
            BulkMoveSheet(
                // Destinations the drop would refuse are not offered. For an item nothing is ever
                // refused, so this is the whole tree; for a folder it removes itself, its own
                // subtree, and anything that would push it past MAX_DEPTH — the three rejections
                // §4.4 defines, read from the one function the hover already asks.
                folders = destinations,
                count = payload.count,
                heading = (payload as? FolderDragPayload)?.let { folderPayload ->
                    val name = state.folders.firstOrNull { it.id == folderPayload.folderId }?.name
                    name?.let {
                        stringResource(R.string.vaultlist_move_sheet_title_folder, it) to
                            stringResource(R.string.vaultlist_move_sheet_subtitle_folder)
                    }
                },
                onDismiss = { moveRequestKey = null },
                onSelect = { folderId ->
                    moveRequestKey = null
                    // Silently dropped when the destination would be a no-op, matching the drop:
                    // re-filing something where it already is should not cost a whole-vault
                    // re-encrypt, and it is not an error worth a message either.
                    if (viewModel.acceptanceFor(payload, folderId) == LkDropAcceptance.Accepts) {
                        commitDrag(payload, folderId)
                    }
                },
            )
        }

        if (showTagDialog) {
            BulkTagDialog(
                count = state.selectedIds.size,
                onDismiss = { showTagDialog = false },
                onConfirm = { tag ->
                    showTagDialog = false
                    viewModel.bulkAddTag(tag, noBulkChangeNotice)
                },
            )
        }

        if (confirmBulkTrash) {
            val count = state.selectedIds.size
            LkDialog(
                onDismiss = { confirmBulkTrash = false },
                title = pluralStringResource(R.plurals.vaultlist_bulk_trash_confirm_title, count, count),
                // The single-item trash does not ask at all, so a user who has learned that will
                // read this dialog as "this one is different" — and it is: the count is the whole
                // reason to stop. Say the reversal too, or the pause reads as a warning of loss.
                body = stringResource(R.string.vaultlist_bulk_trash_confirm_body),
                icon = LkIcons.Trash,
                accent = c.coral,
                confirm = LkDialogAction(
                    stringResource(R.string.vaultlist_bulk_trash_confirm_action),
                    {
                        confirmBulkTrash = false
                        viewModel.bulkTrash(noBulkChangeNotice, undoNoticeLabel)
                    },
                    emphasis = LkActionEmphasis.Destructive,
                ),
            )
        }

        quotaBlockedType?.let { blocked ->
            QuotaBlockedDialog(
                type = blocked,
                used = when (blocked) {
                    NewItemType.CARD -> state.cardQuotaUsed
                    NewItemType.IDENTITY -> state.identityQuotaUsed
                    else -> state.attachmentQuotaUsed
                },
                proPrice = state.proPrice,
                canPurchase = state.canPurchase,
                onUnlock = {
                    activity?.let { viewModel.unlockPro(it) }
                    quotaBlockedType = null
                },
                onDismiss = { quotaBlockedType = null },
            )
        }
    }
}

/** The FAB: the one place jade appears at full strength on the vault screen. */
@Composable
private fun AddFab(onClick: () -> Unit, modifier: Modifier = Modifier, folderName: String? = null) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .lkTag("fab-add-item")
            .size(58.dp)
            .lkPressScale(interaction, pressedScale = 0.93f, shape = LkShape.fab)
            .background(c.jadeFill, LkShape.fab)
            .lkFocusRing(LkShape.fab)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = rememberHapticClick(Haptic.SELECT, onClick),
            )
            // Names the destination when there is one. The FAB adds into the folder being browsed,
            // and a label that says only "Add item" hides the one thing about this tap that differs
            // from the same tap at the root.
            .labelled(
                folderName
                    ?.let { stringResource(R.string.vaultlist_fab_add_to_folder, it) }
                    ?: stringResource(R.string.vaultlist_add_item_label),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(LkIcons.Plus, contentDescription = null, tint = c.onJade, modifier = Modifier.size(23.dp))
    }
}

/**
 * The body under "No cards yet" and its siblings. Inside a folder "the rest of your vault" is wrong
 * twice over — the chip may be empty only *here*, and clearing it shows this folder's other items,
 * not the vault — so the folder gets its own sentence.
 */
@StringRes
internal fun emptyOfTypeBodyRes(inFolder: Boolean): Int =
    if (inFolder) R.string.vaultlist_empty_body_addable_in_folder else R.string.vaultlist_empty_body_addable

/**
 * The offer to set a new master password, shown once to a session opened with the Recovery Kit.
 *
 * The whole block is the tap target, and the action is **drawn** under the sentence rather than
 * living only as the click label TalkBack reads: a bare note read as information, so the one
 * sighted route to the reset looked like a message to dismiss and was never tapped. The drawn label
 * is decorative to the accessibility tree — the same words are already the click label.
 */
@Composable
internal fun RecoveryKitSetPasswordNote(onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    val action = stringResource(R.string.vaultlist_recovery_kit_set_password_action)
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = action, onClick = onAction)
            .lkTag("recovery-kit-set-password"),
    ) {
        LkInlineNote(
            message = stringResource(R.string.vaultlist_recovery_kit_set_password),
            accent = c.amber,
            icon = LkIcons.Key,
        )
        Box(
            Modifier.minTouchTarget().padding(horizontal = 14.dp).decorative(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text = action, style = LkText.buttonSmall, color = c.amber)
        }
    }
}

@Composable
internal fun EmptyVault(
    query: String,
    filtered: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * True when the empty list is explained by "this folder has nothing in it", not by a search or
     * a chip. Distinct from [filtered] on purpose (see the call site) — folding folder presence into
     * [filtered] is exactly what made an empty folder read as "No matching items / Clear filters".
     */
    insideEmptyFolder: Boolean = false,
    /** Where the "Back to all items" button goes. Only read when [insideEmptyFolder] is true. */
    onBackToRoot: () -> Unit = {},
    typeFilter: ItemTypeFilter? = null,
    onAddFirst: () -> Unit = {},
    onAddOfType: ((NewItemType) -> Unit)? = null,
    /** How many items the chips are hiding from this same query — see [VaultListUiState.matchesOutsideFilter]. */
    matchesOutsideFilter: Int = 0,
    onSearchEverywhere: (() -> Unit)? = null,
    /** The folder the search is currently confined to, or null at the root. */
    folderScope: String? = null,
    /** Drops the icon plate when the region is too short to hold it and the sentence — see [LkEmptyState]. */
    compact: Boolean = false,
    /** The resting FAB's window bounds, or null while it is hidden or the list is scrolling. */
    fab: () -> Rect? = { null },
) {
    val searching = query.isNotBlank()
    val narrowed = searching || filtered
    // The panel gives up its end to a resting FAB that is on top of it (SCL-037). The `gutterFab`
    // the caller reserves below the panel keeps its button off the FAB's row, and does nothing for a
    // window short enough that the copy sits *beside* the FAB: at 308x548dp the body ran under it
    // and lost the last ~48dp of its first two lines. Applied after the caller's padding, so the
    // panel is measured by its content and not by the reserve, and it is the same mechanism the
    // search row uses — `fab` is null for the whole of a scroll, so nothing reflows under a finger.
    val panel = modifier.clearOfFab(fab)
    // A scoped search that found nothing here, while the same words match elsewhere, is the one
    // empty state with a better answer than "clear everything and start again". It keeps the chip
    // honest — the list really does only contain what the chip says — and still admits that the
    // thing being looked for exists, which is the fact the old dead end withheld.
    val escalate = searching && matchesOutsideFilter > 0 && onSearchEverywhere != null

    // Checked before every other branch: a folder that is simply empty is not "no matches" (no
    // search ran, no chip narrowed anything) and is not "the vault has nothing" either — it is its
    // own state, with its own way out. `insideEmptyFolder` is only ever true alongside `filtered ==
    // false` and `typeFilter == null` (see the call site), so nothing below this would otherwise
    // fire for it besides the plain "empty vault" copy, which is the wrong sentence for a folder.
    if (insideEmptyFolder) {
        LkEmptyState(
                        compact = compact,
icon = LkIcons.Folder,
            title = stringResource(R.string.vaultlist_empty_folder_title),
            body = stringResource(R.string.vaultlist_empty_folder_body),
            modifier = panel.lkEnter(),
            action = {
                LkSecondaryButton(
                    text = stringResource(R.string.vaultlist_empty_folder_action),
                    onClick = onBackToRoot,
                    height = 40.dp,
                    modifier = Modifier.padding(horizontal = LkSpace.lg),
                )
            },
        )
        return
    }

    // A type chip on its own is not "no matches" — it is a type the vault has none of yet, which is
    // a different thing to say and the only one of these states that has a useful next step. The
    // spec calls for it by name ("no TOTP tokens yet"); the generic filter copy was shown instead.
    val emptyOfType = typeFilter?.takeIf { !searching }
    // Naming the type and offering to add one are separate questions. Passkeys are the type with no
    // add flow — they arrive from the credential provider — so that state still says "No passkeys
    // yet" rather than falling back to the generic filter copy, and simply offers the way out
    // instead of a button that would lead nowhere.
    val addableType = emptyOfType?.addable()?.takeIf { onAddOfType != null }
    val addFirstItemLabel = stringResource(R.string.vaultlist_empty_add_first_item)
    val addTypeLabel = addableType?.let { stringResource(it.addActionRes) }
    LkEmptyState(
                compact = compact,
icon = emptyStateIcon(addableType, emptyOfType, narrowed),
        title = when {
            emptyOfType != null -> stringResource(emptyOfType.emptyTitleRes)
            narrowed -> stringResource(R.string.vaultlist_empty_no_matches)
            else -> stringResource(R.string.vaultlist_empty_vault_title)
        },
        body = when {
            escalate -> pluralStringResource(
                R.plurals.vaultlist_empty_body_search_elsewhere,
                matchesOutsideFilter,
                query,
                matchesOutsideFilter,
            )
            searching && filtered ->
                stringResource(R.string.vaultlist_empty_body_search_filtered, query)
            searching -> stringResource(R.string.vaultlist_empty_body_search, query)
            addableType != null -> stringResource(emptyOfTypeBodyRes(inFolder = folderScope != null))
            // Named rather than "whatever has no add flow": this copy is about passkeys
            // specifically, and a future type that happened to fall into the same branch would
            // otherwise inherit a sentence that is not true of it. `EmptyStateLabelTest` pins
            // Passkey as the only filter without an add-sheet type.
            emptyOfType == ItemTypeFilter.PASSKEY -> stringResource(R.string.vaultlist_empty_body_passkey)
            filtered -> stringResource(R.string.vaultlist_empty_body_filtered)
            else -> stringResource(R.string.vaultlist_empty_body_default)
        },
        modifier = panel.lkEnter(),
        // The plate is the biggest thing on an empty screen and reads as the button; it was inert
        // on every addable category (2026-07-29). It now runs exactly what the button below runs.
        // Where there is genuinely nothing to add — a search with no matches, passkeys — it stays
        // decorative rather than advertising a click it cannot honour. The plain empty vault is
        // also decorative: the labeled button below is the single clear CTA for that state.
        onIconTap = when {
            addableType != null -> ({ onAddOfType?.invoke(addableType); Unit })
            emptyOfType == null && !narrowed -> null  // Plain empty vault - icon is decorative only
            !narrowed -> onAddFirst  // Other non-narrowed case (filtered type with no addable)
            else -> null
        },
        iconTapLabel = when {
            addableType != null -> addTypeLabel
            emptyOfType == null && !narrowed -> null  // Plain empty vault - no icon label
            !narrowed -> addFirstItemLabel  // Other non-narrowed case
            else -> null
        },
        // Named for whichever thing actually emptied the list. It said "Clear search" whatever the
        // cause, so a user who had only tapped filter chips was offered a button about a search
        // they never typed — it did clear the filters, which is the worst version of the bug: the
        // way out was there and read as irrelevant.
        // The empty vault had no action at all, relying on the FAB being noticed. The spec asks
        // for one clear CTA here, and this is the screen a new user lands on with nothing to look at.
        action = when {
            addableType != null -> {
                {
                    LkSecondaryButton(
                        text = addTypeLabel.orEmpty(),
                        onClick = { onAddOfType?.invoke(addableType) },
                        height = 40.dp,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            }
            // Widening the search comes before clearing it: the user's words are the thing they
            // still want, and "Clear search and filters" throws them away to escape a dead end.
            escalate -> {
                {
                    LkSecondaryButton(
                        // Names the scope when there is one. "Search everywhere" from inside a
                        // folder does not say what it is leaving, and the action also pops the
                        // breadcrumb — a button that moves the user has to say where from.
                        text = folderScope
                            ?.let { stringResource(R.string.vaultlist_search_outside_folder, it) }
                            ?: stringResource(R.string.vaultlist_empty_search_everywhere),
                        onClick = { onSearchEverywhere?.invoke() },
                        height = 40.dp,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            }
            narrowed -> {
                {
                    LkSecondaryButton(
                        text = when {
                            searching && filtered -> stringResource(R.string.vaultlist_empty_clear_search_filters)
                            searching -> stringResource(R.string.vaultlist_empty_clear_search)
                            else -> stringResource(R.string.vaultlist_empty_clear_filters)
                        },
                        onClick = onClear,
                        height = 40.dp,
                        modifier = Modifier.padding(horizontal = LkSpace.lg),
                    )
                }
            }
            else -> {
                {
                    LkSecondaryButton(
                        text = addFirstItemLabel,
                        onClick = onAddFirst,
                        height = 40.dp,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            }
        },
    )
}

/**
 * The three things worth doing to a set of items, floating where the FAB was.
 *
 * Move and Tag before Trash, with a gap before it and coral on it: the destructive action must not
 * sit under the thumb that just finished tapping rows. Every label is spelled out rather than
 * icon-only — this bar appears rarely enough that nobody will have learned its glyphs, and it is the
 * one place in the app where a mis-tap acts on two hundred items at once.
 */
@Composable
private fun BulkActionBar(
    count: Int,
    working: Boolean,
    onMove: () -> Unit,
    onTag: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lkSurface(LkShape.card, c.sheetTop, c.ringStrong)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .lkEnter(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The one in-flight indicator on this screen: a bulk action re-encrypts the whole vault,
        // and with the bar looking identical during the commit, a user re-tapping an action got
        // nothing — the guard swallowed it silently.
        if (working) {
            Text(
                stringResource(R.string.vaultlist_bulk_working),
                style = LkText.subtitle,
                color = c.textSecondary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            )
        } else {
            BulkAction(
                icon = LkIcons.Folder,
                label = stringResource(R.string.vaultlist_bulk_move_label),
                description = pluralStringResource(R.plurals.vaultlist_bulk_move_description, count, count),
                onClick = onMove,
                modifier = Modifier.weight(1f),
            )
            BulkAction(
                icon = LkIcons.Tag,
                label = stringResource(R.string.vaultlist_bulk_tag_label),
                description = pluralStringResource(R.plurals.vaultlist_bulk_tag_description, count, count),
                onClick = onTag,
                modifier = Modifier.weight(1f),
            )
            BulkAction(
                icon = LkIcons.Trash,
                label = stringResource(R.string.vaultlist_bulk_trash_label),
                description = pluralStringResource(R.plurals.vaultlist_bulk_trash_description, count, count),
                onClick = onTrash,
                tint = c.coral,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * [BulkActionBar]'s three actions as header icon buttons, for a window too short to float the bar.
 *
 * Landscape left the list about 300px under the header, search and tab bar, and the floating bar
 * with the room the list reserves for it took all of it: no row visible or tappable while selecting
 * (QA 2026-10-03). A short window is a wide one, so the header row has the room the list does not.
 * Same names and the same in-flight guard as the bar.
 */
@Composable
private fun HeaderBulkActions(
    count: Int,
    working: Boolean,
    onMove: () -> Unit,
    onTag: () -> Unit,
    onTrash: () -> Unit,
) {
    LkIconButton(
        icon = LkIcons.Folder,
        label = pluralStringResource(R.plurals.vaultlist_bulk_move_description, count, count),
        onClick = onMove,
        enabled = !working,
    )
    LkIconButton(
        icon = LkIcons.Tag,
        label = pluralStringResource(R.plurals.vaultlist_bulk_tag_description, count, count),
        onClick = onTag,
        enabled = !working,
    )
    LkIconButton(
        icon = LkIcons.Trash,
        label = pluralStringResource(R.plurals.vaultlist_bulk_trash_description, count, count),
        onClick = onTrash,
        enabled = !working,
        tint = LkTheme.colors.coral,
    )
}

@Composable
private fun BulkAction(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LkTheme.colors.textSecondary,
) {
    val c = LkTheme.colors
    val haptics = rememberHaptics()
    Column(
        modifier = modifier
            .clip(LkShape.lg)
            .clickable {
                haptics.play(Haptic.SELECT)
                onClick()
            }
            .labelled(description)
            // 56dp, comfortably past the 48dp floor: this is a bar of three adjacent targets and
            // the third one is destructive.
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Text(label, style = LkText.caption, color = if (tint == c.coral) c.coralText else c.textSecondary)
    }
}

/**
 * Where to file the selection.
 *
 * "No folder" is offered first and always, not only when folders exist: taking items *out* of a
 * folder is as much a bulk edit as putting them in, and it was otherwise unreachable in bulk. The
 * sheet does not offer to create a folder — that lives in the item editor, and duplicating it here
 * means two creation paths to keep honest for one that is rarely wanted mid-selection.
 */
@Composable
private fun BulkMoveSheet(
    folders: List<Folder>,
    count: Int,
    onDismiss: () -> Unit,
    onSelect: (UUID?) -> Unit,
    /** Creates a folder under the list's current scope and moves into it. Null disables the row. */
    onCreateAndMove: ((String) -> Unit)? = null,
    /**
     * Overrides the "Move N items" heading and its subtitle, for the one caller moving a folder.
     *
     * A folder move borrows this sheet wholesale — same tree, same indents, same per-row labels —
     * and only the two lines at the top are wrong for it. Passing them in beats a second sheet that
     * would have to be kept in step with this one every time the tree's rendering changes.
     */
    heading: Pair<String, String>? = null,
) {
    val c = LkTheme.colors
    var newFolderName by rememberSaveable { mutableStateOf("") }
    LkSheet(
        onDismiss = onDismiss,
        title = heading?.first
            ?: pluralStringResource(R.plurals.vaultlist_move_sheet_title, count, count),
        subtitle = heading?.second ?: stringResource(R.string.vaultlist_move_sheet_subtitle),
    ) {
        LkGroup {
            LkGroupRow(
                first = true,
                onClick = { onSelect(null) },
                modifier = Modifier.labelled(stringResource(R.string.vaultlist_move_no_folder_label)),
            ) {
                Text(stringResource(R.string.vaultlist_move_no_folder_text), style = LkText.rowTitle, color = c.textPrimary)
            }
            // The whole tree, indented, rather than the folder being browsed and its children:
            // a bulk move's destination is very often somewhere else entirely, and a sheet scoped
            // to where you already are cannot express that.
            bulkMoveRows(folders).forEach { (folder, depth) ->
                val siblings = folders.count {
                    it.parentId == folder.parentId && it.name.equals(folder.name, ignoreCase = true)
                }
                val parentName = folder.parentId
                    ?.takeIf { siblings > 1 }
                    ?.let { id -> folders.firstOrNull { it.id == id }?.name }
                val label = parentName
                    ?.let { stringResource(R.string.vaultlist_folder_row_disambiguated, folder.name, it) }
                    ?: folder.name
                LkGroupRow(
                    first = false,
                    onClick = { onSelect(folder.id) },
                    // Named per folder; a row of identical "Move" labels tells a screen-reader user
                    // nothing about which one they are on.
                    modifier = Modifier
                        .lkTag("bulk-move-folder")
                        .labelled(stringResource(R.string.vaultlist_move_to_folder_label, label)),
                ) {
                    if (depth > 0) Spacer(Modifier.width((depth * 14).dp))
                    Text(label, style = LkText.rowTitle, color = c.textPrimary)
                }
            }
        }
        // Somewhere to put the selection when the folder it belongs in does not exist yet. Without
        // it the user has to abandon the selection, go and make the folder, and rebuild it.
        onCreateAndMove?.let { create ->
            Spacer(Modifier.height(12.dp))
            LkTextField(
                value = newFolderName,
                onValueChange = { newFolderName = it },
                label = stringResource(R.string.vaultlist_move_new_folder),
                modifier = Modifier.fillMaxWidth().lkTag("bulk-move-new-folder"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { newFolderName.trim().takeIf(String::isNotEmpty)?.let(create) },
                ),
            )
        }
        if (folders.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            LkFootnote(stringResource(R.string.vaultlist_move_no_folders_hint))
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** One tag, added to everything selected. Additive — see VaultListViewModel.bulkAddTag. */
@Composable
private fun BulkTagDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Saveable, because the flag that shows this dialog already is (see the sibling flags near the
    // top of this file, made saveable so a bulk-delete confirmation could not silently vanish on
    // rotation). Leaving the text plain `remember` produced the worse half of that same bug: the
    // dialog came back after a rotate with an empty field and a disabled Add button, which reads as
    // the control being broken rather than as the text having been dropped.
    var tag by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val tagNameLabel = stringResource(R.string.vaultlist_tag_dialog_field_label)
    LkDialog(
        onDismiss = onDismiss,
        title = pluralStringResource(R.plurals.vaultlist_tag_dialog_title, count, count),
        body = stringResource(R.string.vaultlist_tag_dialog_body),
        icon = LkIcons.Tag,
        confirm = LkDialogAction(
            stringResource(R.string.vaultlist_tag_dialog_confirm),
            { onConfirm(tag) },
            enabled = tag.isNotBlank(),
        ),
    ) {
        // Same double-gap as RenameFolderDialog (fixed 2026-09-13):
        // this Spacer(4.dp) was a direct sibling of LkModalMasthead inside LkDialogSurface's
        // spacedBy(18.dp) Column, giving 18 + 4 + 18 = 40dp instead of 4dp. Dropped, relying on the
        // surface's own 18dp gap.
        LkTextField(
            value = tag,
            onValueChange = { tag = it },
            placeholder = tagNameLabel,
            label = tagNameLabel,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (tag.isNotBlank()) onConfirm(tag) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).lkTag("bulk-tag-dialog-field"),
        )
    }
}

/**
 * Task 6's shared name prompt — the add sheet's "New folder" row and the folder strip's own "+"
 * both open this, always aimed at the folder currently being browsed (or the root).
 */
/**
 * Making a folder, on the comp's folder screen rather than in a dialog.
 *
 * `Item Flows.dc.html` draws exactly one folder surface — a list of what exists, with the naming
 * card rising underneath it — and until 2026-08-19 the vault list did not use it. It raised
 * `NewFolderDialog` instead, a modal with a title, a body sentence and one text field. Two things
 * that dialog could not do, both of which the screen does for free:
 *
 * - **Show the folders that already exist.** A name is typed to be distinct from the others, and the
 *   dialog covered them. "A folder here is already called that" arrived only after the tap.
 * - **Let the user say where it goes.** The dialog fixed the parent to whichever folder was being
 *   browsed and stated it in prose ("At the top level" / "Inside Work"). Here that sentence is a row
 *   with a tick on it, and tapping another row moves it.
 *
 * The card is open on arrival and there is no "New folder" button, because the user tapped one to
 * get here — [LkFolderPickerScreen] expresses both by being handed a non-null name and no
 * `onCreateStart`. Cancel leaves rather than merely closing the card, for the same reason: on this
 * screen the card is the whole point, so abandoning it is abandoning the screen.
 *
 * **Making a folder does not leave.** It used to pop back to the list as soon as the write landed,
 * so ten folders were ten trips through the add sheet (2026-10-12: "What if I want to create 5 or
 * 10 folders or 20? Do I have to click every time?"). Now the new row appears, the field empties,
 * the keyboard goes away and the parent goes back to the top level (2026-10-15: "a small reset each
 * time one adds a new folder"), and the way out turns from Cancel into Done.
 * [onDismiss] is leaving before anything was made — it returns to the door the user came through;
 * [onDone] is leaving after — the list behind is the confirmation and nothing is re-raised over it.
 *
 * Every string is one the vault list already ships in all five languages; this added none.
 */
@Composable
fun NewFolderScreen(
    folders: List<Folder>,
    folderItemCounts: Map<UUID, Int>,
    initialParentId: UUID?,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    /** Makes the folder, then calls back with its id once it exists and the list has been re-read. */
    onCreate: (name: String, parentId: UUID?, onCreated: (UUID) -> Unit) -> Unit,
) {
    // Seeded from the folder being browsed — the parent the dialog used to assume — and then the
    // user's to change. rememberSaveable on both: a rotation here must not bin a half-typed name or
    // silently move the folder back to the top level.
    // Held as a String rather than a UUID so it saves without a custom Saver — the same trick
    // `moveRequestKey` above uses, and for the same reason: a rotation must not lose the choice.
    //
    // Clamped to a parent the list below actually offers. Browsing a folder on the deepest level
    // seeded the picker with a row it does not list (a ninth-level child is refused), so the screen
    // opened with nothing marked while the create quietly targeted — and was refused under — that
    // unlisted folder. The nearest listed ancestor is what is marked and used instead.
    //
    // The seed is *derived* until the user picks, not computed once. `folders` is loaded by a
    // ViewModel created with this destination, so the first composition sees an empty list; clamping
    // there found no ancestor, fell to the top level, and rememberSaveable kept that answer after the
    // tree arrived — a folder started inside the deepest one was created at the top level, silently
    // (D3-F-01). Re-derived on every composition, the seed settles on the nearest listed ancestor as
    // soon as the tree is there.
    var picked by rememberSaveable { mutableStateOf(false) }
    var pickedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val parentId = if (picked) {
        pickedKey?.let(UUID::fromString)
    } else {
        newFolderParentSeed(folders, initialParentId)
    }
    var name by rememberSaveable { mutableStateOf("") }
    // The folder most recently made on this visit. Presentation only, like the item editor's: it
    // flashes the new row, and "something was made" is what turns Cancel into Done. A rotation that
    // loses it falls back to Cancel, which still leaves.
    var arrivedFolderId by remember { mutableStateOf<UUID?>(null) }
    val leave = { if (arrivedFolderId != null) onDone() else onDismiss() }

    BackHandler(enabled = true) { leave() }

    // The whole tree, flat and indented — `rows` gates children on the expanded set, so passing every
    // id is how you ask it for all of them. Same walk the item editor's chooser uses, so the depth
    // cap, the name sort and the sibling disambiguation stay in the one place they are tested.
    // Collapsed ids are subtracted from that set, so the list still opens flat and only hides what
    // the user folds away — the chevrons added 2026-08-20 for trees deep enough that the flat list
    // is itself the problem.
    var collapsedFolders by rememberSaveable(stateSaver = LkCollapsedFoldersSaver) {
        mutableStateOf(emptySet<UUID>())
    }
    val allFolderIds = folders.mapTo(mutableSetOf()) { it.id }
    // Only parents that can still take a child — see [FolderPickerState.parentCandidates].
    val rows = FolderPickerState.parentCandidates(folders, allFolderIds - collapsedFolders).map { row ->
        val base = row.disambiguateBy
            ?.let { stringResource(R.string.vaultlist_folder_picker_subfolder, row.folder.name, it) }
            ?: row.folder.name
        LkFolderRow(
            id = row.folder.id,
            name = stringResource(
                R.string.vaultlist_folder_name_suffix,
                base,
                folderItemCounts[row.folder.id] ?: 0,
            ),
            depth = row.depth,
            hasChildren = row.hasChildren,
            expanded = row.folder.id !in collapsedFolders,
        )
    }

    // Siblings of the chosen parent only, and case-insensitively — the same comparison the create
    // performs, so this cannot say "fine" about a name the commit will refuse.
    val typed = name.trim()
    val status = when {
        typed.isEmpty() -> LkFolderNameStatus.Free
        folders.any { it.parentId == parentId && it.name.equals(typed, ignoreCase = true) } ->
            LkFolderNameStatus.TakenHere
        folders.any { it.name.equals(typed, ignoreCase = true) } -> LkFolderNameStatus.TakenElsewhere
        else -> LkFolderNameStatus.Free
    }

    val inside = stringResource(R.string.vaultlist_folders_new_in)
    LkFolderPickerScreen(
        rows = rows,
        selectedId = parentId,
        onPick = {
            picked = true
            pickedKey = it?.toString()
        },
        onBack = leave,
        onToggleExpanded = { id ->
            collapsedFolders = if (id in collapsedFolders) collapsedFolders - id else collapsedFolders + id
        },
        onCollapseAll = { collapsedFolders = allFolderIds },
        onExpandAll = { collapsedFolders = emptySet() },
        newFolderName = name,
        onNewFolderNameChange = { name = it },
        onCreateConfirm = {
            if (typed.isNotEmpty() && status != LkFolderNameStatus.TakenHere) {
                onCreate(typed, parentId) { createdId ->
                    name = ""
                    // The small reset (2026-10-15): the next folder starts from the top level again.
                    picked = true
                    pickedKey = null
                    arrivedFolderId = createdId
                }
            }
        },
        onCreateCancel = leave,
        newFolderStatus = status,
        arrivedId = arrivedFolderId,
        title = stringResource(R.string.vaultlist_add_new_folder),
        subtitle = stringResource(R.string.vaultlist_folders_new_parent_choose),
        // The root row keeps its place at the top of the list and changes only what it says: not
        // "None" — nothing is being un-filed — but where a folder with no folder above it sits.
        rootLabel = stringResource(R.string.vaultlist_folders_new_in_root),
        rootClickLabel = stringResource(R.string.vaultlist_folders_new_in_root),
        rowClickLabel = { inside.format(it) },
    )
}

/**
 * The parent the new-folder screen opens with: [initialParentId] when the picker lists it, else its
 * nearest ancestor that the picker does, else the top level (null).
 *
 * "Lists it" is [FolderPickerState.parentCandidates] — every folder except the ones already on the
 * deepest level, because a child of one of those would be a ninth level. An id that is not in
 * [folders] at all (a folder trashed while the screen was opening) walks up to the top level too.
 */
internal fun newFolderParentSeed(folders: List<Folder>, initialParentId: UUID?): UUID? {
    if (initialParentId == null) return null
    val byId = folders.associateBy { it.id }
    val offered = FolderPickerState.parentCandidates(folders, byId.keys).mapTo(HashSet()) { it.folder.id }
    var cursor: UUID? = initialParentId
    // Bounded by the tree's own depth cap, so a cycle in a damaged tree cannot spin here.
    var steps = 0
    while (cursor != null && cursor !in offered && steps++ <= FolderTree.MAX_DEPTH) {
        cursor = byId[cursor]?.parentId
    }
    return cursor?.takeIf { it in offered }
}

/**
 * Rename a folder from the vault list.
 *
 * Holds no name of its own — the text lives in [FolderViewModel], which is what performs the
 * sibling-clash check and surfaces its own error. A local `remember` here would be a second copy of
 * the same string, and the one the user was looking at would not be the one being validated.
 *
 * [error] is that same [FolderViewModel.state] field, rendered here the way `FolderScreen.kt`
 * renders it for its own rename row. Until 2026-09-13
 * this dialog never read it: `commitRename()` set the error on a sibling-name collision and
 * returned, and nothing on screen changed — the dialog just sat there with an enabled Confirm
 * button, indistinguishable from a rename that silently did nothing. `onValueChange` and `onDismiss`
 * already clear the field (`onRenameTextChanged` / `cancelRenaming` both null it out), so no extra
 * dismissal wiring was needed here.
 */
@Composable
internal fun RenameFolderDialog(
    folder: Folder,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    @StringRes error: Int? = null,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(folder.id) { focus.requestFocus() }
    LkDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.vaultlist_folders_rename_action, folder.name),
        icon = LkIcons.Folder,
        confirm = LkDialogAction(
            stringResource(R.string.vaultlist_folders_rename_confirm),
            onConfirm,
            enabled = value.isNotBlank(),
        ),
        dismissLabel = stringResource(R.string.vaultlist_folders_rename_cancel),
    ) {
        // The Spacer(4.dp) this content lambda opened with (removed 2026-09-13)
        // was a direct sibling of LkModalMasthead inside LkDialogSurface's own
        // spacedBy(18.dp) Column, not a scope of its own — the lambda's type is
        // `@Composable ColumnScope.() -> Unit`. That put 18 + 4 + 18 = 40dp between the masthead and
        // the field instead of the intended 4dp. Dropped, relying on the surface's own 18dp gap.
        error?.let { errorRes ->
            val message = stringResource(errorRes)
            LkInlineError(message, Modifier.announce(message))
        }
        LkTextField(
            value = value,
            onValueChange = onValueChange,
            label = stringResource(R.string.vaultlist_folders_rename_label, folder.name),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onConfirm() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).lkTag("rename-folder-field"),
        )
    }
}

/**
 * Shown when a free vault has no file-and-photo slot left (see [AttachmentQuota]).
 *
 * The app's only refusal dialog, and since 2026-09-04 the shape the item editor's backstop follows
 * too: amber, the refused type's own glyph, and the free remedy before the paid one.
 *
 * It replaced a `ProBlockedDialog` that said "Files & media — part of Pro" — true while files were
 * gated, and a straight lie the moment they became quota-limited, because it told someone who had
 * just stored five files that the feature they had been using was not available to them. It also
 * named the wrong remedy: this one has two, and deleting something is the free one, so it is stated
 * first. That dialog and the `ProBlocked` destination behind it were deleted on 2026-09-04, once
 * nothing could reach them.
 *
 * Named "Files & media" rather than "File" because the drawer calls the category that, and one
 * feature should not have two names.
 */
@Composable
private fun QuotaBlockedDialog(
    /**
     * What the user was refused.
     *
     * Three quotas share this dialog since 2026-09-03 — files and photos ([AttachmentQuota]), Cards
     * and Identity documents ([ProTypeQuota]) — because the sentence they need has the same shape:
     * a real count, a real cap, a free remedy stated before the paid one. What differs is the noun,
     * and a noun cannot be interpolated into a sentence across fr/de/es/pt, so the body is one key
     * per type while the title, an apposition, takes the category name as an argument.
     */
    type: NewItemType,
    /**
     * Slots actually in use. Both numbers in the sentence used to be [AttachmentQuota.FREE_LIMIT]
     * — the title read "5 of 5 used" whatever the vault held, and the body said "remove one to
     * make room" whether that was true or not. A vault above the cap (import never charges against
     * it, so that is a supported state) therefore answered every deletion with a byte-identical
     * refusal, and the one number that would have explained it was the one being withheld.
     */
    used: Int,
    canPurchase: Boolean,
    /** Play's localised price, or null for the no-amount wording. Never a literal. */
    proPrice: String?,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Title and body both keyed off the type, both with the category noun written into the string.
    // The title took the noun as a `%1$s` argument until the 3-vote check rejected it: a participle
    // cannot agree with a word it is handed at runtime, so French rendered "Cartes — 1 sur 1
    // utilisé" and Spanish "Tarjetas — 1 de 1 usado". Nothing here passes a category name any more.
    val titleRes = when (type) {
        NewItemType.CARD -> R.plurals.vaultlist_card_quota_blocked_title
        NewItemType.IDENTITY -> R.plurals.vaultlist_identity_quota_blocked_title
        else -> null
    }
    val bodyRes = when (type) {
        NewItemType.CARD -> R.plurals.vaultlist_card_quota_blocked_body_base
        NewItemType.IDENTITY -> R.plurals.vaultlist_identity_quota_blocked_body_base
        else -> null
    }
    val limit = if (bodyRes != null) ProTypeQuota.FREE_LIMIT else AttachmentQuota.FREE_LIMIT
    // Never below one, so the sentence always names a way forward — see AttachmentQuota.mustRemove
    // and ProTypeQuota.mustRemove, which are the same rule for the same reason.
    val mustRemove =
        if (bodyRes != null) ProTypeQuota.mustRemove(used) else AttachmentQuota.mustRemove(used)
    LkDialog(
        onDismiss = onDismiss,
        title = if (titleRes != null) {
            pluralStringResource(titleRes, used, used, limit)
        } else {
            pluralStringResource(R.plurals.vaultlist_quota_blocked_title, used, used, limit)
        },
        body = buildString {
            // Counted on what has to go, not on the cap: that is the number the reader acts on,
            // and it is the only one in the sentence that ever changes.
            append(
                pluralStringResource(
                    bodyRes ?: R.plurals.vaultlist_quota_blocked_body_base,
                    mustRemove,
                    mustRemove,
                    limit,
                    used,
                ),
            )
            // The upgrade is only ever offered where it can actually be completed. In `foss` there
            // is no purchase path at all (§N2), so naming one would send the user looking for a
            // button that does not exist.
            append(
                if (canPurchase) {
                    stringResource(R.string.vaultlist_quota_blocked_suffix_purchase)
                } else {
                    stringResource(R.string.vaultlist_quota_blocked_suffix_no_purchase)
                },
            )
        },
        // The refused type's own glyph. Hardcoded to Attachment while files were the only quota;
        // a card refusal wearing a paperclip names the wrong feature.
        icon = type.icon,
        // Amber, not coral: nothing has gone wrong and nothing was lost. See LkColors.
        accent = LkTheme.colors.amber,
        confirm = if (canPurchase) {
            LkDialogAction(
                proPrice
                    ?.let { stringResource(R.string.vaultlist_unlock_pro_action, it) }
                    ?: stringResource(R.string.vaultlist_unlock_pro_action_no_price),
                onUnlock,
                emphasis = LkActionEmphasis.Pro,
            )
        } else {
            LkDialogAction(
                stringResource(R.string.vaultlist_view_on_play_action),
                { context.openPlayListing() },
                emphasis = LkActionEmphasis.Pro,
            )
        },
        dismissLabel = stringResource(R.string.vaultlist_dialog_not_now),
    )
}

/**
 * @param passwordLabel,[totpLabel] the system clipboard UI's label for the copied value. Resolved
 *   by the caller — a composable — and passed in, per the repo's `stringResource`-in-a-handler
 *   trap: this function runs from a click callback, not composition.
 */
private fun QuickCopy.perform(
    context: Context,
    passwordLabel: String,
    totpLabel: String,
    totpGenerateError: String,
) {
    when (this) {
        is QuickCopy.Password -> ClipboardHelper.copy(context, passwordLabel, value)
        is QuickCopy.TotpCode -> {
            val code = runCatching { TotpGenerator.generate(totp) }.getOrNull()
            if (code != null) {
                ClipboardHelper.copy(context, totpLabel, code)
            } else {
                Toast.makeText(context, totpGenerateError, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * The count line under the title. Says what is on screen *and* where it lives.
 *
 * `internal` so the folder narrowing can be asserted directly. It resolves five string resources and
 * joins them, so the assertion has to happen in a composition; reaching it through the whole screen
 * would need an unlocked vault and a repository to say something true about one line of text.
 */
@Composable
internal fun VaultListUiState.countLabel(): String {
    // Nothing has been projected yet, so `items` is empty because it has not been filled — not
    // because the vault is. Saying "0 items" here is a claim about the user's vault, and on a slow
    // device with a large one it stands for seconds: on the API 29 image a 15,000-item vault showed
    // "0 items · encrypted with your vault password", with no rows and no empty-state illustration,
    // for five seconds after unlock before the real count appeared (G6, and the transient the
    // earlier sweep saw once on API 35 and could not reproduce — O1). It reads as "my vault is
    // empty", which is the worst sentence this app could show at that moment.
    //
    // `projectionVersion` is incremented on every successful scan and starts at 0, so zero means
    // "no projection has landed yet" — distinct from a projection that legitimately produced
    // nothing, which is the genuine empty state and has its own illustration and CTA.
    if (projectionVersion == 0L) return stringResource(R.string.vaultlist_count_opening)
    val n = scopeItemCount
    val count = pluralStringResource(R.plurals.vaultlist_count_items, n, n)
    // Names the narrowing when there is one. The chips are the control, but they scroll away with
    // the list: come back to an item from a filtered list you left ten minutes ago and the header
    // said "4 items" over what looks like the whole vault. The title already carries the type
    // filter, so this only has to add the axis the title cannot show.
    //
    // The folder is deliberately NOT in this list, and it used to be. While a folder was a filter
    // this line was the only thing naming it; now the breadcrumb sits directly above and names it
    // permanently, so repeating it here read as "filter: folder Work" over a screen with a Work
    // crumb on it — calling navigation a filter, and offering "clear filters" as the way out of a
    // place the user walked into. Found on a device, 2026-08-12.
    //
    // Favorites is the exception while its section heading is on screen: the chip, the heading and
    // "filter: favorites" made it three statements of one fact, and the heading is the one that
    // cannot scroll away with the chips. Kept whenever the heading is absent (no favourite, or a
    // search running), where this line is the only place naming the narrowing.
    val narrowed = listOfNotNull(
        stringResource(R.string.vaultlist_narrowed_favourites).takeIf { showFavoritesOnly && !favouritesHeadingShown },
        tagFilter?.let { stringResource(R.string.vaultlist_narrowed_tag, it) },
        stringResource(R.string.vaultlist_narrowed_hidden).takeIf { showHiddenOnly },
        stringResource(R.string.vaultlist_narrowed_search).takeIf { searchQuery.isNotBlank() },
    )
    // What the number counts: the whole vault at the root, the browsed folder's subtree inside one.
    // Principal's decision, 2026-09-16.
    //
    // This block used to argue the opposite, and the argument was good as far as it went: at the
    // root the list shows only what is filed nowhere, so a bare count of the *rows* reads as data
    // loss the moment everything is in folders — "0 items" over a vault holding four hundred — and
    // the folder suffix beside it was the rest of the answer. What that misses is that "5 folders"
    // does not say how much is in them. Measured on a device (F-31, 2026-09-15): a vault of 58
    // items with 13 of them filed announced itself as "45 items · 5 folders", and the same number
    // moved 41 → 45 across an import that had correctly reported 16 new items — so the header made
    // the import wizard look like it had lied. A count under a vault's own title is read as a
    // statement about the vault, not about the current viewport, and it has to be true as one.
    //
    // The folder suffix stays. It is now a second fact rather than a patch over a missing one —
    // "58 items · 5 folders" says how much there is and how it is arranged — and inside a folder
    // there are no root folders to name, so it drops out on its own.
    //
    // Not under Favorites, though: that view hides the folders (they are not favourites), so
    // counting them here would claim "5 folders" over a screen showing none — the same
    // contradiction the rows themselves used to be. The item count under Favorites is honest
    // either way, because [scopeItemCount] carries every narrowing the user set; only the folder
    // axis is relaxed at the root.
    //
    // Four branches rather than one composed value: ComposedPluralAgreementTest reads the source
    // for a plural reaching a format string, and following it through an intermediate variable is
    // beyond what it can see. Passing `count` straight into every call keeps all four keys visible
    // to it, which matters more than the two duplicated lines cost.
    // Through `rootFolders` — the filter-aware list — so the number matches the folder rows the
    // screen actually draws, including under a type chip, where a raw count included folders the
    // filter was about to remove. This was written to keep the header in step with the root-folder
    // chip strip; the strip is gone and `rootFolders` now has this one consumer, but the reason it
    // is the right list to count from is unchanged.
    val rootFolderCount = if (folderStack.isEmpty()) rootFolders.size else 0
    val folderCount = pluralStringResource(R.plurals.vaultlist_count_folders, rootFolderCount, rootFolderCount)
    if (narrowed.isEmpty()) {
        return if (rootFolderCount > 0) {
            stringResource(R.string.vaultlist_count_device_folders_suffix, count, folderCount)
        } else {
            stringResource(R.string.vaultlist_count_device_suffix, count)
        }
    }
    val and = stringResource(R.string.vaultlist_narrowed_and)
    val narrowing = narrowed.joinToString(and)
    return if (rootFolderCount > 0) {
        stringResource(R.string.vaultlist_count_filtered_folders_suffix, count, folderCount, narrowing)
    } else {
        stringResource(R.string.vaultlist_count_filtered_suffix, count, narrowing)
    }
}

/**
 * Where an item's row sits in the vault list's `LazyColumn`, given its index among
 * [VaultListUiState.orderedItems].
 *
 * The folder rows, the fold's "show N more" control and the "no items of its own" line are `item`s
 * of the same list as the results, so the two indices differ by however many of them are drawn.
 * `animateScrollToItem` was given the item index directly until 2026-09-16 and therefore stopped
 * short by exactly that many rows — with three folders at the root, a just-saved login landed three
 * rows above the viewport (F-10).
 *
 * [noItemsRow] can only be true while the browsed folder holds no items of its own, so it cannot be
 * true on a scroll *to* an item in this folder; it is a parameter anyway, because the arithmetic
 * belongs to the list's shape rather than to the one caller that happens to use it today.
 */
internal fun listRowIndexOfItem(
    itemIndex: Int,
    foldersShown: Int,
    folderOverflowRow: Boolean,
    noItemsRow: Boolean,
    /** The header, breadcrumb and error rows ahead of everything else — see [leadingListRows]. */
    leadingRows: Int = 0,
): Int = leadingRows + foldersShown + (if (folderOverflowRow) 1 else 0) + (if (noItemsRow) 1 else 0) + itemIndex

/**
 * The glyph an empty vault list shows.
 *
 * A "+" only where the plate is actually an add button. It was drawn for every type filter,
 * including Passkeys — the one type with no add flow at all, because passkeys arrive from the
 * credential provider — so the biggest thing on that screen was a 48dp plus sign, dead centre and
 * inert, while `fab-add-item` was the real control (F-09, 2026-09-15). Where there is nothing to
 * add, the type's own glyph says what the empty screen is *about* rather than advertising a tap that
 * cannot be honoured; `onIconTap` at the call site is the other half of the same rule and has said
 * this since 2026-07-29, so only the picture was out of step.
 *
 * Pure, so the rule can be pinned without a composition — see `EmptyStateIconTest`.
 */
internal fun emptyStateIcon(
    addableType: NewItemType?,
    emptyOfType: ItemTypeFilter?,
    narrowed: Boolean,
): ImageVector = when {
    addableType != null -> LkIcons.Plus
    emptyOfType != null -> emptyOfType.icon
    narrowed -> LkIcons.Search
    else -> LkIcons.Shield
}

/**
 * Whether the add FAB is on screen.
 *
 * Two conditions, and they are the same argument twice: a floating control may not sit on top of
 * something the user is working with.
 *
 * [keyboardUp] is the older one (2026-08-19) — the window does not resize for the IME, so the FAB
 * lifted out of the list and onto the folder strip, covering 80 of its 126px.
 *
 * [listScrolling] is F-12's (2026-09-16). The FAB overlaps whichever row happens to be level with it
 * mid-scroll, and a tap in the overlap opened the add sheet instead of the row's menu. The row used
 * to answer that with a scroll-driven dodge, which fixed the tap and cost the action column a stable
 * position: rows 1–4 put Copy at x=738 and the row under the FAB put it at x=544, a 194px jog, and
 * every row near the FAB slid a fraction of it. Hiding the FAB removes the overlap outright for the
 * whole of a scroll, which is when a moving control is read as a rendering fault.
 *
 * `isScrollInProgress` covers the drag and the fling that follows it, so "rests" means settled, not
 * "finger lifted".
 */
internal fun addFabVisible(keyboardUp: Boolean, listScrolling: Boolean): Boolean =
    !keyboardUp && !listScrolling

/**
 * The FAB rect the rows are allowed to dodge, or null when they must not.
 *
 * The other half of [addFabVisible], and the whole of "dodge only at rest". At rest the FAB is
 * visible and it really does cover one row's action cluster: that row still has to move, or its
 * "More actions" button is untappable and a tap in the overlap opens the add sheet instead. While
 * the list scrolls the FAB is off screen, so there is nothing to avoid — and, more to the point,
 * a scroll is the only moment a shift could be *seen* to happen, which is what made the deleted
 * mechanism read as a jogging action column rather than as avoidance.
 *
 * So the gate is here rather than inside the row: `AnimatedVisibility` keeps the FAB composed and
 * positioned for the 120ms of its exit, and a row reading those bounds would slide left under a
 * moving finger for exactly as long. Null from the first frame of the scroll instead.
 *
 * See `VaultItemRow.fabDodgeOffset` for what a row does with a non-null answer.
 */
internal fun fabDodgeBounds(fab: Rect?, listScrolling: Boolean): Rect? =
    if (listScrolling) null else fab

/**
 * How far the FAB shrinks on its way out, and grows from on its way back.
 *
 * Not to zero: a control that vanishes to a point reads as being destroyed, and this one is only
 * stepping aside for a scroll it will outlive by a fraction of a second. Far enough from 1 to be
 * read as movement rather than as a flicker.
 */
private const val FabHideScale = 0.8f

/** The bulk-action bar's gap from the bottom of the screen. Named so the list can add it back. */
private val BulkBarBottomMargin = 24.dp

/**
 * How much room the list must leave at its foot.
 *
 * [LkFrame.gutterFab] at rest — the constant the FAB needs — and, while a selection is live, the
 * bar's own measured height plus the margin it floats on, whichever is larger. The bar takes the
 * FAB's place and is taller than it, and it grows with the text: at font scale 2 the three labels
 * wrap and the constant stops being enough, which strands the last row under a bar that no amount of
 * scrolling can move (F-11's reachability half; the occlusion a floating bar causes mid-scroll is
 * inherent to floating and is not what this addresses).
 *
 * Pure so the arithmetic can be pinned without a device — see `BulkBarInsetTest`.
 */
@Composable
internal fun bulkBarListInset(selecting: Boolean, barHeightPx: Int): Dp {
    if (!selecting || barHeightPx == 0) return LkFrame.gutterFab
    val bar = with(LocalDensity.current) { barHeightPx.toDp() }
    return bulkBarListInset(bar)
}

/**
 * What the list reserves at its foot, given whether the keyboard is up.
 *
 * [resting] is the FAB's gutter, or the bulk bar's measured height during a selection. While the
 * keyboard is up the FAB is not drawn (`addFabVisible`) and the list already ends at the keyboard
 * (`imePadding`), so the FAB's ~90dp reserve was an empty band between the last row and the
 * keyboard with a row sliced above it — nothing was sitting there. A live selection keeps its
 * reserve: the bulk bar is still on screen.
 */
internal fun listFootInset(keyboardUp: Boolean, selecting: Boolean, resting: Dp): Dp =
    if (keyboardUp && !selecting) LkSpace.sm else resting

/** The height half of [bulkBarListInset], in dp, with no composition around it. */
internal fun bulkBarListInset(barHeight: Dp): Dp =
    maxOf(LkFrame.gutterFab, barHeight + BulkBarBottomMargin + LkSpace.md)

/**
 * How long the active-filter strip holds its height after its last token is removed.
 *
 * Long enough to outlast a tap's release and the reflexive second tap that follows a miss; short
 * enough that the list visibly reclaims the row. Not a motion duration — it runs unchanged under
 * reduced motion, because the finger it protects is there either way.
 */
private const val FilterStripLingerMillis = 400L

/**
 * The add-sheet type that corresponds to this list filter, or null where none does — Passkeys
 * arrive from the credential provider rather than from any add flow, so there is nothing to offer.
 */
/** Where an add of [this] type must go. One decision for the sheet, the empty state, and anything future. */
internal sealed interface AddDestination {
    data class Editor(val type: NewItemType) : AddDestination
    data object FileEditor : AddDestination

    /**
     * This tier may create [type], but has used all of its allowance.
     *
     * The only way this interface refuses anything. There used to be a `ProBlocked` case beside it
     * for types the tier could not create at all, and reusing its sentence here told users a
     * straight lie: at the cap, a File add raised "Files & media — part of Pro", about a feature
     * they had just used five times. Cards and Identity documents became one-each quotas on
     * 2026-09-03, which left `ProBlocked` unreachable, and it was deleted on 2026-09-04. Every
     * refusal in this app is now a spent allowance, with two ways out rather than one.
     */
    data class QuotaBlocked(val type: NewItemType) : AddDestination
}

/**
 * The add-sheet cells that will refuse if selected, mapped to how many of that type the vault holds.
 *
 * The other half of [addDestination]: every type this returns is a type that routes to
 * `QuotaBlocked`, and the sheet's brass plate and count badge exist to say so before the tap rather
 * than after it. Two functions rather than one because they answer at different moments — this one
 * for ten cells at once while the sheet composes, that one for a single tap — but they must agree,
 * and [AddSheetLockedTypesTest] is where that is pinned.
 *
 * **All three metered types.** File is capped at seven (`AttachmentQuota.FREE_LIMIT`) and refuses on
 * selection exactly as Card and Identity do, but it was absent from this set until 2026-09-04
 * because the sheet keyed its marking off [NewItemType.requiresPro], which File is not. So the one
 * type a free vault is most likely to exhaust was the one type whose cell promised it would work.
 * "A cap of 1 is a teaser, a cap of 7 is a budget" is a fair distinction everywhere except the only
 * place this is read: at the moment of the tap a spent 7 bounces precisely as hard as a spent 1.
 *
 * Each parameter is the count when that allowance is spent and null when it is not — one argument
 * carrying both facts, so a caller cannot pass "spent" without also passing the number the badge
 * has to render. Every quota flag in `VaultListUiState` is already false for a Pro vault, which is
 * why `isPro` is not a parameter here.
 */
internal fun spentAllowances(
    cards: Int?,
    identities: Int?,
    attachments: Int?,
): Map<NewItemType, Int> = buildMap {
    cards?.let { put(NewItemType.CARD, it) }
    identities?.let { put(NewItemType.IDENTITY, it) }
    attachments?.let { put(NewItemType.FILE, it) }
}

/**
 * @param quotaFull the free tier's file-and-photo slots are all taken (`AttachmentQuota`). Only
 *   ever true for a free vault, and only ever consulted for [NewItemType.FILE] — every other type
 *   is either free and unlimited or gated outright by [NewItemType.requiresPro].
 */
internal fun NewItemType.addDestination(
    isPro: Boolean,
    quotaFull: Boolean = false,
    typeQuotaFull: Boolean = false,
): AddDestination = when {
    // Cards and Identity documents: one free each since 2026-09-03 (`ProTypeQuota`), where they
    // used to be gated outright. So the refusal is on the count, exactly as it is for files, and
    // never on the tier — a free vault holding no card may create one, and meets no dialog at all.
    // `!isPro` guards this on its own terms rather than trusting the caller to have filtered, for
    // the same reason the file branch below does.
    requiresPro && typeQuotaFull && !isPro -> AddDestination.QuotaBlocked(this)
    // Blocked on the count, not the tier. Files are free up to the cap, so this is the one route
    // where a free user is turned away by how much they already have rather than by what they are.
    // Caught here so they meet the upgrade prompt instead of a file editor whose Save refuses.
    // `!isPro` is not redundant with the caller. AttachmentQuota already answers false for Pro, so
    // in practice this only ever fires for a free vault — but a routing rule that depends on its
    // caller having filtered correctly is one careless call site away from locking a paying user
    // out of the feature they paid for. Cheap to make true on its own terms.
    isFile && quotaFull && !isPro -> AddDestination.QuotaBlocked(this)
    isFile -> AddDestination.FileEditor
    else -> AddDestination.Editor(this)
}

internal fun ItemTypeFilter.addable(): NewItemType? = when (this) {
    ItemTypeFilter.LOGIN -> NewItemType.LOGIN
    ItemTypeFilter.TOTP -> NewItemType.TOTP
    ItemTypeFilter.NOTE -> NewItemType.NOTE
    ItemTypeFilter.CARD -> NewItemType.CARD
    ItemTypeFilter.IDENTITY -> NewItemType.IDENTITY
    ItemTypeFilter.FILE -> NewItemType.FILE
    ItemTypeFilter.COORDINATE -> NewItemType.COORDINATE
    ItemTypeFilter.PHRASE -> NewItemType.PHRASE
    ItemTypeFilter.PASSKEY -> null
}

// `inlineNoun` and `openingLowercase` lived here until 2026-07-30. Both existed only to make a noun
// fit a sentence template it was being dropped into, and neither could succeed: the words *around*
// a noun change with the noun in every language the app ships. The templates are gone and each
// category now carries its own finished sentence — see ItemTypeFilter.emptyTitleRes.

/**
 * The bulk-move sheet's rows: every folder, depth-first and name-sorted, paired with its indent.
 *
 * `internal` so the ordering can be asserted directly — the sheet is a flat list of rows whose only
 * expression of the hierarchy is that indent, so an order that disagreed with it would render a
 * tree that is not the vault's.
 */
internal fun bulkMoveRows(folders: List<Folder>): List<Pair<Folder, Int>> {
    val childrenOf = folders.groupBy { it.parentId }
    val rows = mutableListOf<Pair<Folder, Int>>()
    fun walk(parentId: UUID?, depth: Int) {
        if (depth > FolderTree.MAX_DEPTH) return
        childrenOf[parentId].orEmpty().sortedByDisplayName { it.name }.forEach {
            rows += it to depth
            walk(it.id, depth + 1)
        }
    }
    walk(null, 0)
    return rows
}

/**
 * The persistent banner shown only while a reveal session is active. Amber — a state, not a failure —
 * it states that hidden entries are showing, counts down to the moment they stop, and offers "Stop
 * showing", which conceals the whole session at once. Announced once, politely, when it appears;
 * carries a stateDescription so a screen reader names the reveal state on focus.
 *
 * The countdown is the fix for the complaint that produced it (2026-09-02): *"the yellow window
 * doesn't disappear until I click, even if I go to another folder"*. Persistence is correct — the
 * banner tracks a state, and hiding it while the state held would make the app quietly show
 * concealed entries — so the answer was to bound the state (see [RevealSession.expiresAtElapsedMs])
 * and to say so here. A banner that names its own end is information; one that does not is a nag,
 * and no amount of styling changes which of the two it reads as.
 */
@Composable
private fun RevealedSessionHeader(
    /** Whole seconds left, or null before the first tick lands. */
    secondsRemaining: Int?,
    onHideAgain: () -> Unit,
) {
    val c = LkTheme.colors
    val stateDesc = stringResource(R.string.vaultlist_revealed_state_description)
    val announcement = stringResource(R.string.vaultlist_revealed_announcement)
    Row(
        // Tagged on the container, not either Text: the flow that proves a reveal session is
        // active only needs to know the banner is on screen, and the header's own text is the
        // one string in this composable that is not safe to assert on directly — it changes with
        // the language, which is exactly what a tag exists to route around.
        // No `padding(top = ...)` here, and that is the fix rather than an omission. This banner is
        // emitted straight into the screen's outer `spacedBy(LkSpace.md)` column, so the parent
        // already puts a token gap above it; a top padding on top of that is the modifier form of
        // the Spacer-inside-a-spacedBy trap, and it rendered md + sm = 24dp — the section-to-section
        // step — between the search row and a banner that is not a new section. Measured on device
        // 2026-09-03 at 63px / 420dpi before, 42px after.
        modifier = Modifier
            .fillMaxWidth()
            .clip(LkShape.sm)
            .background(c.amber.copy(alpha = 0.16f))
            .semantics { stateDescription = stateDesc }
            .announce(announcement)
            .padding(horizontal = LkSpace.md, vertical = LkSpace.sm)
            .lkTag("vaultlist-revealed-header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        // A Column, so the countdown sits under the sentence it qualifies rather than competing with
        // it for the same line. Three siblings in this Row — sentence, clock, action — put the clock
        // between two things that are not about each other, and at the largest font scale the middle
        // one is what gets squeezed.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
        ) {
            Text(
                text = stringResource(R.string.vaultlist_revealed_header),
                style = LkText.badge,
                color = c.amber,
            )
            // Absent, not zero, until the first tick: a countdown that flashes "0:00" as it appears
            // says the opposite of what is happening.
            if (secondsRemaining != null) {
                val clock = stringResource(
                    R.string.vaultlist_revealed_countdown_clock,
                    secondsRemaining / 60,
                    secondsRemaining % 60,
                )
                Text(
                    text = stringResource(R.string.vaultlist_revealed_countdown, clock),
                    style = LkText.subtitle,
                    color = c.amber.copy(alpha = 0.75f),
                )
            }
        }
        Text(
            text = stringResource(R.string.vaultlist_hide_again),
            style = LkText.badge,
            color = c.amber,
            // `wrapContentSize` after `minTouchTarget`, not before. `minTouchTarget` is a
            // `defaultMinSize`, so on a one-line label it makes the box taller than the glyph — and a
            // `Text` draws at the top of a box it does not fill. The Row's `CenterVertically` centres
            // the *box*, not the glyph inside it, so the action rendered visibly higher than the
            // sentence beside it while both boxes were correctly centred (seen on device 2026-08-19).
            modifier = Modifier
                .clip(LkShape.sm)
                .clickable(onClick = onHideAgain)
                .minTouchTarget()
                .wrapContentSize(Alignment.Center)
                .padding(horizontal = LkSpace.sm, vertical = LkSpace.xs)
                .lkTag("vaultlist-hide-again"),
        )
    }
}

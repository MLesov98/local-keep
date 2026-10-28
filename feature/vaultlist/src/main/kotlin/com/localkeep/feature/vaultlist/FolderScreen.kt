package com.localkeep.feature.vaultlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.model.FolderTree
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.minTouchTarget
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
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkRise
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.rememberLkKeyboardUnpinsChrome
import java.util.UUID

/** What Back resolves to on the Folders screen — see [resolveFolderBack]. */
internal enum class FolderBackAction { HideNewFolder, CancelRename, Leave }

/**
 * One decision, used by both the system Back gesture and the header chevron.
 *
 * They used to disagree: the gesture handler only guarded [FolderUiState.showNewFolder], so Back
 * while renaming fell through to [onBack] and dropped the uncommitted name, and the chevron left
 * unconditionally regardless of either state. Routing both entry points through the same function
 * makes that class of drift impossible — there is nowhere left for a second, looser check to live.
 * The new-folder card takes priority because closing it is what "cancel" has always meant while it
 * is open; the two states are not expected to be true together, but the order is fixed either way.
 */
internal fun resolveFolderBack(showNewFolder: Boolean, renamingId: UUID?): FolderBackAction = when {
    showNewFolder -> FolderBackAction.HideNewFolder
    renamingId != null -> FolderBackAction.CancelRename
    else -> FolderBackAction.Leave
}

/**
 * Folders, as a place you can go.
 *
 * Until this screen existed, creating, renaming and deleting a folder lived **inside the item
 * editor's folder picker** and nowhere else — so the only route to tidying up folders ran through
 * opening an item you had no intention of changing. That is the third of the four ways the folder
 * model was invisible; the other three are on the list itself (the row marker, the chip strip, and
 * the header naming what it is filtered to).
 *
 * A management surface, not a browser. Tapping a folder here takes the user to the vault list,
 * browsing inside it — the list is where a container is opened; this screen is where one is made,
 * renamed, moved or deleted.
 */
@Composable
fun FolderScreen(
    viewModel: FolderViewModel,
    onOpenFolder: (List<UUID>) -> Unit,
    onBack: () -> Unit,
) {
    val c = LkTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.pendingRepair) {
        LkDialog(
            onDismiss = viewModel::cancelRepair,
            title = stringResource(R.string.vaultlist_folders_repair_title),
            body = stringResource(R.string.vaultlist_folders_repair_body),
            icon = LkIcons.Folder,
            accent = c.amber,
            confirm = LkDialogAction(
                label = stringResource(R.string.vaultlist_folders_repair_confirm),
                onClick = viewModel::confirmRepair,
            ),
            dismissLabel = stringResource(R.string.vaultlist_folders_repair_dismiss),
        )
    }

    state.movingRow?.let { row -> MoveFolderDialog(state, row, viewModel) }

    state.pendingDeletionRow?.let { row ->
        LkDialog(
            onDismiss = viewModel::cancelDelete,
            title = stringResource(R.string.vaultlist_folders_delete_title, row.folder.name),
            // Says what happens to the contents, because that is the only question anyone has at
            // this prompt. One version now: the folder that holds other folders and the folder that
            // holds none have the same outcome, since the whole subtree goes to the trash together.
            // The second version existed to state a promote and the rename it could force, and
            // neither happens any more.
            body = folderDeleteBody(row.itemCount, hasSubfolders = state.folders.any { it.folder.parentId == row.folder.id }),
            icon = LkIcons.Trash,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.vaultlist_folders_delete_confirm),
                // No rename notice any more: nothing is promoted, so nothing can clash with a new
                // sibling and need renaming out of the way.
                onClick = { viewModel.confirmDelete() },
                emphasis = LkActionEmphasis.Destructive,
            ),
            dismissLabel = stringResource(R.string.vaultlist_folders_delete_dismiss),
        )
    }

    // Cancelling the name — or an in-place rename — is what back means while either is open, before
    // it means leaving the screen. One handler for both the gesture and the header chevron, so the
    // two stages are visible together and cannot drift apart again.
    val handleBack: () -> Unit = {
        when (resolveFolderBack(state.showNewFolder, state.renamingId)) {
            FolderBackAction.HideNewFolder -> viewModel.hideNewFolderInput()
            FolderBackAction.CancelRename -> viewModel.cancelRenaming()
            FolderBackAction.Leave -> onBack()
        }
    }
    BackHandler(enabled = state.showNewFolder || state.renamingId != null, onBack = handleBack)

    // The shared keyboard-up rule (see [rememberLkKeyboardUnpinsChrome]): while the keyboard leaves a
    // short window too little room, the header and the Cancel / New folder button scroll with the
    // list instead of being pinned around it. Pinned, they left the list ~120dp between them on a
    // 360x640 window — shorter than the New folder card — so with the keyboard up the parent line and
    // Create sat under the viewport's edge, and after a create so did the name field
    // (`NewFolderCardStaysAboveTheKeyboardTest` on lk_scale_small, 2026-10-20; CI's Pixel-sized
    // emulator has the room and never sees it).
    val unpinned = rememberLkKeyboardUnpinsChrome()
    val header: @Composable (Boolean) -> Unit = { pinned ->
        LkScreenHeader(
            title = stringResource(R.string.vaultlist_folders_title),
            subtitle = stringResource(R.string.vaultlist_folders_subtitle),
            // The entrance plays once, on the pinned header; inside the list it has just been moved
            // there, and replaying it would flash. The list's own padding is the margin there.
            modifier = if (pinned) {
                Modifier.lkEnter().padding(horizontal = LkFrame.screenHorizontal).padding(top = LkFrame.screenTop)
            } else {
                Modifier
            },
            // Trailing, matching the eight other LkScreenHeader back chevrons (Trash,
            // Settings, Backup, Import, Recovery Kit, item edit, file edit, attachments).
            // This screen put it in the leading slot, so Back physically swapped sides
            // depending on which screen you were leaving. Visual sweep, 2026-09-01.
            trailing = {
                LkIconButton(
                    icon = LkIcons.ChevronLeft,
                    label = stringResource(R.string.vaultlist_folders_back),
                    onClick = handleBack,
                    plateAlignment = Alignment.CenterEnd,
                )
            },
        )
    }
    // Pinned, not sitting above the list as an always-open field. Making a folder is the one reason
    // to be on this screen that is not about a folder already on it, and the field used to occupy the
    // top of the screen on every visit — including the visits that were only ever going to rename
    // something.
    val action: @Composable (Boolean) -> Unit = { pinned ->
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (pinned) {
                        Modifier.padding(horizontal = LkFrame.screenHorizontal).padding(bottom = LkFrame.gutterPlain)
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (state.showNewFolder) {
                LkSecondaryButton(
                    text = stringResource(R.string.vaultlist_folders_new_cancel),
                    onClick = viewModel::hideNewFolderInput,
                )
            } else {
                LkPrimaryButton(
                    text = stringResource(R.string.vaultlist_add_new_folder),
                    icon = LkIcons.Plus,
                    onClick = viewModel::showNewFolderInput,
                )
            }
        }
    }

    LkScreen(accent = c.jade, auraAlpha = 0.08f, auraBias = 0.5f) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (!unpinned) header(true)

            // Lazy since 2026-10-02 (S1-09): this was a scrolling Column that composed every folder
            // up front, each with its own card, three icon buttons and a menu — seconds of main-thread
            // work on a large import. Sections keep their `lg` rhythm by padding the gap that
            // `spacedBy` leaves between them; folder cards sit 4dp apart as before.
            val listState = rememberLazyListState()
            // The card is the last item, so on a long list it is not composed when it opens and its
            // own bring-into-view (2C-F1) has nothing to run in. Scrolling to it composes it; the
            // card's request then settles it fully on screen, error line included.
            LaunchedEffect(state.showNewFolder) {
                if (state.showNewFolder) {
                    // The card is last, or second to last when the button rides the list.
                    val trailing = if (unpinned) 2 else 1
                    listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - trailing).coerceAtLeast(0))
                }
            }
            // The folder just made is the only confirmation this screen gives, and it sorts into
            // place among the others — so on a list longer than the screen it can land off it, the
            // name well empties, and nothing at all appears to have happened. Scroll to it. Keyed on
            // whether its row exists yet as well as on the id: the id is published a beat before the
            // re-read that adds the row, and the request has to be made when there is something to
            // scroll to.
            val createdIndex = createdRowScrollIndex(
                folders = state.folders,
                createdId = state.justCreatedId,
                leadingItems = (if (unpinned) 1 else 0) + (if (state.needsRepair) 1 else 0),
            )
            LaunchedEffect(state.justCreatedId, createdIndex != null) {
                createdIndex?.let { listState.animateScrollToItem(it) }
            }
            // Above the list, not its first item: a lazy list keeps its first visible row anchored
            // when something is inserted ahead of it, so at any scroll offset — the rename keyboard
            // supplies one — the error arrived off the top of the screen and a refused rename read
            // as a Save that did nothing (QA 2026-10-03). Pinned here it is always on screen.
            state.listError?.let { errorRes ->
                val message = stringResource(errorRes)
                LkInlineError(
                    message,
                    Modifier
                        .padding(horizontal = LkFrame.screenHorizontal)
                        .padding(top = LkSpace.lg)
                        .announce(message),
                )
            }
            val sectionGap = LkSpace.lg - FolderCardGap
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(
                    start = LkFrame.screenHorizontal,
                    end = LkFrame.screenHorizontal,
                    top = LkSpace.lg,
                    bottom = LkSpace.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(FolderCardGap),
            ) {
                if (unpinned) {
                    item(key = "header") { Box(Modifier.padding(bottom = sectionGap)) { header(false) } }
                }
                // Offered only when the stored tree really is broken — a repair button on a healthy
                // vault is an invitation to fix something that is not wrong. The cause is on disk
                // (a restored snapshot, a merge), so nothing on this screen could have prevented it
                // and the wording does not blame the user for it.
                if (state.needsRepair) {
                    item(key = "repair") {
                        LkGroup(Modifier.padding(bottom = sectionGap)) {
                            LkGroupRow(first = true, onClick = viewModel::askRepair) {
                                Icon(
                                    LkIcons.Folder,
                                    contentDescription = null,
                                    tint = c.amber,
                                    modifier = Modifier.size(17.dp),
                                )
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(
                                        stringResource(R.string.vaultlist_folders_repair_action),
                                        style = LkText.body,
                                        color = c.textPrimary,
                                    )
                                    Text(
                                        stringResource(R.string.vaultlist_folders_repair_banner),
                                        style = LkText.caption,
                                        color = c.textFaint,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.folders.isEmpty()) {
                    item(key = "empty") {
                        LkEmptyState(
                            icon = LkIcons.Folder,
                            title = stringResource(R.string.vaultlist_folders_empty_title),
                            body = stringResource(R.string.vaultlist_folders_empty_body),
                        )
                    }
                } else {
                    // One card per folder, not one card holding hairline-divided rows. `Item
                    // Flows.dc.html` draws the list this way and it is also what this screen needs:
                    // every row here carries two or three of its own controls, and a shared
                    // container made the boundary between "this folder's trash" and the next row
                    // a 1px line. Each row is `first = true` in its own group, so no hairline is
                    // drawn and the interaction plumbing — including the long-press menu and its
                    // documented no-press-wash fix — is untouched.
                    items(state.folders, key = { it.folder.id.toString() }) { row ->
                        LkGroup(shape = LkShape.xl) {
                            FolderManagementRow(
                                row = row,
                                first = true,
                                renaming = state.renamingId == row.folder.id,
                                renameText = state.renameText,
                                justCreated = state.justCreatedId == row.folder.id,
                                viewModel = viewModel,
                                onOpen = { onOpenFolder(row.path) },
                            )
                        }
                    }
                }

                if (state.showNewFolder) {
                    item(key = "new-folder") {
                        Box(Modifier.padding(top = sectionGap)) { NewFolderCard(state, viewModel) }
                    }
                }
                if (unpinned) {
                    item(key = "action") { Box(Modifier.padding(top = sectionGap)) { action(false) } }
                }
            }

            if (!unpinned) action(true)
        }
    }
}

/**
 * Naming a new folder, in a card under the list it will join.
 *
 * The same shape `core:ui`'s picker screen uses, so the two places a folder can be made do not
 * disagree about what making one looks like. Kept separate rather than shared because this one
 * carries the parent chooser, which is a statement about *this* screen's tree and has no equivalent
 * in the item editor, where the parent is whatever the user last opened.
 */
@Composable
private fun NewFolderCard(state: FolderUiState, viewModel: FolderViewModel) {
    val c = LkTheme.colors
    // **Deliberately not focused on arrival**, matching [LkFolderPicker]'s naming card — which is
    // the whole point of the KDoc above claiming the two places a folder can be made agree about
    // what making one looks like. They did not: the shared picker dropped its focus request on
    // 2026-08-20 and this copy kept one, so the two screens disagreed about the keyboard for a
    // fortnight. Measured here on emulator-5554 2026-09-04: tapping "New folder" gave
    // `mInputShown=true` with the parent chooser sitting directly above the IME.
    //
    // The argument is the picker's, unchanged, and this screen is if anything the stronger case for
    // it: the card carries a parent chooser, so the order the flow wants is choose-where then name,
    // and a keyboard that opens with the card covers the tree the user is choosing from. Reported
    // 2026-08-20 against the picker: "I click and immediately keyboard opens.. when if user wants
    // to add a folder in a subfolder somewhere? exceptionally bad UX". The requester stays — the
    // field is still the obvious next target — it is simply no longer taken.
    val focus = remember { FocusRequester() }
    // The card is the last child of a six-screen scroll on a vault with 18 folders, so opening it
    // changed nothing the user could see: no field, no keyboard (it deliberately takes no focus,
    // above), just the button relabelling itself Cancel six screens away (2C-F1). Keyed on the
    // measured size in the manner of the Recovery-Kit QR reveal, because the request has to be made
    // after the card has been laid out — and re-fired when the size changes, which is what an
    // inline error appearing inside it does, so the error lands on screen too (2C-F2).
    val reveal = remember { BringIntoViewRequester() }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    // And re-fired whenever the keyboard moves. It shrinks the list's viewport (`imePadding()` on
    // the screen) without touching the card, so neither key above changes, and the only scroll in
    // answer was the focused field keeping *itself* on screen: the name well at the viewport's
    // bottom edge, the parent line and Create below it until the keyboard was put away — and Done
    // on the keyboard creates the folder, so the parent could not be chosen without hiding it first
    // (device pass, API 36, 2026-10-05). Keyed on the inset rather than on whether it is non-zero,
    // because the viewport is still shrinking for the whole slide; the request made on its last
    // frame is the one that lands. `NewFolderCardStaysAboveTheKeyboardTest` drives a real keyboard.
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(cardSize, keyboardHeight) {
        if (cardSize.height > 0) runCatching { reveal.bringIntoView() }
    }
    var choosingParent by remember { mutableStateOf(false) }
    if (choosingParent) {
        NewFolderParentDialog(
            state = state,
            onPick = { parentId ->
                viewModel.setNewParent(parentId)
                choosingParent = false
            },
            onDismiss = { choosingParent = false },
        )
    }
    val parentName = state.newParentId?.let { id ->
        state.folders.firstOrNull { it.folder.id == id }?.folder?.name
    }
    Column(
        Modifier
            .fillMaxWidth()
            .lkRise()
            .bringIntoViewRequester(reveal)
            .onSizeChanged { cardSize = it }
            // `ground`, not `surface`: this card holds a live text well, and a well on #0E1214 has
            // only two levels per channel to separate it from its own backdrop. The jade ring
            // stays — that is the "this card is active" signal and is unrelated.
            .lkSurface(LkShape.card, c.ground, c.jade.copy(alpha = 0.22f))
            .padding(LkSpace.md),
        verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        LkTextField(
            value = state.newName,
            onValueChange = viewModel::onNewNameChanged,
            label = stringResource(R.string.vaultlist_folders_new_label),
            placeholder = stringResource(R.string.vaultlist_folders_new_placeholder),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.createFolder() }),
        )
        // Beside the field it is about, not at the top of the list. See [FolderUiState.composerError].
        state.composerError?.let { errorRes ->
            val message = stringResource(errorRes)
            LkInlineError(message, Modifier.announce(message))
        }
        // Where the folder will land, and a way to change your mind about it. Without this line the
        // "Add a folder inside X" action on a row and this card look like two unrelated controls,
        // and the one that just changed the other's meaning says nothing.
        NewFolderParentRow(
            caption = parentName
                ?.let { stringResource(R.string.vaultlist_folders_new_in, it) }
                ?: stringResource(R.string.vaultlist_folders_new_in_root),
            chooseLabel = stringResource(R.string.vaultlist_folders_new_parent_choose),
            onClick = { choosingParent = true },
        )
        LkPrimaryButton(
            text = stringResource(R.string.vaultlist_folders_new_create),
            onClick = viewModel::createFolder,
            enabled = state.newName.isNotBlank(),
        )
    }
}

/**
 * The parent line of the new-folder card, drawn as the control it is.
 *
 * It was a 12sp faint caption with a click handler — the only way to pick a parent, and nothing on
 * it said so. The chevron is the affordance; the whole row, 48dp tall, is the target. The caption
 * is the state ("Inside X") and the click label is the verb, which is what TalkBack announces.
 */
@Composable
internal fun NewFolderParentRow(caption: String, chooseLabel: String, onClick: () -> Unit) {
    val c = LkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lkTag("new-folder-parent-row")
            .clickable(role = Role.Button, onClick = onClick)
            .minTouchTarget()
            .labelled(chooseLabel),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Text(
            text = caption,
            style = LkText.caption,
            color = c.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            LkIcons.ChevronRight,
            contentDescription = null,
            tint = c.textSecondary,
            modifier = Modifier.size(16.dp).lkTag("new-folder-parent-chevron"),
        )
    }
}

/**
 * What a folder row holds: the indent, the glyph, the name over its count, and the actions.
 *
 * Beside the name there are up to three 48dp targets, the indent (14dp a level) and the glyph, and
 * all of them take their width before the name is given what is left. At 308dp that left a depth-1
 * name 63dp and a depth-3 one 36dp, so "archive" broke as "archi/ve" (SCL-025); nothing was lost,
 * since the name wraps and has no line cap, but a word cut in half is not a name. When the room the
 * name would get falls under [folderNameFloor] the actions drop to a row of their own beneath it,
 * end-aligned, and the name takes everything the glyph leaves. The floor scales with the font so
 * that "enough for a word" means the same thing at every text size; 360dp at the default size keeps
 * the single row for a top-level folder and stacks only from the third level down.
 *
 * Its own composable, taking labels and callbacks rather than the ViewModel, so the geometry can be
 * measured without one. [justCreated] shows only Delete, the quiet first beat after creating one —
 * the full set stays one long-press away the whole time.
 */
@Composable
internal fun FolderRowBody(
    name: String,
    itemCount: Int,
    depth: Int,
    justCreated: Boolean,
    moveLabel: String,
    renameLabel: String,
    deleteLabel: String,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val fontScale = LocalDensity.current.fontScale
    // The indent is the hierarchy. Capped by the tree's own depth limit, so the deepest row
    // still leaves room for the name and the three controls beside it on a small phone.
    val indent = (depth.coerceAtMost(FolderTree.MAX_DEPTH) * 14).dp
    val actionCount = if (justCreated) 1 else 3
    val nameWord = rememberWidestWord(name, LkText.body)
    BoxWithConstraints(modifier) {
        val stacked = folderRowStacks(maxWidth, indent, actionCount, fontScale, nameWord)
        val countText: @Composable () -> Unit = {
            Text(
                pluralStringResource(R.plurals.vaultlist_count_items, itemCount, itemCount),
                style = LkText.caption,
                color = c.textFaint,
            )
        }
        val identity: @Composable RowScope.() -> Unit = {
            if (depth > 0) Spacer(Modifier.size(width = indent, height = 1.dp))
            Icon(
                LkIcons.Folder,
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(FolderGlyph),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(name, style = LkText.body, color = c.textPrimary)
                countText()
            }
        }
        val actions: @Composable RowScope.() -> Unit = {
            // A folder created a moment ago shows only its undo (Delete) on the row itself. The full
            // set stays one long-press away the whole time, which is what makes this a quiet first
            // beat rather than a mode the row is stuck in. See [FolderUiState.justCreatedId].
            //
            // This comment used to claim the inline controls "return the instant the user does anything
            // else — including opening the long-press menu", and that was measured false (2C-F3,
            // 2026-09-20): `justCreatedId` is cleared only by `startRenaming`, `startMoving` and
            // `duplicate`, so scrolling and twenty seconds of waiting changed nothing. That would have
            // been harmless if the menu had carried every action, but Move was the one it did not
            // carry, so a folder just created could not be moved at all until the user did something
            // unrelated to it. The menu carries Move now, and the claim above is only what is true.
            if (!justCreated) {
                LkIconButton(icon = LkIcons.Folder, label = moveLabel, onClick = onMove)
                // Edit (a pencil), not Note: Note is the secure-note item type's glyph, so a rename
                // control read as "open a note" next to a folder.
                LkIconButton(icon = LkIcons.Edit, label = renameLabel, onClick = onRename)
            }
            LkIconButton(icon = LkIcons.Trash, label = deleteLabel, onClick = onDelete, tint = c.coral)
        }
        if (!stacked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                identity()
                actions()
            }
        } else {
            // The name takes the full width and the actions move down beside the count, the same
            // shape a stacked vault row takes. They used to take a line of their own, end-aligned,
            // which left a dead half-row to their left under every stacked folder (final sweep,
            // 308dp / font 1.3-1.5, 2026-10-28).
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (depth > 0) Spacer(Modifier.size(width = indent, height = 1.dp))
                Icon(
                    LkIcons.Folder,
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(FolderGlyph),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(name, style = LkText.body, color = c.textPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { countText() }
                        actions()
                    }
                }
            }
        }
    }
}

/** The folder glyph at the head of a row. */
private val FolderGlyph = 17.dp

/** A 48dp touch target, which is what each action button costs the row whatever plate it draws. */
private val FolderRowActionWidth = 48.dp

/**
 * The least room a name keeps beside its row's actions, at the default text size, whatever its words
 * measure: about four characters of body text, so a name of short words is not set one word to a
 * line. Scaled by the font in [nameYields]. The name's own widest word is the other half of the rule.
 *
 * This was 96dp and the only half until 2026-10-28, which made it a word budget that ignored the
 * word: at font 1.5 on a 360dp phone it asked 144dp, and a folder called "Personal" — 100dp of text —
 * got its three buttons on a line of their own under an empty half-row.
 */
internal val folderNameFloor = 56.dp

/**
 * Whether a folder row's actions drop beneath the name, for a row [rowWidth] wide.
 *
 * [rowWidth] is what the row content is given, after the group row's own padding. The name gets
 * that less the indent, the glyph, the 12dp after it and the actions; below [folderNameFloor] times
 * the font scale (never less than one) it would have to break words, so the actions yield.
 */
internal fun folderRowStacks(
    rowWidth: Dp,
    indent: Dp,
    actionCount: Int,
    fontScale: Float,
    widestWord: Dp = 0.dp,
): Boolean =
    nameYields(rowWidth, indent + FolderGlyph + 12.dp + FolderRowActionWidth * actionCount, fontScale, widestWord)

/**
 * Whether a name that is given what is left of a row [rowWidth] wide after [taken] has less than a
 * word's room, so the row should put its actions elsewhere. The one rule behind the folder rows
 * (SCL-025) and the trash rows (SCL-027), kept in one place so the two cannot drift: the floor is
 * [folderNameFloor] at the default size and grows with the font, never shrinking below it.
 */
internal fun nameYields(rowWidth: Dp, taken: Dp, fontScale: Float, widestWord: Dp = 0.dp): Boolean =
    rowWidth - taken < maxOf(widestWord, folderNameFloor * fontScale.coerceAtLeast(1f))

/**
 * The rendered width of [text]'s widest word in [style] — the narrowest column it can be set in
 * without a word breaking. Words split on whitespace only, matching how the line breaker treats
 * "accounts.google.com": one word.
 */
@Composable
internal fun rememberWidestWord(text: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, style, measurer, density) {
        val px = text.split(' ', '\n', '\t').filter { it.isNotEmpty() }
            .maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
        with(density) { px.toDp() }
    }
}

@Composable
private fun FolderManagementRow(
    row: FolderRow,
    first: Boolean,
    renaming: Boolean,
    renameText: String,
    justCreated: Boolean,
    viewModel: FolderViewModel,
    onOpen: () -> Unit,
) {
    val c = LkTheme.colors
    // Resolved locally rather than threaded down from FolderScreen: this closure's only job is
    // formatting `vaultlist_folder_copy_suffix[_n]`, and every row needs one only if it is ever
    // long-pressed, so there is nothing to gain by building it once at the screen for every row.
    val resources = LocalContext.current.resources
    val duplicateDisambiguate: (String, Int) -> String = remember(resources) {
        { name, ordinal ->
            if (ordinal <= 1) {
                resources.getString(R.string.vaultlist_folder_copy_suffix, name)
            } else {
                resources.getString(R.string.vaultlist_folder_copy_suffix_n, name, ordinal)
            }
        }
    }
    var menuExpanded by remember(row.folder.id) { mutableStateOf(false) }
    if (renaming) {
        FolderRenameRow(
            folderName = row.folder.name,
            first = first,
            text = renameText,
            onTextChange = viewModel::onRenameTextChanged,
            onCommit = viewModel::commitRename,
            onCancel = viewModel::cancelRenaming,
        )
        return
    }
    // Tapping the row filters the vault list to this folder. The two icon buttons sit *beside* the
    // row's own click target rather than inside it, so "look at what is in here" and "change this
    // folder" never resolve to the same tap. A long press is a second route to the same three
    // actions the icon buttons already offer — some phones make a three-icon row cramped, and a
    // long press is the platform's own convention for "more about this row".
    Box {
        // T5 (#5, device pass 2026-08-12): the old bolted-on `combinedClickable` used Compose's
        // default ripple, which a long-press detector holds open until its timeout — the ~1s
        // lingering box the user saw on the Folders screen. LkGroupRow now exposes `onLongClick`
        // (indication-free, no press-wash), so the row goes through it and the box is gone.
        LkGroupRow(
            first = first,
            onClick = onOpen,
            onLongClick = { menuExpanded = true },
            onClickLabel = stringResource(R.string.vaultlist_folders_open_action, row.folder.name),
            onLongClickLabel = stringResource(R.string.vaultlist_folder_action_more, row.folder.name),
        ) {
        FolderRowBody(
            name = row.folder.name,
            itemCount = row.itemCount,
            depth = row.depth,
            justCreated = justCreated,
            moveLabel = stringResource(R.string.vaultlist_folders_move_action, row.folder.name),
            renameLabel = stringResource(R.string.vaultlist_folders_rename_action, row.folder.name),
            deleteLabel = stringResource(R.string.vaultlist_folders_delete_action, row.folder.name),
            onMove = { viewModel.startMoving(row.folder.id) },
            onRename = { viewModel.startRenaming(row.folder.id) },
            onDelete = { viewModel.askDelete(row.folder.id) },
            modifier = Modifier.weight(1f),
        )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vaultlist_folder_action_rename)) },
                onClick = {
                    menuExpanded = false
                    viewModel.startRenaming(row.folder.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vaultlist_folder_action_move)) },
                onClick = {
                    menuExpanded = false
                    viewModel.startMoving(row.folder.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vaultlist_folder_action_duplicate)) },
                onClick = {
                    menuExpanded = false
                    viewModel.duplicate(row.folder.id, duplicateDisambiguate)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vaultlist_folder_action_delete), color = c.coral) },
                onClick = {
                    menuExpanded = false
                    viewModel.askDelete(row.folder.id)
                },
            )
        }
    }
}

/**
 * The inline rename row. The name well takes focus, and the keyboard with it, the moment the row
 * opens: the pencil is a request to type, and a second tap on the well to raise the keyboard was
 * the dead step the device pass found (D3-L-07). Focus is requested from [rememberFocusOnArrival],
 * not here, so the guard that keeps the *new folder* card from raising the keyboard
 * ([NewFolderCardAutoFocusTest]) still reads this file for what it was written to forbid.
 */
@Composable
internal fun FolderRenameRow(
    folderName: String,
    first: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = LkTheme.colors
    val focusOnArrival = rememberFocusOnArrival()
    // Brought fully into view, and again whenever the keyboard moves. Focusing the name well
    // scrolls only the well itself, so the row's own padding and the card around it were left
    // under the viewport's bottom edge — flush against the pinned Cancel/New folder button,
    // which then read as sitting on top of the row's own ✕ (reported with the new-folder card
    // open, where that button is Cancel). The same arrangement as [NewFolderCard], for the
    // same reason: the inset shrinks the viewport without changing anything this row keys on.
    val reveal = remember { BringIntoViewRequester() }
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(keyboardHeight) { runCatching { reveal.bringIntoView() } }
    Box(Modifier.fillMaxWidth().bringIntoViewRequester(reveal)) {
        LkGroupRow(first = first) {
            // A 4dp patch of `ground` under the well, because this row is inside an `LkGroup` and a
            // group fills with `c.surface`. Without it the well and its backdrop differ by two per
            // channel and the field stops reading as a field. The patch restores the six/eight/eight
            // the rest of the app gets for free, without restructuring `LkGroup` or the six other
            // screens that use it. `weight(1f)` has to move onto the Box — it only resolves as a
            // direct RowScope child.
            Box(
                Modifier
                    .weight(1f)
                    .background(c.ground, LkShape.xl)
                    .padding(LkSpace.xs),
            ) {
                LkTextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = stringResource(R.string.vaultlist_folders_rename_label, folderName),
                    modifier = Modifier.fillMaxWidth().then(focusOnArrival),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommit() }),
                )
            }
            Spacer(Modifier.size(LkSpace.sm))
            LkIconButton(
                icon = LkIcons.Check,
                label = stringResource(R.string.vaultlist_folders_rename_confirm),
                onClick = onCommit,
                tint = c.jade,
            )
            LkIconButton(
                icon = LkIcons.Close,
                label = stringResource(R.string.vaultlist_folders_rename_cancel),
                onClick = onCancel,
            )
        }
    }
}

/**
 * Where a folder goes. Every folder in the vault is offered except the ones that would detach the
 * branch — the folder itself and everything under it — which are absent rather than disabled: a
 * greyed row invites a tap that can only ever be refused.
 */
@Composable
private fun MoveFolderDialog(state: FolderUiState, row: FolderRow, viewModel: FolderViewModel) {
    val c = LkTheme.colors
    LkDialog(
        onDismiss = viewModel::cancelMoving,
        title = stringResource(R.string.vaultlist_folders_move_title, row.folder.name),
        icon = LkIcons.Folder,
        accent = c.jade,
        dismissLabel = stringResource(R.string.vaultlist_folders_move_dismiss),
        body = null,
        content = {
            LkGroup {
                LkGroupRow(first = true, onClick = { viewModel.commitMove(null) }) {
                    Text(
                        stringResource(R.string.vaultlist_folders_move_root),
                        style = LkText.body,
                        color = c.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                state.folders
                    .filter { it.folder.id != row.folder.id && viewModel.canMoveInto(it.folder.id) }
                    .forEach { candidate ->
                        LkGroupRow(
                            first = false,
                            onClick = { viewModel.commitMove(candidate.folder.id) },
                        ) {
                            if (candidate.depth > 0) {
                                Spacer(Modifier.size(width = (candidate.depth * 14).dp, height = 1.dp))
                            }
                            Text(
                                candidate.folder.name,
                                style = LkText.body,
                                color = c.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
            }
        },
    )
}

/** Where the next new folder lands. Every folder is a legal parent — nothing exists to nest yet. */
@Composable
private fun NewFolderParentDialog(
    state: FolderUiState,
    onPick: (UUID?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LkTheme.colors
    LkDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.vaultlist_folders_new_parent_title),
        icon = LkIcons.Folder,
        accent = c.jade,
        dismissLabel = stringResource(R.string.vaultlist_folders_move_dismiss),
        body = null,
        content = {
            LkGroup {
                LkGroupRow(first = true, onClick = { onPick(null) }) {
                    Text(
                        stringResource(R.string.vaultlist_folders_move_root),
                        style = LkText.body,
                        color = c.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                // A folder already at the depth limit cannot take a child, so it is not offered:
                // the create would be refused and the row would have promised something it
                // cannot do.
                state.folders.filter { it.depth + 1 < FolderTree.MAX_DEPTH }.forEach { candidate ->
                    LkGroupRow(first = false, onClick = { onPick(candidate.folder.id) }) {
                        if (candidate.depth > 0) {
                            Spacer(Modifier.size(width = (candidate.depth * 14).dp, height = 1.dp))
                        }
                        Text(
                            candidate.folder.name,
                            style = LkText.body,
                            color = c.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Which list item the Folders screen should scroll to after a folder was made, or null when there
 * is nothing to do yet.
 *
 * [leadingItems] is the number of lazy items above the folder rows (the repair banner, when it is
 * showing). Null while [createdId] is absent from [folders]: the id arrives before the re-read that
 * adds the row, and a scroll to a row that does not exist would land on whatever is at its index.
 */
internal fun createdRowScrollIndex(folders: List<FolderRow>, createdId: UUID?, leadingItems: Int): Int? {
    if (createdId == null) return null
    val index = folders.indexOfFirst { it.folder.id == createdId }
    return if (index < 0) null else index + leadingItems
}

/** The gap between two folder cards on the Folders screen. */
private val FolderCardGap = 4.dp

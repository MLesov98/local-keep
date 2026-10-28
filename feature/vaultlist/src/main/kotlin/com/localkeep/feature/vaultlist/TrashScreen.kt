package com.localkeep.feature.vaultlist

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.model.Attachment
import com.localkeep.core.model.File
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.RevealAuth
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.lkTag
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
import com.localkeep.core.ui.theme.LkMonogramTile
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSearchField
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LkTintButton
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkScaledMaxLines
import com.localkeep.core.ui.theme.lkStaggerDelay
import com.localkeep.core.vault.PurgeScope
import com.localkeep.core.vault.TrashedFolder
import com.localkeep.feature.files.SecureMediaViewer
import com.localkeep.feature.files.R as FilesR

/**
 * Trash.
 *
 * Coral throughout, and both destructive actions are behind a confirmation. Everything else in this
 * app is recoverable; these two are not, and that difference should be visible before the tap
 * rather than explained afterwards.
 */
@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    onBack: () -> Unit,
) {
    val c = LkTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    // What is actually in the trash, which is not what this screen lists as rows: an item that went
    // down with a folder is represented by that folder's row, not one of its own. The header and the
    // Empty Trash sentence name this number; the search clause inside `emptyTrashBody` still compares
    // the flat `totalCount`, because a search only ever narrows the flat rows.
    // Straight from the projection, which counts it with the purge's own predicate. This used to be
    // `flatCount + folderRows.sumOf { itemCount }`, and that reasoning held only while every trashed
    // item was represented by either a flat row or a root folder row. Nested trashing breaks that:
    // a folder trashed inside an already-trashed parent is neither, so its items were counted
    // nowhere and the confirmation read "removes all 0 items" over a subtree it then destroyed.
    val trashedItemTotal = state.purgeableItemCount
    // `rememberSaveable`, so rotating the device does not silently retract a "delete forever"
    // confirmation. The dialog vanishing looks like it was answered, and the answer it looks like
    // is the destructive one — the user is left unsure whether the item is gone.
    //
    // The pending delete is held as the item's id rather than the closure that performs it: a
    // lambda cannot go into a Bundle, and keeping the id means the confirmation is re-derived from
    // the live list, so an item that disappeared underneath it cannot be deleted by a stale capture.
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    // The entry whose read-only preview is open. An id for the same reason the confirmations hold
    // one: it survives rotation in a Bundle, and it is re-resolved against the live list, so a
    // preview cannot outlive the entry it is about.
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    val restoredNotice = stringResource(R.string.vaultlist_notice_item_restored)
    val restoreUndoLabel = stringResource(R.string.vaultlist_notice_undo)
    val restoreUndoneNotice = stringResource(R.string.vaultlist_notice_restore_undone)
    val itemRestoredToRootNotice = stringResource(R.string.vaultlist_notice_item_restored_to_root)
    // A template rather than a resolved string, mirroring folderRestoredToNotice below: which
    // destination it names is decided by the write, so the ViewModel formats it once
    // `restoreItemNotice` has read where the item actually landed.
    val itemRestoredToTemplate = stringResource(R.string.vaultlist_notice_item_restored_to)
    val itemRestoredToNotice: (String) -> String =
        remember(itemRestoredToTemplate) { { name -> String.format(itemRestoredToTemplate, name) } }
    val untitledDialogTitle = stringResource(R.string.vaultlist_item_untitled)
    val deletedForeverNotice = stringResource(R.string.vaultlist_notice_item_deleted_forever)
    val trashEmptiedNotice = stringResource(R.string.vaultlist_notice_trash_emptied)
    val folderRestoredNotice = stringResource(R.string.vaultlist_notice_folder_restored)
    val folderRestoredToRootNotice = stringResource(R.string.vaultlist_notice_folder_restored_to_root)
    val folderDeletedForeverNotice = stringResource(R.string.vaultlist_notice_folder_deleted_forever)
    val purgeStaleNotice = stringResource(R.string.vaultlist_notice_purge_stale)
    // A template rather than a resolved string: which destination it names is decided by the write,
    // so the ViewModel formats it once the restore has reported where the folder actually landed.
    val folderRestoredToTemplate = stringResource(R.string.vaultlist_notice_folder_restored_to)
    val folderRestoredToNotice: (String) -> String =
        remember(folderRestoredToTemplate) { { name -> String.format(folderRestoredToTemplate, name) } }

    // The preview's media viewer, mounted ahead of the screen it replaces rather than layered over
    // it — the same shape AttachmentPickerScreen and FileItemEditScreen both use, and for the same
    // reason: without its own BackHandler the system Back gesture goes past the viewer to the nav
    // host and pops Trash entirely, closing the viewer and the screen in one gesture.
    //
    // `onDelete` is deliberately absent. Everything on show here is already in the trash, and a
    // second, unconfirmed route to destroying it — inside a surface whose whole purpose is *looking*
    // before deciding — is exactly the trap the row's coral button avoids by asking first.
    val previewItem = state.items.firstOrNull { it.id.toString() == previewId }
    val previewFiles = previewItem?.let { previewableFiles(it) }.orEmpty()
    val viewingAttachment = previewFiles.firstOrNull { it.id.toString() == state.viewingAttachmentId }

    // The challenge that fronts the viewer, and the reason this screen has one at all.
    //
    // A trashed File item's bytes are the same bytes the live route guards: FileItemViewerScreen
    // challenges unconditionally before it mounts a decrypting renderer, and FileItemEditScreen's
    // viewer gate states the rule — "a gate one route enforces and another does not is a gate nobody
    // can rely on, so the cheaper route is raised to the stricter one". Trash used to be that cheaper
    // route, and the precondition was free to make: anyone holding the unlocked phone can trash an
    // item from its row with no challenge, look at it here, and restore it. Unconditional, not keyed
    // on `reprompt` or the high-value types, because one viewer pages across an item's payload and
    // all its attachments — a per-file gate would be a swipe away from the file it was protecting.
    //
    // Shape copied from FileItemViewerScreen, order included: the session token is taken *before*
    // the prompt goes up, so a lock landing while it is on screen cannot be answered with a key from
    // the session that ended.
    val revealTitle = stringResource(FilesR.string.files_edit_viewer_reveal_title)
    val revealSubtitle = stringResource(FilesR.string.files_edit_viewer_reveal_subtitle)
    val context = LocalContext.current
    LaunchedEffect(state.pendingPreviewReveal) {
        if (!state.pendingPreviewReveal) return@LaunchedEffect
        val token = viewModel.beginReveal()
        val activity = context as? FragmentActivity
        val outcome = when {
            token == null || activity == null -> RevealAuth.RevealOutcome.Error(-1)
            // A device with no screen lock and no enrolled biometric cannot be challenged at all, so
            // this refuses — and says so through `previewGateError` below rather than leaving a tap
            // that does nothing. Same decision, and the same reason, as AttachmentPickerScreen's.
            !RevealAuth.isAvailable(context) -> RevealAuth.RevealOutcome.Unavailable
            else -> RevealAuth.authenticate(
                activity = activity,
                title = revealTitle,
                subtitle = revealSubtitle,
            )
        }
        viewModel.completeReveal(token, outcome)
    }

    if (previewItem != null && viewingAttachment != null) {
        val sessionKey = viewModel.previewSessionKeyOrNull()
        if (sessionKey != null) {
            BackHandler(enabled = true) { viewModel.closePreviewViewer() }
            SecureMediaViewer(
                attachments = previewFiles,
                startId = viewingAttachment.id.toString(),
                sessionKey = sessionKey,
                fileBlobStore = viewModel.fileBlobStore,
                onClose = viewModel::closePreviewViewer,
            )
            return
        }
        // The vault locked between the tap and this frame. The lock is already routing the app to
        // the unlock screen; drop the viewer rather than render an empty one. In an effect, not
        // inline: writing composable state during composition is how a recomposition loop starts.
        LaunchedEffect(Unit) { viewModel.closePreviewViewer() }
    }

    LkScreen(accent = c.coral, auraAlpha = 0.06f, auraBias = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = LkFrame.screenHorizontal)
                .padding(top = LkFrame.screenTop),
        ) {
            val emptyTrashLabel = stringResource(R.string.vaultlist_trash_empty_button)
            LkScreenHeader(
                title = stringResource(R.string.vaultlist_trash_title),
                // Absent when there is nothing in the trash: "0 items · still encrypted" sat above
                // "Trash is empty", one fact stated twice, the first of them as a count of nothing.
                subtitle = if (trashCountSubtitleShown(trashedItemTotal, state.totalCount, state.folders.size)) {
                    trashHeaderSubtitle(trashedItemTotal, state.folders.sumOf { it.folderCount })
                } else {
                    null
                },
                modifier = Modifier.lkEnter(),
                trailing = {
                    LkIconButton(icon = LkIcons.ChevronLeft, label = stringResource(R.string.vaultlist_trash_back), onClick = onBack, plateAlignment = Alignment.CenterEnd)
                },
            )

            Spacer(Modifier.height(16.dp))

            // Only once there is enough here to be worth narrowing; a search box over three rows is
            // furniture. Trash never expires on a timer, so this grows without bound.
            if (state.totalCount > SEARCH_THRESHOLD) {
                LkSearchField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchChanged,
                    placeholder = stringResource(R.string.vaultlist_trash_search_placeholder),
                )
                Spacer(Modifier.height(16.dp))
            }

            state.error?.let { errorRes ->
                val message = stringResource(errorRes)
                LkInlineError(
                    message = message,
                    modifier = Modifier.announce(message),
                    actionLabel = stringResource(R.string.vaultlist_error_retry).takeIf { state.loadFailed },
                    onAction = viewModel::retryLoad.takeIf { _ -> state.loadFailed },
                )
                Spacer(Modifier.height(16.dp))
            }

            // Not `isEmpty()` alone: a failed read also produces an empty list, and "Trash is empty"
            // on top of "couldn't read the trash" tells the user something the app does not know.
            // A search that matched nothing is a third case again — the trash is not empty, the
            // query just missed, and offering "Empty trash" there would be aimed at rows the user
            // cannot currently see.
            if (state.items.isEmpty() && state.folders.isEmpty() && !state.loadFailed) {
                val searching = state.searchQuery.isNotBlank()
                LkEmptyState(
                    icon = if (searching) LkIcons.Search else LkIcons.Trash,
                    title = if (searching) {
                        stringResource(R.string.vaultlist_trash_empty_no_matches)
                    } else {
                        stringResource(R.string.vaultlist_trash_empty_title)
                    },
                    body = if (searching) {
                        stringResource(R.string.vaultlist_trash_empty_body_search)
                    } else {
                        stringResource(R.string.vaultlist_trash_empty_body)
                    },
                    modifier = Modifier.padding(top = 48.dp).lkEnter(),
                )
            } else {
                LkTintButton(
                    text = emptyTrashLabel,
                    icon = LkIcons.Trash,
                    accent = c.coral,
                    onClick = {
                            confirmEmpty = true
                            viewModel.preparePurge(PurgeScope.AllTrash)
                        },
                    // The same guard the row actions carry, for the failure `enabled`'s own KDoc
                    // describes: a restore or a delete-forever still in flight makes
                    // `mutateReporting` re-entrant, so `confirmPurge` clears `pendingPurge`, hits
                    // that guard and returns — the dialog closes having promised to empty the trash
                    // and nothing happened, with nothing written to the screen either way.
                    enabled = !state.isWorking,
                    modifier = Modifier.lkTag("trash-empty").lkEnter(delayMillis = 40),
                )
                Spacer(Modifier.height(16.dp))
                val untitled = stringResource(R.string.vaultlist_item_untitled)
                val trailEllipsis = stringResource(R.string.vaultlist_row_folder_trail_ellipsis)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    if (state.folders.isNotEmpty()) {
                        item(key = "folders-header") {
                            Text(
                                stringResource(R.string.vaultlist_trash_folders_section),
                                style = LkText.sectionLabel,
                                color = c.textSecondary,
                                modifier = Modifier.padding(bottom = 8.dp).lkEnter(),
                            )
                        }
                        itemsIndexed(
                            state.folders,
                            key = { _, row -> "folder-${row.folder.id}" },
                        ) { index, row ->
                            TrashFolderRow(
                                row = row,
                                enabled = !state.isWorking,
                                index = index,
                                onRestore = {
                                    viewModel.restoreFolder(
                                        id = row.folder.id,
                                        confirmation = folderRestoredNotice,
                                        confirmationAt = folderRestoredToNotice,
                                        confirmationAtRoot = folderRestoredToRootNotice,
                                    )
                                },
                                onDeleteForever = {
                            confirmDeleteFolderId = row.folder.id.toString()
                            viewModel.preparePurge(PurgeScope.Folder(row.folder.id))
                        },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        item(key = "folders-gap") { Spacer(Modifier.height(8.dp)) }
                    }
                    if (
                        trashNoMatchesInList(
                            itemCount = state.items.size,
                            folderCount = state.folders.size,
                            searching = state.searchQuery.isNotBlank(),
                            loadFailed = state.loadFailed,
                        )
                    ) {
                        item(key = "trash-no-matches") {
                            LkEmptyState(
                                icon = LkIcons.Search,
                                title = stringResource(R.string.vaultlist_trash_empty_no_matches),
                                body = stringResource(R.string.vaultlist_trash_empty_body_search),
                                modifier = Modifier.padding(top = 24.dp).lkEnter(),
                            )
                        }
                    }
                    itemsIndexed(state.items, key = { _, item -> item.id.toString() }) { index, item ->
                        val title = item.title.ifBlank { untitled }
                        val subtitleText = item.subtitle(
                            loginFallback = stringResource(R.string.vaultlist_subtitle_login_fallback),
                            totpFallback = stringResource(R.string.vaultlist_subtitle_totp_fallback),
                            cardFallback = stringResource(R.string.vaultlist_subtitle_card_fallback),
                            identityFallback = stringResource(R.string.vaultlist_subtitle_identity_fallback),
                            noteText = stringResource(R.string.vaultlist_subtitle_note),
                            fileText = stringResource(R.string.vaultlist_subtitle_file),
                            passkeyText = stringResource(R.string.vaultlist_subtitle_passkey),
                            coordinateText = stringResource(R.string.vaultlist_subtitle_coordinate),
                            phraseText = stringResource(R.string.vaultlist_subtitle_phrase),
                        )
                        LkGroup(modifier = Modifier.lkEnter(delayMillis = lkStaggerDelay(index))) {
                            LkGroupRow(
                                first = true,
                                // Opens the read-only preview. Trash was the one screen where the
                                // only two things you could do to an entry were restore it or
                                // destroy it, with no way to look at it first — and for a scanned
                                // ID or a photograph the title is not enough to decide on.
                                onClick = { previewId = item.id.toString() },
                                onClickLabel = stringResource(R.string.vaultlist_trash_preview_action, title),
                            ) {
                                TrashItemRowBody(
                                    title = title,
                                    subtitle = subtitleText,
                                    folderTrail = folderTrail(state.folderTree, item.folderId, trailEllipsis),
                                    restoreLabel = stringResource(R.string.vaultlist_trash_restore_action, title),
                                    onRestore = {
                                        viewModel.restore(
                                            id = item.id,
                                            confirmation = restoredNotice,
                                            confirmationAt = itemRestoredToNotice,
                                            confirmationAtRoot = itemRestoredToRootNotice,
                                            undoLabel = restoreUndoLabel,
                                            undoneNotice = restoreUndoneNotice,
                                        )
                                    },
                                    deleteLabel = stringResource(R.string.vaultlist_trash_delete_forever_action, title),
                                    onDelete = { confirmDeleteId = item.id.toString() },
                                    // Both row actions rewrite the whole vault. While one is in
                                    // flight the row still looks idle, so the buttons say so
                                    // rather than silently swallowing the second tap.
                                    enabled = !state.isWorking,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        // Every trashed folder, nested ones included: `trashedFolderRows` lists roots only and each
        // carries its own subtree count. The purge takes all of them, so a dialog naming only items
        // understated what "Empty trash" destroys whenever the trash held folders.
        val trashedFolderTotal = state.folders.sumOf { it.folderCount }
        val phrasing = emptyTrashPhrasing(itemTotal = trashedItemTotal, folderTotal = trashedFolderTotal)
        DestructiveDialog(
            title = stringResource(R.string.vaultlist_trash_empty_confirm_title),
            body = emptyTrashBody(
                totalCount = state.totalCount,
                shownCount = state.items.size,
                // `trashedItemTotal`, not `state.totalCount`. This is tier-A copy on the only
                // irreversible control in the app, and it says "all N items" — while the purge takes
                // every visible deleted record, folder-contained ones included. Naming the flat count
                // understated the loss by exactly the contents of every trashed folder.
                removesPhrase = pluralStringResource(phrasing.removesRes, phrasing.removesCount, phrasing.removesCount),
                hidingClause = stringResource(R.string.vaultlist_trash_empty_hiding_clause),
                noUndoClause = stringResource(R.string.vaultlist_trash_empty_no_undo_clause),
                foldersClause = if (phrasing.showFoldersClause) {
                    pluralStringResource(
                        R.plurals.vaultlist_trash_empty_and_folders,
                        trashedFolderTotal,
                        trashedFolderTotal,
                    )
                } else {
                    ""
                },
            ),
            confirmLabel = stringResource(R.string.vaultlist_trash_empty_button),
            onConfirm = {
                confirmEmpty = false
                viewModel.confirmPurge(trashEmptiedNotice, purgeStaleNotice)
            },
            onDismiss = {
                confirmEmpty = false
                viewModel.cancelPurge()
            },
        )
    }

    // Same resolution rule as the item dialog below: read from the live list, so a folder restored
    // or purged underneath the prompt takes the prompt with it.
    state.folders.firstOrNull { it.folder.id.toString() == confirmDeleteFolderId }?.let { row ->
        val name = row.folder.name.ifBlank { stringResource(R.string.vaultlist_item_untitled) }
        val folderPhrase =
            pluralStringResource(R.plurals.vaultlist_trash_folder_folder_count, row.folderCount, row.folderCount)
        val itemPhrase =
            pluralStringResource(R.plurals.vaultlist_trash_folder_item_count, row.itemCount, row.itemCount)
        DestructiveDialog(
            title = stringResource(R.string.vaultlist_trash_folder_delete_confirm_title, name),
            // Names the subtree count, because "delete forever" on one row here destroys things the
            // row does not show — the folder's own children and their items.
            // The two counts go in as separate arguments rather than pre-joined into the summary
            // string. Nesting them read the same on screen and put this site outside
            // ComposedPluralAgreementTest's reach — its detector sees a plural bound to a local and
            // handed to `stringResource`, and a plural buried two calls deep is invisible to it. A
            // security-copy string that escapes the agreement register is exactly the one that
            // should not.
            body = stringResource(
                R.string.vaultlist_trash_folder_delete_confirm_body,
                folderPhrase,
                itemPhrase,
            ),
            confirmLabel = stringResource(R.string.vaultlist_trash_delete_confirm_action),
            onConfirm = {
                confirmDeleteFolderId = null
                viewModel.confirmPurge(folderDeletedForeverNotice, purgeStaleNotice)
            },
            onDismiss = {
                confirmDeleteFolderId = null
                viewModel.cancelPurge()
            },
        )
    }

    // The read-only preview. Same resolution rule as the confirmations below.
    if (previewItem != null && state.viewingAttachmentId == null) {
        val title = previewItem.title.ifBlank { untitledDialogTitle }
        TrashPreviewDialog(
            item = previewItem,
            title = title,
            enabled = !state.isWorking,
            // Asks for the challenge; it cannot open the file itself. The viewer's own state is
            // written only where a reveal passes, so there is no second route to it from here.
            onOpenAttachment = viewModel::requestPreviewReveal,
            gateError = state.previewGateError,
            onRestore = {
                previewId = null
                viewModel.clearPreviewGateError()
                viewModel.restore(
                    id = previewItem.id,
                    confirmation = restoredNotice,
                    confirmationAt = itemRestoredToNotice,
                    confirmationAtRoot = itemRestoredToRootNotice,
                    undoLabel = restoreUndoLabel,
                    undoneNotice = restoreUndoneNotice,
                )
            },
            onDismiss = {
                previewId = null
                viewModel.clearPreviewGateError()
            },
        )
    }

    // Resolved against the current list, not against what was on screen when the button was
    // tapped: if the item has since left the trash the dialog simply does not appear, rather than
    // confirming the deletion of something that is no longer there.
    state.items.firstOrNull { it.id.toString() == confirmDeleteId }?.let { item ->
        val title = item.title.ifBlank { stringResource(R.string.vaultlist_item_untitled) }
        DestructiveDialog(
            title = stringResource(R.string.vaultlist_trash_delete_confirm_title, title),
            body = stringResource(R.string.vaultlist_trash_delete_confirm_body),
            confirmLabel = stringResource(R.string.vaultlist_trash_delete_confirm_action),
            onConfirm = {
                confirmDeleteId = null
                viewModel.deleteForever(item.id, deletedForeverNotice)
            },
            onDismiss = { confirmDeleteId = null },
        )
    }
}

/**
 * A read-only look at a trashed entry, before deciding whether to keep it.
 *
 * Trash offered exactly two acts on a row — restore it or destroy it forever — and no way to see
 * what the row *was*. Reported 2026-09-02: *"I open it, I cannot see the actual file content, say
 * it's an image or an ID; I can't click on it"*. A title and a monogram are enough to pick between
 * two unrelated entries and not enough to pick between two scans, which is precisely the case where
 * getting it wrong is unrecoverable.
 *
 * Read-only in the strict sense: it shows the identifying line the vault list shows and the names of
 * the attachments, and it opens an attachment in the ordinary secure viewer. It reveals no secret —
 * a deleted item must not become a way around the reveal gate, so nothing here decrypts a field, and
 * [VaultItem.subtitle] is used exactly because it is the projection already deemed safe to render on
 * a row that gets read over shoulders.
 *
 * That last claim was half true until 2026-09-07. "The ordinary secure viewer" meant the composable,
 * and the composable is not the gate — the gate is the [RevealAuth] challenge the live routes raise
 * before mounting it. This dialog mounted it off a plain session key, so a File item's payload, the
 * one type whose content *is* the file, opened here with no challenge while the same bytes were
 * gated everywhere else. The precondition cost nothing to create: trashing an item from its row is
 * itself ungated, and Restore is offered right here. The challenge now fronts every open — see the
 * `LaunchedEffect(state.pendingPreviewReveal)` in [TrashScreen] — so the sentence above is a fact
 * about the whole route rather than about the fields this dialog happens not to print.
 *
 * Restore is offered here, because "look at it, then keep it" is the whole journey. Delete forever is
 * not: it stays on the row, behind its own confirmation, rather than gaining a second entry point
 * one tap from a surface people open in order to *browse*.
 */
/**
 * Everything on [item] that the secure viewer can open, newest concept first.
 *
 * A **File item keeps its file in its own [File] payload, not in [VaultItem.attachments]**
 * — so reading `attachments` alone told the one item type that *is* a file that it had no files. A
 * photograph moved to the trash opened a preview reading "No files attached to this entry", with
 * nothing to tap: reported 2026-09-03, *"I tried to click on it to preview it — nothing"*. The
 * projection mirrors `FileItemViewerScreen`, down to using the item's own id as the attachment id,
 * so a File item addresses the same way in both viewers.
 *
 * The payload comes first because on a File item it is the subject and the attachments are extras.
 */
internal fun previewableFiles(item: VaultItem): List<Attachment> {
    val payload = (item.content as? File)
        ?.takeIf { it.blobRef.isNotBlank() }
        ?.let {
            Attachment(
                id = item.id,
                filename = it.filename,
                mime = it.mime,
                sizeBytes = it.sizeBytes,
                blobRef = it.blobRef,
                thumbnailRef = it.thumbnailRef,
            )
        }
    return listOfNotNull(payload) + item.attachments
}

@Composable
private fun TrashPreviewDialog(
    item: VaultItem,
    title: String,
    enabled: Boolean,
    onOpenAttachment: (String) -> Unit,
    @StringRes gateError: Int?,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LkTheme.colors
    val subtitleText = item.subtitle(
        loginFallback = stringResource(R.string.vaultlist_subtitle_login_fallback),
        totpFallback = stringResource(R.string.vaultlist_subtitle_totp_fallback),
        cardFallback = stringResource(R.string.vaultlist_subtitle_card_fallback),
        identityFallback = stringResource(R.string.vaultlist_subtitle_identity_fallback),
        noteText = stringResource(R.string.vaultlist_subtitle_note),
        fileText = stringResource(R.string.vaultlist_subtitle_file),
        passkeyText = stringResource(R.string.vaultlist_subtitle_passkey),
        coordinateText = stringResource(R.string.vaultlist_subtitle_coordinate),
        phraseText = stringResource(R.string.vaultlist_subtitle_phrase),
    )
    LkDialog(
        onDismiss = onDismiss,
        title = title,
        body = subtitleText,
        icon = LkIcons.Eye,
        confirm = LkDialogAction(
            label = stringResource(R.string.vaultlist_trash_preview_restore),
            onClick = onRestore,
            enabled = enabled,
        ),
        dismissLabel = stringResource(R.string.vaultlist_trash_preview_close),
    ) {
        // A refused challenge says why, here rather than nowhere. Without this the row stays
        // tappable, a device with no screen lock raises no prompt at all, and the tap simply does
        // nothing — the dead tap AttachmentPickerScreen's gate was already fixed for.
        // No top padding of its own: LkDialogSurface's Column already arranges its children with
        // `spacedBy(18.dp)`, so a dp added here would be spaced as well as padded.
        gateError?.let { message ->
            val text = stringResource(message)
            LkInlineError(text, Modifier.announce(text))
        }
        val files = previewableFiles(item)
        if (files.isEmpty()) {
            // Said, not left blank. An entry with nothing to open and an entry whose files failed to
            // list look identical if the section is simply absent.
            Text(
                stringResource(R.string.vaultlist_trash_preview_no_files),
                style = LkText.subtitle,
                color = c.textFaint,
                modifier = Modifier.padding(top = LkSpace.sm),
            )
        } else {
            // Wrapped in an LkGroup, because LkGroupRow draws a full-bleed strip meant to sit inside
            // one — the separator between rows is the group's, not the row's.
            Box(Modifier.fillMaxWidth().padding(top = LkSpace.sm)) {
                LkGroup {
                    files.forEachIndexed { index, att ->
                        LkGroupRow(
                            first = index == 0,
                            horizontalArrangement = Arrangement.spacedBy(LkSpace.md),
                            onClick = { onOpenAttachment(att.id.toString()) },
                            onClickLabel = stringResource(R.string.vaultlist_trash_preview_open_file, att.filename),
                        ) {
                            Icon(
                                imageVector = LkIcons.Attachment,
                                contentDescription = null,
                                tint = c.jade,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                att.filename,
                                style = LkText.rowTitle,
                                color = c.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The gap between Restore and Delete forever. Zero: the two 48dp touch boxes abut and never overlap.
 *
 * Not a visual zero. [LkIconButton] draws a 34dp plate centred in its 48dp box, so the plates are
 * `TRASH_ACTION_GAP + 14dp` apart. It was 16dp on an item row (a `spacedBy(LkSpace.md)` row, so
 * ~30dp to the eye) and 8dp on a folder row (a hand-placed `Spacer`, ~22dp) — the two rows
 * disagreed with each other, and both read as a gap wide enough to belong to two different groups
 * (reported 2026-10-12: "Why is there so much spacing in trash between trashbin icon and green
 * one?"). Below zero the touch targets would overlap on a destructive control, so this is as tight
 * as the 48dp floor allows.
 */
internal val TRASH_ACTION_GAP = 0.dp

/**
 * Restore (jade) and Delete forever (coral), as one unit.
 *
 * The pair owns its own gap so the item row and the folder row cannot disagree about it again, and
 * so it reaches its parent as a single child: a row that arranges with `spacedBy` spaces *between
 * children*, and two loose buttons are two children.
 */
@Composable
internal fun TrashRowActions(
    restoreLabel: String,
    onRestore: () -> Unit,
    deleteLabel: String,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    val c = LkTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(TRASH_ACTION_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Undo (a bin with a return arrow), not Export: this puts the entry back, and an arrow
        // leaving a box promises a copy going out, which a restore is not.
        LkIconButton(
            icon = LkIcons.Undo,
            label = restoreLabel,
            onClick = onRestore,
            enabled = enabled,
            size = 34.dp,
            iconSize = 22.dp,
            tint = c.jade,
            container = c.jade.copy(alpha = 0.08f),
            ring = c.jade.copy(alpha = 0.16f),
            shape = LkShape.sm,
        )
        LkIconButton(
            icon = LkIcons.Trash,
            label = deleteLabel,
            onClick = onDelete,
            enabled = enabled,
            size = 34.dp,
            iconSize = 22.dp,
            tint = c.coral,
            container = c.coral.copy(alpha = 0.08f),
            ring = c.coral.copy(alpha = 0.16f),
            shape = LkShape.sm,
        )
    }
}

/**
 * One trashed item's row: tile, title over its identifying lines, and Restore / Delete forever.
 *
 * Beside the title there are the tile, two gaps and two 48dp targets, and they take their width
 * first. At 308dp that left a title 72dp, so a one-token name like "TrashMe" broke as "Trash/Me"
 * (SCL-027). Under the same rule the folder rows use ([nameYields]) the two buttons drop to a row of
 * their own beneath the title, end-aligned, when the title would get less than a word's room.
 *
 * The tile grows with the font, up to 1.5x. The monogram plate has no floor of its own (it takes
 * the glyph's size once the glyph is bigger than the plate), so at 2.0 a wide monogram sat flush
 * against the plate's edge with a dp or two to spare; a plate scaled with the text keeps the same
 * margin it has at the default size.
 *
 * Its own composable, taking labels and callbacks rather than the ViewModel, so the geometry can be
 * measured without one.
 */
@Composable
internal fun TrashItemRowBody(
    title: String,
    subtitle: String,
    folderTrail: String?,
    restoreLabel: String,
    onRestore: () -> Unit,
    deleteLabel: String,
    onDelete: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val fontScale = LocalDensity.current.fontScale
    val tile = TrashTile * fontScale.coerceIn(1f, 1.5f)
    val titleWord = rememberWidestWord(title, LkText.rowTitle)
    BoxWithConstraints(modifier) {
        val stacked = nameYields(maxWidth, tile + LkSpace.md * 2 + TrashActionsWidth, fontScale, titleWord)
        val identity: @Composable RowScope.() -> Unit = {
            LkMonogramTile(title, size = tile, shape = LkShape.sm)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = LkText.rowTitle,
                    color = c.textPrimary,
                    // Held at one line the title lost roughly three quarters of
                    // its characters at the largest font size, so two logins
                    // differing in a suffix read identically on the one screen
                    // where the choice is restore-or-destroy.
                    maxLines = lkScaledMaxLines(),
                    overflow = TextOverflow.Ellipsis,
                )
                // The identifying line the vault list has always carried, and
                // that this screen did not: two Google logins are the same row
                // without it, which is the worst place in the app to be unsure
                // which one you are about to delete forever.
                Text(
                    subtitle,
                    style = LkText.subtitle,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // And where it came from, for the same reason one step on:
                // Restore puts the item back in its folder, and until this
                // line existed the row said nothing about which folder that
                // was, so Restore was blind until it landed (2C-F8). Same
                // trail the vault list draws, same glyph, one step quieter —
                // and absent entirely for an item filed nowhere, rather than
                // costing every row a "no folder" marker.
                if (folderTrail != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LkSpace.xs),
                    ) {
                        Icon(
                            LkIcons.Folder,
                            contentDescription = null,
                            tint = c.textFaint,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            folderTrail,
                            style = LkText.subtitle,
                            color = c.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        val actions: @Composable () -> Unit = {
            TrashRowActions(
                restoreLabel = restoreLabel,
                onRestore = onRestore,
                deleteLabel = deleteLabel,
                onDelete = onDelete,
                enabled = enabled,
            )
        }
        if (!stacked) {
            // The tile leads the row, so the gap is a token rather than the 12dp that used to be
            // padded onto the text.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LkSpace.md),
            ) {
                identity()
                actions()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LkSpace.md),
                ) { identity() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        }
    }
}

/** The monogram plate at the default text size. */
private val TrashTile = 36.dp

/** Restore and Delete forever: two 48dp touch targets. */
private val TrashActionsWidth = 96.dp

/**
 * One trashed folder, standing for its whole subtree.
 *
 * The summary is built from **two** `pluralStringResource` calls joined by a template, not from one
 * `<plurals>`. Android picks a plural variant from exactly one quantity argument, so "1 item,
 * 3 folders" — singular beside plural — is unrepresentable in a single entry and comes out wrong in
 * any language whose categories differ from English's. `ImpliedQuantity` lint does not catch it,
 * because such a string is still nominally a `<plurals>`.
 */
@Composable
private fun TrashFolderRow(
    row: TrashedFolder,
    enabled: Boolean,
    index: Int,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val name = row.folder.name.ifBlank { stringResource(R.string.vaultlist_item_untitled) }
    val summary = trashFolderSummary(row.folderCount, row.itemCount)
    LkGroup(modifier = Modifier.lkEnter(delayMillis = lkStaggerDelay(index))) {
        LkGroupRow(first = true) {
            TrashFolderRowBody(
                name = name,
                summary = summary,
                restoreLabel = stringResource(R.string.vaultlist_trash_folder_restore_action, name),
                onRestore = onRestore,
                deleteLabel = stringResource(R.string.vaultlist_trash_folder_delete_action, name),
                onDelete = onDeleteForever,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "1 folder, 3 items" for a trashed folder's row; see [TrashFolderRow] for why it is two plurals. */
@Composable
internal fun trashFolderSummary(folderCount: Int, itemCount: Int): String {
    val folderPhrase = pluralStringResource(R.plurals.vaultlist_trash_folder_folder_count, folderCount, folderCount)
    // An empty folder says only that it is a folder: "1 folder, 0 items" read as two facts where
    // there is one, and was the line the narrow row clipped (S-W4-F2).
    if (itemCount == 0) return folderPhrase
    val itemPhrase = pluralStringResource(R.plurals.vaultlist_trash_folder_item_count, itemCount, itemCount)
    return stringResource(R.string.vaultlist_trash_folder_summary, folderPhrase, itemPhrase)
}

/**
 * A trashed folder's row content: glyph, name over its summary, Restore / Delete forever.
 * Its own composable, taking labels and callbacks rather than the ViewModel, so the geometry can be
 * measured without one.
 */
@Composable
internal fun TrashFolderRowBody(
    name: String,
    summary: String,
    restoreLabel: String,
    onRestore: () -> Unit,
    deleteLabel: String,
    onDelete: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val fontScale = LocalDensity.current.fontScale
    val nameWord = rememberWidestWord(name, LkText.rowTitle)
    BoxWithConstraints(modifier) {
        // The glyph, the name column's 12dp each side and the two buttons take their width first. At
        // 308dp and 2.0x that left the name ~112dp and "W4empty" broke as "W4emp/ty" (S-W4-F2), so
        // under the rule the item row and the folder screen use ([nameYields]) the buttons drop to
        // a row of their own beneath the name, end-aligned.
        val stacked = nameYields(maxWidth, TrashFolderGlyph + 24.dp + TrashActionsWidth, fontScale, nameWord)
        val identity: @Composable RowScope.() -> Unit = {
            // Plain icon rather than LkMonogramTile: a folder has no initial worth showing, and the
            // amber says "nothing is wrong here" — this row is an offer to restore, not a warning.
            Icon(
                imageVector = LkIcons.Folder,
                contentDescription = null,
                tint = c.amber,
                modifier = Modifier.size(TrashFolderGlyph),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    name,
                    style = LkText.rowTitle,
                    color = c.textPrimary,
                    // lkScaledMaxLines(), not a fixed 1, for the same reason TrashItemRow's title
                    // above uses it: a folder name is one of the user-named strings LkDynamicType.kt
                    // names explicitly, and a fixed cap loses most of it at the largest font size.
                    // Left mismatched with its sibling until 2026-09-13.
                    maxLines = lkScaledMaxLines(),
                    overflow = TextOverflow.Ellipsis,
                )
                // Wraps rather than clipping: this line is the count the user acts on (what a restore
                // returns, what a delete destroys), and "1 folder," with the rest cut off said less
                // than the row's own buttons promise.
                Text(summary, style = LkText.subtitle, color = c.textSecondary)
            }
        }
        val actions: @Composable () -> Unit = {
            TrashRowActions(
                restoreLabel = restoreLabel,
                onRestore = onRestore,
                deleteLabel = deleteLabel,
                onDelete = onDelete,
                enabled = enabled,
            )
        }
        if (!stacked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                identity()
                actions()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) { identity() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        }
    }
}

/** The folder glyph at the head of a trashed folder's row. */
private val TrashFolderGlyph = 20.dp

/**
 * Whether the "no matches" panel belongs inside the list rather than in place of it.
 *
 * The full-screen empty state a few hundred lines above requires *both* lists to be empty, and the
 * folder list is deliberately never narrowed by the query — a folder row stands for a subtree whose
 * items are absent from `items` by construction (see [TrashUiState.folders]). So one trashed folder
 * was enough to route a search that matched nothing into the else branch, where the folder rows
 * render and nothing anywhere says the query missed: the user is left reading a list that looks like
 * the answer. The UI/UX spec lists "no search results" among the mandatory empty states, and
 * `vaultlist_trash_empty_no_matches` / `_body_search` already exist for exactly this case.
 *
 * Inside the list, not instead of it. The folders are not a match failure — they were never tested
 * against the query — so hiding them to make room for the message would trade one wrong screen for
 * another, and would take a restorable subtree off screen while a search is running.
 *
 * A failed *read* is not a failed search: `loadFailed` empties both lists, and the error banner
 * above already says what happened.
 */
internal fun trashNoMatchesInList(
    itemCount: Int,
    folderCount: Int,
    searching: Boolean,
    loadFailed: Boolean,
): Boolean = searching && !loadFailed && itemCount == 0 && folderCount > 0

/**
 * The Trash header's count line.
 *
 * It counted items alone while trashed folders sit in the list directly beneath it, so "10 items"
 * undercounted what the screen offers to restore or destroy. With a folder in the trash it names
 * both, composed the way a folder row's own summary is — two plurals through one template, since a
 * single plural cannot agree with two counts — and with the same counts: [itemTotal] is the
 * purgeable count (items inside trashed folders included) and [folderTotal] includes nested folders,
 * the numbers the Empty trash confirmation uses.
 */
@Composable
internal fun trashHeaderSubtitle(itemTotal: Int, folderTotal: Int): String {
    if (folderTotal == 0) return pluralStringResource(R.plurals.vaultlist_trash_count_subtitle, itemTotal, itemTotal)
    val folderPhrase = pluralStringResource(R.plurals.vaultlist_trash_folder_folder_count, folderTotal, folderTotal)
    val itemPhrase = pluralStringResource(R.plurals.vaultlist_trash_folder_item_count, itemTotal, itemTotal)
    return stringResource(R.string.vaultlist_trash_count_subtitle_with_folders, folderPhrase, itemPhrase)
}

/**
 * Whether the header's "N items · still encrypted" line has anything to say.
 *
 * False only when the trash holds nothing at all — no items anywhere (the purgeable count includes
 * those inside trashed folders), no flat items and no folder rows. A trash holding just an empty
 * folder still shows it: the line is then true, and the screen has a row beneath it.
 */
internal fun trashCountSubtitleShown(purgeableItemCount: Int, flatTotal: Int, folderRows: Int): Boolean =
    purgeableItemCount > 0 || flatTotal > 0 || folderRows > 0

/**
 * What "Empty the trash?" promises to destroy.
 *
 * [totalCount], not the size of the list on screen: the list is search-filtered and `purgeTrash()`
 * is not, so with a search active the dialog used to say it would remove one item and then removed
 * every one. A pure function, and pulled out of the composable purely so a test can hold it to
 * that — the version that lived inline was wrong for as long as the search field has existed.
 *
 * When a search is narrowing the view, say so. Otherwise the number in the sentence and the number
 * of rows behind the dialog disagree, and the sentence is the one that is true.
 *
 * [removesPhrase], [foldersClause], [hidingClause] and [noUndoClause] are resolved by the caller — a
 * composable — from `vaultlist_trash_empty_*` string/plural resources, so this function stays plain
 * Kotlin and testable by `EmptyTrashBodyTest` without an Android context.
 */
internal fun emptyTrashBody(
    totalCount: Int,
    shownCount: Int,
    removesPhrase: String,
    hidingClause: String,
    noUndoClause: String,
    /** "and 2 folders", or empty when the trash holds none. Joined to [removesPhrase] with a space. */
    foldersClause: String = "",
): String = buildString {
    append(removesPhrase)
    if (foldersClause.isNotEmpty()) append(' ').append(foldersClause)
    if (shownCount != totalCount) append(hidingClause)
    // Said "There is no undo, and no copy anywhere else — the vault only exists on this device."
    // The first clause is true. The second was false twice over: a rolling snapshot taken before
    // the purge still holds these items, and so does an Auto Backup copy if the user left cloud
    // backup on. Claiming otherwise inside a confirmation for the one irreversible control in the
    // app is the worst place in the product to be wrong, in either direction — someone counting
    // on the deletion being total is misled, and someone who wanted the item back is talked out
    // of looking for it.
    append(noUndoClause)
}

@Composable
private fun DestructiveDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LkDialog(
        onDismiss = onDismiss,
        title = title,
        body = body,
        icon = LkIcons.Trash,
        accent = LkTheme.colors.coral,
        confirm = LkDialogAction(
            label = confirmLabel,
            onClick = onConfirm,
            emphasis = LkActionEmphasis.Destructive,
        ),
    )
}

/**
 * Which plural leads the empty-trash sentence, and with what count.
 *
 * Items lead and folders trail ("all 4 items and 2 folders") — except when the trash holds no
 * items, where that read "all 0 items and 1 folder". Then the folders lead and the trailing clause
 * is dropped so they are not named twice.
 */
internal class EmptyTrashPhrasing(
    @androidx.annotation.PluralsRes val removesRes: Int,
    val removesCount: Int,
    val showFoldersClause: Boolean,
)

internal fun emptyTrashPhrasing(itemTotal: Int, folderTotal: Int): EmptyTrashPhrasing =
    if (itemTotal == 0 && folderTotal > 0) {
        EmptyTrashPhrasing(R.plurals.vaultlist_trash_empty_removes_folders_only, folderTotal, showFoldersClause = false)
    } else {
        EmptyTrashPhrasing(R.plurals.vaultlist_trash_empty_removes_phrase, itemTotal, showFoldersClause = folderTotal > 0)
    }

/** Below this the trash fits on a screen and a search box is just furniture. */
private const val SEARCH_THRESHOLD = 6

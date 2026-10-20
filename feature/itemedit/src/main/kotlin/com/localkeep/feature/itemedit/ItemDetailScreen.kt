package com.localkeep.feature.itemedit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.model.FolderPickerState
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.CardBrands
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.PasswordHistory
import com.localkeep.core.model.ALWAYS_REAUTH_FIELDS
import com.localkeep.core.model.COORDINATE_FIELD
import com.localkeep.core.model.NOTE_BODY_FIELD
import com.localkeep.core.model.PHRASE_FIELD
import com.localkeep.core.model.isValid
import com.localkeep.core.model.PHRASE_PASSPHRASE_FIELD
import com.localkeep.core.model.TotpAlgorithm
import com.localkeep.core.totp.TotpGenerator
import com.localkeep.core.ui.ClipboardHelper
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.rememberHaptics
import com.localkeep.core.ui.secretFieldSemantics
import com.localkeep.core.ui.theme.LkBadge
import com.localkeep.core.ui.theme.LkCardDigitGroups
import com.localkeep.core.ui.theme.LkCardPreview
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkGroupRow
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkLiftedCard
import com.localkeep.core.ui.theme.LkMonogramTile
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkProgressRing
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSkeleton
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkCardNumberSlots
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkFocusRing
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkRing
import com.localkeep.core.ui.theme.lkStaggerDelay
import com.localkeep.core.ui.theme.lkTileColorsFor
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.core.vault.SeedPhraseStatus
import com.localkeep.feature.itemedit.ItemEditUiState.ItemType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * Read-only view of a vault item (UI/UX spec §5.1, §5.4).
 *
 * Opening an item used to drop straight into the editable form, which made every glance at a
 * password a chance to corrupt it with a stray keystroke. The read view is now the default and
 * Edit is a deliberate act.
 *
 * Shares [ItemEditViewModel] with the edit form on purpose: reveal gating, the auto-hide timer and
 * the ticking TOTP code are then literally the same code, so a secret can't end up better
 * protected on one screen than the other.
 */
@Composable
fun ItemDetailScreen(
    viewModel: ItemEditViewModel,
    itemId: String,
    onEdit: () -> Unit,
    onManageAttachments: (String) -> Unit = {},
    /**
     * Called once the trash write has landed, with the item's id and the three sentences the
     * confirmation needs, *before* [onBack] takes this screen off the stack (4A-F7).
     *
     * The Undo cannot be posted from the ViewModel: setting `trashed` pops this screen, which
     * clears that ViewModel, so an undo lambda closing over `viewModelScope` would run on a
     * cancelled scope and do nothing. The host owns a scope that outlives the screen — see
     * `TrashUndoBus` in `:app`. The strings are resolved here because `LkNotices` is deliberately
     * Context-free, the same split `VaultListViewModel.moveToTrash` already makes for the identical
     * notice.
     */
    onTrashed: (itemId: String, movedNotice: String, undoLabel: String, restoredNotice: String, restoreFailedNotice: String) -> Unit = { _, _, _, _, _ -> },
    onBack: () -> Unit,
) {
    val c = LkTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = state.title.ifBlank { stringResource(R.string.itemedit_detail_untitled) }

    // A fallback now, not the load path. The ViewModel reads the route argument at construction
    // (restoreRequestedItem), so by the time this composes the state already describes the item and
    // this does nothing — which is the point. A launched effect runs after the first frame is drawn,
    // so loading from here is what painted a placeholder frame on every single entry. It stays for
    // the one case construction cannot cover: a back stack restored while the vault was locked.
    LaunchedEffect(itemId) {
        if (viewModel.needsInitialLoad(itemId)) viewModel.loadItem(itemId)
    }
    RevealAuthEffect(state.pendingRevealField, viewModel, forCopy = state.copyAfterReveal)

    // rememberSaveable: a rotation while the prompt is up must not look like it was answered, and
    // the answer it would look like is the destructive one.
    var confirmTrash by rememberSaveable { mutableStateOf(false) }
    // Folder actions from view mode. Two stages, same reasoning as FolderPicker's own expand/rename
    // split: the sheet either offers Move/Remove, or — once Move is tapped — the folder list itself.
    // Saveable so a rotation mid-pick doesn't silently close the sheet under the user's thumb.
    var folderSheetOpen by rememberSaveable { mutableStateOf(false) }
    var folderSheetPicking by rememberSaveable { mutableStateOf(false) }
    // Only once the item is actually in the trash — see ItemEditViewModel.moveToTrash. The
    // confirmation and its Undo are handed to the host on the way out, because this screen and its
    // ViewModel are both about to stop existing (4A-F7).
    val movedNotice = stringResource(R.string.itemedit_notice_moved_to_trash)
    val undoLabel = stringResource(R.string.itemedit_notice_undo)
    val restoredNotice = stringResource(R.string.itemedit_notice_restored)
    val restoreFailedNotice = stringResource(R.string.itemedit_notice_restore_failed)
    LaunchedEffect(state.trashed) {
        if (state.trashed) {
            onTrashed(state.itemId.toString(), movedNotice, undoLabel, restoredNotice, restoreFailedNotice)
            onBack()
        }
    }
    // The item stopped being visible under us — a reveal session ended while this screen held it.
    // The ViewModel has already dropped the decrypted fields; this is what takes the screen off the
    // stack rather than leaving an emptied detail view sitting there looking like a broken item.
    LaunchedEffect(state.concealed) { if (state.concealed) onBack() }

    if (confirmTrash) {
        LkDialog(
            onDismiss = { confirmTrash = false },
            title = stringResource(R.string.itemedit_detail_trash_dialog_title),
            body = stringResource(R.string.itemedit_detail_trash_dialog_body),
            icon = LkIcons.Trash,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.itemedit_detail_trash_confirm),
                onClick = {
                    confirmTrash = false
                    viewModel.moveToTrash()
                },
                emphasis = LkActionEmphasis.Destructive,
            ),
            dismissLabel = stringResource(R.string.itemedit_detail_trash_dismiss),
        )
    }

    // The header aura takes the item's own tile colour, so opening an entry carries its identity
    // through from the list rather than resetting to a generic screen. While the lookup is still
    // running it has no identity to carry: `title` resolves to "Untitled" then, and that word
    // hashes to a colour of its own, so the aura would announce an item nobody opened.
    val loading = isShowingWrongItem(state, itemId)
    // Held and held-forever look identical in `loading`, and only one of them is a wait.
    val missing = viewModel.itemIsMissing(itemId)
    val tile = lkTileColorsFor(title)

    LkScreen(
        accent = if (loading) c.field else tile.container,
        auraAlpha = 0.26f,
        // Centred, as the comp grounds every item flow. Was 0.26.
        auraBias = 0.5f,
    ) {
        // Held back until the state describes *this* item. Rendering the default state meanwhile
        // drew a blank title and no fields, which is indistinguishable from an item that genuinely
        // has nothing in it — and the lookup is a linear scan over the vault, so it is not always
        // one frame. A skeleton says "not yet" where an empty form says "nothing here".
        // The vault was asked and did not have it. Before this branch existed the screen kept the
        // skeleton below instead — forever, since `loadItem`'s not-found path never adopts the
        // requested id — and `return@LkScreen` meant it never reached the block that renders
        // `state.error`, so the one sentence explaining the situation was unreachable *and* the
        // back chevron was not drawn either. A screen with no content, no explanation and no way
        // off it is the dead end §9 says an empty state must never be.
        //
        // Both strings here already ship in all five catalogues, which is why this treatment and
        // not an illustrated empty state: the richer §9 plate wants a headline and a line of prose
        // that do not exist yet, and new copy needs the DeepSeek pass that cannot run here.
        // `LkInlineError` is the right component regardless — its own doc reserves the action slot
        // for "errors that leave the user with nothing to press", which is exactly this one.
        if (missing) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = LkFrame.screenHorizontal)
                    .padding(top = LkFrame.screenTop),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                // The ordinary header, so the way out is where it always is rather than only inside
                // the message. Trash and Edit are deliberately absent: there is nothing to act on.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LkIconButton(
                        icon = LkIcons.ChevronLeft,
                        label = stringResource(R.string.itemedit_detail_back),
                        onClick = onBack,
                        plateAlignment = Alignment.CenterStart,
                    )
                }
                // Centred in what is left, not stacked under the header. Pinned to the top it read
                // as a banner with the screen's real content still to come below it — which is the
                // sentence the skeleton was already telling wrongly. In the middle of an empty
                // screen it reads as the whole answer, which it is.
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val message = stringResource(R.string.itemedit_error_item_not_found)
                    LkInlineError(
                        message = message,
                        modifier = Modifier.announce(message).lkEnter(),
                        actionLabel = stringResource(R.string.itemedit_detail_back),
                        onAction = onBack,
                    )
                }
            }
            return@LkScreen
        }
        if (loading) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = LkFrame.screenHorizontal)
                    .padding(top = LkFrame.screenTop),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                // The morph's destination has to exist for the whole load, or the shared element
                // has nothing to travel to. Keyed off the route id — always correct, unlike
                // `state.itemId`, which is precisely what lags — and drawn as a placeholder, which
                // states no identity at all rather than briefly stating the wrong one.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(15.dp),
                ) {
                    LkMonogramTile(
                        title = "",
                        size = 60.dp,
                        shape = LkShape.xxl,
                        textStyle = LkText.heading,
                        sharedKey = itemId,
                        morphsOnArrivalOnly = true,
                        placeholder = true,
                    )
                    LkSkeleton(Modifier.weight(1f).height(64.dp))
                }
                LkSkeleton(Modifier.fillMaxWidth().height(120.dp))
                LkSkeleton(Modifier.fillMaxWidth().height(120.dp))
            }
            return@LkScreen
        }
        Column(
            Modifier
                .fillMaxSize()
                // A tap anywhere outside an open inline edit ends it, saving — see
                // InlineEditTapAway.kt. Outside the scroll, so the whole viewport counts.
                .endInlineEditOnTapAway()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LkFrame.screenHorizontal)
                .padding(top = LkFrame.screenTop, bottom = LkFrame.gutterPlain),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // The blocks walk in rather than arriving together. Read top to bottom on every
            // composition, so a conditional block that is absent simply does not consume a step —
            // the visible sequence is always 0, 1, 2, … with no gap where a missing group was.
            var block = 0

            Row(
                modifier = Modifier.fillMaxWidth().lkEnter(lkStaggerDelay(block++)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LkIconButton(
                    icon = LkIcons.ChevronLeft,
                    label = stringResource(R.string.itemedit_detail_back),
                    onClick = onBack,
                    plateAlignment = Alignment.CenterStart,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Same act as the list's row action, on the item already open. Until
                    // 2026-09-20 (sweep 2B-6) this screen had neither the control nor an
                    // indicator, so an item the list announced as `favorite` could be opened and
                    // read with nothing saying so and no way to change it from here. Lands on the
                    // tap rather than waiting for a Save this screen does not have — see
                    // `toggleFavoriteNow`. Brass when set, which is the tint the vault list's own
                    // favourite dot uses; the label carries the state for a screen reader.
                    LkIconButton(
                        // Filled when set: brass alone is colour-only state, which a reader who
                        // cannot tell brass from grey could not see.
                        icon = if (state.favorite) LkIcons.StarFilled else LkIcons.Star,
                        label = stringResource(
                            if (state.favorite) {
                                R.string.itemedit_detail_favourite_remove
                            } else {
                                R.string.itemedit_detail_favourite_add
                            },
                            title,
                        ),
                        onClick = viewModel::toggleFavoriteNow,
                        tint = if (state.favorite) c.brass else c.textMuted,
                    )
                    // Deleting from here used to mean going back to the list, finding the row
                    // again, and trashing it there — a find-and-confirm loop for the item already
                    // on screen. Coral and behind a prompt, matching the list's bulk action.
                    LkIconButton(
                        icon = LkIcons.Trash,
                        label = stringResource(R.string.itemedit_detail_trash_icon_label, title),
                        onClick = { confirmTrash = true },
                        tint = c.coral,
                        container = c.coral.copy(alpha = 0.08f),
                        ring = c.coral.copy(alpha = 0.16f),
                    )
                    // Not offered for content the editor cannot represent: opening it there and
                    // saving would write an empty item over the real one. `save()` refuses that
                    // independently — this is so the user is never invited to try.
                    if (!state.readOnly) EditButton(onEdit)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().lkEnter(lkStaggerDelay(block++)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                LkMonogramTile(
                    title = title,
                    size = 60.dp,
                    shape = LkShape.xxl,
                    textStyle = LkText.heading,
                    sharedKey = itemId,
                    morphsOnArrivalOnly = true,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        title,
                        // `heading()` because this screen builds its own header instead of using
                        // LkScreenHeader, which supplies it everywhere else. Bypassing the shared
                        // header is deliberate (ScreenFrameConsistencyTest pins it, for the
                        // back-chevron slot) but nothing replaced the heading it carried, so
                        // TalkBack's navigate-by-heading gesture had no stop at all on the screen
                        // most used to read a credential (V20, 2026-09-12).
                        modifier = Modifier.semantics { heading() },
                        style = LkText.heading,
                        color = c.textStrong,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(state.type.readableName(), style = LkText.subtitle, color = c.textMuted)
                        if (state.reprompt) {
                            LkBadge(
                                stringResource(R.string.itemedit_detail_reprompt_badge),
                                accent = c.brass,
                                container = c.brass.copy(alpha = 0.10f),
                            )
                        }
                    }
                }
            }

            state.error?.let { errorRes ->
                // Announced, and not carried by colour alone (spec §2). Error text that just
                // appears below the content is, to a screen reader, a change that never happened.
                // `state` is a delegated property, so `state.errorArg` needs its own local val
                // before Kotlin can smart-cast it past the null check.
                val arg = state.errorArg
                val message = if (arg != null) stringResource(errorRes, arg) else stringResource(errorRes)
                LkInlineError(message, Modifier.announce(message).lkEnter(lkStaggerDelay(block++)))
            }

            // Wrapped because each of these emits several children into the parent column, and a
            // modifier can only be given to one node. The wrapper repeats the parent's own
            // arrangement, so the spacing is unchanged — a plain Column here would collapse the
            // gap between the fields group and the TOTP card.
            //
            // `lg`, from 22dp. What this separates is one section of the item from the next, which
            // is the relationship `lg` is defined to own. 22 was nobody's decision: it was typed,
            // and it made this screen — the one a user lands on every time they open an entry —
            // the only screen in the app whose rhythm came from no rung at all.
            Column(
                modifier = Modifier.fillMaxWidth().lkEnter(lkStaggerDelay(block++)),
                verticalArrangement = Arrangement.spacedBy(LkSpace.lg),
            ) {
                if (state.rendersNoContent()) {
                    EmptyItemDetail(state, onEdit)
                } else {
                    when (state.type) {
                        ItemType.LOGIN -> LoginDetail(state, viewModel)
                        ItemType.NOTE -> NoteDetail(state, viewModel, onEdit)
                        ItemType.TOTP -> TotpDetail(state, viewModel)
                        ItemType.CARD -> CardDetail(state, viewModel)
                        ItemType.IDENTITY -> IdentityDetail(state, viewModel)
                        ItemType.COORDINATE -> CoordinateDetail(state, viewModel)
                        ItemType.PHRASE -> PhraseDetail(state, viewModel)
                        ItemType.PASSKEY -> PasskeyDetail(state)
                    }
                }
            }

            // Folder before tags: both answer "how is this filed", and the folder is the coarser
            // of the two. Absent when the item is in none, exactly like the tags group below —
            // an empty "Folder: None" row is a fact nobody came here for.
            //
            // Missing entirely until 2026-07-31, which was the last place folders were invisible:
            // the list now has a folder strip and search matches folder names, but the one screen
            // devoted to a single item still could not say which folder that item was in. Reading
            // it meant opening the editor, where the picker doubles as the display.
            // Tappable only for items the editor can actually mutate — matches the EditButton gate
            // above. An unfiled item still gets the row (2026-08-12): the group used to be absent
            // entirely, which meant filing an item from its own view screen had no entry point at
            // all — the only way in was through the editor's picker. read-only items still render
            // nothing here at all, filed or not, matching the row's own no-tap fallback.
            val currentFolder = state.folders.firstOrNull { it.id == state.folderId }
            if (currentFolder != null || !state.readOnly) {
                DetailGroup(
                    stringResource(R.string.itemedit_folder_label),
                    Modifier.lkEnter(lkStaggerDelay(block++)),
                ) {
                    FolderReadField(
                        folderName = currentFolder?.name,
                        // The group heading above already says FOLDER.
                        showLabel = false,
                        onClick = if (!state.readOnly) {
                            { folderSheetOpen = true; folderSheetPicking = false }
                        } else {
                            null
                        },
                    )
                }
            }

            if (folderSheetOpen) {
                FolderActionSheet(
                    state = state,
                    picking = folderSheetPicking,
                    onStartPicking = { folderSheetPicking = true },
                    onPick = { folderId ->
                        viewModel.onFolderSelected(folderId)
                        viewModel.save()
                        folderSheetOpen = false
                        folderSheetPicking = false
                    },
                    onRemove = {
                        viewModel.onFolderSelected(null)
                        viewModel.save()
                        folderSheetOpen = false
                        folderSheetPicking = false
                    },
                    onDismiss = {
                        folderSheetOpen = false
                        folderSheetPicking = false
                    },
                )
            }

            if (state.tags.isNotEmpty()) {
                DetailGroup(
                    stringResource(R.string.itemedit_tags_label),
                    Modifier.lkEnter(lkStaggerDelay(block++)),
                ) {
                    ReadField(
                        stringResource(R.string.itemedit_tags_label),
                        state.tags.joinToString(", "),
                        first = true,
                        // The group heading above already says TAGS.
                        showLabel = false,
                    )
                }
            }

            // Only the fields that will actually draw something decide whether the heading draws.
            // `ReadField` returns early on a blank value — right for a built-in row, since an empty
            // USERNAME line is noise — but the heading above it did not know that, so a custom
            // field with a name and no value yet produced a "CUSTOM FIELDS" heading with nothing
            // under it. That reads as the field having been dropped, which is exactly the
            // conclusion the F16-era device pass drew; it is still in the editor, and still safe.
            // Found on the device 2026-08-30 while checking a different claim.
            val renderable = state.customFields.withIndex().filter { (_, field) ->
                // A hidden field draws its own masked placeholder, so it is visible even empty.
                field.type == CustomFieldType.HIDDEN || field.value.isNotBlank()
            }
            if (renderable.isNotEmpty()) {
                DetailGroup(
                    stringResource(R.string.itemedit_custom_fields_heading),
                    Modifier.lkEnter(lkStaggerDelay(block++)),
                ) {
                    // `position` decides the divider; `index` stays the field's real position in
                    // `state.customFields`, because it keys the reveal gate and the edit commit.
                    renderable.forEachIndexed { position, (index, field) ->
                        // A key the app wrote, not a name a person typed, gets a sentence a
                        // person can read — see [wellKnownCustomFieldLabelRes] (4A-F9). Display
                        // only: `field.name` is still the exact-match key `:credential` looks the
                        // binding up by on every ceremony.
                        val fieldLabel = wellKnownCustomFieldLabelRes(field.name)?.let { stringResource(it) }
                            ?: field.name.ifBlank {
                                stringResource(R.string.itemedit_detail_field_fallback, index + 1)
                            }
                        // A field the user marked Hidden is a secret, and the edit form treats it
                        // as one. Rendering it through the plain ReadField here meant the same
                        // value was masked on one screen and in the clear on the other — including
                        // for the hidden fields that arrive wholesale from a KeePass or Bitwarden
                        // import, which nobody in this app ever chose to mark.
                        if (field.type == CustomFieldType.HIDDEN) {
                            SecretReadField(
                                label = fieldLabel,
                                value = field.value,
                                field = "custom:$index",
                                state = state,
                                viewModel = viewModel,
                                first = position == 0,
                            )
                        } else {
                            ReadField(
                                label = fieldLabel,
                                value = field.value,
                                first = position == 0,
                                // Copyable like Username and Email on the same screen. A custom
                                // field is very often the thing worth copying — an account number,
                                // a licence key, a recovery code the user typed in by hand — and
                                // without this the only way to get one out was to open the editor.
                                copyable = true,
                                onCommit = if (!state.readOnly) {
                                    { viewModel.updateCustomFieldValue(index, it); viewModel.save() }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            AttachmentsLink(
                count = state.attachmentCount,
                imageCount = state.attachmentImageCount,
                onClick = { onManageAttachments(state.itemId.toString()) },
                modifier = Modifier.lkEnter(lkStaggerDelay(block++)),
            )

            // When this item arrived and when it last changed.
            //
            // Nothing in the app said. Not the detail screen, not the editor, not the filing sheet —
            // while the health dashboard ships an "Old passwords (90+ days)" category whose entire
            // basis is a date the user had no way to see, and the import wizard's merge decisions
            // turn on the same timestamps (F-36, 2026-09-15 visual pass). A screen cannot ask
            // someone to act on a date it will not show them.
            //
            // Last, above the privacy footer, because it is the least of what this screen is for:
            // metadata about the entry, under the entry. Absent on an unsaved item, which has no
            // dates yet, and each row absent on its own if the vault never recorded it — an import
            // sets no `passwordChangedAt`, and "Password changed: —" is a worse answer than none.
            val created = state.createdAt
            val updated = state.updatedAt
            val passwordChanged = state.passwordChangedAt
                ?.takeIf { state.type == ItemType.LOGIN }
            if (created != null || updated != null || passwordChanged != null) {
                DetailGroup(
                    stringResource(R.string.itemedit_detail_dates_heading),
                    Modifier.lkEnter(lkStaggerDelay(block++)),
                ) {
                    var firstRow = true
                    if (created != null && !addedRepeatsPasskeyCreated(state.type, created, state.passkeyCreatedAt)) {
                        ReadField(
                            stringResource(R.string.itemedit_detail_added_label),
                            created.asLocalDate(),
                            first = firstRow,
                        )
                        firstRow = false
                    }
                    if (updated != null) {
                        ReadField(
                            stringResource(R.string.itemedit_detail_updated_label),
                            updated.asLocalDate(),
                            first = firstRow,
                        )
                        firstRow = false
                    }
                    if (passwordChanged != null) {
                        ReadField(
                            stringResource(R.string.itemedit_detail_password_changed_label),
                            passwordChanged.asLocalDate(),
                            first = firstRow,
                        )
                    }
                }
            }

            // "Never left the device" was a blanket claim this screen cannot keep: its own Copy
            // buttons put plaintext on the system clipboard, which every app on the phone can read
            // until it clears. The narrow claim — no network, no sync — is the one that is
            // actually true, and it is the one worth making.
            Text(
                stringResource(R.string.itemedit_detail_screenshot_notice),
                style = LkText.caption,
                color = c.textFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .lkEnter(lkStaggerDelay(block++)),
            )
        }
    }
}

@Composable
private fun EditButton(onEdit: () -> Unit) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.itemedit_detail_edit)
    Box(
        modifier = Modifier
            .minTouchTarget()
            .lkFocusRing(LkShape.md)
            .clickable(interactionSource = interaction, indication = null, onClick = onEdit)
            .labelled(label),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(40.dp)
                .lkPressScale(interaction, pressedScale = 0.95f)
                .background(c.field, LkShape.md)
                .lkRing(LkShape.md, c.ringStrong)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = LkText.buttonSmall, color = c.textPrimary)
        }
    }
}

@Composable
private fun LoginDetail(state: ItemEditUiState, viewModel: ItemEditViewModel) {
    LkGroup {
        ReadField(
            stringResource(R.string.itemedit_username_label),
            state.username,
            first = true,
            copyable = true,
            onCommit = if (!state.readOnly) {
                { viewModel.onUsernameChanged(it); viewModel.save() }
            } else {
                null
            },
        )
        SecretReadField(
            stringResource(R.string.itemedit_password_label),
            state.password,
            "password",
            state,
            viewModel,
            first = false,
        )
    }
    // A login's own second factor. Imported since B4a and persisted correctly, but until now it
    // rendered nowhere at all — so a KeePass entry's 2FA came across and then vanished.
    if (state.totpSecret.isNotBlank()) {
        TotpCard(state, viewModel)
        LkGroup {
            // Same gate as a standalone authenticator: the seed clones the second factor outright,
            // so it is an always-reprompt field (§5.2/A2) even though the code above is not.
            SecretReadField(
                stringResource(R.string.itemedit_detail_totp_secret_field_label),
                state.totpSecret,
                "totpSecret",
                state,
                viewModel,
                first = true,
            )
        }
    }
    val sites = state.uris.filter(String::isNotBlank)
    if (sites.isNotEmpty()) {
        DetailGroup(stringResource(R.string.itemedit_detail_websites_title)) {
            sites.forEachIndexed { index, uri -> SiteRow(uri, first = index == 0) }
        }
    }
    // Shown only when there is something to show, unlike the Card's, which is part of a fixed
    // group. Most logins carry no note, and an always-present empty well under every one of them
    // would be a row of nothing on the app's most-visited screen. The ones that do carry a note
    // are mostly imported — KeePass's Notes field is the common source — and until this existed
    // that text was stored, searchable, and visible nowhere.
    if (state.loginNotes.isNotBlank()) {
        DetailGroup(stringResource(R.string.itemedit_notes_label)) {
            ReadField(
                stringResource(R.string.itemedit_notes_label),
                state.loginNotes,
                first = true,
                // The group heading above already says NOTES.
                showLabel = false,
                onCommit = if (!state.readOnly) {
                    { viewModel.onLoginNotesChanged(it); viewModel.save() }
                } else {
                    null
                },
            )
        }
    }
    PasswordHistoryGroup(state, viewModel)
}

/**
 * Previously-used passwords, newest first, with the one control that deletes them.
 *
 * This is the whole point of the 2026-07-31 change. The app had been recording a login's old
 * passwords since autofill's save prompt shipped and rendered them nowhere — no screen, no export
 * view, no string in any locale — so the user could neither know they were kept nor get rid of
 * them. Bounding the list (`PasswordHistory.MAX_ENTRIES`) is only half a fix; a retained secret the
 * user cannot see is still a retained secret they never agreed to.
 *
 * Absent entirely when there is no history, like every other conditional group on this screen: a
 * "Previous passwords: none" row would advertise the feature to the many items that never had one.
 */
@Composable
private fun PasswordHistoryGroup(state: ItemEditUiState, viewModel: ItemEditViewModel) {
    if (state.passwordHistory.isEmpty()) return
    val c = LkTheme.colors
    // rememberSaveable for the same reason the trash prompt uses it: a rotation while the dialog is
    // up must not read as an answer, and the answer it would read as is the destructive one.
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    if (confirmClear) {
        LkDialog(
            onDismiss = { confirmClear = false },
            title = stringResource(R.string.itemedit_detail_password_history_clear_dialog_title),
            body = pluralStringResource(
                R.plurals.itemedit_detail_password_history_clear_dialog_body,
                state.passwordHistory.size,
                state.passwordHistory.size,
            ),
            icon = LkIcons.Trash,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.itemedit_detail_password_history_clear_confirm),
                onClick = {
                    confirmClear = false
                    viewModel.clearPasswordHistory()
                },
                emphasis = LkActionEmphasis.Destructive,
            ),
            dismissLabel = stringResource(R.string.itemedit_detail_password_history_clear_dismiss),
        )
    }

    // Wrapped: this emits two children, and the caller's Column is `spacedBy(22.dp)` — left
    // unwrapped the caption would sit 22dp off its own group and read as a page footnote.
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DetailGroup(stringResource(R.string.itemedit_detail_password_history_title)) {
            state.passwordHistory.forEachIndexed { index, entry ->
                SecretReadField(
                    label = stringResource(
                        R.string.itemedit_detail_password_history_entry_label,
                        entry.changedAt.asLocalDate(),
                    ),
                    value = entry.password,
                    field = passwordHistoryRevealKey(index),
                    state = state,
                    viewModel = viewModel,
                    first = index == 0,
                    // Not copyable, unlike the live password above it. Copying puts plaintext on a
                    // clipboard every app on the phone can read, and there is no routine task that
                    // needs a password the user has already stopped using — Show is enough to check
                    // one against a site that might still be holding it.
                    copyable = false,
                )
            }
            ClearPasswordHistoryRow(onClick = { confirmClear = true })
        }
        // States the retention rule where the retained data is, not in a settings screen nobody
        // opens. Someone looking at a list of their own old passwords is owed the answer to "how
        // many of these are you keeping, and for how long" on the same screen.
        Text(
            stringResource(
                R.string.itemedit_detail_password_history_retention,
                PasswordHistory.MAX_ENTRIES,
            ),
            style = LkText.caption,
            color = c.textFaint,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun ClearPasswordHistoryRow(onClick: () -> Unit) {
    val c = LkTheme.colors
    val label = stringResource(R.string.itemedit_detail_password_history_clear_action)
    LkGroupRow(first = false, onClick = onClick, modifier = Modifier.labelled(label)) {
        Icon(LkIcons.Trash, contentDescription = null, tint = c.coral, modifier = Modifier.size(17.dp))
        Text(
            label,
            style = LkText.body,
            color = c.coral,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

/**
 * The day a password was replaced, in the reader's own locale.
 *
 * A localised medium date, not a hand-written pattern: "3 Mar 2026" and "Mar 3, 2026" are the same
 * fact written the way each reader expects, and this app ships in five languages. The time of day
 * is left off deliberately — it answers nothing here and makes the row harder to scan.
 */
/**
 * Whether the DATES card's "Added" row would only repeat the passkey card's own "Created" row.
 *
 * A passkey states when it was made inside its own card, and the item's `createdAt` is normally the
 * same day: two rows a screen apart saying one thing read as a duplicate. The row stays when the
 * days differ (an imported passkey is added long after it was created), since then they are two
 * facts.
 */
internal fun addedRepeatsPasskeyCreated(
    type: ItemEditUiState.ItemType,
    createdAt: Instant,
    passkeyCreatedAt: Instant?,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean =
    type == ItemEditUiState.ItemType.PASSKEY && passkeyCreatedAt != null &&
        createdAt.atZone(zone).toLocalDate() == passkeyCreatedAt.atZone(zone).toLocalDate()

private fun Instant.asLocalDate(): String =
    HistoryDateFormat.format(atZone(ZoneId.systemDefault()))

private val HistoryDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * A note's body, and — when there is no body — the way in to writing one.
 *
 * "This note is empty" sat in a well shaped exactly like every tappable row on this screen, styled
 * faint like every placeholder, and did nothing: a `uiautomator` dump of an empty note listed four
 * clickable nodes and this was not among them. It read as an invitation and was not one, and the
 * only route to the editor was the icon in the top corner.
 *
 * Tapping is offered **only** while the body is blank. A note with content stays read-only like
 * every other field on this screen — the read view exists precisely so that glancing at an item
 * cannot change it, and a stray tap on a page of text would undo that.
 */
@Composable
private fun NoteDetail(state: ItemEditUiState, viewModel: ItemEditViewModel, onEdit: () -> Unit) {
    val c = LkTheme.colors
    val empty = state.noteBody.isBlank()
    // Not offered on content the editor cannot represent, for the same reason the Edit button is
    // withheld above: opening it there and saving would write an empty item over the real one.
    val invite = (empty && !state.readOnly).takeIf { it }?.let { onEdit }
    // The visible line says so too, not only the click handler. A row that merely *became*
    // tappable looks identical to the one that was not, which is how it came to read as an
    // affordance and not be one; sighted users get the invitation from the sentence, and the
    // labelled() below names the action for everyone else.
    val placeholder = if (invite != null) {
        stringResource(R.string.itemedit_detail_note_empty_tap)
    } else {
        stringResource(R.string.itemedit_detail_note_empty)
    }
    val action = stringResource(R.string.itemedit_detail_note_empty_action)
    // Gated only when the user asked for it. `reprompt` off leaves the note exactly as it read
    // before - prose in a well, no bullets, no Show - because a note is not a high-value field by
    // *what it is* the way a CVV or a seed phrase is; it is one by what its owner put in it, and
    // the reprompt tick is how they say so. With it on, the body goes behind the same
    // `SecretReadField` the coordinate uses, which is what the badge on this screen and the tier-G
    // caption in the editor have been claiming all along.
    //
    // `copyable = false` keeps the capability the same as before: a note body has never had a Copy
    // button here, and a gate is not the place to hand out a new way to extract the secret.
    if (!empty && state.reprompt) {
        LkGroup {
            SecretReadField(
                stringResource(R.string.itemedit_note_label),
                state.noteBody,
                NOTE_BODY_FIELD,
                state,
                viewModel,
                first = true,
                copyable = false,
            )
        }
        return
    }
    LkGroup {
        LkGroupRow(
            first = true,
            verticalPadding = 18.dp,
            onClick = invite,
            modifier = if (invite != null) Modifier.labelled(action) else Modifier,
        ) {
            Text(
                if (empty) placeholder else state.noteBody,
                style = LkText.body,
                color = if (empty) c.textFaint else c.textPrimary,
            )
        }
    }
}

/**
 * A saved position: masked, re-auth to reveal or copy, with the time it was taken beside it.
 *
 * The gate is [SecretReadField]'s, unchanged, and the field key is in `ALWAYS_REAUTH_FIELDS` — so a
 * coordinate is treated exactly as a card's CVV is, on every item, regardless of its reprompt flag.
 * That is a deliberate equivalence rather than caution: a precise position says where its owner was,
 * and unlike a password it cannot be changed after someone sees it.
 *
 * What is deliberately *not* here: any way out of the app with the value. No map intent, no share
 * sheet, no "open in" row. Each would hand a precise position to another process, which is the one
 * thing an offline vault must not do quietly — and the clipboard path this screen already has is
 * gated and auto-clears, which none of those would be.
 */
@Composable
private fun CoordinateDetail(state: ItemEditUiState, viewModel: ItemEditViewModel) {
    val c = LkTheme.colors
    // `isValid()` applied on the read path, which is the half its own KDoc promises — "applied at
    // both ends, before a write and after a decode" — and which had no call site anywhere. A
    // decrypted vault is authenticated as *ours*, which says nothing about a value a past build, a
    // merge or a hand-built fixture could have put there, and a NaN latitude formats as "NaN°" on
    // this screen while comparing false against every range test written to catch it.
    //
    // Falls through to the item's empty state rather than raising an error, because saying so
    // properly needs a new string and none can be produced without its translations. Showing
    // nothing is the weaker of the two honest answers; showing "NaN°" is not one of them.
    val fix = state.coordinateFix?.takeIf { it.isValid() } ?: return
    LkGroup {
        SecretReadField(
            stringResource(R.string.itemedit_coordinate_value_label),
            fix.displayValue(),
            COORDINATE_FIELD,
            state,
            viewModel,
            first = true,
        )
        // Inside the card, under the value it describes — which is where the editor draws it, both
        // in the preview and in the masked state. On this screen alone it was emitted after the
        // group closed and landed on the page background, orphaned in the gap above FOLDER
        // (4C-18, 2026-09-20).
        //
        // Still outside the *gate*, which is what the note below is about: the gate is
        // `SecretReadField`'s, per field, not the group's. The time and the error bars are what
        // make the position a measurement rather than an assertion, and hiding them behind the same
        // prompt would mean authenticating to find out how much to trust a value you cannot see.
        //
        // `LkGroupRow` rather than a bare Text: this container is full-bleed and carries no padding
        // of its own, so a Text emitted straight into it would sit flush against the card's edge.
        LkGroupRow(first = false, verticalPadding = 12.dp) {
            Text(
                coordinateCapturedCaption(fix),
                style = LkText.caption,
                color = c.textFaint,
            )
        }
    }
}

/**
 * A stored passkey, viewed: which site it is for, the account it signs in as, when it was created,
 * and how many times it has been used to sign in.
 *
 * There is nothing here to edit and nothing here to reveal. `withPasskeyContent` sets `readOnly`
 * unconditionally, so the Edit button above is already withheld — this composable does not need to
 * re-check it, the way [LoginDetail] and [IdentityDetail] check it per field, because there is no
 * per-field distinction to make: every value on this screen is either read-only or absent.
 *
 * [ItemEditUiState.passkeyRpId], [ItemEditUiState.passkeyUsername], [ItemEditUiState.passkeyCreatedAt]
 * and [ItemEditUiState.passkeySignCounter] are the whole surface deliberately: the credential id and
 * the DEK-wrapped private key (`Passkey.privateKeyRef`) never reach `ItemEditUiState` at all (see
 * `withPasskeyContent`), so there is no field here that could be wired to a reveal or copy action by
 * a later, careless edit — the private key simply is not in scope to leak. `ReadField` is used
 * throughout with `onCommit = null` and no `copyable`, matching that: this card cannot become
 * editable by one call site forgetting a guard, because none of its rows accept one.
 */
@Composable
private fun PasskeyDetail(state: ItemEditUiState) {
    LkGroup {
        ReadField(
            stringResource(R.string.itemedit_detail_passkey_site_label),
            state.passkeyRpId,
            first = true,
        )
        ReadField(
            stringResource(R.string.itemedit_detail_passkey_username_label),
            state.passkeyUsername,
            first = false,
        )
        ReadField(
            stringResource(R.string.itemedit_detail_passkey_created_label),
            state.passkeyCreatedAt?.asLocalDate().orEmpty(),
            first = false,
        )
        ReadField(
            stringResource(R.string.itemedit_detail_passkey_sign_counter_label),
            state.passkeySignCounter.toString(),
            first = false,
        )
    }
}

/**
 * What a loaded item with nothing in it says for itself.
 *
 * Shaped exactly like [NoteDetail]'s placeholder rather than as a full-screen empty state: this sits
 * mid-column under a header and a monogram, and a centred illustration between them would read as a
 * failure to load. The line names the situation, the row is the way out of it, and both are absent
 * for content the editor cannot represent — where opening the editor and saving would write an empty
 * item over the real one.
 */
@Composable
private fun EmptyItemDetail(state: ItemEditUiState, onEdit: () -> Unit) {
    val c = LkTheme.colors
    val action = stringResource(R.string.itemedit_detail_empty_action)
    LkGroup {
        LkGroupRow(
            first = true,
            verticalPadding = 18.dp,
            onClick = onEdit.takeIf { !state.readOnly },
            modifier = if (!state.readOnly) Modifier.labelled(action) else Modifier,
        ) {
            Text(
                stringResource(R.string.itemedit_detail_empty_body),
                style = LkText.body,
                color = c.textFaint,
            )
        }
    }
}

@Composable
private fun TotpDetail(state: ItemEditUiState, viewModel: ItemEditViewModel) {
    TotpCard(state, viewModel)
    LkGroup {
        ReadField(
            stringResource(R.string.itemedit_totp_issuer_label),
            state.totpIssuer,
            first = true,
            onCommit = if (!state.readOnly) { { viewModel.onTotpIssuerChanged(it); viewModel.save() } } else null,
        )
        ReadField(
            stringResource(R.string.itemedit_totp_account_label),
            state.totpAccount,
            first = false,
            onCommit = if (!state.readOnly) { { viewModel.onTotpAccountChanged(it); viewModel.save() } } else null,
        )
        // Always re-auth gated: the seed clones the authenticator outright, not just this one code.
        SecretReadField(
            stringResource(R.string.itemedit_detail_totp_secret_label),
            state.totpSecret,
            "totpSecret",
            state,
            viewModel,
            first = false,
        )
        // Shown only when the entry departs from the ordinary SHA1/6-digit/30s shape (F41,
        // 2026-09-13). Digit count and Steam mode already have an indirect tell — the rendered code's
        // own length and alphabet — so a row on every item would be noise for the common case. The
        // algorithm has no such tell anywhere else in this view, and that is exactly the value a user
        // debugging a rejected code from an atypical import needs to see, which is also exactly when
        // this condition is true. Values are technical parameters, not prose — same choice
        // `core_import_skip_unsupported_totp_settings` already made for the identical trio.
        if (totpConfigRowVisible(state)) {
            ReadField(
                stringResource(R.string.itemedit_detail_totp_config_label),
                stringResource(
                    R.string.itemedit_detail_totp_config_value,
                    state.totpAlgorithm.name,
                    state.totpDigits,
                    state.totpPeriodSec,
                ),
                first = false,
            )
        }
    }
}

/** The default an imported or hand-entered TOTP config is compared against — RFC 6238's own. */
internal fun totpConfigIsDefault(state: ItemEditUiState): Boolean =
    state.totpAlgorithm == TotpAlgorithm.SHA1 && state.totpDigits == "6" && state.totpPeriodSec == "30"

/**
 * Whether the "Code settings" row has anything true to say.
 *
 * A Steam entry is excluded outright, and the exclusion belongs here rather than inside
 * [totpConfigIsDefault]: that function answers "does this entry depart from RFC 6238's defaults",
 * and folding `!steam` into it would invert its meaning and print the row for every *ordinary*
 * Steam item.
 *
 * `TotpGenerator.generateSteam` hard-codes SHA1, a 30-second window and a five-character alphabet,
 * so none of the three stored parameters is consulted for a Steam code. The editor only *hides* the
 * controls when Steam is switched on — `onTotpSteamToggled` flips the flag and nothing else — so a
 * `digits = "8"` typed before the toggle survives in the record. The row then read
 * "SHA1, 8 digits, 30s" directly beneath a rendered five-character code: three values, at least one
 * of them contradicted by what the user is looking at, and all three ignored.
 */
internal fun totpConfigRowVisible(state: ItemEditUiState): Boolean =
    !state.totpSteam && !totpConfigIsDefault(state)

/**
 * The period the countdown ring and the "refreshes every N seconds" caption must be drawn on.
 *
 * The item's own period, not a hardcoded 30: `TotpGenerator` has always counted down from the real
 * value and the parser accepts anything from 1s to a day, so a 60-second code drew a ring that
 * emptied halfway through its life and a caption that flatly contradicted the number ticking above
 * it.
 *
 * Except on a Steam entry, where the item's own period is the one number that is not the truth.
 * `TotpGenerator.generateSteam` rolls on its own 30-second counter whatever the record stores, and
 * the editor only *hides* the period control when Steam is switched on rather than normalising it —
 * so a Steam item saved with `period = 60` drew a ring that took a minute to empty and a caption
 * promising a refresh every 60 seconds, over a code that changed at 30.
 *
 * A free function for the reason `restoreCheckLabel` in `:feature:unlock` is one: the decision is
 * the whole of the defect and none of the drawing, so it can be walked through every combination
 * without a Compose environment.
 */
internal fun totpDisplayPeriodSec(state: ItemEditUiState): Int =
    if (state.totpSteam) {
        TotpGenerator.STEAM_PERIOD_SECONDS
    } else {
        state.totpPeriodSec.toIntOrNull()?.takeIf { it > 0 } ?: 30
    }

@Composable
private fun CardDetail(state: ItemEditUiState, viewModel: ItemEditViewModel) {
    // The card's network. Prefer the stored brand so an imported one is actually surfaced (it was
    // write-only before); fall back to detecting from the PAN. Absent for anything unrecognised —
    // loyalty and gift cards live here too, and a badge that guessed at them would be noise.
    val brand = state.cardBrand.takeIf { it != CardBrand.UNKNOWN }
        ?: CardBrands.detect(state.cardNumber)
    // The wizard's own formatter, not a second copy of it — see [cardExpiryFace]. The copy that
    // stood here dropped the month's leading zero, so a card read `01 / 20` while it was being
    // created and `1 / 20` from the moment it was saved.
    val expiry = cardExpiryFace(state)
    val expiryCaption = stringResource(R.string.itemedit_detail_card_expires_label)

    // The face reads the same revealedFields the row below it does, so it discloses nothing the
    // gate has not already released — an unrevealed number is bullets here exactly as it is there.
    // Nothing on this face is ungated that was not already ungated: brand, expiry and holder have
    // always been plain ReadFields.
    val revealed = "cardNumber" in state.revealedFields
    val cardDigits = state.cardNumber.filter(Char::isDigit)
    LkCardPreview(
        // The same face the wizard fills in, drawn from the same slot walk — so a card looks the
        // same the moment after it is saved as it did the moment before.
        numberSlots = lkCardNumberSlots(
            digits = cardDigits,
            groups = if (brand == CardBrand.AMEX) LkCardDigitGroups.Amex else LkCardDigitGroups.Default,
            maskedBefore = if (revealed) 0 else cardDigits.length,
        ),
        expiryDisplay = expiry,
        holder = state.cardHolder,
        holderPlaceholder = stringResource(R.string.itemedit_card_face_holder_placeholder),
        brandLabel = brand.takeIf { it != CardBrand.UNKNOWN }?.displayName(),
        expiryCaption = expiryCaption,
        contentDescription = run {
            val numberSpoken = if (revealed) {
                state.cardNumber
            } else {
                stringResource(R.string.itemedit_detail_card_number_hidden_a11y)
            }
            // See the matching note in CardWizard: the generic word must not be fed into the
            // "%1$s card, …" pattern, or an unrecognised card announces as "Card card".
            val brandName = brand.takeIf { it != CardBrand.UNKNOWN }?.displayName()
            if (brandName == null) {
                stringResource(R.string.itemedit_detail_card_face_a11y_no_brand, numberSpoken, expiry)
            } else {
                stringResource(R.string.itemedit_detail_card_face_a11y, brandName, numberSpoken, expiry)
            }
        },
    )
    // Amber, not coral: an expired card is a fact about the card, not a failure of the app or a
    // sign the user's data is less safe — which is the test LkColors sets for coral. Storing an
    // expired card is legitimate (the number is still needed for a refund or a dispute), so this
    // notes the date rather than warning about it. The wizard already validates that the month is
    // 1-12 and refuses 13; it never checked that the date had not already passed, so the screen
    // that asks "Check it over" showed `EXPIRES 01 / 20` in ordinary white.
    if (cardExpiryHasPassed(state.cardExpMonth, state.cardExpYear)) {
        // No Spacer here: this column is `spacedBy`, so a Spacer is itself a spaced child and
        // costs its own height plus a gap on each side (V23, 2026-09-12). Same trap as G11.
        LkInlineNote(
            stringResource(R.string.itemedit_detail_card_expired),
            icon = LkIcons.Info,
            accent = LkTheme.colors.amber,
        )
    }
    // No Spacer here: this column is `spacedBy`, so a Spacer is itself a spaced child and
    // costs its own height plus a gap on each side (V23, 2026-09-12). Same trap as G11.
    // Holder and expiry are on the face now; what stays here is everything the face cannot carry —
    // the two secrets it must never show, the note, and the number's own reveal and copy controls.
    LkGroup {
        SecretReadField(
            stringResource(R.string.itemedit_detail_card_number_label),
            state.cardNumber,
            "cardNumber",
            state,
            viewModel,
            first = true,
            // Grouped, like the face directly above it and like the editor — 4-4-4-4 for a Visa,
            // 4-6-5 for an Amex. Revealing used to give one unbroken run of sixteen digits
            // (4B-8, 2026-09-20), which is the difference between a number a human can read back
            // off a phone and one they cannot, on the same screen that groups it twice over.
            // Only the drawn text: Copy, the bullet count and the screen reader all still get
            // `state.cardNumber`, which is what `displayValue` exists to allow.
            displayValue = cardNumberFace(state.cardNumber, brand, revealed = true),
        )
        SecretReadField(
            stringResource(R.string.itemedit_card_cvv_label),
            state.cardCvv,
            "cardCvv",
            state,
            viewModel,
            first = false,
        )
        SecretReadField(
            stringResource(R.string.itemedit_card_pin_label),
            state.cardPin,
            "cardPin",
            state,
            viewModel,
            first = false,
        )
        ReadField(
            stringResource(R.string.itemedit_notes_label),
            state.cardNotes,
            first = false,
            onCommit = if (!state.readOnly) { { viewModel.onCardNotesChanged(it); viewModel.save() } } else null,
        )
    }
}

/**
 * What the card face shows where the embossed number goes.
 *
 * Masked, the bullets are grouped the way the digits would be, so an Amex reads as three groups and
 * a Visa as four — the shape of the number is not the number, and it is what makes the face read as
 * that card rather than as a generic rectangle. An empty card shows the full-length placeholder for
 * its brand, or the default 4-4-4-4 when nothing is typed yet.
 */
internal fun cardNumberFace(number: String, brand: CardBrand, revealed: Boolean): String {
    val digits = number.filter(Char::isDigit)
    val groups = if (brand == CardBrand.AMEX) LkCardDigitGroups.Amex else LkCardDigitGroups.Default
    val shown = if (revealed) digits else "•".repeat(digits.length.coerceAtLeast(groups.total))
    // The same grouping the field and the strip draw, so a 19-digit number is not spoken 4-4-4-4-3
    // while the screen shows it 3-4-4-4-4.
    return groups.format(shown)
}

@Composable
private fun IdentityDetail(state: ItemEditUiState, viewModel: ItemEditViewModel) {
    val c = LkTheme.colors
    val name = listOf(state.identityFirstName, state.identityLastName)
        .filter(String::isNotBlank)
        .joinToString(" ")
    LkGroup {
        // Not editable in place: it is two fields (first + last name) joined for display, and
        // there's no single setter to commit a re-typed line back to — see the report. Copyable
        // all the same: an identity exists to be pasted into someone else's form, and until
        // 2026-09-20 (4B-11) the three government-ID numbers were the only rows on this screen
        // that could leave it — the name, the company and the whole address had to be retyped,
        // against spec §5.3 ("field-level actions … are consistent everywhere").
        ReadField(
            stringResource(R.string.itemedit_detail_identity_name_label),
            name,
            first = true,
            copyable = true,
        )
        ReadField(
            stringResource(R.string.itemedit_identity_company_label),
            state.identityCompany,
            first = false,
            copyable = true,
            onCommit = if (!state.readOnly) { { viewModel.onIdentityCompanyChanged(it); viewModel.save() } } else null,
        )
        ReadField(
            stringResource(R.string.itemedit_identity_email_label),
            state.identityEmail,
            first = false,
            copyable = true,
            onCommit = if (!state.readOnly) { { viewModel.onIdentityEmailChanged(it); viewModel.save() } } else null,
        )
        ReadField(
            stringResource(R.string.itemedit_detail_identity_phone_label),
            state.identityPhone,
            first = false,
            copyable = true,
            onCommit = if (!state.readOnly) { { viewModel.onIdentityPhoneChanged(it); viewModel.save() } } else null,
        )
        // Masked, and re-auth gated on every identity item rather than only on a reprompt one —
        // they are in [ALWAYS_REAUTH_FIELDS] beside the CVV and the saved position, one tier above
        // the card *number* they sit next to. A card can be cancelled in an afternoon and a
        // national ID number cannot be reissued at all. These three rendered as plain text —
        // visible to anyone glancing at the screen, and copyable without ever meeting the gate the
        // same user's password sits behind — while `IdentityFields` in the *editor* had used
        // SecretField for them all along. The read view is the one people actually open.
        SecretReadField(
            stringResource(R.string.itemedit_identity_ssn_label),
            state.identitySsn,
            "ssn",
            state,
            viewModel,
            first = false,
        )
        SecretReadField(
            // The editor's own label, not a shorter detail-only one. The editor reads
            // "PASSPORT NUMBER" and "DRIVER'S LICENSE" while this screen read "PASSPORT" and
            // "LICENSE" (4B-10, 2026-09-20), so a user who filled a field in could not find it
            // under the same name when reading it back — with the project glossary binding on
            // terminology. The national-ID row beside these two already shared its key, which is
            // the pattern; these two are now the same.
            stringResource(R.string.itemedit_identity_passport_field_label),
            state.identityPassport,
            "passport",
            state,
            viewModel,
            first = false,
        )
        SecretReadField(
            stringResource(R.string.itemedit_identity_license_field_label),
            state.identityLicense,
            "license",
            state,
            viewModel,
            first = false,
        )
    }

    val address = listOf(
        state.identityAddressLine1,
        state.identityAddressLine2,
        listOf(state.identityAddressCity, state.identityAddressState, state.identityAddressZip)
            .filter(String::isNotBlank)
            .joinToString(" "),
        state.identityAddressCountry,
    ).filter(String::isNotBlank)
    if (address.isNotEmpty()) {
        val addressLabel = stringResource(R.string.itemedit_identity_address_heading)
        val addressValue = address.joinToString("\n")
        val context = LocalContext.current
        DetailGroup(addressLabel) {
            // Through the same long-press path every other copyable row on this screen uses, and
            // through `ClipboardHelper` so the clip is marked sensitive and auto-clears. It is the
            // longest thing on an identity and was the one row with no way off the screen at all.
            LkGroupRow(
                first = true,
                verticalPadding = 16.dp,
                onLongClick = { ClipboardHelper.copy(context, addressLabel, addressValue) },
                onLongClickLabel = stringResource(R.string.itemedit_action_copy, addressLabel),
            ) {
                Text(addressValue, style = LkText.body, color = c.textPrimary)
            }
        }
    }
}

/**
 * The current TOTP code, with a ring that drains over the period.
 *
 * The ring recolours jade → amber → coral as the code ages ([totpToneFor] owns the boundaries), but
 * the seconds are always printed beside it: colour alone must not be the signal (spec §2), and a
 * user who is colour-blind still needs to know whether they have time to type the code before it
 * rolls.
 */
@Composable
private fun TotpCard(state: ItemEditUiState, viewModel: ItemEditViewModel) {
    val c = LkTheme.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()

    // On a reprompt item the live code is a secret like any other: masked until the gate is passed,
    // and copied only through it. On every other item it stays exactly as it was — §5.2 exempts the
    // one-shot code from the always-gated list, and a fingerprint every thirty seconds is not a
    // second factor anyone would keep using.
    val gated = state.requiresReauthFor(TOTP_CODE_FIELD)
    val revealed = !gated || TOTP_CODE_FIELD in state.revealedFields

    if (state.totpCurrentCode.isBlank()) {
        LkGroup { ReadField(stringResource(R.string.itemedit_detail_totp_code_label), "—", first = true) }
        return
    }

    // Modifier.semantics {} is a plain, non-@Composable lambda, so these are resolved here rather
    // than at the point of use.
    val spokenCode = stringResource(
        R.string.itemedit_detail_totp_code_spoken,
        state.totpCurrentCode.toCharArray().joinToString(" "),
    )
    val hiddenCodeDescription = stringResource(R.string.itemedit_detail_totp_code_hidden)

    // Spec §8.2 wants the rollover to be felt. It is the one moment here the user cannot predict
    // and must not miss: typing a code that expired mid-entry just fails with no explanation.
    // Keyed on the code, so it fires exactly when the value actually changes — but the card
    // reaches this line only once totpCurrentCode is already non-blank (see the early return
    // above), so the first composition already holds a code with nothing to roll away from.
    // Remembering the code already played for is what tells those two cases apart.
    var hapticPlayedForCode by remember { mutableStateOf("") }
    LaunchedEffect(state.totpCurrentCode) {
        val previousCode = hapticPlayedForCode
        hapticPlayedForCode = state.totpCurrentCode
        if (totpRolloverHapticShouldFire(previousCode, state.totpCurrentCode)) {
            haptics.play(Haptic.TOTP_ROLLOVER)
        }
    }

    val seconds = state.totpRemainingSeconds
    val toneStep = totpToneFor(seconds)
    val targetTone = when (toneStep) {
        TotpTone.EXPIRING -> c.coral
        TotpTone.NEAR -> c.amber
        TotpTone.CALM -> c.jade
    }
    val tone by animateColorAsState(targetTone, lkTween(LkMotion.Screen), label = "totp-tone")
    // The halo behind the ring, which warms with it. It reuses the tone animation above rather than
    // running a second one, so the ring and its glow can never disagree mid-transition.
    val haloTone by animateColorAsState(
        when (toneStep) {
            TotpTone.CALM -> c.jade.copy(alpha = 0.12f)
            else -> c.amber.copy(alpha = 0.14f)
        },
        lkTween(LkMotion.Screen),
        label = "totp-halo",
    )

    LkLiftedCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // An inline badge in a Row, not a section heading: LkSectionLabel owns an 8 dp gap
            // below itself (2026-09-17), which a Row cannot use, so this draws the same style
            // directly. Same case, same colour, no gap.
            Text(
                stringResource(R.string.itemedit_login_totp_badge).uppercase(),
                style = LkText.sectionLabel,
                color = c.textMuted,
            )
            // The word, not just the colour. Amber at 8s and coral at 4s were the only signal that
            // a code was about to turn over, which is nothing at all to a colour-blind user and
            // nothing at all to a screen reader — and this is a number people are mid-way through
            // typing into another app when it expires. Deliberately not a live region: it changes
            // once a second, and announcing that would talk over everything else on the screen for
            // as long as the item is open.
            Text(
                if (toneStep != TotpTone.CALM) {
                    stringResource(R.string.itemedit_detail_totp_seconds_expiring, seconds)
                } else {
                    stringResource(R.string.itemedit_detail_totp_seconds, seconds)
                },
                style = LkText.monoCaption,
                color = tone,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val periodSec = totpDisplayPeriodSec(state)
            LkProgressRing(
                // Read off a frame clock, not off the once-per-second integer beside it.
                //
                // This used to pass `seconds / periodSec` with `animated = false`, and the comment
                // here argued that easing it would make the ring lag the printed number. That was
                // right about easing and wrong about the remedy. `animateFloatAsState` chases a
                // target that only moves at 1 Hz, so it restarts a tween every tick and trails a
                // value that is already stale — and at rollover, when the count jumps from 1 back
                // up to the full period, it would unwind *backwards* from empty to full over a
                // second, which is the opposite of the hard cut the design asks for.
                //
                // Deriving the fraction from the clock fixes both: it drains smoothly, the rollover
                // is a modulo wrap needing no special case, and it stays in agreement with the
                // printed integer because both come from the same second-of-epoch. Reduced motion
                // keeps the stepped arc — spec §5.3 asks for exactly that.
                progress = rememberTotpFraction(periodSec, seconds),
                size = 78.dp,
                strokeWidth = 4.dp,
                color = tone,
                animated = false,
                modifier = Modifier.labelled(
                    pluralStringResource(R.plurals.itemedit_detail_totp_ring_seconds, seconds, seconds),
                ),
            ) {
                // A soft wash inside the ring that warms with it. Decorative only — it carries no
                // information the caption and the ring colour do not already carry twice.
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(9.dp)
                        .background(
                            Brush.radialGradient(listOf(haloTone, Color.Transparent)),
                            CircleShape,
                        )
                        .decorative(),
                )
                Icon(LkIcons.Clock, contentDescription = null, tint = tone, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    if (revealed) {
                        state.totpCurrentCode.grouped()
                    } else {
                        "•".repeat(state.totpCurrentCode.length)
                    },
                    style = LkText.numeralLarge,
                    color = c.textStrong,
                    // Spoken digit by digit. Left alone, TTS reads "123 456" as "one hundred and
                    // twenty-three, four hundred and fifty-six", which is unusable for a code you
                    // have to transcribe into another app before it expires.
                    modifier = Modifier.semantics {
                        contentDescription = if (revealed) {
                            spokenCode
                        } else {
                            // The a11y tree is a reveal surface too: a masked code that spells
                            // itself out to TalkBack is not masked.
                            hiddenCodeDescription
                        }
                    },
                )
                // Who the code is *for*. Saved at enrolment and shown in the editor's Two-factor
                // card, it was never shown here — so the screen people actually open to read a code
                // gave no way to tell two factors apart, and an item holding the wrong issuer looks
                // identical to one holding the right one until the code is rejected (F-20,
                // 2026-09-15 visual pass). Same construction as the editor's caption, so the two
                // cards cannot start disagreeing about how a factor identifies itself.
                val issuerAndAccount = listOf(state.totpIssuer, state.totpAccount)
                    .filter(String::isNotBlank)
                    .joinToString(" · ")
                if (issuerAndAccount.isNotEmpty()) {
                    Text(issuerAndAccount, style = LkText.subtitle, color = c.textMuted)
                }
                // Deliberately NOT a live region: it changes every second, and announcing it would
                // talk over everything else on the screen forever.
                Text(
                    pluralStringResource(R.plurals.itemedit_detail_totp_refresh_caption, periodSec, periodSec),
                    style = LkText.subtitle,
                    color = c.textFaint,
                )
            }
            if (gated) {
                LkIconButton(
                    icon = if (revealed) LkIcons.EyeOff else LkIcons.Eye,
                    label = if (revealed) {
                        stringResource(R.string.itemedit_detail_totp_hide)
                    } else {
                        stringResource(R.string.itemedit_detail_totp_show)
                    },
                    haptic = if (revealed) Haptic.CONCEAL else Haptic.REVEAL,
                    onClick = { viewModel.toggleFieldRevealed(TOTP_CODE_FIELD) },
                    size = 44.dp,
                    iconSize = 28.dp,
                    // Amber while revealed, matching `SecretField` in ItemEditScreen - jade
                    // is primary-action only, and Copy beside this one already owns it.
                    tint = if (revealed) c.amber else c.textMuted,
                    container = if (revealed) c.amber.copy(alpha = 0.10f) else c.wash,
                    ring = if (revealed) c.amber.copy(alpha = 0.20f) else c.ring,
                    shape = LkShape.lg,
                )
                Spacer(Modifier.size(8.dp))
            }
            val totpClipboardLabel = stringResource(R.string.itemedit_detail_totp_clipboard_label)
            LaunchedEffect(state.copyGrantedField) {
                if (state.copyGrantedField == TOTP_CODE_FIELD) {
                    ClipboardHelper.copy(context, totpClipboardLabel, state.totpCurrentCode)
                    viewModel.onCopyPerformed()
                }
            }
            LkIconButton(
                icon = LkIcons.Copy,
                label = if (gated && !revealed) {
                    stringResource(R.string.itemedit_detail_totp_copy_gated)
                } else {
                    stringResource(R.string.itemedit_detail_totp_copy)
                },
                haptic = if (gated && !revealed) Haptic.REVEAL else Haptic.COPY,
                onClick = { viewModel.requestCopy(TOTP_CODE_FIELD) },
                size = 44.dp,
                iconSize = 28.dp,
                tint = c.jade,
                container = c.jade.copy(alpha = 0.10f),
                ring = c.jade.copy(alpha = 0.20f),
                shape = LkShape.lg,
            )
        }
    }
}

/**
 * The fraction of a TOTP period still remaining, sampled every frame.
 *
 * TOTP counts in absolute time: a code covers `[n*period, (n+1)*period)` of Unix epoch seconds, and
 * `TotpGenerator.remainingSeconds` is `period - epochSecond % period`. Taking the same quantity in
 * milliseconds gives a value that falls continuously and wraps to 1 at the instant the code rolls,
 * with no special case and nothing to keep in sync — [wholeSeconds] is passed in only so the ring
 * re-reads the clock immediately when the count ticks under reduced motion.
 *
 * Under reduced motion the frame loop does not run and the arc steps once per second instead, which
 * is what spec §5.3 asks for: "ring steps rather than sweeps".
 */
@Composable
private fun rememberTotpFraction(periodSec: Int, wholeSeconds: Int): Float {
    val reduceMotion = LocalLkReduceMotion.current
    val periodMillis = periodSec.toLong() * 1_000L
    if (reduceMotion) return (wholeSeconds / periodSec.toFloat()).coerceIn(0f, 1f)

    var fraction by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(periodMillis) {
        while (true) {
            withFrameNanos { }
            val elapsed = System.currentTimeMillis().mod(periodMillis)
            fraction = 1f - elapsed / periodMillis.toFloat()
        }
    }
    return fraction
}

/** Splits a 6/8-digit code down the middle — `123 456` reads far faster than `123456`. */
private fun String.grouped(): String =
    if (length < 6) this else "${substring(0, length / 2)} ${substring(length / 2)}"

@Composable
private fun DetailGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // No arrangement. `sm` — a label to the thing it labels — is still the gap, but [LkSectionLabel]
    // is what supplies it now (LkSectionLabelGap). This was 9dp against Settings' 10dp for the
    // identical construct: an uppercase heading directly above a rounded group of rows, one
    // relationship rendered 0.8dp apart on two screens of one build. A `spacedBy` left here would be
    // added to the label's own padding instead of being the thing that sets it.
    Column(modifier) {
        LkSectionLabel(title, Modifier.padding(start = 2.dp))
        LkGroup(content = content)
    }
}

/**
 * Lines the website row may occupy at [fontScale].
 *
 * One line at the default scale, and proportionally more above it — the same rule, and the same
 * reasoning, as the vault list's row title. A fixed line count is a *shrinking character budget*:
 * doubling the text size halves what a line holds, so `maxLines = 1` at scale 2.0 shows about half
 * the characters it shows at 1.0, whatever that number is on the device in hand. This row's whole
 * job is telling the user *which* site a login belongs to, and halving its budget is enough that
 * two hostnames sharing a prefix — `accounts.google.com`, `accounts.googleapis.com` — collapse to
 * the same ellipsised string, making two different credentials indistinguishable at the one row
 * that exists to distinguish them. Spec §2 forbids truncating critical content at large font sizes
 * and this is that clause exactly.
 *
 * Rounding, not ceiling, is what keeps it inert where nothing is wrong: the common small bumps
 * (1.15, 1.3) still resolve to one line, so the row only grows once the text has genuinely
 * outgrown it. The ceiling of three is for a URL with a long path — unlike the vault list's title
 * this string is not user-authored prose, so it needs less headroom, but it is not bounded either.
 */
internal fun siteRowMaxLines(fontScale: Float): Int = fontScale.roundToInt().coerceIn(1, 3)

@Composable
private fun SiteRow(uri: String, first: Boolean) {
    val c = LkTheme.colors
    val context = LocalContext.current
    val clipboardLabel = stringResource(R.string.itemedit_detail_website_clipboard_label)
    LkGroupRow(
        first = first,
        modifier = Modifier.labelled(stringResource(R.string.itemedit_detail_website_row_label, uri)),
        onClick = { ClipboardHelper.copy(context, clipboardLabel, uri) },
    ) {
        Icon(LkIcons.Globe, contentDescription = null, tint = c.textFaint, modifier = Modifier.size(16.dp))
        Text(
            uri,
            style = LkText.monoField.copy(fontSize = LkText.subtitle.fontSize),
            color = c.textTertiary,
            maxLines = siteRowMaxLines(LocalDensity.current.fontScale),
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 11.dp),
        )
        Icon(LkIcons.Copy, contentDescription = null, tint = c.textFaint, modifier = Modifier.size(15.dp))
    }
}

/**
 * What the attachments row should call itself, given what the item is carrying.
 *
 * "Attachments" is the right word for a destination and the wrong one for a description: it read
 * identically whether the item held nothing at all or the two photographs of a passport that are
 * the whole reason an Identity exists. `Photos` only when *every* file is an image — one PDF among
 * them and the row would be promising something the viewer will not show.
 */
internal sealed interface AttachmentsRowLabel {
    /** Nothing attached yet, so the row names where it goes rather than what is there. */
    data object Empty : AttachmentsRowLabel
    data class Photos(val count: Int) : AttachmentsRowLabel
    data class Files(val count: Int) : AttachmentsRowLabel
}

internal fun attachmentsRowLabel(count: Int, imageCount: Int): AttachmentsRowLabel = when {
    count <= 0 -> AttachmentsRowLabel.Empty
    // `coerceAtMost` rather than trusting the pair: the two numbers are counted from one list, but a
    // row that says "3 photos" about 2 files is the kind of wrong that survives review by looking
    // plausible, and the guard costs nothing.
    imageCount.coerceAtMost(count) == count -> AttachmentsRowLabel.Photos(count)
    else -> AttachmentsRowLabel.Files(count)
}

@Composable
private fun AttachmentsLink(
    count: Int,
    imageCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val label = when (val kind = attachmentsRowLabel(count, imageCount)) {
        AttachmentsRowLabel.Empty -> stringResource(R.string.itemedit_detail_attachments)
        is AttachmentsRowLabel.Photos ->
            pluralStringResource(R.plurals.itemedit_detail_attachments_photos, kind.count, kind.count)
        is AttachmentsRowLabel.Files ->
            pluralStringResource(R.plurals.itemedit_detail_attachments_files, kind.count, kind.count)
    }
    LkGroup(modifier) {
        LkGroupRow(first = true, onClick = onClick, modifier = Modifier.labelled(label)) {
            Icon(LkIcons.Attachment, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(17.dp))
            Text(
                label,
                style = LkText.body,
                color = c.textSecondary,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Icon(LkIcons.ChevronRight, contentDescription = null, tint = c.textFaint, modifier = Modifier.size(15.dp))
        }
    }
}

/**
 * Saves [ReadField]'s draft across a recreation only while [isEditing] says an edit is open.
 *
 * Returning null from `save` is how a saver declines: nothing is written, and a restore runs the
 * initialiser again, which is the stored value — all an idle row ever held.
 */
internal fun readFieldDraftSaver(isEditing: () -> Boolean): Saver<MutableState<String>, String> = Saver(
    save = { if (isEditing()) it.value else null },
    restore = { mutableStateOf(it) },
)

/** Plain read-only row. Renders nothing for an empty value rather than an empty labelled box.
 *
 * When [copyable] is true, long-press to copy the value to clipboard (no visible button).
 */
@Composable
private fun ReadField(
    label: String,
    value: String,
    first: Boolean,
    copyable: Boolean = false,
    /**
     * False when the [DetailGroup] wrapping this row already carries the same word.
     *
     * NOTES, FOLDER and TAGS each have exactly one natural name, and each was passing it to both
     * the group heading and the row, so the detail screen printed the word twice, 78px apart, in
     * two type ramps. The groups that read correctly — websites, password history — give the group
     * a heading of its own (`itemedit_detail_websites_title`) that differs from the row's label;
     * these three have nothing else to call themselves, so the row yields instead.
     *
     * The *spoken* label is unaffected: `readout` below still names the field, because a screen
     * reader gets none of the visual grouping that makes the second copy redundant.
     */
    showLabel: Boolean = true,
    // T1 (2026-08-12): non-null turns a tap into inline edit-in-place for this field. Null for
    // secrets (SecretReadField keeps its own reveal/copy path), for composite/derived values (the
    // identity name, the joined tags line) and for the few fields this pass could not reach without
    // growing past a single-file change — see the report for exactly which and why.
    onCommit: ((String) -> Unit)? = null,
) {
    if (value.isBlank()) return
    val c = LkTheme.colors
    val context = LocalContext.current
    val readout = stringResource(R.string.itemedit_detail_field_readout, label, value)
    // rememberSaveable for the same reason the trash prompt above uses it: a rotation part-way
    // through an inline edit must not read as an answer. Here the answer it would read as is
    // "discard" — a plain `remember` closed the row and dropped the typed draft on recreation,
    // with nothing to show the user that anything had been thrown away.
    var editing by rememberSaveable { mutableStateOf(false) }
    // Saved only while an edit is open (O1-04, 2026-10-02). Idle, `draft` is just a copy of the
    // shown value, and saving it put every plain field on screen — login notes, card notes, custom
    // fields — into the Activity's saved state, outside the vault and outliving the auto-lock.
    var draft by rememberSaveable(saver = readFieldDraftSaver { editing }) { mutableStateOf(value) }

    // Back cancels the in-place edit rather than leaving the screen — the same "undo, don't
    // navigate" rule the folder sheet's own back handling follows.
    //
    // `cancelled` is what makes that true. Setting `editing = false` alone does not cancel
    // anything: it removes the `if (editing)` branch, which disposes the text field, which fires
    // `onFocusChanged(hasFocus = false)` — and by then `everFocused` is true, so the focus-loss
    // handler below read the disposal as a tap-away and **saved the draft to the vault**. Back
    // therefore did the exact opposite of what this comment claimed, silently (W1, 2026-09-12:
    // typed `ZZZ`, pressed Back, and `ZZZalpha@example.com` was in the vault afterwards).
    var cancelled by remember { mutableStateOf(false) }

    BackHandler(enabled = editing) {
        cancelled = true
        editing = false
    }

    fun commit() {
        val next = draft
        editing = false
        // `cancelled` is checked here rather than at the call site because the commit that has to
        // be suppressed is the one the *disposal* triggers, not one anybody calls deliberately.
        if (!cancelled && next != value) onCommit?.invoke(next)
    }

    if (editing) {
        val focusRequester = remember { FocusRequester() }
        // Guards the focus-loss commit below. `onFocusChanged` fires once on first composition with
        // `hasFocus = false` — before `requestFocus` has run — so an unguarded "commit on !hasFocus"
        // committed and closed the field in the same frame it opened, which is exactly why tapping a
        // value never appeared to enter edit mode on device (the row reverted before a keystroke
        // could land). Only a loss *after* a real gain is a genuine tap-away.
        // Deliberately NOT saveable, unlike `editing` and `draft` above. After a recreation this
        // block is a fresh composition that has not been focused yet, so the spurious first
        // `hasFocus = false` is exactly the event the guard exists to swallow — and restoring it as
        // `true` makes the restored composition read that event as a tap-away instead. Measured
        // 2026-08-31 with `StateRestorationTester`: saveable, the row commits on arrival and the
        // inline edit closes; plain, the edit comes back open with its draft. Making all three
        // saveable therefore undoes the fix the other two are.
        var everFocused by remember { mutableStateOf(false) }
        // Same pattern the new-folder input uses (FolderPicker's `folderFocus`): a FocusRequester
        // attached to LkTextField's own `modifier` finds and focuses its inner BasicTextField even
        // though it isn't the focus target itself — LkTextField doesn't expose one of its own.
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Column(
            // The one region where a tap is still editing; anywhere else ends the edit.
            Modifier.inlineEditArea().fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (showLabel) LkFieldLabel(label)
            // The well gets a 4dp patch of `ground` under it, and only the well — the label above
            // belongs to the group. A `DetailGroup` fills with `c.surface` (#0E1214) and the well
            // with `c.field` (#101416): two levels per channel, where the same well on `c.ground`
            // (#0A0C0E) gets six/eight/eight. `Item Flows.dc.html` puts every free-text well
            // directly on the device background and never inside a raised panel; this restores that
            // relationship at the one place it is visible, without restructuring `LkGroup` or the
            // six other screens built on it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.ground, LkShape.xl)
                    .padding(LkSpace.xs),
            ) {
                LkTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = label,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
                    // Focus loss (tapping elsewhere, or the field scrolling off) commits exactly like
                    // Done does — the plan calls for "IME Done or focus loss", and a tap-away that
                    // silently discarded a typed value would be a worse surprise than saving it.
                    // A tap elsewhere only becomes a focus loss through `endInlineEditOnTapAway`:
                    // most of this screen is not focusable, so the tap alone moved nothing.
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.hasFocus) everFocused = true else if (everFocused) commit()
                        },
                )
            }
        }
        return
    }

    // Tap edits (when editable), long-press copies (when copyable) — both routed through
    // LkGroupRow's own interaction handling. The earlier attempt attached a `combinedClickable` via
    // the `modifier` param, and on device its `onClick` never fired (long-press did): LkGroupRow's
    // proven click path is its own `onClick`/`onLongClick`, not a bolted-on modifier.
    val editLabel = stringResource(R.string.itemedit_detail_field_edit, label)
    LkGroupRow(
        first = first,
        onClick = if (onCommit != null) ({ draft = value; cancelled = false; editing = true }) else null,
        onLongClick = if (copyable) ({ ClipboardHelper.copy(context, label, value) }) else null,
        onClickLabel = if (onCommit != null) editLabel else null,
        // Reuses SecretReadField's copy label rather than adding a duplicate key — same action,
        // same wording ("Copy <field>"), named for TalkBack's actions menu the same way.
        onLongClickLabel = if (copyable) stringResource(R.string.itemedit_action_copy, label) else null,
    ) {
        // Label and value as one node: separately, a screen reader reads "Username" and then, on a
        // second swipe, an address with nothing tying it to the field it belongs to.
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { contentDescription = readout },
            // `sm`. "A label to its own field" is the rung's whole definition, and this row was
            // re-implementing the pairing at 5dp while `LkFormField` rendered it at 8 — the same
            // construct two sizes apart depending on which screen you were looking at.
            verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            // A field's own name, not a section heading — [DetailGroup] above already carries the
            // section. Both render `textFaint`, so this changes volume and nothing else. Suppressed
            // entirely where the group's heading *is* this word — see [showLabel].
            if (showLabel) LkFieldLabel(label)
            Text(value, style = LkText.body, color = c.textPrimary)
        }
    }
}

/**
 * The folder row on the item view screen. A [ReadField] with an affordance and an optional tap —
 * the only [DetailGroup] row on this screen that leads anywhere rather than just stating a fact.
 *
 * [onClick] is null for a read-only item (files, and anything else the editor cannot represent —
 * matches the gate on [EditButton] above), in which case this renders exactly like a plain
 * [ReadField]: no chevron, no interaction.
 */
@Composable
private fun FolderReadField(folderName: String?, onClick: (() -> Unit)?, showLabel: Boolean = true) {
    val c = LkTheme.colors
    // Unfiled: the row shows "Add to folder" instead of a folder name — same fact as an unset
    // field on this screen (see FolderPicker's own placeholder), and the readout says the same
    // thing a screen reader hears for any other empty-but-actionable row.
    val displayValue = folderName ?: stringResource(R.string.itemedit_folder_add_action)
    val readout = stringResource(R.string.itemedit_detail_field_readout, stringResource(R.string.itemedit_folder_label), displayValue)
    LkGroupRow(first = true, onClick = onClick, modifier = Modifier.minTouchTarget()) {
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { contentDescription = readout },
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            if (showLabel) LkFieldLabel(stringResource(R.string.itemedit_folder_label))
            Text(displayValue, style = LkText.body, color = if (folderName == null) c.textFaint else c.textPrimary)
        }
        if (onClick != null) {
            Icon(
                LkIcons.ChevronRight,
                contentDescription = stringResource(R.string.itemedit_folder_change_action),
                tint = c.textFaint,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * Move/remove sheet opened from [FolderReadField]. Two stages: the action list (Move / Remove),
 * and — once Move is tapped — the folder list itself, reusing [FolderPickerState.rows] for the
 * same indentation and sibling-name disambiguation the editor's own picker uses. Fully expanded
 * rather than disclosure-by-disclosure: this list is a one-shot pick, not something the user
 * leaves open and returns to, so there is nothing a collapsed branch would be saving them from.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FolderActionSheet(
    state: ItemEditUiState,
    picking: Boolean,
    onStartPicking: () -> Unit,
    onPick: (java.util.UUID?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    com.localkeep.core.ui.theme.LkSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.itemedit_folder_sheet_title),
    ) {
        if (!picking) {
            LkGroup {
                LkGroupRow(first = true, onClick = onStartPicking, modifier = Modifier.minTouchTarget()) {
                    Text(
                        stringResource(R.string.itemedit_folder_move),
                        style = LkText.body,
                        color = LkTheme.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.folderId != null) {
                    LkGroupRow(first = false, onClick = onRemove, modifier = Modifier.minTouchTarget()) {
                        Text(
                            stringResource(R.string.itemedit_folder_remove),
                            style = LkText.body,
                            color = LkTheme.colors.coral,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            val allExpanded = remember(state.folders) { state.folders.map { it.id }.toSet() }
            LkGroup {
                LkGroupRow(
                    first = true,
                    onClick = { onPick(null) },
                    modifier = Modifier.minTouchTarget(),
                ) {
                    Text(
                        stringResource(R.string.itemedit_folder_none),
                        style = LkText.body,
                        color = if (state.folderId == null) LkTheme.colors.jade else LkTheme.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                FolderPickerState.rows(state.folders, allExpanded).forEach { row ->
                    val folder = row.folder
                    LkGroupRow(
                        first = false,
                        onClick = { onPick(folder.id) },
                        modifier = Modifier.minTouchTarget(),
                    ) {
                        if (row.depth > 0) {
                            Spacer(Modifier.width((row.depth * 14).dp))
                        }
                        val name = row.disambiguateBy
                            ?.let { stringResource(R.string.itemedit_folder_picker_subfolder, folder.name, it) }
                            ?: folder.name
                        Text(
                            name,
                            style = LkText.body,
                            color = if (folder.id == state.folderId) LkTheme.colors.jade else LkTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Lines a secret's value may occupy, masked or [revealed]. The asymmetry IS the security property.
 *
 * **Masked stays exactly one line.** The bullets are capped at 16 above so they cannot leak an
 * exact length, but letting them wrap would reintroduce that channel the moment the cap is ever
 * raised, and a wrapped block of bullets tells a shoulder-surfer something a single row does not.
 * This is the invariant; nothing about the reveal side may be allowed to erode it.
 *
 * **Revealed is not capped at all.** It was capped at three lines, and three was too few for the
 * longest secret this app's own editors produce: a 24-word recovery phrase (the phrase editor
 * offers 12/15/18/21/24) joined by spaces runs 95 to 215 characters — the shipped
 * `bip39-wordlist.txt` holds 2048 words of three to eight letters — against a value column that is
 * only a `weight(1f)` share of a row also carrying a 34dp reveal button and a Copy button. Three
 * lines of that column does not hold it at the *default* font scale, so a user who had just passed
 * a biometric prompt specifically to read their recovery phrase was handed an ellipsis instead.
 * Spec §2 forbids truncating critical content, and there is no content in this app more critical.
 *
 * Deliberately NOT the `BasicText` + `TextAutoSize.StepBased(8.sp, …)` pattern that
 * `GeneratorScreen` uses for the same overflow. That stage is a fixed three-line box on a screen
 * whose entire job is one value, so shrinking preserves a layout that matters. This row lives in a
 * `verticalScroll` column where growing costs scroll distance and nothing else, and shrinking a
 * recovery phrase toward an 8sp floor answers "I cannot read this" with smaller text. Wrapping is
 * the only one of the two that makes the revealed value more readable rather than less.
 *
 * No ceiling, because every finite one is a guess at the longest secret a user will ever paste and
 * this file already made that guess and got it wrong by a factor of three. Unlike the vault list's
 * row title — which is capped at five lines because a list must stay scannable — this is the
 * dedicated surface for exactly one item, reached on purpose, and length here costs only scroll.
 */
internal fun secretValueMaxLines(revealed: Boolean): Int = if (revealed) Int.MAX_VALUE else 1

/**
 * What a row draws in place of a value it is withholding that is not the value itself.
 *
 * Two bullets, fixed, and that is the whole point: a masked *secret* draws one bullet per character
 * because the length of a password is not worth hiding, but the things this stands in for are
 * counts and lengths — a recovery phrase's word count (F-26) — where bullets-per-character would
 * restate the very number being withheld. Not a resource: it is punctuation, identical in all five
 * languages, and a translator given "••" has nothing to decide.
 */
internal const val MASKED_COUNT_PLACEHOLDER = "••"

/**
 * A revealed recovery phrase, with every word carrying its position.
 *
 * `01 abandon 02 ability …`, which is exactly how this app's own Recovery Kit prints its words
 * (`OnboardingScreen.MnemonicWord`). The stored *item* type printed them as a bare run instead, so
 * twelve or twenty-four words wrapped three to a line and finding word 9 meant counting across the
 * rows — for the single task this item type exists for, which is transcribing a seed onto paper or
 * steel (F-28, 2026-09-15 visual pass).
 *
 * Two-digit, zero-padded, like the Kit: a column of `01 … 12` aligns where `1 … 12` does not.
 *
 * The index and its word are joined by U+00A0, a non-breaking space, so a line break can fall
 * between pairs but never inside one — a wrap that left `07` at the end of one line and its word at
 * the start of the next would reintroduce the counting this removes.
 *
 * Blank slots are dropped rather than numbered. This renders a *stored* phrase, where a blank is an
 * artefact of a partially-filled grid and never something to transcribe; numbering it would make the
 * positions of every word after it wrong.
 */
internal fun numberedPhraseWords(words: List<String>): String =
    words.filter(String::isNotBlank)
        .mapIndexed { index, word -> "${(index + 1).toString().padStart(2, '0')}\u00A0$word" }
        .joinToString(" ")

/**
 * Masked row with Show/Hide and Copy. Reveal routes through the ViewModel so the high-value fields
 * hit the same re-auth gate as the edit form, and the state is announced for screen readers (§5.2).
 */
@Composable
internal fun SecretReadField(
    label: String,
    value: String,
    field: String,
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    first: Boolean,
    copyable: Boolean = true,
    /**
     * What the revealed row *draws*, when that is not the same string as [value].
     *
     * One call site passes one: a recovery phrase is drawn with its words numbered (F-28). Only the
     * drawn text changes — [value] is still what Copy writes to the clipboard, what the masked
     * bullets are counted from, and what the screen reader is given — because the presentation is
     * for transcribing by eye and the value is for everything else.
     */
    displayValue: String = value,
    // Test-only escape hatch: none of the eight call sites in this file pass one, so this changes
    // nothing about production behavior. It exists so an instrumented test can pin a testTag to
    // *this* row directly — SecretReadFieldCopyOnlyAccessibilityTest — rather than asserting on
    // whichever node a text-content query happens to resolve to inside a row that also nests a
    // Show/Hide button and, when copyable, a Copy button.
    modifier: Modifier = Modifier,
) {
    if (value.isBlank()) return
    val c = LkTheme.colors
    val context = LocalContext.current
    val revealed = field in state.revealedFields

    // The bullets and the secret cross-fade rather than swapping between frames. Revealing is a
    // deliberate act with a consequence — someone behind you can now read this — and an instant
    // swap gives it exactly as much weight as a value that happened to change. The colour rides
    // its own tween so the jade arrives with the text instead of a frame ahead of it.
    val ink by animateColorAsState(
        if (revealed) c.jadeLit else c.textMuted,
        lkTween(LkMotion.Base),
        label = "secret-ink",
    )
    // Read here rather than inside transitionSpec: that lambda is an AnimatedContentTransitionScope
    // receiver, not a composable one, and lkTween is @Composable — it is what carries the
    // reduce-motion collapse, so it has to be resolved in a composable context and captured.
    val revealSpec = lkTween<Float>(LkMotion.Base)
    val concealSpec = lkTween<Float>(LkMotion.Quick)
    LkGroupRow(
        first = first,
        modifier = modifier,
        // T2 (2026-08-12): long-press is additive to the Copy icon below, not a replacement for
        // it — and it goes through the exact same gate: requestCopy() re-authenticates before the
        // copyGrantedField effect below ever touches the clipboard, so a long-press on a field that
        // needs re-auth raises the prompt instead of silently copying.
        //
        // Expressed as the row's own long-press rather than a `combinedClickable` on the modifier.
        // That form cannot register a long-click without a click beside it, so it supplied an empty
        // `onClick` — and then hung the Copy label on that dead tap. A screen reader was offered
        // "Copy <field>", activating it did nothing at all, and the gesture that actually copies
        // was never named. The row's own parameters put the label on the action that performs it.
        onLongClick = if (copyable) ({ viewModel.requestCopy(field) }) else null,
        onLongClickLabel = if (copyable) stringResource(R.string.itemedit_action_copy, label) else null,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            LkFieldLabel(label)
            // Semantics sit on the wrapper, NOT inside the AnimatedContent. Both children exist
            // during the cross-fade, and secretFieldSemantics is a polite live region — two of them
            // would announce contradictory states, one of which reads the secret aloud while it is
            // still notionally hidden. One node, always describing the committed state.
            //
            // Shared with the edit form's SecretField: the wording of "hidden" vs "revealed" is a
            // promise about whether a secret is currently exposed, and it must not depend on which
            // screen the user happens to be looking at.
            Box(Modifier.secretFieldSemantics(label, revealed, value)) {
                AnimatedContent(
                    targetState = revealed,
                    transitionSpec = { fadeIn(revealSpec) togetherWith fadeOut(concealSpec) },
                    label = "secret-reveal",
                ) { shown ->
                    Text(
                        if (shown) displayValue else "•".repeat(value.length.coerceAtMost(16)),
                        // A revealed secret switches to monospace as well as to jade: the change of
                        // *shape*, not only of content, is what makes "this is currently exposed"
                        // impossible to miss when glancing at the screen.
                        style = if (shown) LkText.monoField else LkText.masked,
                        color = ink,
                        // Asymmetric on purpose, and the asymmetry IS the security property —
                        // argued where the rule lives, in secretValueMaxLines' own KDoc.
                        maxLines = secretValueMaxLines(shown),
                        // Unreachable on the revealed branch now. Kept for the masked one, where
                        // 16 bullets can still outrun a narrow column at a large font scale — and
                        // there the ellipsis is correct, since the row is already saying nothing
                        // about length.
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
        LkIconButton(
            icon = if (revealed) LkIcons.EyeOff else LkIcons.Eye,
            label = if (revealed) {
                stringResource(R.string.itemedit_action_hide, label)
            } else {
                stringResource(R.string.itemedit_action_show, label)
            },
            // `revealed` is the state before the toggle, so this fires CONCEAL as it goes away.
            haptic = if (revealed) Haptic.CONCEAL else Haptic.REVEAL,
            onClick = { viewModel.toggleFieldRevealed(field) },
            size = 34.dp,
            iconSize = 22.dp,
            // Amber while revealed, matching `SecretField` in ItemEditScreen - jade is
            // primary-action only, and Copy beside this one already owns it.
            tint = if (revealed) c.amber else c.textMuted,
            container = if (revealed) c.amber.copy(alpha = 0.10f) else c.wash,
            ring = if (revealed) c.amber.copy(alpha = 0.20f) else c.ring,
            shape = LkShape.sm,
        )
        if (copyable) {
            // Copying is gated exactly as revealing is. Tapping Copy on a field that needs
            // re-authentication raises the prompt and then completes the copy — the request is not
            // silently dropped, and it is not silently honoured either.
            val needsAuth = !revealed && state.requiresReauthFor(field)
            LaunchedEffect(state.copyGrantedField) {
                if (state.copyGrantedField == field) {
                    ClipboardHelper.copy(context, label, value)
                    viewModel.onCopyPerformed()
                }
            }
            Spacer(Modifier.size(8.dp))
            LkIconButton(
                icon = LkIcons.Copy,
                label = if (needsAuth) {
                    stringResource(R.string.itemedit_action_copy_gated, label)
                } else {
                    stringResource(R.string.itemedit_action_copy, label)
                },
                haptic = if (needsAuth) Haptic.REVEAL else Haptic.COPY,
                onClick = { viewModel.requestCopy(field) },
                size = 34.dp,
                iconSize = 22.dp,
                tint = c.jade,
                container = c.jade.copy(alpha = 0.08f),
                ring = c.jade.copy(alpha = 0.16f),
                shape = LkShape.sm,
            )
        }
    }
}

@Composable
private fun ItemType.readableName(): String = when (this) {
    ItemType.LOGIN -> stringResource(R.string.itemedit_type_login)
    ItemType.NOTE -> stringResource(R.string.itemedit_type_note)
    ItemType.TOTP -> stringResource(R.string.itemedit_type_totp)
    ItemType.CARD -> stringResource(R.string.itemedit_type_card)
    ItemType.IDENTITY -> stringResource(R.string.itemedit_type_identity)
    ItemType.COORDINATE -> stringResource(R.string.itemedit_type_coordinate)
    ItemType.PHRASE -> stringResource(R.string.itemedit_type_phrase)
    ItemType.PASSKEY -> stringResource(R.string.itemedit_type_passkey)
}

/**
 * A stored recovery phrase, viewed: how many words (a plain readout), the words themselves behind
 * the reveal gate, and the optional passphrase behind its own.
 *
 * The words render joined by spaces — the form a wallet expects on paste, and the numbers were the
 * entry aid, not part of the phrase. Both secrets are always re-auth gated (`ALWAYS_REAUTH_FIELDS`),
 * the same rule the TOTP seed gets: a recovery phrase is the whole key to whatever it opens and
 * nobody needs to glance at one in passing.
 */
@Composable
private fun PhraseDetail(state: ItemEditUiState, viewModel: ItemEditViewModel) {
    // The stored phrase does not verify (4C-14). Since 2026-09-20 the editor lets a
    // checksum-invalid phrase be saved — the person copying words off a plate they can barely read
    // needs somewhere to put them — so the warning has to survive the save, not live only on the
    // form that accepted it. Coral, not amber: `LkColors` gives amber to "nothing is wrong", and a
    // phrase that will not restore is a failure, exactly as the editor's banner paints it.
    //
    // Outside the reveal gate on purpose. Unlike the word *count* (F-26), "these words do not add
    // up" narrows nothing for anyone reading over a shoulder, and it is the one thing on this
    // screen the owner must be able to see without unlocking it again.
    if (state.phraseStatus == SeedPhraseStatus.CHECKSUM) {
        LkInlineNote(
            stringResource(R.string.itemedit_detail_phrase_checksum_invalid),
            icon = LkIcons.Warning,
            accent = LkTheme.colors.coralText,
        )
    }
    LkGroup {
        ReadField(
            stringResource(R.string.itemedit_phrase_words_label),
            // Masked with the words it counts. A stored phrase carried a REPROMPT badge, a field
            // reading "hidden" and a copy button saying "unlocks first" — and, one row above all
            // three, "12 words" in clear the instant the screen opened (F-26, 2026-09-15 visual
            // pass). The length is a fact about the secret, and a screen that publishes one fact
            // about a secret while refusing the rest teaches that the gate is decorative.
            //
            // Two bullets, not twelve: the number of bullets would be the count again.
            if (state.phraseCountHidden) {
                MASKED_COUNT_PLACEHOLDER
            } else {
                pluralStringResource(
                    R.plurals.itemedit_detail_phrase_word_count,
                    state.phraseWordCount,
                    state.phraseWordCount,
                )
            },
            first = true,
        )
        SecretReadField(
            stringResource(R.string.itemedit_detail_phrase_label),
            state.phraseWords.joinToString(" "),
            PHRASE_FIELD,
            state,
            viewModel,
            first = false,
            // Numbered 01, 02, 03 … once revealed, the way the app's own Recovery Kit numbers its
            // words. Twelve or twenty-four words in a run, three to a line, means counting on your
            // fingers to find word 9 — and transcribing a seed phrase onto paper or steel is the
            // one task this item type exists for (F-28, 2026-09-15 visual pass).
            //
            // Display only: `value` above is still the bare words, so Copy puts the phrase on the
            // clipboard in the form a wallet will accept, and the screen reader hears the phrase
            // rather than a list of numbers. The index and its word are joined by a non-breaking
            // space so a wrap can never separate them.
            displayValue = numberedPhraseWords(state.phraseWords),
        )
        // Returns nothing when there is no passphrase — SecretReadField early-returns on a blank.
        SecretReadField(
            stringResource(R.string.itemedit_detail_phrase_passphrase_label),
            state.phrasePassphrase,
            PHRASE_PASSPHRASE_FIELD,
            state,
            viewModel,
            first = false,
        )
    }
}

/**
 * Whether a stored card expiry is in the past, by whole months.
 *
 * A card is good through the end of its printed month, so December 2030 has not expired on any day
 * in December 2030. Blank or unparseable values are never called expired: this is a note about a
 * date the app is sure about, and the fields are free text (`ItemContent.expMonth` is an Int, but
 * the year is a string and an import can carry anything).
 */
internal fun cardExpiryHasPassed(
    month: String,
    year: String,
    today: java.time.YearMonth = java.time.YearMonth.now(),
): Boolean {
    val m = month.trim().toIntOrNull()?.takeIf { it in 1..12 } ?: return false
    val rawYear = year.trim().toIntOrNull() ?: return false
    // Two-digit years are what a card prints and what most exports carry; four-digit ones arrive
    // from Bitwarden. Anything else is not a year this can reason about.
    val y = when {
        rawYear in 0..99 -> 2000 + rawYear
        rawYear in 1000..9999 -> rawYear
        else -> return false
    }
    return java.time.YearMonth.of(y, m).isBefore(today)
}

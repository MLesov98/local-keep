package com.localkeep.feature.itemedit

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.health.StrengthAnalyzer
import com.localkeep.core.vault.ProTypeQuota
import com.localkeep.core.vault.SeedPhraseStatus
import com.localkeep.core.vault.SeedPhraseWordList
import com.localkeep.core.model.FolderPickerState
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.CardBrands
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.TotpAlgorithm
import com.localkeep.core.model.ALWAYS_REAUTH_FIELDS
import com.localkeep.core.model.COORDINATE_FIELD
import com.localkeep.core.model.NOTE_BODY_FIELD
import com.localkeep.core.ui.ClipboardHelper
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.MinTouchTarget
import com.localkeep.core.ui.PermissionDenial
import com.localkeep.core.ui.PermissionDenialSaver
import com.localkeep.core.ui.ProGate
import com.localkeep.core.ui.QrScannerDialog
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.openAppSettings
import com.localkeep.core.ui.openPlayListing
import com.localkeep.core.ui.rememberHaptics
import com.localkeep.core.ui.secretFieldSemantics
import com.localkeep.core.ui.theme.GuardedSecretField
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkActionScaffold
import com.localkeep.core.ui.theme.LkBadge
import com.localkeep.core.ui.theme.LkCardDigitGroups
import com.localkeep.core.ui.theme.LkCardNumberTransformation
import com.localkeep.core.ui.theme.LkCheckRow
import com.localkeep.core.ui.theme.LkChip
import com.localkeep.core.ui.theme.LkCollapsedFoldersSaver
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkEncryptingScreen
import com.localkeep.core.ui.theme.LkFieldGroup
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFolderField
import com.localkeep.core.ui.theme.LkFolderNameStatus
import com.localkeep.core.ui.theme.LkFolderPickerScreen
import com.localkeep.core.ui.theme.LkFolderRow
import com.localkeep.core.ui.theme.folderPickerLeavesOnDismiss
import com.localkeep.core.ui.theme.LkFormField
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkGlyphButton
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkInlineAddButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkLiftedCard
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.LkSavedScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkSectionSwap
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkStrength
import com.localkeep.core.ui.theme.LkStrengthMeter
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LkTintButton
import com.localkeep.core.ui.theme.LocalLkChromeUnpinned
import com.localkeep.core.ui.theme.lkArrival
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkRegenerateSpinDegrees
import com.localkeep.core.ui.theme.lkBloom
import com.localkeep.core.ui.theme.lkBrandedText
import com.localkeep.core.ui.theme.lkRise
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.feature.itemedit.ItemEditUiState.ItemType
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Which way a new Identity was started — see [IdentityStart]. Saveable as an enum name. */
internal enum class IdentityRoute { UNCHOSEN, PHOTO, MANUAL }

/**
 * Fills a new item's Title from [rememberSuggestedTitle] until the user types one. Its own
 * composable so the placement is testable: it must be composed before every early `return` in
 * [ItemEditScreen], and a harness can reproduce that order without the ViewModel.
 */
@Composable
internal fun AutoTitleEffect(
    state: ItemEditUiState,
    titleTyped: Boolean,
    onSuggested: (String) -> Unit,
) {
    val titleSuggestion = rememberSuggestedTitle(state)
    LaunchedEffect(titleSuggestion) {
        if (state.isNew && !titleTyped && state.title != titleSuggestion) {
            onSuggested(titleSuggestion)
        }
    }
}

@Composable
fun ItemEditScreen(
    viewModel: ItemEditViewModel,
    itemId: String?,
    onManageAttachments: (String) -> Unit = {}, // NEW (11c) — default no-op, existing call sites keep compiling
    onSaved: () -> Unit,
    /**
     * Open the item that was just written, by id.
     *
     * Only the Identity photograph route uses it. That route ends on a confirmation screen the
     * user dismisses themselves, so "where does saving lead" stops being a single answer the
     * ViewModel can trigger: [onSaved] fires the moment the write succeeds, and this fires when
     * the user is done reading what was written. Defaulted so every other call site is unchanged.
     */
    onOpenSaved: (String) -> Unit = {},
    onBack: () -> Unit,
    presetType: ItemType? = null, // NEW (B3) — the type chosen in the add sheet
    /** Arrived via the add sheet's "Password" row — open with one already generated and revealed. */
    startGenerated: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(itemId) {
        // The same fallback as the detail screen's — the ViewModel loads the route's item at
        // construction, so this is only reached when that could not run.
        if (itemId != null && viewModel.needsInitialLoad(itemId)) viewModel.loadItem(itemId)
    }
    LaunchedEffect(presetType) {
        presetType?.let(viewModel::presetType)
    }
    // After the preset type, so a handover from the generator wins: it can only mean a login, and
    // the user arrived by asking to save this specific password.
    LaunchedEffect(itemId) {
        if (itemId == null) viewModel.adoptGeneratedPassword()
    }
    // Last, so it cannot overwrite a password handed over from the Generator tab above — that one
    // is a specific value the user chose to keep, this one is "make me any strong password".
    // Generating through the ordinary path means the §5.2 reveal-and-auto-hide runs, so the user
    // can actually read what they are about to save instead of looking at twenty bullets.
    LaunchedEffect(itemId, startGenerated) {
        if (itemId == null && startGenerated && state.password.isEmpty()) {
            // seedGeneratedPassword, not generatePassword: this value was minted by opening the
            // form, not asked for, so backing straight out must not raise the discard prompt.
            viewModel.seedGeneratedPassword()
        }
    }
    // The item stopped being visible while this form held it — a reveal session ended (O1-02,
    // 2026-10-02). The ViewModel has already blanked the form and refuses to save it; this takes the
    // screen off the stack the way ItemDetailScreen and FileItemEditScreen do. Straight back, no
    // dialog: naming the cause would tell whoever holds the phone that a hidden item exists.
    LaunchedEffect(state.concealed) { if (state.concealed) onBack() }
    // A new Identity begun with the camera holds the user on its own "Saved" screen, so the exit
    // is theirs to make — see [onOpenSaved]. Declared before the effect because the effect must
    // not fire for that route, and after `state` because it reads the type.
    var identityRoute by rememberSaveable(state.itemId) { mutableStateOf(IdentityRoute.UNCHOSEN) }
    // No `isPro` term — see the route gate below for why it was removed on 2026-09-07. It has to
    // match that condition exactly: a route the chooser can enter but this predicate calls false
    // would let the encrypting and saved screens draw over a live camera.
    val identityPhotoRoute = state.isNew && state.type == ItemType.IDENTITY &&
        identityRoute == IdentityRoute.PHOTO
    // Every save now shows the write and then what it produced, which is the comp's one firm rule
    // about this moment: *"A save never looks different depending on what you saved."* Until
    // 2026-08-19 exactly one route had these screens — the identity photograph flow — and every
    // other save swapped the Save button's label to "Encrypting…" and vanished.
    //
    // **Where it leads is unchanged.** `onSaved` still fires and still lands wherever the nav graph
    // sends it (the vault list, deliberately, so a card's CVV does not demand a fingerprint seconds
    // after being typed). The only change is that the user sees the payoff on the way.
    //
    // **And it advances itself.** A Done button on every save would be one extra tap on the most
    // repeated action in the app, and the comp argues the case against it in its own scanner note:
    // a screen that waits for an answer when there is only one answer is not asking a question. The
    // button is still there for anyone who reaches it first. The identity route keeps its manual
    // Done because its Saved screen carries two facts worth reading.
    //
    // **Both exits share one guard.** This screen stays composed for the length of the next
    // screen's entrance, so a Done or Back landing just before the dwell ran out fired `onSaved`
    // twice. The second call found the stack already rewritten and popped the vault list itself,
    // leaving an empty NavHost: a dark screen with the bottom bar and nothing else. Reported from
    // the principal's phone after saving a generated password, 2026-10-02.
    var savedExited by remember { mutableStateOf(false) }
    val exitSaved = {
        if (!savedExited) {
            savedExited = true
            onSaved()
        }
    }
    LaunchedEffect(state.saved, identityPhotoRoute) {
        if (state.saved && !identityPhotoRoute) {
            delay(SAVED_DWELL_MILLIS)
            exitSaved()
        }
    }

    // A new item names itself: the title follows what the form holds (`suggestedTitle`) until the
    // user types into it, and from then on it is theirs. Asked for on 2026-10-05 — a 2FA code was
    // already named by its scan, and a location opened on an empty TITLE (REQUIRED) well, a
    // question about an item that did not exist yet. Visible and editable the whole time, so it is
    // still a suggestion rather than the silent default `suggestedTitle` rules out. Through
    // `onTitleSuggested`, not `onTitleChanged`: a login now opens already named "Login 1", and that
    // is not work the Back button should ask to discard.
    //
    // **Composed above every early `return`** (moved 2026-10-17). It sat below the card and identity
    // wizards' returns, so it ran once, on the first composition, while the state was still the
    // default LOGIN — whose suggestion is now always non-blank — and wrote "Login 1" into Title. The
    // wizards never composed it again after the type became CARD or IDENTITY, and the Name-it sheet
    // offered the stale "Login 1". Up here it re-keys on the type's own suggestion, as the flat form
    // always did.
    var titleTyped by rememberSaveable(state.itemId) { mutableStateOf(false) }
    AutoTitleEffect(state, titleTyped, viewModel::onTitleSuggested)

    // High-value secrets (CVV/PIN, TOTP seed, anything on a reprompt item) require the user to
    // prove it's still them before the value is shown (§5.2).
    RevealAuthEffect(state.pendingRevealField, viewModel, forCopy = state.copyAfterReveal)

    // Saveable: a rotation while choosing a folder should not drop the user back into the form.
    var showFolderPicker by rememberSaveable { mutableStateOf(false) }

    // Unsaved-change protection (§5.1): edit is now a distinct mode reached deliberately, so
    // backing out of it must not silently bin the work. Covers the system Back gesture too.
    // rememberSaveable, not remember: a rotation while the discard prompt is up used to
    // dismiss it, dropping the one guard standing between the user and losing their edits.
    //
    // **Declared above the encrypting/saved branch below, not after it** (moved 2026-09-07). It sat
    // after, so the two screens that `return` past it composed no back guard at all: for the whole
    // encrypt, Back popped the editor. The write itself survives — `VaultLockManager.commit` runs
    // its body on an IO dispatcher with no suspension point before `AtomicVaultWriter.write`, so
    // cancelling the ViewModel scope cannot abort it — but everything the user was owed about it is
    // thrown away: the outcome notices, any failure message, and the vault list's highlight of the
    // row that just changed. And when the commit is still *queued* behind another writer's mutex
    // (`VaultRepository`'s mutation lock, reachable via the autofill Save-confirm path), it is
    // suspended at a cancellable point and the edit is lost outright, silently.
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    /**
     * Leave the editor, or raise the discard guard. Returns true when the screen is actually going.
     *
     * The Boolean exists because a caller that has a modal of its own open needs to know which
     * happened. If the editor is leaving, the pop takes that modal down as part of taking the
     * screen down, and closing it first turns one intent into two window teardowns — which the
     * platform plays in sequence, showing the screen being abandoned in a state nobody asked for.
     * If the guard stopped the departure instead, the modal must be closed, or it stays up behind
     * the discard dialog and reappears on "Keep editing".
     *
     * **A save in flight is not a leaveable state.** `isDirty()` compares the form against the
     * baseline and can perfectly well answer false mid-commit, so without this the guard waved the
     * user through the one moment it exists for. Swallowed rather than routed to the discard
     * dialog, which is where this differs from `FileItemEditScreen`'s `isWorking` arm: there the
     * import can still be abandoned, here the write is already going to land, so "discard changes?"
     * would be a question about something that is not happening. Same answer as
     * `BackupSettingsScreen`'s in-flight handler — the operation owns the screen until it reports.
     */
    val leave: () -> Boolean = {
        if (state.isSaving) {
            false
        } else {
            val dirty = viewModel.isDirty()
            if (dirty) confirmDiscard = true else onBack()
            !dirty
        }
    }
    // The folder chooser is a screen now, not a disclosure inside the form, so back has two jobs
    // here and they must not be the same one: with the chooser up, back closes the chooser; the
    // discard guard only applies to leaving the editor itself. Disabled rather than left to
    // dispatcher ordering, because "which BackHandler wins" is not something to leave implicit on
    // the one key standing between the user and losing their edits.
    //
    // Deliberately still enabled while `state.saved`: both payoff screens below compose their own
    // `BackHandler(enabled = true)` after this one, so theirs is the one that runs and Back means
    // "Done" there, exactly as it did when this handler was not composed on that path at all.
    BackHandler(enabled = !showFolderPicker) { leave() }

    // The identity photograph route draws its own pair, with its own copy and its own manual exit.
    if (!identityPhotoRoute) {
        if (state.saved) {
            LkSavedScreen(
                title = stringResource(R.string.itemedit_edit_saved_title),
                body = stringResource(R.string.itemedit_edit_saved_body),
                actionLabel = stringResource(R.string.itemedit_identity_capture_done),
                onAction = exitSaved,
            )
            return
        }
        if (state.isSaving) {
            LkEncryptingScreen(
                title = stringResource(R.string.itemedit_edit_encrypting),
                body = stringResource(
                    if (state.type == ItemType.NOTE) {
                        R.string.itemedit_edit_saving_body_note
                    } else {
                        R.string.itemedit_edit_saving_body
                    },
                ),
            )
            return
        }
    }

    val formScroll = rememberScrollState()
    // Hoisted above the folder-picker branch below, not held inside [MoreSection]: choosing a folder
    // disposes the form, and a user who returned to find the section they had just opened closed
    // again would have to reopen it to see the answer. Still `remember` and not `rememberSaveable`
    // — a rotation should reopen the form on the item's own fields, which is the original rule.
    var moreOpen by remember { mutableStateOf(false) }
    // Both of these belong to screens composed *past* the folder-picker branch below, so both have
    // to be declared before it. Left where they were used, choosing a folder disposed them: the
    // card wizard came back at a step the user had already passed, and someone who had switched a
    // new card to the flat form was thrown back into the wizard. Neither is a rotation, and neither
    // should read like one.
    //
    // A new card is entered a fact at a time; everything else, and every *existing* card, uses the
    // flat form. Editing must never re-walk the flow — someone fixing a typo in a note would be
    // marched through four unrelated screens to reach it.
    var enteredFullForm by rememberSaveable(state.itemId) { mutableStateOf(false) }
    val cardStep = rememberSaveable(state.itemId) { mutableIntStateOf(0) }
    // The identity wizard's step, hoisted for the same reason: the folder chooser replaces the screen.
    val identityStep = rememberSaveable(state.itemId) { mutableIntStateOf(0) }

    val c = LkTheme.colors

    // Composed instead of the editor rather than over it: an overlay leaks taps through its gaps,
    // and a full-screen chooser that half-works is worse than one that replaces the form outright.
    // The form's scroll position is hoisted above this branch so returning lands where the user
    // left, which is the whole reason the old disclosure felt cheaper than it was.
    if (showFolderPicker) {
        ItemFolderPicker(state, viewModel) { showFolderPicker = false }
        return
    }

    if (confirmDiscard) {
        LkDialog(
            onDismiss = { confirmDiscard = false },
            title = stringResource(R.string.itemedit_edit_discard_dialog_title),
            body = stringResource(R.string.itemedit_edit_discard_dialog_body),
            icon = LkIcons.Warning,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.itemedit_edit_discard_confirm),
                onClick = {
                    // Deliberately does NOT clear `confirmDiscard` first. Dismissing this dialog
                    // and popping the screen are two window teardowns of the same intent, and run
                    // as two they play in sequence: the dialog's window comes down, the form the
                    // user just abandoned is redrawn undimmed and complete — generated password
                    // and all — and sits there for ~500 ms before the pop's first frame. Measured
                    // on the emulator, mean luminance of the form region: 29 (dialog up) -> 33 for
                    // seven frames -> 24 (vault list). The user asked to discard and was shown the
                    // thing they discarded.
                    //
                    // Leaving the flag set means the pop takes the dialog with it: this screen
                    // leaves the composition, and its dialog window is torn down as part of that
                    // rather than before it. One intent, one teardown. The flag dies with the
                    // back-stack entry, so there is nothing left set for a later visit to find.
                    onBack()
                },
                emphasis = LkActionEmphasis.Destructive,
            ),
            dismissLabel = stringResource(R.string.itemedit_edit_discard_dismiss),
        )
    }

    // Hoisted above the two branches that return past the form: the card wizard below, and the
    // identity photograph route under it. A dialog is a window, so where it is declared does not
    // change where it draws — and declaring it *inside* the form's column meant the one refusal
    // `blockedBeforeTitle` reports with no `error` (the free-tier allowance, which sets this flag
    // and nothing else) had no renderer on either linear route. `save()` from the identity naming
    // step and `readyToName()` from the wizard's Review step both returned having reported the
    // refusal to a composable that was never composed, so the button did nothing and said nothing.
    // Same window, three routes, one declaration.
    if (state.showProPrompt) {
        val activity = LocalActivity.current
        val context = LocalContext.current
        LkDialog(
            onDismiss = { viewModel.dismissProPrompt() },
            title = stringResource(R.string.itemedit_edit_pro_dialog_title),
            // The cap comes from the constant, not from a literal in the sentence. This
            // dialog said "This item type is part of Pro" until 2026-09-03 and went
            // false the moment one card and one identity became free — it told someone
            // holding a card that the type was not available to them.
            body = if (state.canPurchase) {
                state.proPrice
                    ?.let {
                        stringResource(
                            R.string.itemedit_edit_pro_dialog_body_purchasable,
                            it,
                            ProTypeQuota.FREE_LIMIT,
                        )
                    }
                    ?: stringResource(
                        R.string.itemedit_edit_pro_dialog_body_purchasable_no_price,
                        ProTypeQuota.FREE_LIMIT,
                    )
            } else {
                stringResource(
                    R.string.itemedit_edit_pro_dialog_body_not_purchasable,
                    ProTypeQuota.FREE_LIMIT,
                )
            },
            // The refused type's own glyph, and amber — both matched to the vault
            // list's `QuotaBlockedDialog` on 2026-09-04, because these two are the same
            // refusal reached two ways and they did not look or sound like it. That one
            // is amber with the type's icon and leads with the free remedy; this one was
            // brass with a crown and offered only the purchase, so whichever a user met
            // first taught them a different thing about the same cap.
            //
            // Amber, not brass: nothing has gone wrong and nothing is lost — the vault
            // simply already holds its one (see LkColors). Brass says "this costs
            // money", which is the claim the badge on the add sheet stopped making on
            // the same day, for the same reason: this is a spent allowance, not a gate.
            //
            // Deliberately aligned rather than shared. `QuotaBlockedDialog` lives in
            // :feature:vaultlist, which :feature:itemedit does not depend on, so
            // single-sourcing it means moving it and about sixty catalogue entries of
            // already-verified Tier F copy into :core:ui. Worth doing; not worth doing
            // inside a fix to the *backstop* path, which the add sheet's own refusal
            // stands in front of. The cap itself is already single-sourced —
            // ProTypeQuota.FREE_LIMIT is what both sentences interpolate — so what is
            // duplicated here is a voice, not a number that could drift.
            icon = when (state.type) {
                ItemEditUiState.ItemType.CARD -> LkIcons.Card
                else -> LkIcons.Identity
            },
            accent = c.amber,
            confirm = if (state.canPurchase) {
                LkDialogAction(
                    label = state.proPrice
                        ?.let { stringResource(R.string.itemedit_edit_pro_unlock_button, it) }
                        ?: stringResource(R.string.itemedit_edit_pro_unlock_button_no_price),
                    onClick = {
                        // Dismiss FIRST. `dismissProPrompt` goes through `update`,
                        // which clears `error` — and `unlockPro` reports a failed
                        // purchase onto that same field. Ordered the other way, an
                        // outcome that arrives without suspending is wiped by the
                        // dismissal that follows it, leaving the silence this fixes.
                        viewModel.dismissProPrompt()
                        activity?.let { viewModel.unlockPro(it) }
                    },
                    emphasis = LkActionEmphasis.Pro,
                )
            } else {
                // Not null. A dialog naming a remedy with no way to reach it is a dead end,
                // and foss can at least point at the build that sells it — it still takes
                // no money itself (§N2).
                LkDialogAction(
                    label = stringResource(R.string.itemedit_view_on_play),
                    onClick = {
                        context.openPlayListing()
                        viewModel.dismissProPrompt()
                    },
                    emphasis = LkActionEmphasis.Pro,
                )
            },
            dismissLabel = stringResource(R.string.itemedit_dismiss_not_now),
        )
    }

    if (state.isNew && state.type == ItemType.CARD && !enteredFullForm) {
        CardWizard(
            state = state,
            viewModel = viewModel,
            // Discards the Boolean: the wizard has no modal of its own to keep or close, so it has
            // no use for the answer. Widen its parameter the day it grows one.
            onLeave = { leave() },
            onEnterFullForm = { enteredFullForm = true },
            onChangeFolder = { showFolderPicker = true },
            stepState = cardStep,
        )
        return
    }

    // A new Identity is asked how it wants to start, and "photograph" means the shutter — the
    // capture flow owns the whole screen until it saves or the user backs out. Same "new items
    // only" gate as the card wizard above, and the same `return` past the form.
    // **No `isPro` term.** There was one, and the comment justifying it argued from the binary Pro
    // gate that `ProTypeQuota` replaced on 2026-09-03: Identity is no longer a Pro-only type, the
    // free tier is entitled to one identity document, and the add sheet refuses the *second* one
    // before this screen is ever reached. So the free user who arrives here is a user this app has
    // already agreed may create the item — and the `isPro` term silently took the marquee route
    // away from them, showing the ordinary form with no explanation, since no purchase prompt is
    // due either. Both compensations that comment named were dead: `ProGate` had no call site in
    // this module, and the save-time prompt is raised only by `showProPrompt`, which
    // `creationBlockedByAllowance` leaves false at zero identities used.
    //
    // The harm it was written to prevent — photograph three passport pages, then be refused — is
    // real and is now handled where the work happens: `ItemEditViewModel.captureIdentityPhoto`
    // charges each frame against `AttachmentQuota` and refuses *before* the strip and encrypt,
    // keeping the pages already taken. Dropping this term without that check would have restored
    // exactly the harm.
    if (state.isNew && state.type == ItemType.IDENTITY) {
        when (identityRoute) {
            IdentityRoute.UNCHOSEN -> {
                IdentityStart(
                    onPhoto = { identityRoute = IdentityRoute.PHOTO },
                    onType = { identityRoute = IdentityRoute.MANUAL },
                    onLeave = leave,
                    // Asked here, on the chooser, rather than left for the shutter to discover:
                    // manual entry needs no attachment slot, so only the photo route can be
                    // blocked by it.
                    photoBlockedReason = viewModel.identityPhotoQuotaMustRemove(),
                )
                return
            }
            IdentityRoute.PHOTO -> {
                IdentityCaptureFlow(
                    state = state,
                    viewModel = viewModel,
                    onLeave = leave,
                    onSavedDone = { onOpenSaved(state.itemId.toString()) },
                    // The same route the chooser's second tile picks. Reachable from the
                    // camera-denied state so declining the permission costs a tap, not the flow.
                    onEnterByHand = { identityRoute = IdentityRoute.MANUAL },
                )
                return
            }
            IdentityRoute.MANUAL -> {
                // Typing it in is a wizard, like the card's: the four groups of the ordinary form,
                // one per screen. The ordinary form is for editing a stored identity, which this
                // `isNew` gate never reaches.
                IdentityWizard(
                    state = state,
                    viewModel = viewModel,
                    onLeave = { leave() },
                    onChangeFolder = { showFolderPicker = true },
                    stepState = identityStep,
                )
                return
            }
        }
    }

    // The naming step for a new item. `rememberSaveable`, unlike the "more" accordion above: this
    // one holds a decision the user is part-way through, and a rotation that closed it would drop
    // them back on the form with no sign of why.
    var showSaveSheet by rememberSaveable { mutableStateOf(false) }
    if (showSaveSheet) {
        SaveSheet(
            state = state,
            onDismiss = { showSaveSheet = false },
            onSave = viewModel::saveWithTitle,
        )
    }

    val saveLabel = stringResource(R.string.itemedit_edit_save)
    // The permission machinery for a location item, built once here so the button at the bottom and
    // the failure notice in the form are two views of the same thing. Only for the one type that
    // has a capture at all — every other type would be arming a launcher nothing can fire.
    val locationCapture =
        if (state.type == ItemType.COORDINATE) rememberLocationCapture(viewModel) else null
    val onSave = {
        // The naming step is now the fallback, not the gate. The form carries a TITLE field on a
        // new item as well as an existing one (F-15), so a user who has already typed a name is
        // asked for it a second time only if we make them — and a modal that repeats a field you
        // can see behind it is how a form teaches that its own inputs do not count.
        if (state.isNew && state.title.isBlank()) {
            if (viewModel.readyToName()) showSaveSheet = true
        } else {
            viewModel.save()
        }
    }
    // One owner for the scan tap. The action column's button and the new-item chooser's button
    // reach the screen through two different lambda parameters of this same `LkActionScaffold`
    // call, so a trigger declared inside either one is invisible to the other — and two copies
    // would each hold their own permission launcher and their own denial notice.
    val totpScanTrigger = rememberTotpScanTrigger(viewModel)
    // A new 2FA item with nothing scanned yet. Its only way in is the camera, so the form is the
    // header and one centred group (hint, "Scan the QR code", any camera denial), the pinned slot
    // is empty rather than a Save that could only be dead, and "Folder, tags and more" waits for
    // there to be something to file. After the scan Save takes the slot.
    val awaitingScan = state.type == ItemType.TOTP && state.isNew && state.totpSecret.isBlank()
    // Both dialogs move up here with the trigger that raises them — a dialog is a window, so where
    // it is declared does not affect where it draws, and this is the only scope both call sites see.
    if (totpScanTrigger.showCameraDisclosure) {
        LkDialog(
            onDismiss = totpScanTrigger.onDismissDisclosure,
            title = stringResource(R.string.itemedit_totp_camera_dialog_title),
            body = stringResource(R.string.itemedit_totp_camera_dialog_body),
            icon = LkIcons.Camera,
            confirm = LkDialogAction(
                label = stringResource(R.string.itemedit_totp_camera_dialog_continue),
                onClick = totpScanTrigger.onConfirmDisclosure,
            ),
            dismissLabel = stringResource(R.string.itemedit_dismiss_not_now),
        )
    }
    if (state.showQrScanner) {
        QrScannerDialog(
            title = stringResource(R.string.itemedit_qr_dialog_title),
            cameraUnavailableMessage = stringResource(R.string.itemedit_qr_camera_unavailable),
            onResult = {
                viewModel.scanQrCode(it)
                viewModel.dismissQrScanner()
            },
            onDismiss = viewModel::dismissQrScanner,
        )
    }
    // The Save stack, as a lambda because it lives in one of two places: pinned under the form, or
    // at the end of the scroll while the keyboard has left too little room — see [ItemEditFrame].
    val itemEditActions: @Composable ColumnScope.() -> Unit = {
            val savingLabel = stringResource(R.string.itemedit_edit_saving_a11y)
            val saveText =
                if (state.isSaving) stringResource(R.string.itemedit_edit_encrypting) else saveLabel
            // The label changes with the state, because a button whose text becomes a spinner is
            // nameless to a screen reader.
            val saveSemantics = Modifier.labelled(if (state.isSaving) savingLabel else saveText)
            if (awaitingScan) {
                // Empty on purpose. The scan button is in the form, centred under its hint
                // ([TotpScanStep]); a copy here, or a Save that could only be dead, would be a
                // second control for the same tap. Save takes the slot once a code is in.
            } else if (locationCapture != null) {
                // **One slot.** Find my location, Cancel, Keep location and Save are states of one
                // control in one place, never two commit-shaped buttons at once, and never a
                // disabled Save — see [locationActions] for the three reports this settles.
                CoordinateActionSlot(
                    state = state,
                    viewModel = viewModel,
                    capture = locationCapture,
                    saveText = saveText,
                    saveModifier = saveSemantics,
                    onSave = onSave,
                )
            } else {
                LkPrimaryButton(
                    text = saveText,
                    // `readOnly` is set on exactly one path: an item whose content type this editor
                    // has no mapping for — a Passkey, today — which loads with an error and refuses
                    // every save. Without the guard the loudest control on that screen is one that
                    // cannot work and answers a tap with an error message, which is the shape of
                    // defect this app treats as a lie rather than a nuisance.
                    //
                    // A new item is named last, so Save opens the naming step rather than writing —
                    // and only once the item is complete enough to be worth naming, which is what
                    // `readyToName` decides (it reports its own refusal on the form). An *existing*
                    // item already has a name and saves straight through.
                    //
                    // `phraseSaveBlocked` dims Save on a recovery phrase until every slot holds a
                    // real BIP-39 word (a failed checksum is a coral warning, not a block, since
                    // 4C-14). The banner on the form gives sighted users the
                    // specific reason; F49 (2026-09-13, device walk) added the sentence a screen
                    // reader gets instead — keyed on `phraseStatus`, not on `enabled`, which also
                    // folds in isSaving/readOnly, and not on `phraseSaveBlocked` either: a
                    // Recovery-Kit phrase is checksum-valid and blocked, so the one-sentence version
                    // told that user to repair something that was not broken.
                    onClick = onSave,
                    enabled = !state.isSaving && !state.readOnly && !state.phraseSaveBlocked,
                    modifier = state.phraseSaveDisabledReasonRes?.takeIf { !state.isSaving }?.let {
                        Modifier.labelled(stringResource(it))
                    } ?: saveSemantics,
                )
            }
            // "Scan the QR code", pinned under Save exactly as the comp pins it — a
            // `flex-direction:column;gap:8px` block, primary then secondary, on both the Login and
            // the 2FA screen.
            //
            // `sc-if value="{{ noTotp }}"` on Login: the offer disappears once a factor is
            // attached, because the block in the form is then the thing to look at. On a 2FA item
            // the button is the screen's whole purpose, so it is always there — and it carries the
            // scan-first auto-open for a new one, which is why the flag is the type test rather
            // than a constant.
            val offersScan = when (state.type) {
                // A Login's two entry points are no longer here. Pinned under Save they were two
                // unheaded buttons below the screen's primary commit — and "Enter the details
                // myself", read directly under Save, is an offer to save some other way (F-19,
                // 2026-09-15 visual pass). They live in the form now, under the same `Two-factor`
                // heading that already appeared once a method was chosen; see [LoginFields]. The
                // comp's argument for pinning them ("a full-width bar between the last field and
                // Save reads as a second Save") was about an *unheaded* bar in the flow of the
                // fields, and the heading is what answers it.
                ItemType.LOGIN -> false
                // A new 2FA item with nothing scanned yet already has its scan button in the form
                // (`awaitingScan`, above), so a copy here would be a second control for the
                // same tap. Once a code is in, this is the way to scan a different one.
                ItemType.TOTP -> !awaitingScan
                else -> false
            }
            if (offersScan) {
                TotpScanControls(totpScanTrigger)
            }
    }
    ItemEditFrame(
        formScroll = formScroll,
        hasActions = !awaitingScan,
        actions = itemEditActions,
    ) {
            // The screen is titled by the thing being made — "Login", "Secure note", "2FA code" —
            // which is how `Item Flows.dc.html` heads every one of its six flows. It replaces a
            // "New item"/"Edit item" pair that named the *action* and left the type to a row of
            // chips underneath (removed below): the title said nothing you could not already infer
            // from having tapped Add, and the one fact worth stating at the top of a form was the
            // one the header did not carry.
            //
            // No subtitle. The comp gives these screens a title and then the first field; the
            // sentence that used to sit here explained the form to someone already filling it in.
            LkScreenHeader(
                title = state.type.displayName(),
                modifier = Modifier.lkEnter(),
                trailing = {
                    LkIconButton(
                        icon = LkIcons.ChevronLeft,
                        label = stringResource(R.string.itemedit_detail_back),
                        onClick = { leave() },
                        // Trailing header control: park the plate on the 26dp frame line the wells
                        // below share, not 4dp inside it. See [LkIconButton.plateAlignment].
                        plateAlignment = Alignment.CenterEnd,
                    )
                },
            )

            // The header is deliberately outside the spaced column below.
            // `Arrangement.spacedBy` is uniform, and the gap this one child needs is not the gap
            // every other child needs: the header row is taller than its own title by
            // `HeaderTitleInset` at each end, so 16dp here renders as 28.5dp of air under the
            // title. See `ItemFormHeaderGap`.
            Spacer(Modifier.height(ItemFormHeaderGap))
            Column(
                // Before the scan the form is a header and one centred hero, so this column takes
                // whatever height the header leaves - the outer column is at least one viewport
                // tall, and a weight in a column inside a scroller resolves against that minimum.
                // Afterwards it wraps its content like every other form.
                if (awaitingScan) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(LkSpace.md),
            ) {

                // The type's own fields open the form, then the title, and Folder/Tags at the end.
                //
                // Every type used to open on the same two rows — Title, then Folder — so a Card, a
                // Login and a 2FA code were indistinguishable until the third screenful, and switching
                // type changed nothing you could see without scrolling (2026-07-29 hand-test). What
                // leads is now what makes this item the kind of thing it is; where it gets filed is a
                // separate, later question.
                // No `verticalArrangement` here, deliberately, and it is the fix for the complaint that
                // the form felt bloated. `spacedBy` applies to a `Spacer` child exactly as it applies
                // to a field, so the 8dp spacers each type's fields place between themselves were
                // rendering as 16 + 8 + 16 = 40dp. Measured on device 2026-07-29: the gap between the
                // Username well and the Password label was 42dp, against 18dp between Title and
                // Username, which has no spacer. Nobody chose a 2.3× swing inside one form.
                //
                // With the arrangement gone, every gap in these blocks is the one written at the call
                // site, and every one of them is an [LkSpace] rung: `md` between fields, `sm` where a
                // label or caption hugs what it belongs to, `lg` at a section boundary.
                // The scan group's room: pre-scan it takes what the header leaves, so the group sits
                // in the middle of the screen rather than under the header.
                Box(if (awaitingScan) Modifier.weight(1f) else Modifier) {
                    LkSectionSwap(state.type) { type ->
                        Column {
                            when (type) {
                                ItemType.LOGIN -> LoginFields(state, viewModel)
                                ItemType.NOTE -> NoteFields(state, viewModel)
                                ItemType.TOTP -> TotpFields(state, viewModel, totpScanTrigger)
                                ItemType.CARD -> CardFields(state, viewModel)
                                ItemType.IDENTITY -> IdentityFields(state, viewModel)
                                // `locationCapture` is non-null for exactly this branch — both are
                                // gated on the same type — but the compiler cannot see that, and a
                                // capture-less field is still a readable screen rather than a crash.
                                ItemType.COORDINATE -> locationCapture?.let {
                                    CoordinateFields(state, viewModel, it)
                                }
                                ItemType.PHRASE -> PhraseFields(state, viewModel)
                                // No fields: a passkey has no editable form (see
                                // ItemDetailScreen.PasskeyDetail for the read-only view). `readOnly`
                                // keeps this screen from ever opening for one; this branch exists only
                                // so the `when` stays exhaustive.
                                ItemType.PASSKEY -> Unit
                            }
                        }
                    }
                }

                // **Below the type's own fields, not above them** (2026-10-05, on request). Opening a
                // location on TITLE (REQUIRED) asked the user to name a spot before it had been
                // captured, and a 2FA code is named by the QR it came from. The form now asks for
                // what the item *is* first, fills this in from it (`titleTyped`, above), and lets the
                // user overwrite it. "(required)" left the label with the same change: a field that
                // fills itself is not one the user has to answer, and [SaveSheet] still catches the
                // types that have nothing to be named after.
                //
                // Hidden only while the item has nothing to be named after *and* cannot be saved
                // anyway — a 2FA code before its scan, a location before its fix. A field here then
                // could only be an empty well under the one control that matters.
                //
                // Scrolled into view on normal entry, NOT focused there — focusing on every keystroke
                // opened the IME, which shortened the viewport below the height of the error rect
                // LkTextField asks for; that request could then never be satisfied, and an
                // unsatisfiable bring-into-view fights every scroll gesture. The form froze — the tag
                // field two rows down was unreachable until a title was typed (2026-07-29).
                //
                // T6 (2026-08-12): a *blank-title save* is a different gesture from ordinary typing —
                // it happens once, after Save/Go, not on every keystroke — so it gets both: scroll,
                // then focus, in this one effect (a second LaunchedEffect on the same key would race
                // it). Same FocusRequester-on-the-outer-modifier pattern the new-folder input
                // (`folderFocus`, below) already uses to focus LkTextField's inner BasicTextField
                // without LkTextField exposing one of its own.
                // **Every item, new or not.** It was gated on `!state.isNew` until 2026-09-15: a
                // new Login opened on USERNAME, PASSWORD, WEBSITES, NOTES with no title field at
                // all, while the *same screen* reopened on the saved item led with TITLE (REQUIRED)
                // — two different forms for one editor, and the difference was invisible until the
                // user went looking for the name field that was there last time (F-15, visual pass).
                //
                // [SaveSheet] stays, and stays the argument it always was — a new item can be named
                // at the end, after the thing being named exists — but it is now the *fallback* for
                // an item that reaches Save with no name, rather than the only way to give one. A
                // user who wants to type the title first can; a user who does not is asked once,
                // exactly as before.
                val nothingToNameYet = state.isNew && state.title.isBlank() && when (state.type) {
                    ItemType.TOTP -> state.totpSecret.isBlank()
                    ItemType.COORDINATE -> state.coordinateFix == null
                    else -> false
                }
                if (!nothingToNameYet) {
                    val titleReveal = remember { BringIntoViewRequester() }
                    val titleFocus = remember { FocusRequester() }
                    LaunchedEffect(state.titleError) {
                        if (state.titleError) {
                            titleReveal.bringIntoView()
                            titleFocus.requestFocus()
                        }
                    }
                    Field(
                        state.title,
                        {
                            titleTyped = true
                            viewModel.onTitleChanged(it)
                        },
                        stringResource(R.string.itemedit_edit_title_field_label),
                        isError = state.titleError,
                        modifier = Modifier.bringIntoViewRequester(titleReveal).focusRequester(titleFocus),
                    )
                    if (state.titleError) {
                        val requiredCaption = stringResource(R.string.itemedit_edit_title_required_caption)
                        Text(
                            requiredCaption,
                            style = LkText.caption,
                            color = LkTheme.colors.coral,
                            modifier = Modifier.announce(stringResource(R.string.itemedit_edit_title_required_a11y)),
                        )
                    }
                }
                // No section-boundary spacer here any more. `LkSectionSwap` and `MoreSection` are two
                // children of a `spacedBy(16.dp)` column, which is already the comp's block gap —
                // and a `Spacer` inside a spaced column is spaced at both ends, so the `lg` that
                // used to sit here rendered as 16 + 24 + 16 = 56dp. Measured 61dp on the emulator
                // on 2026-08-19: a screenful of nothing between "Add website" and
                // "Folder, tags and more", where the comp has 16.
                //
                // The boundary it was marking is real, but the comp marks it with the row's own
                // ring rather than with air — "Folder, tags and more" is visibly a control and the
                // things above it are visibly fields.
                // Filing and options, behind one disclosure. See [MoreSection].
                // Not before the scan: a 2FA item with nothing in it has nothing to file (the title is
                // hidden for the same reason, `nothingToNameYet`), and it returns once the code lands.
                if (!awaitingScan) MoreSection(
                    state = state,
                    viewModel = viewModel,
                    open = moreOpen,
                    onOpenChange = { moreOpen = it },
                    onChangeFolder = { showFolderPicker = true },
                    // A login's second factor and its notes, folded away with the rest of what most
                    // logins never use (2026-10-05, on request). Open, a Two-factor heading sitting
                    // between Websites and Notes read as a required step of saving a password.
                    leading = if (state.type == ItemType.LOGIN) {
                        { LoginExtras(state, viewModel, totpScanTrigger) }
                    } else {
                        null
                    },
                )
                // No Spacer here. This column is `spacedBy`, so a Spacer is itself a spaced child:
                // LkSpace.md between two 16 dp gaps rendered as ~81 dp of dead air between the
                // reprompt note and "Manage attachments", where the rest of the form keeps 16 dp
                // (measured 2026-09-11).
                // Scrolled to, not just rendered: this sits at the bottom of a form taller than the
                // screen, so a refusal shown here was invisible from the Save button that caused it.
                // Only for errors no field owns — see [shouldRevealErrorBanner].
                val errorReveal = remember { BringIntoViewRequester() }
                LaunchedEffect(state.error, state.titleError, state.cardNumberError) {
                    if (shouldRevealErrorBanner(
                            state.error,
                            fieldOwnsError = state.titleError || state.cardNumberError,
                        )
                    ) {
                        errorReveal.bringIntoView()
                    }
                }
                errorBannerFor(state.error, state.titleError)?.let { errorRes ->
                    // `state` is a delegated property, so `state.errorArg` needs its own local val
                    // before Kotlin can smart-cast it past the null check.
                    val arg = state.errorArg
                    val message = if (arg != null) stringResource(errorRes, arg) else stringResource(errorRes)
                    LkInlineError(message, Modifier.bringIntoViewRequester(errorReveal).announce(message))
                }
                // Not an error — the save succeeded — but it must be read before leaving, so it uses
                // the same loud treatment and the screen stays put until it is dismissed.
                state.conflictNotice?.let {
                    val message = stringResource(it)
                    LkInlineError(message, Modifier.announce(message))
                }
                if (!state.isNew) {
                    // Ungated, because attachments stopped being a tier gate when they became
                    // quota-limited. This panel was the last place still saying otherwise: it
                    // showed "Files & media — PRO" in the primary edit form of every item, under
                    // the exact name the drawer uses for a destination the same free user can open
                    // one tap away and use immediately. `AttachmentQuota`'s own KDoc records the
                    // ruling — free means five, not zero — and `AttachmentPicker.attach()` has
                    // enforced it as "a count check, not a tier check" since. `QuotaBlockedDialog`
                    // was rewritten for exactly this reason, and this screen was missed.
                    //
                    // The cap is still enforced, and enforced where the add happens rather than at
                    // the door: opening the panel shows what is there, and attaching a sixth file
                    // on a free vault raises the quota dialog. That is the invariant the quota is
                    // written to keep — it is an *add*-path question only, never a filter on a
                    // list, because hiding a stored file in a password manager is the same as
                    // losing it. Found by the F16 UX persona, 2026-08-30.
                    LkSecondaryButton(
                        text = stringResource(R.string.itemedit_edit_manage_attachments_button),
                        icon = LkIcons.Attachment,
                        onClick = { onManageAttachments(state.itemId.toString()) },
                    )
                }
            }
    }
}

/**
 * The item editor's frame: the form scrolling under a Save stack that stays put.
 *
 * **On a window too short for that, the Save stack stops being pinned while the keyboard is up** — the
 * app's one keyboard-up rule ([rememberLkKeyboardUnpinsChrome], published by [LkActionScaffold] as
 * [LocalLkChromeUnpinned]). Header, form and a pinned Save (plus "Scan the QR code") are three claims
 * on one window, and at 308dp wide and 2.0 text the keyboard left the form what the Save stack did
 * not take: a field being typed into with the stack above the keys and the header gone. Here the
 * stack is emitted as the last child of the form's own scroll instead, so the focused field gets
 * the room; pinned again the moment the keyboard closes. With the room to spare (a Pixel-sized
 * portrait window) it stays pinned just over the keys, which is the better arrangement.
 *
 * The form keeps its place in the tree in both arrangements — only [actions] moves — so the field
 * the keyboard was raised for is not disposed by the keyboard arriving.
 */
@Composable
internal fun ItemEditFrame(
    formScroll: ScrollState,
    /** False while the form is the 2FA scan chooser, whose one control is already in the form. */
    hasActions: Boolean,
    actions: @Composable ColumnScope.() -> Unit,
    form: @Composable ColumnScope.() -> Unit,
) {
    LkActionScaffold(
        accent = LkTheme.colors.jade,
        auraAlpha = 0.07f,
        // Centred, as the comp grounds every item flow (`at 50% 38%`). Was 0.3.
        auraBias = 0.5f,
        // Two actions, not one, and the comp stacks them: `Save` then, for a login with no second
        // factor yet, "Scan the QR code" — `display:flex;flex-direction:column;gap:8px` under
        // a 16px rule. 8dp is the comp's gap.
        actionSpacing = LkSpace.sm,
        // Same margin as the form above it, so Save's edges land on the wells' edges.
        horizontalPadding = LkFrame.horizontal,
        // Left empty, not skipped, while unpinned: the empty bar's `imePadding()` is what reserves
        // the keyboard's height for the scroll region above it.
        action = { if (!LocalLkChromeUnpinned.current) actions() },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(formScroll)
                // 26dp, the item flows' own margin. `Item Flows.dc.html` pads every one of its six
                // screens `90px 26px 32px`, and [LkFrame.horizontal] is that 26 — the 20dp
                // [LkFrame.screenHorizontal] this used is the *list* frame, which is right for the
                // vault and wrong here. Six dp on each side is 12dp of well width, and it is the
                // difference between the comp's proportions and something near them.
                .padding(horizontal = LkFrame.horizontal)
                .padding(top = ItemFormTop, bottom = 16.dp),
        ) {
            form()
            if (hasActions && LocalLkChromeUnpinned.current) {
                Spacer(Modifier.height(LkSpace.lg))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                    actions()
                }
            }
        }
    }
}

/**
 * Folder, tags, custom fields and the reprompt toggle, behind one disclosure.
 *
 * `Item Flows.dc.html` collapses all four into a single "Folder, tags and more" row with a live
 * summary. The argument for it is what the form looks like without it: four always-open sections
 * of *filing* between the fields the user came to type and the Save button they are aiming at, on
 * every item, whether or not they intend to file anything. Most items are filed once and never
 * again; a control used once should not cost a screenful every time.
 *
 * **Every type, not only the two the comp draws.** The comp shows Login and Note. This is one form
 * shared by six types, and two editors that disagree about where the folder picker lives is a worse
 * outcome than a wider diff (principal's call, 2026-08-19).
 *
 * The summary is what makes collapsing safe: "Personal · 2 tags · reprompt" answers the only
 * question a closed section raises, which is whether anything is inside it. A disclosure that hides
 * its own state is how a user loses a setting they made.
 */
@Composable
private fun MoreSection(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onChangeFolder: () -> Unit,
    /** Type-specific content drawn first in the panel — a login's two-factor and notes. */
    leading: (@Composable () -> Unit)? = null,
) {
    val c = LkTheme.colors
    val chevron by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = lkTween(LkMotion.Crossfade, easing = LkMotion.Settle),
        label = "more-chevron",
    )

    // Filing is here for a new item too, as `Item Flows.dc.html` draws it — the comp's untitled
    // Login shows "Folder, tags and more · none set" on the form itself. It lived in the naming
    // step for three weeks (2026-08-19, revision 2) and came back out on 2026-08-19 with the
    // chooser: a modal sheet cannot open a screen without destroying itself and the half-typed
    // title inside it.
    val namedFields = state.customFields.count { it.name.isNotBlank() || it.value.isNotBlank() }
    val summary = buildList {
        // First, because they are what [leading] puts first — and a login's 2FA and notes are now
        // behind this row, so the summary is the only place a closed form says they exist.
        if (state.type == ItemType.LOGIN) {
            if (state.totpSecret.isNotBlank()) add(stringResource(R.string.itemedit_edit_more_summary_totp))
            if (state.loginNotes.isNotBlank()) add(stringResource(R.string.itemedit_edit_more_summary_notes))
        }
        state.folders.firstOrNull { it.id == state.folderId }?.let { add(it.name) }
        if (state.tags.isNotEmpty()) {
            add(pluralStringResource(R.plurals.itemedit_edit_more_summary_tags, state.tags.size, state.tags.size))
        }
        if (namedFields > 0) {
            add(pluralStringResource(R.plurals.itemedit_edit_more_summary_fields, namedFields, namedFields))
        }
        if (state.favorite) add(stringResource(R.string.itemedit_edit_more_summary_favourite))
        if (state.reprompt) add(stringResource(R.string.itemedit_edit_more_summary_reprompt))
    }.joinToString(" · ").ifEmpty { stringResource(R.string.itemedit_edit_more_summary_none) }

    val label = stringResource(R.string.itemedit_edit_more_label)
    val toggleLabel = stringResource(
        if (open) R.string.itemedit_edit_more_collapse_a11y else R.string.itemedit_edit_more_expand_a11y,
    )
    // `onClickLabel` above is the "double tap to…" action hint, not the node's name or its state —
    // the same distinction `LkFolderPicker` spells out at its chevron. On its own it left the only
    // route to Folder, Tags, custom fields and Reprompt announcing nothing about whether the
    // section under it was already showing, so a screen-reader user had to open it and listen for
    // the layout to change to find out. A state word, not a second name: TalkBack speaks it after
    // the row's own text and re-speaks it when it changes, and the summary below keeps reading.
    val stateWord = stringResource(
        if (open) R.string.itemedit_edit_more_state_expanded else R.string.itemedit_edit_more_state_collapsed,
    )
    // Opening this at the bottom of a long form used to open it out of sight. See [rememberOpenReveal].
    val panelReveal = rememberOpenReveal(open)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LkShape.xl)
                .lkSurface(LkShape.xl, Color.Transparent, c.ring)
                .minTouchTarget()
                .semantics { stateDescription = stateWord }
                .clickable(onClickLabel = toggleLabel) { onOpenChange(!open) }
                .padding(horizontal = LkSpace.md, vertical = LkSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            MoreSectionHeadline(label, summary, chevron)
        }
        if (open) {
            // `lkRise` and not `AnimatedVisibility`: this branch composes and disposes rather than
            // flipping while on screen, which is the one call pattern `lkRise` documents as safe.
            Column(
                Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(panelReveal)
                    .padding(top = LkSpace.xs)
                    .lkRise()
                    .lkSurface(LkShape.card, c.surface, c.ring)
                    .padding(LkSpace.md),
                // Safe only because each of the four below is exactly *one* child: FolderPicker,
                // TagEditor and CustomFieldsSection each wrap themselves in a Column, and
                // RepromptRow does the same for that reason. A composable emitting several children
                // into a `spacedBy` Column gets its own internal gaps spaced too.
                verticalArrangement = Arrangement.spacedBy(LkSpace.md),
            ) {
                // One child, like the four below: [LoginExtras] wraps itself in a Column.
                leading?.invoke()
                // The comp's order: where it goes, what it is tagged, what else it holds, and only
                // then how it is protected. Filing before options.
                LkFolderField(
                    selectedName = state.folders.firstOrNull { it.id == state.folderId }?.name,
                    onChange = onChangeFolder,
                )
                TagEditor(state, viewModel)
                // A marking, so it sits with tags rather than with the reveal gate below. Until
                // 2026-09-20 (sweep 2B-6 / 4A-F5) the flag could only be set from the vault list's
                // row menu, so an item already open in front of the user could not be favourited
                // without leaving it — and the editor, which is where every other property of the
                // item lives, said nothing about it at all.
                CheckboxRow(
                    stringResource(R.string.itemedit_favourite_checkbox_label),
                    state.favorite,
                    viewModel::onFavoriteChanged,
                )
                // Free. The gate was never enforcing anything: a Secure Note is free and unlimited,
                // so "account number" and "security question" always had somewhere to go — the
                // paywall only made that somewhere worse, and put it on a different item from the
                // login it belongs to. Pro is what the vault *holds* beyond passwords (cards,
                // identities, files and media), not how many boxes a login is allowed.
                CustomFieldsSection(state, viewModel)
                RepromptRow(state, viewModel)
            }
        }
    }
}

/**
 * The closed row of [MoreSection]: its name, the summary of what is inside, and the chevron.
 *
 * Label and summary share one weighted slot, so neither can take the chevron's room, and inside it
 * the summary is the one that yields — one line, ellipsised (O1-05, 2026-10-02). It was unweighted
 * with no line limit, so a long folder name was measured first, wrapped across the whole row, and
 * left the weighted label and the chevron zero wide: the only way into folder, tags and reprompt
 * read as a grey block. `internal` so a JVM test can measure it.
 *
 * A [FlowRow] and not a `Row` with the summary in `weight(1f)` (device font-scale sweep,
 * 2026-10-28). The label is measured first and keeps its whole line, so at a 360dp window and font
 * scale 1.5 the summary was handed what was left — "no…", two letters of "none set" — and the one
 * thing a closed section has to say, whether anything is inside it, was unreadable on the screen
 * most phones have. A flow row measures each child whole and moves the one that no longer fits
 * under the label, where it has the full width; where it does fit (411dp and 1.0) it stays beside
 * the label at the end, as before. A summary too long for even a line to itself still ellipsises
 * there, so the label and chevron are never starved (O1-05 above).
 */
@Composable
internal fun RowScope.MoreSectionHeadline(label: String, summary: String, chevronDegrees: Float) {
    val c = LkTheme.colors
    FlowRow(
        Modifier.weight(1f),
        // SpaceBetween puts the summary at the end beside the label and, alone on a stacked line,
        // at the start under it. The gap is the label's own end padding, since SpaceBetween has none.
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LkText.navLabel, color = c.textSecondary, modifier = Modifier.padding(end = LkSpace.sm))
        Text(
            summary,
            style = LkText.monoCaption,
            color = c.textFaint,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // A flow row decides where to break from a child's *minimum* intrinsic width, which for
            // text is its longest word — "none" — so without this it kept "none set" beside the
            // label and squeezed it to an ellipsis anyway. Max intrinsic asks for the whole value.
            modifier = Modifier.width(IntrinsicSize.Max),
        )
    }
    Icon(
        LkIcons.ChevronRight,
        null,
        tint = c.textFaint,
        modifier = Modifier.size(18.dp).rotate(chevronDegrees),
    )
}

/**
 * The per-item reveal gate, with a caption that states what it actually does in each position.
 *
 * Both captions are Tier G and both are narrower than the obvious wording, because the obvious
 * wording is false. **On** does not mean "asks every time" in a wall-clock sense — a field already
 * revealed stays revealed until its auto-hide fires — it means every *reveal or copy* is challenged
 * (`requiresReauthFor`, and the Copy button derives `needsAuth` from the same call). **Off** does
 * not mean "no gate": `ALWAYS_REAUTH_FIELDS` keeps a CVV, a PIN, a TOTP seed and a saved position
 * behind the prompt on every item, flag or no flag. A caption that said otherwise would be telling
 * the user their card is less protected than it is.
 */
@Composable
private fun RepromptRow(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
) {
    val c = LkTheme.colors
    Column(Modifier.fillMaxWidth()) {
        CheckboxRow(stringResource(R.string.itemedit_reprompt_checkbox_label), state.reprompt) {
            viewModel.onRepromptChanged(it)
        }
        Text(
            stringResource(
                if (state.reprompt) R.string.itemedit_reprompt_caption_on else R.string.itemedit_reprompt_caption_off,
            ),
            style = LkText.caption,
            color = c.textFaint,
            modifier = Modifier.padding(top = LkSpace.xs),
        )
    }
}

/**
 * The item editor's folder chooser: [LkFolderPickerScreen] wired to this screen's ViewModel.
 *
 * The control moved to `core:ui` on 2026-08-19 so the file editor could file a file with the same
 * one, and became a screen rather than a disclosure later the same day. Everything domain-shaped
 * stayed here — the tree walk's disambiguated names, what deleting a folder does to the items
 * inside it, and what the back key means at each stage — because those are statements about a
 * vault, and `core:ui` knows nothing about vaults.
 *
 * Picking commits and leaves in one tap, so there is no Save on this screen and nothing to lose by
 * backing out of it: the only writes it can make are creating and deleting folders, and both are
 * committed where they are made.
 */
@Composable
internal fun ItemFolderPicker(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    onBack: () -> Unit,
) {
    // The folder the user just made, for exactly one arrival. Deliberately *not* ViewModel state:
    // a flash is presentation, it is over in under two seconds, and a rotation that loses it is the
    // correct outcome rather than a bug to fix with a saveable.
    var arrivedFolderId by remember { mutableStateOf<UUID?>(null) }
    var knownFolderCount by remember { mutableIntStateOf(state.folders.size) }
    LaunchedEffect(state.folders.size) {
        // createFolderInternal selects what it creates, so the new row is the selected one — and
        // this only fires when the list actually grew, so selecting an existing folder does not
        // make an old row announce itself as new.
        if (state.folders.size > knownFolderCount) arrivedFolderId = state.folderId
        knownFolderCount = state.folders.size
    }

    // Two stages, innermost first: the name being typed, then the screen. There is no third — the
    // comp's list has no disclosure to collapse, and no trash, so back has nothing else to unwind
    // and there is no delete confirmation left on this screen to dismiss.
    //
    // Making a folder leaves the card open and empty for the next one, so "the card is open" no
    // longer means "a name is being typed": once something was made and the field is empty, Back
    // leaves rather than closing an empty card first. Leaving closes the card too, so the next visit
    // does not open on one.
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

    // Every folder, at its own depth. `rows` gates children on the expanded set, so passing every id
    // as expanded is how you ask it for the whole tree — which is what the comp draws: flat and
    // indented. Reusing the walk rather than adding a second one keeps the depth cap, the name sort
    // and the sibling disambiguation in the one place they are tested.
    //
    // Collapsed ids are subtracted from that, so the screen still *opens* flat and only hides what
    // the user has folded away (2026-08-20, on request: a deep tree makes this list unusable).
    var collapsedFolders by rememberSaveable(stateSaver = LkCollapsedFoldersSaver) {
        mutableStateOf(emptySet<UUID>())
    }
    val allFolderIds = state.folders.mapTo(mutableSetOf()) { it.id }
    // **Every folder, deliberately — this picker is not the vault list.**
    //
    // Item 6 asked for folders to be filtered by item type, and the vault list's strip now is (see
    // FolderRelevance). The same filter was tried here on 2026-08-21 and reverted the same day,
    // because a device pass showed what it costs: a vault holding Alpha and Beta and no 2FA code
    // yet offered *neither* when filing the first one. The filter is worst exactly when the vault
    // is youngest, and "where should this go?" has a different right answer from "where are my 2FA
    // codes?" — the second is a dead end if it does not narrow, the first is a dead end if it does.
    //
    // Two folders may legally share a name under different parents — a merge of two vaults produces
    // exactly that — and a picker offering the same word twice makes the choice unknowable. Resolved
    // here rather than in the control, which is handed names and does not know what a parent is.
    val rows = FolderPickerState.rows(state.folders, allFolderIds - collapsedFolders).map { row ->
        // `map` and `let` are inline, so these resolve in the composition that called us.
        val base = row.disambiguateBy
            ?.let { stringResource(R.string.itemedit_folder_picker_subfolder, row.folder.name, it) }
            ?: row.folder.name
        LkFolderRow(
            id = row.folder.id,
            // "Personal (12)", as the comp names its rows. Zero is shown too — an empty folder that
            // said nothing would be indistinguishable from one whose count failed to load.
            name = stringResource(
                R.string.itemedit_folder_name_suffix,
                base,
                state.folderItemCounts[row.folder.id] ?: 0,
            ),
            depth = row.depth,
            hasChildren = row.hasChildren,
            expanded = row.folder.id !in collapsedFolders,
        )
    }

    val typed = state.newFolderName.trim()
    // Siblings only, and case-insensitively — the same comparison createFolderInternal makes, so
    // this cannot say "fine" about a name the commit will refuse.
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

@Composable
private fun TagEditor(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
) {
    val c = LkTheme.colors
    val addTagLabel = stringResource(R.string.itemedit_tag_add_label)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LkFieldLabel(stringResource(R.string.itemedit_tags_label))
        // Chips before the input, which is the comp's order and the useful one: what this item is
        // already tagged with is the thing you check before deciding whether to add another. Below
        // the input they were the last thing on the block and read as an outcome rather than as
        // state.
        //
        // Existing tags as removable chips rather than a stack of full-width rows: tags are short,
        // and a list of them reading one per line made a three-tag item look like a form section.
        if (state.tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth(),
                // 6dp, the comp's `gap:6px` on the tag row. Chips are small objects in a wrap; at
                // 8dp a row of four starts to read as four separate controls.
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
                        modifier = Modifier.labelled(stringResource(R.string.itemedit_tag_remove_a11y, tag)),
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LkTextField(
                value = state.tagInput,
                onValueChange = viewModel::onTagInputChanged,
                label = addTagLabel,
                placeholder = stringResource(R.string.itemedit_tag_add_placeholder),
                shape = LkShape.xl,
                modifier = Modifier.weight(1f),
                // Enter adds the tag, as it does in the bulk-tag dialog. Without this the two
                // places you type a tag behaved differently, and the one people meet first taught
                // the habit that fails in the other.
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

@Composable
private fun LoginFields(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
) {
    // No container. `Item Flows.dc.html` draws Username and Password as two bare labelled wells on
    // the ground, and the card that used to hold them was the principal's complaint on 2026-08-19:
    // *"we have a box inside of which we keep username and password, I need it exactly 1:1."* It
    // also cost width — 16dp of inset on each side of a well that was already sharing its row with
    // three buttons.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        // Logins are email addresses far more often than not, and the email layout only moves the
        // "@" and "." within reach — it constrains nothing, so a username that is not an address is
        // unaffected.
        Field(
            state.username,
            viewModel::onUsernameChanged,
            stringResource(R.string.itemedit_username_label),
            KeyboardType.Email,
        )
        SecretField(
            value = state.password,
            onValueChanged = viewModel::onPasswordChanged,
            label = stringResource(R.string.itemedit_password_label),
            field = "password",
            state = state,
            viewModel = viewModel,
            // Without this the only way to get a strong password into an item was to leave the
            // half-filled form for the Generator tab and carry the value back on the clipboard.
            onGenerate = viewModel::generatePassword,
        )
        PasswordStrengthRow(
            state.password,
            onGenerate = viewModel::generatePassword,
            onUndoGenerate = viewModel::undoGeneratedPassword,
            canUndo = viewModel.canUndoGeneratedPassword,
            entropyBits = state.generatedEntropyBits,
        )
    }
    // `md`, the comp's 16px block gap, written out because the Column these land in carries no
    // `verticalArrangement` — see the note above `LkSectionSwap`.
    Spacer(Modifier.height(LkSpace.md))
    WebsiteEditor(state, viewModel)
}

/**
 * A login's second factor and its notes, drawn inside [MoreSection] rather than in the form.
 *
 * Both were open fields under the websites until 2026-10-05, and the principal's question on seeing
 * them was what two-factor had to do with saving a login at all. Neither is part of what a login
 * *is* — most never get either — so they sit behind the same disclosure as folder and tags, and the
 * disclosure's summary names them when they hold something.
 *
 * The factor arrives by scanning only. The manual Issuer/Account/secret fields this used to offer
 * went with the same request: a QR code is the one way in, here and on the 2FA item.
 */
@Composable
private fun LoginExtras(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    scanTrigger: TotpScanTrigger,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        // The seed lands in this login's own `totpSecret`, which `LoginTotpSection` renders with a
        // live code — so what was scanned is verifiable before Save: a seed from the wrong account
        // produces codes that never work and nothing else catches that.
        if (state.totpSecret.isNotBlank()) {
            LoginTotpSection(state, viewModel)
        } else {
            LkFieldGroup(spacing = LkSpace.sm) {
                Text(
                    stringResource(R.string.itemedit_login_totp_heading),
                    style = LkText.buttonSmall,
                    color = LkTheme.colors.textSecondary,
                )
                TotpScanControls(scanTrigger)
            }
        }
        // Built exactly like the Card's notes well — same composable, same heights — because they
        // are the same field doing the same job.
        BoxedField(
            value = state.loginNotes,
            onValueChanged = viewModel::onLoginNotesChanged,
            label = stringResource(R.string.itemedit_notes_label),
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            // Same IME suppression as the secure note's body, and for the same reason: a notes well
            // is where the things with no dedicated box are typed. See the note on `NoteFields`.
            keyboardType = KeyboardType.Password,
            minHeight = 86.dp,
            maxHeight = 220.dp,
        )
    }
}

/**
 * The four-band meter, under the password well, for a password that was *typed*.
 *
 * The gap this closes: the generator has scored entropy since it shipped, and the form where
 * passwords are actually entered scored nothing at all. `Item Flows.dc.html`: *"Strength is shown
 * where the password is."*
 *
 * **Scored with zxcvbn, not with length.** The comp computes `length × log2(72)`, which reports
 * "aaaaaaaaaaaaaaaa" as Strong — see `LkStrength.ofScore` for why that is the one direction a
 * password manager must not be wrong in. `StrengthAnalyzer` is the same scorer the vault-health
 * dashboard buckets weak logins with, so the form and the dashboard cannot disagree about a
 * password the user is looking at in both.
 *
 * **Scored off the main thread, and debounced.** zxcvbn's dictionary matching is not free and this
 * runs on a field being typed into. 120ms of quiet is short enough to feel immediate and long
 * enough that a burst of keystrokes costs one measurement rather than one per character.
 *
 * The *meter* is absent on an empty field rather than showing an empty one: nothing has been said
 * yet, and four grey segments claiming "Weak" about a field the user has not filled is a verdict on
 * nothing. The row itself is not, and that distinction is the fix for a first-run dead end. Until
 * 2026-09-11 this composable returned early on an empty password and took the generate control with
 * it, because the control lives here — so a brand-new Login, which is the single most likely moment
 * to want a generated password, offered `Show Password` and `Copy Password` (both no-ops on an empty
 * value) and no way to generate one short of the Generator tab and the clipboard. Now the row draws
 * whenever it has a control to draw, and only the meter waits for something to measure.
 */
@Composable
private fun PasswordStrengthRow(
    password: String,
    /** Null on every field that is transcribed rather than invented — see [SecretField]. */
    onGenerate: (() -> Unit)? = null,
    onUndoGenerate: (() -> Unit)? = null,
    canUndo: Boolean = false,
    /** See [ItemEditUiState.generatedEntropyBits]: the draw's own measure, or null for a typed value. */
    entropyBits: Double? = null,
) {
    // Nothing to draw at all: no value to measure and no control to offer.
    if (password.isEmpty() && onGenerate == null) return
    val c = LkTheme.colors
    val bits = entropyBits?.toInt()?.takeIf { password.isNotEmpty() }
    // Only ever run for a value whose entropy is not already known. zxcvbn is the right scorer for
    // something typed and the wrong one for something drawn: it reads a 20-character CSPRNG string
    // off its characters and lands on "Excellent" where the draw that produced it knows the figure
    // exactly, which is how the Generator said "Reasonable · 77 bits" and this row said "Excellent"
    // about the identical string one tap later (F-29, 2026-09-15 visual pass).
    val scored by produceState(LkStrength.Weak, password, bits) {
        if (bits != null) return@produceState
        delay(STRENGTH_DEBOUNCE_MILLIS)
        value = withContext(Dispatchers.Default) {
            LkStrength.ofScore(StrengthAnalyzer.score(password))
        }
    }
    val strength = if (bits != null) LkStrength.ofBits(bits) else scored
    // The meter and, since 2026-09-09, regenerate — moved off the field's trailing row by the UI
    // gate. Its separation from Copy is now vertical, a different axis from every reversible
    // control, which is the point: a slip along the row cannot reach it at all.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = LkSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `Spacer` rather than the meter while the field is empty — it holds the generate button
        // at the same right-hand edge it occupies once there is a password, so the control does not
        // jump sideways the moment the user types a character.
        if (password.isEmpty()) {
            Spacer(Modifier.weight(1f))
        } else {
            LkStrengthMeter(
                strength,
                modifier = Modifier.weight(1f),
                // The figure that justifies the word, in the slot the component reserves for
                // exactly that — and drawn the way the Generator draws it, because the whole point
                // of carrying the bits here is that the two screens stop disagreeing.
                trailing = bits?.let {
                    {
                        Text(
                            pluralStringResource(R.plurals.itemedit_entropy_bits, it, it),
                            style = LkText.monoCaption,
                            color = c.textFaint,
                        )
                    }
                },
            )
        }
        if (onGenerate != null) {
            // Undo first, so it sits between the meter and the control that armed it, and only
            // while there is something to undo. This is what actually makes regenerate safe —
            // geometry can only make a mis-tap unlikely, and this makes it recoverable.
            if (canUndo && onUndoGenerate != null) {
                LkGlyphButton(
                    icon = LkIcons.Undo,
                    label = stringResource(R.string.itemedit_undo_generate_a11y),
                    onClick = onUndoGenerate,
                    size = 40.dp,
                    iconSize = 24.dp,
                    shape = LkShape.sm,
                    tint = c.textSecondary,
                    container = Color.Transparent,
                )
            }
            // The same half-turn the Generator screen's Regenerate button plays. Same glyph, same
            // action, so it has to give the same feedback — a user who has seen one spin reads a
            // dead one here as a button that did not register the tap.
            var generateCount by remember { mutableIntStateOf(0) }
            val spin by animateFloatAsState(
                targetValue = lkRegenerateSpinDegrees(generateCount),
                animationSpec = lkTween(LkMotion.Screen, easing = LkMotion.Emphasized),
                label = "generate-spin",
            )
            if (password.isEmpty()) {
                // Empty field: the control says what it does, in words.
                //
                // It was the same bare die as below, alone in the row with the meter absent — a
                // 48dp glyph floating in ~100px of whitespace, unlabelled, on the one screen where
                // a new user is most likely to want a generated password (F-16, 2026-09-15 visual
                // pass). The glyph is legible *as a repeat* of something you just watched happen;
                // it is not legible as an offer.
                //
                // `LkInlineAddButton` rather than a secondary band: it is the form's own weight for
                // "make a new thing here" (Add website, Add custom field), it carries the 48dp
                // floor, and it cannot be mistaken for the primary Save the way a full-width filled
                // bar was. Still right-aligned, so the control does not jump sideways the moment a
                // character lands and the meter takes the row.
                //
                // Generating into an empty field is not destructive — `generatePassword` arms no
                // undo when there is nothing to lose — so the louder treatment applies exactly
                // where the quiet one was argued for and nowhere else.
                LkInlineAddButton(
                    text = stringResource(R.string.itemedit_generate_password_a11y),
                    icon = LkIcons.Dice,
                    onClick = {
                        generateCount++
                        onGenerate()
                    },
                )
            } else {
                LkGlyphButton(
                    // Dice, not Refresh — a die says the value is being *rolled*, where Refresh says
                    // "load this again", which is what a user reasonably fears when the field already
                    // holds a password they typed. Matches the Generator screen's own button.
                    icon = LkIcons.Dice,
                    label = stringResource(R.string.itemedit_generate_password_a11y),
                    onClick = {
                        generateCount++
                        onGenerate()
                    },
                    size = 40.dp,
                    // Quieter than its former neighbours, never louder: a destructive trigger drawn
                    // loud is an invitation. It was unconditional jade inside the well, which made it
                    // indistinguishable from Copy at exactly the moment both were lit.
                    iconSize = 24.dp,
                    shape = LkShape.sm,
                    tint = c.textSecondary,
                    container = Color.Transparent,
                    modifier = Modifier.rotate(spin),
                )
            }
        }
    }
}

/**
 * How long the Saved screen holds before it moves on by itself.
 *
 * Long enough for the check to finish drawing (480ms after a 200ms delay) and be read; short enough
 * that it is not a step. Set this to `Long.MAX_VALUE` and the screen becomes button-only, which is
 * the one-line change if the auto-advance ever proves wrong.
 */
private const val SAVED_DWELL_MILLIS = 1_400L

/**
 * The air [LkScreenHeader] puts above and below the title that the comp's title does not have.
 *
 * The comp's screens carry no back control. [LkScreenHeader] does, and a 40dp glyph inside a 48dp
 * touch target makes the header row 48dp tall with the title's 23dp line box centred in it — so the
 * row extends 12.5dp past the title at each end. Every measurement below is stated against the
 * *title*, the way the comp states them, and this is what has to come off each end to get there.
 */
private val HeaderTitleInset = (48.dp - 23.dp) / 2

/**
 * Top padding of the item form, chosen so the *title* lands where the comp puts it.
 *
 * `Item Flows.dc.html` pads its screens `90px 26px 32px` and overlays a 44px status bar, so the
 * title's own box begins 46px below the status bar — and the app is already inset by `systemBars`
 * at the root ([com.localkeep.app.MainActivity]), so 46dp is measured from the same edge. Padding
 * the header *row* by 46 would land the title at ~58dp instead; see [HeaderTitleInset].
 */
private val ItemFormTop = 46.dp - HeaderTitleInset

/**
 * The gap between the header row and the first field block.
 *
 * The comp puts 16px between the title and the first label. The header row already contributes
 * [HeaderTitleInset] of its own below the title, so the spacer that follows it is the remainder —
 * write 16 here and the visible gap is 28.5dp, which is what the form looked like when the
 * principal called it "not exactly the same" on 2026-08-19.
 */
private val ItemFormHeaderGap = 16.dp - HeaderTitleInset

/** Quiet a typing burst must reach before the password is re-scored. */
private const val STRENGTH_DEBOUNCE_MILLIS = 120L

/**
 * The websites a login belongs to, one row each.
 *
 * This was a single multi-line well whose label had to teach its own convention — "Websites (one
 * per line)". A control that explains how to operate itself in its label is the wrong control: the
 * model stores a list ([com.localkeep.core.model.Login.uris]), the read view already renders one
 * row per site, and only the form flattened it to text. There was no way to remove one entry
 * without editing around it, and no per-row anything to hang validation or a match strategy off
 * later.
 *
 * Empty means the add action alone. An empty well with a remove button beside it invites the user
 * to work out what an empty website is for; a button that says what it does does not.
 */
@Composable
private fun WebsiteEditor(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
) {
    val c = LkTheme.colors
    // "Add website" appends an empty row, and until this existed it appended it *unfocused*: the
    // well appeared, the keyboard did not, and the caret stayed wherever it had been. Every user
    // had to add the row and then tap the thing they had just asked for. The folder picker at
    // :669 already focuses its new field on appearing; this is the same gesture and had none.
    //
    // Keyed on the row count rather than fired from the click handler, because the row the caret
    // belongs in does not exist yet at the moment of the tap — it is composed on the pass that
    // follows, which is also when the requester is attached and `requestFocus` is legal.
    var focusNewRow by remember { mutableStateOf(false) }
    val newRowFocus = remember { FocusRequester() }
    LaunchedEffect(state.uris.size) {
        if (focusNewRow && state.uris.isNotEmpty()) {
            newRowFocus.requestFocus()
            focusNewRow = false
        }
    }
    // The row a just-added "Add website" produced, for one arrival. Same vocabulary as a new
    // folder row and a landed attachment — see `lkArrival`.
    var arrivedUriIndex by remember { mutableIntStateOf(-1) }
    var knownUriCount by remember { mutableIntStateOf(state.uris.size) }
    LaunchedEffect(state.uris.size) {
        arrivedUriIndex = if (state.uris.size > knownUriCount) state.uris.lastIndex else -1
        knownUriCount = state.uris.size
    }
    // Saveable, like every other destructive confirmation in this file: a dialog that disappears on
    // rotation reads as one that was answered.
    var pendingRemoval by rememberSaveable { mutableIntStateOf(-1) }
    if (pendingRemoval in state.uris.indices) {
        val index = pendingRemoval
        val siteName = state.uris[index].ifBlank {
            stringResource(R.string.itemedit_login_website_fallback_name, index + 1)
        }
        LkDialog(
            onDismiss = { pendingRemoval = -1 },
            title = stringResource(R.string.itemedit_login_website_remove_title, siteName),
            // Names the consequence rather than the mechanism, and names *when* it happens: nothing
            // is lost until Save, and what changes afterwards is autofill, which is the only way
            // most users meet these entries at all.
            body = stringResource(R.string.itemedit_login_website_remove_body),
            icon = LkIcons.Trash,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.itemedit_login_website_remove_confirm),
                onClick = {
                    viewModel.removeUri(index)
                    pendingRemoval = -1
                },
                emphasis = LkActionEmphasis.Destructive,
            ),
        )
    }
    // Its own Column, so this section is *one* child of the form — see the note in
    // CustomFieldsSection for what emitting several children into the parent costs.
    Column(Modifier.fillMaxWidth()) {
        // The same key the read view heads this section with: the editor and the detail screen
        // naming the same section differently is how two words for one thing get into an app.
        // `LkFieldLabel`, not `LkSectionLabel`: the comp gives "Websites" the same 10sp/.12em
        // micro-label it gives "Username" and "Password" — it labels one field group, and the
        // louder 11sp/.13em section voice belongs to headers over unrelated groups (Two-factor).
        LkFieldLabel(stringResource(R.string.itemedit_detail_websites_title))
        Spacer(Modifier.height(LkSpace.sm))
        if (state.uris.isNotEmpty()) {
            // 4dp between rows, not 8. The comp sets `gap:4px` on the website list and on the file
            // list — a run of rows that belong to one list reads as a stack, and 8dp starts to read
            // as separate blocks.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
                state.uris.forEachIndexed { index, uri ->
                    // Named by what it holds, falling back to the position, so a login with four
                    // sites does not present four identical "Remove website" buttons. The same
                    // rule the custom-field rows follow.
                    val siteName = uri.ifBlank {
                        stringResource(R.string.itemedit_login_website_fallback_name, index + 1)
                    }
                    // One well per site, with the globe and the trash *inside* it — the comp's
                    // row is a single 52dp object (`height:52px;padding:0 8px 0 16px`), not a
                    // field with a button parked beside it. The old shape cost the field about
                    // a fifth of its width to a control that is used once per site at most, and
                    // it put a destructive tap one thumb-width from the text you are editing.
                    LkTextField(
                        value = uri,
                        onValueChange = { viewModel.onUriChanged(index, it) },
                        label = siteName,
                        placeholder = stringResource(R.string.itemedit_login_website_placeholder),
                        // Only the last row carries the requester: it is the one "Add website"
                        // just created, and a requester attached to several nodes at once is
                        // ambiguous about which one it means.
                        modifier = Modifier
                            .fillMaxWidth()
                            .lkArrival(
                                arrived = index == arrivedUriIndex,
                                shape = LkShape.xl,
                                key = index,
                            )
                            .then(
                                if (index == state.uris.lastIndex) {
                                    Modifier.focusRequester(newRowFocus)
                                } else {
                                    Modifier
                                }
                            ),
                        leadingIcon = LkIcons.Globe,
                        // Monospaced, like the comp: a hostname is an identifier being checked
                        // character by character, and the ambiguous pairs in a proportional face
                        // (rn/m, l/I) are exactly what a typo'd domain hides behind.
                        textStyle = LkText.monoField,
                        // 52dp, the comp's row height: the 48dp touch floor the trash glyph
                        // carries, plus 2dp of air each side. Not the 12dp default — that would
                        // make the row 72dp and it would stop reading as a list.
                        minHeight = 52.dp,
                        verticalPadding = 2.dp,
                        trailing = {
                            LkGlyphButton(
                                icon = LkIcons.Trash,
                                label = stringResource(R.string.itemedit_login_website_remove_a11y, siteName),
                                size = 40.dp,
                                iconSize = 30.dp,
                                container = Color.Transparent,
                                tint = c.textFaint,
                                // Raises the dialog rather than removing the row. The comp's
                                // reason is the right one: *"Removing raises the dialog rather
                                // than animating the row out — a destructive action should not
                                // look easy."*
                                onClick = { pendingRemoval = index },
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            // The one field in the app that exists to hold URLs was opening on
                            // a plain alphabetic layout with autocorrect on — "/" and "." two
                            // taps away each, and the IME free to "correct" a hostname into a
                            // word.
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(LkSpace.sm))
        }
        LkInlineAddButton(
            text = stringResource(R.string.itemedit_login_website_add_button),
            onClick = {
                viewModel.addUri()
                focusNewRow = true
            },
        )
    }
}

/**
 * A login's embedded second factor, in the edit form.
 *
 * Read-only apart from removal, deliberately: the seed arrives from a QR code or an `otpauth://`
 * URI, and a free-text field over base32 invites a typo that silently produces wrong codes forever.
 * Editing one means scanning it again, which is the flow that already exists for TOTP items.
 */
@Composable
private fun LoginTotpSection(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
) {
    val c = LkTheme.colors
    // Saveable, like every other destructive confirmation: a dialog that disappears on rotation
    // reads as one that was answered, and the answer it looks like is “yes, remove it”.
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    // Tighter than the default gap: this block holds a heading, its caption and one button, not a
    // run of wells that each need air around their ring.
    LkFieldGroup(spacing = LkSpace.sm) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            Icon(LkIcons.Clock, contentDescription = null, tint = c.jade, modifier = Modifier.size(15.dp))
            Text(stringResource(R.string.itemedit_login_totp_heading), style = LkText.buttonSmall, color = c.textSecondary)
            LkBadge(
                stringResource(R.string.itemedit_login_totp_badge),
                accent = c.jade,
                container = c.jade.copy(alpha = 0.12f),
            )
        }
        Text(
            listOf(state.totpIssuer, state.totpAccount)
                .filter(String::isNotBlank)
                .joinToString(" · ")
                .ifBlank { stringResource(R.string.itemedit_login_totp_caption_default, state.totpPeriodSec) },
            style = LkText.caption,
            color = c.textFaint,
        )
        LkTintButton(
            text = stringResource(R.string.itemedit_login_totp_remove_button),
            accent = c.coral,
            icon = LkIcons.Trash,
            onClick = { confirmRemove = true },
        )
    }

    if (confirmRemove) {
        LkDialog(
            onDismiss = { confirmRemove = false },
            title = stringResource(R.string.itemedit_login_totp_remove_dialog_title),
            body = stringResource(R.string.itemedit_login_totp_remove_dialog_body),
            icon = LkIcons.Warning,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.itemedit_login_totp_remove_confirm),
                onClick = {
                    confirmRemove = false
                    viewModel.removeLoginTotp()
                },
                emphasis = LkActionEmphasis.Destructive,
            ),
        )
    }
}

/**
 * The capture action, the preview of what it produced, and the one way it can fail that is worth
 * showing on this screen.
 *
 * There is no text input here at all, which is what makes this block unlike every other type's. A
 * coordinate cannot be typed — the whole item is "where I am now", and a hand-entered pair of
 * numbers would be a different feature with a different promise. So the form is a button, and Save
 * stays disabled until the button has produced something.
 *
 * The permission is requested here rather than at launch or on opening the add sheet: Android's own
 * guidance and the Play requirement are both that the prompt follows an action whose purpose is
 * evident, and "Save current location" is that action. The rationale dialog goes first when the
 * grant is not already held, for the same reason the QR scanner's does.
 */
@Composable
private fun CoordinateFields(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    capture: LocationCapture,
) {
    val c = LkTheme.colors
    val context = LocalContext.current
    val hasFix = state.coordinateFix != null

    // Wake the receiver while this section is on screen, so the tap starts against a tracking
    // receiver rather than a cold one. Time-to-first-fix dominates a capture and no amount of work
    // inside the app shortens it — the only lever is to have paid it already.
    //
    // Scoped as tightly as the honest version of this can be. It runs only here, in a composable
    // that exists only for a coordinate item, and only when precise location is *already* granted —
    // so it never prompts, and the system's location indicator appears only while the user is
    // looking at the one screen whose entire purpose is a location. It is not a background service,
    // it is capped in the ViewModel, and it produces nothing: every fix it receives is discarded
    // unread and the rule that refuses a pre-tap position is untouched.
    val warmUpAllowed = !hasFix && !state.coordinateCapturing && !state.readOnly &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    DisposableEffect(warmUpAllowed) {
        if (warmUpAllowed) viewModel.warmLocationReceiver()
        // Fires on leaving the section, on the capture starting, and on the fix arriving — the
        // three ways this stops being speculative. A warm-up that outlived the screen would be the
        // receiver running behind a screen nobody is looking at.
        onDispose { viewModel.stopWarmingLocationReceiver() }
    }

    // No Spacer: [LkSectionLabel] owns the gap below it (LkSectionLabelGap, which is this same sm).
    LkSectionLabel(stringResource(R.string.itemedit_coordinate_section_label))
    // The product name takes the wordmark's jade "Keep" here too — reported 2026-08-20, and the
    // one place in this flow the name is set in prose rather than by [LkWordmarkText].
    Text(
        lkBrandedText(stringResource(R.string.itemedit_coordinate_explainer)),
        style = LkText.bodyQuiet,
        color = c.textFaint,
    )
    Spacer(Modifier.height(LkSpace.md))

    // The field itself: the cloud's spread is the reading's accuracy, the pin's colour its band,
    // and the corner figure the same number the live line under it says in words — see
    // LocationCaptureField.kt, which owns all of it.
    LocationRadarField(
        phase = radarPhaseFor(
            capturing = state.coordinateCapturing,
            hasFix = hasFix,
            convergence = state.coordinateConvergence,
        ),
        band = state.coordinateFix?.displayAccuracyBand(),
        accuracyMeters = state.coordinateFix?.accuracyMeters,
        modifier = Modifier.lkTag("coordinate-field"),
    )

    if (state.coordinateCapturing) {
        Spacer(Modifier.height(LkSpace.md))
        LocationCaptureStatus(
            startedAtElapsedMillis = state.coordinateCaptureStartedElapsedMillis,
            timeoutMillis = state.coordinateCaptureTimeoutMillis,
            fix = state.coordinateFix,
            convergence = state.coordinateConvergence,
        )
    }

    capture.denial?.let { denial ->
        Spacer(Modifier.height(LkSpace.md))
        LkInlineError(
            message = denial.message,
            modifier = Modifier.announce(denial.message),
            actionLabel = denial.settingsActionLabel,
            onAction = denial.settingsActionLabel?.let { { context.openAppSettings() } },
        )
    }

    state.coordinateFailure?.let { failure ->
        Spacer(Modifier.height(LkSpace.md))
        val message = stringResource(failure.messageRes())
        LkInlineError(
            message = message,
            modifier = Modifier.announce(message).lkTag("coordinate-failure"),
            // The two permission cases and a switched-off provider can all be fixed from a
            // settings page — see isFixableInSettings; the rest are "try again", and offering a
            // settings trip for a timeout indoors would send the user to a screen where there is
            // nothing to change.
            actionLabel = if (failure.isFixableInSettings()) {
                stringResource(failure.settingsActionLabelRes())
            } else {
                null
            },
            onAction = if (failure.isFixableInSettings()) {
                { context.openSettingsFor(failure) }
            } else {
                null
            },
        )
    }

    state.coordinateFix?.let { fix ->
        Spacer(Modifier.height(LkSpace.md))
        // Shown in clear only when this session took it. A fix loaded from the vault is stored data
        // and is gated exactly as it is on the detail screen — the editor must not be a way around
        // the reveal prompt, which is the mistake the TOTP preview above already had to fix once.
        val revealed = state.coordinateJustCaptured || COORDINATE_FIELD in state.revealedFields
        val value = fix.displayValue()
        val label = stringResource(R.string.itemedit_coordinate_value_label)
        val haptics = rememberHaptics()
        val copiedAnnouncement = stringResource(R.string.itemedit_coordinate_copied_a11y)
        var copied by remember(value) { mutableStateOf(false) }
        val holdToCopy = stringResource(R.string.itemedit_coordinate_hold_to_copy)
        // Hold, not tap, and only while the value is already on screen.
        //
        // The pair is [ALWAYS_REAUTH_FIELDS] material — a precise position says where its owner was
        // and cannot be changed after someone sees it — so the clipboard is behind exactly the gate
        // the screen is behind: a fix loaded from the vault has to go through Show, which re-auths,
        // before this does anything. A press-and-hold rather than a tap because the card is a
        // readout and the copy is silent: a stray tap that put a location on the clipboard would be
        // an invisible act with a real consequence.
        val copy = {
            ClipboardHelper.copy(context, label, value)
            haptics.play(Haptic.COPY)
            copied = true
        }
        // The arrival, which this card did not have at all.
        //
        // `Location Capture.dc.html` gives the coordinates card two animations — `lcRise` 420 ms as
        // it appears, and `lcBloom` 900 ms behind it on a 120 ms delay — and neither was
        // implemented. Reported 2026-08-20, and correctly: "there's a transition and nice animation
        // once coordinates arrive <-- we don't even have that". The card simply popped into
        // existence at the end of a twenty-second wait, which is the one moment in this flow that
        // has earned a payoff.
        //
        // The bloom fires once per *finished* reading, and that is the whole of 5.6.
        //
        // The token used to be the value's own hash. `onInterim` rewrites `coordinateFix` on every
        // better fix while the capture runs, so the card bloomed at the receiver's irregular cadence
        // — several times during the wait, at no moment that meant anything — and then, because
        // `cancelLocationCapture` leaves the value alone, did not bloom at the commit at all. The
        // payoff was firing everywhere except where it belonged.
        //
        // A counter incremented on the capture *ending* with a reading this session produced gives
        // exactly one bloom per capture, at the moment the wait is over, and a second one if the
        // user takes a new reading. Opening a stored item does not bloom: `coordinateJustCaptured`
        // is false there, and a reading you saved last week is not something that just happened.
        //
        // `lkRise()` below stays: the card enters composition at the first interim, and that really
        // is when it arrives. Both modifiers are reduced-motion aware, and nothing is carried by
        // motion alone — the caption says the reading is there in words.
        var arrivalToken by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(state.coordinateCapturing, hasFix, state.coordinateJustCaptured) {
            if (!state.coordinateCapturing && hasFix && state.coordinateJustCaptured) arrivalToken++
        }
        LkLiftedCard(
            Modifier
                .lkTag("coordinate-preview")
                .lkRise()
                .lkBloom(arrivalToken)
                .then(
                    if (revealed) {
                        Modifier
                            .pointerInput(value) { detectTapGestures(onLongPress = { copy() }) }
                            .semantics {
                                // The same action, reachable without the gesture: a long press is
                                // not something a switch or screen-reader user can perform, and
                                // this is the only way to the clipboard on this screen.
                                onLongClick(label = holdToCopy) { copy(); true }
                            }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
            ) {
                LkFieldLabel(label, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                // The affordance, in the corner the principal asked for it. Only while there is
                // something to copy, and it becomes the confirmation once the copy has happened —
                // the clipboard is invisible, so without this the hold has no result at all.
                if (revealed) {
                    Text(
                        if (copied) stringResource(R.string.itemedit_coordinate_copied) else holdToCopy,
                        style = LkText.caption,
                        color = if (copied) c.jade else c.textFaint,
                        modifier = if (copied) Modifier.announce(copiedAnnouncement) else Modifier,
                    )
                }
            }
            Spacer(Modifier.height(LkSpace.sm))
            Text(
                if (revealed) value else COORDINATE_MASK,
                style = if (revealed) LkText.monoField else LkText.masked,
                color = if (revealed) c.jadeLit else c.textMuted,
                modifier = Modifier.secretFieldSemantics(label, revealed, value),
            )
            Spacer(Modifier.height(LkSpace.sm))
            Text(
                coordinateCapturedCaption(fix),
                style = LkText.caption,
                color = c.textFaint,
            )
            if (!revealed) {
                Spacer(Modifier.height(LkSpace.sm))
                LkTextButton(
                    text = stringResource(R.string.itemedit_coordinate_reveal),
                    onClick = { viewModel.toggleFieldRevealed(COORDINATE_FIELD) },
                )
            }
        }
    }
}

/**
 * The permission half of a location capture, hoisted so the form and the action column can share it.
 *
 * The capture button lives at the bottom of the screen with Save — `Location Capture.dc.html` puts
 * it there, and it is the same argument the Login scanner's move made: the primary action of a
 * screen belongs where every other primary action is, not floating in the middle of the form. The
 * *failure* of that action still belongs beside the field, so the two halves are split and this is
 * what joins them.
 */
private class LocationCapture(
    val denial: PermissionDenial?,
    val start: () -> Unit,
)

/**
 * Builds that holder, and emits the disclosure dialog it needs.
 *
 * Both — it is a composable that returns a value *and* draws one thing, which is unusual and is the
 * point: the dialog is a window of its own and has no position in any layout, so keeping it beside
 * the launcher that it arms is better than passing another flag down two call paths.
 */
@Composable
private fun rememberLocationCapture(viewModel: ItemEditViewModel): LocationCapture {
    val context = LocalContext.current
    // Saveable, like the camera disclosure: a rationale lost to a rotation puts the user back in
    // front of a button that appears to have done nothing.
    var showLocationDisclosure by rememberSaveable { mutableStateOf(false) }
    var locationDenial by rememberSaveable(stateSaver = PermissionDenialSaver) {
        mutableStateOf<PermissionDenial?>(null)
    }
    // Which kind of capture the user asked for before the system dialog interrupted them. Saveable
    // for the same reason the dialog flag is: the permission sheet can outlive a rotation, and
    // coming back from it with the mode forgotten would silently start the wrong capture — a
    // five-minute wait the user did not ask for, or a short one instead of the long one they did.
    val denialSentence = stringResource(R.string.itemedit_coordinate_denial_sentence)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        when {
            fine -> {
                locationDenial = null
                viewModel.dismissLocationFailure()
                viewModel.captureCurrentLocation()
            }
            // Android 12+ offers "Approximate" as a first-class answer to a request for both, and
            // the user experienced choosing it as saying yes. It gets its own message rather than
            // the denial notice, because "you denied it" would be false and because the remedy is
            // different: they have to change *which* kind of access, not whether there is any.
            coarse -> viewModel.onLocationPermissionRefused(approximateOnly = true)
            else -> locationDenial = PermissionDenial.after(
                context = context,
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                featureSentence = denialSentence,
            )
        }
    }

    if (showLocationDisclosure) {
        LkDialog(
            onDismiss = { showLocationDisclosure = false },
            title = stringResource(R.string.itemedit_coordinate_dialog_title),
            body = stringResource(R.string.itemedit_coordinate_dialog_body),
            icon = LkIcons.Pin,
            confirm = LkDialogAction(
                label = stringResource(R.string.itemedit_coordinate_dialog_continue),
                onClick = {
                    showLocationDisclosure = false
                    // Both, in one request. Android 12+ only offers a precise grant when the pair is
                    // asked for together; requesting fine alone gets silently downgraded to the
                    // approximate dialog, which this feature then has to refuse.
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                },
            ),
            dismissLabel = stringResource(R.string.itemedit_dismiss_not_now),
        )
    }

    return LocationCapture(
        denial = locationDenial,
        start = {
            viewModel.dismissLocationFailure()
            locationDenial = null
            // Checked at the moment of the tap, never cached: the grant can be revoked from
            // Settings while this screen is in the background.
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasFine) {
                viewModel.captureCurrentLocation()
            } else {
                showLocationDisclosure = true
            }
        },
    )
}

/**
 * Which of a coordinate item's two pinned actions is the jade one.
 *
 * The names are historical: this decided the literal stack order until 2026-09-15, when Save was
 * pinned to one slot (F-25) and only the emphasis was left to swap. `CAPTURE_FIRST` now means the
 * capture is the jade primary and Save is the hollow disabled plate above it; `SAVE_FIRST` means
 * Save is jade and the capture is the washed retry below.
 */
/** One action a Location item's pinned slot can offer. */
internal enum class LocationAction { TAKE_READING, CANCEL, KEEP_READING, SAVE, RETAKE }

/** What the slot draws: [main] in the slot that never moves, [secondary] quietly above it. */
internal data class LocationActionSlot(val main: LocationAction, val secondary: LocationAction? = null)

/**
 * The Location item's actions, as one slot whose label follows the capture.
 *
 * | State                                   | main           | secondary |
 * |-----------------------------------------|----------------|-----------|
 * | no reading                              | Find my location | —       |
 * | searching, nothing worth keeping yet    | Cancel           | —       |
 * | searching, a reading worth keeping      | Keep location    | Cancel  |
 * | reading kept                            | Save             | Find again |
 *
 * Three earlier fixes each solved one complaint and set up the next. Save and "Keep the rough
 * reading" were both live (2026-09-03, *"how come we have 2 saves?"*); Save was then hidden while
 * capturing and its slot moved three times in one flow (F-25, 2026-09-15); it was then kept on
 * screen but disabled, and a hollow outlined Save above "Take a reading" read as a button that
 * could be pressed (2026-10-09, *"why is Save even visible before Take a reading?"*).
 *
 * This answers all three: the slot never moves, it never holds two commit-shaped actions, and no
 * state draws a disabled button — a control that cannot act is not drawn at all. `hasFix` alone
 * cannot pick Save: interim readings land in the form while a search runs, so it is true for most
 * of a capture, which is the exact state the 2026-09-03 report caught.
 *
 * Pure so [LocationActionSlotTest] can hold it: this module hosts no Compose UI tests.
 */
internal fun locationActions(capturing: Boolean, offerable: Boolean, hasFix: Boolean): LocationActionSlot =
    when {
        capturing && offerable -> LocationActionSlot(LocationAction.KEEP_READING, LocationAction.CANCEL)
        capturing -> LocationActionSlot(LocationAction.CANCEL)
        hasFix -> LocationActionSlot(LocationAction.SAVE, LocationAction.RETAKE)
        else -> LocationActionSlot(LocationAction.TAKE_READING)
    }

/**
 * The Location item's pinned actions: one slot whose label follows the capture, and at most one
 * quiet action above it. [locationActions] decides which; this only draws it.
 *
 * Save lives here rather than in the generic pinned Save, because on this screen Save is one of the
 * slot's states and not a separate control — the reading is the whole item, so until one is kept
 * there is nothing for Save to write and nothing is drawn for it.
 */
@Composable
private fun CoordinateActionSlot(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    capture: LocationCapture,
    saveText: String,
    saveModifier: Modifier,
    onSave: () -> Unit,
) {
    // Whether the running search has a reading *worth offering*, not merely any reading. The offer
    // used to appear the instant the first epoch landed, on something nobody would keep — reported
    // 2026-08-20: "it never actually says that it's not ready and the button immediately appears".
    // See [readingIsOfferable] for the three thresholds, all lifted from the running FixRequest.
    //
    // One scheduled wake, not a poll: the tracking floor is a single edge. The 200 ms poll in the
    // form above is for the elapsed arc, which needs a continuous value.
    val startedAt = state.coordinateCaptureStartedElapsedMillis
    var trackingFloorPassed by remember(startedAt) { mutableStateOf(false) }
    LaunchedEffect(startedAt) {
        if (startedAt == null) return@LaunchedEffect
        val remaining = MIN_TRACKING_MILLIS - (android.os.SystemClock.elapsedRealtime() - startedAt)
        if (remaining > 0) kotlinx.coroutines.delay(remaining)
        trackingFloorPassed = true
    }
    val offerable = state.coordinateCapturing &&
        readingIsOfferable(state.coordinateFix, state.coordinateConvergence, trackingFloorPassed)
    val slot = locationActions(
        capturing = state.coordinateCapturing,
        offerable = offerable,
        hasFix = state.coordinateFix != null,
    )

    // "Cancel" *discards* — the form goes back to whatever it held before the tap — and "Keep this
    // reading" keeps the reading shown. Until 2026-09-02 every label called the keeping function, so
    // a Cancel pressed over a rough interim quietly kept it.
    @Composable
    fun Action(action: LocationAction, main: Boolean) {
        when (action) {
            LocationAction.TAKE_READING -> LkPrimaryButton(
                text = stringResource(R.string.itemedit_coordinate_capture_button),
                icon = LkIcons.Pin,
                onClick = capture.start,
                enabled = !state.readOnly,
                modifier = Modifier.lkTag("coordinate-capture"),
            )
            // The only control while nothing usable has arrived, so it takes the slot — washed,
            // because it ends the task without a result.
            //
            // No glyph. It used to wear the location pin, which means "find my location" on the two
            // sibling buttons - a pin on the button that stops the search. The search's own
            // progress is drawn by the status panel above, so the button has nothing to animate.
            LocationAction.CANCEL -> LkSecondaryButton(
                text = stringResource(R.string.itemedit_coordinate_stop_capture),
                onClick = viewModel::abandonLocationCapture,
                modifier = Modifier.lkTag(if (main) "coordinate-stop" else "coordinate-cancel"),
            )
            // One keep label for every band: the reading's quality belongs to the status line above,
            // which names it accurately across three bands. Calling a reading the panel has just
            // described as "street level" a "rough reading" here was the 2026-09-03 contradiction.
            LocationAction.KEEP_READING -> LkPrimaryButton(
                text = stringResource(R.string.itemedit_coordinate_cancel_capture),
                icon = LkIcons.Pin,
                onClick = viewModel::cancelLocationCapture,
                modifier = Modifier.lkTag("coordinate-stop"),
            )
            LocationAction.SAVE -> LkPrimaryButton(
                text = saveText,
                onClick = onSave,
                enabled = !state.isSaving && !state.readOnly,
                modifier = saveModifier,
            )
            LocationAction.RETAKE -> LkSecondaryButton(
                text = stringResource(R.string.itemedit_coordinate_replace_button),
                icon = LkIcons.Pin,
                onClick = capture.start,
                enabled = !state.readOnly && !state.isSaving,
                modifier = Modifier.lkTag("coordinate-capture"),
            )
        }
    }

    // The secondary goes *above* the main action, so the main one holds the bottom slot in every
    // state: Find my location, Cancel, Keep location and Save are all one thumb position. Drawn
    // beneath it instead, the arrival of a secondary pushed the main action up a row (measured on
    // emulator-5554, 2026-10-09: y=2208 for Take a reading, y=2050 for Keep and Save), which is the
    // walking button F-25 removed.
    slot.secondary?.let { Action(it, main = false) }
    Action(slot.main, main = true)
}

/**
 * A fixed-width mask for a gated note body - deliberately not one bullet per character.
 *
 * `SecretReadField` caps its own bullets at 16 for the same reason: a run of bullets as long as the
 * secret tells a shoulder-surfer how long the secret is, and on a note that is a paragraph count.
 */
private const val NOTE_MASK = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"

/** Bullets standing in for a masked position — a fixed width, so the mask reveals no digit count. */
internal const val COORDINATE_MASK = "•••••••••••••••••"

@Composable
private fun NoteFields(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
) {
    val c = LkTheme.colors
    // The body is the point of a secure note (`Item Flows.dc.html`), so it may grow to a page - but
    // it starts small (see [NOTE_MIN_HEIGHT]) and grows with what is typed.
    //
    // The ceiling is expressed as a fraction of the device rather than the comp's `flex: 1`, because this form is
    // one scrolling column shared by six item types and a genuinely flexible child inside a scroll
    // container has no height to fill. 42% of the screen is a page on a tall phone and still a page
    // on a short one; the clamp keeps a very small or very large device inside the range this was
    // designed against.
    //
    // Still bounded, and the bound is the important half. Unbounded, the well grew with its
    // content: a pasted 50 KB note rendered a field thousands of dp tall and pushed Save off the
    // end of a form the user then had to scroll a long way to reach. The comp says the same thing
    // in its own motion spec — *"A field that grows as you type moves the Save button, which is the
    // one thing that must not move."*
    val noteMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
        .coerceIn(NOTE_MIN_HEIGHT, NOTE_MAX_HEIGHT)
    // The editor half of the note gate. Gating the read view alone would have been worse than
    // gating neither: Edit sits one tap from the body on that screen, so the secret would still be
    // two taps away while the badge and the tier-G caption looked satisfied. `RevealAuthEffect`'s
    // own KDoc is the rule - "a gate that applies on one screen and not the other is no gate at
    // all".
    //
    // A locked well rather than a masked editable field: masking live text in a box the user is
    // about to type into is a different and worse problem - selection, IME and the caret would all
    // operate on characters nobody can see. Reveal first, then edit, which is what the coordinate
    // field does four hundred lines up; this borrows its shape and its string.
    //
    // Not on a new note (O1-03, 2026-10-02): its body is what the user is typing in, so masking it
    // the moment Reprompt is ticked disclosed nothing and took their text away from them — the same
    // argument that leaves a phrase being captured ungated (`phraseGateRequired`).
    val noteGated = state.reprompt && !state.isNew && NOTE_BODY_FIELD !in state.revealedFields
    if (noteGated && state.noteBody.isNotEmpty()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .lkSurface(LkShape.card, c.field, c.ring)
                    .padding(LkSpace.md),
            ) {
                LkFieldLabel(stringResource(R.string.itemedit_note_label))
                Spacer(Modifier.height(LkSpace.sm))
                Text(
                    NOTE_MASK,
                    style = LkText.masked,
                    color = c.textMuted,
                    modifier = Modifier.secretFieldSemantics(
                        stringResource(R.string.itemedit_note_label),
                        false,
                        state.noteBody,
                    ),
                )
                Spacer(Modifier.height(LkSpace.sm))
                LkTextButton(
                    text = stringResource(R.string.itemedit_coordinate_reveal),
                    onClick = { viewModel.toggleFieldRevealed(NOTE_BODY_FIELD) },
                )
            }
            NoteAssurance(written = true)
        }
        return
    }
    // No autofocus, deliberately - no editor in this app focuses a field on entry. An empty note used
    // to raise the keyboard the moment the form opened (2026-09-20, sweep 4B-7, so that the detail
    // screen's "Tap to write one" delivered a write). That put the keyboard over half the form on
    // every entry, including the ones that came to change the folder or tags; the principal chose
    // the tap over the surprise (2026-10-14). The empty well now starts small enough to leave the
    // whole form on screen, and the tap that was promised is the one that opens the keyboard.
    // The platform selection menu loses Copy and Cut here (S5-06, 2026-10-02). Its Copy put the body
    // on the clipboard unmarked and never cleared, past ClipboardHelper's sensitive flag and 30 s
    // wipe — and the body is where the secrets that fit no field end up, which is why the detail
    // screen has never offered a note Copy. Paste and Select all stay.
    GuardedSecretField {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        BoxedField(
            value = state.noteBody,
            onValueChanged = viewModel::onNoteBodyChanged,
            label = stringResource(R.string.itemedit_note_label),
            placeholder = stringResource(R.string.itemedit_note_placeholder),
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            // A secure note IS the secret (`ReauthFields.kt`), and until 2026-09-20 (sweep 2B-2) it
            // was typed on an ordinary prose keyboard: `inputType=0x28001` — TEXT | AUTO_CORRECT,
            // with neither NO_SUGGESTIONS nor NO_PERSONALIZED_LEARNING — so the IME rewrote what was
            // typed ("tempor" became "temporary") and learned the rest into a dictionary that
            // outlives the app. `KeyboardType.Password` is what turns both off here, exactly as it
            // does on the recovery-phrase words below; [LkFormField] derives `autoCorrectEnabled`
            // from it. Masking is unaffected — that is `visualTransformation`, and this well has
            // none, because you have to be able to read the note you are writing.
            keyboardType = KeyboardType.Password,
            // Grows from [NOTE_MIN_HEIGHT] with the text and stops at the cap, past which the text
            // scrolls inside the well - so an empty note leaves the rest of the form visible and a
            // long one still cannot push Save off the end.
            minHeight = NOTE_MIN_HEIGHT,
            maxHeight = noteMaxHeight,
            // The comp's one departure from the 16dp well corner: the note body is a `200px`
            // textarea at `border-radius:20px`, because a well this tall is a block, not a line.
            shape = LkShape.card,
            // On the label's row, opposite the label, which is where the comp draws it — not under
            // the well. Below the field it read as a caption *about* the note; beside the label it
            // reads as the label's own readout, and it stops a line of type sitting between the
            // field and the sentence that explains what protects it.
            //
            // Characters, not words: what matters is the field's stored size. No animation on the
            // number — it is a readout, not an event.
            labelTrailing = {
                // Code points, not `String.length`. Kotlin's length is UTF-16 code units, so a
                // single emoji is a surrogate pair and one 😀 in an empty note read "2 characters"
                // — a count the user can see is wrong about the one character on screen, on a field
                // whose counter exists to be trusted about size.
                //
                // Code points rather than grapheme clusters, deliberately: a flag or a
                // skin-toned emoji is still several code points, so this is closer rather than
                // exact. `BreakIterator` would be exact and would run over the whole body on every
                // keystroke of a field this repo has already sized at 50KB — the wrong trade for a
                // caption. Stated here rather than implied, so nobody reads the number as a
                // grapheme count later.
                val characters = state.noteBody.codePointCount(0, state.noteBody.length)
                Text(
                    pluralStringResource(
                        R.plurals.itemedit_note_character_count,
                        characters,
                        characters,
                    ),
                    style = LkText.monoCaption,
                    color = c.textFaint,
                )
            },
        )
        NoteAssurance(written = state.noteBody.isNotEmpty())
    }
    }
}

/**
 * One line about what protects the note, directly under the field it applies to.
 *
 * Placed rather than buried, because the moment it matters is the moment someone is deciding
 * whether to type a recovery phrase into this box — not later, in a settings page about encryption.
 *
 * **The comp's version of this sentence was wrong and is not shipped.** It read "Nothing here is
 * indexed or sent anywhere". A note body *is* indexed: `VaultRepository`'s search corpus yields
 * `Note.body` in full, so a note is findable by any substring of its text from the vault list. The
 * encryption and the no-network halves are true and stay; the indexing claim is replaced by the
 * fact it got wrong, which is also the more useful thing to know before typing a seed phrase in.
 * A security caption that overstates the protection is worse than no caption.
 *
 * **Only the searchability sentence ships now** (2026-10-15, the principal's decision): "Encrypted
 * with the same key as the rest of the vault, and never sent anywhere" was removed from the caption.
 * Both halves were true; the app-wide encryption and no-network claims live where they are made
 * once (onboarding, Settings), and the one thing specific to a note is what is left.
 *
 * Brightens from 0.55 to full when the field has content — the comp's cue that the sentence now
 * applies to something real. Opacity is state here, not decoration, so it survives reduced motion.
 */
@Composable
internal fun NoteAssurance(written: Boolean) {
    val c = LkTheme.colors
    val alpha by animateFloatAsState(
        targetValue = if (written) 1f else 0.55f,
        animationSpec = lkTween(LkMotion.Panel, easing = LkMotion.Settle),
        label = "note-assurance",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .lkSurface(LkShape.xl, c.jade.copy(alpha = 0.06f), c.jade.copy(alpha = 0.16f))
            .padding(horizontal = LkSpace.md, vertical = LkSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        // Search, not a shield: since 2026-10-15 the caption says only that search can find the
        // note's text, and a shield beside that would claim a protection the sentence no longer does.
        //
        // Centred on the first caption line, not hung from the top of the row: a 1dp top pad put the
        // glyph's centre 9dp down while the first line's centre moves with the font, so at 2.0 text
        // the glyph floated above a caption that wrapped over several lines (SCL-028). The same
        // one-line box [com.localkeep.core.ui.theme.LkInlineNote] gives its glyph.
        val firstLine = with(LocalDensity.current) { LkText.caption.lineHeight.toDp() }
        Box(Modifier.height(firstLine), contentAlignment = Alignment.Center) {
            Icon(
                LkIcons.Search,
                null,
                tint = c.jade,
                modifier = Modifier.size(16.dp).lkTag("note-assurance-icon"),
            )
        }
        Text(
            stringResource(R.string.itemedit_note_assurance),
            style = LkText.caption,
            color = c.textTertiary,
        )
    }
}

/**
 * The note well's height band. The ceiling is 42% of the screen clamped to [NOTE_MIN_HEIGHT]..
 * [NOTE_MAX_HEIGHT] (42% of a 480dp screen is 202; of a 1000dp screen, 420).
 *
 * The floor is five lines of `LkText.body` (14sp x 1.5 = 21sp each = 105) plus the well's 12dp
 * vertical padding twice (24), rounded up to 144dp so a placeholder that wraps to a second line
 * still fits. It was 200dp and a fixed height, which put half a phone screen of empty well above
 * the folder row and Save (2026-10-14).
 */
private val NOTE_MIN_HEIGHT = 144.dp
private val NOTE_MAX_HEIGHT = 420.dp

/** Grid cells: the comp draws 40px, but a tappable cell is held to the 48dp touch floor. */
private val PHRASE_SLOT_HEIGHT = 48.dp

/**
 * The Recovery-phrase capture screen (`Item Flows.dc.html`, "Recovery phrase"): a word-count row, a
 * numbered grid whose words check themselves against BIP-39 and their own checksum, the focused
 * slot's entry field with four-letter autocomplete, a verdict banner, and the optional 25th word.
 *
 * The title ("what it opens") is the shared field — asked at the end in [SaveSheet] for a new item,
 * drawn at the top of the form for an existing one — so it is not repeated here, exactly as
 * [NoteFields] does not draw its own title. Folder, tags and the reprompt toggle are the shared
 * [MoreSection] below.
 *
 * **Reveal is gated on an edit, ungated on a capture.** Entering a secret is not disclosing a stored
 * one, so a brand-new phrase uncovers on a tap while the user copies it onto steel. But this same
 * composable draws the `item/{id}/edit` route, where `load()` has put the *stored* words into the
 * grid, so there both the Reveal pill and the first slot tap raise the §5.2 prompt — see
 * [ItemEditUiState.phraseGateRequired]. The KDoc here used to claim the capture argument covered
 * both routes and that "the detail screen gates it instead"; it did not, and the editor was the one
 * place a saved recovery phrase came out on one tap.
 */
@Composable
private fun PhraseFields(state: ItemEditUiState, viewModel: ItemEditViewModel) {
    val c = LkTheme.colors
    val clipboard = LocalClipboardManager.current
    val reduceMotion = LocalLkReduceMotion.current

    val words = state.phraseWords
    val count = state.phraseWordCount
    val status = state.phraseStatus
    val bad = state.phraseBadIndices.toSet()
    val filled = state.phraseFilledCount
    val allFull = count > 0 && filled == count
    val draft = state.phraseDraft
    val focus = state.phraseFocus
    val reveal = state.phraseRevealAll
    val suggestions = remember(draft) {
        if (draft.isBlank()) emptyList() else SeedPhraseWordList.validator.suggestions(draft, 6)
    }
    // A slot is not a text field: it names which word the entry field below the grid is editing.
    // Tapping one only moved that highlight, so the keyboard never came up and whatever already
    // held focus (the title) took the typing. Device pass 2026-10-04: "aban" typed after tapping
    // slot 01 landed in the title twice, and on a 1920px screen the entry field sat below the fold.
    // Counted per tap rather than keyed on `focus`, because slot 01 is already focused when the
    // form opens, so tapping it changes no state; and only after a tap, so opening the form still
    // leaves the title first, as it does for every other type.
    val entryFocus = remember { FocusRequester() }
    var entryTaps by remember { mutableIntStateOf(0) }

    // The checksum sweep — a jade hairline down the grid, once, only when the last slot lands. Seeded
    // at 0 and flipped in the effect (never `animateFloatAsState(1f)`, which draws pre-swept on frame
    // one). Reduced motion skips it: the verdict still arrives, without the animation.
    val sweep = remember { Animatable(0f) }
    var sweeping by remember { mutableStateOf(false) }
    // Resolved in composable scope, not inside the effect — lkTween reads composition locals.
    val sweepSpec = lkTween<Float>(PHRASE_SWEEP_MILLIS, easing = LkMotion.Settle)
    LaunchedEffect(allFull) {
        if (allFull && !reduceMotion) {
            sweeping = true
            sweep.snapTo(0f)
            sweep.animateTo(1f, sweepSpec)
            sweeping = false
        } else {
            sweeping = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {

        // ── how many words ──
        //
        // Masked with the words while the gate is shut, and for the same reason the detail screen's
        // count row is (F-26, 2026-09-15 visual pass): the length is a fact about the secret, and a
        // chooser with "12" lit up states it in full. `phraseCountHidden` is false while a phrase is
        // being *typed in* — entering a secret is not disclosing one, and a capture screen that hid
        // the number the user is choosing would be hiding their own input from them.
        //
        // Hidden rather than dimmed: the chooser's whole job is to say which length is selected, so
        // there is no disabled state of it that does not answer the question. The lengths come back
        // with the words, on the Reveal pill directly below — and the shrink it guards against was
        // already refused behind the gate (`onPhraseWordCountChanged`), so nothing usable is lost.
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            LkFieldLabel(stringResource(R.string.itemedit_phrase_count_label))
            if (state.phraseCountHidden) {
                Text(MASKED_COUNT_PLACEHOLDER, style = LkText.masked, color = c.textMuted)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                    for (n in PHRASE_COUNTS) {
                        val selected = count == n
                        Box(
                            Modifier
                                .weight(1f)
                                // The floor, not 44dp, and stated as the constant rather than as a
                                // number: `.height()` is an exact constraint, so appending
                                // `minTouchTarget()` to this chain would be the silent no-op A11y.kt
                                // and LkIconButton both record. Raising the drawn plate is the honest
                                // fix here — it is the whole target, and the word slots further down
                                // this same form already stand at 48dp (`PHRASE_SLOT_HEIGHT`).
                                .height(MinTouchTarget)
                                .clip(LkShape.sm)
                                .lkSurface(
                                    LkShape.sm,
                                    if (selected) c.jade.copy(alpha = 0.10f) else Color.Transparent,
                                    if (selected) c.jade.copy(alpha = 0.30f) else c.textFaint.copy(alpha = 0.20f),
                                )
                                // selectable, not clickable: colour alone told a sighted user which
                                // length was chosen; RadioButton role + selected state let a screen
                                // reader hear "selected" and read the five as one exclusive group.
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.onPhraseWordCountChanged(n) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(n.toString(), style = LkText.monoField, color = if (selected) c.jade else c.textMuted)
                        }
                    }
                }
            }
        }

        // ── words: header, then the grid ──
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            PhraseWordsHeader(
                reveal = reveal,
                onPasteAll = {
                    // An empty clipboard used to be the one silent outcome on this screen: the
                    // safe-call chain simply evaluated to null and nothing changed, on a screen
                    // that narrates "No match", "0 of 12 words" and the checksum verdict. Say so.
                    val pasted = clipboard.getText()?.text
                    if (pasted.isNullOrBlank()) {
                        LkNotices.postRefusal(R.string.itemedit_phrase_paste_all_empty)
                    } else {
                        viewModel.pastePhrase(pasted)
                    }
                },
                onToggleReveal = viewModel::togglePhraseReveal,
            )

            // Valid tints the whole grid jade at 4%; a bad word, or a refused vault-kit phrase, tints
            // it coral. Nothing moves — the words stay readable while the verdict changes (comp motion
            // § "colour, not layout").
            // CHECKSUM joins them: it is the one verdict that names no slot — "one of them is
            // wrong, or two are in the wrong order" — so the whole grid is what the refusal is
            // about, and leaving it neutral while the banner below turned coral would split one
            // verdict into two colours.
            val gridFill = when (status) {
                SeedPhraseStatus.VALID -> c.jade.copy(alpha = 0.04f)
                SeedPhraseStatus.BADWORD, SeedPhraseStatus.VAULTKIT, SeedPhraseStatus.CHECKSUM ->
                    c.coral.copy(alpha = 0.04f)
                else -> c.textFaint.copy(alpha = 0.015f)
            }
            val gridRing = when (status) {
                SeedPhraseStatus.VALID -> c.jade.copy(alpha = 0.18f)
                SeedPhraseStatus.BADWORD, SeedPhraseStatus.VAULTKIT, SeedPhraseStatus.CHECKSUM ->
                    c.coral.copy(alpha = 0.22f)
                else -> c.textFaint.copy(alpha = 0.12f)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(LkShape.card)
                    .lkSurface(LkShape.card, gridFill, gridRing)
                    .drawWithContent {
                        drawContent()
                        if (sweeping) {
                            val y = sweep.value * size.height
                            drawLine(
                                color = c.jade,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                    }
                    .padding(LkSpace.sm),
                verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
            ) {
                words.indices.chunked(3).forEach { rowIndices ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                        for (i in rowIndices) {
                            PhraseSlot(
                                number = i + 1,
                                word = words[i],
                                focused = i == focus,
                                draft = draft,
                                bad = i in bad,
                                reveal = reveal,
                                onClick = {
                                    viewModel.focusPhraseSlot(i)
                                    entryTaps++
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Pad the last row so a 12/15/18/21 grid keeps three equal columns.
                        repeat(3 - rowIndices.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // ── the focused slot's entry field + four-letter autocomplete ──
        if (focus in words.indices) {
            val hint = stringResource(
                when {
                    suggestions.size == 1 -> R.string.itemedit_phrase_hint_one
                    draft.length >= 4 && suggestions.isEmpty() -> R.string.itemedit_phrase_hint_none
                    else -> R.string.itemedit_phrase_hint_four
                },
            )
            // The IME covers the bottom of the form, and the entry field sits near it. Compose lifts
            // the focused field above the keyboard on its own, but the suggestion chips are a separate
            // element below it and would stay hidden — you would type, read "One match", and have no
            // chip to tap. This scrolls the chips into view whenever they appear, the same
            // BringIntoViewRequester idiom the title field and the inline error use above.
            val chipsReveal = remember { BringIntoViewRequester() }
            LaunchedEffect(suggestions.isNotEmpty(), focus) {
                if (suggestions.isNotEmpty()) runCatching { chipsReveal.bringIntoView() }
            }
            // Inside this block so a tap that first has to pass the reveal gate (an edit of a stored
            // phrase) still lands here once the field exists.
            LaunchedEffect(entryTaps) {
                if (entryTaps > 0) runCatching { entryFocus.requestFocus() }
            }
            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                // Copy/Cut on this field must go through the app's clipboard path, not the
                // platform's. `focusPhraseSlot` already gates loading a *stored* word into this
                // well behind the same prompt that guards Reveal, so there is no way to read a
                // phrase without authenticating — but once the word is legitimately on screen, the
                // default text toolbar would lift it straight to the clipboard, skipping
                // `ClipboardHelper` and therefore both `EXTRA_IS_SENSITIVE` and the auto-clear
                // timer. A word of a seed phrase would then sit there until something overwrote
                // it. The field stays visually unmasked on purpose (see the note below); it is the
                // toolbar, not the rendering, that this changes.
                GuardedSecretField {
                    BoxedField(
                        value = draft,
                        onValueChanged = viewModel::onPhraseDraftChanged,
                        label = stringResource(R.string.itemedit_phrase_word_label, focus + 1),
                        modifier = Modifier.fillMaxWidth().focusRequester(entryFocus),
                        placeholder = stringResource(R.string.itemedit_phrase_type_four),
                        labelTrailing = { Text(hint, style = LkText.caption, color = c.textFaint) },
                        // The words themselves are the higher-value half of this screen, and they were
                        // typed through a plain text field: autocorrect on, and the IME free to learn
                        // and later suggest each one. `KeyboardType.Password` is what turns both off.
                        // The value stays visible while typing whatever the reveal toggle says — you
                        // have to be able to see the word you are entering, and the completion chips
                        // below already show it — so this masks nothing; it is the keyboard that
                        // changes. Once committed, the slot obeys `reveal` like every other.
                        keyboardType = KeyboardType.Password,
                        visualTransformation = VisualTransformation.None,
                        // The keyboard commits the word. Until this there was no keyboard path at all:
                        // `onPhraseDraftChanged` strips everything outside a-z, so a typed Space never
                        // even reached the draft, and the ONLY thing that wrote a typed word into
                        // `phraseWords` was the autocomplete chip. A user who typed all twelve words by
                        // hand watched the last slot show their word while Save stayed dimmed and
                        // silent - the word was in the draft, and `phraseSaveBlocked` reads the grid.
                        // This file already calls that chip "small, optional-looking, and not where a
                        // user tapping through twelve slots is looking".
                        imeAction = ImeAction.Next,
                        onImeAction = {
                            // Resolved exactly as tapping the chip resolves it: an exact wordlist hit
                            // commits itself, and an unambiguous prefix commits the word it can only
                            // mean. Anything still ambiguous commits nothing and leaves the chips up -
                            // guessing between six candidates on the user's behalf is how a wrong word
                            // ends up in a seed phrase, and the checksum would only say so twelve
                            // slots later.
                            val resolved = suggestions.firstOrNull { it == draft }
                                ?: suggestions.singleOrNull()
                            if (resolved != null) viewModel.commitPhraseWord(resolved)
                        },
                    )
                }
                if (suggestions.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.bringIntoViewRequester(chipsReveal),
                        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
                        verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
                    ) {
                        val lone = suggestions.size == 1
                        for (word in suggestions) {
                            // Two nodes, for the reason `PhrasePillButton` below gives at length:
                            // `minTouchTarget()` is `defaultMinSize`, which raises a dimension only
                            // when the incoming minimum is zero, so appending it to a chain that
                            // already carries `.height(36.dp)` is a silent no-op. The tappable node
                            // is therefore the outer one, with nothing fixing its size, and the
                            // 36dp chip is a drawn child — the same split, and the same 12dp that
                            // was missing under the app's own floor on a control that commits a
                            // word straight into the recovery-phrase grid.
                            Box(
                                Modifier
                                    .clip(LkShape.sm)
                                    .clickable { viewModel.commitPhraseWord(word) }
                                    .minTouchTarget(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .height(36.dp)
                                        .clip(LkShape.sm)
                                        .lkSurface(
                                            LkShape.sm,
                                            if (lone) {
                                                c.jade.copy(alpha = 0.12f)
                                            } else {
                                                c.textFaint.copy(alpha = 0.05f)
                                            },
                                            if (lone) {
                                                c.jade.copy(alpha = 0.30f)
                                            } else {
                                                c.textFaint.copy(alpha = 0.18f)
                                            },
                                        )
                                        .padding(horizontal = LkSpace.md),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        word,
                                        style = LkText.monoField,
                                        color = if (lone) c.jade else c.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── verdict banner ──
        PhraseBanner(status = status, count = count, filled = filled, firstBadSlot = bad.minOrNull()?.plus(1))

        // ── the optional 25th word ──
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            // Masked with the words, and honouring the same reveal toggle they do. It was drawn by
            // the plain `Field`, which meant the 25th word — the piece that makes a stolen seed
            // phrase useless without it — was the one part of this screen shown in clear, on
            // KeyboardType.Text, with autocorrect enabled and the IME free to learn it. The words
            // beside it were masked the whole time.
            Field(
                value = state.phrasePassphrase,
                onValueChanged = viewModel::onPhrasePassphraseChanged,
                label = stringResource(R.string.itemedit_phrase_passphrase_label),
                // Password unconditionally, never keyed off the reveal. `LkFormField` derives
                // `autoCorrectEnabled` from the keyboard type, so revealing used to switch this
                // field to Text and hand the 25th word to the IME's dictionary and suggestion
                // strip — learned once, offered back on any other app's form afterwards. Only the
                // visual transformation below answers to `reveal`; the keyboard does not. The
                // hidden custom field is the precedent: it keys its keyboardOptions off `hidden`
                // alone. This bounds what a *benign* keyboard keeps; a malicious IME reads
                // keystrokes whatever the type says, and nothing here changes that.
                keyboardType = KeyboardType.Password,
                visualTransformation = if (state.phraseRevealAll) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation(PHRASE_MASK_CHAR)
                },
            )
            Text(
                stringResource(R.string.itemedit_phrase_passphrase_caption),
                style = LkText.caption,
                color = c.textFaint,
            )
        }
    }
}

/** The five word counts BIP-39 defines. Read by [ItemEditUiState.phraseSaveBlocked] too, which is
 *  why it is not private: a grid at any other length is an unfinished phrase, not a phrase whose
 *  checksum happened to fail. */
internal val PHRASE_COUNTS = listOf(12, 15, 18, 21, 24)
private const val PHRASE_SWEEP_MILLIS = 800

/** Five dots, always five, whatever the word's length — a mask that tracked length would leak most
 *  of the search space to a glance. Not a translatable string; the same glyph [SecretReadField] uses. */
/** The bullet the phrase slots and the 25th-word field are masked with — one glyph, one meaning. */
private const val PHRASE_MASK_CHAR = '•'
private val PHRASE_MASK = PHRASE_MASK_CHAR.toString().repeat(5)

/**
 * The Words header: the field label, then "Paste all" and "Reveal"/"Hide".
 *
 * A [FlowRow] and not a `Row` with the label in `weight(1f)` (SCL-014). The label shared the line
 * with two 48dp-tall pills, so at the 308dp floor and font scale 2.0 the pills took their width first
 * and the label was handed what was left: a lone "…", no letter surviving. A flow row measures each
 * child whole and moves the one that no longer fits to the next line, so the pills drop under the
 * label when they must and stay beside it at every width that has room. At 411dp and 1.0 everything
 * fits on one line and the label is pushed to the start with the pills at the end, exactly as before.
 */
@Composable
internal fun PhraseWordsHeader(
    reveal: Boolean,
    onPasteAll: () -> Unit,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        LkFieldLabel(stringResource(R.string.itemedit_phrase_words_label), modifier = Modifier.weight(1f))
        PhrasePillButton(
            text = stringResource(R.string.itemedit_phrase_paste_all),
            onClick = onPasteAll,
        )
        PhrasePillButton(
            text = stringResource(if (reveal) R.string.itemedit_phrase_hide else R.string.itemedit_phrase_reveal),
            icon = if (reveal) LkIcons.EyeOff else LkIcons.Eye,
            // Amber, not jade, once the secret is on screen (N-03) — the same rule the
            // per-field reveal toggle below states in full: LkColors reserves jade for
            // primary action, and revealing is a state toggle, not one. This one rendered
            // jade until 2026-09-16, the only reveal control in the app that did.
            tint = if (reveal) c.amber else c.textMuted,
            onClick = onToggleReveal,
        )
    }
}

/** The drawn height of a Words-header pill. The comp draws 30; the *tappable* box around it is held
 *  to the 48dp floor by [minTouchTarget], exactly as [PHRASE_SLOT_HEIGHT] holds the grid cells. */
private val PHRASE_PILL_HEIGHT = 30.dp

/**
 * A small pill control on the Words header — "Paste all", "Reveal"/"Hide".
 *
 * Two nodes rather than one, and the split is the point. The outer box carries the click and the
 * 48dp touch floor; the inner row carries the 30dp the comp draws. Written as a single 30dp
 * `Row(...).clickable(...)` it was 18dp under the floor the word slots twenty pixels below it
 * already hold — and these two pills sit 8dp apart, with "Paste all" (which overwrites the whole
 * grid) next to Reveal. A miss there costs a recovery phrase, so the floor is not cosmetic here.
 */
@Composable
private fun PhrasePillButton(
    text: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: Color = LkTheme.colors.textMuted,
) {
    val c = LkTheme.colors
    Box(
        Modifier
            .clip(LkShape.xs)
            .clickable(onClick = onClick)
            .minTouchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(PHRASE_PILL_HEIGHT)
                .clip(LkShape.xs)
                .lkSurface(LkShape.xs, Color.Transparent, c.textFaint.copy(alpha = 0.20f))
                .padding(horizontal = LkSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(LkSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            Text(text, style = LkText.buttonSmall, color = tint)
        }
    }
}

/** One numbered slot in the grid. Masked to a fixed five dots — length must not leak. */
@Composable
private fun PhraseSlot(
    number: Int,
    word: String,
    focused: Boolean,
    draft: String,
    bad: Boolean,
    reveal: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    // Focused shows the live draft; a bad word always shows itself (in coral); a filled, quiet,
    // unrevealed, valid slot collapses to five dots regardless of its length.
    val masked = word.isNotBlank() && !focused && !reveal && !bad
    val display = when {
        focused -> draft
        masked -> PHRASE_MASK
        else -> word
    }
    val fill = when {
        focused -> c.jade.copy(alpha = 0.08f)
        bad -> c.coral.copy(alpha = 0.09f)
        word.isNotBlank() -> c.surface
        else -> c.surface.copy(alpha = 0.6f)
    }
    val ring = when {
        focused -> c.focusRing
        bad -> c.coral.copy(alpha = 0.42f)
        word.isNotBlank() -> c.textFaint.copy(alpha = 0.18f)
        else -> c.textFaint.copy(alpha = 0.10f)
    }
    val ink = when {
        bad -> c.coralText
        word.isNotBlank() || focused -> c.textStrong
        else -> c.textFaint
    }
    Row(
        modifier
            .height(PHRASE_SLOT_HEIGHT)
            .clip(LkShape.sm)
            .lkSurface(LkShape.sm, fill, ring)
            .clickable(onClick = onClick)
            .padding(horizontal = LkSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(LkSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number.toString().padStart(2, '0'),
            style = LkText.monoCaption,
            color = if (focused) c.jade else if (bad) c.coralText else c.textFaint,
        )
        Text(
            display,
            style = LkText.monoField,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The verdict line under the grid — icon, colour and two sentences that swap with the status. */
@Composable
private fun PhraseBanner(status: SeedPhraseStatus, count: Int, filled: Int, firstBadSlot: Int?) {
    val c = LkTheme.colors
    data class Banner(val icon: androidx.compose.ui.graphics.vector.ImageVector, val accent: Color, val title: String, val body: String)
    val b = when (status) {
        SeedPhraseStatus.BADWORD -> Banner(
            LkIcons.Warning, c.coralText,
            stringResource(R.string.itemedit_phrase_banner_badword_title, firstBadSlot ?: 1),
            stringResource(R.string.itemedit_phrase_banner_badword_body),
        )
        // Coral, not amber. `LkColors` gives amber to "something is worth looking at but nothing is
        // wrong", and a checksum failure is a hard block: Save stays disabled with
        // `desc='Save, available once the recovery phrase is valid'`, so the card was painted the
        // one colour that says the opposite of what the screen does (4C-14, 2026-09-20). Its two
        // siblings — a word that is not BIP-39, and a phrase that is this vault's own Recovery Kit
        // — already refuse in coral.
        SeedPhraseStatus.CHECKSUM -> Banner(
            LkIcons.Warning, c.coralText,
            stringResource(R.string.itemedit_phrase_banner_checksum_title),
            stringResource(R.string.itemedit_phrase_banner_checksum_body),
        )
        SeedPhraseStatus.VALID -> Banner(
            LkIcons.Check, c.jade,
            stringResource(R.string.itemedit_phrase_banner_valid_title),
            stringResource(R.string.itemedit_phrase_banner_valid_body, (count - 1).coerceAtLeast(0)),
        )
        SeedPhraseStatus.VAULTKIT -> Banner(
            LkIcons.Key, c.coralText,
            stringResource(R.string.itemedit_phrase_banner_vaultkit_title),
            stringResource(R.string.itemedit_phrase_banner_vaultkit_body),
        )
        SeedPhraseStatus.INCOMPLETE -> Banner(
            LkIcons.SeedPhrase, c.textSecondary,
            stringResource(R.string.itemedit_phrase_banner_incomplete_title, filled, count),
            stringResource(R.string.itemedit_phrase_banner_incomplete_body),
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .lkSurface(LkShape.xl, b.accent.copy(alpha = 0.07f), b.accent.copy(alpha = 0.22f))
            .padding(horizontal = LkSpace.md, vertical = LkSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Icon(b.icon, contentDescription = null, tint = b.accent, modifier = Modifier.size(17.dp).padding(top = 1.dp))
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
            Text(b.title, style = LkText.cardTitle, color = b.accent, modifier = Modifier.announce(b.title))
            Text(b.body, style = LkText.caption, color = c.textMuted)
        }
    }
}

/**
 * Everything needed to get a seed in through the camera: the button, the permission rationale, the
 * denial notice and the scanner itself.
 *
 * **Extracted so a Login can reach it.** `itemedit_totp_scan_button` existed on exactly one screen
 * — the standalone 2FA item type — while the Login form, which is where a second factor actually
 * belongs, could only receive a seed from an item the user had already created elsewhere.
 * `Item Flows.dc.html` makes the scanner a shared surface: *"the Login flow now routes into it
 * instead of attaching a seed from nowhere."* `scanQrCode` was already type-agnostic, so this is a
 * reachability change rather than a new capability.
 *
 * **No QR glyph, deliberately.** The button shipped with `LkIcons.Search`, a magnifier, which says
 * "find" rather than "point the camera at that square". `LkIcons` has no QR mark and drawing one
 * for this would be a new glyph in a set that is otherwise transcribed from the design. The comp
 * makes the same call and states it: *"No invented icon… the scan buttons here are text-only."*
 *
 * [autoOpenForNewItem] belongs to the 2FA item type alone. Someone who tapped "2FA code" in the add
 * sheet has already said what they came to do, so the camera opens on arrival. Someone editing a
 * login has not, and a viewfinder opening over their password field would be startling.
 */
/**
 * Everything the "Scan the QR code" tap needs, owned once and shared by the two places that
 * can fire it.
 *
 * Hoisted out of [TotpScanControls] on 2026-08-21. The action column and the new-item wizard reach
 * the screen through two *different* lambda parameters of the same `LkActionScaffold` call, so a
 * trigger living inside either one is invisible to the other — and two copies would each carry
 * their own permission launcher and their own denial notice, which is how one button ends up
 * showing an error the other one caused.
 *
 * The auto-open effect that used to live here is deliberately not reproduced. See [TotpEntryWizard].
 */
private data class TotpScanTrigger(
    val openScanner: () -> Unit,
    val cameraDenial: PermissionDenial?,
    val showCameraDisclosure: Boolean,
    val onDismissDisclosure: () -> Unit,
    val onConfirmDisclosure: () -> Unit,
)

@Composable
private fun rememberTotpScanTrigger(viewModel: ItemEditViewModel): TotpScanTrigger {
    val context = LocalContext.current
    // Saveable: this is the runtime-permission rationale. Losing it to a rotation drops the user
    // back to a button that appears to have done nothing.
    var showCameraDisclosure by rememberSaveable { mutableStateOf(false) }
    // Saveable for the same reason the disclosure above is: a denial notice lost to a rotation
    // leaves the user back at a button that appears to do nothing, which is the very state this
    // branch exists to end.
    var cameraDenial by rememberSaveable(stateSaver = PermissionDenialSaver) {
        mutableStateOf<PermissionDenial?>(null)
    }
    val cameraDenialSentence = stringResource(R.string.itemedit_totp_camera_denial_sentence)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // The `else` this used to lack entirely. Denying the camera silently dropped the result,
        // so the QR button read as broken rather than as blocked.
        if (granted) {
            cameraDenial = null
            viewModel.showQrScanner()
        } else {
            cameraDenial = PermissionDenial.after(
                context = context,
                permission = Manifest.permission.CAMERA,
                featureSentence = cameraDenialSentence,
            )
        }
    }
    val openScanner = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        // Rationale before the system dialog when we do not already hold the permission. Landing a
        // bare OS prompt on someone who just tapped "2FA code" gives them no way to know why the
        // camera is being asked for, which is both the Android guidance and the Play requirement
        // for a permission whose purpose is not evident from the tap.
        if (granted) viewModel.showQrScanner() else showCameraDisclosure = true
    }
    return TotpScanTrigger(
        openScanner = openScanner,
        cameraDenial = cameraDenial,
        showCameraDisclosure = showCameraDisclosure,
        onDismissDisclosure = { showCameraDisclosure = false },
        onConfirmDisclosure = {
            showCameraDisclosure = false
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
    )
}

/**
 * The scan offer, pinned under Save exactly as the comp pins it — a `flex-direction:column;gap:8px`
 * block, primary then secondary, on both the Login and the 2FA screen.
 *
 * **No QR glyph, deliberately.** The button shipped with `LkIcons.Search`, a magnifier, which says
 * "find" rather than "point the camera at that square". `LkIcons` has no QR mark and drawing one
 * for this would be a new glyph in a set that is otherwise transcribed from the design. The comp
 * makes the same call and states it: *"No invented icon… the scan buttons here are text-only."*
 *
 * A pure renderer now: everything that decides *what happens* lives in [rememberTotpScanTrigger].
 */
@Composable
private fun TotpScanControls(trigger: TotpScanTrigger, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth()) {
        LkSecondaryButton(
            text = stringResource(R.string.itemedit_totp_scan_button),
            onClick = trigger.openScanner,
            modifier = Modifier.fillMaxWidth(),
        )
        // Rendered next to the button that triggered it, so the answer is where the question was.
        trigger.cameraDenial?.let { denial ->
            Spacer(Modifier.height(LkSpace.sm))
            LkInlineError(
                message = denial.message,
                modifier = Modifier.announce(denial.message),
                actionLabel = denial.settingsActionLabel,
                onAction = denial.settingsActionLabel?.let { { context.openAppSettings() } },
            )
        }
    }
}

/**
 * A standalone 2FA item's form.
 *
 * A **new** item is one button until a code has been scanned: "Scan the QR code", and nothing else
 * (2026-10-05, on request — the QR is the only way in). It replaced a two-step chooser whose second
 * option, "Enter the details myself", opened a paste-a-link well and a hand-typed Base32 secret.
 * Once the scan lands, the scanned fields and the live code show, so the result can be checked
 * before Save.
 *
 * `Item Flows.dc.html` holds for the part that stays: the scanner is opened by a tap, never by an
 * effect on first composition.
 *
 * Editing an existing item skips the button — that is someone correcting a field, not enrolling.
 */
@Composable
private fun TotpFields(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    // The whole trigger, not just its `openScanner`: the button has to be able to *answer* a denial
    // as well as raise the request. See [TotpScanStep].
    scanTrigger: TotpScanTrigger,
) {
    if (state.isNew && state.totpSecret.isBlank()) {
        TotpScanStep(scanTrigger)
    } else {
        TotpManualFields(state, viewModel)
    }
}

/**
 * A new 2FA item before its scan: one line saying what to point the camera at, the "Scan the QR
 * code" button under it, and the camera denial if there is one - a single compact group, centred
 * vertically in the room the header leaves.
 *
 * **No viewfinder.** On 2026-10-14 this step drew four jade corner brackets and pinned the button
 * in the action slot; the principal rejected it on 2026-10-15 because the brackets look like the
 * camera is already open and scanning, when nothing has been asked of it yet. The action slot is
 * empty until a code is in, and Save takes it then.
 *
 * **No QR glyph, deliberately** - `LkIcons` has none, and the comp states the call (*"No invented
 * icon"*).
 */
@Composable
private fun TotpScanStep(scanTrigger: TotpScanTrigger) {
    val context = LocalContext.current
    val c = LkTheme.colors
    Column(
        // Fills the height its parent hands it and centres within it. Scrolls only if the content is
        // taller than that (landscape, font scale 2): `fillMaxHeight` before `verticalScroll` makes
        // the viewport the minimum, so `CenterVertically` has room to work and overflow still
        // reaches the denial notice.
        Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).lkEnter(),
        verticalArrangement = Arrangement.spacedBy(LkSpace.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.itemedit_totp_scan_hint),
            style = LkText.body,
            color = c.textTertiary,
            textAlign = TextAlign.Center,
        )
        LkPrimaryButton(
            text = stringResource(R.string.itemedit_totp_scan_button),
            onClick = scanTrigger.openScanner,
            modifier = Modifier.lkTag("totp-scan"),
        )
        // The answer where the question was, under the button it came from. Until 2026-09-20 (sweep
        // 4B-1) this step had no slot for it: denying the camera here left the screen
        // byte-identical to before the tap and the primary button simply stopped working.
        scanTrigger.cameraDenial?.let { denial ->
            LkInlineError(
                message = denial.message,
                modifier = Modifier.announce(denial.message),
                actionLabel = denial.settingsActionLabel,
                onAction = denial.settingsActionLabel?.let { { context.openAppSettings() } },
            )
        }
    }
}

@Composable
private fun TotpManualFields(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
) {
    val context = LocalContext.current
    // Side by side, at `flex:1` each with the comp's `gap:8px`. Issuer and Account are two halves
    // of one name — "GitHub / ada@example.com" — and the comp draws them as one row for that
    // reason; stacked, they read as two unrelated questions and cost a screenful between the
    // scanner and the secret.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Field(
            state.totpIssuer,
            viewModel::onTotpIssuerChanged,
            stringResource(R.string.itemedit_totp_issuer_label),
            modifier = Modifier.weight(1f),
        )
        Field(
            state.totpAccount,
            viewModel::onTotpAccountChanged,
            stringResource(R.string.itemedit_totp_account_label),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(LkSpace.md))
    // An unreadable key is marked here, on the field, the way DIGITS and PERIOD below already are.
    // It used to be refused only at Save, by the banner that names no field (4B-3, 2026-09-20).
    val secretUnreadable = state.totpSecretIsUnreadable()
    SecretField(
        value = state.totpSecret,
        onValueChanged = viewModel::onTotpSecretChanged,
        // "Secret (Base32)" named the encoding, which is the one thing the person typing it in
        // cannot check and does not choose. The label says what it is; the caption below says what
        // the characters look like, which is what actually helps someone transcribing one.
        label = stringResource(R.string.itemedit_totp_secret_field_label),
        field = "totpSecret",
        state = state,
        viewModel = viewModel,
        isError = secretUnreadable,
    )
    Spacer(Modifier.height(LkSpace.sm))
    // The caption's slot, not a line added beside it: the hint describes the key that should be
    // there, and once what is there cannot be read, saying what went wrong is the more useful of
    // the two. Coral because this is a failure — `LkColors` reserves amber for "nothing is wrong".
    Text(
        if (secretUnreadable) {
            stringResource(R.string.itemedit_totp_secret_error)
        } else {
            stringResource(R.string.itemedit_totp_secret_hint)
        },
        style = LkText.caption,
        color = if (secretUnreadable) LkTheme.colors.coral else LkTheme.colors.textFaint,
    )
    // Steam mode decides whether the three parameters below apply at all. `TotpGenerator.steamCode`
    // hardcodes SHA1, five digits and a 30-second period and reads none of them — so with it on
    // they were three controls that looked configurable and changed nothing about the code the user
    // actually gets. Worse, typing a digit count other than 6 or 8 marked the field and blocked Save
    // against a constraint Steam codes do not even have.
    //
    // Hidden rather than disabled: a greyed-out control still invites the question "why can't I
    // change this?", and the honest answer is that the parameter does not exist for a Steam token,
    // not that it is temporarily unavailable. An imported Steam token opens with them already
    // hidden for the same reason.
    if (!state.totpSteam) {
        Spacer(Modifier.height(LkSpace.md))
        // Three equally weighted chips asked the user to pick a hash, with nothing to pick on. SHA1 is
        // the default and covers virtually every real authenticator token, so the alternatives sit
        // behind a disclosure — and it opens on its own if the item already uses one, which is how an
        // imported token with SHA256 stays visible and editable rather than hidden behind a toggle
        // nobody would think to open.
        var showAlgorithms by rememberSaveable(state.itemId) {
            mutableStateOf(state.totpAlgorithm != TotpAlgorithm.SHA1)
        }
        // The initialiser runs once per `itemId`, and on a *new* item that id is fixed before the
        // user pastes anything — so the "opens on its own if the item already uses one" behaviour
        // above never fired on the enrolment path it was written for. Importing a link carrying
        // `algorithm=SHA256` applied it (DIGITS, PERIOD and the preview code all changed) and left
        // the row reading "Different algorithm?", with the one parameter the user cannot verify
        // from the preview hidden behind it (4B-3alg, 2026-09-20).
        //
        // Opens, never closes: a user who opened the disclosure by hand keeps it open, and one who
        // sets SHA1 back still needs the control they just used.
        LaunchedEffect(state.totpAlgorithm) {
            if (state.totpAlgorithm != TotpAlgorithm.SHA1) showAlgorithms = true
        }
        if (showAlgorithms) {
            LkFieldLabel(stringResource(R.string.itemedit_totp_algorithm_label))
            Spacer(Modifier.height(LkSpace.sm))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TotpAlgorithm.entries.forEach { algorithm ->
                    LkChip(
                        text = algorithm.name,
                        selected = algorithm == state.totpAlgorithm,
                        onClick = { viewModel.onTotpAlgorithmChanged(algorithm) },
                    )
                }
            }
        } else {
            LkTextButton(
                text = stringResource(R.string.itemedit_totp_different_algorithm_button),
                onClick = { showAlgorithms = true },
            )
        }
        Spacer(Modifier.height(LkSpace.md))
        // These two are free text but their acceptable values are narrow (digits 6 or 8, period a
        // positive int — the same require()s toTotp() throws). Without a per-field mark an
        // out-of-range value cleared the title check and then collapsed into the single generic
        // "Check the item fields" banner, which names no field: the refusal was untraceable from
        // where Save sits. Mark the offending field so it is field-attributed. Empty is not an
        // error — the user may still be typing.
        //
        // Hoisted out of the Row so the caption below can read them; nothing else about them moved.
        val digitsError = state.totpDigits.isNotBlank() &&
            state.totpDigits.toIntOrNull().let { it != 6 && it != 8 }
        val periodError = state.totpPeriodSec.isNotBlank() &&
            (state.totpPeriodSec.toIntOrNull()?.let { it <= 0 } ?: true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Both hold a small integer and nothing else, so both ask for the numeric layout — the
            // field marks an out-of-range value as an error while opening on a keyboard that makes one
            // easy to type.
            BoxedField(
                state.totpDigits,
                viewModel::onTotpDigitsChanged,
                stringResource(R.string.itemedit_totp_digits_label),
                Modifier.weight(1f),
                keyboardType = KeyboardType.Number,
                isError = digitsError,
            )
            BoxedField(
                state.totpPeriodSec,
                viewModel::onTotpPeriodSecChanged,
                stringResource(R.string.itemedit_totp_period_label),
                Modifier.weight(1f),
                keyboardType = KeyboardType.Number,
                isError = periodError,
            )
        }
        // The rule in words, under the field that broke it. The mark alone was colour-only
        // signalling — the a11y node stayed `desc='Digits'` with no error text, so a TalkBack user
        // got nothing at all, and nothing on screen ever said "6 or 8" (4B-6, 2026-09-20). Two
        // screens away the card expiry already answers "Month is 1–12, as printed on the card."
        if (digitsError || periodError) {
            Spacer(Modifier.height(LkSpace.sm))
            val message = stringResource(
                if (digitsError) R.string.itemedit_totp_digits_error else R.string.itemedit_totp_period_error,
            )
            Text(
                message,
                style = LkText.caption,
                color = LkTheme.colors.coral,
                modifier = Modifier.announce(message),
            )
        }
    }
    // Steam is one service among every other 2FA token, so it sits at the end behind a quiet
    // disclosure, the way the algorithm chips do — a full-width checkbox in the middle of the form
    // asked every user a question that applies to almost none of them. Opens on its own for a
    // token already in Steam mode (an Aegis import), so the switch that turns it off stays visible.
    var showSteam by rememberSaveable(state.itemId) { mutableStateOf(state.totpSteam) }
    LaunchedEffect(state.totpSteam) {
        if (state.totpSteam) showSteam = true
    }
    Spacer(Modifier.height(LkSpace.md))
    if (showSteam) {
        CheckboxRow(stringResource(R.string.itemedit_totp_steam_label), state.totpSteam, viewModel::onTotpSteamToggled)
    } else {
        LkTextButton(
            text = stringResource(R.string.itemedit_totp_steam_button),
            onClick = { showSteam = true },
        )
    }

    // A live preview of what this configuration actually produces. It is the only way to tell a
    // mistyped seed or a wrong period from a correct one before saving and failing at a login page.
    if (state.totpCurrentCode.isNotEmpty()) {
        Spacer(Modifier.height(LkSpace.md))
        val c = LkTheme.colors
        // Thresholds come from totpToneFor, shared with the detail card. Both screens used to carry
        // their own copy of the same two numbers, which is a disagreement waiting to happen.
        val tone = when (totpToneFor(state.totpRemainingSeconds)) {
            TotpTone.EXPIRING -> c.coral
            TotpTone.NEAR -> c.amber
            TotpTone.CALM -> c.jade
        }
        // On a reprompt item the live code is a secret like any other: masked until the gate is
        // passed, exactly as the detail card treats it. Without this the editor was a reveal-gate
        // bypass — the same code the detail screen masks and biometric-gates rendered in clear the
        // moment you tapped Edit.
        val totpGated = state.requiresReauthFor(TOTP_CODE_FIELD)
        val totpRevealed = !totpGated || TOTP_CODE_FIELD in state.revealedFields
        LkLiftedCard {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // No arrangement: the only gap this column has is the label-to-code one, and
                // [LkSectionLabel] owns it (LkSectionLabelGap). A `spacedBy` here would be added to
                // the label's own padding rather than replacing it.
                Column(Modifier.weight(1f)) {
                    LkSectionLabel(stringResource(R.string.itemedit_totp_preview_label))
                    val spokenCode = stringResource(
                        R.string.itemedit_detail_totp_code_spoken,
                        state.totpCurrentCode.toCharArray().joinToString(" "),
                    )
                    val hiddenCodeDescription =
                        stringResource(R.string.itemedit_detail_totp_code_hidden)
                    Text(
                        if (totpRevealed) {
                            state.totpCurrentCode
                        } else {
                            "•".repeat(state.totpCurrentCode.length)
                        },
                        style = LkText.numeralLarge,
                        color = c.textStrong,
                        // Digit by digit: TTS otherwise reads "123456" as a single large number.
                        // The a11y tree is a reveal surface too — a masked code that spells itself
                        // out to TalkBack is not masked.
                        modifier = Modifier.semantics {
                            contentDescription = if (totpRevealed) spokenCode else hiddenCodeDescription
                        },
                    )
                }
                Text(
                    stringResource(R.string.itemedit_detail_totp_seconds, state.totpRemainingSeconds),
                    style = LkText.monoCaption,
                    color = tone,
                )
                if (totpGated) {
                    LkIconButton(
                        icon = if (totpRevealed) LkIcons.EyeOff else LkIcons.Eye,
                        label = if (totpRevealed) {
                            stringResource(R.string.itemedit_detail_totp_hide)
                        } else {
                            stringResource(R.string.itemedit_detail_totp_show)
                        },
                        onClick = { viewModel.toggleFieldRevealed(TOTP_CODE_FIELD) },
                    )
                }
                // Same gate as the detail card: on a reprompt item this button put the live second
                // factor on the clipboard for anyone to paste, while the vault-list row refused the
                // identical copy on the identical item.
                val totpClipboardLabel = stringResource(R.string.itemedit_detail_totp_clipboard_label)
                LaunchedEffect(state.copyGrantedField) {
                    if (state.copyGrantedField == TOTP_CODE_FIELD) {
                        ClipboardHelper.copy(context, totpClipboardLabel, state.totpCurrentCode)
                        viewModel.onCopyPerformed()
                    }
                }
                LkIconButton(
                    icon = LkIcons.Copy,
                    label = if (state.requiresReauthFor(TOTP_CODE_FIELD) &&
                        TOTP_CODE_FIELD !in state.revealedFields
                    ) {
                        stringResource(R.string.itemedit_detail_totp_copy_gated)
                    } else {
                        stringResource(R.string.itemedit_detail_totp_copy)
                    },
                    onClick = { viewModel.requestCopy(TOTP_CODE_FIELD) },
                )
            }
        }
    }
    // Re-enrolling an existing code is a rescan, pinned under Save — the paste-a-link field that
    // sat here went when 2FA became scan-only (2026-10-05).
}

@Composable
private fun CardFields(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
) {
    // The card's own facts read as one object rather than as seven separate boxes; the free-text
    // note is about the card and not part of it, so it keeps its own block.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        Field(state.cardHolder, viewModel::onCardHolderChanged, stringResource(R.string.itemedit_card_holder_field_label))
        // Wrapped: the caption belongs to the number above it, and a bare child of this group
        // would be pushed a full field-gap away from what it describes.
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            // Scrolled to and focused when the save refuses over it, the same three signals the
            // title field gives: ring, caret, viewport. A ring alone at the top of a form the user
            // is looking at the bottom of is not feedback they will ever see.
            val numberReveal = remember { BringIntoViewRequester() }
            val numberFocus = remember { FocusRequester() }
            val isAmex = CardBrands.detect(state.cardNumber) == CardBrand.AMEX
            LaunchedEffect(state.cardNumberError) {
                if (state.cardNumberError) {
                    numberReveal.bringIntoView()
                    numberFocus.requestFocus()
                }
            }
            SecretField(
                state.cardNumber,
                viewModel::onCardNumberChanged,
                stringResource(R.string.itemedit_card_number_field_label),
                "cardNumber",
                state,
                viewModel,
                keyboardType = KeyboardType.NumberPassword,
                modifier = Modifier.bringIntoViewRequester(numberReveal).focusRequester(numberFocus),
                isError = state.cardNumberError,
                // The one well in this form whose value is both long and fixed-length. At font
                // scale 2.0 a single line showed about eleven and a half of sixteen bullets, and
                // revealed it cut `4111111111111111` off mid-glyph with no ellipsis and no way to
                // scroll to the rest (2B-3, 2026-09-20) — while the detail screen wrapped the same
                // value onto two lines correctly. Spec line 43 asks for dynamic type "without
                // truncation of critical content", and a card number the user cannot read back is
                // the case that phrase is about.
                singleLine = CARD_NUMBER_WELL_SINGLE_LINE,
                // Bullets grouped like the digits they hide, so a 19-digit number wraps between
                // groups and not inside a run of bullets (R-L-04). The same groups the wizard types in.
                maskTransformation = remember(isAmex) {
                    cardNumberMaskFor(if (isAmex) LkCardDigitGroups.Amex else LkCardDigitGroups.Default)
                },
                // Revealed, the same groups as the bullets (R-L-06): the number read back must
                // match the plastic and wrap only at the gaps, not run on as one ungapped string.
                revealedTransformation = remember(isAmex) {
                    LkCardNumberTransformation(if (isAmex) LkCardDigitGroups.Amex else LkCardDigitGroups.Default)
                },
            )
            // Announced as well as drawn, for the same reason the expiry message below is: the ring
            // is the only other signal and a screen reader does not carry it.
            if (state.cardNumberError) {
                val message = stringResource(R.string.itemedit_card_number_required_caption)
                Text(
                    message,
                    style = LkText.caption,
                    color = LkTheme.colors.coral,
                    modifier = Modifier.announce(message),
                )
            }
            // The one piece of feedback a masked card number can give: the network it belongs to.
            // Typing into a row of bullets otherwise confirms nothing at all, and this is the same
            // fact the user would check by looking at the physical card. Absent for anything
            // unrecognised — loyalty and gift cards live here too, and a badge that guessed at
            // them would be noise.
            val brand = CardBrands.detect(state.cardNumber)
            if (brand != CardBrand.UNKNOWN) {
                Text(
                    brand.displayName(),
                    style = LkText.caption,
                    color = LkTheme.colors.textTertiary,
                )
            }
        }
        // Likewise wrapped, so the failure message stays with the pair it is about.
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            // Checked as it is typed, not only at Save. Two identical-looking boxes and a single
            // failure message at the bottom of a filled-in form left the user to work out which
            // one it meant.
            Row(horizontalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                BoxedField(
                    state.cardExpMonth,
                    viewModel::onCardExpMonthChanged,
                    stringResource(R.string.itemedit_card_exp_month_label),
                    Modifier.weight(1f),
                    keyboardType = KeyboardType.Number,
                    isError = state.cardExpMonthInvalid,
                )
                BoxedField(
                    state.cardExpYear,
                    viewModel::onCardExpYearChanged,
                    stringResource(R.string.itemedit_card_exp_year_label),
                    Modifier.weight(1f),
                    keyboardType = KeyboardType.Number,
                    isError = state.cardExpYearInvalid,
                )
            }
            // Announced: the red ring is the only other signal, and it is not one a screen reader
            // carries.
            if (state.cardExpMonthInvalid || state.cardExpYearInvalid) {
                // Same three cases as the wizard's expiry step, and for the same reason: a year that
                // parses cleanly but is decades out needs telling apart from one that is not a year
                // at all. Here the field does take four digits, so the original message is still
                // right for the second case.
                val message = when {
                    state.cardExpMonthInvalid -> stringResource(R.string.itemedit_card_exp_month_error)
                    state.cardExpYearTooFarAhead ->
                        stringResource(R.string.itemedit_card_exp_year_far_error)
                    else -> stringResource(R.string.itemedit_card_exp_year_error)
                }
                Text(
                    message,
                    style = LkText.caption,
                    color = LkTheme.colors.coral,
                    modifier = Modifier.announce(message),
                )
            }
        }
        SecretField(
            state.cardCvv,
            viewModel::onCardCvvChanged,
            stringResource(R.string.itemedit_card_cvv_label),
            "cardCvv",
            state,
            viewModel,
            keyboardType = KeyboardType.NumberPassword,
        )
        SecretField(
            state.cardPin,
            viewModel::onCardPinChanged,
            stringResource(R.string.itemedit_card_pin_label),
            "cardPin",
            state,
            viewModel,
            keyboardType = KeyboardType.NumberPassword,
        )
    }
    Spacer(Modifier.height(LkSpace.md))
    BoxedField(
        value = state.cardNotes,
        onValueChanged = viewModel::onCardNotesChanged,
        label = stringResource(R.string.itemedit_notes_label),
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        // See the note on `NoteFields`. The card's notes well and the login's are the same field
        // doing the same job; the two disagreeing about the keyboard would be the difference with
        // no reason this file already refuses on their heights.
        keyboardType = KeyboardType.Password,
        minHeight = 86.dp,
        maxHeight = 220.dp,
    )
}

@Composable
private fun IdentityFields(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
) {
    // Sixteen fields, previously one flat run separated only by Spacers: name, then a social
    // security number, then a passport, then an email, then six lines of address. Grouped under
    // the same LkSectionLabel the rest of the app uses for sections, so the government IDs read as
    // a block a user can skip rather than as three more boxes among thirteen.
    // The heading names the block; the block is what makes it a block. Left uncontained, four
    // headings over fourteen loose wells still read as fourteen things with captions between them.
    // None of the four headings below carries a Spacer of its own: [LkSectionLabel] owns the gap to
    // the block it heads, and the `sm` that used to follow each one now stacks on it instead of
    // supplying it. The `lg` Spacers *between* sections stay — those are a different relationship.
    LkSectionLabel(stringResource(R.string.itemedit_identity_personal_details_heading))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        Field(
            state.identityFirstName,
            viewModel::onIdentityFirstNameChanged,
            stringResource(R.string.itemedit_identity_first_name_label),
        )
        Field(
            state.identityLastName,
            viewModel::onIdentityLastNameChanged,
            stringResource(R.string.itemedit_identity_last_name_label),
        )
        Field(state.identityCompany, viewModel::onIdentityCompanyChanged, stringResource(R.string.itemedit_identity_company_label))
    }

    Spacer(Modifier.height(LkSpace.lg))
    LkSectionLabel(stringResource(R.string.itemedit_identity_gov_ids_heading))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        SecretField(
            state.identitySsn,
            viewModel::onIdentitySsnChanged,
            stringResource(R.string.itemedit_identity_ssn_label),
            "ssn",
            state,
            viewModel,
        )
        SecretField(
            state.identityPassport,
            viewModel::onIdentityPassportChanged,
            stringResource(R.string.itemedit_identity_passport_field_label),
            "passport",
            state,
            viewModel,
        )
        SecretField(
            state.identityLicense,
            viewModel::onIdentityLicenseChanged,
            stringResource(R.string.itemedit_identity_license_field_label),
            "license",
            state,
            viewModel,
        )
    }

    Spacer(Modifier.height(LkSpace.lg))
    LkSectionLabel(stringResource(R.string.itemedit_identity_contact_heading))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        Field(
            state.identityEmail,
            viewModel::onIdentityEmailChanged,
            stringResource(R.string.itemedit_identity_email_label),
            KeyboardType.Email,
        )
        Field(
            state.identityPhone,
            viewModel::onIdentityPhoneChanged,
            stringResource(R.string.itemedit_detail_identity_phone_label),
            KeyboardType.Phone,
        )
    }

    Spacer(Modifier.height(LkSpace.lg))
    // Was a bare `Text("Address")` — the one section heading in this form that did not use the
    // shared style, so it rendered at a different weight halfway down the screen.
    LkSectionLabel(stringResource(R.string.itemedit_identity_address_heading))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        Field(
            state.identityAddressLine1,
            viewModel::onIdentityAddressLine1Changed,
            stringResource(R.string.itemedit_identity_address_line1_label),
        )
        Field(
            state.identityAddressLine2,
            viewModel::onIdentityAddressLine2Changed,
            stringResource(R.string.itemedit_identity_address_line2_label),
        )
        Field(
            state.identityAddressCity,
            viewModel::onIdentityAddressCityChanged,
            stringResource(R.string.itemedit_identity_address_city_label),
        )
        Field(
            state.identityAddressState,
            viewModel::onIdentityAddressStateChanged,
            stringResource(R.string.itemedit_identity_address_state_label),
        )
        Field(
            state.identityAddressZip,
            viewModel::onIdentityAddressZipChanged,
            stringResource(R.string.itemedit_identity_address_zip_label),
        )
        Field(
            state.identityAddressCountry,
            viewModel::onIdentityAddressCountryChanged,
            stringResource(R.string.itemedit_identity_address_country_label),
        )
    }

}

@Composable
private fun CustomFieldsSection(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
) {
    val c = LkTheme.colors
    // Its own Column, so this section is *one* child of the form. Emitting a label, some
    // cards and a button straight into the parent put the parent's 16dp between each of
    // them — including between the section label and the section it labels, which should
    // hug. Same double-spacing that inflated the field gaps; see the note on LkSectionSwap.
    Column(Modifier.fillMaxWidth()) {
        // Field volume, not section volume — it labels one group of wells inside the "more" card,
        // exactly as "Folder" and "Tags" above it do, and the comp draws all three at 10sp/.12em.
        LkFieldLabel(stringResource(R.string.itemedit_custom_fields_heading))
        Spacer(Modifier.height(LkSpace.sm))
        state.customFields.forEachIndexed { index, field ->
            val hidden = field.type == CustomFieldType.HIDDEN
            val revealed = index in state.revealedCustomFieldIndices
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                Field(
                    field.name,
                    { viewModel.updateCustomFieldName(index, it) },
                    stringResource(R.string.itemedit_custom_field_name_label),
                )
                // A hidden custom field is a secret like any other (a recovery code, a security
                // answer, an API key): reveal and the timer-driven auto-hide must announce via
                // the same polite live region SecretField/SecretReadField use everywhere else,
                // or a screen-reader user never learns the value is exposed or re-masked.
                //
                // The field itself is a plain LkTextField, so the platform selection menu's Copy
                // would put a revealed secret on the clipboard with no IS_SENSITIVE extra and no
                // auto-clear — the one editable secret that still had the LkPasswordField guard
                // missing. GuardedSecretField drops Copy/Cut from that menu for a *hidden* field,
                // keeping the app's own (gated) copy path as the only way out; a plain TEXT field
                // keeps its platform Copy because it is not a secret.
                val fieldContent: @Composable () -> Unit = {
                    LkTextField(
                        value = field.value,
                        onValueChange = { viewModel.updateCustomFieldValue(index, it) },
                        label = if (hidden) {
                            stringResource(R.string.itemedit_custom_field_value_hidden_label)
                        } else {
                            stringResource(R.string.itemedit_custom_field_value_label)
                        },
                        placeholder = stringResource(R.string.itemedit_custom_field_value_placeholder),
                        visualTransformation = if (hidden && !revealed) {
                            PasswordVisualTransformation('•')
                        } else {
                            VisualTransformation.None
                        },
                        // Masking the glyphs is only half of it. Without KeyboardType.Password the IME
                        // treats a hidden custom field — a recovery code, a security answer, an API key —
                        // as ordinary prose: suggestion strip, autocorrect, and personalised learning that
                        // outlives the app entirely. Every other secret input in the app already asks for
                        // this; this one was a plain LkTextField.
                        keyboardOptions = if (hidden) {
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                            )
                        } else {
                            KeyboardOptions.Default
                        },
                        modifier = if (hidden) {
                            Modifier
                                .fillMaxWidth()
                                .secretFieldSemantics(
                                    field.name.ifBlank {
                                        stringResource(R.string.itemedit_custom_field_fallback_name)
                                    },
                                    revealed,
                                    field.value,
                                )
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                }
                if (hidden) {
                    GuardedSecretField { fieldContent() }
                } else {
                    fieldContent()
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Only the two types this editor implements. `BOOLEAN` and `LINKED` are in the
                    // vault format and survive a round trip, but nothing anywhere renders them — the
                    // value stayed a text box either way, so those chips changed the stored type and
                    // nothing a user could see. A 2026-07-26 sweep dismissed this by saying those types
                    // "cannot be created through the UI"; this row is that UI, and it called the setter
                    // unguarded. A field that already carries one — from an imported vault — keeps its
                    // own chip in the row, so the type is still visible and switching away is a choice
                    // rather than the only option.
                    val offered = listOf(CustomFieldType.TEXT, CustomFieldType.HIDDEN, field.type)
                    offered.distinct().forEach { type ->
                        val typeLabel = stringResource(type.chipLabelRes())
                        LkChip(
                            text = typeLabel,
                            selected = type == field.type,
                            onClick = { viewModel.updateCustomFieldType(index, type) },
                            // The visible chip is a bare word — "Text", "Hidden" — sitting between
                            // a name well and a value well, and on its own it says nothing about
                            // what is being chosen (4A-F4, 2026-09-20). `LkChip`'s `toggleable`
                            // already announces the on/off state; this names the question. Same
                            // case its own KDoc describes for the vault list's filter tokens.
                            contentDescription = stringResource(
                                R.string.itemedit_custom_field_type_a11y,
                                typeLabel,
                            ),
                        )
                    }
                }
                val fieldName = field.name.ifBlank { stringResource(R.string.itemedit_custom_field_fallback_name) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hidden) {
                        LkIconButton(
                            icon = if (revealed) LkIcons.EyeOff else LkIcons.Eye,
                            // Named with the field, so a form with four hidden fields does not present
                            // four identical "Show" buttons.
                            label = if (revealed) {
                                stringResource(R.string.itemedit_custom_field_hide_a11y, fieldName)
                            } else {
                                stringResource(R.string.itemedit_custom_field_show_a11y, fieldName)
                            },
                            onClick = { viewModel.toggleCustomFieldRevealed(index) },
                        )
                    }
                    LkIconButton(
                        icon = LkIcons.Trash,
                        label = stringResource(R.string.itemedit_custom_field_remove_a11y, fieldName),
                        tint = c.coral,
                        onClick = { viewModel.removeCustomField(index) },
                    )
                }
            }
            Spacer(Modifier.height(LkSpace.md))
        }
        LkInlineAddButton(
            text = stringResource(R.string.itemedit_custom_field_add_button),
            onClick = viewModel::addCustomField,
        )
    }
}

/**
 * One masked value plus its Show and Copy actions.
 *
 * Takes the [field] key rather than a pre-computed `revealed` flag and an `onToggle` lambda, because
 * both actions have to agree about *which* secret they are acting on: reveal was gated through
 * `toggleFieldRevealed(field)` while Copy, sitting right beside it, put the same value on the
 * clipboard with no prompt at all. Deriving both from one key here leaves no call site free to wire
 * only half the gate.
 */
@Composable
internal fun SecretField(
    value: String,
    onValueChanged: (String) -> Unit,
    label: String,
    field: String,
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    keyboardType: KeyboardType = KeyboardType.Password,
    /** Non-null adds a Generate action beside Show and Copy. See the call in [LoginFields]. */
    onGenerate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Draws the well's error ring, the same signal [Field] gives a plain input. */
    isError: Boolean = false,
    /**
     * False lets a revealed value wrap instead of scrolling out of the well — see
     * [LkPasswordField.singleLine]. Default true, because every ordinary secret wants one line.
     */
    singleLine: Boolean = true,
    /** What the well shows while masked; null is the plain bullet run. See [cardNumberMaskFor]. */
    maskTransformation: VisualTransformation? = null,
    /** What the well shows while revealed; null is the plain value. Same groups as [maskTransformation]. */
    revealedTransformation: VisualTransformation? = null,
) {
    val c = LkTheme.colors
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val revealed = field in state.revealedFields
    // The label as an object inside someone else's sentence — "Copy ___, unlocks first", and the
    // clipboard's own description of what it holds. The "(required)" marker earns its place once,
    // above the well; carried into every derived string TalkBack read "Copy Secret key (required),
    // unlocks first" (2026-07-29 device pass).
    val objectLabel = label.removeSuffix(" (required)")
    // The copy only happens once the ViewModel says this field is cleared — immediately for an
    // ungated field, and after RevealAuthEffect's prompt passes for a gated one.
    LaunchedEffect(state.copyGrantedField) {
        if (state.copyGrantedField == field) {
            ClipboardHelper.copy(context, objectLabel, value)
            viewModel.onCopyPerformed()
        }
    }
    // One full-width well with its controls inside it, which is how the comp draws every secret:
    // `padding:0 96px 0 16px` on a full-width input, the glyphs sitting in that right-hand gutter.
    // They used to be a Row of 44dp buttons *beside* a `weight(1f)` field — see the note on
    // [LkPasswordField.actions] for what that cost, and why the repo's "never inside the well" rule
    // is about buttons that push the well open rather than about this.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        LkFieldLabel(label)
        LkPasswordField(
            value = value,
            onValueChange = onValueChanged,
            label = label,
            revealed = revealed,
            keyboardType = keyboardType,
            isError = isError,
            singleLine = singleLine,
            maskTransformation = maskTransformation,
            revealedTransformation = revealedTransformation,
            // The Done key was wired to an empty handler, so pressing it did nothing at all —
            // the one input in the app where the keyboard's own button was inert. It cannot
            // move focus down (the next focusable is this field's own Show button, not the
            // next field), so it does the other useful thing and puts the keyboard away.
            onImeAction = { focusManager.clearFocus() },
            // Still null, so the component draws no toggle of its own — the eye is the first of the
            // three glyphs below instead. Drawn here rather than there for two reasons: these
            // labels name the object ("Show password", not "Show"), which a card form with four
            // secrets needs; and the component's own toggle carries a filled plate, while the comp
            // draws all three glyphs bare on the well.
            onToggleReveal = null,
            leadingIcon = null,
            modifier = modifier
                .fillMaxWidth()
                // Without this the field reads as a run of bullets: the user learns the
                // character count and not that it is a secret, nor whether it is currently on
                // screen. The read-only detail screen already announced this; the form did
                // not, so the same password behaved differently depending on which screen you
                // met it on.
                .secretFieldSemantics(objectLabel, revealed, value),
        ) {
            LkGlyphButton(
                icon = if (revealed) LkIcons.EyeOff else LkIcons.Eye,
                label = if (revealed) {
                    stringResource(R.string.itemedit_action_hide, objectLabel)
                } else {
                    stringResource(R.string.itemedit_action_show, objectLabel)
                },
                // `revealed` is the state before the toggle, so this fires CONCEAL as it goes away.
                haptic = if (revealed) Haptic.CONCEAL else Haptic.REVEAL,
                onClick = { viewModel.toggleFieldRevealed(field) },
                // 40/20/`sm` on all three, the comp's own `40x40` plate at `border-radius:11px` with
                // one glyph size instead of the 16/14/16 mix that was here. The plate is what closes
                // the gap the principal saw: at 30dp inside a 48dp target each glyph carried 9dp of
                // dead margin either side, so an 8dp arrangement rendered as a 26dp hole.
                size = 40.dp,
                iconSize = 30.dp,
                shape = LkShape.sm,
                // Amber, not jade, once the secret is on screen. `LkColors` reserves jade for
                // "the only colour used for primary action", and a state toggle is not an action -
                // meanwhile Copy directly beside it is jade whenever the field has a value, so a
                // filled-and-revealed password painted reveal and copy the same colour and the row
                // had no hierarchy at the one moment it carried a visible secret. Amber is the
                // whole of what this state is: worth looking at, nothing wrong. Hidden stays
                // `textMuted` - a masked field is not asking for attention.
                tint = if (revealed) c.amber else c.textMuted,
                container = Color.Transparent,
            )
            val needsAuth = !revealed && state.requiresReauthFor(field)
            LkGlyphButton(
                icon = LkIcons.Copy,
                // "Show"/"Copy" alone name no object. A card form has four of each.
                label = if (needsAuth) {
                    stringResource(R.string.itemedit_action_copy_gated, objectLabel)
                } else {
                    stringResource(R.string.itemedit_action_copy, objectLabel)
                },
                // An empty field has nothing to protect, so it raises no prompt and, since the
                // component now plays the signature, no haptic either — a buzz for a tap that does
                // nothing is worse than silence.
                haptic = if (value.isEmpty()) null else if (needsAuth) Haptic.REVEAL else Haptic.COPY,
                onClick = {
                    if (value.isEmpty()) return@LkGlyphButton
                    viewModel.requestCopy(field)
                },
                size = 40.dp,
                iconSize = 30.dp,
                shape = LkShape.sm,
                tint = if (value.isEmpty()) c.textFaint else c.jade,
                container = Color.Transparent,
            )
            // Regenerate is NOT in this row, and its absence is the ruling of the 2026-09-09 UI
            // gate rather than an omission. It is the one trailing control that destroys a value
            // instead of revealing or copying one, and it sat here at 0dp from Copy. The geometry
            // could not be tuned out of that: three 48dp targets need 144dp, and a 20-character
            // generated password already runs to within ~21dp of the cluster, so no arrangement of
            // three controls fits this well. The destructive member left the row instead — see
            // `PasswordStrengthRow`, which carries it on the row below, quieter, and on a different
            // axis from every reversible control. `onGenerate` is still taken here so the call
            // sites that pass it need not change and so this note has somewhere to live.
        }
    }
}

@Composable
internal fun Field(
    value: String,
    onValueChanged: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    BoxedField(
        value,
        onValueChanged,
        label,
        modifier.fillMaxWidth(),
        keyboardType = keyboardType,
        isError = isError,
        visualTransformation = visualTransformation,
    )
}

/**
 * The form's one text input — now a thin alias for [LkFormField], which is the same composable
 * moved into `core/ui` so that onboarding, Settings and the import wizard build a field the same
 * way this screen does. Kept as a private name because the call sites here read better for it.
 */
@Composable
internal fun BoxedField(
    value: String,
    onValueChanged: (String) -> Unit,
    label: String,
    modifier: Modifier,
    singleLine: Boolean = true,
    /**
     * 56dp, not the app-wide 48.
     *
     * `Item Flows.dc.html` draws every input on these screens at `height:56px`, and on a 392px-wide
     * comp that is 56dp. The repo's 48dp default is the *touch floor* and stays the floor
     * everywhere else — onboarding, Settings, the import wizard — but on an item form it left the
     * Username well visibly shorter than the Password well beside it, which carries 48dp glyph
     * targets and so lands on the comp's 56. Two wells of different heights in one column is the
     * thing SS1 does not have.
     */
    minHeight: Dp = 56.dp,
    /** Passed through to [LkFormField]; the note body takes the comp's 20dp block corner. */
    shape: Shape = LkShape.xl,
    maxHeight: Dp = Dp.Unspecified,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    placeholder: String = "",
    labelTrailing: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** See [LkFormField]. Null keeps the default IME key and the default focus-down behaviour. */
    imeAction: ImeAction? = null,
    onImeAction: (() -> Unit)? = null,
) {
    LkFormField(
        value = value,
        onValueChange = onValueChanged,
        label = label,
        placeholder = placeholder,
        modifier = modifier,
        singleLine = singleLine,
        minHeight = minHeight,
        shape = shape,
        maxHeight = maxHeight,
        keyboardType = keyboardType,
        isError = isError,
        labelTrailing = labelTrailing,
        visualTransformation = visualTransformation,
        imeAction = imeAction,
        onImeAction = onImeAction,
    )
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    LkGroup {
        LkCheckRow(
            label = label,
            checked = checked,
            onToggle = { onChecked(!checked) },
            first = true,
        )
    }
}

@Composable
internal fun CardBrand.displayName(): String = when (this) {
    CardBrand.VISA -> stringResource(R.string.itemedit_card_brand_visa)
    CardBrand.MASTERCARD -> stringResource(R.string.itemedit_card_brand_mastercard)
    CardBrand.AMEX -> stringResource(R.string.itemedit_card_brand_amex)
    CardBrand.DISCOVER -> stringResource(R.string.itemedit_card_brand_discover)
    CardBrand.JCB -> stringResource(R.string.itemedit_card_brand_jcb)
    CardBrand.DINERS -> stringResource(R.string.itemedit_card_brand_diners)
    CardBrand.UNIONPAY -> stringResource(R.string.itemedit_card_brand_unionpay)
    CardBrand.MAESTRO -> stringResource(R.string.itemedit_card_brand_maestro)
    CardBrand.UNKNOWN -> ""
}

@Composable
private fun ItemType.displayName(): String = when (this) {
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
 * Whether the form's bottom error banner should scroll itself into view.
 *
 * The banner exists for errors no single field can own — a card expiry the parser refused, a write
 * that failed. When a field is already showing the same refusal with its own ring and caption,
 * revealing the banner too sends the viewport in the opposite direction from the field, and the two
 * competing reveal requests can pin the scroll outright (2026-07-29).
 */
internal fun shouldRevealErrorBanner(
    @androidx.annotation.StringRes error: Int?,
    fieldOwnsError: Boolean,
): Boolean = error != null && !fieldOwnsError

/**
 * The error the form's bottom banner should draw.
 *
 * A refused save over a blank title marks the Title field with a ring and a caption saying the same
 * thing; drawing the banner as well said it twice, a screen apart. The field owns that refusal, so
 * the banner is dropped for it. The identity photo route's naming page has no such field marking
 * and keeps drawing [ItemEditUiState.error] itself.
 */
internal fun errorBannerFor(
    @androidx.annotation.StringRes error: Int?,
    titleError: Boolean,
): Int? = error?.takeUnless { titleError && it == R.string.itemedit_error_title_required }

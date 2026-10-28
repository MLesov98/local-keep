package com.localkeep.feature.unlock

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.PermissionDenial
import com.localkeep.core.ui.PermissionDenialSaver
import com.localkeep.core.ui.QrScannerDialog
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.openAppSettings
import com.localkeep.core.ui.rememberHaptics
import com.localkeep.core.ui.theme.rememberLkWindowTooShortForPinnedChrome
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkActionScaffold
import com.localkeep.core.ui.theme.LkDerivingOverlay
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkLogo
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkPulseHalo
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkChromeUnpinned
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkRing
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.core.vault.UnlockPhase

/**
 * The unlock screen, and the derivation overlay it becomes while Argon2id runs.
 *
 * The overlay is the important half. Deriving the key takes long enough that a spinner reads as a
 * hang, so instead the screen states what is happening and names the parameters doing it. Nothing
 * here is padded or faked: when the work finishes, the screen leaves immediately.
 */
@Composable
fun UnlockScreen(
    viewModel: UnlockViewModel,
    onUnlocked: () -> Unit,
) {
    val c = LkTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val haptics = rememberHaptics()
    // Saveable, not remembered: the passphrase itself lives in the ViewModel and survives a
    // configuration change, so a toggle that did not left the user mid-proof-read of a long
    // passphrase looking at dots again — on the screen they meet more often than any other.
    var revealed by rememberSaveable { mutableStateOf(false) }

    // Both biometric flags were read once in the ViewModel's constructor and then only inside a
    // failure handler, so anything that changed underneath the app was never noticed — a sensor
    // cool-off expiring while the screen sat in the background being the case that was measured
    // (device, 2026-09-20). Re-read on every resume, which is also when a user who went to device
    // settings to enrol a fingerprint comes back.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBiometricAvailability()
        onPauseOrDispose {}
    }

    LaunchedEffect(state.unlocked) {
        if (state.unlocked) {
            haptics.play(Haptic.UNLOCK)
            onUnlocked()
        }
    }
    // Failure gets its own signature (spec §8.2). This screen is the one place a user may be typing
    // a long passphrase while not looking at the screen; a wrong attempt should be felt, not only
    // rendered in red.
    LaunchedEffect(state.error) {
        if (state.error != null) haptics.play(Haptic.ERROR)
    }
    // Recovery-kit entry replaces the password field in place rather than opening a screen, so it
    // left the back gesture pointing at the activity: a user who tapped "Use Recovery Kit" to see
    // what it wanted, then pressed Back to return to the password, closed the app instead. Enabled
    // only in that mode — on the password field itself Back should still leave, since this is the
    // root destination and a locked vault has nothing to protect the user from exiting.
    BackHandler(enabled = state.recoveryVisible) { viewModel.hideRecovery() }

    // The pre-unlock restore flow replaces this screen entirely while it is open (F34). It is a
    // *state*, not a nav route, so nothing that can navigate can reach it — the only way in is the
    // action on `UnlockError.Actionable`, which only `UnlockResult.VAULT_UNREADABLE` produces.
    // Returning here rather than nesting keeps the unlock screen's keyboard-aware scaffold, its
    // deriving overlay and its BackHandler out of the restore screen's way.
    state.restore?.let { restore ->
        BackHandler { viewModel.closeRestore() }
        PreUnlockRestoreScreen(restore, viewModel)
        return
    }

    // Hoisted to this scope for the same reason ItemEditScreen.kt's TotpScanTrigger is: the field
    // it feeds is reachable from two different places below (the pinned action bar on a short
    // window, the scrolling body otherwise — see `shortWindow`), and a permission launcher living
    // inside either one is invisible to the other.
    val recoveryQrTrigger = rememberRecoveryQrScanTrigger(viewModel)
    if (recoveryQrTrigger.showCameraDisclosure) {
        LkDialog(
            onDismiss = recoveryQrTrigger.onDismissDisclosure,
            title = stringResource(R.string.unlock_recovery_qr_camera_dialog_title),
            body = stringResource(R.string.unlock_recovery_qr_camera_dialog_body),
            icon = LkIcons.Camera,
            confirm = LkDialogAction(
                label = stringResource(R.string.unlock_recovery_qr_camera_dialog_continue),
                onClick = recoveryQrTrigger.onConfirmDisclosure,
            ),
        )
    }
    if (state.showRecoveryQrScanner) {
        QrScannerDialog(
            title = stringResource(R.string.unlock_recovery_qr_dialog_title),
            cameraUnavailableMessage = stringResource(R.string.unlock_recovery_qr_camera_unavailable),
            onResult = viewModel::scanRecoveryQrCode,
            onDismiss = viewModel::dismissRecoveryQrScanner,
        )
    }

    val shortWindow = rememberUnlockWindowIsShort()

    val scroll = rememberScrollState()
    // Bring a new error into view, because on this screen it can arrive below the fold.
    //
    // Measured on emulator-5554, 2026-09-13: after a failed unlock with the keyboard still open
    // — which is every failed unlock, since the user has just been typing — the error rendered
    // at the very bottom of the scroll viewport and the `Restore a backup` button under it was
    // clipped away entirely, absent from the `uiautomator` tree. The pinned action bar below
    // still showed Unlock and Use Recovery Kit, so the screen looked complete while the one
    // control the error exists to offer was reachable only by a scroll nothing suggested.
    //
    // Same fix and same reason as `BackupSettingsScreen`'s `LaunchedEffect(state.status,
    // state.error)`: a refusal written off-screen from where the button was pressed is a
    // refusal the user does not get. Scrolls to the end because the error and its action are the
    // last children of this column.
    LaunchedEffect(state.error) {
        if (state.error != null) scroll.animateScrollTo(scroll.maxValue)
    }
    UnlockFrame(
        shortWindow = shortWindow,
        scroll = scroll,
        hero = {
            UnlockHero(
                // Mode-aware, because the section below is: switching to the Recovery
                // Kit swaps the password field for a 24-word grid, and a subtitle still
                // saying "enter your master password" then describes a control that is
                // no longer on the screen. The hero renders above the branch, so it has
                // to read the same flag the branch does.
                subtitle = stringResource(
                    if (state.recoveryVisible) {
                        R.string.unlock_recovery_subtitle
                    } else {
                        R.string.unlock_subtitle
                    },
                ),
                compact = shortWindow,
            )
        },
        entry = { CredentialEntry(state, viewModel, revealed, recoveryQrTrigger) { revealed = it } },
        actions = { UnlockActions(state, viewModel, activity) },
    ) {
        // Stacked over the whole frame (see UnlockFrame), not emitted after it or inside its scroll
        // region.
        //
        // `LkActionScaffold` hands its content slot a `Column`, so an overlay declared as the
        // scroll region's *sibling* in it was measured against whatever height that sibling had
        // left — nothing, it is `fillMaxSize` — and then placed below the bottom edge. It drew
        // zero pixels, on the screen it exists for. What that looked like from the outside:
        // submit cleared the field and dimmed the button for the whole derivation with no
        // indicator of any kind, indistinguishable from a rejection, and the overlay's own
        // "Opening your vault…" line was first legible *during the cross-fade into the vault*
        // — i.e. only after the work it describes had finished. Read frame by frame off
        // `rec/flow01-unlock` at 20 fps, 2026-09-15. The state was never the problem:
        // `unlockWithPassword` sets isWorking, unlockProgress and unlockPhase in the same
        // synchronous `update` as the field clear.
        if (state.isWorking) {
            // unlockPhase is set (READING, at the latest) the same instant isWorking flips true
            // on the password path, and stays null for the whole biometric/Recovery Kit path —
            // see UnlockViewModel's `unlockPassword`/`unlockWithBiometric`/
            // `unlockWithRecoveryKit`. It is therefore the signal for "a real Argon2id
            // derivation is actually running", which `state.kdfSummary` is not: that field is
            // the vault's own KDF params, loaded once at init and populated whichever way the
            // vault gets unlocked.
            val realKdfRunning = state.unlockPhase != null
            LkDerivingOverlay(
                // The "N passes, M MiB" summary describes a derivation that only actually runs
                // on the password path — showing it beside a fingerprint or Recovery Kit prompt
                // claims work that never happened.
                kdfSummary = if (realKdfRunning) resolveKdfSummary(state.kdfSummary) else null,
                title = stringResource(unlockStageTitle(state.unlockPhase)),
                // Null on the biometric and Recovery Kit paths, which report no checkpoints —
                // the overlay falls back to its indeterminate halo there rather than showing a
                // number nothing is driving.
                progress = state.unlockProgress,
                // The Tier G "this wait is the point" rationale is an Argon2id claim; biometric
                // and Recovery Kit unlocks run no KDF, so the claim would be false on those
                // paths.
                showRationale = realKdfRunning,
            )
        }
    }
}

/**
 * The unlock screen's frame: where the masthead, the credential entry and the buttons are placed,
 * and which of them scroll.
 *
 * Slotted rather than inlined so the arrangement itself can be measured. `UnlockScreen` needs a
 * Hilt-built `UnlockViewModel` and therefore a vault, so no test can render it; before this
 * existed the landscape test rebuilt the arrangement by hand, which is how 2A-F16 shipped twice —
 * the copy's pinned block was missing one of the screen's three buttons, so it measured an 84dp
 * scroll region where the device had 28dp, and agreed the masthead fitted. `UnlockHeroFitsTest`
 * now drives this, so a change here is a change under the test.
 *
 * Two arrangements, one threshold ([shortWindow], the vault list's own):
 * - **Tall.** [entry] and the masthead scroll; [actions] are pinned above the keyboard by
 *   [LkActionScaffold]. This is portrait, and it is unchanged.
 * - **Short.** Nothing is pinned. Landscape has about 387dp, and the pinned block alone — the
 *   well, three buttons and the disclaimer — is roughly 330dp of it, which left the masthead a
 *   28dp strip to be sliced across (2A-F16, device, 2026-09-20). One scroll holds all of it
 *   instead. The action slot is still called, empty: `LkActionScaffold` measures it first, so its
 *   `imePadding()` is what shrinks the region above when the keyboard opens and lets it scroll.
 */
@Composable
internal fun UnlockFrame(
    shortWindow: Boolean,
    scroll: ScrollState,
    hero: @Composable () -> Unit,
    entry: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val c = LkTheme.colors
    // The overlay is drawn at THIS level, over the scaffold, not inside its content slot. The slot is
    // only the region above the pinned action bar, so an overlay raised from inside it covered that
    // region and nothing else: at 308dp / 2.0 text the ring was cut at y~523, the caption below it
    // never showed, and Unlock, Use Recovery Kit and the footer were drawn undimmed under a scrim
    // that stopped short of them — with the keyboard never raised (SCL-004 / S-L2). The Box is
    // exactly the scaffold's size, so the overlay's scrim and its swallowed taps cover the pinned
    // block too, and LkDerivingOverlay scrolls inside itself when its column is taller than that.
    Box(Modifier.fillMaxSize()) {
        LkActionScaffold(
            accent = c.jade,
            auraBias = 0.5f,
            auraAlpha = 0.10f,
            // Explicit, and it must match the content column's margin below. Left unset this took the
            // 20dp LkFrame.screenHorizontal default while the content used 30dp, so Unlock and Use
            // Recovery Kit overhung the password field they belong to by 4dp on each side — on the
            // screen the app is met on most often. Found by the visual sweep, 2026-09-01.
            horizontalPadding = LkFrame.heroHorizontal,
            // Pinned rather than the last child of the scroll. This is the screen a user meets most
            // often and the one where the keyboard is always open, so Unlock sitting under it was the
            // worst instance of the pattern in the app.
            action = {
                // Nothing is pinned on a short window — see the KDoc above. The slot is left empty on
                // purpose rather than skipped, because the empty column's `imePadding()` is what
                // reserves the keyboard's height for the scroll region above it.
                //
                // The pinning this replaces was F02's (2026-08-30), whose point was that the well and
                // Unlock stay reachable with the keyboard up. They are, by scroll — which is what the
                // tall window has always done with the well.
                //
                // Nor while the keyboard has left the window too little room (SCL-003): the same rule
                // as a short window, decided once by the scaffold — see [LocalLkChromeUnpinned]. A
                // 548dp window is not "short" by the 480dp test above, but with the keyboard up the
                // pinned well-less block took everything above it, and the field being typed into was
                // off screen with Go still submitting.
                if (!shortWindow && !LocalLkChromeUnpinned.current) {
                    actions()
                }
            },
        ) {
            val actionsInScroll = shortWindow || LocalLkChromeUnpinned.current
            // This was the only screen in the app with no scroll, and it is the one that most needs it.
            // The keyboard takes better than a third of the height, MainActivity's safeDrawingPadding()
            // hands that loss straight to this layout, and a `weight(1f)` region under a shrinking
            // bounded height does not scroll and does not clip — it *crushes*. At display size Large the
            // master-password well measured 42px tall with its label gone entirely, so the caret sat
            // nowhere near where the box appeared to be, which is exactly what it looks like from the
            // outside: typing into a field you cannot see.
            //
            // heightIn(min = viewport) inside the scroll is what keeps the roomy case looking identical:
            // with slack the column is exactly viewport-tall and the arrangement has surplus to
            // distribute, and without slack it simply outgrows the viewport and scrolls. The leading
            // zero-height Spacer is load-bearing — SpaceBetween across three children leaves equal gaps,
            // which centres the middle one the way `Arrangement.Center` in a weighted region used to;
            // across two children it would pin the hero to the top instead.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewport = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .heightIn(min = viewport)
                        .padding(horizontal = LkFrame.heroHorizontal)
                        // 80dp of gutter is a whole landscape screen's worth of air once the screen
                        // itself scrolls here: the short window spends its height on the masthead, the
                        // well and the buttons, not on a margin. Compact on a short window, and the
                        // portrait figures untouched — measured in `UnlockHeroFitsTest`.
                        .padding(
                            top = if (shortWindow) LkSpace.sm else 46.dp,
                            bottom = if (shortWindow) LkSpace.sm else 34.dp,
                        ),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Spacer(Modifier.height(0.dp))
                    Column {
                        hero()
                        Spacer(Modifier.height(if (shortWindow) LkSpace.lg else 34.dp))
                        entry()
                        // The buttons join the scroll on a short window, where there is no pinned bar
                        // to hold them, and while the keyboard is up on a window too short to pin them.
                        if (actionsInScroll) {
                            Spacer(Modifier.height(LkSpace.lg))
                            actions()
                        }
                    }

                    // A trailing zero-height Spacer keeps SpaceBetween distributing across three
                    // children on a tall window, where there is surplus to distribute. Across two it
                    // would pin the hero to the top instead — the same reason the leading one exists.
                    // On a short window the column outgrows the viewport and there is no surplus, so
                    // both are inert.
                    Spacer(Modifier.height(0.dp))
                }
            }
        }
        overlay()
    }
}

/**
 * The unlock screen's buttons, pinned above the keyboard by [LkActionScaffold].
 *
 * Extracted from the scrolling column purely so it can be handed to the action slot; the order and
 * the reasoning behind it are unchanged.
 */
/**
 * The unlock screen's own masthead: the mark, "Welcome back", and the line that says what to do.
 *
 * [compact] is the landscape / short-window arrangement. Landscape is the *wide* orientation and
 * the short one, so the mark moves beside the heading rather than above it and takes the smaller of
 * the two mark sizes — 61dp of masthead instead of 155dp, in a row that has width the portrait
 * column never had. Nothing is dropped and nothing is truncated.
 *
 * It is a saving, not a fit: what makes the masthead whole on a short window is that the screen
 * scrolls as one there (see the empty action slot in [UnlockScreen]). Compact on its own was tried
 * first and was not enough — against a pinned well and three pinned buttons the region left for the
 * masthead measured 28dp on the device, and 61dp still sliced across it (2A-F16, 2026-09-20).
 * Measured in `UnlockHeroFitsTest`.
 */
@Composable
internal fun UnlockHero(subtitle: String, compact: Boolean) {
    val c = LkTheme.colors
    val heading: @Composable () -> Unit = {
        Text(
            stringResource(R.string.unlock_welcome_title),
            // The unlock screen builds its own header rather than using LkScreenHeader, so it
            // lost the heading that component supplies — on the most-visited screen in the app,
            // TalkBack's navigate-by-heading gesture had no stop at all (W9, 2026-09-12). Same
            // defect and same fix as the item detail screen (V20).
            modifier = Modifier.semantics { heading() },
            style = LkText.title,
            color = c.textStrong,
        )
    }
    val body: @Composable () -> Unit = {
        Text(subtitle, style = LkText.body, color = c.textMuted)
    }
    if (compact) {
        Column(
            Modifier.lkEnter(delayMillis = 50),
            verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LkSpace.md),
            ) {
                LkLogo(size = 32.dp, filled = false)
                heading()
            }
            body()
        }
    } else {
        Column(
            Modifier.lkEnter(delayMillis = 50),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LkLogo(size = 52.dp, filled = false)
            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                heading()
                body()
            }
        }
    }
}

@Composable
private fun UnlockActions(
    state: UnlockUiState,
    viewModel: UnlockViewModel,
    activity: androidx.fragment.app.FragmentActivity?,
) {
    val c = LkTheme.colors
    Column(
        Modifier.lkEnter(delayMillis = 200),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (state.recoveryVisible) {
            // The checksum runs once per completed phrase, not on every recomposition.
            val complete = remember(state.recoveryWords) { recoveryKitIsComplete(state.recoveryWords) }
            // A full 24 words that still fail the checksum (N-01): the counter above already reads
            // "24 of 24 words", so the a11y label below can no longer say "available once all 24
            // words are entered" — that is now false — and has to name the checksum instead.
            val checksumInvalid = remember(state.recoveryWords) {
                recoveryKitChecksumInvalid(state.recoveryWords)
            }
            // And past 24 words, where the label used to keep saying "available once all 24 words
            // are entered" to a reader who had entered thirty-two (V3-01).
            val tooManyWords = remember(state.recoveryWords) {
                recoveryKitTooManyWords(state.recoveryWords)
            }
            // Pinned here rather than left under the field, where it belongs by adjacency and is
            // invisible by geometry: the counter is the one thing on this screen that is worth
            // reading *while typing*, and the keyboard covers exactly the band it sat in. Measured
            // on emulator-5554, 2026-09-15 — at three words the `3 of 24 words` node was absent
            // from the `uiautomator` tree with the IME up and present the moment it came down.
            RecoveryWordProblem(state.recoveryWords)
            RecoveryWordCount(state.recoveryWords, checksumInvalid)
            val openLabel = stringResource(R.string.unlock_recovery_open_button)
            LkPrimaryButton(
                text = openLabel,
                onClick = viewModel::unlockWithRecoveryKit,
                // Non-blank was the old gate, so three words were enough to submit — and the answer
                // was "Those words didn't open this vault. Check the order and the spelling", which
                // describes a transcription error nobody had made. Each of those attempts also
                // counted as a wrong guess and cost real unlock backoff, on the screen reached by
                // someone who has already lost their password. A partial phrase cannot open
                // anything, so there is nothing to try.
                enabled = !state.isWorking && complete,
                // Sighted users have the counter directly above; a screen reader gets neither that
                // adjacency nor the dimming, so the reason is said out loud — the same treatment
                // onboarding's Continue gives its own inert state.
                modifier = Modifier.labelled(
                    when {
                        complete -> openLabel
                        checksumInvalid -> stringResource(
                            R.string.unlock_recovery_open_button_a11y_checksum_invalid,
                        )
                        tooManyWords -> stringResource(
                            R.string.unlock_recovery_open_button_a11y_too_many,
                        )
                        else -> stringResource(R.string.unlock_recovery_open_button_a11y_incomplete)
                    },
                ),
            )
            LkSecondaryButton(
                text = stringResource(R.string.unlock_back_to_password),
                onClick = viewModel::hideRecovery,
                enabled = !state.isWorking,
            )
            return@Column
        }
        LkPrimaryButton(
            text = stringResource(R.string.unlock_button),
            onClick = viewModel::unlockWithPassword,
            enabled = !state.isWorking && state.password.isNotEmpty(),
            modifier = Modifier
                .lkTag("unlock-submit")
                .labelled(
                    when {
                        state.isWorking -> stringResource(R.string.unlock_button_a11y_working)
                        state.password.isEmpty() -> stringResource(R.string.unlock_button_a11y_needs_password)
                        else -> stringResource(R.string.unlock_button)
                    },
                ),
        )
        // Low in the stack, under the everyday controls: this is the door you use once,
        // after something has gone wrong. But present, and reachable without help — a
        // recovery route the user has to search for is one they will not find on the day
        // they need it.
        LkSecondaryButton(
            text = stringResource(R.string.unlock_use_recovery_kit_button),
            icon = LkIcons.RecoveryKit,
            onClick = viewModel::showRecovery,
            enabled = !state.isWorking,
        )
        if (state.biometricAvailable && activity != null) {
            LkSecondaryButton(
                text = stringResource(R.string.unlock_biometric_button),
                icon = LkIcons.Fingerprint,
                onClick = { viewModel.unlockWithBiometric(activity) },
                enabled = !state.isWorking,
            )
        } else if (state.biometricBlockedByPanicLock) {
            // Explained rather than silently absent: a button that just vanished would read
            // as a bug instead of the state the user asked for. Unreachable until
            // 2026-09-20 — see [UnlockViewModel.biometricBlockedByPanicLock].
            Text(
                stringResource(R.string.unlock_biometric_blocked_notice),
                style = LkText.caption,
                color = c.textFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Text(
            // Was "No account. No cloud. Nothing leaves this device." — an unconditional literal
            // that the app cannot keep: Android Auto Backup takes `vault.enc` unless the user
            // turned it off, and this string never consulted that setting. The first two clauses
            // are structurally true (there is no account and no sync server); the third was not.
            // Same defect the previous wave fixed in three other places, still here on the one
            // screen every user sees at every unlock, and found by reading it off the glass.
            //
            // The replacement third clause was wrong too, for two more waves: "Your vault opens
            // only with your password" sat directly under the Recovery Kit button below and the
            // biometric button next to it, both of which open the real vault with no password.
            // This comment asserted the first two clauses and never argued the third, which is
            // how it survived a reading. Scoped to the master password 2026-08-31 — that one
            // really does stay here — and the string is now Tier G, so TierGClaimsTest guards
            // the exclusivity phrasing from returning in any of the five languages.
            stringResource(R.string.unlock_footer_disclaimer),
            style = LkText.caption,
            color = c.textFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        )
    }
}

/**
 * Entering a Recovery Kit.
 *
 * One free-text field rather than twenty-four boxes. The words are being copied off a printed page
 * or read aloud from one, and a grid of numbered inputs turns that into twenty-four separate
 * keyboard focus events with no way to paste — measurably worse for the exact person using it, who
 * has already lost their password and does not need a second obstacle.
 *
 * Not masked either. Nobody can proof-read twenty-four words against paper through bullets, and a
 * transcription error is the likeliest reason this screen fails. Shoulder-surfing is a real risk
 * and it loses to that one — the user chose this door deliberately and can look around first.
 */
@Composable
private fun RecoveryKitEntry(
    state: UnlockUiState,
    viewModel: UnlockViewModel,
    recoveryQrTrigger: RecoveryQrScanTrigger,
) {
    val c = LkTheme.colors
    val context = LocalContext.current
    // A heading over a block — the instructions and then the well — rather than a caption on one
    // field, so it keeps section volume while every real field label got quieter.
    //
    // The heading and its instructions are one child of the caller's `spacedBy(sm)` column rather
    // than two, because [LkSectionLabel] owns the gap below it (LkSectionLabelGap) and the
    // arrangement would otherwise be added to that padding instead of supplying it. Same value as
    // it renders today; this decouples them, it does not restyle anything.
    Column {
        LkSectionLabel(stringResource(R.string.unlock_recovery_kit_label))
        Text(
            stringResource(R.string.unlock_recovery_instructions),
            style = LkText.caption,
            color = c.textMuted,
        )
    }
    // The QR the Recovery Kit export screen draws (RecoveryKit.formatForQrCode) was, until
    // RecoveryKit.parseQrCode was wired to this button, unreadable by anything: decoration next to
    // the word grid. Above the field rather than below it — this is the faster of the two ways in,
    // and the field stays for whoever cannot use the camera or would rather type.
    LkSecondaryButton(
        text = stringResource(R.string.unlock_recovery_scan_button),
        icon = LkIcons.Camera,
        onClick = recoveryQrTrigger.openScanner,
        enabled = !state.isWorking,
        modifier = Modifier.fillMaxWidth(),
    )
    recoveryQrTrigger.cameraDenial?.let { denial ->
        LkInlineError(
            message = denial.message,
            modifier = Modifier.announce(denial.message),
            actionLabel = denial.settingsActionLabel,
            onAction = denial.settingsActionLabel?.let { { context.openAppSettings() } },
        )
    }
    LkTextField(
        value = state.recoveryWords,
        onValueChange = viewModel::onRecoveryWordsChanged,
        label = stringResource(R.string.unlock_recovery_words_label),
        placeholder = stringResource(R.string.unlock_recovery_words_placeholder),
        singleLine = false,
        minHeight = 132.dp,
        isError = state.error.namesTheField(),
        keyboardOptions = KeyboardOptions(
            // No autocorrect and no capitalisation: a keyboard "helpfully" retyping a BIP-39 word
            // into an ordinary English one is a failure the user cannot see and cannot explain.
            //
            // `autoCorrectEnabled = false` alone does not achieve that. Compose maps it to the
            // absence of TYPE_TEXT_FLAG_AUTO_CORRECT, which Gboard treats as a hint and ignores:
            // measured 2026-09-14 on API 28, "excite" (a BIP-39 word) became "excited" on the
            // space that followed it, and the kit was refused. The password keyboard variation is
            // the one every IME honours — no correction, no suggestions, and no learning of the
            // words into the keyboard's personal dictionary, which for a recovery phrase is the
            // more important half. The transformation stays None, so the words remain visible;
            // only the keyboard's behaviour changes.
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Default,
        ),
        visualTransformation = VisualTransformation.None,
    )
    // The counter is not here any more — it moved into the pinned action bar, which is the only
    // band of this screen the keyboard never covers. See [RecoveryWordCount].
    AnimatedVisibility(
        visible = state.error != null,
        enter = fadeIn(lkTween(LkMotion.Base)),
        exit = fadeOut(lkTween(LkMotion.Quick)),
    ) {
        UnlockErrorArea(state.error, onAction = viewModel::openRestore)
    }
    // The checksum and too-many-words messages are not here: they sit in the pinned action bar
    // beside the counter, because the IME covers this band for the whole of the only task the
    // screen has. See [RecoveryWordProblem].
}

/**
 * Puts focus on [requester] each time [requests] goes up, and never for a value it was born with.
 *
 * Declining the biometric prompt ("Use master password") closes it with focus nowhere, so the user
 * who just asked for the password well had to tap it before typing. The count is compared against a
 * saved copy so a rotation, which re-runs this composition with the same count, does not pop the
 * keyboard up again. Only composed where the requester is attached: requesting focus on a
 * requester no field holds throws.
 */
@Composable
internal fun FocusOnRequest(requests: Int, requester: FocusRequester) {
    var handled by rememberSaveable { mutableIntStateOf(requests) }
    LaunchedEffect(requests) {
        if (requests > handled) requester.requestFocus()
        handled = requests
    }
}

/**
 * The sentence that says why a full-looking phrase will not open, in the pinned action bar.
 *
 * It used to render inside the scroll, under the field. With the keyboard up (and it is up for the
 * whole of this task) that band is exactly what the IME covers, so a 24-word phrase that fails its
 * checksum showed "24 of 24 words" in amber above a button that stayed dead, and the sentence
 * naming the problem was somewhere the user could not see. The counter already moved here for the
 * same reason; the explanation belongs next to it.
 *
 * Both predicates only count words and run one checksum; neither derives a key. They are mutually
 * exclusive (the checksum one is false at any count but exactly 24).
 */
@Composable
internal fun RecoveryWordProblem(words: String) {
    val checksumInvalid = remember(words) { recoveryKitChecksumInvalid(words) }
    val tooManyWords = remember(words) { recoveryKitTooManyWords(words) }
    val message = when {
        checksumInvalid -> stringResource(R.string.unlock_recovery_checksum_invalid_message)
        tooManyWords -> stringResource(R.string.unlock_recovery_too_many_words_message)
        else -> return
    }
    LkInlineError(message = message, modifier = Modifier.announce(message).lkTag("unlock-recovery-word-problem"))
}

/**
 * "N of 24 words", above the keyboard.
 *
 * It used to sit under the field, which is where it reads best and where it cannot be read at all:
 * the IME is open for the whole of the only task this screen has, and it covers the field's own
 * caption band. So the counter existed exactly when it was useless and appeared the moment it
 * stopped mattering (F-02). Here it rides the pinned action strip, directly above the button it
 * describes, and `LkActionScaffold` gives that strip `imePadding()`.
 */
@Composable
private fun RecoveryWordCount(words: String, checksumInvalid: Boolean) {
    val c = LkTheme.colors
    val count = recoveryWordCount(words)
    val text = if (count == 0) {
        stringResource(R.string.unlock_recovery_word_count_empty)
    } else {
        pluralStringResource(R.plurals.unlock_recovery_word_count, count, count)
    }
    Text(
        text,
        style = LkText.caption,
        color = when (recoveryCountTone(count, checksumInvalid)) {
            RecoveryCountTone.COMPLETE -> c.jade
            RecoveryCountTone.CHECKSUM_INVALID -> c.amber
            RecoveryCountTone.TOO_MANY -> c.amber
            RecoveryCountTone.COUNTING -> c.textFaint
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().announce(text),
    )
}

/**
 * Everything the Recovery Kit's "Scan Recovery Kit QR" tap needs: the permission check, the
 * rationale dialog, and the denial notice — mirrors `TotpScanTrigger` in
 * `ItemEditScreen.kt`, which solved the identical problem for the TOTP scanner. A second copy
 * rather than a shared one because the two live in different modules with no dependency between
 * them, and the whole of each is a dozen lines wired to one permission string apiece.
 */
private data class RecoveryQrScanTrigger(
    val openScanner: () -> Unit,
    val cameraDenial: PermissionDenial?,
    val showCameraDisclosure: Boolean,
    val onDismissDisclosure: () -> Unit,
    val onConfirmDisclosure: () -> Unit,
)

@Composable
private fun rememberRecoveryQrScanTrigger(viewModel: UnlockViewModel): RecoveryQrScanTrigger {
    val context = LocalContext.current
    // Saveable for the same reason ItemEditScreen.kt's disclosure/denial state is: losing either
    // to a rotation drops the user back at a button that appears to have done nothing.
    var showCameraDisclosure by rememberSaveable { mutableStateOf(false) }
    var cameraDenial by rememberSaveable(stateSaver = PermissionDenialSaver) {
        mutableStateOf<PermissionDenial?>(null)
    }
    val cameraDenialSentence = stringResource(R.string.unlock_recovery_qr_camera_denial_sentence)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraDenial = null
            viewModel.showRecoveryQrScanner()
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
        // Rationale before the system dialog when we do not already hold the permission — landing
        // a bare OS prompt on someone who just lost their master password and reached for the
        // Recovery Kit gives them no way to know why the camera is being asked for.
        if (granted) viewModel.showRecoveryQrScanner() else showCameraDisclosure = true
    }
    return RecoveryQrScanTrigger(
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
 * Resolves an [UnlockError] to display text. Kept in this file rather than the ViewModel because
 * `pluralStringResource` is only callable from a composable — see the type's own doc comment.
 */
@Composable
private fun resolveUnlockError(error: UnlockError?): String? = when (error) {
    null -> null
    is UnlockError.Simple -> stringResource(error.messageRes)
    is UnlockError.RetryAfter -> pluralStringResource(error.pluralsRes, error.seconds, error.seconds)
    // The message half only. The action half is a button, drawn by [UnlockErrorArea] beside it —
    // an error whose remedy is a sentence the user cannot act on is the defect this variant exists
    // to close (F34).
    is UnlockError.Actionable -> stringResource(error.messageRes)
}

/**
 * The inline error, plus the one control that can fix it when the error carries one.
 *
 * A single place, because both call sites below — the Recovery Kit pane and the password pane —
 * need identical behaviour and the password pane is the one a `VAULT_UNREADABLE` actually lands on.
 */
@Composable
private fun UnlockErrorArea(error: UnlockError?, onAction: () -> Unit) {
    val message = resolveUnlockError(error) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        LkInlineError(message, Modifier.announce(message))
        if (error is UnlockError.Actionable) {
            LkSecondaryButton(
                text = stringResource(error.actionRes),
                onClick = onAction,
                icon = LkIcons.History,
                modifier = Modifier.fillMaxWidth().lkTag("unlock-error-action"),
            )
        }
    }
}

/**
 * The sentence over the ring, named for the operation that is running at that moment.
 *
 * Only the labels change; the count-up underneath is driven by [UnlockUiState.unlockProgress], which
 * steps on real checkpoints. See `UnlockProgress` in `:core:vault` for why each checkpoint is safe
 * to report — the short version is that everything below OPENING fires unconditionally, so the
 * sequence a wrong password produces is indistinguishable from a right one's.
 */
@androidx.annotation.StringRes
private fun unlockStageTitle(phase: UnlockPhase?): Int = when (phase) {
    UnlockPhase.READING -> com.localkeep.core.ui.R.string.core_ui_deriving_reading
    UnlockPhase.DECRYPTING -> com.localkeep.core.ui.R.string.core_ui_deriving_decrypting
    UnlockPhase.OPENING -> com.localkeep.core.ui.R.string.core_ui_deriving_opening
    // Also the null case, which is where the biometric and Recovery Kit paths land: "Deriving your
    // key" is true of them too, and it is the overlay's own default.
    UnlockPhase.DERIVING, null -> com.localkeep.core.ui.R.string.core_ui_deriving_default_title
}

/** Resolves an [UnlockKdfSummary] into the sentence [LkDerivingOverlay] shows under the spinner. */
@Composable
private fun resolveKdfSummary(summary: UnlockKdfSummary?): String? {
    if (summary == null) return null
    val passesPhrase = pluralStringResource(R.plurals.unlock_kdf_passes, summary.passes, summary.passes)
    return stringResource(R.string.unlock_kdf_summary, passesPhrase, summary.memoryMib)
}

/**
 * Whether [UnlockFrame] takes its short arrangement — everything in one scroll, nothing pinned.
 *
 * Under 480dp tall, the threshold the vault list uses, so the app agrees with itself about what
 * "short" is. Or short for its text size ([rememberLkWindowTooShortForPinnedChrome]): on a 308x548
 * window at font 1.5 the pinned Unlock, Use Recovery Kit and footer left the scroll region room for
 * the masthead alone, and the master-password well sat below its fold with nothing on screen saying
 * it scrolled — a lock screen showing two buttons and no field (device sweep, 2026-10-28).
 */
@Composable
internal fun rememberUnlockWindowIsShort(): Boolean =
    LocalConfiguration.current.screenHeightDp < 480 || rememberLkWindowTooShortForPinnedChrome()

/**
 * The credential the screen exists to take: the password well, or the Recovery Kit grid.
 *
 * Extracted so it can be composed in either region. On an ordinary window it sits under the hero
 * inside the scroll, where it has always been; on a short one it moves into the pinned action bar
 * with the buttons, because that is the only place on a 140dp scroll region where it is reachable
 * without a blind gesture. See the `action` slot for the measurement.
 */
@Composable
private fun CredentialEntry(
    state: UnlockUiState,
    viewModel: UnlockViewModel,
    revealed: Boolean,
    recoveryQrTrigger: RecoveryQrScanTrigger,
    onRevealedChange: (Boolean) -> Unit,
) {
    Column(
        Modifier.lkEnter(delayMillis = 130),
        // LkSpace.sm, not a hand-picked number — see G12: this well, Settings' two and onboarding's
        // are the same control on the screens where the master password is typed, and they sat at
        // 12 dp, 7 dp, 7 dp and 8 dp. Onboarding was the one already on the token.
        verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        if (state.recoveryVisible) {
            // Wrapped so this branch owns its rhythm instead of inheriting the password well's.
            // `RecoveryKitEntry` emits six top-level children straight into the parent, so G12's
            // change to the label-to-well gap moved the whole Recovery-Kit layout with it, in a
            // shared branch the change was never aimed at (V24, 2026-09-12). Same value as it
            // renders today -- this decouples them, it does not restyle anything.
            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                RecoveryKitEntry(state, viewModel, recoveryQrTrigger)
            }
            return@Column
        }
        LkFieldLabel(stringResource(R.string.unlock_password_label))
        val passwordFocus = remember { FocusRequester() }
        FocusOnRequest(state.passwordFocusRequests, passwordFocus)
        LkPasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChanged,
            placeholder = stringResource(R.string.unlock_password_placeholder),
            label = stringResource(R.string.unlock_password_label),
            revealed = revealed,
            onToggleReveal = { onRevealedChange(!revealed) },
            showCount = true,
            isError = state.error.namesTheField(),
            imeAction = ImeAction.Go,
            onImeAction = viewModel::unlockWithPassword,
            modifier = Modifier.focusRequester(passwordFocus).lkTag("unlock-password"),
        )
        // Announced. Without a live region the button appears to do nothing at all: focus stays in
        // the password field and the reason is rendered somewhere the user never visits.
        AnimatedVisibility(
            visible = state.error != null,
            enter = fadeIn(lkTween(LkMotion.Base)) + scaleIn(lkTween(LkMotion.Base, easing = LkMotion.Emphasized), 0.94f),
            exit = fadeOut(lkTween(LkMotion.Quick)),
        ) {
            UnlockErrorArea(state.error, onAction = viewModel::openRestore)
        }
    }
}

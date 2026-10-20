package com.localkeep.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.rememberExternalPickerLauncher
import com.localkeep.core.ui.theme.LkCard
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkFootnote
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkEnter

/**
 * Make a Recovery Kit for a vault that already exists.
 *
 * This is the screen that makes deferring the kit during first run an honest offer rather than a
 * trapdoor, and it is also the only way to replace the kit that a master-password change silently
 * voids.
 *
 * Nothing is shown until the user asks. Opening a settings row should not paint twenty-four words
 * that unlock the vault onto a screen someone may be standing behind.
 */
@Composable
fun RecoveryKitScreen(
    viewModel: RecoveryKitViewModel,
    onBack: () -> Unit,
) {
    val c = LkTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    // The gate's biometric door needs a FragmentActivity: BiometricPrompt hosts itself in the
    // fragment manager. `activity` above is the plain Activity, kept for the config-change check below.
    val fragmentActivity = activity as? FragmentActivity
    // Paired with the lock manager: browsing for a folder backgrounds the app, and on Aggressive
    // the vault relocked before the PDF could be written.
    val createPdf = rememberExternalPickerLauncher(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
        onPickerOpened = viewModel::onPickerOpened,
        onPickerClosed = viewModel::onPickerClosed,
    ) { uri -> uri?.let(viewModel::exportPdf) }

    // The QR is drawn inline and the button that opened it is disabled while it is up, so back
    // closes it before it moves anywhere — the same interception `OnboardingViewModel.back` makes on
    // the twin step. Without it the only ways out of the card were "I captured the QR code", which
    // commits a kit the user never saved, and leaving the screen, which forgets the 24 words.
    BackHandler(enabled = state.qrVisible) { viewModel.hideQr() }

    // Leaving takes the words with it. They are re-derivable at any time from the session key, so
    // holding them after the screen is gone buys nothing and costs an extra copy in memory.
    //
    // A configuration change — rotation, dark mode, language switch — also disposes this
    // composable and is, from inside composition, indistinguishable from a real navigate-away.
    // But it is not a departure: the freshly-generated words are still on screen a moment later,
    // and forgetting them here would silently swap them for a different, unrecoverable 24 words on
    // the next `createKit`. `isChangingConfigurations` is the one signal that tells the two apart,
    // and it is only true on the way out, so it is read at disposal.
    DisposableEffect(Unit) {
        onDispose {
            if (shouldForgetRecoveryKit(activity?.isChangingConfigurations == true)) {
                viewModel.forget()
            }
        }
    }

    LkScreen(accent = c.jade, auraAlpha = 0.07f, auraBias = 0.4f) {
        // Two children rather than one scrolling column, so the way out can sit below the scroll
        // instead of at the end of it. Not `LkActionScaffold`: its action strip is unconditional and
        // costs its own padding even when empty, which would take a band off the bottom of the gate
        // and create screens that have no action to pin. Nothing changes here until the kit is
        // saved — a Column whose only child is `weight(1f)` lays that child out exactly where
        // `fillMaxSize` did.
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // The shell keeps the keyboard out of its own padding, so this is where the
                    // viewport shrinks for it. Without it the gate's vault-password well was typed
                    // into from under the keyboard on a short window or a large font:
                    // `LkTextField`'s bring-into-view had nothing to scroll because the viewport
                    // never got shorter. Outside the scroll so it is the scroller's height that
                    // gives, not its content.
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LkFrame.screenHorizontal)
                    .padding(top = LkFrame.screenTop, bottom = LkFrame.gutterPlain),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LkScreenHeader(
                    title = stringResource(R.string.onboarding_recoverykit_title),
                    subtitle = stringResource(R.string.onboarding_recoverykit_subtitle),
                    modifier = Modifier.lkEnter(),
                    trailing = {
                        LkIconButton(
                            icon = LkIcons.ChevronLeft,
                            label = stringResource(R.string.onboarding_recoverykit_back_label),
                            onClick = onBack,
                            plateAlignment = Alignment.CenterEnd,
                        )
                    },
                )

                if (!state.authorized) {
                    // Nothing about the kit composes until the challenge passes — not the explanation,
                    // not the Create button, and above all not the words. Fail-closed by construction
                    // rather than by a disabled control, which is a suggestion, not a gate.
                    RecoveryKitGate(
                        biometricAvailable = state.biometricAvailable && fragmentActivity != null,
                        working = state.isWorking,
                        onBiometric = { fragmentActivity?.let(viewModel::authorizeWithBiometric) },
                        onSubmit = viewModel::authorize,
                    )
                } else if (state.mnemonic.isBlank()) {
                    Text(
                        stringResource(R.string.onboarding_recoverykit_empty_body),
                        style = LkText.bodyQuiet,
                        color = c.textMuted,
                        modifier = Modifier.lkEnter(delayMillis = 40),
                    )
                    LkPrimaryButton(
                        text = stringResource(
                            if (state.isWorking) {
                                R.string.onboarding_recoverykit_working
                            } else {
                                R.string.onboarding_recoverykit_create_cta
                            },
                        ),
                        onClick = viewModel::createKit,
                        enabled = !state.isWorking,
                        modifier = Modifier.lkEnter(delayMillis = 80),
                    )
                } else {
                    // "Show QR code instead" — and instead means instead. The card used to be
                    // *appended* below the 24 words, so both were on screen at once and the only
                    // thing the control did was lengthen the scroll (3A-F34). Nothing is
                    // regenerated by the swap: the words come back exactly as they were when the
                    // card is dismissed, which Back does.
                    val qr = if (state.qrVisible) {
                        remember(state.qrData) {
                            RecoveryKitExport.qrBitmap(state.qrData, QR_SIZE_PX)
                        }
                    } else {
                        null
                    }
                    // A QR that failed to render replaces nothing. Hiding the 24 words behind a
                    // message about a missing image would take away the one thing the user came
                    // for, in the one state where the alternative is already broken.
                    if (mnemonicGridVisible(qrVisible = state.qrVisible, qrRendered = qr != null)) {
                        MnemonicGrid(state.mnemonic)
                    }

                    if (state.qrVisible) {
                        if (qr == null) {
                            val error = stringResource(R.string.onboarding_recovery_qr_render_error)
                            LkInlineError(error, Modifier.announce(error))
                        } else {
                            LkCard(padding = 16.dp) {
                                // Bounded by the SHORTER screen dimension, not by width alone. A QR
                                // is square, so `fillMaxWidth` in landscape asked for an image taller
                                // than the display: it pushed "I captured the QR code" off the bottom
                                // of a card that does not scroll, leaving the only way to confirm the
                                // capture unreachable on the orientation people naturally use to
                                // photograph something with a second device.
                                QrImage(qr.asImageBitmap())
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.onboarding_recovery_qr_instruction),
                                    style = LkText.caption,
                                    color = c.textMuted,
                                )
                                Spacer(Modifier.height(12.dp))
                                LkSecondaryButton(
                                    text = stringResource(R.string.onboarding_recovery_qr_captured_cta),
                                    onClick = viewModel::confirmQrCaptured,
                                    height = 46.dp,
                                )
                            }
                        }
                    }

                    val pdfFileName = stringResource(viewModel.suggestedPdfNameRes())
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        LkSecondaryButton(
                            text = stringResource(R.string.onboarding_recovery_save_pdf),
                            icon = LkIcons.Export,
                            onClick = { createPdf(pdfFileName) },
                            enabled = !state.isWorking,
                        )
                        LkSecondaryButton(
                            text = stringResource(R.string.onboarding_recovery_show_qr),
                            onClick = viewModel::showQr,
                            enabled = !state.isWorking && !state.qrVisible,
                        )
                    }

                    // The replacement is the loud part. Someone who made a kit, saved it, and comes
                    // back to look at it will be handed *different* words, and the sheet in their
                    // drawer is now scrap. Saying so is the difference between a revocation and a
                    // surprise.
                    LkFootnote(stringResource(R.string.onboarding_recoverykit_replace_footnote))
                }

                state.notice?.let {
                    val notice = stringResource(it)
                    // Success notices must be announced too: a status flip the user is waiting on
                    // (PDF exported, QR captured) is silent to a screen reader as plain
                    // Text, while the error directly below it announces — failures spoken, successes
                    // swallowed. QA 2026-08-06, B8.
                    Text(notice, style = LkText.caption, color = c.jade, modifier = Modifier.announce(notice))
                }
                state.error?.let {
                    val message = stringResource(it)
                    LkInlineError(message, Modifier.announce(message))
                }
                // Mutually exclusive with state.error — see RecoveryKitUiState.throttleSeconds.
                // Reports how long the gate's throttle lasts, the way the unlock screen and
                // Settings already do for the same VaultLockedException.
                state.throttleSeconds?.let { seconds ->
                    val message = pluralStringResource(
                        R.plurals.onboarding_recoverykit_gate_error_throttled,
                        seconds,
                        seconds,
                    )
                    LkInlineError(message, Modifier.announce(message))
                }
            }

            // The way out, once the kit is genuinely somewhere the user can reach it.
            RecoveryKitDoneBar(exported = state.exported, onDone = onBack)
        }
    }
}

/**
 * The way forward, once the kit is actually saved.
 *
 * Without it this screen was a dead end: after the last save path succeeded it said "Recovery Kit
 * captured. Verify the photo is readable before continuing." and then offered nothing to continue
 * with. The only exit was the header's Back chevron, which reads identically before and after the
 * save — so nothing on screen distinguished "saved, you are finished" from "you have not saved
 * yet", on the one screen whose entire purpose is producing a durable artefact the user must be
 * told they now have. Found on a device, 2026-08-31.
 *
 * Gated on [exported] alone, the same fact onboarding's own Recovery step gates its Continue on
 * (`OnboardingUiState.recoveryStepReady`, which is `recoveryExported` and nothing else). Only
 * [RecoveryKitViewModel.markSaved] sets it, and only after `commitPreparedRecoveryKit` returned
 * true — so this button appearing is a statement that a kit exists, not that words were shown.
 *
 * Absent rather than disabled before then. A dimmed button below three save buttons would be one
 * more control to reason about on a screen that already has the Back chevron; nothing to press is
 * the honest rendering of "there is nothing to confirm yet", and it is what keeps this distinct
 * from Back rather than a second copy of it.
 *
 * Below the scroll, not at the end of it. The content above is a 24-word grid, an optional QR card,
 * three save buttons and a footnote, which on a phone runs well past the fold — a confirmation the
 * user has to scroll back down to is the same dead end one gesture further away.
 */
@Composable
internal fun RecoveryKitDoneBar(
    exported: Boolean,
    onDone: () -> Unit,
) {
    if (!exported) return
    Column(
        Modifier
            .fillMaxWidth()
            // Opaque, so the scrolling content passes behind the strip rather than through it.
            .background(LkTheme.colors.ground)
            // The app is edge to edge (see MainActivity), and this strip is now what sits at the
            // bottom of the window — so it owes the gesture bar the inset the scrolling column
            // above it used to cover with LkFrame.gutterPlain. Same treatment as LkActionScaffold.
            .navigationBarsPadding()
            .padding(horizontal = LkFrame.screenHorizontal)
            .padding(top = LkSpace.sm, bottom = LkSpace.md),
    ) {
        LkPrimaryButton(
            text = stringResource(R.string.onboarding_recoverykit_done_cta),
            onClick = onDone,
        )
    }
}

/**
 * Whether the unsaved recovery kit should be forgotten on disposal of [RecoveryKitScreen]. False
 * for an Activity recreation ([isChangingConfigurations] true) — the composable is about to be
 * re-entered with the same [RecoveryKitViewModel], so the words are still needed. True for a
 * genuine navigate-away, where the copy in memory should not outlive the screen.
 */
internal fun shouldForgetRecoveryKit(isChangingConfigurations: Boolean): Boolean =
    !isChangingConfigurations

/**
 * Whether the 24-word grid is on screen.
 *
 * "Show QR code instead" used to *append* the QR card below the words rather than replace them, on
 * both this screen and the onboarding step that shares its copy: with the card up, the tree still
 * held `RECOVERY WORDS` and all 24 words above it, so nothing was shown "instead" and the control's
 * only effect was to make the page longer (3A-F34, device 2026-09-20).
 *
 * [qrRendered] is the half that is not obvious. A QR that failed to encode replaces nothing — the
 * grid stays, with the error beside it — because the alternative is to take the words away and
 * leave the user with a message about a missing image, on the one screen where the words are the
 * whole point.
 *
 * Pulled out of the composable so the rule can be pinned without a `RecoveryKitViewModel`, a
 * `VaultLockManager` or a `Context`, in the same idiom as [shouldForgetRecoveryKit] above.
 */
internal fun mnemonicGridVisible(qrVisible: Boolean, qrRendered: Boolean): Boolean =
    !(qrVisible && qrRendered)

/**
 * The challenge in front of minting a Recovery Kit.
 *
 * Strong biometric or the vault password — the same pair the invisible-items gate offers, and the
 * label says "vault password" rather than "master password" because these words are shown in a
 * duress session too, where the real master password is not what is being asked for. See
 * [RecoveryKitViewModel] for why the gate exists and why the biometric door is strong-only.
 *
 * Wrapped in its own Column because it emits several children into a `spacedBy` parent, where a
 * bare `Spacer` would be spaced as well and read as a double gap.
 */
/**
 * Which body the gate shows: the shared one invites biometrics, so it is only true while the
 * biometric door is actually on screen.
 */
@androidx.annotation.StringRes
internal fun recoveryKitGateBody(biometricAvailable: Boolean): Int =
    if (biometricAvailable) {
        R.string.onboarding_recoverykit_gate_body
    } else {
        R.string.onboarding_recoverykit_gate_body_password_only
    }

@Composable
private fun RecoveryKitGate(
    biometricAvailable: Boolean,
    working: Boolean,
    onBiometric: () -> Unit,
    onSubmit: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    // Plain `remember`, never `rememberSaveable`: saved state is written to disk by the system, and
    // a half-typed vault password does not belong there. Cleared on dispose so it does not outlive
    // the screen even in memory (the String itself cannot be wiped, and this does not pretend to).
    DisposableEffect(Unit) { onDispose { password = "" } }

    fun submit() {
        if (working || password.isBlank()) return
        // Copied out and the field cleared before the call: the lock manager consumes and wipes the
        // array, and leaving the String behind would keep the secret on screen.
        val chars = password.toCharArray()
        password = ""
        onSubmit(chars)
    }

    Column(
        modifier = Modifier.lkEnter(delayMillis = 40),
        verticalArrangement = Arrangement.spacedBy(LkSpace.md),
    ) {
        LkEmptyState(
            icon = LkIcons.Lock,
            title = stringResource(R.string.onboarding_recoverykit_gate_title),
            body = stringResource(recoveryKitGateBody(biometricAvailable)),
        )
        if (biometricAvailable) {
            LkSecondaryButton(
                text = stringResource(R.string.onboarding_recoverykit_gate_biometric),
                icon = LkIcons.Fingerprint,
                onClick = onBiometric,
                enabled = !working,
            )
        }
        // The label is drawn, not only spoken. `LkPasswordField`'s own `label` sets a semantics
        // name and nothing visible, so on its own it renders an unnamed box — which is what this
        // gate was, while its sibling gate one screen away had a label. Wrapped in its own Column
        // because two children in a `spacedBy` parent would otherwise be spaced apart as siblings.
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            LkFieldLabel(stringResource(R.string.onboarding_recoverykit_gate_password_label))
            LkPasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.onboarding_recoverykit_gate_password_label),
                revealed = revealed,
                onToggleReveal = { revealed = !revealed },
                onImeAction = { submit() },
            )
        }
        // The label carries the working state, because nothing else on this gate does. Deriving the
        // vault key is an Argon2id run measured in seconds, and a disabled primary button renders
        // hollow — identical to the button before a password has been typed. Same treatment as the
        // create-kit button one branch up.
        LkPrimaryButton(
            text = stringResource(
                if (working) {
                    R.string.onboarding_recoverykit_working
                } else {
                    R.string.onboarding_recoverykit_gate_submit
                },
            ),
            enabled = !working && password.isNotBlank(),
            onClick = { submit() },
        )
    }
}

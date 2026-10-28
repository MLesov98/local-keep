package com.localkeep.feature.onboarding

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.FlowRow
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.rememberExternalPickerLauncher
import com.localkeep.core.ui.theme.LkButtonAccent
import com.localkeep.core.ui.theme.LkCard
import com.localkeep.core.ui.theme.LkDerivingOverlay
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFootnote
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkSegmentMeter
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkStepRail
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkWordmarkText
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LkTintButton
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.theme.lkBrandedText
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkFocusRing
import com.localkeep.core.ui.theme.lkRing
import com.localkeep.core.ui.theme.lkScrollEdges
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.core.ui.theme.rememberLkKeyboardUnpinsChrome
import com.localkeep.core.ui.labelled

/**
 * First run (§3.2): one screen, one decision.
 *
 * Every step shares [StepColumn], which supplies the progress rail, the heading pair and the
 * bottom-anchored action block. That frame is what makes seven separate decisions feel like one
 * sequence — and it is why the Recovery Kit step, which is by far the most consequential, does not
 * need to shout to be taken seriously: it looks exactly as deliberate as the others.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: (OnboardingUiState.Finish) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finish) {
        state.finish?.let { finish ->
            onFinished(finish)
            // The wizard opens over this screen rather than replacing it, so Back returns here.
            if (finish == OnboardingUiState.Finish.OPEN_IMPORT) viewModel.importHandedOff()
        }
    }
    // This is the root destination, so anything the screen does not take goes to the activity. On
    // the welcome step that is the right answer — nothing has been created yet and leaving costs
    // nothing. Everywhere else it would end setup mid-ceremony.
    BackHandler(enabled = state.step != OnboardingUiState.Step.WELCOME) { viewModel.back() }
    // Before the vault exists, whether the biometric step will appear is answered by the device
    // alone — a secure lock screen and a strong sensor — and the ViewModel asks that in its init,
    // so the rail can count with the real answer from the first screen. Afterwards it is
    // biometricAvailable, which additionally requires the new vault to have unlocked.
    //
    // This used to assume "probably yes" until creation, on the reasoning that an over-count
    // corrects better than an under-count. It does not: on a device with no enrolled sensor the
    // rail read "2 of 7" and then "3 of 6" (device pass, 2026-07-29). A total that revises itself
    // is a progress indicator moving its own goalposts, which is worse than none.
    val biometricStepShown = biometricStepShown(
        step = state.step,
        possibleOnDevice = state.biometricPossibleOnDevice,
        available = state.biometricAvailable,
    )
    // Same rule for the migration step, which chooseCloudBackup skips outright when the vault did
    // not open. "Probably" before creation, the real answer afterwards.
    val migrateStepShown = state.step <= OnboardingUiState.Step.CREATE_PASSWORD ||
        state.migrateAvailable
    CompositionLocalProvider(
        LocalBiometricStepShown provides biometricStepShown,
        LocalMigrateStepShown provides migrateStepShown,
    ) {
        when (state.step) {
            OnboardingUiState.Step.WELCOME -> WelcomeStep(viewModel)
            OnboardingUiState.Step.CREATE_PASSWORD -> PasswordStep(state, viewModel)
            OnboardingUiState.Step.ENABLE_BIOMETRIC -> BiometricStep(state, viewModel)
            OnboardingUiState.Step.RECOVERY_KIT -> RecoveryStep(state, viewModel)
            OnboardingUiState.Step.CLOUD_BACKUP -> CloudBackupStep(state, viewModel)
            OnboardingUiState.Step.MIGRATE -> MigrateStep(state, viewModel)
            OnboardingUiState.Step.DONE -> DoneStep(state, viewModel)
        }
    }
    // Creating a vault costs exactly what opening one costs, and the unlock screen has covered that
    // wait with an overlay since it was written — this screen tracked `isWorking` and drew nothing
    // for it. Measured on the emulator: ~80 seconds of Argon2 during which both password fields
    // blank themselves (correct zeroisation) and nothing else changes, which reads as a failure.
    ReleaseFocusWhileWorking(state.isWorking)
    if (state.isWorking) {
        val busy = onboardingBusyOverlay(state.step)
        LkDerivingOverlay(
            title = stringResource(busy.title),
            showRationale = busy.showRationale,
        )
    }
}

/**
 * Drops focus and the keyboard when a long wait begins.
 *
 * The keyboard's own Go key submits the form, and the confirm field keeps focus through the
 * submit, so "Creating your vault" and its explanation of why the wait is long were drawn under an
 * IME that covered the lower half of the screen. Nothing on the screen is editable while it waits.
 */
@Composable
internal fun ReleaseFocusWhileWorking(isWorking: Boolean) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(isWorking) {
        if (isWorking) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
}

/** What the busy overlay says: its title, and whether the Argon2id "this wait is the point" copy applies. */
internal data class OnboardingBusyOverlay(@androidx.annotation.StringRes val title: Int, val showRationale: Boolean)

/**
 * Picks the overlay for the step that raised `isWorking`, because the flag is shared by three
 * unlike waits. Only creating the vault runs Argon2id — and it does so while [OnboardingUiState.step]
 * is still CREATE_PASSWORD, including the unlock that follows it; the step advances in the same
 * update that clears the flag. Enabling biometric unlock is a Keystore key plus one AES-GCM wrap,
 * and exporting the Recovery Kit is a PDF write: both happen after the vault exists, so naming them
 * "Creating your vault" is false and the "same cost an attacker pays for every guess" rationale
 * (Tier G) describes work that is not running. `UnlockScreen` withholds it the same way. Any other
 * step never raises the flag, so it keeps the creation overlay rather than inventing a fourth.
 */
internal fun onboardingBusyOverlay(step: OnboardingUiState.Step): OnboardingBusyOverlay = when (step) {
    OnboardingUiState.Step.ENABLE_BIOMETRIC -> OnboardingBusyOverlay(
        R.string.onboarding_screen_busy_biometric_title,
        showRationale = false,
    )
    OnboardingUiState.Step.RECOVERY_KIT -> OnboardingBusyOverlay(
        R.string.onboarding_screen_busy_recovery_title,
        showRationale = false,
    )
    else -> OnboardingBusyOverlay(R.string.onboarding_screen_deriving_title, showRationale = true)
}

/**
 * The brand moment. The only screen in the app with no chrome at all.
 *
 * The one step in the flow [DoneStep] already got this treatment for: `heightIn(min = viewport)`
 * inside a scrolling `Column` so `Arrangement.Center` still has slack to distribute on the screens
 * where everything already fits, and only gives way once it does not. Nothing here was device-
 * measured overflowing off a real English string — at fontScale 2.0 on a 411dp window there is
 * still ~180-240dp of headroom under the CTA. What is measured is that the margin is
 * language-dependent (the French tagline+body run to 192 chars against the English 116,
 * `values-fr/strings.xml:13`) and narrows further on the smallest supported window (308dp), and
 * that this was the only step still laid out in a fixed-height Column while every sibling scrolls.
 * Consistency with a real, if currently unhit, failure mode — not a reproduced clip.
 */
@Composable
private fun WelcomeStep(viewModel: OnboardingViewModel) {
    val c = LkTheme.colors
    LkScreen(accent = c.jade, auraAlpha = 0.12f, auraBias = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = LkFrame.heroHorizontal)
                .padding(top = 46.dp, bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val scroll = rememberScrollState()
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .lkScrollEdges(
                        moreAbove = scroll.canScrollBackward,
                        moreBelow = scroll.canScrollForward,
                        color = c.ground,
                    ),
            ) {
                val viewport = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .heightIn(min = viewport),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.lkEnter(travel = 16.dp)) {
                        Icon(
                            LkIcons.Shield,
                            contentDescription = null,
                            tint = c.jade,
                            modifier = Modifier.size(70.dp),
                        )
                    }
                    Spacer(Modifier.height(34.dp))
                    LkWordmarkText(modifier = Modifier.lkEnter(delayMillis = 120))
                    Spacer(Modifier.height(13.dp))
                    Text(
                        stringResource(R.string.onboarding_welcome_tagline),
                        style = LkText.bodyQuiet,
                        color = c.textFaint,
                        modifier = Modifier.lkEnter(delayMillis = 200),
                    )
                    Spacer(Modifier.height(30.dp))
                    Text(
                        stringResource(R.string.onboarding_welcome_body),
                        style = LkText.body,
                        color = c.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.lkEnter(delayMillis = 260),
                    )
                }
            }
            LkPrimaryButton(
                text = stringResource(R.string.onboarding_welcome_cta),
                onClick = viewModel::beginSetup,
                modifier = Modifier.lkEnter(delayMillis = 320),
            )
        }
    }
}

@Composable
private fun PasswordStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
) {
    val c = LkTheme.colors
    val matches = state.confirmationMatches
    val weak = state.isWeakPassword
    // Weak is allowed — see OnboardingUiState.weakAccepted — so the only things that can hold the
    // button back are an unfinished form and an unread warning. Derived on the state rather than
    // here, so this button and createVault cannot disagree about what "ready" means.
    val ready = state.readyToCreate

    StepColumn(
        step = state.step,
        title = stringResource(R.string.onboarding_password_title),
        body = stringResource(R.string.onboarding_password_body),
        actions = {
            val continueLabel = stringResource(R.string.onboarding_action_continue)
            LkPrimaryButton(
                // One label. The old one changed between four strings as the user typed, which put
                // the verdict on their password inside the button they were reaching for — the
                // control moved out from under them semantically while staying still visually. The
                // meter and the mismatch line say those things where they belong.
                text = continueLabel,
                onClick = viewModel::createVault,
                enabled = ready,
                // The visible label stays put; only the spoken one carries the reason. Same
                // treatment, and the same argument, as the Recovery Kit step's Continue below:
                // dimming and adjacency are sighted-only cues, so a screen reader that lands here
                // before the meter or the mismatch line would otherwise hear "Continue, dimmed"
                // and nothing about what to fix.
                modifier = Modifier.labelled(
                    state.continueDisabledReason?.let { stringResource(it) } ?: continueLabel,
                ),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    LkIcons.Shield,
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                )
                Text(
                    stringResource(R.string.onboarding_password_recovery_kit_promise),
                    style = LkText.caption,
                    color = c.textFaint,
                )
            }
        },
    ) {
        // The suggestion sits *above* the field, beside its label, because it is an alternative to
        // typing — offered before the user starts, not after they have already finished and been
        // graded. It used to sit below the strength verdict, which is the one place it could not
        // do its job.
        // Bottom-aligned, not centred. "Suggest one" needs a 48dp touch target, which makes this
        // row 48dp tall while the label inside it is 13dp — and centring left the label floating in
        // the middle, 45dp above its own well against 10dp for the Confirm field directly below
        // (measured 2026-07-29). Two adjacent fields, two different label offsets, for no reason
        // the user can see. Aligned to the bottom the label sits where every other label in the app
        // sits, and the link's tap area simply extends upward into space the heading was not using.
        // Explicit, not `focusManager.moveFocus(Next)`. Verified on device 2026-07-28: relying on
        // Compose's default Next action here dismisses the keyboard and lands focus nowhere, so the
        // user's next keystrokes go into no field at all — worse than the empty handler it replaced,
        // because it looks like it worked. The strength line appearing between the two fields
        // changes the layout mid-interaction, which is the likeliest reason the default traversal
        // finds no target.
        val confirmFocus = remember { FocusRequester() }
        // Label and well wrapped together, so their gap is LkSpace.sm — the same gap LkFormField
        // gives every other labelled field — instead of the outer form's field-to-field rhythm.
        // Left as two top-level siblings, the label sat 23dp above its own well against Confirm's
        // 8dp one field down: two adjacent labels reading as different components. The 2026-07-29
        // bottom-alignment fix above addressed the *centring* half of this; the structural half is
        // this wrapper. LkFormField itself cannot be reused here — it only builds LkTextField, and
        // this is a masked field with a label-trailing link.
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            PasswordLabelRow(onSuggest = viewModel::suggestPassphrase)
            LkPasswordField(
                value = state.password,
                onValueChange = viewModel::onPasswordChanged,
                // No placeholder: the label above already names the field, and there is nothing to
                // add to "MASTER PASSWORD" that a hint could say. Confirm below *does* take one
                // ("Type it once more") because the instruction is not in its label.
                //
                // The second half of this reason used to be "and a masked field renders its
                // placeholder in the secret style, which is prose nobody asked for." That stopped
                // being true: `LkPasswordField` passes `placeholderStyle = LkText.body` — "Prose,
                // always — never the secret style" — so both wells would render a hint in ordinary
                // text. The asymmetry is a choice about what each field needs to say, not a
                // limitation of the component.
                label = stringResource(R.string.onboarding_password_field_label),
                revealed = state.passwordVisible,
                onToggleReveal = viewModel::togglePasswordVisible,
                // A suggested passphrase wraps; a typed one does not. `generatePassphrase` returns
                // six words — about 52 characters — and the single-line well showed 25 of them and
                // stopped at the field edge with no ellipsis, no fade and no Copy control, directly
                // above `onboarding_password_notice_suggested`: "Write this down before continuing —
                // it is your master password." Confirm is deliberately absent on this path
                // (`confirmationRequired`), so nothing downstream would have caught the half the
                // user could not see. The Generator screen has always drawn the same generator's
                // output in a wrapping Text; this is that, in the one place it mattered most.
                singleLine = !state.passwordWasGenerated,
                // Both of these follow whether Confirm exists. `confirmFocus` is attached to a field
                // that is not composed when the password was generated, and requesting focus on an
                // unattached FocusRequester throws — so Next has to become Done in exactly the case
                // where there is nothing to advance to.
                imeAction = if (state.confirmationRequired) ImeAction.Next else ImeAction.Done,
                onImeAction = {
                    if (state.confirmationRequired) confirmFocus.requestFocus() else viewModel.createVault()
                },
            )
        }

        // Compact, and directly under the field it judges. As a full card it pushed Confirm most of
        // a screen away, so the two halves of one decision were never visible together.
        if (state.password.isNotEmpty()) {
            StrengthLine(score = state.strengthScore, feedback = state.strengthFeedback)
        }

        // Absent entirely for a suggested passphrase. Confirmation catches a typo in something
        // typed from memory; a suggestion was neither typed nor memorised — it is on screen and
        // revealed. suggestPassphrase already filled this field, so what stood here was a full,
        // unrevealable box asking the user to confirm a value they never entered. See
        // OnboardingUiState.passwordWasGenerated.
        if (state.confirmationRequired) {
            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                LkFieldLabel(stringResource(R.string.onboarding_password_confirm_label))
                LkPasswordField(
                    value = state.confirmation,
                    onValueChange = viewModel::onConfirmationChanged,
                    modifier = Modifier.focusRequester(confirmFocus),
                    placeholder = stringResource(R.string.onboarding_password_confirm_placeholder),
                    label = stringResource(R.string.onboarding_password_confirm_field_label),
                    // Never revealable: the point of the second field is that it is typed from
                    // memory, and showing it turns the check into a copy-from-the-screen exercise.
                    revealed = false,
                    isError = !matches,
                    onImeAction = viewModel::createVault,
                )
                if (!matches) {
                    Text(
                        stringResource(R.string.onboarding_password_mismatch),
                        style = LkText.caption,
                        color = c.coralText,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }

        if (weak) {
            WeakPasswordConsent(
                accepted = state.weakAccepted,
                onToggle = { viewModel.setWeakAccepted(!state.weakAccepted) },
            )
        }

        NoticeText(state.notice)
        ErrorText(state.error)
    }
}

/**
 * The master-password field's label with "Suggest one" beside it, or beneath it where both do not
 * fit. `internal`, with tag modifiers, so `PasswordLabelAndSuggestGapTest` measures this row and not
 * a copy of it.
 */
@Composable
internal fun PasswordLabelRow(
    onSuggest: () -> Unit,
    labelModifier: Modifier = Modifier,
    linkModifier: Modifier = Modifier,
) {
    // A flow row: the link sits beside the label where both fit and drops beneath it where
    // they do not. It was a Row with the label weighted, which kept the two apart (2A-F4,
    // the link flush against the label at 2.0) but made the *label* pay for the room: at
    // 308dp and font 2.0 it came out as "MAST / ER P…", broken inside a word and cut, on
    // the one field the whole of onboarding exists to fill (final sweep, 2026-10-28). The
    // label's end padding is the gap 2A-F4 asked for; SpaceBetween adds none of its own.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        LkFieldLabel(
            stringResource(R.string.onboarding_password_field_label),
            // Max intrinsic, or the flow row breaks on the label's longest word and keeps
            // the link beside a label squeezed to it.
            modifier = labelModifier.width(IntrinsicSize.Max).padding(end = LkSpace.md),
            maxLines = 2,
        )
        SuggestLink(onClick = onSuggest, modifier = linkModifier)
    }
}

/**
 * "Suggest a passphrase", as a link beside the field label rather than a full-width button.
 *
 * Sized to the 48dp target floor even though the text is small — the tap area is the padded box,
 * not the glyphs.
 *
 * `internal` rather than private for the same reason [StepColumn] is: what it measures beside the
 * field label at a large font scale is the whole of `PasswordLabelAndSuggestGapTest`, and that
 * cannot be driven through `PasswordStep`, which needs a vault.
 */
@Composable
internal fun SuggestLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(LkShape.sm)
            .lkFocusRing(LkShape.sm, accent = c.brass)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .minTouchTarget()
            .padding(horizontal = 8.dp),
        // Bottom, not centre: the 48dp target is mostly empty space above a 13dp line, and centring
        // the ink in it floated "Suggest one" ~17dp above the label it shares a row with. The
        // target keeps its size; its text rests on the label's line.
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(LkIcons.Dice, contentDescription = null, tint = c.brass, modifier = Modifier.size(14.dp))
        Text(
            stringResource(R.string.onboarding_password_suggest_one),
            style = LkText.buttonSmall,
            color = c.brass,
            modifier = Modifier.lkTag("suggest-one-text"),
        )
    }
}

/**
 * The strength readout, in one line plus advice.
 *
 * Shows the *specific* advice zxcvbn produced rather than a generic "weak" — "add another word" is
 * actionable, a red bar is not. There is deliberately no "time to crack" figure: zxcvbn's own
 * estimate depends on assumptions about the attacker that this screen has no room to state, and an
 * unqualified "3 years" reads as a promise.
 */
@Composable
private fun StrengthLine(score: Int, feedback: String) {
    val c = LkTheme.colors
    val label = stringResource(strengthLabel(score))
    val tone by animateColorAsState(
        targetValue = when {
            score >= 3 -> c.jade
            score == 2 -> c.amber
            else -> c.coral
        },
        animationSpec = lkTween(LkMotion.Base),
        label = "strength-tone",
    )
    // zxcvbn's own advice (feedback) is library output in English only — the templates below are
    // what's translated, not the advice text they carry.
    val summary = if (feedback.isBlank()) label else stringResource(R.string.onboarding_password_strength_summary, label, feedback)
    val summaryDescription = if (feedback.isBlank()) {
        label
    } else {
        stringResource(R.string.onboarding_password_strength_summary_description, label, feedback)
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        LkSegmentMeter(level = strengthMeterLevel(score), tone = tone, segments = STRENGTH_SEGMENTS)
        // Announced politely so the verdict reaches screen-reader users without interrupting them
        // mid-word. The description carries the specific advice too, because the two together are
        // the whole message — the label alone ("Weak") names no remedy.
        Text(
            summary,
            style = LkText.caption,
            color = c.textMuted,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = summaryDescription
            },
        )
    }
}

/**
 * The one thing standing between a weak master password and the vault: an explicit acknowledgement.
 *
 * Not a dialog. A dialog raised at the moment someone taps Continue is dismissed reflexively, and
 * would make this read as an obstacle to get past rather than a fact to take in. Inline, next to the
 * field, it is part of the decision.
 *
 * The wording names the actual mechanism rather than scolding. "Weak password" means nothing to
 * someone who has typed weak passwords into websites for twenty years without consequence — the
 * thing they do not know is that this file has no server in front of it.
 */
@Composable
private fun WeakPasswordConsent(accepted: Boolean, onToggle: () -> Unit) {
    ConsentRow(
        accepted = accepted,
        onToggle = onToggle,
        title = stringResource(R.string.onboarding_password_weak_consent_title),
        body = stringResource(R.string.onboarding_password_weak_consent_body),
    )
}

/**
 * An inline acknowledgement: a fact the user has to take in, and a tick that says they did.
 *
 * The shape both places need. Not a dialog — a dialog raised at the moment someone taps the primary
 * button is dismissed reflexively, and turns the fact into an obstacle to get past rather than part
 * of the decision. Amber rather than coral: nothing has gone wrong, and the user is allowed to
 * proceed.
 */
@Composable
private fun ConsentRow(
    accepted: Boolean,
    onToggle: () -> Unit,
    title: String,
    /**
     * Null where the row is pinned below a scrolling step.
     *
     * A pinned row takes its height straight out of the scrolling region, so a body that grows with
     * translation shrinks the content it sits under. On the Recovery Kit step that cost every one of
     * the 24 words: at `en-XA` (+40%) the row reached 531px of a 1465px content area and left a card
     * labelled RECOVERY WORDS with nothing visible inside it. The decision stays pinned; the prose
     * that explains it rides in the scroll, immediately above.
     */
    body: String?,
) {
    val c = LkTheme.colors
    val box by animateColorAsState(
        if (accepted) c.amber else Color.Transparent,
        lkTween(LkMotion.Quick),
        label = "weak-consent-box",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LkShape.md)
            .lkFocusRing(LkShape.md, accent = c.amber)
            .lkSurface(LkShape.md, c.amber.copy(alpha = 0.08f), c.amber.copy(alpha = 0.22f))
            .toggleable(
                value = accepted,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .lkSurface(LkShape.xs, box, if (accepted) c.amber else c.controlIdle),
            contentAlignment = Alignment.Center,
        ) {
            if (accepted) {
                Icon(
                    LkIcons.Check,
                    contentDescription = null,
                    tint = c.ground,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = LkText.buttonSmall, color = c.amber)
            if (body != null) {
                Text(body, style = LkText.caption, color = c.textMuted)
            }
        }
    }
}

private const val STRENGTH_SEGMENTS = 4

@androidx.annotation.StringRes
private fun strengthLabel(score: Int): Int = when (score) {
    0 -> R.string.onboarding_password_strength_guessed_instantly
    1 -> R.string.onboarding_password_strength_guessed_seconds
    2 -> R.string.onboarding_password_strength_guessed_hours
    3 -> R.string.onboarding_password_strength_strong_enough
    else -> R.string.onboarding_password_strength_very_strong
}

@Composable
private fun BiometricStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
) {
    val c = LkTheme.colors
    // BiometricPrompt is hosted by a FragmentActivity; offer the control only where one exists.
    val activity = LocalContext.current.findActivity() as? FragmentActivity

    StepColumn(
        step = state.step,
        title = stringResource(R.string.onboarding_biometric_title),
        body = stringResource(R.string.onboarding_biometric_body),
        actions = {
            if (state.biometricEnabled) {
                LkPrimaryButton(text = stringResource(R.string.onboarding_action_continue), onClick = viewModel::skipBiometric)
            } else {
                LkPrimaryButton(
                    text = stringResource(R.string.onboarding_biometric_enable_cta),
                    icon = LkIcons.Fingerprint,
                    onClick = { activity?.let(viewModel::enableBiometric) },
                    enabled = !state.isWorking && activity != null,
                )
                LkTextButton(text = stringResource(R.string.onboarding_biometric_not_now), onClick = viewModel::skipBiometric)
            }
        },
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                LkIcons.Fingerprint,
                contentDescription = null,
                tint = c.jade,
                modifier = Modifier.size(58.dp).lkEnter(),
            )
        }
        NoticeText(state.notice)
        ErrorText(state.error)
    }
}

/**
 * Holds the display on for as long as the calling step is composed.
 *
 * 2026-10-02 (O3-04): every screen-off locks the vault, and a lock pops onboarding. The Recovery
 * Kit step is the one where a user sits still for minutes — copying 24 words by hand, or fetching a
 * second phone for the QR — so the display's own timeout (30 s is a common default) ended first run
 * mid-transcription, with a kit that can never be shown again. The idle auto-lock still applies, so
 * an abandoned phone still locks; only the shorter display timeout stops cutting the step short.
 * `View.keepScreenOn` is released by the platform when the view detaches, so nothing can leak it.
 */
@Composable
internal fun KeepScreenOnWhileShown() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun RecoveryStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
) {
    val c = LkTheme.colors
    KeepScreenOnWhileShown()
    // Paired with the lock manager: browsing for a folder backgrounds the app, and on Aggressive
    // the vault relocked before the PDF could be written.
    val createPdf = rememberExternalPickerLauncher(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
        onPickerOpened = viewModel::onPickerOpened,
        onPickerClosed = viewModel::onPickerClosed,
    ) { uri -> uri?.let(viewModel::exportRecoveryPdf) }

    StepColumn(
        step = state.step,
        title = stringResource(R.string.onboarding_recovery_title),
        // "your vault never leaves this device" was the reason given here for the password being
        // unresettable, and it was not true: Android Auto Backup takes vault.enc by default, and
        // the toggle that opts out was three screens deep in Settings and never mentioned. The
        // reason is now what actually makes it true — nobody else holds the key — and the backup
        // decision itself is the very next step, where the user can make the stronger claim true.
        body = stringResource(R.string.onboarding_recovery_body),
        actions = {
            // The kit must actually be saved before this opens (UI/UX spec §3.3). The two save
            // buttons below each set recoveryExported, so either one is the way forward.
            val ready = state.recoveryStepReady
            val continueLabel = stringResource(R.string.onboarding_action_continue)
            LkPrimaryButton(
                // "Continue" in both states. A primary button's label is the verb for what it
                // does; the disabled reason is said out loud for screen readers below, and the
                // three save actions are right there, so the button restates neither.
                text = continueLabel,
                onClick = viewModel::confirmRecoverySaved,
                enabled = ready && !state.isWorking,
                // Sighted users see a dimmed button next to the row that un-dims it. A screen
                // reader gets neither adjacency nor dimming, so the reason is said out loud —
                // the same treatment the folder-name button gives its own inert state.
                modifier = Modifier.labelled(
                    if (ready) continueLabel else stringResource(R.string.onboarding_recovery_continue_disabled_label),
                ),
            )
            // In `actions`, beside Continue — not down in the scrolling content with the three save
            // buttons. `actions` is pinned outside the scroll, and the content above it is a 24-word
            // monospace grid plus an optional QR card, which on a phone runs well past the fold: a
            // way out that has to be scrolled to is not a way out for the person most likely to want
            // it. This is what makes it genuinely the same affordance as the Biometric step's
            // "Not now" rather than merely the same widget.
            LkTextButton(
                text = stringResource(R.string.onboarding_recovery_defer),
                onClick = viewModel::deferRecoveryKit,
            )
        },
        // What makes the paragraph above true on this step. `StepColumn` otherwise un-pins the
        // action block as soon as the content overflows, which on exactly this step it does — and
        // then the deferral link the comment places here "because `actions` is pinned outside the
        // scroll" is at the bottom of the scroll, behind everything it was put here to sit in front
        // of. 4-1.
        alwaysPinActions = true,
    ) {
        // The ways to keep the kit come first, directly under the warning — the grid scrolls
        // below them. Principal's call, 2026-09-16, closing F-49.
        //
        // The grid used to be first, and it is 24 rows tall: on a 1080x2400 phone the fold landed
        // inside `Save as PDF`, which was drawn as a half-height sliver against the pinned Continue
        // strip with `lkScrollEdges`' fade over it, and the other save buttons were below the fold
        // entirely (`shots/195-onboarding-3.png`, 2026-09-15). Nothing was actually unreachable — a
        // scroll brought them all up (`shots/196`) — but the frame the step opens on is what the
        // user reads, and it showed a butchered control where the step's whole purpose is.
        //
        // Print was dropped from this step on 2026-10-13 (principal's call): PDF and QR cover it,
        // and a fourth screen is too early to ask anyone to find a printer. Settings → Recovery
        // Kit still offers it.
        //
        // Ordering by purpose rather than by narrative: the words are a thing to *photograph or
        // file*, not a thing to read down. Whoever wants to check them scrolls, which is a
        // deliberate act; whoever wants to save them is offered it without one.
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
                onClick = viewModel::showRecoveryQr,
                enabled = !state.isWorking && !state.qrVisible,
            )
        }

        // The deferral lives in `actions` above, pinned beside Continue.

        // Stays with the buttons, wherever they go: it is the caveat on pressing them ("some print
        // or share targets upload to the cloud"), and a caveat that outruns its control is not one.
        LkFootnote(stringResource(R.string.onboarding_recovery_footnote))

        // Also with the buttons, and for the same reason: every notice and every error this step
        // can raise is the *result* of pressing one of the two above — a written PDF, a captured
        // QR, or one of those failing. Left at the foot of the step they would now be a screen's
        // worth of scrolling away from the button that caused them, behind the 24-word grid.
        NoticeText(state.notice)
        ErrorText(state.error)

        // Same swap as the standalone screen, for the same reason and through the same rule — see
        // [mnemonicGridVisible]. The two screens share the "Show QR code instead" string, so
        // fixing one and not the other would leave the claim false on whichever was left.
        val qr = if (state.qrVisible) {
            remember(state.recoveryQrData) {
                RecoveryKitExport.qrBitmap(state.recoveryQrData, QR_SIZE_PX)
            }
        } else {
            null
        }
        if (state.qrVisible) {
            if (qr == null) {
                LkInlineError(stringResource(R.string.onboarding_recovery_qr_render_error))
            } else {
                // Scrolled fully into view the moment it is drawn, with a margin.
                //
                // The card appeared at a scroll offset the user was nowhere near — it sat below a
                // 24-word grid — so `lkScrollEdges`' fade fell across the code's cut edge and the
                // screen opened on a QR a scanner could not lock on, saying nothing about it
                // (F-50). The reorder above shortens that gap but does not close it: the card is
                // taller than the room left under three buttons and a footnote, so on a phone it
                // still lands part-way past the fold.
                //
                // The requested rectangle is the card grown by `LkSpace.xl` top and bottom rather
                // than the card itself, because `bringIntoView` is satisfied by a flush edge — and
                // flush is exactly where the 28dp fade is painted. One rung above it, so the
                // clearance survives the fade at every density.
                val qrReveal = remember { BringIntoViewRequester() }
                var qrCardSize by remember { mutableStateOf(IntSize.Zero) }
                val clearance = with(LocalDensity.current) { LkSpace.xl.toPx() }
                LaunchedEffect(qrCardSize) {
                    if (qrCardSize.height > 0) {
                        runCatching { qrReveal.bringIntoView(qrRevealRect(qrCardSize, clearance)) }
                    }
                }
                LkCard(
                    modifier = Modifier
                        .bringIntoViewRequester(qrReveal)
                        .onSizeChanged { qrCardSize = it },
                    padding = 16.dp,
                ) {
                    // Bounded by the SHORTER screen dimension, not by width alone — see [QrImage].
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

        if (mnemonicGridVisible(qrVisible = state.qrVisible, qrRendered = qr != null)) {
            MnemonicGrid(state.recoveryMnemonic)
        }
    }
}

/**
 * The 24 words, numbered, in monospace.
 *
 * Numbered because the order is load-bearing and a wall of words invites transcription errors
 * exactly where they are least recoverable; monospace so `l`/`1` and `O`/`0` cannot be confused
 * when copying them onto paper.
 */
@Composable
internal fun MnemonicGrid(mnemonic: String) {
    val c = LkTheme.colors
    val words = remember(mnemonic) { mnemonic.trim().split(Regex("\\s+")).filter(String::isNotBlank) }
    if (words.isEmpty()) return

    LkCard(shape = LkShape.card, padding = 18.dp) {
        // No Spacer: [LkSectionLabel] owns the gap to the block it heads (LkSectionLabelGap). The
        // 14dp that used to sit here was this caller's own number for that one relationship.
        LkSectionLabel(stringResource(R.string.onboarding_recovery_words_label))
        // Two columns when a cell can hold the longest BIP-39 word beside its index; one column
        // once it cannot — measured, not assumed, because this composable is shared at two widths
        // (this screen pads 26dp for 155.5dp/cell; RecoveryKitScreen pads 20dp for 161.5dp/cell,
        // so this one breaks first) and fontScale multiplies word width independently of either.
        // A dp cutoff written for one caller would be wrong for the other; measuring the actual
        // cell and the actual longest word is correct for both. Below fontScale ~1.3 the word
        // always fits and this renders exactly the two-column grid it always has.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val columnSpacingPx = with(density) { 12.dp.toPx() }
        val indexGapPx = with(density) { 8.dp.toPx() }
        val indexWidthPx = remember(words.size, measurer) {
            measurer.measure(words.size.toString().padStart(2, '0'), LkText.monoCaption).size.width.toFloat()
        }
        val wordWidthPx = remember(words, measurer) {
            val longest = words.maxByOrNull(String::length).orEmpty()
            measurer.measure(longest, LkText.monoField).size.width.toFloat()
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val columns = mnemonicColumnCount(containerWidthPx, columnSpacingPx, indexWidthPx, indexGapPx, wordWidthPx)
            val rows = (words.size + columns - 1) / columns
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                repeat(rows) { row ->
                    if (columns == 1) {
                        MnemonicWord(row + 1, words.getOrNull(row), Modifier.fillMaxWidth())
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MnemonicWord(row + 1, words.getOrNull(row), Modifier.weight(1f))
                            MnemonicWord(rows + row + 1, words.getOrNull(rows + row), Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The column-count decision behind [MnemonicGrid], pulled out so it can be pinned without a
 * Compose runtime. Two columns is the shape every existing screenshot was taken against, so
 * "unmeasured" (either input still zero, e.g. before the first layout pass) resolves to 2 rather
 * than degrading to the narrower layout it has no evidence for.
 */
internal fun mnemonicColumnCount(
    containerWidthPx: Float,
    columnSpacingPx: Float,
    indexWidthPx: Float,
    indexGapPx: Float,
    wordWidthPx: Float,
): Int {
    if (containerWidthPx <= 0f || wordWidthPx <= 0f) return 2
    val cellWidthPx = (containerWidthPx - columnSpacingPx) / 2f
    val wordBudgetPx = cellWidthPx - indexWidthPx - indexGapPx
    return if (wordWidthPx > wordBudgetPx) 1 else 2
}

@Composable
private fun MnemonicWord(index: Int, word: String?, modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            index.toString().padStart(2, '0'),
            style = LkText.monoCaption,
            color = c.textFaint,
        )
        Text(
            word.orEmpty(),
            style = LkText.monoField,
            color = c.textPrimary,
        )
    }
}

internal const val QR_SIZE_PX = 720

/**
 * The Recovery Kit QR, sized to fit the screen it is on.
 *
 * A QR is square, so `fillMaxWidth()` sets its height from the *width* — which in landscape, or on
 * a tablet, is the long edge. The image then ran taller than the display and pushed the "I captured
 * the QR code" button off the bottom of a card that does not scroll, so the only way to confirm the
 * capture was unreachable in exactly the orientation someone holds a phone to photograph it with a
 * second device.
 *
 * Bounding by the shorter dimension (and half the height, to leave room for the caption and the
 * button) keeps it as large as it can usefully be in both orientations. Scanners need pixels, not
 * screen fraction, so it stays centred rather than stretching.
 */
@Composable
internal fun QrImage(bitmap: ImageBitmap) {
    val configuration = LocalConfiguration.current
    val side = minOf(
        configuration.screenWidthDp.dp,
        // Half the height, not all of it: the caption and the confirm button below share the card.
        configuration.screenHeightDp.dp * 0.5f,
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.onboarding_recovery_qr_content_description),
            // White plate behind it: a QR on a near-black card is unreadable to roughly every
            // scanner, which would silently break the whole escape hatch.
            modifier = Modifier
                .sizeIn(maxWidth = side, maxHeight = side)
                .background(Color.White, LkShape.md)
                .clip(LkShape.md)
                .padding(12.dp),
        )
    }
}

/** Unwraps the Activity behind a Compose [Context], which may be a ContextWrapper. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The cloud-backup decision, required at onboarding by design.md \u00a78.2 and never built until now.
 *
 * Two buttons, following the same primary/secondary convention every other onboarding step uses
 * for its recommended action (Welcome, Create password, Biometric, Recovery Kit, Migrate all put
 * the app's suggested path in the bright `LkPrimaryButton`). This *is* a dressed default, not a
 * neutral choice presented twice: "Keep the backup on" is the primary, and it matches Auto Backup's
 * actual shipped default (`LocalKeepSettings.isCloudBackupDisabled` defaults to `false`, and the
 * manifest's `allowBackup="true"` is live before this step is ever reached \u2014 design.md \u00a78.2 calls
 * it "a chosen feature"). So the bright button changes nothing; only the secondary one, "No \u2014
 * nothing leaves this device", writes a new value.
 *
 * The screen before this one tells the user their master password cannot be reset; the honest
 * follow-up is to say, in the same breath, whether an encrypted copy is going to Google \u2014 and to
 * let them stop it here rather than after they have gone looking. Backup on is a real protection
 * against the commonest way people lose a vault, which is losing the phone; backup off is the
 * literal "nothing leaves this device" property \u00a71 promises. Both consequences are stated at the
 * same weight in the copy, even though the buttons are not visually neutral.
 */
@Composable
private fun CloudBackupStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
) {
    StepColumn(
        step = state.step,
        title = stringResource(R.string.onboarding_cloudbackup_title),
        body = stringResource(R.string.onboarding_cloudbackup_body),
        actions = {
            LkPrimaryButton(
                text = stringResource(R.string.onboarding_cloudbackup_enable_cta),
                onClick = { viewModel.chooseCloudBackup(disabled = false) },
            )
            LkSecondaryButton(
                text = stringResource(R.string.onboarding_cloudbackup_disable_cta),
                onClick = { viewModel.chooseCloudBackup(disabled = true) },
            )
        },
        pinned = {
            LkFootnote(
                stringResource(R.string.onboarding_cloudbackup_footnote),
                accent = LkTheme.colors.amber,
            )
        },
    ) {
        // Every notice `back()` can raise lands on whichever step the gesture was made from, and
        // three of the six steps used to render none — so on those the refusal was consumed with
        // nothing said and nothing moved, which from the outside is a screen that has stopped
        // responding (2A-F6, reported on step 4 of 6). `NoticeText` draws nothing when there is no
        // notice, so this costs the step nothing in the ordinary case.
        NoticeText(state.notice)
        ErrorText(state.error)
    }
}

@Composable
private fun MigrateStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
) {
    StepColumn(
        step = state.step,
        title = stringResource(R.string.onboarding_migrate_title),
        body = stringResource(R.string.onboarding_migrate_body),
        actions = {
            LkPrimaryButton(
                text = stringResource(R.string.onboarding_migrate_import_cta),
                icon = LkIcons.Import,
                onClick = { viewModel.chooseMigration(migrate = true) },
            )
            LkSecondaryButton(
                text = stringResource(R.string.onboarding_migrate_skip_cta),
                onClick = { viewModel.chooseMigration(migrate = false) },
            )
        },
        pinned = {
            // Next to the buttons rather than above the fold: this sentence exists to stop someone
            // importing under the impression that it is now or never, so it has to be legible at
            // the instant they are choosing between the two buttons — which is the one instant a
            // grey note at the top of a scroll is not on screen.
            //
            // Amber, not brass. Brass means Pro everywhere else in the app — the crown, the badge,
            // the locked panel — and a brass note sitting against "Import my passwords" would
            // re-imply the paywall that importing was just moved out of. Amber is what ConsentRow
            // already uses two steps earlier for the same kind of "here is what you are choosing".
            LkFootnote(
                stringResource(R.string.onboarding_migrate_footnote),
                accent = LkTheme.colors.amber,
            )
        },
    ) {
        // Every notice `back()` can raise lands on whichever step the gesture was made from, and
        // three of the six steps used to render none — so on those the refusal was consumed with
        // nothing said and nothing moved, which from the outside is a screen that has stopped
        // responding (2A-F6, reported on step 4 of 6). `NoticeText` draws nothing when there is no
        // notice, so this costs the step nothing in the ordinary case.
        NoticeText(state.notice)
        ErrorText(state.error)
    }
}

@Composable
private fun DoneStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
) {
    val c = LkTheme.colors
    LkScreen(accent = c.jade, auraAlpha = 0.12f, auraBias = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = LkFrame.heroHorizontal)
                .padding(top = 46.dp, bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Scrollable, and centred only while there is room to centre in. This is the one
            // onboarding step that does not go through StepColumn, and it was the one step whose
            // content had no way out from under the buttons: at font_scale 2.0 the
            // `biometricSkipReason` footnote — which is what tells the user their master password
            // is still the way in — was laid out underneath the pinned CTA stack and could not be
            // scrolled to, because a fixed-height Column has nowhere to scroll.
            //
            // `heightIn(min = viewport)` is what keeps the existing look: a plain
            // `.verticalScroll()` measures its content against an infinite height, so
            // `Arrangement.Center` would have no slack to distribute and everything would jump to
            // the top on the ordinary screens where it all already fits. Same shape as
            // UnlockScreen's own scroll region, for the same reason.
            val scroll = rememberScrollState()
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    // The same fade every other step now draws. This step is where the flow ends,
                    // so a clipped footnote here is the last thing onboarding says.
                    .lkScrollEdges(
                        moreAbove = scroll.canScrollBackward,
                        moreBelow = scroll.canScrollForward,
                        color = c.ground,
                    ),
            ) {
                val viewport = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .heightIn(min = viewport),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(c.jade.copy(alpha = 0.18f), c.jade.copy(alpha = 0.04f)),
                                ),
                                LkShape.dialog,
                            )
                            .lkRing(LkShape.dialog, c.jade.copy(alpha = 0.22f))
                            .lkEnter(travel = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            LkIcons.ShieldCheck,
                            contentDescription = null,
                            tint = c.jade,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(
                        stringResource(R.string.onboarding_done_title),
                        style = LkText.display,
                        color = c.textStrong,
                        modifier = Modifier.lkEnter(delayMillis = 80),
                    )
                    Spacer(Modifier.height(12.dp))
                    // Conditioned on whether a kit was actually exported. It used to say "keep your
                    // Recovery Kit somewhere safe" unconditionally, so a user who had just chosen
                    // "I'll do this later" was told to look after a thing that does not exist — and
                    // the one screen that should have been saying "you currently have no way back in"
                    // said the opposite.
                    Text(
                        stringResource(
                            if (state.recoveryExported) {
                                R.string.onboarding_done_body_recovery_saved
                            } else {
                                R.string.onboarding_done_body_recovery_missing
                            },
                        ),
                        style = LkText.body,
                        color = if (state.recoveryExported) c.textMuted else c.amber,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.lkEnter(delayMillis = 140),
                    )
                    // Surfaced here rather than as a dead screen earlier: the biometric step was
                    // skipped, and this says why and what to do about it.
                    state.biometricSkipReason?.let {
                        Spacer(Modifier.height(20.dp))
                        LkFootnote(stringResource(it), icon = LkIcons.Fingerprint)
                    }
                }
            }
            LkPrimaryButton(
                text = stringResource(
                    if (state.migrateRequested) {
                        // Not the migration step's own label, which is what this said until
                        // 2026-09-20: `chooseMigration(migrate = true)` moves straight from that
                        // step to this one, so the user tapped "Import my passwords" and arrived at
                        // a screen offering "Import my passwords". The identical words on the two
                        // consecutive screens read as the first tap having done nothing (2A-F7).
                        R.string.onboarding_done_import_cta
                    } else {
                        R.string.onboarding_done_open_vault_cta
                    },
                ),
                onClick = viewModel::finish,
                modifier = Modifier.lkEnter(delayMillis = 200),
            )
            // The autofill offer, demoted from its own screen. It used to sit between the migration
            // step and this one, which meant a trip out to Android's system settings before the
            // user had ever seen the vault they had just made. Offered here it still catches them
            // at the moment of highest intent, but "Open my vault" is the primary action and this
            // is plainly optional — and both of these are also in Settings.
            Spacer(Modifier.height(14.dp))
            // Two states, because a user who has just turned autofill on and one who never touched
            // it were being shown the identical button. Still tappable when on — it is the route
            // back to the system picker if they want to change providers — but it now says what is
            // true rather than what to do. See OnboardingUiState.autofillEnabled.
            if (state.autofillEnabled) {
                LkSecondaryButton(
                    text = stringResource(R.string.onboarding_done_autofill_done),
                    icon = LkIcons.Check,
                    onClick = viewModel::openAutofillSettings,
                    modifier = Modifier.lkEnter(delayMillis = 240),
                )
            } else {
                LkSecondaryButton(
                    text = stringResource(R.string.onboarding_done_autofill_cta),
                    onClick = viewModel::openAutofillSettings,
                    modifier = Modifier.lkEnter(delayMillis = 240),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Spacer(Modifier.height(8.dp))
                LkTextButton(
                    text = stringResource(R.string.onboarding_done_passkeys_cta),
                    onClick = viewModel::openCredentialProviderSettings,
                    modifier = Modifier.lkEnter(delayMillis = 280),
                )
            }
            state.notice?.let {
                Spacer(Modifier.height(12.dp))
                // See CloudBackupStep: a `back()` refusal raised here used to be silent.
                NoticeText(it)
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                ErrorText(it)
            }
        }
    }
}

/**
 * One screen, one decision (§3.2) — the shared frame, so the steps don't drift apart visually.
 *
 * The rail across the top is the only thing that tells the user how much of this is left. Without
 * it, a seven-screen setup with no visible end is where people abandon a password manager.
 *
 * `internal` rather than private so the frame can be measured without a vault behind it: what it
 * gives the content slot at a large font scale on a narrow window is the whole of the argument
 * below, and `PasswordStepFieldOnScreenTest` drives this directly with step 2's own strings.
 */
@Composable
internal fun StepColumn(
    step: OnboardingUiState.Step,
    title: String,
    body: String,
    actions: @Composable ColumnScope.() -> Unit,
    /**
     * A note that rides with [actions] instead of scrolling away above them.
     *
     * The reassurance that a step can be skipped is only worth anything at the moment the user is
     * deciding whether to skip it, and that moment happens at the buttons. Left in [content] it sits
     * behind however much the step happens to render — on the Recovery Kit step, behind twenty-four
     * words, three export buttons and a footnote — so the person most likely to want it is the one
     * least likely to scroll far enough to see it.
     */
    pinned: (@Composable ColumnScope.() -> Unit)? = null,
    /**
     * Keeps [actions] on its own strip even when the step overflows.
     *
     * The default is the arithmetic below: the pin is kept while it is free and dropped once it
     * costs the content the room it needs. That is right for a step whose action block is the
     * *destination* — Continue, which nobody can press until they have read what is above it.
     *
     * It is wrong for a step whose action block holds the way *out*. The Recovery Kit step puts
     * "Set this up later" here precisely because this block does not scroll, and its content is a
     * twenty-four-word grid, an optional QR card, three save buttons and a footnote — so the moment
     * the step overflows, the un-pinned arrangement buries the one control the person who wants to
     * skip the ceremony is looking for, under the whole of the ceremony. An escape hatch that has
     * to be scrolled to is not an escape hatch. 4-1.
     */
    alwaysPinActions: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LkTheme.colors
    // The shared keyboard-up rule (see [rememberLkKeyboardUnpinsChrome]). RG-43 fixed portrait by
    // letting the action block ride down inside the scroll; the progress rail, the 46dp above it,
    // the gap under it and the 32dp below the strip stayed pinned, and in landscape (or on a
    // 308dp-wide window at 2.0 text) that is the whole region: the password step could only be
    // typed blind (SCL-006). While this is true everything but the keyboard scrolls.
    val keyboardUnpinned = rememberLkKeyboardUnpinsChrome()
    LkScreen(accent = c.jade, auraAlpha = 0.09f, auraBias = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                // The keyboard is taken off the whole frame rather than off the action strip, so
                // both arrangements end above it and the choice between them is free of the IME.
                // On the strip it was equivalent only while the strip was always the last child —
                // which stopped being true once the strip can ride inside the scroll (below), where
                // a bottom padding the size of the keyboard is dead scroll rather than clearance.
                .imePadding()
                .padding(horizontal = 26.dp)
                // The frame's own 46dp above and 32dp below ride inside the scroll while the
                // keyboard has left the window too little room (below), where they are 78dp of the
                // very region the field needs.
                .padding(top = if (keyboardUnpinned) 0.dp else 46.dp, bottom = if (keyboardUnpinned) 0.dp else 32.dp),
        ) {
            if (!keyboardUnpinned) {
                StepRail(step)
                Spacer(Modifier.height(30.dp))
            }

            // Every step's content scrolls, and until this was added nothing said so. The Recovery
            // Kit step was the case that mattered: its card ended at "Save as PDF" flush against
            // the pinned Continue bar, so Print and "Show QR code instead" — two of the three ways
            // to save the one secret that can rescue this vault — read as not existing rather than
            // as being below the fold. At font_scale 2.0 the QR mode's own confirm button is a
            // fourteen-pixel unlabeled strip against that same edge. Both found by the F01 UX
            // persona, 2026-08-29.
            //
            // Past tense on the first of those two as of 2026-09-16: the three save buttons now
            // render *above* the 24-word grid (`RecoveryStep`), so the fold no longer lands inside
            // them. The fade stays — the grid is what runs past the fold now, and every other step
            // still needs it.
            //
            // Outside the scroll, so the fade belongs to the viewport rather than travelling with
            // the content.
            val scroll = rememberScrollState()

            // Whether the action block keeps its own strip at the bottom of the window, or rides
            // down inside the scroll with everything else.
            //
            // The pin was written for one thing: the keyboard, which covers the bottom of the
            // window and nothing else. It costs whatever the block happens to measure, and that
            // cost comes out of the *only* region the step's content has. On the narrowest window
            // this app supports (308dp, the system's largest display size) at font scale 2.0 the
            // block measures 202dp — a Continue button and a four-line footnote — against a
            // 531dp region, and step 2 opens on a heading, three quarters of one sentence and a
            // disabled button. Both password fields are laid out 104dp past the bottom edge, and
            // the only thing on screen that could tell you so is a 28dp fade. Measured, not
            // inferred: `PasswordStepFieldOnScreenTest`.
            //
            // So it stays pinned exactly while it is free. Nothing overflowing means the block sits
            // at the bottom of the window either way — a scrolling column with `heightIn(min =
            // viewport)` and a weighted spacer puts it in the same pixels the outer Column did —
            // which is why every window that fits today is untouched. Once the step overflows the
            // pin is no longer free, and it is no longer buying anything either: there is no
            // keyboard, and a button pinned above content the user has not reached yet is a
            // control for a decision they cannot make.
            //
            // The flip cannot oscillate, and that is arithmetic rather than luck. Un-pinning moves
            // the block from outside the viewport to inside the content, so the viewport and the
            // content grow by the same height and `maxValue` — content minus viewport — comes out
            // where it started, give or take the spacing below. A layout that overflows overflows
            // in both arrangements, so the predicate reads the same on the frame after it flips.
            //
            // [alwaysPinActions] opts a step out of the whole argument, and the trade it makes is
            // the deliberate one: that step's content keeps whatever the block costs it, in
            // exchange for a way out that is never below the fold. It cannot oscillate either,
            // being constant.
            //
            // The keyboard used to be a third term here — `|| imeVisible` — and it reinstated the
            // pin in the one state where it is least free, which undid the whole of the paragraph
            // above the moment a user touched a field. Measured on step 2 with a 233dp IME
            // (`PasswordStepImeScrollTest`): on a 309dp window at font scale 2.0 the forced pin
            // left the content **32dp** of viewport — a 72dp password well could not be seen at
            // all, only a sliver of one — and 190dp on a 360dp window at the same scale. With the
            // frame taking the inset instead, the predicate reads the same with the keyboard up as
            // without it, and the step that fits keeps its pinned strip either way.
            val density = LocalDensity.current
            val pinActions = !keyboardUnpinned && (alwaysPinActions || scroll.maxValue == 0)

            // What the pinned strip actually measures, so the scroll below it can reserve the same
            // amount rather than a guess. A constant cannot do this: the block is a button plus
            // whatever [pinned] holds, at the user's font scale, in the user's language — on the
            // Recovery Kit step it is a Continue button and a deferral link, and at font scale 2.0
            // in German it is nearly twice the height it is in English here.
            var actionBlockHeight by remember { mutableStateOf(0.dp) }
            val actionBlock: @Composable () -> Unit = {
                Column(
                    Modifier
                        .onSizeChanged { actionBlockHeight = with(density) { it.height.toDp() } }
                        .lkEnter(delayMillis = 120),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pinned?.invoke(this)
                    actions()
                }
            }

            // The room the scroll gives back at its far end, so the last control does not come to
            // rest jammed against the strip and under its fade. Applied only on a step that pins
            // unconditionally *and* already overflows, which is what keeps the no-oscillation
            // argument above intact: on a step that fits, `maxValue` is 0 and this is 0, so the
            // reserve can never be the thing that creates the overflow that justifies it — and on a
            // step that overflows, extra content at the end can only ever add to the overflow.
            // Nothing is pinned while the keyboard-up arrangement is on, so there is no strip to clear.
            val tailRoom = scrollTailRoom(alwaysPinActions && !keyboardUnpinned, scroll.maxValue, actionBlockHeight)

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .lkScrollEdges(
                        moreAbove = scroll.canScrollBackward,
                        moreBelow = scroll.canScrollForward,
                        color = c.ground,
                    ),
            ) {
                val viewport = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .heightIn(min = viewport),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Centres a step that fits, and does nothing to one that does not.
                    //
                    // With the actions pinned, everything above them was top-aligned, so the two
                    // shortest steps — cloud backup and import, one heading and one paragraph each
                    // — left ~40% of the window empty between the paragraph and the buttons while
                    // steps 1, 3 and 6 fill or centre theirs. These are also the two steps that ask
                    // the flow's only consequential questions, and a screen that looks unfinished is
                    // a poor place to ask one.
                    //
                    // Safe on the long steps by construction: a `weight` inside a scrolling column
                    // has slack to take only while `heightIn(min = viewport)` is what is setting the
                    // height. The moment the content is taller than the viewport the weight resolves
                    // to zero and this is a no-op — which is exactly the Recovery Kit step, and is
                    // the same argument the un-pinned branch's own weight below already rests on.
                    if (pinActions) Spacer(Modifier.weight(1f))
                    if (keyboardUnpinned) {
                        // Spacers inside a `spacedBy(14.dp)` column are spaced too: these are the
                        // frame's 46dp above the rail and 30dp under it, less the gaps either side.
                        Spacer(Modifier.height(46.dp - 14.dp))
                        StepRail(step)
                        Spacer(Modifier.height(30.dp - 14.dp * 2))
                    }
                    Column(
                        Modifier.lkEnter(delayMillis = 40),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(title, style = LkText.display.copy(fontSize = LkText.title.fontSize), color = c.textStrong)
                        // `lkBrandedText`, not the raw String: this scaffold prints every step's
                        // body, and several of them name the product. Flat `textMuted` painted
                        // "LocalKeep" grey — the exact complaint reported from a phone on
                        // 2026-08-20 and fixed then in `LkModalMasthead` only, which is the one
                        // place the utility was wired. Onboarding was never migrated, so the
                        // biometrics step reported it again on 2026-09-08.
                        //
                        // Safe across locales: the utility substring-matches the untranslated brand
                        // name rather than taking a positional arg, so the existing fr/de/es/pt
                        // strings need no change.
                        Text(lkBrandedText(body), style = LkText.bodyQuiet, color = c.textMuted)
                    }
                    Spacer(Modifier.height(4.dp))
                    content()
                    // The air below the content, reserved in both arrangements. Keeping it in the
                    // un-pinned one is what makes that arrangement strictly the taller of the two,
                    // and that is the load-bearing half of the no-oscillation argument above: a
                    // step overflowing by a hair must not un-pin, come out fitting, and re-pin
                    // forever. Un-pinning here can only ever add to the overflow, never remove it.
                    Spacer(Modifier.height(16.dp + tailRoom))
                    // The other half of the centring above. Equal weights top and bottom put the
                    // block in the optical middle rather than pushing it to the foot of the window.
                    if (pinActions) Spacer(Modifier.weight(1f))
                    if (!pinActions) {
                        // Takes the slack when there is any, which is what makes the un-pinned
                        // arrangement land the block in the same pixels as the pinned one on every
                        // window that fits. It is legal inside a scroll only because `heightIn`
                        // puts a *minimum* on a column the scroll otherwise measures with unbounded
                        // height — with nothing to divide, a weight is zero and the block would
                        // ride up under the content instead of sitting at the bottom of the window.
                        Spacer(Modifier.weight(1f))
                        actionBlock()
                    }
                    // The frame's bottom 32dp, which the keyboard-up arrangement moved into the scroll.
                    if (keyboardUnpinned) Spacer(Modifier.height(32.dp - 14.dp))
                }
            }

            if (pinActions) actionBlock()
        }
    }
}

/**
 * How much empty room the scrolling column reserves past its last child, so the pinned action strip
 * does not come down flush on the final control.
 *
 * Zero unless the step pins unconditionally *and* already overflows, and that guard is the whole of
 * the correctness argument: reserved room is content, content is what `maxValue` measures, and a
 * reserve that could itself create the overflow it is conditioned on would oscillate every frame.
 * On a step that fits, `maxValue` is 0 and this is 0 — so it cannot. On a step that already
 * overflows, more content at the end only deepens the overflow, so the predicate reads the same on
 * the next frame.
 *
 * A free function so that argument can be tested rather than asserted.
 */
internal fun scrollTailRoom(
    alwaysPinActions: Boolean,
    scrollMaxValue: Int,
    actionBlockHeight: Dp,
): Dp = if (alwaysPinActions && scrollMaxValue > 0) actionBlockHeight else 0.dp

/**
 * The rectangle the Recovery-Kit QR asks `bringIntoView` for: the card, grown by [clearancePx] at
 * top and bottom.
 *
 * The growth is the point. `bringIntoView` is satisfied by an edge flush with the viewport, and
 * flush is exactly where `lkScrollEdges` paints its fade — so asking for the card's own bounds
 * would scroll a QR into view with a gradient over the finder patterns, which is the defect
 * (F-50), one scroll position further along.
 */
internal fun qrRevealRect(size: IntSize, clearancePx: Float): Rect = Rect(
    left = 0f,
    top = -clearancePx,
    right = size.width.toFloat(),
    bottom = size.height + clearancePx,
)

/**
 * Whether this run of onboarding will show the biometric step at all.
 *
 * A CompositionLocal rather than a parameter because the only thing that needs it is the rail, and
 * the rail is nested inside every step's scaffold — threading a flag through seven call sites to
 * reach one `filter` would be more code in more places than the fact is worth.
 */
private val LocalBiometricStepShown = compositionLocalOf { true }

/** As [LocalBiometricStepShown], for the migration step. */
private val LocalMigrateStepShown = compositionLocalOf { true }

/**
 * Only the steps this run will actually show.
 *
 * A device with no strong biometric sensor skips `ENABLE_BIOMETRIC` outright, and counting the
 * enum meant the rail said "3 of 7" while three of those seven were never going to appear. A
 * progress indicator that overstates what is left is worse than none on a flow people are already
 * impatient with.
 *
 * `MIGRATE` was missed when that was fixed. `chooseCloudBackup` jumps straight to `DONE` whenever
 * the post-creation unlock did not take, because the wizard writes into the vault and there is no
 * open vault to write to — so on that path the rail counted a step the user would never reach.
 * `back()` already knew this; `previousStep` has taken `migrateStepShown = vaultUnlocked` all
 * along, and only the rail was left behind.
 *
 * A plain function rather than a `filter` inside the composable so the count can be asserted
 * without rendering anything.
 */
/**
 * Whether the biometric step counts toward the progress rail's total.
 *
 * A free function so the invariant it carries can be tested: **the total never shrinks**. Given one
 * device answer, this must not go true-then-false as [step] advances, because the rail renders one
 * segment per counted step and prints "N of total" beside them.
 */
internal fun biometricStepShown(
    step: OnboardingUiState.Step,
    possibleOnDevice: Boolean,
    available: Boolean,
): Boolean = if (step <= OnboardingUiState.Step.CREATE_PASSWORD) {
    // The device's own answer, asked in the ViewModel's init. Before the vault exists this is the
    // whole of the question.
    possibleOnDevice
} else {
    // Now also requires the new vault to have unlocked. That can only narrow the answer, and it
    // narrows it on a path (creation succeeded, unlock failed) that is already an error state.
    available
}

internal fun visibleSteps(
    biometricShown: Boolean,
    migrateShown: Boolean,
): List<OnboardingUiState.Step> = OnboardingUiState.Step.entries.filter {
    when (it) {
        OnboardingUiState.Step.ENABLE_BIOMETRIC -> biometricShown
        OnboardingUiState.Step.MIGRATE -> migrateShown
        else -> true
    }
}

@Composable
private fun StepRail(step: OnboardingUiState.Step) {
    val steps = visibleSteps(
        biometricShown = LocalBiometricStepShown.current,
        migrateShown = LocalMigrateStepShown.current,
    )
    val index = steps.indexOf(step)
    // The rail itself moved to core/ui when the card flow needed one too; what stays here is the
    // part that is actually onboarding's — which steps this device shows, and the wording.
    LkStepRail(
        stepIndex = index,
        stepCount = steps.size,
        progressText = stringResource(R.string.onboarding_rail_progress, index + 1, steps.size),
        progressDescription = stringResource(
            R.string.onboarding_rail_progress_description,
            index + 1,
            steps.size,
        ),
    )
}

@Composable
private fun NoticeText(@androidx.annotation.StringRes notice: Int?) {
    val c = LkTheme.colors
    if (notice != null) {
        Text(
            stringResource(notice),
            style = LkText.caption,
            color = c.textMuted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun ErrorText(@androidx.annotation.StringRes error: Int?) {
    if (error != null) {
        val message = stringResource(error)
        // .announce, not a bare live region on the container: LkInlineError puts the message in a
        // child Text, so a liveRegion on the Row itself announces a node that carries no text or
        // contentDescription — an error a screen reader never hears. Same fix as the settings
        // status slot (QA 2026-08-06, 8-2).
        LkInlineError(message, Modifier.announce(message))
    }
}

/**
 * How many bars the strength meter fills for a zxcvbn [score].
 *
 * A floor of one, and that is the whole function. zxcvbn scores 0 and 1 are both coral bands, but a
 * raw 0 filled nothing, so the worst possible master password painted four idle bars — the meter's
 * colour language went silent at exactly the moment it had the most to say, on the one field in the
 * app that can never be reset. Found by the visual sweep of 2026-09-01, which pixel-sampled the bars
 * at RGB(108,109,110) — `controlIdle` over the background — and found no coral anywhere on screen.
 *
 * Deliberately *not* `LkStrength.ofScore(score).filledSegments`. That folds 0 and 1 into one Weak
 * band, which would collapse this screen's five-rung label ladder ([strengthLabel]: guessed
 * instantly / seconds / hours / strong enough / very strong) into four and rewrite five translated
 * strings in fr/de/es/pt — a re-mapping, to fix a defect that is one value wide. Scores 1..4 already
 * agree with `LkStrength` band for band.
 *
 * Extracted from the composable so it can be tested; it has no other reason to be a function.
 */
internal fun strengthMeterLevel(score: Int): Int = score.coerceAtLeast(1)

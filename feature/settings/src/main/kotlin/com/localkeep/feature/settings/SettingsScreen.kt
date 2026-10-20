package com.localkeep.feature.settings

import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.health.StrengthAnalyzer
import com.localkeep.core.ui.openPlayListing
import com.localkeep.core.ui.A11yPreferences
import com.localkeep.core.ui.AlwaysOn
import com.localkeep.core.ui.ClipboardHelper
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.ProGate
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkButtonAccent
import com.localkeep.core.ui.theme.LkChip
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkGroupRow
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSearchField
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkStrength
import com.localkeep.core.ui.theme.LkStrengthMeter
import com.localkeep.core.ui.theme.LkSwitchRow
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LkTintButton
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkStaggerDelay
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.AutoLockPreset
import com.localkeep.core.vault.LocalKeepSettings
import com.localkeep.core.vault.ProTypeQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/**
 * Grouped, searchable settings (UI/UX spec §10.1).
 *
 * **Every entry is one row until you ask for more.** The version this replaces gave each of the
 * eighteen settings a card with its title and a full paragraph, all expanded, all at once — so the
 * screen was a page of prose that happened to contain some controls, and finding the auto-lock
 * preset meant reading past an essay on Android Auto Backup. The prose was accurate and worth
 * keeping; it was the *always* that was wrong.
 *
 * Two structural changes came out of that. Rows collapse: a summary line says what the setting is
 * and what it is currently set to, and the detail opens on a tap. And the always-on guarantees —
 * encryption, unlock throttling, anti-phishing, what may leave the device — moved out of the
 * groups where they sat between real controls, into their own section. They are not settings.
 * Nobody can change them, and mixing them in was most of what made this screen read as a wall.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onHealthClick: () -> Unit,
    onImportClick: () -> Unit = {}, // NEW (14b Task 17) — default no-op, existing call sites keep compiling
    onBackupClick: () -> Unit = {}, // NEW (15) — default no-op, existing call sites keep compiling
    onRecoveryKitClick: () -> Unit = {},
    onTrustedAppsClick: () -> Unit = {},
    onHiddenItemsClick: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity

    // The Recovery Kit screen is one row away and clears the outstanding-kit warning the moment a
    // kit is exported. Re-read on every entry, or Settings keeps warning about a kit that now
    // exists until the process restarts.
    //
    // There used to be a second refresh here, for a cloud-backup switch this screen owned a copy
    // of. That switch is gone — the row navigates to the one on the Backup screen now — and with
    // one writer there is no stale twin to re-read.
    LaunchedEffect(Unit) {
        viewModel.refreshRecoveryKitState()
        // Changed one row away, on the Backup screen — same re-read-on-entry reason as above.
        viewModel.refreshCloudBackupState()
    }
    // Autofill, biometrics and touch feedback are all changed in the *system* Settings app, which
    // rows here send the user to and which never returns a result. Without a re-read, the row that
    // had just sent someone to enrol a fingerprint still said their device had none when they came
    // back.
    RefreshOnReturn { viewModel.refreshSystemCapabilities() }

    // Set when the drawer's "Duress / decoy" sent the user here, naming a row rather than a screen.
    // Taken once and consumed, so reaching Settings any other way shows the whole list.
    // Taken as a resource id and resolved here: matching on the rendered title is the whole point
    // (the search field and the expand key both work in display text), but the id is what survives
    // a locale that is not English. See [SettingsFocus.DURESS].
    val focusRes = viewModel.focusEntryTitleRes
    val focus = focusRes?.let { stringResource(it) }

    // All transient state is hoisted here rather than kept inside the entries: search filtering
    // moves entries around and unmounts them, which would otherwise wipe a half-typed password.
    // The search term is how the row gets found: it narrows the list to the one row the link
    // promised, and leaves the reason visible in the search field rather than scrolling silently.
    // Owned by the ViewModel, not by composition: `focusRes` is consumed once per ViewModel and a
    // configuration change rebuilds this composable, so a `remember` here re-seeded from a focus
    // that had already been taken and emptied the field. `rememberSaveable` is not the alternative
    // — see the ViewModel property, and SettingsFocus’s own reason for not being a route argument.
    val typedQuery by viewModel.query.collectAsStateWithLifecycle()
    val query = typedQuery ?: focus.orEmpty()
    // The change-password row's wells, its "forgot" mode and the Recovery Kit words that mode asks
    // for — all owned by the ViewModel, never `rememberSaveable`. See [PasswordFormState]: plain
    // `remember` lost all of it to a rotation, and saved state is written to disk by the system, where
    // a master password or 24 words that open the vault are the last things that belong.
    val passwordForm by viewModel.passwordForm.collectAsStateWithLifecycle()
    val currentPassword = passwordForm.currentPassword
    val newPassword = passwordForm.newPassword
    val confirmPassword = passwordForm.confirmPassword
    val usingRecoveryKit = passwordForm.usingRecoveryKit
    val recoveryWords = passwordForm.recoveryWords
    // One reveal flag per well. The form replaces the only key to the vault and had no way to check
    // what had been typed into any of its three fields — alone among the app's password forms, and
    // the one where a typo locks the user out, because the same operation revokes the Recovery Kit.
    // Plain `remember`, unlike the wells: a rotation re-masking them is the safe direction.
    var revealCurrentPassword by remember { mutableStateOf(false) }
    var revealNewPassword by remember { mutableStateOf(false) }
    var revealConfirmPassword by remember { mutableStateOf(false) }
    // The duress row's three wells and its two dialog flags, all five owned by the ViewModel — see
    // [DuressFormState] for why they moved out of composition. In short: a rotation used to destroy
    // whichever confirmation was open and empty every well with it (3A-F24), the wells cannot be
    // `rememberSaveable` because a duress password must not reach the instance-state Bundle, and a
    // saveable flag beside non-saveable wells brings the dialog back over an emptied form. A
    // ViewModel outlives the configuration change and dies with the process, which keeps the flags
    // and the secrets they act on on exactly the same lifetime.
    val duressForm by viewModel.duressForm.collectAsStateWithLifecycle()
    // Saveable, and safely so: it holds no password field, so it carries none of the
    // emptied-CharArray trap that moved the duress form into the ViewModel above. The biometric
    // disclosure is a consent dialog - dismissing it silently on rotation
    // fails closed, but it makes the user re-open the row and re-read it, and consent the user was
    // half-way through reading is exactly the thing not to throw away.
    var showBiometricDisclosure by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    // Which rows are open, by title. Saveable so a rotation doesn't collapse the one the user just
    // opened — and a set rather than a single value because closing one to read another is busywork.
    val expanded = rememberSaveable(saver = expandedSaver) { mutableStateOf(setOfNotNull(focus)) }

    // The rows are inline disclosures, not destinations, so the platform gesture for "close the
    // open thing" should collapse them before it pops the screen. Registered only while collapsing
    // would change something — see [settingsBackCollapses].
    BackHandler(enabled = settingsBackCollapses(query, expanded.value)) { expanded.value = emptySet() }

    val groups = listOf(
        SettingsGroup(
            stringResource(R.string.settings_group_security),
            // `listOfNotNull`, because one entry is conditional: the duress row is absent, not
            // disabled, during a decoy session.
            listOfNotNull(
                SettingsEntry.Expandable(
                    title = stringResource(R.string.settings_autolock_title),
                    // The summary carries the actual numbers. "Balanced" alone tells the user
                    // nothing about how exposed the vault is, and this is the setting that decides
                    // that — so the answer is on the closed row, not behind a tap.
                    summary = stringResource(
                        R.string.settings_autolock_summary,
                        stringResource(state.autoLockPreset.labelRes),
                        rememberDuration(state.autoLockPreset.idleSeconds),
                        rememberDuration(state.autoLockPreset.backgroundGraceSeconds),
                    ),
                    keywords = "timeout idle background grace aggressive balanced relaxed session expire",
                ) {
                    // FlowRow, not Row: three chips in a fixed row are wider than the screen once
                    // the text scales, and the third was clipped to a 17px sliver at font scale 2.0
                    // — including when it was the selected value, so a low-vision user could not
                    // read the setting they were on. Spec §2 forbids truncating critical content at
                    // large font sizes, and which auto-lock preset is active qualifies. Wrapping
                    // costs nothing at the default size, where all three still fit on one line.
                    // The horizontal gap is wider than the vertical one on purpose: each chip carries a
                    // "30 s / 2 min" caption underneath, and at 8dp the three captions sat close enough
                    // to read as one run of text instead of three belonging to their chips.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(LkSpace.lg),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AutoLockPreset.entries.forEach { preset ->
                            // The durations, not just the name. Comparing the three used to mean
                            // applying each in turn — every tap commits, there is no preview, so
                            // reading this control's documentation meant repeatedly changing a
                            // security setting and leaving the vault on whichever preset was
                            // inspected last. The numbers were already formatted for the closed
                            // row's summary; they simply were not where the choice is made.
                            // The durations sit *under* the pill, not inside it. `LkChip`'s label
                            // is one line with an ellipsis by construction, so at font scale 2.0
                            // the pair this control exists to compare was cut off on two of the
                            // three presets — "Balanced · 2 minutes / 30 s…" (device,
                            // 2026-09-20, filed twice). Below it they wrap, and the pill keeps the
                            // single short word that never truncates.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
                            ) {
                                LkChip(
                                    text = stringResource(preset.labelRes),
                                    selected = state.autoLockPreset == preset,
                                    onClick = { viewModel.setAutoLockPreset(preset) },
                                )
                                Text(
                                    stringResource(
                                        R.string.settings_autolock_chip_durations,
                                        rememberDuration(preset.idleSeconds),
                                        rememberDuration(preset.backgroundGraceSeconds),
                                    ),
                                    style = LkText.caption,
                                    color = LkTheme.colors.textFaint,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // Which number is which. Two bare durations separated by a slash are not
                    // self-describing, and the ordering is the same one the closed row states.
                    Body(stringResource(R.string.settings_autolock_chip_legend))
                    Spacer(Modifier.height(10.dp))
                    Body(stringResource(state.autoLockPreset.descriptionRes))
                },
                SettingsEntry.Expandable(
                    title = stringResource(R.string.settings_biometric_title),
                    // The unavailable case says which unavailable it is. "Unavailable on this
                    // device" was one sentence covering a phone with no sensor, a phone whose owner
                    // has simply not enrolled a fingerprint yet, and a sensor that is busy this
                    // second — and it is false for the middle one, which is also the most common of
                    // the three on a phone somebody has just set up. `biometricUnavailableReason`
                    // is null exactly when the platform says BIOMETRIC_SUCCESS, so the two
                    // available summaries above it are unchanged.
                    summary = when {
                        state.biometricEnabled -> stringResource(R.string.settings_biometric_summary_on)
                        state.biometricAvailable -> stringResource(R.string.settings_biometric_summary_off)
                        else -> stringResource(
                            state.biometricUnavailableReason
                                ?: R.string.settings_biometric_summary_unavailable,
                        )
                    },
                    keywords = "fingerprint face touch id unlock convenience",
                ) {
                    Body(stringResource(R.string.settings_biometric_body), balanced = true)
                    if (!state.biometricEnabled && state.biometricAvailable && activity != null) {
                        Spacer(Modifier.height(12.dp))
                        LkSecondaryButton(
                            text = stringResource(R.string.settings_biometric_enable_button),
                            icon = LkIcons.Fingerprint,
                            onClick = { showBiometricDisclosure = true },
                            enabled = !state.isWorking,
                        )
                    }
                    // Nothing enrolled is the one refusal with an action behind it, so it gets
                    // one. The other three — no sensor, sensor busy, security update pending — have
                    // nothing this app can offer, and a button that opens a screen where there is
                    // nothing to do is worse than no button.
                    if (state.biometricUnavailableReason ==
                        R.string.settings_biometric_summary_not_enrolled
                    ) {
                        Spacer(Modifier.height(12.dp))
                        // No Fingerprint glyph on this button or on "Turn off" below: LkIcons reserves
                        // it for "a biometric prompt will actually be raised", and these two open
                        // system Settings and switch a route off — neither raises one.
                        LkSecondaryButton(
                            text = stringResource(R.string.settings_biometric_enrol_button),
                            onClick = viewModel::openBiometricEnrollSettings,
                            enabled = !state.isWorking,
                        )
                    }
                    // The other half of the switch. The row used to render a button only when
                    // biometrics were off, so turning them on was a one-way door: the summary said
                    // "On for this device" and offered nothing to act on. Not destructive enough
                    // for a confirmation — it removes an unlock route and the master password is
                    // untouched — so it acts directly and says what happened.
                    if (state.biometricEnabled) {
                        Spacer(Modifier.height(12.dp))
                        LkSecondaryButton(
                            text = stringResource(R.string.settings_biometric_disable_button),
                            onClick = viewModel::disableBiometric,
                            enabled = !state.isWorking,
                        )
                    }
                    // The disclosure is a dialog rather than text unfolding inside the row: it is a
                    // consent moment, and consent that appears as more prose in a scrolling list is
                    // the kind users scroll straight past.
                    if (showBiometricDisclosure && activity != null) {
                        LkDialog(
                            onDismiss = { showBiometricDisclosure = false },
                            title = stringResource(R.string.settings_biometric_dialog_title),
                            body = stringResource(R.string.settings_biometric_dialog_body),
                            icon = LkIcons.Fingerprint,
                            confirm = LkDialogAction(
                                label = stringResource(R.string.settings_biometric_dialog_continue),
                                onClick = {
                                    showBiometricDisclosure = false
                                    viewModel.enableBiometric(activity)
                                },
                            ),
                            dismissLabel = stringResource(R.string.settings_biometric_dialog_dismiss),
                        )
                    }
                }.takeIf { !state.isDuressSession },
                // Withheld in a decoy session, both halves of it. `biometricEnabled` is read from
                // DeviceKeyStore, which is device-global rather than vault-scoped, so a decoy
                // session showed the *real* vault's enrolment: the row read "On" and its "Turn off"
                // deleted the real vault's device-wrapped DEK. The enable path was the quieter
                // problem — `enableBiometricUnlock` goes through `realSessionSnapshot`, whose
                // `check(!isDuress)` throws a plain IllegalStateException, so the coercer
                // authenticates successfully at the system prompt and gets "That didn't go through.
                // Try again." on repeat. That is verbatim the tell the change-password row was
                // hidden to avoid: an outcome a healthy real vault never produces.
                SettingsEntry.Expandable(
                    title = stringResource(R.string.settings_password_title),
                    summary = stringResource(R.string.settings_password_summary),
                    keywords = "master change rotate credentials new password",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // A password changed because the old one may have been seen has to take
                        // every other route to the same key with it, so this replaces the vault's
                        // encryption key rather than only rewrapping it. Both consequences are
                        // stated before the fields, not discovered afterwards.
                        Body(stringResource(R.string.settings_password_body))
                        val mismatch = confirmPassword.isNotEmpty() && newPassword != confirmPassword
                        val tooShort = newPassword.isNotEmpty() &&
                            newPassword.length < MIN_MASTER_PASSWORD_LENGTH
                        // Scored here rather than in the ViewModel, for the reason
                        // BackupSettingsScreen gives at its own meter: no secret reaches the
                        // instance-state Bundle, and
                        // `produceState` keeps zxcvbn off the frame — its matching is superlinear
                        // in length — cancelling the previous run on each keystroke. Nought until a
                        // score arrives, so the advice below fails towards "not yet strong".
                        val newPasswordScore by produceState(0, newPassword) {
                            value = if (newPassword.isEmpty()) {
                                0
                            } else {
                                withContext(Dispatchers.Default) { StrengthAnalyzer.score(newPassword) }
                            }
                        }
                        val currentPasswordLabel = stringResource(R.string.settings_password_current_label)
                        val newPasswordLabel = stringResource(R.string.settings_password_new_label)
                        val confirmPasswordLabel = stringResource(R.string.settings_password_confirm_label)
                        // The credential half of the form: the current password, or — for the one
                        // user this branch exists for — the Recovery Kit that is the only thing
                        // they still have. Without it the Kit could open the vault and never give
                        // it back: this form asked for the password they had forgotten, so a
                        // recovery unlock left them tied to a sheet of paper for every cold start.
                        if (usingRecoveryKit) {
                            Body(stringResource(R.string.settings_password_kit_body))
                            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                                LkFieldLabel(stringResource(R.string.settings_password_kit_label))
                                // Not a password field: 24 words are transcribed from paper, and
                                // masking them hides the transcription errors that are the whole
                                // reason a kit fails. Nothing here is a secret the user knows by
                                // heart and might be shoulder-surfed mid-typing — it is a document
                                // they are already holding in the open.
                                LkTextField(
                                    value = recoveryWords,
                                    onValueChange = {
                                        viewModel.updatePasswordForm(passwordForm.copy(recoveryWords = it))
                                    },
                                    placeholder = stringResource(R.string.settings_password_kit_placeholder),
                                    singleLine = false,
                                    keyboardOptions = KeyboardOptions(
                                        // The same guard `UnlockScreen`'s recovery field carries, and
                                        // for the same reason in its words: a keyboard "helpfully"
                                        // retyping a BIP-39 word into an ordinary English one is a
                                        // failure the user cannot see and cannot explain. This field
                                        // had none — `KeyboardOptions.Default` leaves
                                        // `autoCorrectEnabled` null, which resolves to true — so the
                                        // one screen reached by someone who has already forgotten
                                        // their master password was the one correcting their only
                                        // way back in. Capitalisation is harmless here
                                        // (`RecoveryKit.canonicalMnemonic` lowercases) and is set
                                        // anyway so the two fields read identically.
                                        // And, as there, the password keyboard variation:
                                        // `autoCorrectEnabled = false` is a hint Gboard ignores
                                        // (measured 2026-09-14), this is the flag it honours.
                                        keyboardType = KeyboardType.Password,
                                        autoCorrectEnabled = false,
                                        capitalization = KeyboardCapitalization.None,
                                        imeAction = ImeAction.Default,
                                    ),
                                    visualTransformation = VisualTransformation.None,
                                )
                            }
                        } else {
                            PasswordField(
                                currentPassword,
                                { viewModel.updatePasswordForm(passwordForm.copy(currentPassword = it)) },
                                currentPasswordLabel,
                                revealed = revealCurrentPassword,
                                onToggleReveal = { revealCurrentPassword = !revealCurrentPassword },
                            )
                        }
                        PasswordField(
                            newPassword,
                            { viewModel.updatePasswordForm(passwordForm.copy(newPassword = it)) },
                            newPasswordLabel,
                            revealed = revealNewPassword,
                            onToggleReveal = { revealNewPassword = !revealNewPassword },
                        )
                        // The same floor the ViewModel enforces, shown while it can still be acted
                        // on. Only a character count stood here, so a user could replace a strong
                        // master password with one zxcvbn rejects and learn it only from a refusal
                        // after the form had been wiped. Emitted straight into this `spacedBy`
                        // Column rather than from a helper, the way BackupSettingsScreen's export
                        // meter is: a composable emitting several children into an arranged Column
                        // would have to repeat the arrangement itself.
                        // Inline, and the submit below is disabled on it. The floor used to be
                        // checked only inside the ViewModel, which refuses *after* this
                        // composable's onClick has cleared all three wells — so a rule the form
                        // could have stated up front cost the user everything they had typed.
                        if (tooShort) {
                            Text(
                                stringResource(
                                    R.string.settings_error_password_too_short,
                                    MIN_MASTER_PASSWORD_LENGTH,
                                ),
                                style = LkText.caption,
                                color = LkTheme.colors.coralText,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        if (showsStrengthMeter(newPassword)) {
                            LkStrengthMeter(LkStrength.ofScore(newPasswordScore))
                            if (newPasswordScore < StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE) {
                                Text(
                                    stringResource(R.string.settings_error_password_too_weak),
                                    style = LkText.caption,
                                    color = LkTheme.colors.coralText,
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                )
                            }
                        }
                        // The ring here does two jobs: it matches onboarding's treatment of the same
                        // condition, and it is what tells the field to scroll the sentence below it
                        // into view when the keyboard has shortened the viewport.
                        PasswordField(
                            confirmPassword,
                            { viewModel.updatePasswordForm(passwordForm.copy(confirmPassword = it)) },
                            confirmPasswordLabel,
                            isError = mismatch,
                            revealed = revealConfirmPassword,
                            onToggleReveal = { revealConfirmPassword = !revealConfirmPassword },
                        )
                        val credentialGiven = if (usingRecoveryKit) {
                            recoveryWords.isNotBlank()
                        } else {
                            currentPassword.isNotEmpty()
                        }
                        val ready = changePasswordReady(
                            isWorking = state.isWorking,
                            credentialGiven = credentialGiven,
                            newPassword = newPassword,
                            newPasswordScore = newPasswordScore,
                            confirmPassword = confirmPassword,
                        )
                        if (mismatch) {
                            Text(
                                // Both halves borrowed from the onboarding confirm field, which
                                // shows the same message under the same condition. "yet" because
                                // this fires on the first character typed into an empty confirm
                                // field, when nothing has gone wrong; and a live region because
                                // text appearing under a field is, to a screen reader, a change
                                // that never happened.
                                stringResource(R.string.settings_password_mismatch),
                                style = LkText.caption,
                                color = LkTheme.colors.coralText,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        // On a change that actually happened, the replacement and its
                        // confirmation leave composition — the other half of the rule the submit
                        // below follows. Keyed on the message rather than cleared in `onClick`:
                        // `runOperation` sets `message = null` before it starts and the result
                        // after, so this re-arms for a second change and never fires on a refusal.
                        LaunchedEffect(state.message) {
                            if (state.message is SettingsMessage.PasswordChanged) {
                                viewModel.updatePasswordForm(
                                    passwordForm.copy(newPassword = "", confirmPassword = ""),
                                )
                            }
                        }
                        LkPrimaryButton(
                            text = stringResource(R.string.settings_password_submit),
                            enabled = ready,
                            onClick = {
                                // Read from the form as it is at the tap, not as last composed.
                                val form = passwordForm
                                val newChars = form.newPassword.toCharArray()
                                val currentChars = form.currentPassword.toCharArray()
                                val words = form.recoveryWords.trim()
                                // The credential is spent by the attempt and goes now, as it
                                // always has. The *replacement* does not: a mistyped current
                                // password discarded a correct new password and its confirmation
                                // with it, so one typo in one well emptied all three and the user
                                // retyped what they had already got right (device, 2026-09-20,
                                // 3A-F29). Both wells are ViewModel-held — nothing about them
                                // reaches the instance-state Bundle — and they have held the same
                                // characters since they were typed, so keeping them until the
                                // result arrives exposes nothing the form did not already expose.
                                viewModel.updatePasswordForm(
                                    form.copy(currentPassword = "", recoveryWords = ""),
                                )
                                if (form.usingRecoveryKit) {
                                    currentChars.fill('\u0000')
                                    viewModel.changePasswordWithRecoveryKit(words, newChars)
                                } else {
                                    viewModel.changePassword(currentChars, newChars)
                                }
                            },
                        )
                        // The route between the two, both ways. Offered rather than hidden behind
                        // a failed attempt: someone who has forgotten their password does not
                        // discover the way out by guessing at it first.
                        LkTextButton(
                            text = stringResource(
                                if (usingRecoveryKit) {
                                    R.string.settings_password_current_cta
                                } else {
                                    R.string.settings_password_forgot_cta
                                },
                            ),
                            onClick = {
                                // Whichever credential was half-typed is dropped on the way out,
                                // so switching modes never leaves one behind the other.
                                viewModel.updatePasswordForm(
                                    passwordForm.copy(
                                        currentPassword = "",
                                        recoveryWords = "",
                                        usingRecoveryKit = !passwordForm.usingRecoveryKit,
                                    ),
                                )
                            },
                        )
                    }
                    // Present in a decoy session, and it changes the *decoy’s* password there.
                    //
                    // It was withheld, on the reasoning that the form was reachable and could not
                    // work: `changeMasterPassword` goes through `realSessionSnapshot`, so submitting
                    // the duress password as the current one failed with the generic "That didn't
                    // go through. Try again." — an outcome the real vault answers with a specific
                    // wrong-password message instead. Withholding it traded that tell for a bigger
                    // one: four SECURITY rows missing is something a coercer can see by scrolling,
                    // with no knowledge of the feature at all (3A-F23). The form works now — see
                    // `VaultLockManager.changeDuressPassword`, which rewrites slot 1 and nothing
                    // else — so there is nothing left to hide from.
                },
                // Filed under Security, not Vault. The drawer's SECURITY group and the bottom
                // bar's "Security" destination both point at this screen, so having Settings alone
                // call it a Vault concern meant a user scanning Security for a password audit did
                // not find one.
                SettingsEntry.Action(
                    title = stringResource(R.string.settings_health_title),
                    // The scan is free; only matching against the breach corpus is Pro, and the
                    // screen itself says so where it applies. Gating the whole row meant the one
                    // place that tells you a password is reused was unreachable without paying.
                    summary = stringResource(R.string.settings_health_summary),
                    keywords = "weak reused breached compromised audit dashboard",
                    onClick = onHealthClick,
                ),
                SettingsEntry.Action(
                    title = stringResource(R.string.settings_recovery_kit_title),
                    // The row carries the debt when onboarding was finished without a kit. Not a
                    // separate banner: this is the row that resolves it, and a warning somewhere
                    // else would be one more thing to learn to ignore. It also keeps the outstanding
                    // state findable — settings search reads these summaries.
                    summary = if (state.recoveryKitSaved) {
                        stringResource(R.string.settings_recovery_kit_summary)
                    } else {
                        stringResource(R.string.settings_recovery_kit_summary_missing)
                    },
                    warning = !state.recoveryKitSaved,
                    keywords = "recovery kit mnemonic 24 words seed forgot lost reset backup phrase",
                    onClick = onRecoveryKitClick,
                    // Absent in a decoy session. `prepareRecoveryKit` returns null there, so the
                    // screen's Create button answered with "Couldn't create a Recovery Kit for this
                    // vault." — a message a healthy real vault never produces. See the matching
                    // note on the drawer row in AppShell, which records why this reverses an earlier
                    // decision to keep the row visible. F14 QA persona, 2026-08-30.
                ).takeIf { !state.isDuressSession },
                // Withheld entirely in the decoy session — not disabled, not greyed out, absent.
                // The row's own summary names the feature, and its body used to promise the reader
                // that "your real vault is untouched and gives no sign it exists", which is a
                // signed confession that one exists. A coercer who has been handed the duress
                // password and then scrolls Settings learns from this row that there is more to
                // demand. Dropping the entry also removes it from search, which reads this list.
                if (state.isDuressSession) {
                    null
                } else {
                    SettingsEntry.Expandable(
                        // Through the constant, not the resource directly: the drawer's focus
                        // request and this row's rendered title have to be the same string in
                        // every language, and naming one resource in two places is how they drift.
                        title = stringResource(SettingsFocus.DURESS),
                        summary = if (state.isPro) {
                            stringResource(R.string.settings_duress_summary_pro)
                        } else {
                            stringResource(R.string.settings_duress_summary_free)
                        },
                        keywords = "decoy coercion fake second password plausible deniability",
                    ) {
                        ProGate(
                            isPro = state.isPro,
                            content = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Body(stringResource(R.string.settings_duress_body_explain))
                                    // Said here as well as in the dialog, because this is also the
                                    // only way to *change* a duress password — so someone doing the
                                    // ordinary thing arrives at a destructive action.
                                    Body(stringResource(R.string.settings_duress_body_replace_warning))
                                    val duressPasswordLabel = stringResource(R.string.settings_duress_password_label)
                                    val confirmDuressLabel = stringResource(R.string.settings_duress_confirm_label)
                                    val setupDuressLabel = stringResource(R.string.settings_duress_setup_button)
                                    val duressMismatch = duressForm.confirmPassword.isNotEmpty() &&
                                        duressForm.password != duressForm.confirmPassword
                                    PasswordField(
                                        duressForm.password,
                                        { viewModel.updateDuressForm(duressForm.copy(password = it)) },
                                        duressPasswordLabel,
                                    )
                                    // The length floor, stated while it can be acted on. It was refused only
                                    // after the vault-password dialog had been answered and the form wiped.
                                    // Directly under the well it is about, and brought into view when it
                                    // appears: below the confirmation it was added past the keyboard's edge,
                                    // and the person typing saw the button stay grey with no reason (D3-L-15).
                                    if (duressPasswordTooShort(duressForm.password)) {
                                        val refusalInView = remember { BringIntoViewRequester() }
                                        LaunchedEffect(Unit) { refusalInView.bringIntoView() }
                                        Text(
                                            stringResource(
                                                R.string.settings_error_duress_password_too_short,
                                                MIN_MASTER_PASSWORD_LENGTH,
                                            ),
                                            style = LkText.caption,
                                            color = LkTheme.colors.coralText,
                                            modifier = Modifier
                                                .bringIntoViewRequester(refusalInView)
                                                .semantics { liveRegion = LiveRegionMode.Polite },
                                        )
                                    }
                                    PasswordField(
                                        duressForm.confirmPassword,
                                        { viewModel.updateDuressForm(duressForm.copy(confirmPassword = it)) },
                                        confirmDuressLabel,
                                        isError = duressMismatch,
                                    )
                                    // The button below merely dims on a mismatch; without this the
                                    // refusal was marked nowhere, unlike the master-change and
                                    // onboarding confirm fields. Same message and live region as
                                    // the master-password form, so a typo in the confirm field is
                                    // explained from where the submit sits rather than reading as a
                                    // broken screen.
                                    if (duressMismatch) {
                                        Text(
                                            stringResource(R.string.settings_password_mismatch),
                                            style = LkText.caption,
                                            color = LkTheme.colors.coralText,
                                            modifier = Modifier.semantics {
                                                liveRegion = LiveRegionMode.Polite
                                            },
                                        )
                                    }
                                    LkPrimaryButton(
                                        text = setupDuressLabel,
                                        enabled = duressSetupReady(
                                            isWorking = state.isWorking,
                                            password = duressForm.password,
                                            confirmPassword = duressForm.confirmPassword,
                                        ),
                                        // Always asked, never conditioned on whether a decoy already
                                        // exists: the vault header carries two slots whether or not
                                        // duress is configured, on purpose, so the app genuinely
                                        // cannot know — and a prompt that appeared only when a decoy
                                        // existed would answer that question out loud.
                                        onClick = {
                                            viewModel.updateDuressForm(
                                                duressForm.copy(confirmSetup = true),
                                            )
                                        },
                                    )
                                }
                                if (duressForm.confirmSetup) {
                                    LkDialog(
                                        onDismiss = viewModel::dismissDuressConfirmations,
                                        title = stringResource(R.string.settings_duress_replace_dialog_title),
                                        body = stringResource(R.string.settings_duress_replace_dialog_body),
                                        confirm = LkDialogAction(
                                            label = stringResource(R.string.settings_duress_setup_button),
                                            emphasis = LkActionEmphasis.Destructive,
                                            enabled = !state.isWorking &&
                                                duressForm.gatePassword.isNotBlank(),
                                            // The copy-out and the clear both live in the
                                            // ViewModel now: it owns the wells, so it is what turns
                                            // them into the arrays the lock manager consumes and
                                            // wipes, and what empties the form afterwards.
                                            onClick = viewModel::submitDuressSetup,
                                        ),
                                    ) {
                                        DuressGateField(
                                            value = duressForm.gatePassword,
                                            onValueChange = {
                                                viewModel.updateDuressForm(
                                                    duressForm.copy(gatePassword = it),
                                                )
                                            },
                                        )
                                    }
                                }
                            },
                            lockedContent = {
                                ProUpsell(
                                    state,
                                    activity,
                                    viewModel,
                                    stringResource(R.string.settings_duress_upsell_purpose),
                                )
                            },
                        )
                        // Removal sits OUTSIDE the gate, and setup sits inside it, because the two
                        // answer to different rules. Setup is a Pro feature and may be sold. Removal
                        // is the way out of a security feature the user already armed, and
                        // entitlement can be lost without any act of theirs — a Google refund, a
                        // changed Play account, a re-install that reconciles against a different
                        // one. Inside the gate, that user's Settings row collapsed to an upsell
                        // while `DuressUnlocker` kept honouring the decoy password: an armed
                        // coercion vault with no UI path to disarm it, recoverable only by buying
                        // Pro a second time. `removeDuress()` never checked `isPro` itself, so the
                        // gate was the whole of the restriction and this is the whole of the fix.
                        //
                        // Showing the control to a user who never bought Pro discloses nothing:
                        // this row refuses to say whether a decoy is configured in either state,
                        // which is the plausible-deniability property that makes it safe to show
                        // unconditionally. Removal on a vault with no decoy is a no-op.
                        Spacer(Modifier.height(12.dp))
                        LkTextButton(
                            text = stringResource(R.string.settings_duress_remove_button),
                            // Gated on `isWorking` for a sharper reason than the Setup button:
                            // `removeDuress` opens with a bare
                            // `if (isDuressSession || isWorking) return`, which writes neither a
                            // message nor an error. So during another Settings operation the
                            // destructive confirmation still opened, the user answered it, it
                            // closed — and nothing happened. This row deliberately never reveals
                            // whether a decoy is configured, so the transient status note is its
                            // only feedback channel and there was no second way to notice: the
                            // coercion escape hatch the user believed they had just removed was
                            // still armed.
                            enabled = !state.isWorking,
                            onClick = {
                                viewModel.updateDuressForm(duressForm.copy(confirmRemoval = true))
                            },
                        )
                        // `!state.isWorking` as well as the flag, the same pairing
                        // BackupSettingsScreen uses for its Replace confirmation: a
                        // configuration change restores this dialog from the ViewModel, and
                        // it can land on top of an operation that started after it was
                        // raised — which no gate on the button itself can cover.
                        if (duressForm.confirmRemoval && !state.isWorking) {
                            LkDialog(
                                onDismiss = viewModel::dismissDuressConfirmations,
                                title = stringResource(R.string.settings_duress_remove_dialog_title),
                                body = stringResource(R.string.settings_duress_remove_dialog_body),
                                icon = LkIcons.Trash,
                                accent = LkTheme.colors.coral,
                                confirm = LkDialogAction(
                                    label = stringResource(R.string.settings_duress_remove_confirm),
                                    emphasis = LkActionEmphasis.Destructive,
                                    enabled = duressForm.gatePassword.isNotBlank(),
                                    onClick = viewModel::submitDuressRemoval,
                                ),
                            ) {
                                DuressGateField(
                                    value = duressForm.gatePassword,
                                    onValueChange = {
                                        viewModel.updateDuressForm(
                                            duressForm.copy(gatePassword = it),
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
                SettingsEntry.Expandable(
                    title = stringResource(R.string.settings_panic_title),
                    summary = stringResource(R.string.settings_panic_summary),
                    keywords = "lock now emergency coercion police border stolen phone immediately",
                ) {
                    val panicContext = LocalContext.current
                    Body(stringResource(R.string.settings_panic_body))
                    Spacer(Modifier.height(12.dp))
                    LkTintButton(
                        text = stringResource(R.string.settings_panic_button),
                        // Clipboard cleared here rather than inside `panicLock()`: `:core:vault`
                        // cannot reach `:core:ui`, and a ViewModel must not hold a Context. Same
                        // pairing as the header's long-press panic in `LkLockButton`, and for the
                        // same reason - ordinary lock deliberately spares the clipboard so a paste
                        // in another app still works; panic lock has no paste to protect.
                        onClick = {
                            ClipboardHelper.clearNow(panicContext)
                            viewModel.panicLock()
                        },
                        accent = LkTheme.colors.coral,
                        icon = LkIcons.Lock,
                    )
                },
            ),
        ),
        // Unconditional — no `listOfNotNull`, no `if`, and no state read anywhere in the row.
        //
        // A Hidden-items row that appeared only once something was hidden would itself announce that
        // something is, which is the one fact the feature exists to withhold; and it would be missing
        // for the user who most needs it, the one who has forgotten the reveal phrase or set an
        // indefinite lease. For the same reason the summary carries no count and no status: this row
        // reads identically with nothing hidden, with one entry hidden, and in a decoy session.
        SettingsGroup(
            stringResource(R.string.settings_group_privacy),
            listOf(
                SettingsEntry.Action(
                    title = stringResource(R.string.settings_hidden_items_title),
                    summary = stringResource(R.string.settings_hidden_items_summary),
                    keywords = "hidden hide conceal invisible reveal phrase unhide recover restore",
                    onClick = onHiddenItemsClick,
                    testTag = HIDDEN_ITEMS_TEST_TAG,
                ),
                // The way back from the File editor's "Don't ask again" (2026-10-09). A checkbox
                // that switches something off for good needs a place to switch it on again, or
                // it is a one-way door the user cannot see.
                SettingsEntry.Custom(
                    title = stringResource(R.string.settings_original_delete_title),
                    summary = stringResource(R.string.settings_original_delete_summary),
                    keywords = "original gallery photo file delete copy import downloads remove",
                ) { first ->
                    val context = LocalContext.current
                    var offered by remember { mutableStateOf(LocalKeepSettings.isOriginalDeleteOffered(context)) }
                    LkSwitchRow(
                        label = stringResource(R.string.settings_original_delete_title),
                        hint = stringResource(R.string.settings_original_delete_summary),
                        checked = offered,
                        onCheckedChange = {
                            LocalKeepSettings.setOriginalDeleteOffered(context, it)
                            offered = it
                        },
                        first = first,
                        modifier = Modifier.lkTag("settings-original-delete"),
                    )
                },
            ),
        ),
        // Above Vault. These two rows are the ones a new user has to find — until autofill
        // is on, the app only stores passwords and never offers them, which is most of the point.
        // They were fourth, below four backup and import rows that matter once and then never
        // again.
        SettingsGroup(
            stringResource(R.string.settings_group_autofill),
            // `listOfNotNull`: the Trusted apps row is absent in a decoy session, not disabled.
            listOfNotNull(
                SettingsEntry.Action(
                    title = stringResource(R.string.settings_autofill_service_title),
                    // State, not a description of the feature. Material's rule for a settings row
                    // is that its secondary text says what the thing is currently set to; this one
                    // said what autofill *is*, so a user who had never turned it on and a user for
                    // whom it had been working for months read the same sentence, and the row that
                    // is supposed to answer "is LocalKeep filling my logins?" answered nothing.
                    summary = stringResource(
                        if (state.autofillEnabled) {
                            R.string.settings_autofill_service_summary_on
                        } else {
                            R.string.settings_autofill_service_summary_off
                        },
                    ),
                    keywords = "fill forms browser apps keyboard suggestions enable provider",
                    external = true,
                    onClick = viewModel::openAutofillSettings,
                ),
                // Named in UI/UX spec §10.1 alongside the service and anti-phishing. It is not a
                // toggle — the keyboard app decides whether to render inline suggestions, and the
                // service already builds them (DatasetBuilder.inlinePresentation) whenever the
                // platform asks. So it is surfaced as an always-on fact, the same treatment
                // anti-phishing gets, rather than left with no user-facing surface at all.
                SettingsEntry.Expandable(
                    title = stringResource(R.string.settings_autofill_inline_title),
                    // Conditional, because the unconditional summary is a promise the row cannot
                    // keep while LocalKeep is not the autofill service: nothing appears in any
                    // suggestion bar until it is. The service row directly above already flips;
                    // this one kept saying "Saved logins appear in your keyboard\'s suggestion
                    // bar" with autofill off (device, 2026-09-20), and the qualifying sentence was
                    // only visible once the row was expanded.
                    summary = stringResource(
                        if (state.autofillEnabled) {
                            R.string.settings_autofill_inline_summary
                        } else {
                            R.string.settings_autofill_inline_summary_off
                        },
                    ),
                    keywords = "inline suggestions keyboard suggestion bar strip ime fill",
                ) {
                    Body(stringResource(R.string.settings_autofill_inline_body))
                },
                SettingsEntry.Action(
                    title = stringResource(R.string.settings_trusted_title),
                    summary = stringResource(R.string.settings_trusted_summary),
                    keywords = "trusted apps sites domains manage remove revoke forget autofill pairing",
                    onClick = onTrustedAppsClick,
                    // The only route to this screen is this row, and it sits far enough down a
                    // data-driven list that a sweep scrolling for its label never found it — so the
                    // screen went unmeasured. Tagged for the same reason [HIDDEN_ITEMS_TEST_TAG]
                    // is: a tag is the one handle that survives a longer list and a larger font.
                    testTag = TRUSTED_APPS_TEST_TAG,
                    // Present in a decoy session; the *screen* is what is scoped, not the row.
                    //
                    // The row was withheld because the data behind it is device-global —
                    // `AppSignatureCache` keeps the trust store in its own DataStore, not in the
                    // vault — so a decoy session listed the *real* user's bank, work SSO, exchange
                    // and mail provider: a disclosure of the victim's account inventory to whoever
                    // is holding them to the phone, and a plain statement that the open vault is
                    // not the one that filled those sites. Both of those are properties of the
                    // list, and `TrustedAppsViewModel` now answers a decoy session with an empty
                    // one — which is what a real vault that has trusted nothing shows, and says
                    // nothing about either vault. Taking the row away instead was one of the four
                    // missing SECURITY rows a decoy session could be recognised by (3A-F23).
                ),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    SettingsEntry.Action(
                        title = stringResource(R.string.settings_passkey_title),
                        summary = stringResource(R.string.settings_passkey_summary),
                        keywords = "passkey webauthn fido credential manager passwordless",
                        external = true,
                        onClick = viewModel::openCredentialProviderSettings,
                    )
                } else {
                    SettingsEntry.Custom(
                        title = stringResource(R.string.settings_passkey_title),
                        summary = stringResource(R.string.settings_passkey_summary_unavailable),
                        keywords = "passkey webauthn fido credential manager passwordless",
                    ) { first ->
                        ValueRow(
                            title = stringResource(R.string.settings_passkey_title),
                            value = stringResource(R.string.settings_passkey_value_unavailable),
                            first = first,
                        )
                    }
                },
            ),
        ),
        SettingsGroup(
            stringResource(R.string.settings_group_vault_backup),
            listOf(
                SettingsEntry.Action(
                    title = stringResource(R.string.settings_backup_title),
                    summary = stringResource(R.string.settings_backup_summary),
                    keywords = "export save copy restore archive rolling",
                    onClick = onBackupClick,
                ),
                // Free. Charging for it meant a new user could not bring their passwords in, so
                // they had nothing to manage, so there was nothing to value and no reason to buy —
                // the gate was collecting a toll at the door of an empty room.
                SettingsEntry.Action(
                    title = stringResource(R.string.settings_import_title),
                    summary = stringResource(R.string.settings_import_summary),
                    keywords = "chrome firefox bitwarden 1password lastpass keepass csv move transfer migrate",
                    onClick = onImportClick,
                ),
                // A route to the switch, not a second copy of it.
                //
                // This row used to carry its own `LkSwitchRow` over the same preference the Backup
                // screen's switch writes, and the two spelled it opposite ways: Backup asks whether
                // the vault is *included*, so jade-on means included; this one asked whether cloud
                // backup is *disabled*, so jade-on meant excluded. One accent colour, two opposite
                // meanings, one preference — which is what the 2026-09-09 gate filed as D19 and
                // scoped to the Backup screen alone, missing this instance entirely.
                //
                // Aligning the labels would have cost three tier-G keys in five catalogues; a
                // per-row colour override would have made a global token decision one screen at a
                // time. Sending the row where the control already lives costs neither, and removes
                // the second writer of a preference that should only ever have had one.
                SettingsEntry.Action(
                    title = stringResource(R.string.settings_cloud_backup_title),
                    summary = stringResource(cloudBackupSummaryRes(state.cloudBackupEnabled)),
                    keywords = "google drive android auto backup disable upload",
                    onClick = onBackupClick,
                ),
            ),
        ),
        SettingsGroup(
            stringResource(R.string.settings_group_appearance),
            listOf(
                // "Why LocalKeep is dark" used to lead this group. It moved to "How this works",
                // where the app already collects its explanations, because it was never a setting.
                // Material's rule for a settings row is that its secondary text carries *state* —
                // what the thing is currently set to — not the reasoning behind it. A row with no
                // control and a paragraph of rationale is documentation wearing a setting's
                // clothes, and it was most of what "Appearance" appeared to contain while the group
                // has exactly one thing in it you can actually change.
                SettingsEntry.Custom(
                    title = stringResource(R.string.settings_haptics_title),
                    summary = stringResource(R.string.settings_haptics_summary),
                    // Someone looking for this searches for what they want gone — "vibration",
                    // "buzz" — not for the accessibility term the setting is filed under.
                    keywords = "vibration vibrate buzz feedback touch accessibility silent quiet",
                ) { first ->
                    val context = LocalContext.current
                    // From the resume re-read, not a `remember {}` of its own: that read once per
                    // composition, so switching touch feedback off in system Settings left this row
                    // unchecked and enabled until Settings was re-entered (device, 2026-10-04).
                    val systemOff = state.systemHapticsOff
                    val preferQuiet by A11yPreferences.reduceHapticsState(context)
                    val hapticsTitle = stringResource(R.string.settings_haptics_title)
                    val hapticsSummary = stringResource(R.string.settings_haptics_summary)
                    val hapticsSystemOffHint = stringResource(R.string.settings_haptics_hint_system_off)
                    LkSwitchRow(
                        label = hapticsTitle,
                        // Shown as on when the system has already silenced haptics, because that
                        // is the truth of what the user will feel. Disabled in that case rather
                        // than hidden: a control that quietly disappears looks like a bug, and
                        // this way the reason is stated in the row itself.
                        checked = preferQuiet || systemOff,
                        onCheckedChange = { A11yPreferences.setReduceHaptics(context, it) },
                        enabled = !systemOff,
                        hint = if (systemOff) {
                            hapticsSystemOffHint
                        } else {
                            hapticsSummary
                        },
                        first = first,
                    )
                },
            ),
        ),
        // The Pro group sits exactly here, on the seam between the two halves of this screen: five
        // operational groups above (Security, Privacy, Autofill, Vault & backup, Appearance), two
        // explanatory ones below (How this works, About). "LocalKeep Pro" is neither a control nor
        // an explanation — it is an account fact, and this seam is the one position derivable from
        // the list's own logic rather than from a preference for top or bottom.
        //
        // Not first. A brass purchase button directly under the Settings title, above auto-lock and
        // change-master-password, is a transaction placed ahead of the security controls of a
        // password manager — and it would not even buy the discoverability it costs: someone who
        // does not know a Pro group exists does not scroll to the top to look for one. They search,
        // which is why PRO_KEYWORDS is the larger half of this fix.
    ) + proGroup(state, viewModel, activity) + listOf(
        // Not settings. Nothing here can be changed, and that is exactly why these used to be the
        // worst of the wall — paragraphs sitting between controls, read past every time.
        // Collected, collapsed, and labelled as what they are.
        SettingsGroup(
            stringResource(R.string.settings_group_how_this_works),
            buildList {
                // Sourced from AlwaysOn so this list and the Security tab cannot end up making
                // different claims about the same cryptography.
                AlwaysOn.facts.forEach { fact ->
                    add(
                        SettingsEntry.Expandable(
                            title = fact.title,
                            summary = fact.summary,
                            keywords = fact.keywords,
                        ) { Body(fact.detail) },
                    )
                }
                // Moved here from "Appearance" on 2026-07-29. It is an explanation, not a setting —
                // it has no control — and Material's rule is that a settings row's secondary text
                // carries state rather than reasoning. It was also most of what "Appearance"
                // appeared to contain, in a group with exactly one changeable thing in it.
                add(
                    SettingsEntry.Expandable(
                        title = stringResource(R.string.settings_dark_title),
                        summary = stringResource(R.string.settings_dark_summary),
                        keywords = "dark light mode colour color night system theme",
                    ) {
                        // The app is dark-only by construction (see LocalKeepTheme), so claiming it
                        // follows the system setting would be untrue.
                        Body(stringResource(R.string.settings_dark_body))
                    },
                )
                add(
                    SettingsEntry.Expandable(
                        title = stringResource(R.string.settings_breach_title),
                        summary = stringResource(R.string.settings_breach_summary),
                        keywords = "corpus vintage compromised passwords have i been pwned date",
                    ) {
                        // Pull-based: the filter is tens of megabytes, so it loads only once this row
                        // is actually opened — not on every Settings open.
                        LaunchedEffect(Unit) { viewModel.loadBreachVintage() }
                        // The date, not the raw header. This row showed `filterVintage()` verbatim
                        // until now, which put "SecLists Pwdb_top-10000000, 2026-07" on screen — an
                        // upstream repo path and a corpus filename, in the one place a user comes to
                        // ask how current the list is. The raw string survives only as the fallback
                        // for a future corpus whose vintage carries no parseable date, because
                        // showing something opaque still beats showing nothing.
                        val breachLocale = LocalConfiguration.current.locales[0]
                        // What the corpus is, before how old it is. The row is titled "The corpus
                        // your passwords are checked against" and its entire body was one
                        // unlabelled month — "July 2026" — which answers a question nobody asked
                        // (device, 2026-09-20). Deliberately says nothing about what does or does
                        // not leave the device: that is a Tier G claim and belongs in the register.
                        Body(stringResource(R.string.settings_breach_body))
                        Spacer(Modifier.height(6.dp))
                        Body(
                            when (val vintage = state.breachVintage) {
                                null -> stringResource(R.string.settings_breach_checking)
                                is BreachVintage.Available -> stringResource(
                                    R.string.settings_breach_vintage,
                                    vintage.yearMonth?.format(
                                        // getBestDateTimePattern, not a hardcoded "MMMM yyyy":
                                        // month-then-year is an English habit and the five shipped
                                        // locales do not all share it.
                                        DateTimeFormatter.ofPattern(
                                            DateFormat.getBestDateTimePattern(breachLocale, "yMMMM"),
                                            breachLocale,
                                        ),
                                    ) ?: vintage.text,
                                )
                                BreachVintage.Unavailable -> stringResource(R.string.settings_breach_unavailable)
                            },
                        )
                    },
                )
            },
        ),
        SettingsGroup(
            stringResource(R.string.settings_group_about),
            listOf(
                SettingsEntry.Custom(
                    title = stringResource(R.string.settings_version_title),
                    summary = state.appVersion,
                    keywords = "build number release app",
                ) { first ->
                    ValueRow(
                        title = stringResource(R.string.settings_version_title),
                        value = state.appVersion.ifBlank { stringResource(R.string.settings_version_unknown) },
                        first = first,
                    )
                },
                SettingsEntry.Action(
                    title = stringResource(R.string.settings_licenses_title),
                    summary = stringResource(R.string.settings_licenses_summary),
                    keywords = "licences attribution third party notices oss credits",
                    onClick = { showLicenses = true },
                ),
            ),
        ),
    ) + debugTierGroup(state, viewModel)

    val visible = searchSettings(groups, query)
    val c = LkTheme.colors
    val searching = query.isNotBlank()

    // Status is rendered at the top of the list, which is only "where the user is looking" on a
    // screen that has not been scrolled. Settings is eight groups long in a shipped build: the row
    // that produced the message is routinely a screen or two below the banner reporting on it, so
    // an outcome could be written and never seen. That is how a broken Passkey provider row read as
    // one that did nothing at all rather than one that failed.
    val scroll = rememberScrollState()
    // A confirmation of a change that already happened goes where every other screen's does: to
    // the app-wide notice host, which floats it and retires it. Drawn here it stayed at the top of
    // Settings until the next operation — "Auto-lock updated" two minutes on (device, 2026-10-04).
    // What stays inline still asks something of the user; see [isTransientConfirmation].
    LaunchedEffect(state.message) {
        val message = state.message
        if (message is SettingsMessage.Simple && message.isTransientConfirmation()) {
            LkNotices.post(message.textRes)
            viewModel.consumeMessage(message)
        }
    }
    val inlineMessage = state.message?.takeUnless { it.isTransientConfirmation() }
    LaunchedEffect(inlineMessage, state.error) {
        if (inlineMessage != null || state.error != null) scroll.animateScrollTo(0)
    }

    LkScreen(accent = c.jade, auraAlpha = 0.07f, auraBias = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                // The shell keeps the keyboard out of its own padding, so this is where the
                // viewport shrinks for it. Without it the change-password wells and their button
                // sat under the keyboard and the master password was typed blind: `LkTextField`'s
                // bring-into-view had nothing to scroll because the viewport never got shorter.
                // Outside the scroll so it is the scroller's height that gives — padding inside
                // `verticalScroll` would only be dead scroll after the last row.
                .imePadding()
                .verticalScroll(scroll)
                .padding(horizontal = LkFrame.screenHorizontal)
                .padding(top = LkFrame.screenTop, bottom = LkFrame.gutterPlain),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LkScreenHeader(
                title = stringResource(R.string.settings_screen_title),
                modifier = Modifier.lkEnter(),
                trailing = {
                    LkIconButton(
                        icon = LkIcons.ChevronLeft,
                        label = stringResource(R.string.settings_back),
                        onClick = onBack,
                        plateAlignment = Alignment.CenterEnd,
                    )
                },
            )

            LkSearchField(
                value = query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = stringResource(R.string.settings_search_placeholder),
                modifier = Modifier.lkEnter(delayMillis = 40),
            )

            // Status is rendered above the results, not at the bottom of a long scroll where the
            // outcome of the button you just pressed would be off-screen.
            //
            // `.announce`, not a bare live region on the container: LkInlineNote/LkInlineError put
            // the message in a child Text, so a `liveRegion` on the Row itself announces a node
            // that carries no text or contentDescription of its own — the confirmation a user is
            // waiting on was silent to a screen reader. Every other module uses Modifier.announce,
            // which pins the message to the live-region node.
            inlineMessage?.let {
                val message = resolveMessage(it)
                LkInlineNote(message, Modifier.announce(message))
            }
            state.error?.let {
                val message = resolveMessage(it)
                LkInlineError(message, Modifier.announce(message))
            }
            if (state.isWorking) {
                val working = stringResource(R.string.settings_working)
                LkInlineNote(
                    working,
                    accent = c.textMuted,
                    icon = LkIcons.Refresh,
                    modifier = Modifier.announce(working),
                )
            }

            if (visible.isEmpty()) {
                LkEmptyState(
                    icon = LkIcons.Search,
                    title = stringResource(R.string.settings_empty_title),
                    // The third example is swapped in the decoy session. Suggesting the user
                    // search for “decoy” names a feature whose row is deliberately not in this
                    // list — a help string is a strange place to give the game away, and it is
                    // exactly the sort of thing that survives an audit of the rows themselves.
                    body = stringResource(
                        R.string.settings_empty_body,
                        query,
                        stringResource(
                            if (state.isDuressSession) {
                                R.string.settings_empty_word_backup
                            } else {
                                R.string.settings_empty_word_decoy
                            },
                        ),
                    ),
                    modifier = Modifier.padding(top = 40.dp),
                    action = {
                        LkSecondaryButton(stringResource(R.string.settings_clear_search), { viewModel.onQueryChanged("") })
                    },
                )
            }

            visible.forEachIndexed { groupIndex, group ->
                Column(
                    modifier = Modifier.lkEnter(delayMillis = 80 + lkStaggerDelay(groupIndex)),
                    // No arrangement: `sm` — a section label to the group it heads — is still the
                    // gap, but [LkSectionLabel] supplies it (LkSectionLabelGap). This was 10dp here
                    // against the item view's 9dp for the identical construct; neither number was
                    // chosen, and a 1dp difference between two screens is not a design decision
                    // anyone made. A `spacedBy` left here would stack on the label's own padding.
                ) {
                    LkSectionLabel(group.title)
                    LkGroup {
                        group.entries.forEachIndexed { index, entry ->
                            // Keyed so an entry keeps its identity as the filter reorders the list.
                            key(group.title, entry.title) {
                                EntryRow(
                                    entry = entry,
                                    first = index == 0,
                                    // A search that returns one row should show it, not make the
                                    // user tap again to find out whether it is what they wanted.
                                    open = searching || entry.title in expanded.value,
                                    onToggle = {
                                        expanded.value = if (entry.title in expanded.value) {
                                            expanded.value - entry.title
                                        } else {
                                            expanded.value + entry.title
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLicenses) {
        LicensesDialog(includePlayOnly = state.canPurchase, onDismiss = { showLicenses = false })
    }
}

/**
 * Whether Back on Settings collapses the open rows rather than leaving.
 *
 * Only when something is open and no search is showing. A search draws every row it finds open
 * whatever [expanded] holds, so clearing the set under one changes nothing on screen: arriving from
 * the drawer's "Duress / decoy", searched to that row, the first Back did nothing visible and the
 * second left (QA 2026-10-03).
 */
internal fun settingsBackCollapses(query: String, expanded: Set<String>): Boolean =
    query.isBlank() && expanded.isNotEmpty()

/**
 * Which "Cloud backup" summary to show, given the live [SettingsUiState.cloudBackupEnabled].
 *
 * Extracted so the selection itself — not just the strings it picks between — is covered by a
 * plain unit test (F-41): the row used to show a single static sentence regardless of the actual
 * preference, and read as a claim of state that could contradict the switch on the Backup screen.
 */
@StringRes
internal fun cloudBackupSummaryRes(cloudBackupEnabled: Boolean): Int =
    if (cloudBackupEnabled) {
        R.string.settings_cloud_backup_summary_included
    } else {
        R.string.settings_cloud_backup_summary
    }

/**
 * Resolves a [SettingsMessage] into display text. The single place that turns a ViewModel-held
 * resource id (plus whatever args it carries) into a String, so `SettingsViewModel` itself never
 * holds one — see the extraction brief's "ViewModels must not hold display strings" rule.
 */
@Composable
private fun resolveMessage(message: SettingsMessage): String = when (message) {
    is SettingsMessage.Simple -> stringResource(message.textRes)
    is SettingsMessage.WithArgs -> stringResource(message.textRes, *message.args.toTypedArray())
    is SettingsMessage.SessionEnded ->
        stringResource(message.textRes, stringResource(message.operationRes))
    is SettingsMessage.RetryAfter -> pluralStringResource(message.pluralsRes, message.seconds, message.seconds)
    is SettingsMessage.PasswordChanged -> buildString {
        append(stringResource(R.string.settings_password_changed))
        if (message.recoveryKitVoided) {
            append(" ")
            append(stringResource(R.string.settings_password_changed_recovery_kit_voided))
        }
        if (message.biometricDisabled) {
            append(" ")
            append(stringResource(R.string.settings_password_changed_biometric_note))
        }
        if (message.reKeyedCount > 0) {
            append(" ")
            append(
                pluralStringResource(
                    R.plurals.settings_password_changed_rekeyed,
                    message.reKeyedCount,
                    message.reKeyedCount,
                ),
            )
        }
        if (message.destroyedCount > 0) {
            append(" ")
            append(
                pluralStringResource(
                    R.plurals.settings_password_changed_destroyed,
                    message.destroyedCount,
                    message.destroyedCount,
                ),
            )
        }
        if (message.leftBehindCount > 0) {
            append(" ")
            append(
                pluralStringResource(
                    R.plurals.settings_password_changed_left_behind,
                    message.leftBehindCount,
                    message.leftBehindCount,
                ),
            )
        }
        if (message.blobsPendingCount > 0) {
            append(" ")
            append(
                pluralStringResource(
                    R.plurals.settings_password_changed_blobs_pending,
                    message.blobsPendingCount,
                    message.blobsPendingCount,
                ),
            )
        }
    }
}

/** Renders one [SettingsEntry] as a row in the enclosing [LkGroup]. */
@Composable
private fun EntryRow(
    entry: SettingsEntry,
    first: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
) {
    when (entry) {
        is SettingsEntry.Custom -> entry.content(first)

        is SettingsEntry.Action -> LkGroupRow(
            first = first,
            onClick = entry.onClick,
            modifier = Modifier
                .labelled(
                    stringResource(R.string.settings_entry_action_label, entry.title, entry.summary),
                )
                .let { if (entry.testTag != null) it.lkTag(entry.testTag) else it },
        ) {
            TitleAndSummary(
                entry.title,
                entry.summary,
                Modifier.weight(1f),
                entry.destructive,
                warning = entry.warning,
            )
            Icon(
                if (entry.external) LkIcons.OpenExternal else LkIcons.ChevronRight,
                contentDescription = null,
                tint = LkTheme.colors.textFaint,
                modifier = Modifier.size(16.dp),
            )
        }

        is SettingsEntry.Expandable -> Column {
            val turn by animateFloatAsState(
                targetValue = if (open) 90f else 0f,
                animationSpec = lkTween(LkMotion.Quick),
                label = "settings-disclosure",
            )
            val stateWord = stringResource(
                if (open) R.string.settings_state_expanded else R.string.settings_state_collapsed,
            )
            LkGroupRow(
                first = first,
                onClick = onToggle,
                modifier = Modifier
                    .labelled(announced(entry.title, entry.summary, stateWord))
                    .let { if (entry.testTag != null) it.lkTag(entry.testTag) else it },
            ) {
                TitleAndSummary(entry.title, entry.summary, Modifier.weight(1f))
                Icon(
                    LkIcons.ChevronRight,
                    contentDescription = null,
                    tint = LkTheme.colors.textFaint,
                    modifier = Modifier.size(16.dp).rotate(turn),
                )
            }
            AnimatedVisibility(
                visible = open,
                enter = fadeIn(lkTween(LkMotion.Quick)) + expandVertically(lkTween(LkMotion.Base)),
                exit = fadeOut(lkTween(LkMotion.Quick)) + shrinkVertically(lkTween(LkMotion.Base)),
            ) {
                Column(
                    Modifier
                        // Inset and tinted so the body reads as belonging to the row above it
                        // rather than as another row of its own.
                        .fillMaxWidth()
                        .background(LkTheme.colors.wash)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    entry.content()
                }
            }
        }
    }
}

@Composable
private fun RowScope.TitleAndSummary(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    /**
     * The summary is a standing risk, not a description — draw it amber.
     *
     * Exactly one row in Settings qualifies today: a Recovery Kit that was never saved, whose
     * summary says the vault cannot be opened if the master password is forgotten. It rendered in
     * the same `textFaint` as "Needs your current one" and "Balanced · 2 minutes idle", so the one
     * line on the screen with a permanent, unrecoverable consequence looked exactly like a
     * preference hint. Amber and not coral, per LkColors' own test: nothing has failed and nothing
     * is compromised — there is something here worth acting on.
     */
    warning: Boolean = false,
) {
    val c = LkTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = LkText.body, color = if (destructive) c.coralText else c.textStrong)
        if (summary.isNotBlank()) {
            // Wrapping rather than truncating, and with no line budget at all. This was one line,
            // so that every row was the same height and the group could be scanned in one pass — a
            // real benefit, and one that held only because every English summary happened to fit.
            // It did not survive translation: measured on a 1080x1920 device against the 848px text
            // constraint, the number of summaries cut mid-sentence was 1 in English, 3 in
            // Portuguese, 5 in Spanish, 5 in French and 7 in German — including the encryption
            // claim, the panic lock and the Recovery Kit.
            //
            // Two lines was the fix for that, and it was the same mistake one rung higher: a budget
            // in lines is a budget in pixels only for the width and text size it was measured at.
            // At the system "largest display size" the constraint is 772px, not 848, and the
            // Recovery Kit row lost its last word again — "forget your master password and this
            // vault cannot be", stopping one word before the consequence. Compose's default
            // overflow is Clip, so it did not even end in an ellipsis: the sentence simply stopped,
            // and the row read as complete prose that happened to be about nothing.
            //
            // There is no constant that survives every width times every font scale, so there is no
            // constant here. Prefer wrapping over truncation (Apple HIG truncation-strategy; MD
            // dynamic-type). These summaries are app-authored and bounded — the longest is two
            // sentences — and LkGroupRow sizes itself by padding rather than a fixed height, so the
            // row absorbs whatever it needs. Rows whose summary fits on one line are untouched.
            Text(summary, style = LkText.subtitle, color = if (warning) c.amber else c.textFaint)
        }
    }
}

/** A row that only reports a value — the version, an unmet requirement. */
@Composable
private fun ValueRow(
    title: String,
    value: String,
    first: Boolean,
) {
    val c = LkTheme.colors
    LkGroupRow(
        first = first,
        modifier = Modifier.labelled(stringResource(R.string.settings_value_row_label, title, value)),
    ) {
        Text(title, style = LkText.body, color = c.textStrong, modifier = Modifier.weight(1f))
        Text(value, style = LkText.monoCaption, color = c.textFaint)
    }
}

@Composable
private fun LicensesDialog(includePlayOnly: Boolean, onDismiss: () -> Unit) {
    val c = LkTheme.colors
    LkDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_licenses_title),
        body = stringResource(R.string.settings_licenses_dialog_body),
        dismissLabel = stringResource(R.string.settings_licenses_dialog_close),
    ) {
        Column(
            Modifier
                // Bounded so a long list cannot push the Close button off the bottom of a dialog
                // that has no other way out on a device with gesture navigation.
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LkSpace.md),
        ) {
            // `canPurchase` is the flavour signal the domain already carries - its own KDoc says
            // it is "true only in `play`". A foss install ships no billing and no Play Core code,
            // so listing their licences there told the reader the binary contains dependencies it
            // does not have, on the one screen whose whole job is stating truthfully what is in it.
            ThirdPartyLicenses.groups
                .filter { includePlayOnly || !it.playOnly }
                .forEach { group ->
                // Two nested columns rather than a Spacer between the heading and the list: a
                // Spacer that is a direct child of a `spacedBy` Column is itself spaced, so the
                // 4dp separator here rendered as 10dp. The grouping it was standing in for is
                // said structurally instead, which is also what makes the gap a token.
                Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                    // No arrangement: [LkSectionLabel] owns the gap below it (LkSectionLabelGap),
                    // and an `xs` here would be added to it rather than being it.
                    Column {
                        LkSectionLabel(group.license)
                        Text(group.url, style = LkText.caption, color = c.textFaint)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
                        group.libraries.forEach { library ->
                            Text(library.coordinates, style = LkText.monoCaption, color = c.textMuted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The supporting prose inside an opened row.
 *
 * Its own composable because it appears throughout: at each site the colour was left to Material's
 * `onSurface`, which is a full step brighter than the design's supporting prose — so every
 * explanation competed with the setting title above it.
 */
@Composable
internal fun Body(text: String, balanced: Boolean = false) {
    // [balanced] is for a short body of one or two sentences, where an ordinary paragraph break
    // can leave a single word on the last line ("replacement." alone, D3-L-16). Evens the lines
    // out instead; a long body keeps the paragraph break, which is the better one for prose.
    val style = if (balanced) LkText.bodyQuiet.copy(lineBreak = LineBreak.Heading) else LkText.bodyQuiet
    Text(text, style = style, color = LkTheme.colors.textMuted)
}

/**
 * The locked branch of a Pro-gated setting.
 *
 * Shared so the two gated settings cannot word the offer differently, and so §N2 holds
 * structurally: `foss` (`canPurchase == false`) never sells anything here — it carries no billing
 * code. It does now point at the build that does, because a note naming a remedy with no route to
 * it is a dead end, and those are a launch blocker in this project.
 */
@Composable
private fun ProUpsell(
    state: SettingsUiState,
    activity: FragmentActivity?,
    viewModel: SettingsViewModel,
    purpose: String,
) {
    if (state.canPurchase && activity != null) {
        Body(
            if (state.proPrice != null) {
                stringResource(R.string.settings_pro_upsell_body, state.proPrice, purpose)
            } else {
                stringResource(R.string.settings_pro_upsell_body_no_price, purpose)
            },
        )
        Spacer(Modifier.height(12.dp))
        LkPrimaryButton(
            text = state.proPrice
                ?.let { stringResource(R.string.settings_pro_upsell_button, it) }
                ?: stringResource(R.string.settings_pro_upsell_button_no_price),
            accent = LkButtonAccent.Brass,
            icon = LkIcons.Crown,
            onClick = { viewModel.unlockPro(activity) },
        )
    } else {
        val context = LocalContext.current
        Body(stringResource(R.string.settings_pro_upsell_foss_body))
        Spacer(Modifier.height(12.dp))
        LkPrimaryButton(
            text = stringResource(R.string.settings_pro_upsell_view_play),
            accent = LkButtonAccent.Brass,
            icon = LkIcons.OpenExternal,
            onClick = { context.openPlayListing() },
        )
    }
}

/**
 * The re-authentication well both duress confirmations carry.
 *
 * Its own composable, and wrapped in its own Column, because it emits two children into the
 * dialog's arranged Column — where each would otherwise be spaced as a separate block and read as
 * a gap in the middle of one prompt.
 */
@Composable
private fun DuressGateField(value: String, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        Body(stringResource(R.string.settings_duress_gate_body))
        PasswordField(
            value = value,
            onValueChange = onValueChange,
            label = stringResource(R.string.settings_duress_gate_label),
        )
    }
}

/**
 * A labelled secret well: the module's one way to build one, so no screen re-assembles a label, a
 * gap and a field by hand and lands on its own spacing. `LkPasswordField.label` is a *semantics*
 * name, not a visible one — a field built without this reads as an unnamed box on screen and is
 * named only to a screen reader.
 *
 * Always carries the show/hide eye. A caller that already owns the reveal flag passes it and the
 * toggle (the change-password form keeps its three on the screen); one that passes neither gets a
 * flag of the helper's own. That fallback is what the duress wells and the vault-password gates were
 * missing: they typed a secret they are *creating* or re-proving blind, beside a form whose wells all
 * had an eye. The flag is `remember`, never `rememberSaveable` — coming back from the background
 * with the well re-masked is the safe direction.
 */
@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
    revealed: Boolean = false,
    onToggleReveal: (() -> Unit)? = null,
    onImeAction: (() -> Unit)? = null,
) {
    var ownRevealed by remember { mutableStateOf(false) }
    val callerOwnsReveal = onToggleReveal != null
    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        LkFieldLabel(label)
        LkPasswordField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = stringResource(R.string.settings_password_field_placeholder),
            showCount = true,
            isError = isError,
            revealed = if (callerOwnsReveal) revealed else ownRevealed,
            onToggleReveal = onToggleReveal ?: { ownRevealed = !ownRevealed },
            onImeAction = onImeAction,
        )
    }
}

/** Persists the open-row set across configuration changes as a plain list of titles. */
/**
 * Joins the parts of a row's spoken label into sentences, without doubling the full stop.
 *
 * Summaries are written as sentences and some of them end in a period; the label used to append
 * ". " unconditionally, so "…if you allow it. Nothing else." was announced as "Nothing else..".
 * Harmless on paper, and a screen reader reads the pause twice.
 */
private fun announced(vararg parts: String): String =
    parts.filter { it.isNotBlank() }.joinToString(" ") { it.trimEnd().trimEnd('.') + "." }

private val expandedSaver = androidx.compose.runtime.saveable.Saver<
    androidx.compose.runtime.MutableState<Set<String>>,
    List<String>,
    >(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) },
)

/**
 * One vocabulary for every row in the Pro group, so search cannot answer "buy" from one row and
 * not the next.
 *
 * The words are the fix, not decoration. `searchSettings` is a substring match over
 * `"$title $summary $keywords $groupTitle"` requiring *every* typed term to hit, and the previous
 * list — "restore purchase pro unlock repurchase reinstall bought paid receipt" — answered *buy*,
 * *upgrade*, *price*, *cost*, *premium* and *subscription* with the empty state. A user who has
 * decided to pay types one of those six, not "restore".
 *
 * "subscription" is here deliberately and is a *search* term, not a claim: someone who types it
 * must reach the row that tells them there isn't one. No rendered string says the word.
 */
internal const val PRO_KEYWORDS =
    "buy purchase upgrade pro premium unlock price cost pay payment one-time lifetime " +
        "subscription restore repurchase reinstall bought paid receipt licence license"

/**
 * The product's standing entry point, and the purchase housekeeping under it.
 *
 * Until this group carried an upgrade row, a user who had decided to pay had nowhere to do it: the
 * only purchase affordances in the app were the two quota-wall dialogs — reachable only by being
 * refused — and the `lockedContent` of the duress-vault row, filed under Security and named for a
 * decoy vault. "I like this, take my money" had no answer. That was never a decision; no spec or
 * plan weighed a standing entry point and rejected it.
 *
 * The group renders in **both** flavours, which is a narrowing of §N2 rather than a breach of it.
 * `foss` may name Pro and point at the build that sells it — it already does so at three sites —
 * but it carries no billing code, no transaction and no restore. What it must not grow is a
 * standing *sales section*: hence one row, no brass, no counts, and an external action that leaves
 * for the listing. `canPurchase` is what decides which shape is built, so §N2 still holds
 * structurally rather than by convention.
 *
 * Restore is not a nicety. Until that row existed, `Entitlement.restore()` had no caller at all:
 * the sole route back to Pro was the once-per-process sync at startup, so a reinstall that happened
 * to land offline left a paying user on the free tier with nothing to press.
 */
@Composable
private fun proGroup(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    activity: FragmentActivity?,
): List<SettingsGroup> {
    val context = LocalContext.current
    val upgrade = if (state.canPurchase) {
        SettingsEntry.Expandable(
            title = stringResource(R.string.settings_pro_row_title),
            summary = if (state.isPro) {
                stringResource(R.string.settings_pro_row_summary_pro)
            } else {
                stringResource(R.string.settings_pro_row_summary_free)
            },
            keywords = PRO_KEYWORDS,
            content = { UpgradeRowBody(state, viewModel, activity) },
            testTag = "settings-pro-upgrade",
        )
    } else {
        // foss. A capability statement with a route out, not an offer: no body to expand, no brass,
        // and `external = true` so the glyph says the row leaves the app.
        SettingsEntry.Action(
            title = stringResource(R.string.settings_pro_row_title),
            // `isPro` can only be true in a foss build via the debuggable QA override, which lifts
            // every creation cap app-wide - and this row went on saying "Pro is in the Play
            // version" while it did, so the one screen that reports entitlement disagreed with the
            // entitlement the rest of the app was enforcing. "Unlocked" is reused rather than
            // written: it states the capability and claims no purchase, which is exactly right for
            // a build that cannot sell anything.
            summary = if (state.isPro) {
                stringResource(R.string.settings_pro_row_summary_pro)
            } else {
                stringResource(R.string.settings_pro_row_summary_foss)
            },
            keywords = PRO_KEYWORDS,
            onClick = { context.openPlayListing() },
            external = true,
            testTag = "settings-pro-upgrade",
        )
    }
    return listOf(
        SettingsGroup(
            stringResource(R.string.settings_group_pro),
            buildList {
                add(upgrade)
                if (state.canPurchase) {
                    add(
                        SettingsEntry.Action(
                            title = stringResource(R.string.settings_restore_title),
                            summary = if (state.isPro) {
                                stringResource(R.string.settings_restore_summary_active)
                            } else {
                                stringResource(R.string.settings_restore_summary_inactive)
                            },
                            keywords = PRO_KEYWORDS,
                            onClick = viewModel::restorePurchases,
                        ),
                    )
                }
            },
        ),
    )
}

/**
 * The expanded body of the upgrade row — `play` only, since `foss` builds an [SettingsEntry.Action]
 * with no body at all.
 *
 * Wraps its own children in a `spacedBy` Column and emits no `Spacer`. The disclosure container
 * this renders into sets no `verticalArrangement`, which is how [ProUpsell] gets away with
 * interleaved `Spacer(Modifier.height(12.dp))`; that raw dp is pre-existing debt, not a pattern to
 * copy, and a `Spacer` inside a `spacedBy` Column would be spaced too.
 *
 * Says what Pro removes — the *creation* caps — and denies the other reading in the same breath.
 * Importing and viewing were never capped, so copy implying Pro unlocks data would be false.
 */
@Composable
private fun UpgradeRowBody(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    activity: FragmentActivity?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        Body(
            if (state.isPro) {
                stringResource(R.string.settings_pro_row_body_pro)
            } else {
                stringResource(R.string.settings_pro_row_body_removes_caps)
            },
        )
        // Only for a free tier, and only once the counts are actually known. A Pro vault has no cap
        // to count against, so both "9 of 7" and "9 of unlimited" would be false; and a null usage
        // means locked or not-yet-computed, where the honest rendering is nothing at all rather
        // than a zero.
        if (!state.isPro) {
            state.proUsage?.let { usage ->
                UsageLine(
                    held = usage.files,
                    cap = AttachmentQuota.FREE_LIMIT,
                    usage = R.plurals.settings_pro_row_usage_files,
                    overCap = R.plurals.settings_pro_row_over_cap_files,
                )
                UsageLine(
                    held = usage.cards,
                    cap = ProTypeQuota.FREE_LIMIT,
                    usage = R.plurals.settings_pro_row_usage_cards,
                    overCap = R.plurals.settings_pro_row_over_cap_cards,
                )
                UsageLine(
                    held = usage.identities,
                    cap = ProTypeQuota.FREE_LIMIT,
                    usage = R.plurals.settings_pro_row_usage_identities,
                    overCap = R.plurals.settings_pro_row_over_cap_identities,
                )
            }
        }
        if (!state.isPro && activity != null) {
            LkPrimaryButton(
                text = state.proPrice
                    ?.let { stringResource(R.string.settings_pro_upsell_button, it) }
                    ?: stringResource(R.string.settings_pro_upsell_button_no_price),
                accent = LkButtonAccent.Brass,
                icon = LkIcons.Crown,
                onClick = { viewModel.unlockPro(activity) },
            )
        }
    }
}

/**
 * One metered category, stated as what the vault holds against what free creates.
 *
 * Held of cap, both numerals, never headroom: this repo settled that once already, rejecting a
 * badge that withheld the number in favour of one that "cannot be misread as either". [cap] is a
 * separate format argument from [held] — passing the cap twice is the bug that left a vault above
 * the limit reading "5 of 5 used" after every deletion.
 *
 * Above the cap the fraction is dropped entirely. An imported vault legitimately holds more than
 * free would create, because import is never charged; a fraction there reads as a deficit, when the
 * truth is that every one of them is kept, readable and editable.
 */
@Composable
private fun UsageLine(
    held: Int,
    cap: Int,
    @PluralsRes usage: Int,
    @PluralsRes overCap: Int,
) {
    val res = if (held > cap) overCap else usage
    Body(pluralStringResource(res, held, held, cap))
}

// -------------------------------------------------------------------------------------------
// TEMPORARY — on-device QA only. Delete this function and the `+ debugTierGroup(...)` above.
// -------------------------------------------------------------------------------------------

/**
 * The tier switch, present only in a debuggable `foss` build (`Entitlement.debugTierOverridable`)
 * and therefore absent from anything shippable. It exists because Pro is otherwise unreachable on
 * a device: `foss` has no purchase path at all (§N2), so without this the paid half of the app can
 * only ever be read, never used.
 *
 * Last in the list and named for what it is. A control that grants a paid tier has no business
 * sitting near the ones people actually use, and disguising it as a product feature is how a
 * debug affordance survives into a release.
 */
@Composable
private fun debugTierGroup(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
): List<SettingsGroup> {
    if (!state.debugTierOverridable) return emptyList()
    return listOf(
        SettingsGroup(
            stringResource(R.string.settings_group_debug),
            listOf(
                SettingsEntry.Custom(
                    title = stringResource(R.string.settings_debug_pro_title),
                    summary = stringResource(R.string.settings_debug_pro_summary),
                    keywords = "pro debug tier entitlement unlock qa test paid",
                ) { first ->
                    LkSwitchRow(
                        label = stringResource(R.string.settings_debug_pro_switch_label),
                        hint = stringResource(R.string.settings_debug_pro_switch_hint),
                        checked = state.isPro,
                        onCheckedChange = viewModel::setDebugPro,
                        first = first,
                    )
                },
            ),
        ),
    )
}

/**
 * A whole-second count as a duration a person reads: "2 minutes", not "120s".
 *
 * The auto-lock summary carries the real numbers deliberately — "Balanced" alone says nothing about
 * how exposed the vault is, so the answer belongs on the closed row rather than behind a tap. What
 * it does not need is the raw unit: "Balanced · 120s idle, 30s in the background" is a settings row
 * quoting a constant back at you.
 *
 * Minutes only when the value divides evenly. 90 seconds staying "90 seconds" is correct — "1.5
 * minutes" is worse than either, and every preset this app ships happens to divide cleanly anyway.
 */
@Composable
private fun rememberDuration(seconds: Int): String =
    if (seconds >= 60 && seconds % 60 == 0) {
        pluralStringResource(R.plurals.settings_duration_minutes, seconds / 60, seconds / 60)
    } else {
        pluralStringResource(R.plurals.settings_duration_seconds, seconds, seconds)
    }

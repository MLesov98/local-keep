package com.localkeep.feature.unlock

import android.text.format.DateFormat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.rememberExternalPickerLauncher
import com.localkeep.core.ui.theme.LkCard
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The restore flow a locked, unreadable vault can actually reach (F34, spec §12.5 MUST).
 *
 * Every other backup control in the app lives under Settings, which is inside the authenticated
 * shell — so the one user who needs a restore most, the one whose container will not open at all,
 * was the one user who could not get to one. The unlock error used to *say* "try restoring a backup
 * from Settings" and offer no way there.
 *
 * The screen is a state of [UnlockScreen], not a nav destination. A route would be reachable by
 * anything able to navigate; this is reachable only from [UnlockError.Actionable], which only
 * `UnlockResult.VAULT_UNREADABLE` produces.
 */
@Composable
internal fun PreUnlockRestoreScreen(
    state: RestoreUiState,
    viewModel: UnlockViewModel,
) {
    val c = LkTheme.colors
    LkScreen(accent = c.jade) {
        Column(
            Modifier
                .fillMaxSize()
                // The shell keeps the keyboard out of its own padding, so this is where the
                // viewport shrinks for it. Without it the backup-password well was typed into from
                // under the keyboard on a short window: `LkTextField`'s bring-into-view had nothing
                // to scroll because the viewport never got shorter. Outside the scroll so it is the
                // scroller's height that gives, not its content.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LkFrame.heroHorizontal)
                .padding(top = LkSpace.xl, bottom = LkSpace.xl),
            verticalArrangement = Arrangement.spacedBy(LkSpace.lg),
        ) {
            Header(onBack = viewModel::closeRestore)

            when (state.phase) {
                // CONFIRM keeps rendering the password step underneath its dialog, so dismissing
                // the dialog returns to something rather than to an empty screen.
                RestorePhase.CHOOSE -> ChooseStep(state, viewModel)
                RestorePhase.PROVE, RestorePhase.CONFIRM -> ProveStep(state, viewModel)
                RestorePhase.DONE -> DoneStep(viewModel)
            }

            state.error?.let { error ->
                val message = resolveRestoreError(error)
                LkInlineError(message, Modifier.announce(message))
            }
        }
    }

    if (state.phase == RestorePhase.CONFIRM) {
        RestoreConfirmDialog(
            isWorking = state.isWorking,
            onCancel = viewModel::cancelRestoreConfirm,
            onConfirm = viewModel::confirmRestore,
        )
    }
}

/**
 * The one dialog in this flow, and the only place the irreversible replacement is authorised.
 *
 * **Everything that could dismiss it is withdrawn while the write runs.** `confirmRestore` sets
 * `isWorking` and then launches; the phase does not leave `CONFIRM` until the copy and
 * `restoreVaultFile` have both finished, so for the whole of that window this dialog is still on
 * screen. It used to keep a live Cancel, a live Back and a live outside-tap there, all wired to
 * `cancelRestoreConfirm` — which only flips the phase back and drops the proof. The launched
 * coroutine completes regardless, so the dialog closed as though the replacement had been called
 * off and the screen then announced "Vault restored" over a vault that had been replaced. Worse
 * through the header chevron: `closeRestore()` nulls `restore`, every later `updateRestore` becomes
 * a no-op, and the replacement lands with nothing on screen saying so.
 *
 * The cancel is **removed, not made to abort**. A half-written vault is worse than a completed one,
 * and the write is already ordered so that the damaged containers are quarantined before anything
 * is overwritten. What was wrong was offering a control that did nothing.
 *
 * Split out of [PreUnlockRestoreScreen] so the decision is testable without a `UnlockViewModel`:
 * the screen takes the view-model, this takes two lambdas and a boolean.
 */
@Composable
internal fun RestoreConfirmDialog(
    isWorking: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = LkTheme.colors
    LkDialog(
        onDismiss = onCancel,
        title = stringResource(R.string.unlock_restore_confirm_title),
        body = stringResource(R.string.unlock_restore_confirm_body),
        // Coral, not jade: LkColors reserves coral for "a failure, or a compromised
        // environment", and replacing a vault the app could not read is the second of those.
        accent = c.coral,
        icon = LkIcons.Warning,
        confirm = LkDialogAction(
            label = stringResource(R.string.unlock_restore_confirm_button),
            onClick = onConfirm,
            // The dialog's accent is already coral; a jade confirm under it said "safe, expected"
            // about the one tap that replaces the vault. Same emphasis as the in-vault restore.
            emphasis = LkActionEmphasis.Destructive,
            // Not just cosmetic: `confirmRestore` already returns early on `isWorking`, and this
            // keeps the button from reading as live while it would do nothing.
            enabled = !isWorking,
        ),
        // Null, not a disabled Cancel: a dimmed "Cancel" beside a dimmed "Replace vault" is a
        // dialog with no answer at all, which reads as a freeze. The label simply is not offered
        // for the seconds the write takes.
        dismissLabel = if (isWorking) {
            null
        } else {
            stringResource(com.localkeep.core.ui.R.string.core_ui_dialog_cancel)
        },
        dismissible = !isWorking,
    )
}

@Composable
private fun Header(onBack: () -> Unit) {
    val c = LkTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Icon(
            LkIcons.ChevronLeft,
            contentDescription = null,
            tint = c.textMuted,
            modifier = Modifier
                .minTouchTarget()
                .labelled(stringResource(R.string.unlock_restore_back))
                .clickable(onClick = onBack)
                .size(24.dp),
        )
        Text(
            stringResource(R.string.unlock_restore_title),
            modifier = Modifier.semantics { heading() },
            style = LkText.title,
            color = c.textStrong,
        )
    }
}

@Composable
private fun ChooseStep(state: RestoreUiState, viewModel: UnlockViewModel) {
    val c = LkTheme.colors
    // The same helper and the same `*/*` filter Settings > Backup uses: `.vault` has no registered
    // media type, so narrowing it would hide the user's own export from them in some file managers.
    val pickFile = rememberExternalPickerLauncher(
        contract = ActivityResultContracts.OpenDocument(),
        onPickerOpened = viewModel::onPickerOpened,
        onPickerClosed = viewModel::onPickerClosed,
    ) { uri -> if (uri != null) viewModel.selectPickedFile(uri) }
    Text(
        stringResource(R.string.unlock_restore_subtitle),
        style = LkText.body,
        color = c.textMuted,
    )
    // The heading and the list it heads are one child of the caller's `spacedBy(lg)` arrangement,
    // not two: [LkSectionLabel] owns the gap below it (LkSectionLabelGap), and the arrangement
    // would be added to that padding rather than supplying it.
    Column {
        LkSectionLabel(stringResource(R.string.unlock_restore_on_device_label))
        // Own Column rather than children of an arranged one: a composable that emits several
        // children into an arranged Column gets the arrangement's gap between each of them, which
        // is the `Spacer`-inside-`spacedBy` trap in a different shape.
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            if (state.snapshots.isEmpty() && !state.isWorking) {
                // Never an early return. A device with no ring is exactly the device whose owner needs
                // the file picker most — the snapshot ring is excluded from cloud backup and device
                // transfer, so a restored or replaced phone has none of it, and an empty list that also
                // hid the one remaining route would be the F34 defect rebuilt one screen further in.
                Text(
                    stringResource(R.string.unlock_restore_empty),
                    style = LkText.body,
                    color = c.textMuted,
                )
            }
            state.snapshots.forEach { snapshot ->
                val label = stringResource(
                    R.string.unlock_restore_snapshot_label,
                    snapshot.savedAtMillis.asLocalTimestamp(),
                )
                LkCard(
                    modifier = Modifier
                        .minTouchTarget()
                        .lkTag("restore-snapshot")
                        .clickable { viewModel.selectSnapshot(snapshot) },
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
                    ) {
                        Icon(LkIcons.Clock, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                        Text(label, style = LkText.body, color = c.textStrong)
                    }
                }
            }
            LkSecondaryButton(
                text = stringResource(R.string.unlock_restore_choose_file),
                onClick = { pickFile(arrayOf("*/*")) },
                icon = LkIcons.Folder,
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth().lkTag("restore-choose-file"),
            )
        }
    }
}

@Composable
private fun ProveStep(state: RestoreUiState, viewModel: UnlockViewModel) {
    val c = LkTheme.colors
    val selected = state.selected ?: return
    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        Text(
            when (selected) {
                is RestoreChoice.Ring -> stringResource(
                    R.string.unlock_restore_snapshot_label,
                    selected.snapshot.savedAtMillis.asLocalTimestamp(),
                )
                // Deliberately not the document's name. A SAF display name is chosen by the
                // provider, it is rendered on a screen anyone holding the phone can reach, and repo
                // law keeps paths and URIs out of user-facing text. The user picked the file one
                // tap ago; they know which one it is.
                is RestoreChoice.PickedFile -> stringResource(R.string.unlock_restore_picked_file)
            },
            style = LkText.body,
            color = c.textStrong,
        )
        LkFieldLabel(stringResource(R.string.unlock_restore_password_label))
        LkPasswordField(
            value = state.password,
            onValueChange = viewModel::onRestorePasswordChanged,
            placeholder = stringResource(R.string.unlock_restore_password_placeholder),
            label = stringResource(R.string.unlock_restore_password_label),
            imeAction = ImeAction.Done,
            onImeAction = viewModel::checkSelectedBackup,
            modifier = Modifier.lkTag("restore-password"),
        )
        if (state.isWorking) {
            // Announced, like every other progress line in this module. Argon2id against a backup
            // runs for tens of seconds — and this screen's candidate can carry cost parameters
            // larger than anything this device has ever had to satisfy, because the backup may have
            // come from a stronger phone. Unannounced, a screen-reader user gets up to a minute of
            // silence on the slowest operation in the app, immediately after pressing a button, on
            // the screen they reached because their vault would not open.
            val working = stringResource(R.string.unlock_restore_working)
            Text(working, style = LkText.body, color = c.textMuted, modifier = Modifier.announce(working))
        }
        LkPrimaryButton(
            text = stringResource(R.string.unlock_restore_check_button),
            onClick = viewModel::checkSelectedBackup,
            enabled = !state.isWorking && state.password.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .lkTag("restore-check")
                // Keyed on the blocking *condition*, never on `enabled` — the V21 lesson, and the
                // reason V21 recurred here. `enabled` folds two causes into one flag, so a label
                // derived from it can only ever name one of them: a user who had typed a password
                // and pressed the button would be told to enter a password, in the middle of the
                // wait that tells them it was accepted. Mirrors the master-password button in
                // `UnlockScreen.kt`, which answers the identical pair of conditions.
                .labelled(
                    stringResource(
                        restoreCheckLabel(
                            isWorking = state.isWorking,
                            passwordEmpty = state.password.isEmpty(),
                        ),
                    ),
                ),
        )
        LkSecondaryButton(
            text = stringResource(R.string.unlock_restore_pick_another),
            onClick = viewModel::deselectSnapshot,
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DoneStep(viewModel: UnlockViewModel) {
    val c = LkTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        Text(
            stringResource(R.string.unlock_restore_done_title),
            modifier = Modifier.semantics { heading() },
            style = LkText.heading,
            color = c.textStrong,
        )
        Text(
            stringResource(R.string.unlock_restore_done_body),
            style = LkText.body,
            color = c.textMuted,
        )
        LkPrimaryButton(
            text = stringResource(R.string.unlock_restore_done_button),
            onClick = viewModel::finishRestore,
            modifier = Modifier.fillMaxWidth().lkTag("restore-done"),
        )
    }
}

@Composable
private fun resolveRestoreError(error: UnlockError): String = when (error) {
    is UnlockError.Simple -> stringResource(error.messageRes)
    is UnlockError.RetryAfter -> pluralStringResource(error.pluralsRes, error.seconds, error.seconds)
    // Never produced inside the restore flow: the only Actionable in the app is the unlock error
    // that opens this screen, and opening it a second time from inside itself is not a thing the
    // view-model can reach. Resolved to its message so the `when` stays exhaustive without a
    // branch that could render a dead button.
    is UnlockError.Actionable -> stringResource(error.messageRes)
}

/**
 * Which sentence names the "Open this backup" button right now.
 *
 * A free function for the reason `biometricErrorIsUserChoice` and `unlockErrorFor` are free
 * functions: the decision is the whole of the defect and none of the drawing, and this way it can
 * be walked through every combination without a Compose environment.
 *
 * **Never derived from `enabled`.** That flag is `!isWorking && password.isNotEmpty()` — two causes
 * folded into one boolean — so a label computed from it can only name one of them, and it names the
 * wrong one exactly when the work is running: a user who has typed a password and pressed the
 * button gets told to enter a password. That is V21, and it recurred here because the folding is
 * invisible at the call site.
 */
@StringRes
internal fun restoreCheckLabel(isWorking: Boolean, passwordEmpty: Boolean): Int = when {
    // Order matters, and this is the arm the defect turns on: working wins over an empty field,
    // because while a derivation runs the field has *already* been cleared by the view-model.
    isWorking -> R.string.unlock_restore_check_a11y_working
    passwordEmpty -> R.string.unlock_restore_check_a11y_needs_password
    else -> R.string.unlock_restore_check_button
}

/** Locale-driven, matching what Settings › Backup shows for the same files. */
@Composable
private fun Long.asLocalTimestamp(): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { restoreTimestampFormatter(locale) }
    return formatter.format(Instant.ofEpochMilli(this))
}

/** Split out so the pattern is unit-testable without a Compose environment. */
internal fun restoreTimestampFormatter(locale: Locale): DateTimeFormatter =
    DateTimeFormatter
        .ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMdjm"), locale)
        .withZone(ZoneId.systemDefault())

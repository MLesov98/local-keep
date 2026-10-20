package com.localkeep.feature.vaultlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.localkeep.core.model.HiddenDurationUnit
import com.localkeep.core.model.HiddenLease
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSheet
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkSurface
import java.time.Duration
import java.time.Instant

/**
 * The whole "Hide…" flow, driven by [HideFlowState]. Two steps in one component: a duration sheet,
 * and — only when the vault has no reveal phrase yet — a first-phrase step chained after a duration
 * is chosen. The phrase lives in a plain `remember` (never `rememberSaveable`: a secret must not
 * enter saved state), is copied to a `CharArray` the repository wipes, and the `String` is cleared on
 * every exit.
 */
@Composable
fun HideFlow(
    flow: HideFlowState,
    onDismiss: () -> Unit,
    onPickDuration: (Instant) -> Unit,
    onSubmitPhrase: (Instant, CharArray) -> Unit,
    /** The phrase field changed — the host clears the inline error about the text that was typed. */
    onPhraseEdited: () -> Unit = {},
) {
    val pendingUntil = flow.pendingUntil
    if (flow.needsPhrase && pendingUntil != null) {
        FirstPhraseStep(
            collision = flow.error == HideFlowError.PhraseCollision,
            onDismiss = onDismiss,
            onSubmit = { phrase -> onSubmitPhrase(pendingUntil, phrase) },
            onEdited = onPhraseEdited,
        )
    } else {
        HideDurationSheet(label = flow.label, onDismiss = onDismiss, onPick = onPickDuration)
    }
}

@Composable
private fun HideDurationSheet(label: String, onDismiss: () -> Unit, onPick: (Instant) -> Unit) {
    // Which face of the sheet is showing. The custom step replaces the preset list rather than
    // growing below it, so the sheet never has to scroll on a short phone.
    var custom by remember { mutableStateOf(false) }
    // The custom face replaces the preset list rather than sitting beside it, so without this there
    // is no way back to the presets: the only control that moved was the sheet's own dismiss, which
    // abandons the whole hide flow rather than undoing one choice. Back is the gesture people
    // already reach for, and it belongs to the step, not to the sheet, while the step is showing.
    BackHandler(enabled = custom) { custom = false }
    LkSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.vaultlist_hide_sheet_title, label),
    ) {
        if (custom) {
            CustomDurationStep(onPick = onPick, onBack = { custom = false })
        } else {
            // Instant.now() plus the preset: the repository validates the lease against its own clock,
            // and both are wall-clock, so the millisecond skew between them is immaterial.
            DurationRow(stringResource(R.string.vaultlist_hide_preset_5min)) { onPick(Instant.now().plus(Duration.ofMinutes(5))) }
            DurationRow(stringResource(R.string.vaultlist_hide_preset_30min)) { onPick(Instant.now().plus(Duration.ofMinutes(30))) }
            DurationRow(stringResource(R.string.vaultlist_hide_preset_1hour)) { onPick(Instant.now().plus(Duration.ofHours(1))) }
            DurationRow(stringResource(R.string.vaultlist_hide_preset_1day)) { onPick(Instant.now().plus(Duration.ofDays(1))) }
            DurationRow(stringResource(R.string.vaultlist_hide_preset_custom)) { custom = true }
            DurationRow(stringResource(R.string.vaultlist_hide_preset_indefinite)) { onPick(HiddenLease.INDEFINITE) }
        }
        Spacer(Modifier.height(LkSpace.md))
    }
}

/**
 * The custom-duration face: a whole number plus a unit, validated by [HiddenLease.customLeaseOrNull]
 * (Decision 9 — positive integer × Minutes/Hours/Days, checked arithmetic, capped at ten years). The
 * confirm button is disabled until the input forms a usable lease, so the error line is only for a
 * value that parses but is out of range; a non-numeric character never enters [amount].
 */
@Composable
private fun CustomDurationStep(onPick: (Instant) -> Unit, onBack: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(HiddenDurationUnit.HOURS) }
    // Recomputed on every keystroke against a fresh now; the repository re-validates against its own
    // clock on commit, so this instant is only for enabling the button and is not the source of truth.
    val lease = amount.toLongOrNull()?.let { HiddenLease.customLeaseOrNull(Instant.now(), it, unit) }
    val outOfRange = amount.isNotBlank() && lease == null

    LkTextField(
        value = amount,
        // Digits only: the model rejects the rest anyway, and filtering here keeps the field from
        // showing a character it will not honour.
        onValueChange = { typed -> amount = typed.filter(Char::isDigit).take(7) },
        label = stringResource(R.string.vaultlist_hide_custom_amount_label),
        placeholder = stringResource(R.string.vaultlist_hide_custom_amount_label),
        isError = outOfRange,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Spacer(Modifier.height(LkSpace.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        UnitChip(stringResource(R.string.vaultlist_hide_custom_unit_minutes), unit == HiddenDurationUnit.MINUTES) { unit = HiddenDurationUnit.MINUTES }
        UnitChip(stringResource(R.string.vaultlist_hide_custom_unit_hours), unit == HiddenDurationUnit.HOURS) { unit = HiddenDurationUnit.HOURS }
        UnitChip(stringResource(R.string.vaultlist_hide_custom_unit_days), unit == HiddenDurationUnit.DAYS) { unit = HiddenDurationUnit.DAYS }
    }
    if (outOfRange) {
        Spacer(Modifier.height(LkSpace.xs))
        Text(
            text = stringResource(R.string.vaultlist_hide_custom_error),
            style = LkText.badge,
            color = LkTheme.colors.coralText,
            modifier = Modifier.padding(horizontal = LkSpace.xs),
        )
    }
    Spacer(Modifier.height(LkSpace.md))
    LkPrimaryButton(
        text = stringResource(R.string.vaultlist_hide_custom_confirm),
        enabled = lease != null,
        onClick = { lease?.let(onPick) },
    )
    Spacer(Modifier.height(LkSpace.sm))
    // Beside the BackHandler, not instead of it: a visible control is the only route back for
    // someone driving the sheet with a screen reader or a switch, and gesture back is not
    // discoverable from a face that arrived with no other way out.
    DurationRow(stringResource(R.string.vaultlist_hide_custom_back), onClick = onBack)
}

@Composable
private fun UnitChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LkTheme.colors
    Text(
        text = text,
        style = LkText.rowTitle,
        color = if (selected) colors.textPrimary else colors.textMuted,
        modifier = Modifier
            .clip(LkShape.sm)
            .lkSurface(
                LkShape.sm,
                if (selected) colors.jade.copy(alpha = 0.10f) else colors.surface,
                if (selected) colors.jade.copy(alpha = 0.42f) else colors.ring,
            )
            .selectable(selected = selected, onClick = onClick)
            .minTouchTarget()
            .padding(horizontal = LkSpace.md, vertical = LkSpace.sm),
    )
}

@Composable
private fun DurationRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = LkText.rowTitle,
        color = LkTheme.colors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(LkShape.sm)
            .clickable(onClick = onClick)
            .minTouchTarget()
            .padding(horizontal = LkSpace.md, vertical = LkSpace.sm),
    )
}

@Composable
private fun FirstPhraseStep(
    collision: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (CharArray) -> Unit,
    onEdited: () -> Unit,
) {
    var phrase by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    // The secret String cannot be wiped, but it is cleared on every dispose so it does not linger in
    // this composable's memory once the flow closes.
    DisposableEffect(Unit) { onDispose { phrase = "" } }

    LkSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.vaultlist_hide_phrase_title),
        subtitle = stringResource(R.string.vaultlist_hide_phrase_body),
    ) {
        // A drawn label, because `LkPasswordField`'s `label` is semantics only: the sheet's body
        // explains the phrase but nothing beside the well said which of two secrets it asks for,
        // and the well itself is a row of bullets. The same arrangement Settings' password wells use.
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            LkFieldLabel(stringResource(R.string.vaultlist_hide_phrase_label))
            LkPasswordField(
                value = phrase,
                onValueChange = {
                    phrase = it
                    onEdited()
                },
                label = stringResource(R.string.vaultlist_hide_phrase_label),
                revealed = revealed,
                onToggleReveal = { revealed = !revealed },
                isError = collision,
                onImeAction = { if (phrase.isNotBlank()) onSubmit(phrase.toCharArray()) },
            )
        }
        if (collision) {
            Spacer(Modifier.height(LkSpace.xs))
            Text(
                text = stringResource(R.string.vaultlist_hide_error_collision),
                style = LkText.badge,
                color = LkTheme.colors.coralText,
                modifier = Modifier.padding(horizontal = LkSpace.xs),
            )
        }
        Spacer(Modifier.height(LkSpace.md))
        LkPrimaryButton(
            text = stringResource(R.string.vaultlist_hide_phrase_confirm),
            enabled = phrase.isNotBlank(),
            onClick = { onSubmit(phrase.toCharArray()) },
        )
        Spacer(Modifier.height(LkSpace.md))
    }
}

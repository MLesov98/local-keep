package com.localkeep.core.ui.theme

import com.localkeep.core.ui.R
import com.localkeep.core.ui.labelled
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.lkTag

/**
 * "What is this called?", asked at the end of making something, not at the start.
 *
 * **The ordering this exists to express.** Every editor in this app used to open on a Title field.
 * The principal's objection on 2026-08-19 is the one that stuck: at the top of an empty form the
 * question is about an item that does not exist yet — nothing has been scanned, typed or captured,
 * so there is nothing to name, and the first thing the app asks for is the one thing the user is
 * least able to answer. The Location item had already been built the other way round (take a
 * reading, then say what it was) and was the flow he pointed at as the one that reads right. This
 * is that shape, made available to every flow that ends in a save.
 *
 * **Nothing is written before [onConfirm].** The sheet is UI state only; dismissing it returns to
 * the form with every field intact. That is what makes asking last safe — the alternative, saving
 * first and naming afterwards, leaves an untitled row behind whenever someone backs out.
 *
 * **Confirm is live on arrival** when the caller seeds [value] with a suggestion, which every
 * caller does. A naming step whose field is empty has not removed the typing, only moved it to the
 * end, where the user believed they were finished.
 *
 * [extra] is for the rest of "where does this go" — the item editor puts its folder picker there,
 * because naming and filing are the same decision and splitting them is what buried the folder
 * picker behind a disclosure. A flow with no filing to do passes nothing.
 */
@Composable
fun LkNamingSheet(
    title: String,
    subtitle: String,
    fieldLabel: String,
    confirmLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = value.isNotBlank(),
    placeholder: String = "",
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    // Nothing takes focus on arrival, so the keyboard stays down. This sheet used to request focus
    // on the name field, on the reasoning that the suggestion should be one keystroke from being
    // replaced. That got the cost backwards (2026-08-25): the common case is *accepting* the
    // suggested name, and for that user the IME was pure obstruction — it opened over the confirm
    // button, so finishing meant dismissing a keyboard they never asked for. Renaming costs one
    // extra tap on the field; confirming costs none. The seeded [value] is what makes that trade
    // work, and it is why confirm is live on arrival.
    LkSheet(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        // Expanded: the half-height detent opens this with the confirm button, and anything in
        // [extra], already under the fold.
        startExpanded = true,
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .lkTag("naming-sheet"),
        ) {
            LkFormField(
                value = value,
                onValueChange = onValueChange,
                label = fieldLabel,
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth().lkTag("naming-sheet-field"),
                // The keyboard's own key does what the button below does. It used to be a Next key
                // that went nowhere (nothing follows the name), so typing a name and pressing the
                // key at the end of the keyboard did nothing visible. Blank is the one case the
                // button refuses, so there the key only puts the keyboard away: supplying an action
                // suppresses the platform's own hide.
                imeAction = ImeAction.Done,
                onImeAction = {
                    if (enabled) onConfirm() else focusManager.clearFocus()
                },
            )
            if (extra != null) {
                Spacer(Modifier.height(LkSpace.lg))
                extra()
            }
            Spacer(Modifier.height(LkSpace.lg))
            LkPrimaryButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    // Keyed on the field being blank, **not** on `enabled`. Both callers also
                    // disable this button while a save is in flight, so hanging the reason on
                    // `enabled` would announce "available once you enter a title" over a title the
                    // user has already typed — a false cause, told to the one person who cannot see
                    // the field (V21, 2026-09-13). The repo already settled this shape in
                    // OnboardingViewModel: a button dimmed because work is happening carries no
                    // reason, because the overlay already says so.
                    .then(
                        if (value.isBlank()) {
                            Modifier.labelled(
                                stringResource(
                                    R.string.core_ui_naming_confirm_disabled_a11y,
                                    confirmLabel,
                                ),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .lkTag("naming-sheet-confirm"),
            )
            Spacer(Modifier.height(LkSpace.md))
        }
    }
}

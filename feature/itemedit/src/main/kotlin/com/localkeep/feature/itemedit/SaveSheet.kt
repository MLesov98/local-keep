package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.theme.LkNamingSheet
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme

/**
 * The last step of every new item: what it is called.
 *
 * The ordering argument — why the title is asked here rather than at the top of an empty form —
 * lives on [LkNamingSheet], which the file editor uses for the same purpose. This is that sheet
 * with the one thing specific to the item editor, a per-type suggestion ([rememberSuggestedTitle]).
 *
 * **The folder is not asked here**, though it was for the few hours between this sheet arriving and
 * the chooser becoming a screen on 2026-08-19. A modal sheet cannot open a screen without being
 * torn down, taking the half-typed title with it — so filing stays on the form, under "Folder, tags
 * and more", which is also where `Item Flows.dc.html` draws it on an item that has no name yet.
 *
 * Confirm dims only if the user clears the field. That is the same refusal `save()` makes in the
 * ViewModel, which is where it is enforced — a guard living in a button's enabled state is one
 * navigation change away from being gone.
 */
@Composable
internal fun SaveSheet(
    state: ItemEditUiState,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val c = LkTheme.colors
    val suggestion = rememberSuggestedTitle(state)
    // Local, not ViewModel state, and seeded once. A title the user typed themselves wins over the
    // suggestion — someone who reopens this sheet after dismissing it must not have their word
    // replaced by a guess.
    //
    // `rememberSaveable`, like the file editor's own naming step (FileItemEditScreen): for a new
    // item this field is the only place the title exists — the form's title field is gated on
    // `!state.isNew`, so nothing pushes these keystrokes into the ViewModel until Confirm. The flag
    // that opens this sheet is saveable, so a recreation brings the sheet back; a plain `remember`
    // brought it back re-seeded from the suggestion, silently replacing the user's own word.
    var title by rememberSaveable(state.itemId) { mutableStateOf(state.title.ifBlank { suggestion }) }

    LkNamingSheet(
        title = stringResource(R.string.itemedit_save_sheet_title),
        subtitle = stringResource(R.string.itemedit_save_sheet_subtitle),
        fieldLabel = stringResource(R.string.itemedit_edit_title_field_label),
        confirmLabel = stringResource(R.string.itemedit_save_sheet_confirm),
        value = title,
        onValueChange = { title = it },
        onDismiss = onDismiss,
        onConfirm = { onSave(title.trim()) },
        enabled = title.isNotBlank() && !state.isSaving,
    ) {
        // Shown here rather than on the form behind it: a save refused from this sheet is refused
        // while the sheet is covering the screen, and a message the user cannot see is one they
        // will read as the button doing nothing.
        state.error?.let { errorRes ->
            val arg = state.errorArg
            val message = if (arg != null) stringResource(errorRes, arg) else stringResource(errorRes)
            Spacer(Modifier.height(LkSpace.md))
            Text(
                message,
                style = LkText.caption,
                color = c.coral,
                modifier = Modifier.announce(message),
            )
        }
    }
}

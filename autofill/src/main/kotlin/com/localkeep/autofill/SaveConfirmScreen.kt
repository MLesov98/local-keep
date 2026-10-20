package com.localkeep.autofill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.secretFieldSemantics
import com.localkeep.core.ui.theme.LkActionScaffold
import com.localkeep.core.ui.theme.LkFieldGroup
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFootnote
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkChromeUnpinned

/**
 * F-47: the screen a user meets when saving or updating a password from another app.
 *
 * Was a hand-built `LinearLayout` — the only surface in the product that did not use the design
 * system: default AppCompat type, flat grey buttons, text at x=0, four fifths of the screen left
 * empty. This rebuilds it with `LkScreenHeader`/`LkActionScaffold`/`LkButtons`, unchanged behaviour
 * (see [SaveConfirmActivity.save]): [saving] disables both actions, exactly as the KDoc on
 * `SaveConfirmActivity.saving` always promised but the old View-based Discard listener did not
 * enforce (it only no-opped, never visually disabled).
 */
@Composable
internal fun SaveConfirmContent(
    title: String,
    lockedNotice: String?,
    summary: SaveConfirmSummary,
    saveLabel: String,
    discardLabel: String,
    saving: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    LkActionScaffold(
        actionSpacing = LkSpace.sm,
        action = {
            LkPrimaryButton(
                text = saveLabel,
                onClick = onSave,
                enabled = !saving,
                haptic = Haptic.SUCCESS,
            )
            LkSecondaryButton(
                text = discardLabel,
                onClick = onDiscard,
                enabled = !saving,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = LkFrame.screenHorizontal)
                .padding(top = LkFrame.screenTop),
            verticalArrangement = Arrangement.spacedBy(LkSpace.lg),
        ) {
            LkScreenHeader(title = title, subtitle = lockedNotice, keepSubtitle = true)
            LkFieldGroup {
                SaveConfirmSummaryRow(summary.targetLabel, summary.target)
                // A password-change form and a split sign-in can both reach this screen with no
                // username captured. An empty well under a label says less than no well at all.
                if (summary.identity.isNotBlank()) {
                    SaveConfirmSummaryRow(summary.identityLabel, summary.identity)
                }
                summary.secretLabel?.let { SaveConfirmSecretRow(it) }
            }
            summary.queuedNote?.let { LkFootnote(it) }
        }
    }
}

/**
 * The bullets standing in for the captured password.
 *
 * A fixed run, not one bullet per character as the item detail screen draws it. That screen sits
 * behind an unlock; this one renders over another app's sign-in or checkout page, where a length is
 * the one thing about a password worth reading off a stranger's screen. The row's job here is to
 * say *that* a password is part of what is being stored, which a fixed run says exactly as well.
 */
private const val SECRET_MASK = "••••••••"

/** One `LABEL` / value pair inside the summary card. */
@Composable
private fun SaveConfirmSummaryRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        LkFieldLabel(label)
        Text(
            value,
            style = LkText.body,
            color = LkTheme.colors.textPrimary,
            // A caller label is a domain or an app name and a username is often an email; either
            // can outrun a narrow column at a large font scale, and wrapping one would push the
            // card down into the actions.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The masked password row — no reveal control, no copy, and nothing to gate.
 *
 * [secretFieldSemantics] rather than letting the bullets be read out: a screen reader announces a
 * run of bullets as bullets, which teaches the listener the character count and not that this is a
 * password at all. The same helper the item screens use, so "hidden" is worded identically here.
 */
@Composable
private fun SaveConfirmSecretRow(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        LkFieldLabel(label)
        Box(Modifier.secretFieldSemantics(label = label, revealed = false, value = "")) {
            Text(
                SECRET_MASK,
                style = LkText.masked,
                color = LkTheme.colors.textMuted,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * F-47: the unlock-to-save step, reached from [SaveConfirmContent] when the vault is locked.
 *
 * A screen of its own rather than a reuse of [MasterPasswordPrompt] — that class is also
 * `AuthActivity`'s classic-gate and reprompt prompt, which this fix was not asked to touch and
 * which is exercised by a different set of behaviours (throttling copy, biometric-unavailable
 * copy) than this one. [pending]/[errorMessage] are owned by the activity, which already runs the
 * real unlock through [VaultLockManager] and maps its outcome via [authPromptMessage] — this
 * composable only renders that state and hands a freshly-copied password back through [onSubmit].
 */
@Composable
internal fun SaveConfirmUnlockContent(
    title: String,
    subtitle: String,
    passwordLabel: String,
    continueLabel: String,
    unlockingLabel: String,
    errorMessage: String,
    pending: Boolean,
    onSubmit: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    fun submit() {
        // An empty field is not a guess — see MasterPasswordPrompt.submit, the same rule this
        // screen owes VaultLockManager's brute-force throttle.
        if (password.isEmpty() || pending) return
        val chars = password.toCharArray()
        // Cleared immediately; onSubmit wipes its own copy once the unlock attempt finishes (see
        // SaveConfirmActivity.unlockThenSave). The String itself cannot be wiped — the same
        // limitation every other password field in this app already has (UnlockScreen included);
        // this brings the autofill unlock step in line with that, rather than ahead of it.
        password = ""
        onSubmit(chars)
    }

    // Continue has two homes: pinned over the keyboard in the scaffold's action bar, or — while the
    // keyboard leaves the window too little room (see [LocalLkChromeUnpinned]) — at the end of the
    // scroll, so the well being typed into gets everything above the keys.
    val continueButton: @Composable () -> Unit = {
        LkPrimaryButton(
            text = if (pending) unlockingLabel else continueLabel,
            onClick = ::submit,
            enabled = !pending && password.isNotEmpty(),
            haptic = Haptic.SUCCESS,
        )
    }

    LkActionScaffold(
        // Empty while unpinned on purpose rather than skipped: the empty bar's `imePadding()` is what
        // reserves the keyboard's height for the scroll region above it.
        action = { if (!LocalLkChromeUnpinned.current) continueButton() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // This column did not scroll at all, so at a large font size on a small window the
                // header took what the pinned bar left and the well was measured at 0dp.
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = LkFrame.screenHorizontal)
                .padding(top = LkFrame.screenTop),
            verticalArrangement = Arrangement.spacedBy(LkSpace.lg),
        ) {
            LkScreenHeader(title = title, subtitle = subtitle, keepSubtitle = true)
            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                LkFieldLabel(passwordLabel)
                LkPasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = passwordLabel,
                    revealed = revealed,
                    onToggleReveal = { revealed = !revealed },
                    isError = errorMessage.isNotEmpty(),
                    imeAction = ImeAction.Done,
                    onImeAction = ::submit,
                )
                if (errorMessage.isNotEmpty()) {
                    LkInlineError(errorMessage, Modifier.announce(errorMessage))
                }
            }
            if (LocalLkChromeUnpinned.current) continueButton()
        }
    }
}

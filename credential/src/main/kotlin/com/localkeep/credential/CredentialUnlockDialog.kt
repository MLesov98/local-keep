package com.localkeep.credential

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkSpace

/**
 * The master-password unlock offered *inside* a credential ceremony.
 *
 * A locked vault used to dead-end a passkey request outright: the only unlock this activity had was
 * the device-wrapped biometric key, so on a device where biometric unlock was never enabled — or
 * after a panic lock, which destroys that key — the calling app was handed "biometric unlock is
 * unavailable, open LocalKeep and unlock with your master password" and the ceremony was over
 * (2C-F14). Autofill's `AuthActivity` has always offered the password here; this is the same
 * affordance on the Credential Manager side, so a passkey can be created or used without leaving
 * the app that asked for it.
 *
 * `LkDialog`, not the platform widgets autofill's `MasterPasswordPrompt` draws — this activity
 * already hosts a Compose tree for the native-binding consent, and a master-password field raised
 * over a third-party app is the last place in this app that should look like something else.
 *
 * [password] never leaves this composable as anything but the value handed to [onSubmit]; it is not
 * `rememberSaveable`, so it is never written into saved instance state.
 */
@Composable
internal fun CredentialUnlockDialog(
    error: String?,
    working: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    // Not saveable, same as [password]: a revealed secret must not outlive the composition.
    var revealed by remember { mutableStateOf(false) }
    val submittable = password.isNotEmpty() && !working

    LkDialog(
        // Not dismissible by Back or a tap outside: this is a question the ceremony is waiting on,
        // and an unanswered one has to be reported to the calling app rather than silently dropped.
        // The dismiss button below is the answer "no".
        onDismiss = onCancel,
        dismissible = false,
        title = stringResource(R.string.credential_unlock_action_label),
        body = stringResource(R.string.credential_unlock_password_body),
        icon = LkIcons.Key,
        confirm = LkDialogAction(
            label = stringResource(
                if (working) {
                    R.string.credential_unlock_password_working
                } else {
                    R.string.credential_unlock_password_submit
                },
            ),
            onClick = { if (submittable) onSubmit(password) },
            // An empty field is not a guess and must not be scored as one: `VaultLockManager.unlock`
            // records a knowledge-factor failure against the brute-force throttle for it, so a stray
            // tap would cost a real attempt and answer with the wrong-password message.
            enabled = submittable,
        ),
        dismissLabel = stringResource(R.string.credential_native_binding_cancel),
    ) {
        // The drawn label is `LkFieldLabel`, as Settings' `PasswordField` does it: the well's own
        // `label` is a semantics name only, so without this the box over a third-party app had no
        // name on screen.
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            val label = stringResource(R.string.credential_unlock_password_label)
            LkFieldLabel(label)
            LkPasswordField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.lkTag("credential-unlock-field"),
                label = label,
                revealed = revealed,
                onToggleReveal = { revealed = !revealed },
                isError = error != null,
                imeAction = ImeAction.Done,
                onImeAction = { if (submittable) onSubmit(password) },
            )
        }
        error?.let { LkInlineError(it, Modifier.announce(it)) }
    }
}

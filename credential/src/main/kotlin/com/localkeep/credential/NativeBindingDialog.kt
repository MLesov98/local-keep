package com.localkeep.credential

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction

/**
 * "Allow this app?" — the consent shown when an app with no verified web presence asks to create a
 * passkey for a site, and the user is being asked to take the app's own word for the binding.
 *
 * Its own composable rather than an expression inside [CredentialActivity.confirmNativeBinding], so
 * that what it offers can be rendered and read without a Credential Manager request, a relying
 * party, or an API 34 device. See `NativeBindingDialogTest`.
 *
 * [onDeny] is both answers that are not "allow": the dismiss button, Back, and a tap outside. They
 * were one answer when this was a platform dialog with a `setOnCancelListener` and they stay one
 * here — an unanswered consent is a refused consent.
 */
@Composable
internal fun NativeBindingDialog(
    packageName: String,
    rpId: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    LkDialog(
        onDismiss = onDeny,
        title = stringResource(R.string.credential_native_binding_title),
        body = stringResource(R.string.credential_native_binding_message, packageName, rpId),
        confirm = LkDialogAction(
            label = stringResource(R.string.credential_native_binding_allow),
            onClick = onAllow,
        ),
        dismissLabel = stringResource(R.string.credential_native_binding_cancel),
    )
}

package com.localkeep.core.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.core.app.ActivityCompat

/**
 * What to tell the user when a runtime permission request comes back denied.
 *
 * The app makes two permission requests: the TOTP QR scanner and capture-to-vault, both `play`
 * only. Each handled the granted branch and had no `else` at all, so tapping the button, reading
 * the disclosure and then denying produced a screen that did nothing and said nothing. A repo-wide
 * search confirmed the gap was total: nothing called `shouldShowRequestPermissionRationale`,
 * nothing linked to app settings, and no denial string existed to show.
 *
 * The two states need separating, because the remedy differs and only one is actionable from
 * inside the app:
 *
 *  - **denied once**: asking again works, so say what the feature needs and let them retry.
 *  - **denied permanently** ("don't ask again", or a policy-blocked permission): the system will
 *    not show the dialog again, so the only route is the app's settings page. Telling someone in
 *    this state to "allow camera access" is advice they cannot act on.
 *
 * Not a composable, because what to render differs per screen (both call sites already have an
 * `LkInlineError` slot) and the decision here is about permission state, not layout.
 */
data class PermissionDenial(
    val message: String,
    /** Non-null only when the system dialog will never appear again, so settings is the only route. */
    val settingsActionLabel: String?,
) {
    companion object {
        /**
         * Builds the denial to show for [permission] after a request returned `false`.
         *
         * [featureSentence] should name what the feature cannot do, in the app's voice and without
         * blaming the user — "LocalKeep can't scan a QR code without camera access", not "you
         * denied the camera permission".
         */
        fun after(context: Context, permission: String, featureSentence: String): PermissionDenial {
            val activity = context.findActivity()
            // Android's own signal for "the dialog will not be shown again". It reads inverted:
            // true means a rationale is warranted because asking again will work.
            val canAskAgain = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            return if (canAskAgain) {
                PermissionDenial(
                    message = context.getString(R.string.core_ui_permission_denial_retry, featureSentence),
                    settingsActionLabel = null,
                )
            } else {
                PermissionDenial(
                    message = context.getString(
                        R.string.core_ui_permission_denial_settings_required,
                        featureSentence,
                    ),
                    settingsActionLabel = context.getString(R.string.core_ui_permission_denial_open_settings),
                )
            }
        }
    }
}

/**
 * Lets a denial survive a configuration change.
 *
 * A notice lost to a rotation puts the user back in front of a button that appears to do nothing —
 * exactly the state the denial branch exists to end — so the state holding it has to be saveable,
 * and a data class is not saveable on its own. Two nullable strings, so the list can never be empty.
 */
val PermissionDenialSaver: Saver<PermissionDenial?, Any> = listSaver(
    save = { listOf(it?.message, it?.settingsActionLabel) },
    restore = { saved ->
        val message = saved.getOrNull(0) as String?
        message?.let { PermissionDenial(it, saved.getOrNull(1) as String?) }
    },
)

/**
 * Opens this app's own settings page, where a permanently-denied permission can be granted.
 *
 * `ACTION_APPLICATION_DETAILS_SETTINGS` with a `package:` URI — the only supported deep link to it.
 * Silently does nothing if no activity handles it, which some heavily-modified OEM builds manage:
 * the button not working is a poor outcome, and a crash is a worse one.
 */
fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/** Unwraps the `ContextWrapper` chain a Compose `LocalContext` may hand back. */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is android.content.ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

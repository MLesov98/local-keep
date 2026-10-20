package com.localkeep.autofill

import android.content.Context

/**
 * Who a credential is about to go to, or came from, in the user's terms: the site, or the app's own
 * label. The package name is the last resort rather than the first, because it is the one form of
 * this that a user cannot check against what is on their screen.
 *
 * Shared, because the two screens that name a caller had drifted. [PickerActivity] resolved the
 * label and [SaveConfirmActivity] did not, so the same app was "LocalKeep Autofill Test" when
 * offering a credential and "com.localkeep.autofilltest" when asking to store one — and the item
 * the save created carried the package name as its title, which is what the user then reads in
 * their vault forever. Found by the F15 QA persona, 2026-08-30.
 *
 * Pure, with the platform lookup passed in, so the ordering can be tested without a PackageManager.
 */
internal fun callerLabel(
    webDomain: String?,
    packageName: String?,
    fallback: String,
    resolveAppLabel: (String) -> String?,
): String {
    webDomain?.takeIf(String::isNotBlank)?.let { return it }
    val pkg = packageName?.takeIf(String::isNotBlank) ?: return fallback
    return resolveAppLabel(pkg)?.takeIf(String::isNotBlank) ?: pkg
}

/** [callerLabel] against the real PackageManager, which throws for a package it cannot find. */
internal fun Context.callerLabel(webDomain: String?, packageName: String?): String = callerLabel(
    webDomain = webDomain,
    packageName = packageName,
    fallback = getString(R.string.autofill_save_confirm_target_fallback),
    resolveAppLabel = { pkg ->
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    },
)

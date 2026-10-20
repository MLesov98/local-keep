package com.localkeep.app

import android.content.Context
import android.os.Build

/**
 * Non-hostile root/tamper soft-awareness (Phase 16, Task 1 Step 5). Never blocks — a rooted or
 * re-signed device can still run LocalKeep; this only powers a dismissible, one-time warning
 * surfaced in [MainActivity]. Not a security control: these signals are trivially spoofable and
 * are not relied upon anywhere in the crypto/vault path.
 */
object RootCheck {
    private val suPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/app/Superuser.apk",
    )

    fun looksRooted(): Boolean =
        Build.TAGS?.contains("test-keys") == true || suPaths.any { java.io.File(it).exists() }
}

/** Persists whether the user already dismissed the root-warning banner, so it only ever shows once. */
object RootWarningPreferences {
    private const val FILE_NAME = "localkeep_root_warning"
    private const val DISMISSED = "dismissed"

    fun isDismissed(context: Context): Boolean =
        preferences(context).getBoolean(DISMISSED, false)

    fun setDismissed(context: Context) {
        preferences(context).edit().putBoolean(DISMISSED, true).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}

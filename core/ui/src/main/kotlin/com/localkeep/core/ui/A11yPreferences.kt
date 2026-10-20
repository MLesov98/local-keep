package com.localkeep.core.ui

import android.content.Context
import com.localkeep.core.common.SettingsKeys
import android.provider.Settings
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * User preferences that affect how the app *expresses* itself rather than what it does.
 *
 * Lives in `:core:ui` rather than `:feature:settings` because the code that has to honour these
 * settings — [Haptics], and later the motion layer — is in `:core:ui`, and a feature module cannot
 * be depended on by a core one. `:feature:settings` writes here; everything else reads.
 *
 * The store name matches the one `:feature:settings` already uses, so a user's preferences stay in
 * a single file rather than being split across two by an implementation detail.
 */
object A11yPreferences {
    // Shared with :feature:settings, :feature:backup and :core:vault — see SettingsKeys.
    private const val FILE_NAME = SettingsKeys.FILE
    private const val REDUCE_HAPTICS = "reduce_haptics"

    /**
     * Compose-observable mirror of the stored value.
     *
     * SharedPreferences is not observable, so without this a screen would keep its old answer until
     * something else happened to recompose it — and a settings switch that appears to do nothing is
     * indistinguishable from one that is broken. Written by [setReduceHaptics], seeded on first read.
     */
    private var cached: MutableState<Boolean>? = null

    /** Our own preference, as Compose state. Does not include the OS setting — see [reduceHaptics]. */
    fun reduceHapticsState(context: Context): State<Boolean> = state(context)

    /**
     * True when expressive haptics should be silenced.
     *
     * The OS setting is consulted as well as our own, and either one is enough. A user who has
     * turned off touch feedback system-wide has already answered this question; making them find a
     * second switch inside one app is how an accessibility preference becomes a lie. Our preference
     * can only ever *add* silence — it can never re-enable feedback the system has turned off.
     */
    fun reduceHaptics(context: Context): Boolean =
        state(context).value || systemHapticsDisabled(context)

    fun setReduceHaptics(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(REDUCE_HAPTICS, value).apply()
        state(context).value = value
    }

    /**
     * Whether the OS "touch feedback" toggle is off.
     *
     * Defaults to *enabled* when the setting cannot be read: a missing value must not silently
     * disable feedback for everyone, which is the failure mode nobody would ever report.
     */
    fun systemHapticsDisabled(context: Context): Boolean = try {
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0
    } catch (_: Exception) {
        false
    }

    @Synchronized
    private fun state(context: Context): MutableState<Boolean> =
        cached ?: mutableStateOf(preferences(context).getBoolean(REDUCE_HAPTICS, false))
            .also { cached = it }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}

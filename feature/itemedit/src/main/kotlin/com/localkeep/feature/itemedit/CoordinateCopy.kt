package com.localkeep.feature.itemedit

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import com.localkeep.core.location.LocationFailure
import com.localkeep.core.model.Coordinate
import com.localkeep.core.ui.openAppSettings
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The sentence for each way a capture can fail.
 *
 * One mapping, in one place, so the editor and anything that shows a capture later cannot disagree
 * about what a timeout means. The ViewModel holds the [LocationFailure] and never the string — a
 * ViewModel that resolved these would need a Context, which is the rule this repo enforces with a
 * lint error rather than a convention.
 */
@StringRes
internal fun LocationFailure.messageRes(): Int = when (this) {
    LocationFailure.PERMISSION_DENIED -> R.string.itemedit_coordinate_error_permission
    LocationFailure.APPROXIMATE_ONLY -> R.string.itemedit_coordinate_error_approximate
    LocationFailure.PROVIDER_DISABLED -> R.string.itemedit_coordinate_error_provider_off
    LocationFailure.UNAVAILABLE -> R.string.itemedit_coordinate_error_unavailable
    LocationFailure.TIMED_OUT -> R.string.itemedit_coordinate_error_timeout
    // Never rendered — the ViewModel drops a cancellation rather than storing it, because the user
    // cancelling is not a failure to report back to them. Mapped anyway so this `when` stays
    // exhaustive and a future case cannot be added without a decision about its copy.
    LocationFailure.CANCELLED -> R.string.itemedit_coordinate_error_unavailable
}

/**
 * Whether a settings trip is where this gets fixed — this app's own page, or the system's.
 *
 * The two permission answers, plus [LocationFailure.PROVIDER_DISABLED] since 2026-09-10: that one
 * has a fix too, just not on this app's own page — see [settingsActionLabelRes] and
 * [Context.openLocationSettings]. A timeout indoors or an unusable fix are the two that truly send
 * the user nowhere, and offering a settings trip for either would spend the one bit of attention
 * the error message had on an action that leads nowhere.
 */
internal fun LocationFailure.isFixableInSettings(): Boolean =
    this == LocationFailure.PERMISSION_DENIED ||
        this == LocationFailure.APPROXIMATE_ONLY ||
        this == LocationFailure.PROVIDER_DISABLED

/**
 * The label for the settings action [isFixableInSettings] offers — distinct for
 * [LocationFailure.PROVIDER_DISABLED] because [Context.openSettingsFor] sends that one to the
 * system's location toggle rather than this app's own permission page, and "Open settings" would
 * be true of both but name neither.
 */
@StringRes
internal fun LocationFailure.settingsActionLabelRes(): Int =
    if (this == LocationFailure.PROVIDER_DISABLED) {
        R.string.itemedit_coordinate_open_location_settings
    } else {
        R.string.itemedit_coordinate_open_settings
    }

/**
 * Opens the system's location settings, where the device-wide toggle [LocationFailure
 * .PROVIDER_DISABLED] names actually lives. Not `Context.openAppSettings` (core/ui): that opens
 * this app's own permission page, which has no location on/off switch on it at all — this is a
 * different destination, not a relabelled trip to the same one.
 *
 * `ACTION_LOCATION_SOURCE_SETTINGS`, silently doing nothing if no activity handles it, matching
 * `Context.openAppSettings`'s own fallback: a button that does nothing is a poor outcome, and a
 * crash reached from an error message is a worse one.
 */
internal fun Context.openLocationSettings() {
    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/**
 * Which settings page fixes [failure] — this app's own, or the system's location toggle. The one
 * branch [isFixableInSettings] leaves to its callers, so `ItemEditScreen` does not have to name
 * [LocationFailure.PROVIDER_DISABLED] itself to route it correctly.
 */
internal fun Context.openSettingsFor(failure: LocationFailure) {
    if (failure == LocationFailure.PROVIDER_DISABLED) openLocationSettings() else openAppSettings()
}

/**
 * "Taken 3 Sep 2026, 21:04 · street level", or the date alone when the fix carried no accuracy at
 * all.
 *
 * One whole caption string per band rather than a band word substituted into a template. The word
 * sits mid-sentence here and at the head of a sentence in the live line, so no single translated
 * fragment could serve both without being wrong about capitalisation in one of them — and in the
 * four target languages the adjective has to agree with a noun the fragment does not carry. See
 * `strings.xml` for the per-language wording; [AccuracyBand] for why the number is gone.
 *
 * The capture time is part of the data rather than decoration: this item type claims to record
 * where the user was, and a position with no time attached is a claim about the present that
 * quietly becomes false. It is shown wherever the value is, in both the editor and the read view.
 *
 * Localized through the platform formatter and the composition's own locale, unlike the coordinate
 * pair itself — a date is read by a person and nothing else, so the argument that forces
 * `Locale.ROOT` on the numbers (a paste target has to be able to parse them) does not apply here.
 */
@Composable
internal fun coordinateCapturedCaption(fix: Coordinate): String {
    val locale = Locale.forLanguageTag(ComposeLocale.current.toLanguageTag())
    val stamp = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(fix.capturedAt)
    return stringResource(
        when (fix.displayAccuracyBand()) {
            AccuracyBand.PRECISE -> R.string.itemedit_coordinate_captured_caption_precise
            AccuracyBand.STREET -> R.string.itemedit_coordinate_captured_caption_street
            AccuracyBand.AREA -> R.string.itemedit_coordinate_captured_caption_area
            // No accuracy on the record at all — older fixes, and anything a provider handed over
            // without one. The date alone, rather than a band guessed from nothing.
            null -> R.string.itemedit_coordinate_captured_caption
        },
        stamp,
    )
}

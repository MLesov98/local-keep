package com.localkeep.feature.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Runs [refresh] when the screen is entered, and again every time the user comes back to it.
 *
 * For rows whose state lives in the *system* Settings app — whether LocalKeep is the autofill
 * service, whether a fingerprint is enrolled. This screen sends the user there and nothing comes
 * back: the system picker covers the activity without disposing anything in it, so returning is a
 * resume, not an entry. A `LaunchedEffect(Unit)` read stood here and ran once per composition, so
 * the autofill row still said "Off — tap to choose LocalKeep" after LocalKeep had been chosen,
 * until Settings was left and entered again (2026-10-04). The same resume read `TrustedAppsScreen`
 * uses for a grant made in a browser.
 */
@Composable
internal fun RefreshOnReturn(refresh: () -> Unit) {
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose {}
    }
}

package com.localkeep.feature.itemedit

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * A [BringIntoViewRequester] for a disclosure's panel, asked to bring it into view each time the
 * disclosure goes from closed to open.
 *
 * "Folder, tags and more" is the last row of a form taller than the screen, and on a login with a
 * two-factor block it sits at the very bottom. Tapping it composed the panel below the fold, so the
 * only visible change was a chevron turning: device report C3 finding 5 (2026-10-04), where the
 * tester toggled it four times before a long swipe showed what had been opening. This is the
 * BringIntoViewRequester idiom the title field, the inline error and the phrase chips already use.
 *
 * Only on a change of [open], never on arrival: choosing a folder disposes the form and composes it
 * again with the section still open (`moreOpen` in [ItemEditScreen] is hoisted for that), and the
 * user comes back to the top of their item, not to a scroll they did not make.
 *
 * Held by the caller *outside* its `if (open)` branch, which is what lets the effect outlive the
 * panel's first composition. One frame is awaited first so the panel has been laid out — the
 * request measures it — and a request the scroller cannot honour is dropped rather than thrown.
 */
@Composable
internal fun rememberOpenReveal(open: Boolean): BringIntoViewRequester {
    val requester = remember { BringIntoViewRequester() }
    var shownOpen by remember { mutableStateOf(open) }
    LaunchedEffect(open) {
        if (open && !shownOpen) {
            withFrameNanos { }
            try {
                requester.bringIntoView()
            } catch (_: IllegalStateException) {
                // Detached before the frame came round — the section was closed again at once.
            }
        }
        shownOpen = open
    }
    return requester
}

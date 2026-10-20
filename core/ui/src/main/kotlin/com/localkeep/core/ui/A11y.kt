package com.localkeep.core.ui

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Accessibility primitives shared by every screen (UI/UX spec §2, WCAG 2.1 AA).
 *
 * These exist as one helper each rather than as inline `semantics { }` blocks at ~40 call sites,
 * because the failure mode for accessibility is silent: nothing crashes, no test goes red, and the
 * only signal is a user who cannot operate the app. Centralising the wording means a screen reader
 * says the same thing about a hidden password everywhere, and that fixing the phrasing once fixes
 * it everywhere.
 */

/** The WCAG 2.1 AA minimum touch target on Android (spec §2). */
val MinTouchTarget = 48.dp

/**
 * Guarantees a control is at least [MinTouchTarget] in both directions.
 *
 * Needed because the app's controls are mostly bare `Text` with a `clickable` and a few dp of
 * padding — around 36dp tall, which is fine for a mouse and a real problem for a tremor, a thumb,
 * or switch access. Material's own components already reserve this; hand-rolled ones do not, and
 * every hand-rolled one in this app was under the minimum.
 *
 * `defaultMinSize` rather than `size`: it raises a small control to the floor without shrinking a
 * large one, so applying it can never make a target worse.
 *
 * It forces the floor onto the *constraints* it hands down, not onto the returned placeable — so a
 * `clickable` placed outside this modifier in the chain (the normal arrangement: `.clickable()`
 * *then* `.minTouchTarget()`) genuinely grows to the floor and is tappable everywhere in it. The
 * trade is that content which doesn't self-center under a forced-larger constraint — bare `Text` in
 * particular — is left top-start within the grown box instead of centered; callers with that shape
 * add `wrapContentSize(Alignment.Center)` themselves (see `Crumb` in `FolderBrowsing.kt`) rather
 * than this shared primitive trying to center for every caller, since a version that enlarges and
 * centers a returned placeable instead of forcing constraints down was tried and reverted here: it
 * left `clickable` sitting *inside* it (the other common arrangement, e.g. `LkIconButton`) reporting
 * only the pre-growth size to its own pointer-input and semantics bounds, so the extra space visually
 * reserved was never actually tappable — `MinTouchTargetTest` catches exactly that.
 */
fun Modifier.minTouchTarget(): Modifier =
    defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)

/**
 * Announces a secret field's masked/revealed state (spec §2: "password, hidden" / "revealed").
 *
 * A masked field renders as a run of bullets, which a screen reader reads out as bullets — the user
 * learns the character count and nothing else, not even that this is a password. Worse, the reveal
 * button changes the screen without saying so, so a blind user has no way to know whether the
 * secret is currently exposed. That last part is a privacy question, not only a usability one:
 * whether the password is on screen matters most to the person who cannot see that it is.
 *
 * [LiveRegionMode.Polite] so toggling reveal is announced when it happens, without interrupting.
 *
 * @param label the field's visible name, e.g. "Password".
 * @param value spoken only when [revealed]; a hidden secret is never read aloud.
 */
@Composable
fun Modifier.secretFieldSemantics(
    label: String,
    revealed: Boolean,
    value: String,
): Modifier {
    // Resolved out here, at composition: `semantics {}` is not a composable scope, and until
    // 2026-07-30 that is exactly why the state word was a Kotlin literal — a French device
    // announced "Mot de passe, hidden".
    val description = if (revealed) {
        stringResource(R.string.core_ui_secret_field_revealed, label, value)
    } else {
        stringResource(R.string.core_ui_secret_field_hidden, label)
    }
    return semantics {
        // The live region is what announces the *state* change. It lives on BOTH branches — it used
        // to be set only under `!revealed`, so the reveal transition dropped it in the same
        // semantics pass that flipped the content: auto-hide back to bullets announced "hidden",
        // and the reveal itself was silent, exactly the "blind user has no way to know whether the
        // secret is currently exposed" hole the header describes. On both, the state word is
        // announced in both directions.
        //
        // The value is only ever announced with it on the reveal a *user* initiated (they pressed
        // Show, so hearing the secret is the point of the control). The one transition nobody
        // asked for — the timer-driven auto-hide — flips back to the hidden string, which carries
        // no value, so the app never reads a secret aloud that the user did not just choose to
        // expose. That asymmetry is the security property the KDoc above describes, preserved here.
        //
        // Not `mergeDescendants = true` (a 2026-08-06 attempt, reverted): an editable
        // `BasicTextField` node is never merged into a parent — it must stay individually
        // focusable for the IME — so merging changed nothing on the edit form's secret fields and
        // only risked the read-only detail screen's carefully cleared single node. The two-stop
        // "Password, hidden" then "Password, edit box" shape on the edit form is a real a11y gap
        // that needs the state word carried into the inner editable node's own description, not a
        // merge on the wrapper.
        liveRegion = LiveRegionMode.Polite
        contentDescription = description
    }
}

/**
 * Names a control, replacing whatever its contents would otherwise be read as.
 *
 * For a control whose visible text is a glyph ("★", "☰") or repeats across many rows ("Copy") —
 * which is most of this app's controls, since it has almost no real icons yet.
 *
 * `mergeDescendants = true` makes this node absorb its subtree, so the description replaces the
 * child text rather than being announced alongside it. Verified in a `uiautomator` dump of the
 * vault list: the description lands as a child of the clickable, focusable node, which is the node
 * TalkBack focuses and whose subtree it reads — so what gets announced is "Move Work Login to
 * trash", not "Trash". Worth checking that way rather than by eye: an accessibility label that is
 * attached to the wrong node looks perfectly correct in the source and on screen.
 */
fun Modifier.labelled(description: String): Modifier =
    semantics(mergeDescendants = true) { contentDescription = description }

/**
 * Marks a node as decoration, removing it and its children from the accessibility tree.
 *
 * For things that carry no information a sighted user does not already get from adjacent text — the
 * monogram tile beside a title, a divider glyph. Left alone, a monogram makes TalkBack read "B O"
 * before every single row in the vault list.
 */
fun Modifier.decorative(): Modifier = clearAndSetSemantics { }

/**
 * A polite live region carrying [message], for errors and status that appear without a navigation.
 *
 * An error rendered as ordinary text below a form is, to a screen reader, a change that never
 * happened: focus stays on the field, nothing is announced, and the user is left to guess why the
 * button did not work.
 */
fun Modifier.announce(message: String): Modifier = semantics {
    liveRegion = LiveRegionMode.Polite
    contentDescription = message
}

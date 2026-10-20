package com.localkeep.core.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.R
import com.localkeep.core.ui.minTouchTarget

/**
 * A titled section whose body folds away, with a one-line [summary] that survives the fold.
 *
 * The visual pattern is the item editor's "Folder, tags and more" row, copied rather than shared:
 * a ring-outlined pill, a chevron that turns, and a panel that rises in underneath. The editor's own
 * version carries that screen's vocabulary and sits behind its ViewModel, so this one is the
 * general form for a screen whose sections are long and mostly not needed on any given visit.
 *
 * **The summary is what makes folding safe.** A closed section raises exactly one question —
 * whether anything is inside it that I should know about — and a summary answers it without a tap.
 * A disclosure that hides its own state is how a user loses a setting they made, or never learns a
 * check came back clean. Say what is true *now* ("Last export: …", "Not checked"), not what the
 * section is for; the [title] already does that. [summaryIcon] and [summaryAccent] mark the one
 * summary that is good news, so a clean result is visible from the folded row.
 *
 * **State is the caller's.** [open] and [onOpenChange] are hoisted so the caller can decide where it
 * lives — `rememberSaveable` for a flag, or derived from something else — and can do work when the
 * section folds (clearing a typed password, say), which this component cannot know about. The body
 * leaves composition when closed, so nothing inside it holds state across a fold; fields whose
 * content must survive one have to be hoisted above this call.
 *
 * [locked] is for a section that must stay open for as long as something inside it is pending: the
 * header stops being a control and says so to a screen reader by dropping its action, rather than
 * leaving a tap that does nothing.
 *
 * The body is arranged with `spacedBy(LkSpace.md)`, so — as with every `spacedBy` Column here — a
 * composable that emits several children into it gets its own internal gaps spaced as well. Wrap
 * such a thing in a Column of its own.
 *
 * Opening brings the panel into view, because a section opened at the foot of a long screen
 * otherwise opens out of sight and the only visible change is a chevron turning. Reduced motion is
 * handled by the animation specs ([lkTween] collapses to one frame, [lkRise] likewise).
 */
@Composable
fun LkDisclosure(
    title: String,
    summary: String?,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summaryIcon: ImageVector? = null,
    summaryAccent: Color? = null,
    locked: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LkTheme.colors
    val chevron by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = lkTween(LkMotion.Crossfade, easing = LkMotion.Settle),
        label = "disclosure-chevron",
    )
    // `onClickLabel` is the "double tap to…" hint, not the node's name or its state — the same
    // distinction the item editor's row and `LkFolderPicker`'s chevron spell out. The state word is
    // separate so a screen reader says whether the body is showing before the user has to open it
    // and listen for the layout to change.
    val actionLabel = stringResource(
        if (open) R.string.core_ui_disclosure_collapse_a11y else R.string.core_ui_disclosure_expand_a11y,
    )
    val stateWord = stringResource(
        if (open) R.string.core_ui_disclosure_state_expanded else R.string.core_ui_disclosure_state_collapsed,
    )
    val panelReveal = rememberDisclosureReveal(open)

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LkShape.xl)
                .lkSurface(LkShape.xl, Color.Transparent, c.ring)
                .minTouchTarget()
                .semantics { stateDescription = stateWord }
                .then(
                    if (locked) {
                        Modifier
                    } else {
                        Modifier.clickable(onClickLabel = actionLabel, role = Role.Button) {
                            onOpenChange(!open)
                        }
                    },
                )
                .padding(horizontal = LkSpace.md, vertical = LkSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
                Text(title, style = LkText.navLabel, color = c.textSecondary)
                if (summary != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LkSpace.xs),
                    ) {
                        if (summaryIcon != null) {
                            Icon(
                                summaryIcon,
                                contentDescription = null,
                                tint = summaryAccent ?: c.textFaint,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        Text(
                            summary,
                            style = LkText.monoCaption,
                            // Lifted off `textFaint` when it carries an accent: a tinted glyph beside
                            // faint text reads as a disabled row, and this is the good-news case.
                            color = if (summaryAccent != null) c.textMuted else c.textFaint,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!locked) {
                Icon(
                    LkIcons.ChevronRight,
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(18.dp).rotate(chevron),
                )
            }
        }
        if (open) {
            // `lkRise` and not `AnimatedVisibility`: this branch composes and disposes rather than
            // flipping while on screen, which is the one call pattern `lkRise` documents as safe.
            Column(
                Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(panelReveal)
                    .padding(top = LkSpace.xs)
                    .lkRise()
                    .lkSurface(LkShape.card, c.surface, c.ring)
                    .padding(LkSpace.md),
                verticalArrangement = Arrangement.spacedBy(LkSpace.md),
                content = content,
            )
        }
    }
}

/**
 * A [BringIntoViewRequester] for a disclosure's panel, asked to bring it into view each time the
 * disclosure goes from closed to open — and only then, never on arrival.
 *
 * A section that starts open (forced open by the caller, or restored open) is not an event and must
 * not move a screen the user has not touched. Held by [LkDisclosure] outside its `if (open)` branch,
 * which is what lets the effect outlive the panel's first composition. One frame is awaited so the
 * panel has been laid out — the request measures it — and a request the scroller cannot honour is
 * dropped rather than thrown.
 */
@Composable
private fun rememberDisclosureReveal(open: Boolean): BringIntoViewRequester {
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

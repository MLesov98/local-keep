package com.localkeep.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkRing
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import kotlinx.coroutines.delay

/**
 * Resolves a posted message here, in the one place that has a Context, so that posting from a
 * ViewModel does not force an English literal. See [LkNotices.Message].
 */
@Composable
private fun LkNotices.Message.resolve(): String = when (this) {
    is LkNotices.Message.Text -> value
    is LkNotices.Message.Res -> stringResource(id)
    is LkNotices.Message.Plural -> pluralStringResource(id, count, count)
}

/** 14dp padding either side of the 48dp touch target the action variant carries. */
private val NOTICE_MIN_HEIGHT = 76.dp

/** Tags on the host's tone glyph, so a test can tell a success plate from a refusal plate. */
internal object LkNoticeHostTags {
    const val SUCCESS_GLYPH = "lk-notice-glyph-success"
    const val REFUSAL_GLYPH = "lk-notice-glyph-refusal"
}

/**
 * Renders whatever [LkNotices] is currently announcing. Mount once, high in the app's layout — it
 * renders nothing until something is posted.
 *
 * Floats over the content rather than occupying a row of layout, for the reason
 * [ClipboardCountdown] does: as a bar in the layout it shoves the whole app up and back down for a
 * transient notice, and confirmations fire exactly when a list is already changing shape underneath.
 */
@Composable
fun LkNoticeHost(modifier: Modifier = Modifier) {
    val notice by LkNotices.current.collectAsState()
    val c = LkTheme.colors
    val reduce = LocalLkReduceMotion.current
    val haptics = rememberHaptics()

    // Timed here rather than in the singleton: auto-dismiss only matters while something is on
    // screen, and keeping it in the composable means posting needs no Handler and no Context.
    LaunchedEffect(notice) {
        val active = notice ?: return@LaunchedEffect
        // Every commit in the app is felt, from one place. [LkNotices.post] is documented as
        // announcing something that has *already succeeded*, so this fires on the real outcome
        // rather than on the tap that asked for it. Putting it on the Save *button* would have been
        // a lie: validation can refuse, the write can fail, and the user would have felt "done" for
        // something that did not happen. A notice posted as a refusal plays the error signature
        // instead — the same moment, the opposite verdict.
        haptics.play(active.tone.haptic)
        delay(active.durationMs)
        LkNotices.dismiss(active)
    }

    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier,
        enter = if (reduce) {
            fadeIn(tween(LkMotion.Quick))
        } else {
            slideInVertically(tween(LkMotion.Panel, easing = LkMotion.Sheet)) { it } +
                fadeIn(tween(LkMotion.Base))
        },
        exit = if (reduce) {
            fadeOut(tween(LkMotion.Quick))
        } else {
            slideOutVertically(tween(LkMotion.Base, easing = LkMotion.Standard)) { it } +
                fadeOut(tween(LkMotion.Quick))
        },
    ) {
        val active = notice ?: return@AnimatedVisibility
        val interaction = remember { MutableInteractionSource() }

        NoticeRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // One height for both variants. The action variant is taller only because its
                // button is a 48dp touch target (76dp with the plate's padding); a plain notice
                // came out 58dp, so the plate visibly changed size between "Moved to trash" with
                // Undo and a refusal beside it. Equalised upward: the touch floor cannot shrink.
                .heightIn(min = NOTICE_MIN_HEIGHT)
                // Opaque. 4% of a row underneath is not a subtle tint on a near-black palette: a
                // `textPrimary` glyph bleeding through the old 0.96 plate composited to #1B2023
                // against the plate's own #12171A, and at that separation an OLED renders it as
                // legible text — `shots/041-quickcopy.png` has a row title and a Copy button
                // readable *inside* "Password copied" (F-11, visual pass 2026-09-15). The plate is
                // now exactly [LkColors.cardTop], which [LkContrastTest] already audits as a
                // surface: textPrimary 15.87:1, textFaint 4.78:1.
                .background(c.cardTop, LkShape.xxl)
                .lkRing(LkShape.xxl, c.ringStrong)
                .clip(LkShape.xxl)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            hasAction = active.actionLabel != null && active.onAction != null,
        ) {
            val refusal = active.tone == LkNotices.Tone.Refusal
            // Coral is a failure (see LkColors); jade stays the "nothing is wrong" colour.
            val accent = if (refusal) c.coral else c.jade
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(accent.copy(alpha = 0.13f), LkShape.xs)
                    .testTag(if (refusal) LkNoticeHostTags.REFUSAL_GLYPH else LkNoticeHostTags.SUCCESS_GLYPH),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (refusal) LkIcons.Warning else LkIcons.Check,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp),
                )
            }
            // Polite, not assertive: these report something that already succeeded, so interrupting
            // whatever the screen reader is mid-sentence on would cost more than it tells.
            Text(
                text = active.message.resolve(),
                // Not inherited from [LkText.subtitle]: this style is built from rowTitle, so it
                // needs its own balanced break to avoid ending on a one-word line ("added.").
                style = LkText.rowTitle.copy(
                    fontSize = LkText.subtitle.fontSize,
                    lineBreak = LineBreak.Heading,
                ),
                color = c.textPrimary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val label = active.actionLabel?.resolve()
            val onAction = active.onAction
            if (label != null && onAction != null) {
                Box(
                    modifier = Modifier
                        .minTouchTarget()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = {
                                // Retire first: an undo that leaves its own offer on screen invites
                                // a second tap that would undo the undo.
                                LkNotices.dismiss(active)
                                onAction()
                            },
                        )
                        .labelled(label),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .lkPressScale(interaction)
                            .background(c.wash, LkShape.xs)
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                    ) {
                        Text(label, style = LkText.chip, color = c.textTertiary)
                    }
                }
            }
        }
    }
}

/** Glyph to message, and message to action, when they share a row. */
private val NOTICE_GAP = 12.dp

/** The message's characters per line below which the action gives up its place beside it. */
private const val NOTICE_MIN_MESSAGE_CHARS = 9

/**
 * The notice plate's layout: glyph, message, and an optional action.
 *
 * The children are, in order, the glyph, the message and (when [hasAction]) the action. Beside each
 * other when the message keeps at least [NOTICE_MIN_MESSAGE_CHARS] characters of its own type size on
 * a line; otherwise the action drops under the message, which gets the whole row after the glyph. At
 * 308dp and font scale 2.0 the glyph, the gaps and "Rückgängig" left the message 42dp, and
 * "1 Eintrag verschoben" was set a syllable per line over most of the list (L-03).
 *
 * The rule is a measured width, not a font scale: it is the room the message is left that matters,
 * and a 360dp window at ordinary type has plenty (the comps' layout) while a 308dp one does not.
 */
@Composable
private fun NoticeRow(
    modifier: Modifier,
    hasAction: Boolean,
    content: @Composable () -> Unit,
) {
    val minMessagePx = with(LocalDensity.current) { LkText.subtitle.fontSize.toPx() * NOTICE_MIN_MESSAGE_CHARS }
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = NOTICE_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val glyph = measurables[0].measure(loose)
        val action = if (hasAction) measurables.getOrNull(2)?.measure(loose) else null
        val maxWidth = constraints.maxWidth
        val beside = action == null || maxWidth - glyph.width - action.width - 2 * gap >= minMessagePx
        val messageWidth = when {
            action == null -> maxWidth - glyph.width - gap
            beside -> maxWidth - glyph.width - action.width - 2 * gap
            else -> maxWidth - glyph.width - gap
        }.coerceAtLeast(0)
        val message = measurables[1].measure(Constraints(maxWidth = messageWidth))
        val firstRow = maxOf(glyph.height, message.height, if (beside) action?.height ?: 0 else 0)
        val height = if (beside || action == null) firstRow else firstRow + action.height
        val rtl = layoutDirection == LayoutDirection.Rtl
        val width = constraints.maxWidth
        layout(width, height.coerceAtLeast(constraints.minHeight)) {
            val top = (height.coerceAtLeast(constraints.minHeight) - height) / 2
            fun place(p: Placeable, x: Int, y: Int) =
                p.place(if (rtl) width - x - p.width else x, y + top)
            place(glyph, 0, (firstRow - glyph.height) / 2)
            place(message, glyph.width + gap, (firstRow - message.height) / 2)
            if (action != null) {
                if (beside) {
                    place(action, width - action.width, (firstRow - action.height) / 2)
                } else {
                    place(action, glyph.width + gap, firstRow)
                }
            }
        }
    }
}

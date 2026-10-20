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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkRing
import kotlin.math.ceil
import kotlinx.coroutines.delay

/**
 * "Clears in Ns" affordance for a sensitive clipboard copy (UI/UX spec §2, §5.3).
 *
 * The automatic clear already worked, but it was invisible: nothing told the user that a copied
 * password was about to disappear, so a deliberate safety behaviour read as the clipboard being
 * unreliable. Mount this once, high in the app's layout — it renders nothing until a copy is
 * pending.
 *
 * It now floats over the content rather than occupying a row of layout. As a bar in the layout it
 * shoved the whole app up by ~60dp on every copy and back down when the timer expired, which is a
 * lot of movement for a transient notice — and it meant copying from the bottom of a list scrolled
 * the row you had just copied off the screen.
 */
@Composable
fun ClipboardCountdown(modifier: Modifier = Modifier) {
    val clip by ClipboardHelper.activeClip.collectAsState()
    val c = LkTheme.colors
    val reduce = LocalLkReduceMotion.current

    AnimatedVisibility(
        visible = clip != null,
        modifier = modifier,
        // Mirrors LkNoticeHost, which sits in this package and does the same job: the spec
        // requires all motion to honour reduce-motion, and this banner slid regardless.
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
        val active = clip ?: return@AnimatedVisibility
        val context = LocalContext.current
        val interaction = remember { MutableInteractionSource() }

        var remainingMs by remember(active) { mutableLongStateOf(active.remainingMs()) }
        LaunchedEffect(active) {
            while (remainingMs > 0) {
                delay(TICK_MS)
                remainingMs = active.remainingMs()
            }
        }
        val seconds = ceil(remainingMs / 1000.0).toInt()

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // Opaque, for the reason spelled out in [LkNoticeHost]: this is the card the
                // visual pass actually caught the vault list reading through (F-11,
                // `shots/041-quickcopy.png`), and the two must not drift apart.
                .background(c.cardTop, LkShape.xxl)
                .lkRing(LkShape.xxl, c.ringStrong)
                .clip(LkShape.xxl),
        ) {
            // The row's own content width: the window minus the toast's two 16dp margins and its
            // two 16dp paddings. Asked of [LkText.stacks] so this uses the same rule as every other
            // graphic-beside-caption row; at 308dp it is 244dp, under the 260dp line.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = LkText.stacks(LocalDensity.current.fontScale, maxWidth - 32.dp)
                val clearNow: @Composable () -> Unit = {
                    Box(
                        modifier = Modifier
                            .minTouchTarget()
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = { ClipboardHelper.clearNow(context) },
                            )
                            .labelled(stringResource(R.string.core_ui_clipboard_clear_now_action)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .lkPressScale(interaction)
                                .background(c.wash, LkShape.xs)
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                        ) {
                            Text(stringResource(R.string.core_ui_clipboard_clear_now), style = LkText.chip, color = c.textTertiary)
                        }
                    }
                }
                // Beside the text the action's label took the room: at 308dp and font scale 2.0 in
                // German "Jetzt löschen" left the title a column about one letter wide, and the
                // toast filled the window (SCL-016). The message is the point; the action drops
                // under it, as it does in [LkNoticeHost].
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(30.dp).background(c.jade.copy(alpha = 0.13f), LkShape.xs),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                LkIcons.Check,
                                contentDescription = null,
                                tint = c.jade,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            // Announce politely rather than interrupting; without this a screen-reader
                            // user gets no signal at all that the clipboard is about to empty.
                            Text(
                                text = stringResource(R.string.core_ui_clipboard_copied_label, active.label),
                                style = LkText.rowTitle.copy(fontSize = LkText.subtitle.fontSize),
                                color = c.textPrimary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            // The seconds are deliberately NOT inside that live region: a countdown that
                            // re-announced once a second would talk over everything else for half a minute.
                            Text(
                                pluralStringResource(R.plurals.core_ui_clipboard_clears_in_seconds, seconds, seconds),
                                style = LkText.monoCaption,
                                color = c.textFaint,
                            )
                        }
                        if (!stacked) clearNow()
                    }
                    if (stacked) {
                        // Aligned under the text column: plate (30dp) plus the 12dp gap.
                        Box(Modifier.padding(start = 42.dp)) { clearNow() }
                    }
                }
            }
            // The drain bar tracks the real deadline, so what you watch emptying is the timer that
            // will actually wipe the clipboard — not a decorative approximation of it.
            Box(Modifier.fillMaxWidth().height(2.dp).background(c.wash)) {
                Box(
                    Modifier
                        .fillMaxWidth(
                            (remainingMs.toFloat() / ClipboardHelper.CLEAR_DELAY_MS).coerceIn(0f, 1f),
                        )
                        .height(2.dp)
                        .background(Brush.horizontalGradient(listOf(c.jadeDim, c.jadeLit))),
                )
            }
        }
    }
}

private const val TICK_MS = 250L

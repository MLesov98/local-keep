package com.localkeep.feature.importwizard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkRing

/**
 * The picture beside an export step (UI/UX spec §6.2).
 *
 * Built from the app's own icon family rather than as separate artwork, which §7 asks for and which
 * also means these stay in step with the rest of the UI for free — a change to the stroke weight of
 * [LkIcons] lands here too.
 *
 * A plate is three things stacked: a tinted ground, an optional motif drawn behind the glyph that
 * says something about the *kind* of action (a dotted path for a journey, a corner bracket for a
 * search, a baseline for a surface the file lands on), and the glyph itself. The motif is what stops
 * twelve plates from reading as twelve identical squares with different icons in them.
 *
 * The plate is [decorative] on purpose: the step's own text is right beside it and says the same
 * thing in words. Announcing "settings icon" before reading the sentence just makes the list twice
 * as long to listen to.
 */
@Composable
internal fun StepIllustration(
    art: StepArt,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
) {
    val c = LkTheme.colors
    val accent = art.accent()

    Box(
        modifier = modifier
            .size(size)
            .clip(LkShape.lg)
            .lkRing(LkShape.lg, accent.copy(alpha = 0.18f))
            .decorative(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            // A soft off-centre wash rather than a flat fill, so the plate has a light source and
            // reads as an illustration instead of as another button.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.045f)),
                    center = Offset(this.size.width * 0.34f, this.size.height * 0.28f),
                    radius = this.size.width * 1.05f,
                ),
            )
            art.drawMotif(this, accent)
        }
        Icon(
            art.glyph(),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

/**
 * The accent carries the same meaning it does everywhere else in the app, so the colour is
 * information rather than variety: jade for ordinary progress, amber where the step is the one that
 * is easy to get wrong (picking the right format, confirming a password), brass for "you have to go
 * somewhere else to do this".
 */
@Composable
private fun StepArt.accent(): Color {
    val c = LkTheme.colors
    return when (this) {
        StepArt.CHOOSE_FORMAT, StepArt.AUTHENTICATE -> c.amber
        StepArt.DESKTOP, StepArt.WEB, StepArt.TRANSFER -> c.brass
        else -> c.jade
    }
}

private fun StepArt.glyph(): ImageVector = when (this) {
    StepArt.OPEN_APP -> LkIcons.Phone
    StepArt.DESKTOP -> LkIcons.Desktop
    StepArt.WEB -> LkIcons.Globe
    StepArt.MENU -> LkIcons.MoreDots
    StepArt.SETTINGS -> LkIcons.Settings
    StepArt.EXPORT -> LkIcons.Export
    StepArt.CHOOSE_FORMAT -> LkIcons.Layers
    StepArt.AUTHENTICATE -> LkIcons.Lock
    StepArt.SAVE_FILE -> LkIcons.Download
    StepArt.TRANSFER -> LkIcons.Import
    StepArt.FIND_FILE -> LkIcons.Search
    StepArt.DATA_SHAPE -> LkIcons.Table
}

/**
 * The motif behind the glyph.
 *
 * Deliberately drawn, not another icon layered underneath: these are marks the icon set has no
 * business containing on its own — a dotted route, a horizon line, a pair of framing brackets — and
 * they only mean anything at this size, in this plate.
 */
private fun StepArt.drawMotif(scope: androidx.compose.ui.graphics.drawscope.DrawScope, accent: Color) = with(scope) {
    val faint = accent.copy(alpha = 0.30f)
    val w = size.width
    val h = size.height
    val hair = 1.2.dp.toPx()

    when (this@drawMotif) {
        // A journey: a dotted arc from one corner toward the other.
        StepArt.TRANSFER -> drawLine(
            color = faint,
            start = Offset(w * 0.16f, h * 0.80f),
            end = Offset(w * 0.84f, h * 0.20f),
            strokeWidth = hair,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.07f, w * 0.07f)),
        )

        // A horizon the file settles onto.
        StepArt.SAVE_FILE -> drawLine(
            color = faint,
            start = Offset(w * 0.20f, h * 0.82f),
            end = Offset(w * 0.80f, h * 0.82f),
            strokeWidth = hair * 1.4f,
            cap = StrokeCap.Round,
        )

        // Framing brackets: something is being singled out.
        StepArt.FIND_FILE -> {
            val inset = w * 0.14f
            val arm = w * 0.13f
            listOf(
                // top-left
                Offset(inset, inset + arm) to Offset(inset, inset),
                Offset(inset, inset) to Offset(inset + arm, inset),
                // bottom-right
                Offset(w - inset, h - inset - arm) to Offset(w - inset, h - inset),
                Offset(w - inset, h - inset) to Offset(w - inset - arm, h - inset),
            ).forEach { (a, b) ->
                drawLine(faint, a, b, strokeWidth = hair, cap = StrokeCap.Round)
            }
        }

        // A stack of choices, only one of which is right.
        StepArt.CHOOSE_FORMAT -> {
            drawLine(faint, Offset(w * 0.22f, h * 0.80f), Offset(w * 0.78f, h * 0.80f), hair, StrokeCap.Round)
            drawLine(
                accent.copy(alpha = 0.16f),
                Offset(w * 0.28f, h * 0.88f),
                Offset(w * 0.72f, h * 0.88f),
                hair,
                StrokeCap.Round,
            )
        }

        // A ring, because this is the step that stops and asks.
        StepArt.AUTHENTICATE -> drawCircle(
            color = faint,
            radius = w * 0.36f,
            style = Stroke(width = hair),
        )

        // A desk surface / browser chrome line, respectively — both say "this is somewhere else".
        StepArt.DESKTOP, StepArt.WEB -> drawLine(
            color = faint,
            start = Offset(w * 0.12f, h * 0.14f),
            end = Offset(w * 0.88f, h * 0.14f),
            strokeWidth = hair,
            cap = StrokeCap.Round,
        )

        else -> Unit
    }
}

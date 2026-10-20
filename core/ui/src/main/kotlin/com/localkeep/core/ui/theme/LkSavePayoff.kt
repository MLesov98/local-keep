package com.localkeep.core.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.decorative

/**
 * The two screens every save shares: the write while it happens, then what it produced.
 *
 * **Why this is one component and not six.** `Item Flows.dc.html` states the rule as *"one encrypt,
 * one payoff — a save never looks different depending on what you saved"*, and it is not a
 * stylistic preference. The moment after a save is when the user is deciding whether this app can
 * be trusted with the next thing, and six variations on that moment is six chances to look
 * improvised. These lived privately inside the identity capture flow until 2026-08-19; the folder,
 * login, generator, note, file and 2FA flows now raise the same pair.
 *
 * **The trap this file exists to remember.** Both draw-on animations here once rendered *fully
 * drawn on frame one*, and no screenshot could see it: `animateFloatAsState(targetValue = 1f)`
 * starts at its first target, so a constant target is a still frame, not an animation. Every
 * progress value below is seeded `false` and flipped inside a `LaunchedEffect`. It has cost this
 * repo two passes to find; do not "simplify" it back.
 *
 * Strings are parameters, never resources resolved here. `core/ui` carries no user-facing
 * catalogue, and each flow's body copy differs — a note is "sealing the note into your vault file",
 * a login is not.
 */
private val SealRingSize = 96.dp
private val SavedMarkSize = 104.dp

/**
 * The write, while it is happening.
 *
 * A button reading "Encrypting…" is enough for a save measured in milliseconds and not enough for
 * one that is not — several full-resolution photographs, or a large attachment, look like a stuck
 * button. A screen that says what is being done, and the one thing the user cares about while it is
 * being done, is the honest version of the same wait.
 *
 * The shimmer loops, and that is inside this app's rule against looping attention animations:
 * it is a loading indicator, and it stops when the load does. It carries no progress because there
 * is none to carry — chunk encryption reports nothing usable, and a fake determinate bar for a real
 * indeterminate wait is the one lie this screen must not tell.
 */
@Composable
fun LkEncryptingScreen(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val reduce = LocalLkReduceMotion.current
    LkScreen(modifier = modifier, accent = c.jade, auraAlpha = 0.07f, auraBias = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = LkFrame.horizontal)
                .padding(top = LkFrame.top, bottom = LkFrame.bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(SealRingSize), contentAlignment = Alignment.Center) {
                var started by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { started = true }
                val ring by animateFloatAsState(
                    targetValue = if (started) 1f else 0f,
                    animationSpec = lkTween(LkMotion.Draw, easing = LkMotion.Standard),
                    label = "seal-ring",
                )
                Canvas(Modifier.fillMaxSize().decorative()) {
                    val stroke = 3.dp.toPx()
                    drawArc(
                        color = c.ring,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(stroke),
                    )
                    drawArc(
                        color = c.jade,
                        startAngle = -90f,
                        sweepAngle = 360f * ring,
                        useCenter = false,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                LkLogo(size = 34.dp, accent = c.jade)
            }
            Spacer(Modifier.height(LkSpace.lg))
            Text(title, style = LkText.sheetTitle, color = c.textStrong)
            Spacer(Modifier.height(LkSpace.sm))
            Text(
                body,
                style = LkText.bodyQuiet,
                color = c.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 250.dp),
            )
            Spacer(Modifier.height(LkSpace.lg))
            LkShimmerBar(reduce)
        }
    }
}

/**
 * Saved, and what that means.
 *
 * [notes] is a slot rather than a fixed pair of rows because what is worth saying after a save is
 * flow-specific: an identity made of passport photographs has two properties the user needs before
 * they walk away (it takes re-authentication to open, and it is not in the phone's backup), and a
 * new folder has none. A flow with nothing to add passes nothing and gets the check, the name and
 * the button.
 *
 * [onAction] also handles Back. A screen with one way forward that ignores the system gesture is a
 * dead end, and this one appears at the end of a flow where the user has already committed.
 */
@Composable
fun LkSavedScreen(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    notes: @Composable ColumnScope.() -> Unit = {},
) {
    val c = LkTheme.colors
    BackHandler(enabled = true) { onAction() }
    LkScreen(modifier = modifier, accent = c.jade, auraAlpha = 0.07f, auraBias = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = LkFrame.horizontal)
                .padding(top = LkFrame.top, bottom = LkFrame.bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            LkSavedMark()
            Spacer(Modifier.height(LkSpace.lg))
            Column(
                Modifier.lkRise(delayMillis = 340, durationMillis = LkMotion.Row),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    // LkSavedScreen is the shared 'Saved' payoff every save flow (folder, login,
                    // generator, note, file, 2FA) lands on, so a missing heading here was a missing
                    // TalkBack stop repeated across all of them, not a single-screen gap. Same
                    // defect/fix as unlock (W9) and item detail (V20) (fix-I, 21-2, 2026-09-13).
                    modifier = Modifier.semantics { heading() },
                    style = LkText.heading,
                    color = c.textStrong,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(LkSpace.sm))
                Text(body, style = LkText.body, color = c.textMuted, textAlign = TextAlign.Center)
            }
            Column(
                Modifier.lkRise(delayMillis = 420, durationMillis = LkMotion.Row),
                verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
                content = notes,
            )
            Spacer(Modifier.weight(1f))
            LkSecondaryButton(
                text = actionLabel,
                modifier = Modifier.fillMaxWidth(),
                onClick = onAction,
            )
        }
    }
}

/** The bloom, the disc and the check that draws inside it — the payoff's whole vocabulary. */
@Composable
private fun LkSavedMark(modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    Box(modifier.size(SavedMarkSize), contentAlignment = Alignment.Center) {
        var bloomed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { bloomed = true }
        val bloom by animateFloatAsState(
            targetValue = if (bloomed) 1f else 0f,
            animationSpec = lkTween(LkMotion.Bloom, delayMillis = 120, easing = LkMotion.Settle),
            label = "saved-bloom",
        )
        // Drawn only while in flight. At rest the gradient is invisible anyway, and skipping the
        // Canvas entirely keeps a settled screen off the draw path.
        if (bloom > 0f && bloom < 1f) {
            Canvas(Modifier.fillMaxSize().decorative()) {
                // opacity 0 → .55 at 55% → 0, against a scale of .5 → 1.9.
                val fade = if (bloom < 0.55f) bloom / 0.55f * 0.55f else (1f - bloom) / 0.45f * 0.55f
                val scale = 0.5f + 1.4f * bloom
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to c.jade.copy(alpha = 0.4f * (fade / 0.55f)),
                        0.66f to Color.Transparent,
                        center = center,
                        radius = size.minDimension / 2f * scale,
                    ),
                    radius = size.minDimension / 2f * scale,
                )
            }
        }
        Box(
            Modifier
                .size(76.dp)
                .lkSurface(CircleShape, c.jade.copy(alpha = 0.12f), c.jade.copy(alpha = 0.34f))
                .decorative(),
        )
        var started by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { started = true }
        val draw by animateFloatAsState(
            targetValue = if (started) 1f else 0f,
            animationSpec = lkTween(480, delayMillis = 200, easing = LkMotion.Standard),
            label = "saved-check",
        )
        Canvas(Modifier.size(46.dp).decorative()) {
            val path = Path().apply {
                moveTo(size.width * 0.26f, size.height * 0.53f)
                lineTo(size.width * 0.42f, size.height * 0.69f)
                lineTo(size.width * 0.74f, size.height * 0.33f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val segment = Path()
            measure.getSegment(0f, measure.length * draw, segment, true)
            drawPath(
                segment,
                c.jade,
                style = Stroke(
                    width = 3.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/**
 * A progress hairline with no progress to report — the only honest kind for a sealed write.
 *
 * Public because the file flow needs the same band on a pending attachment row, where the wait is
 * the same kind of wait: real, indeterminate, and capable of stalling on a large video without
 * failing. Under reduced motion it becomes a static jade track — still a "something is happening"
 * signal, with the travel removed rather than shortened.
 */
@Composable
fun LkShimmerBar(
    reduce: Boolean,
    modifier: Modifier = Modifier,
    width: Dp? = 190.dp,
    height: Dp = 3.dp,
) {
    val c = LkTheme.colors
    val infinite = rememberInfiniteTransition(label = "shimmer")
    val travel by infinite.animateFloat(
        initialValue = -1.3f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            tween(LkMotion.Shimmer, easing = LkMotion.Standard),
            RepeatMode.Restart,
        ),
        label = "shimmer-x",
    )
    Box(
        modifier
            .then(if (width != null) Modifier.size(width = width, height = height) else Modifier.fillMaxWidth().height(height))
            .clip(LkShape.xs)
            .background(c.ring)
            .decorative(),
    ) {
        if (!reduce) {
            Canvas(Modifier.fillMaxSize()) {
                val bandWidth = size.width * 0.44f
                val x = size.width * travel
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to c.jade,
                        1f to Color.Transparent,
                        startX = x,
                        endX = x + bandWidth,
                    ),
                    topLeft = Offset(x, 0f),
                    size = Size(bandWidth, size.height),
                )
            }
        } else {
            Box(Modifier.fillMaxSize().background(c.jade, RectangleShape))
        }
    }
}

package com.localkeep.core.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.R
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.labelled
import kotlin.math.roundToInt

/**
 * Progress and measurement.
 *
 * One rule governs everything here: **a bar that represents real work is never eased and never
 * padded.** The KDF ring in particular is the app's most load-bearing piece of honesty — Argon2id
 * genuinely takes tens of seconds on a mid-range phone, and a progress bar that smooths that over
 * would be teaching the user that the number is decorative. It is not; it is the shape of the work.
 */

/**
 * A circular progress ring.
 *
 * @param progress 0f..1f.
 * @param trackColor the unfilled remainder.
 * @param brush the fill; a gradient for the KDF and score rings, a solid for TOTP.
 * @param animated false for the KDF ring, whose value already arrives at the rate of real work —
 *   animating it would add a lag that makes the count look fake at exactly the wrong moment.
 */
@Composable
fun LkProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    strokeWidth: Dp = 6.dp,
    brush: Brush? = null,
    color: Color = LkTheme.colors.jade,
    trackColor: Color = Color(0xFF1A2124),
    animated: Boolean = true,
    animationMillis: Int = 1_100,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val target = progress.coerceIn(0f, 1f)
    val animatedValue by animateFloatAsState(
        targetValue = target,
        animationSpec = lkTween(animationMillis, easing = LkMotion.Standard),
        label = "ring",
    )
    val shown = if (animated) animatedValue else target
    val strokePx = with(LocalDensity.current) { strokeWidth.toPx() }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().decorative()) {
            val inset = strokePx / 2f
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            if (shown > 0f) {
                val sweep = 360f * shown
                if (brush != null) {
                    drawArc(
                        brush = brush,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                } else {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            }
        }
        content()
    }
}

/**
 * The segmented strength meter: [segments] bars that fill up to [level].
 *
 * Segments rather than a continuous bar because password strength is not a continuous quantity the
 * user can act on — "three of five" is a judgement they can hold, "61%" invites tuning a number.
 */
@Composable
fun LkSegmentMeter(
    level: Int,
    tone: Color,
    modifier: Modifier = Modifier,
    segments: Int = 5,
    height: Dp = 5.dp,
) {
    val c = LkTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().decorative(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(segments) { index ->
            val filled = index < level
            // 300ms on the design's settle curve, not [LkMotion.Base]. A strength band changing is
            // one of the few crossfades that has to be *seen* — a bar that snaps between colours
            // reads as a validation failure rather than as a measurement moving. The 40ms this adds
            // over the old value is what the comps specify for every meter in the app, and one
            // speed for one component is the point of having the component.
            val fill by animateColorAsState(
                targetValue = if (filled) tone else c.controlIdle,
                animationSpec = lkTween(LkMotion.Crossfade, easing = LkMotion.Settle),
                label = "segment-$index",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .background(fill, LkShape.xs),
            )
        }
    }
}

/** A thin continuous bar — entropy, and the clipboard countdown's drain. */
@Composable
fun LkBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = LkTheme.colors.jade,
    brush: Brush? = null,
    height: Dp = 3.dp,
    animated: Boolean = true,
) {
    val c = LkTheme.colors
    val target = fraction.coerceIn(0f, 1f)
    val animatedValue by animateFloatAsState(target, lkTween(LkMotion.Screen), label = "bar")
    val shown = if (animated) animatedValue else target
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(c.wash, LkShape.xs)
            .decorative(),
    ) {
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(height)
                .then(
                    if (brush != null) {
                        Modifier.background(brush, LkShape.xs)
                    } else {
                        Modifier.background(color, LkShape.xs)
                    },
                ),
        )
    }
}

/**
 * The length slider.
 *
 * Hand-drawn rather than Material's `Slider` because the track is a two-stop gradient and the thumb
 * carries a jade halo — but the accessibility contract is kept identical: it exposes a
 * [ProgressBarRangeInfo] and a `setProgress` action, so TalkBack's volume-key adjustment works the
 * same as on the stock control. A hand-rolled slider that skips that is unusable without sight.
 */
@Composable
fun LkSlider(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction = ((value - range.first).toFloat() / span).coerceIn(0f, 1f)
    val density = LocalDensity.current
    val thumbRadius = with(density) { 10.dp.toPx() }

    val trackWidth = remember { mutableFloatStateOf(0f) }
    fun valueAt(x: Float): Int {
        val width = trackWidth.floatValue
        if (width <= 0f) return value
        val f = ((x - thumbRadius) / (width - thumbRadius * 2)).coerceIn(0f, 1f)
        return range.first + (f * span).roundToInt()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 48dp of pointer surface for a 24dp track. The drag and tap handlers live on this node,
            // so the height here is the height of the thing you can actually grab — it was 24dp,
            // half the floor, for a control with no stepper alternative and a 20dp thumb. Only `x`
            // is read out of the gesture, so widening the vertical band costs nothing but slack.
            .height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = range.first.toFloat()..range.last.toFloat(),
                    steps = span - 1,
                )
                setProgress { target ->
                    onValueChange(target.roundToInt().coerceIn(range.first, range.last))
                    true
                }
            }
            .labelled(stringResource(R.string.core_ui_slider_label, label, value.toString()))
            .lkFocusRing(LkShape.xl)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onValueChange((value - 1).coerceIn(range.first, range.last))
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onValueChange((value + 1).coerceIn(range.first, range.last))
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(range) {
                detectHorizontalDragGestures(
                    onDragStart = { onValueChange(valueAt(it.x)) },
                    onHorizontalDrag = { change, _ -> onValueChange(valueAt(change.position.x)) },
                )
            }
            .pointerInput(range) {
                detectTapGestures { onValueChange(valueAt(it.x)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(
            Modifier.fillMaxWidth().height(24.dp),
        ) {
            trackWidth.floatValue = size.width
            val cy = size.height / 2f
            val trackHeight = 4.dp.toPx()
            val usable = size.width - thumbRadius * 2
            val thumbX = thumbRadius + usable * fraction

            drawRoundRect(
                color = c.wash,
                topLeft = Offset(0f, cy - trackHeight / 2),
                size = Size(size.width, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2),
            )
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(c.jadeDim, c.jadeLit)),
                topLeft = Offset(0f, cy - trackHeight / 2),
                size = Size(thumbX, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2),
            )
            // The halo is what makes a 20dp thumb legible against a near-black track without
            // growing it into a lozenge.
            drawCircle(color = c.jade.copy(alpha = 0.16f), radius = thumbRadius + 4.dp.toPx(), center = Offset(thumbX, cy))
            drawCircle(color = Color(0xFFF2F6F7), radius = thumbRadius, center = Offset(thumbX, cy))
        }
    }
}

/**
 * A skeleton placeholder: the shape of the thing that is loading, breathing gently.
 *
 * Used instead of a spinner wherever the eventual content has a known shape — a list of rows, a
 * media frame. A spinner says "something is happening somewhere"; a skeleton says "this is what is
 * arriving, and it is this big", which is what stops a slow decrypt from reading as a hang.
 *
 * Under reduced motion it holds still at its dimmest — an animation that only pulses is exactly the
 * kind a user who turned animations off was turning off.
 */
@Composable
fun LkSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = LkShape.xl,
) {
    val c = LkTheme.colors
    val reduce = LocalLkReduceMotion.current
    val infinite = rememberInfiniteTransition(label = "skeleton")
    val breathe by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = LkMotion.Standard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-breathe",
    )
    val alpha = if (reduce) 0.06f else 0.05f + 0.05f * breathe
    Box(
        modifier
            .background(c.textStrong.copy(alpha = alpha), shape)
            .lkRing(shape, c.ring)
            .decorative(),
    )
}

/**
 * A pulsing halo behind a focal glyph — boot, and the re-auth fingerprint.
 *
 * Purely atmospheric, so it disappears entirely under reduced motion rather than being slowed: a
 * user who asked for no animation should not get a slower pulse.
 */
@Composable
fun LkPulseHalo(
    modifier: Modifier = Modifier,
    color: Color = LkTheme.colors.jade,
    size: Dp = 132.dp,
) {
    val reduce = LocalLkReduceMotion.current
    val infinite = rememberInfiniteTransition(label = "halo")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_800, easing = LkMotion.Standard),
            repeatMode = RepeatMode.Restart,
        ),
        label = "halo-pulse",
    )
    Canvas(modifier.size(size).decorative()) {
        val base = this.size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.22f), Color.Transparent),
                center = center,
                radius = base,
            ),
            radius = base,
        )
        if (!reduce) {
            // A ring that expands out and fades, restarting — the "something is happening, and it
            // is not stuck" signal for a screen with no other movement.
            drawCircle(
                color = color.copy(alpha = 0.18f * (1f - pulse)),
                radius = base * (0.62f + 0.5f * pulse),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

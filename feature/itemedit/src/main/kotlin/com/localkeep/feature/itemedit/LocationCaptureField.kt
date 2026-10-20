package com.localkeep.feature.itemedit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.localkeep.core.location.CaptureConvergence
import com.localkeep.core.location.FixRequest
import com.localkeep.core.model.Coordinate
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkProgressRing
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.lkTween
import kotlin.math.ln
import kotlin.math.min

/**
 * What the field is doing right now, in terms the visual can render — a narrower question than
 * [CaptureConvergence], which only exists while a capture is running.
 *
 * Pulled out of the composable and made a pure function of the three inputs it depends on
 * ([radarPhaseFor]) because this module's Compose test harness does not reach idle (see
 * `CoordinateFormatTest`'s own note on that) — this is the part of the field a plain JVM test can
 * hold.
 */
internal enum class RadarPhase { IDLE, SEARCHING, REFINING, SETTLED, CAPTURED }

internal fun radarPhaseFor(
    capturing: Boolean,
    hasFix: Boolean,
    convergence: CaptureConvergence,
): RadarPhase = when {
    !capturing && !hasFix -> RadarPhase.IDLE
    !capturing && hasFix -> RadarPhase.CAPTURED
    capturing && !hasFix -> RadarPhase.SEARCHING
    convergence == CaptureConvergence.SETTLED -> RadarPhase.SETTLED
    else -> RadarPhase.REFINING
}

/**
 * Nine candidate readings, as unit offsets from centre, flattened vertically and multiplied by the
 * current spread — transcribed from the visual pass's own `SEEDS` constant so the two stay the same
 * shape.
 */
private val CLOUD_SEEDS = listOf(
    -0.86f to -0.42f, 0.71f to -0.66f, 0.34f to 0.88f, -0.58f to 0.74f, 0.94f to 0.21f,
    -0.24f to -0.92f, 0.12f to -0.35f, -0.44f to 0.28f, 0.62f to 0.52f,
)

/**
 * What the running capture has to say for itself, as the live line and the panel render it.
 *
 * Four answers rather than the radar's five phases, because the question here is different: the
 * phase says what the *field* is doing, this says what the *reading* is worth. The two split at
 * [NetworkOnly], which the phase model cannot express — a wifi or cell position has landed in the
 * form but no satellite has answered, and the old live line said "Still getting closer…" over a
 * reading that was not a satellite fix at all.
 *
 * Pure, so `LocationCaptureFieldTest` can hold it; the Compose harness in this module does not
 * reach idle (see `CoordinateFormatTest`'s note).
 */
internal sealed interface CaptureReadout {
    /** No reading of any kind yet. */
    data object Searching : CaptureReadout

    /** A wifi/cell position is in the form; satellites have not answered. Not a figure worth printing. */
    data object NetworkOnly : CaptureReadout

    /** A satellite reading, still falling. [band] null when the fix carried no accuracy. */
    data class Improving(val band: AccuracyBand?) : CaptureReadout

    /** A satellite reading the last window did not improve. Waiting longer will not either. */
    data class Settled(val band: AccuracyBand?) : CaptureReadout
}

internal fun captureReadoutFor(fix: Coordinate?, convergence: CaptureConvergence): CaptureReadout =
    when {
        fix == null -> CaptureReadout.Searching
        convergence == CaptureConvergence.SEARCHING -> CaptureReadout.NetworkOnly
        convergence == CaptureConvergence.SETTLED -> CaptureReadout.Settled(fix.displayAccuracyBand())
        else -> CaptureReadout.Improving(fix.displayAccuracyBand())
    }

/**
 * How wide the candidate cloud scatters, as a function of the reading's *actual* accuracy.
 *
 * This used to be a constant per phase — 96 while searching, 46 while refining, 38 or 18 once
 * settled by band — which meant a 90 m reading and an 11 m reading drew exactly the same picture
 * for as long as both were improving. A loading indicator whose motion ignores the quantity it
 * claims to show is decoration, and the principal read it as one: nothing on the panel said how
 * close the reading was or whether it was still closing. Now the spread *is* the accuracy, on a
 * log scale between the capture's own two poles — [FixRequest.targetAccuracyMeters] (good enough
 * to stop) draws at the tight end and [FixRequest.maxAccuracyMeters] (the worst this feature will
 * call a coordinate) at the wide one. Log rather than linear because that is how the series
 * behaves: the fall from 80 m to 40 m and the fall from 8 m to 4 m are the same *event* to a
 * receiver, and should move the cloud by the same amount.
 *
 * SEARCHING has no reading to draw, so it stays at the wide pole; a reading with no accuracy at
 * all falls back to the old per-phase constant rather than inventing a radius.
 */
internal fun cloudSpreadFor(phase: RadarPhase, accuracyMeters: Float?): Dp {
    if (phase == RadarPhase.IDLE) return 0.dp
    if (phase == RadarPhase.SEARCHING) return CLOUD_SPREAD_WIDE
    val accuracy = accuracyMeters?.takeIf { it.isFinite() && it > 0f }
        ?: return if (phase == RadarPhase.REFINING) CLOUD_SPREAD_UNKNOWN else CLOUD_SPREAD_TIGHT
    val fraction = (ln(accuracy / CLOUD_TIGHT_METERS) / ln(CLOUD_WIDE_METERS / CLOUD_TIGHT_METERS))
        .coerceIn(0f, 1f)
    return CLOUD_SPREAD_TIGHT + (CLOUD_SPREAD_WIDE - CLOUD_SPREAD_TIGHT) * fraction
}

/**
 * The comp's own numbers, and they are four times the ones this shipped with. `Location
 * Capture.dc.html` scatters at a spread of 96 and closes to 18, in a 340x220 panel on a 392-wide
 * device — one CSS pixel to one dp. The first pass used 40 → 9, which is the same *shape* an
 * eighth of the size: the points sat in a clump barely wider than the pin and the draw-in was
 * invisible from arm's length. Reported 2026-08-20 as the screen being "not lively".
 */
private val CLOUD_SPREAD_WIDE = 96.dp
private val CLOUD_SPREAD_TIGHT = 18.dp

/** The old REFINING constant, kept only for a reading that reports no accuracy at all. */
private val CLOUD_SPREAD_UNKNOWN = 46.dp

/** Lifted from the shipped [FixRequest] rather than restated: the two poles the capture itself uses. */
private val CLOUD_TIGHT_METERS: Float = FixRequest().targetAccuracyMeters
private val CLOUD_WIDE_METERS: Float = FixRequest().maxAccuracyMeters

/**
 * The field that replaces the old 20dp metres ring: readings scatter, draw in and stop, which is
 * the only question a user watching this is actually asking. The cloud's spread is the reading's
 * accuracy ([cloudSpreadFor]) and the pin's colour is its band, while the live line underneath
 * says the same state in words — none of them alone, so it survives for anyone who reads words,
 * colour or motion but not all three.
 */
@Composable
internal fun LocationRadarField(
    phase: RadarPhase,
    band: AccuracyBand?,
    accuracyMeters: Float?,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val reduceMotion = LocalLkReduceMotion.current
    val hasMarker = phase == RadarPhase.REFINING ||
        phase == RadarPhase.SETTLED ||
        phase == RadarPhase.CAPTURED

    // Amber for anything outside the settle radius, jade inside it — the same split this drew
    // before the band became three-valued, because the question the colour answers is still the
    // binary one (is this the radius the capture treats as one place?) and that threshold is
    // FixRequest's, not the copy boundary. LkColors is satisfied either way: amber is "worth
    // looking at, nothing is wrong", which a street-level reading is.
    val markerColor = if (band != null && band != AccuracyBand.PRECISE) c.amber else c.jade
    // Both crossfade rather than stepping. The comp gives every reading
    // `transition:transform 760ms …, opacity 500ms, background 500ms`; the app implemented the
    // transform and dropped the other two, so grey candidates *became* jade candidates between one
    // frame and the next — nine dots changing colour and brightness at once, which is the third
    // thing that read as sparkling. `lkTween` collapses to one frame under reduced motion, so the
    // end state is unchanged there.
    val pointColor by animateColorAsState(
        if (phase == RadarPhase.SEARCHING) c.textFaint else markerColor,
        lkTween(LkMotion.Fade),
        label = "cloud-color",
    )
    val pointAlpha by animateFloatAsState(
        when (phase) {
            RadarPhase.IDLE -> 0f
            RadarPhase.CAPTURED -> 0.28f
            RadarPhase.SEARCHING -> 0.42f
            else -> 0.66f
        },
        lkTween(LkMotion.Fade),
        label = "cloud-alpha",
    )

    // How wide the cloud scatters: the reading's own accuracy, so every interim that lands moves it
    // in by exactly as much as the reading improved. See cloudSpreadFor() for why a per-phase
    // constant was a lie here. Tweened, so each step is a draw-in rather than a jump; lkTween
    // collapses to one frame under reduced motion and the end state is unchanged.
    val spreadTarget = cloudSpreadFor(phase, accuracyMeters)
    val spread by animateDpAsState(spreadTarget, lkTween(LkMotion.Converge), label = "radar-spread")

    // The pin drops once, the first time a reading arrives, and never re-drops while later readings
    // refine it — only the cloud moves after that. Keyed on `hasMarker` rather than `phase`, so the
    // REFINING <-> SETTLED flip it survives does not restart it.
    val pinDrop = remember { Animatable(if (hasMarker) 1f else 0f) }
    val pinDropSpec = lkTween<Float>(LkMotion.Drop, easing = LkMotion.Overshoot)
    LaunchedEffect(hasMarker) {
        if (hasMarker) pinDrop.animateTo(1f, pinDropSpec) else pinDrop.snapTo(0f)
    }

    // The settle ring: fires once when the series stops improving, then everything holds still —
    // which is itself the message. Keyed on `phase` itself, not just the SETTLED boolean, so a
    // capture that reaches CAPTURED before the 480ms pulse finishes (a fast mock provider, say)
    // cancels the animating coroutine but still snaps the ring to fully faded in the `else` branch
    // — otherwise it would freeze wherever the cancellation caught it and never disappear.
    val settlePulse = remember { Animatable(1f) }
    val settleSpec = lkTween<Float>(LkMotion.Converge, easing = LkMotion.Settle)
    // Keyed on *whether the reading has stopped moving*, not on the phase, and that is the whole
    // fix. SETTLED and CAPTURED are the same event as far as this ring is concerned — the series has
    // finished — and keying on `phase` meant the flip between them cancelled the animation and the
    // `else` branch snapped it to invisible.
    //
    // In practice that made the ring unreachable rather than brief. SETTLED only exists *while a
    // capture is still running*, and a capture that has stopped improving ends almost immediately —
    // so the 760 ms pulse was being cut off after a frame or two, every time. Reported 2026-08-20:
    // "the dots around the map point in the circle do not move when finished and there's no
    // transition or nice effect once they're done". They were right that nothing fires; the reason
    // is that the one state it fires in barely exists.
    //
    // A capture that never settles at all — stopped by hand, or timed out — still reaches CAPTURED
    // and still gets the ring, which is correct: the question it answers is "is this finished?", and
    // it is.
    val finished = phase == RadarPhase.SETTLED || phase == RadarPhase.CAPTURED
    LaunchedEffect(finished) {
        if (finished) {
            settlePulse.snapTo(0f)
            settlePulse.animateTo(1f, settleSpec)
        } else {
            settlePulse.snapTo(1f)
        }
    }

    // Sweeping is purely atmospheric — "still searching", nothing more — so like LkPulseHalo it
    // disappears under reduced motion instead of running slowed down: a wedge frozen mid-turn would
    // be a stranger sight than no wedge at all.
    val sweeping = phase == RadarPhase.SEARCHING && !reduceMotion

    // `Animatable` in a `LaunchedEffect`, not `rememberInfiniteTransition`. The infinite transition
    // holds a frame-clock subscription for the life of the composition and can only be gated at the
    // draw — so these two ran in IDLE, in SETTLED, in CAPTURED and under reduced motion, drawing
    // nothing, for as long as the editor was open. Keyed on `sweeping`, the subscription genuinely
    // ends when the sweep does. Same pattern the settle ring above already uses.
    val sweepAngle = remember { Animatable(0f) }
    val echo = remember { Animatable(0f) }
    // Hoisted: `lkTween` is @Composable (it reads the reduce-motion local), so it cannot be called
    // from inside the effect.
    val sweepSpec = lkTween<Float>(SweepPeriodMillis, easing = LinearEasing)
    val echoSpec = lkTween<Float>(SweepPeriodMillis, easing = LkMotion.Standard)
    LaunchedEffect(sweeping) {
        if (!sweeping) {
            sweepAngle.snapTo(0f)
            echo.snapTo(0f)
            return@LaunchedEffect
        }
        launch {
            while (true) {
                echo.snapTo(0f)
                echo.animateTo(1f, echoSpec)
            }
        }
        while (true) {
            sweepAngle.snapTo(0f)
            sweepAngle.animateTo(360f, sweepSpec)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            // 220dp, the comp's panel height exactly. At 200 the three backdrop rings came out at
            // 88/56/25dp instead of the comp's 98/62/28 — `unit` is half the *shorter* side, so the
            // height is what sets them.
            .height(220.dp)
            .lkSurface(LkShape.card, c.cardFill, c.ring),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().decorative()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val unit = min(size.width, size.height) / 2f
            val gridLine = Color.White.copy(alpha = 0.022f)
            val ringLine = Color.White.copy(alpha = 0.045f)
            // Every stroke and radius below is a dp converted here, never a raw pixel count. A bare
            // `1f` is one *physical* pixel: on this phone's 3x screen the rings and the nine
            // readings were drawing a third of a dp wide, which is the second half of why the field
            // read as inert — at arm's length there was very nearly nothing on it.
            val hairline = 1.dp.toPx()

            // A faint 28dp grid, the panel's own fixed backdrop, so the cloud's motion is what
            // reads as the state changing rather than the ground moving under it.
            val step = 28.dp.toPx()
            var x = cx % step
            while (x < size.width) {
                drawLine(gridLine, Offset(x, 0f), Offset(x, size.height), strokeWidth = hairline)
                x += step
            }
            var y = cy % step
            while (y < size.height) {
                drawLine(gridLine, Offset(0f, y), Offset(size.width, y), strokeWidth = hairline)
                y += step
            }
            // The comp's two axes, one step brighter than the grid they cross.
            val axisLine = Color.White.copy(alpha = 0.035f)
            drawLine(axisLine, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = hairline)
            drawLine(axisLine, Offset(0f, cy), Offset(size.width, cy), strokeWidth = hairline)

            listOf(0.88f, 0.56f, 0.25f).forEach { fraction ->
                drawCircle(
                    ringLine,
                    radius = unit * fraction,
                    center = Offset(cx, cy),
                    style = Stroke(hairline),
                )
            }

            if (sweeping) {
                rotate(sweepAngle.value, pivot = Offset(cx, cy)) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            // The comp's four stops, and the third is the one that matters: without
                            // a hard transparent at 62% the wedge never ends, so what should be a
                            // sweeping arm is a disc with a bright leading edge. Measured on the
                            // emulator 2026-08-20 before the stop was added.
                            0f to c.jade.copy(alpha = 0.20f),
                            0.42f to c.jade.copy(alpha = 0.03f),
                            0.62f to Color.Transparent,
                            1f to Color.Transparent,
                            center = Offset(cx, cy),
                        ),
                        radius = unit * 0.88f,
                        center = Offset(cx, cy),
                    )
                }
                drawCircle(
                    c.jade.copy(alpha = echoAlpha(echo.value)),
                    radius = unit * (0.32f + 0.56f * echo.value),
                    center = Offset(cx, cy),
                    style = Stroke(hairline),
                )
            }

            val spreadPx = spread.toPx()
            val pointRadius = 2.5.dp.toPx()
            CLOUD_SEEDS.forEach { (ux, uy) ->
                drawCircle(
                    pointColor.copy(alpha = pointAlpha),
                    radius = pointRadius,
                    center = Offset(cx + ux * spreadPx, cy + uy * spreadPx * 0.62f),
                )
            }

            if (settlePulse.value < 1f) {
                drawCircle(
                    markerColor.copy(alpha = 0.55f * (1f - settlePulse.value)),
                    radius = unit * (0.24f + 0.28f * settlePulse.value),
                    center = Offset(cx, cy),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }

        // The state, in the panel's own corner. It is the comp's one piece of new text and the
        // reason the field can be read at a glance: the sentence underneath says the same thing in
        // full, but it is 11sp grey prose four lines down, and while a capture is running this is
        // what the eye is already looking at.
        // The chip leaves rather than vanishing. It used to disappear on the frame the capture
        // ended — the one moment the screen is meant to feel finished, and the exit was a cut.
        // 180 out / 300 in is the 60% ratio `quick-reference.md` §7 asks for, on tokens that
        // already exist.
        AnimatedVisibility(
            visible = phase == RadarPhase.SEARCHING ||
                phase == RadarPhase.REFINING ||
                phase == RadarPhase.SETTLED,
            enter = fadeIn(lkTween(LkMotion.Crossfade)),
            exit = fadeOut(lkTween(LkMotion.Quick)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            val chipColor = when (phase) {
                RadarPhase.REFINING -> c.amber
                RadarPhase.SETTLED -> c.jade
                else -> c.textMuted
            }
            val chipText = stringResource(
                when (phase) {
                    RadarPhase.REFINING -> R.string.itemedit_coordinate_chip_refining
                    RadarPhase.SETTLED -> R.string.itemedit_coordinate_chip_settled
                    else -> R.string.itemedit_coordinate_chip_searching
                },
            )
            Row(
                Modifier
                    .padding(14.dp)
                    .lkSurface(LkShape.sm, c.ground.copy(alpha = 0.6f), c.ring)
                    .padding(horizontal = 11.dp, vertical = 6.dp)
                    // The sentence below is a live region saying the same state in full words; a
                    // screen reader announcing both says everything twice.
                    .decorative(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                PhasePulseDot(
                    color = chipColor,
                    running = radarPulseRuns(phase, reduceMotion),
                )
                Text(chipText.uppercase(), style = LkText.fieldLabel, color = chipColor)
            }
        }

        // The opposite corner is deliberately empty. It held a metre figure ("≈ 20 m") until
        // 2026-09-03, and that readout existed only to show the number — with the number gone
        // (AccuracyBand explains why) the corner would have repeated the band word the live line
        // below already carries, which is noise rather than redundancy: it was decorative, so it
        // never spoke to a screen reader, and the one state it could add was one the sentence had
        // already said in full words.

        when {
            // The marker is LkIcons.Pin everywhere this feature uses one — the field, the capture
            // button and the disclosure dialog — rather than a bespoke filled variant, so the
            // vocabulary stays one glyph with one meaning.
            hasMarker -> Icon(
                LkIcons.Pin,
                contentDescription = null,
                tint = markerColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    // The icon's own tip sits at the bottom of its box; offsetting up by half of it
                    // puts that tip, not the icon's centre, on the point being marked.
                    .offset(y = (-13).dp)
                    .graphicsLayer {
                        // Alpha is clamped because [LkMotion.Overshoot] deliberately goes past 1,
                        // and an alpha above 1 is not a brighter pin, it is undefined.
                        alpha = pinDrop.value.coerceIn(0f, 1f)
                        translationY = (1f - pinDrop.value) * -18.dp.toPx()
                        // .7 to 1, so the overshoot is a landing rather than a slide: the comp's
                        // `lcPinDrop` scales the same way over the same 620ms.
                        val scale = 0.7f + 0.3f * pinDrop.value
                        scaleX = scale
                        scaleY = scale
                    },
            )
            phase == RadarPhase.IDLE -> Icon(
                LkIcons.Pin,
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = 0.3f },
            )
        }
    }
}

/**
 * The strip under the panel while a capture runs: the elapsed ring, the live sentence, and — once
 * a capture has settled somewhere loose — why.
 *
 * Moved here from `ItemEditScreen` on 2026-09-02 when the live line was rewritten, so the whole
 * of what the capture says about itself lives in one file with the panel that draws it.
 *
 * The ring is *determinate* — elapsed over the same `FixRequest` timeout the running capture is
 * enforcing — which is the fix for the only real defect the first device run found: a search that
 * runs the full window indoors with nothing moving reads as a dead button. Polled rather than
 * animated, so the value is measured elapsed time and not a curve that happens to end at the same
 * place, and unanimated for the reason `LkIndicators.kt` gives for the KDF ring.
 *
 * The sentence is now the *only* place the reading is described at all, since the panel's corner
 * figure went with the metres on 2026-09-03. It is a live region, so a screen reader hears each
 * change exactly once — and the change is worth hearing: the band word visibly improves as the
 * fix converges (approximate → street level → precise), which is what carries "the wait is buying
 * you something" without printing a number. That progression is the whole reason there are three
 * bands and not two; with two, an indoor capture would sit on one word for its entire run and the
 * line would say nothing, which is the "no feedback during a wait" failure the old
 * "Still getting closer…" line was reported for.
 *
 * Never a colour alone: [CaptureReadout.Settled] is jade *and* says it is ready to save, so the
 * completion survives for anyone who does not see the hue.
 */
@Composable
internal fun LocationCaptureStatus(
    startedAtElapsedMillis: Long?,
    timeoutMillis: Long,
    fix: Coordinate?,
    convergence: CaptureConvergence,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    var elapsedFraction by remember(startedAtElapsedMillis) { mutableFloatStateOf(0f) }
    LaunchedEffect(startedAtElapsedMillis) {
        if (startedAtElapsedMillis == null) return@LaunchedEffect
        val timeout = timeoutMillis.coerceAtLeast(1L)
        while (true) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAtElapsedMillis
            elapsedFraction = (elapsed.toFloat() / timeout).coerceIn(0f, 1f)
            if (elapsedFraction >= 1f) break
            delay(200)
        }
    }

    val readout = captureReadoutFor(fix, convergence)
    // One whole sentence per state, resolved by state — never a band word formatted into a
    // template. The word is the subject of the clause after it, so the four target languages need
    // the rest of the sentence to agree with it; a shared template would force an agreement that
    // does not hold. strings.xml carries the per-language reasoning.
    val live = stringResource(
        when (readout) {
            CaptureReadout.Searching -> R.string.itemedit_coordinate_searching
            CaptureReadout.NetworkOnly -> R.string.itemedit_coordinate_network_only
            is CaptureReadout.Improving -> when (readout.band) {
                AccuracyBand.PRECISE -> R.string.itemedit_coordinate_refining_precise
                AccuracyBand.STREET -> R.string.itemedit_coordinate_refining_street
                AccuracyBand.AREA -> R.string.itemedit_coordinate_refining_area
                null -> R.string.itemedit_coordinate_refining
            }
            is CaptureReadout.Settled -> when (readout.band) {
                AccuracyBand.PRECISE -> R.string.itemedit_coordinate_settled_precise
                AccuracyBand.STREET -> R.string.itemedit_coordinate_settled_street
                AccuracyBand.AREA -> R.string.itemedit_coordinate_settled_area
                null -> R.string.itemedit_coordinate_settled
            }
        },
    )
    // Jade once it is finished, muted while it is not — the chip in the panel makes the same move,
    // and the sentence itself changes, so the colour is a second cue rather than the only one.
    // Both clear AA on this surface (jade 10.6:1, muted 6.29:1 against LkColors.surface).
    val liveColor by animateColorAsState(
        if (readout is CaptureReadout.Settled) c.jade else c.textMuted,
        lkTween(LkMotion.Crossfade),
        label = "capture-live-color",
    )

    // Wrapped, because this emits up to three children and its caller arranges with spacedBy: a
    // bare Spacer in a spacedBy Column is spaced too, and the repo has been bitten by that.
    Column(modifier) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            LkProgressRing(
                progress = elapsedFraction,
                size = 20.dp,
                strokeWidth = 2.dp,
                animated = false,
            )
            Text(
                live,
                style = LkText.caption,
                color = liveColor,
                modifier = Modifier.weight(1f).announce(live),
            )
        }
        // Why it stopped — and only once it has stopped somewhere loose. See
        // indoorExplanationApplies() for why it used to be wrong on every outdoor capture.
        if (indoorExplanationApplies(convergence, fix)) {
            Spacer(Modifier.height(LkSpace.sm))
            Text(
                stringResource(R.string.itemedit_coordinate_indoor_hint),
                style = LkText.caption,
                color = c.textFaint,
            )
        }
    }
}

/** One turn of the sweep and one echo, the comp's `lcSweep 2.6s` / `lcEcho 2.6s`. */
private const val SweepPeriodMillis = 2_600

/**
 * The echo ring's alpha across one turn: nothing, up to a peak at 30%, back to nothing.
 *
 * The shipped envelope was `0.20 * (1 - progress)`, which is 0.20 at progress 0 — so every restart
 * snapped a ring into existence at full strength in the middle of the panel, once every 2.6 s. That
 * step, on top of a wedge sweeping across nine dots at a different period, is what read as *"a weird
 * sparkling that's happening randomly"*: two loops of 2.6 s and 3.6 s beating against each other,
 * neither of them starting from zero.
 *
 * This is the comp's own `lcEcho` shape — `0%{opacity:0} 30%{opacity:.5} 100%{opacity:0}` — so the
 * loop has no seam anywhere. Extracted rather than inlined because a curve with no discontinuity is
 * exactly the kind of claim that should be checkable by a test rather than by eye.
 */
internal fun echoAlpha(progress: Float): Float =
    if (progress < 0.30f) 0.10f * (progress / 0.30f) else 0.10f * (1f - (progress - 0.30f) / 0.70f)

/**
 * Does the chip's pulse run in this phase?
 *
 * SEARCHING and REFINING, and nothing else. Both mean an open GNSS fix stream — a wait the user did
 * not choose and cannot shorten, bounded by `FixRequest().timeoutMillis` — which is precisely what
 * `ux-guidelines.csv`'s Continuous Animation rule permits a loop for. REFINING matters as much as
 * SEARCHING here and used to have nothing at all: the sweep stopped at the first fix, so the panel
 * went completely still while the live line underneath still said "Still getting closer…". A screen
 * that stops moving is this app's way of saying the work is done, and it was saying it early.
 *
 * SETTLED stops, which is the comp's own note: *"Then everything stops moving, which is itself the
 * message."* Reduced motion stops too — the phase is carried by the chip's word and its colour, and
 * by the live sentence below, so nothing is lost.
 */
internal fun radarPulseRuns(phase: RadarPhase, reduceMotion: Boolean): Boolean =
    !reduceMotion && (phase == RadarPhase.SEARCHING || phase == RadarPhase.REFINING)

/**
 * The chip's status dot: a core that breathes, and a wave that radiates out of it and fades.
 *
 * The comp's `lcBreathe` is opacity .5 → .9 on a 5 px dot, and transcribing it exactly produced
 * something nobody could see — a 40% opacity swing on four millimetres of screen, reported as
 * *"refining is not pulsating"*. Worse, it ran on [LkMotion.Standard], a front-loaded curve: a fifth
 * of the way through it had already travelled 68% of its range, so the dot flicked and then sat
 * still. That is a blink, not a breath. [LkMotion.Breathe] is symmetric, which is the whole
 * difference.
 *
 * The wave is what answers *"this gentle pulse radiates a nice and smooth wave matching the color of
 * either Searching or Refining"*. Its geometry is the app's own halo (`LkPulseHalo`), not a new
 * idea, and its alpha envelope is zero at both ends so the restart has no seam. Both animations run
 * at [LkMotion.Breath], so there is exactly one wave per breath, for ever — no beat between two
 * periods, which is the other half of the sparkle.
 *
 * The canvas is fixed at the largest the wave gets, so nothing reflows as it pulses.
 */
@Composable
private fun PhasePulseDot(color: Color, running: Boolean) {
    val breath = remember { Animatable(1f) }
    val wave = remember { Animatable(0f) }
    val waveSpec = lkTween<Float>(LkMotion.Breath, easing = LkMotion.Standard)
    val halfBreath = lkTween<Float>(LkMotion.Breath / 2, easing = LkMotion.Breathe)
    LaunchedEffect(running) {
        if (!running) {
            // Not mid-animation: a stopped indicator has to look deliberate, so the core sits at
            // full strength and the wave is gone entirely.
            wave.snapTo(0f)
            breath.snapTo(1f)
            return@LaunchedEffect
        }
        launch {
            while (true) {
                wave.snapTo(0f)
                wave.animateTo(1f, waveSpec)
            }
        }
        while (true) {
            breath.animateTo(1f, halfBreath)
            breath.animateTo(0f, halfBreath)
        }
    }
    Canvas(Modifier.size(PulseDotCanvas)) {
        val core = PulseDotCore.toPx()
        if (running) {
            val w = wave.value
            // Zero at both ends, peaking a sixth of the way in — the comp's `lcEcho` shape.
            val alpha = 0.55f * (1f - w) * min(1f, w / 0.15f)
            drawCircle(
                color.copy(alpha = alpha),
                radius = core + (PulseDotWave.toPx() - core) * w,
                center = center,
                style = Stroke(1.dp.toPx()),
            )
        }
        // The core never drops below 0.72: the colour is what carries the phase, and a dot fading
        // toward invisible would be dimming the one cue that says which phase it is.
        drawCircle(
            color.copy(alpha = if (running) 0.72f + 0.28f * breath.value else 1f),
            radius = core,
            center = center,
        )
    }
}

/** Sized for the wave at full extent, so the chip's layout never moves. */
private val PulseDotCanvas = 20.dp

/** The comp's 5 px dot. */
private val PulseDotCore = 2.5.dp

/** How far the wave travels before it is gone. */
private val PulseDotWave = 10.dp

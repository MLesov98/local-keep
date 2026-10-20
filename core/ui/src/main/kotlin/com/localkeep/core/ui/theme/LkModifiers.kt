package com.localkeep.core.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The 1px inset hairline that separates a raised surface from the ground.
 *
 * Every raised element in this design carries one. It is what lets surfaces only 4–8% lighter than
 * the background still read as distinct objects — without it, a dark-on-dark card either has to be
 * lightened until it looks grey, or it dissolves.
 */
fun Modifier.lkRing(
    shape: Shape,
    color: Color,
    width: Dp = 1.dp,
): Modifier = border(width, color, shape)

/** A raised surface: flat fill, hairline ring, clipped to [shape]. */
fun Modifier.lkSurface(
    shape: Shape,
    color: Color,
    ring: Color,
): Modifier = background(color, shape).lkRing(shape, ring)

/** A raised surface with the lifted-card gradient rather than a flat fill. */
fun Modifier.lkSurface(
    shape: Shape,
    brush: Brush,
    ring: Color,
): Modifier = background(brush, shape).lkRing(shape, ring)

/**
 * The height of the aura band.
 *
 * A fixed dp value, deliberately not a fraction of the screen. The wash used to be a circle whose
 * radius and origin were derived from `size.height`, so it grew with the device: the same screen
 * showed a tight halo on a compact phone and a wash reaching past the fold on a tall one. The design
 * treats it as a band of light at the top of the screen, and a band has a height.
 */
private val AuraBandHeight = 280.dp

/**
 * The ambient glow behind a screen header — a wide, very low-alpha radial wash of an accent.
 *
 * It does almost nothing on its own and that is the point: it is what stops a full-bleed near-black
 * screen from looking switched-off, without introducing a visible band the eye can catch on.
 *
 * The shape is a wide, shallow ellipse anchored to the top edge — 84% of the screen's width by
 * [AuraBandHeight], fading out at roughly two-thirds of that. Compose has no elliptical gradient
 * brush, so it is a circular one drawn inside a non-uniform `scale`.
 */
fun Modifier.lkAuraTop(
    accent: Color,
    alpha: Float = 0.09f,
    /**
     * Horizontal position of the light source, 0f left … 1f right.
     *
     * Centred by default. `Item Flows.dc.html` grounds every phone frame in a dome centred at 50%
     * (`radial-gradient(120% 90% at 50% 38%, …)`), and the old 0.22 default — kept from before the
     * screens were compared against it side by side — read as accidental vignetting next to that:
     * an off-centre wash on a symmetric form looks like a rendering artefact, not a light source.
     */
    horizontalBias: Float = 0.5f,
): Modifier = drawAura(accent, alpha, horizontalBias)

private fun Modifier.drawAura(accent: Color, alpha: Float, horizontalBias: Float): Modifier =
    composed {
        val reduce = LocalLkReduceMotion.current
        // The mockup drifts this slowly; when motion is reduced it simply sits still. Kept
        // deliberately across the geometry change — this was ruled ambient rather
        // than attention motion, and the still branch is what makes that hold.
        val drift by if (reduce) {
            remember { mutableStateOf(0f) }
        } else {
            rememberSlowDrift()
        }
        this.then(
            Modifier.drawBehindAura(accent, alpha, horizontalBias, drift),
        )
    }

private fun Modifier.drawBehindAura(
    accent: Color,
    alpha: Float,
    horizontalBias: Float,
    drift: Float,
) = drawBehind {
    val bandPx = AuraBandHeight.toPx()
    val radiusX = size.width * 0.84f * (1f + drift * 0.06f)
    val radiusY = bandPx * (1f + drift * 0.06f)
    // Anchored just above the top edge, so the band reads as light entering the screen rather than
    // as a shape sitting on it.
    val origin = Offset(size.width * horizontalBias, -bandPx * (0.08f + drift * 0.03f))
    withTransform({ scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = origin) }) {
        drawCircle(
            brush = Brush.radialGradient(
                // Transparent at 64% of the way out, as the design has it — the remaining third
                // exists only so the falloff is complete before the gradient's own edge.
                0f to accent.copy(alpha = alpha),
                0.64f to Color.Transparent,
                1f to Color.Transparent,
                center = origin,
                radius = radiusX,
            ),
            radius = radiusX,
            center = origin,
        )
    }
}

/** Half a breath: 0→1 takes this long, and 1→0 the same again. */
private const val DriftHalfCycleMillis = 7_000L

/**
 * How often the drift is resampled.
 *
 * It used to be sampled per frame, because `animateFloatAsState` reads the frame clock: an aura on
 * an otherwise static screen therefore redrew at the display's full rate for as long as that screen
 * was up — 289 frames in 5 s on an idle API 28 unlock screen, not one of which showed anything the
 * frame before it had not. One tick moves the drift by at most 0.058 of its range — measured, at
 * the steepest point of [LkMotion.Standard] — and the drift only scales the wash's radius by 6% and
 * its origin by 3%, so the largest step this can produce is a third of a percent of a 280dp band at
 * 9% alpha. Ten samples a second paint the same picture and leave the device idle in between.
 */
private const val DriftTickMillis = 100L

/**
 * The drift value [elapsedMs] into the cycle.
 *
 * Reproduces what the two-step `animateFloatAsState` produced, so the visual is unchanged: a
 * [LkMotion.Standard]-eased ramp to 1 over the first half and the same curve read back down over
 * the second. The fall is `1 - easing(t)` and not `easing(1 - t)` because a tween eases the
 * *interpolation* between its endpoints, not the time it is given.
 */
internal fun auraDriftAt(elapsedMs: Long): Float {
    val phase = elapsedMs % (DriftHalfCycleMillis * 2)
    val eased = LkMotion.Standard.transform(
        (phase % DriftHalfCycleMillis) / DriftHalfCycleMillis.toFloat(),
    )
    return if (phase < DriftHalfCycleMillis) eased else 1f - eased
}

/**
 * Emits the drift on the [DriftTickMillis] tick, forever.
 *
 * Split out of [rememberSlowDrift] because it is the only place the rate is observable: under
 * Robolectric the effect coroutine a Compose test rule installs does not run its `delay`s off the
 * test clock at all — measured 2026-09-17, a `LaunchedEffect { delay(100) }` never resumes across
 * `mainClock.advanceTimeBy(1_000)` — so the composable itself cannot be timed.
 */
internal suspend fun driveAuraDrift(onDrift: (Float) -> Unit) {
    var elapsedMs = 0L
    while (true) {
        kotlinx.coroutines.delay(DriftTickMillis)
        elapsedMs += DriftTickMillis
        onDrift(auraDriftAt(elapsedMs))
    }
}

/** A 0→1→0 eased ramp over 14 seconds, driving the aura's slow breathing. See [auraDriftAt]. */
@Composable
internal fun rememberSlowDrift(): androidx.compose.runtime.State<Float> =
    androidx.compose.runtime.produceState(0f) { driveAuraDrift { value = it } }

/**
 * Shrinks slightly while pressed, and — given a [shape] — washes lighter underneath.
 *
 * Applied to every tappable surface in the app. On a screen with no ripple-friendly light
 * background, scale is the press affordance that survives — a ripple over `#0E1214` is nearly
 * invisible.
 *
 * **Why the wash was added (2026-07-29).** Scale alone was doing the whole job at 0.978 on the two
 * main buttons: on a 56dp-tall control that is 1.2dp of travel, which is under a pixel of movement
 * at the near-black contrast this palette runs at. The app read as unresponsive to touch — the
 * complaint was "dull" — and the cause was not silence, it was an affordance too small to perceive.
 * A wash of [LkColors.washStrong] over the control's own outline is the ripple's job done in a way
 * that survives a `#0E1214` ground.
 *
 * **The wash deliberately ignores reduced motion.** A colour change on press is a *state*
 * indicator, not motion — and it is precisely the user who has turned animations off, and therefore
 * gets no scale at all, who would otherwise be left with no press feedback whatsoever. Scale still
 * collapses to nothing under that preference, exactly as before.
 *
 * [shape] must match the shape the control paints itself with, since the wash is drawn to that
 * outline. Passing null keeps the old scale-only behaviour, which is right for a borderless control
 * that has no plate to tint.
 */
@Composable
fun Modifier.lkPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.96f,
    shape: Shape? = null,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduce = LocalLkReduceMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) pressedScale else 1f,
        animationSpec = lkTween(LkMotion.Instant),
        label = "press-scale",
    )
    val scaled = this.scale(scale)
    return if (shape == null) scaled else scaled.lkPressWash(interactionSource, shape)
}

/**
 * The press wash on its own, for controls that must not move.
 *
 * A full-bleed row inside a grouped card is the case: shrinking it would pull its edges away from
 * the card's and expose the ground through the gap, so the tappable rows in Settings, Backup and the
 * drawer had no press affordance at all — the single largest contributor to the app feeling inert,
 * since those rows are most of what there is to tap outside the vault list.
 *
 * See [lkPressScale] for why this ignores the reduced-motion preference.
 */
@Composable
fun Modifier.lkPressWash(
    interactionSource: InteractionSource,
    shape: Shape,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val wash by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = lkTween(LkMotion.Instant),
        label = "press-wash",
    )
    val washColor = LkTheme.colors.washStrong
    return this.drawWithContent {
        drawContent()
        if (wash <= 0.01f) return@drawWithContent
        drawOutline(
            outline = shape.createOutline(size, layoutDirection, this),
            color = washColor.copy(alpha = washColor.alpha * wash),
        )
    }
}

/**
 * The keyboard/switch focus indicator (WCAG 2.4.7 Focus Visible, and 1.4.11 for its contrast).
 *
 * The app had none. On a touchscreen that is invisible, which is exactly why it survived a design
 * pass and a launch: nothing about driving the app with a finger reveals that a user on a Bluetooth
 * keyboard, a switch, or a D-pad has no idea where they are. The hairline rings this design leans on
 * are ~1.2:1 against their own background — beautiful, and useless as a position indicator.
 *
 * So this is deliberately loud: full-strength [LkColors.jade] at 2dp, which measures 10.9:1 on the
 * ground and 9.7:1 on the dialog surface — comfortably past the 3:1 that 1.4.11 asks of a focus
 * indicator, with enough headroom that it stays legible on every surface in the palette.
 *
 * Drawn just inside the component's own [shape] rather than outside it, because a ring drawn beyond
 * the bounds is clipped the moment the control sits in a scrolling list or a card.
 *
 * Place this *before* `.clickable`/`.focusable` in the chain — [onFocusChanged] reports on focus
 * modifiers that follow it.
 *
 * The fade honours reduced motion through [lkTween]; the ring itself never animates away to nothing
 * on its own, so a user who disabled animations still gets an indicator, just instantly.
 */
@Composable
fun Modifier.lkFocusRing(
    shape: Shape,
    accent: Color = LkTheme.colors.jade,
    width: Dp = 2.dp,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val strength by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = lkTween(LkMotion.Instant),
        label = "focus-ring",
    )
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (strength <= 0.01f) return@drawWithContent
            val stroke = width.toPx()
            // Inset by half the stroke so the ring lands ON the boundary rather than straddling it
            // and getting its outer half clipped.
            inset(stroke / 2f) {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this@drawWithContent),
                    color = accent.copy(alpha = strength),
                    style = Stroke(width = stroke),
                )
            }
        }
}

/**
 * The shared entrance: fade up from 10dp with a hair of scale.
 *
 * Used for whole screens and for individual blocks with a [delayMillis] stagger. One entrance for
 * everything is deliberate — the app should feel like it has a single way of arriving.
 *
 * **Never call this inside an `if`/`else` that can flip while the node stays on screen.** This is a
 * plain `@Composable` extension, not a `composed {}` factory, so [shown] and its [LaunchedEffect]
 * live in the *caller's* composition at the position of the call. A call site that swaps between
 * `Modifier` and `Modifier.lkEnter(...)` composes a new group when it flips, which re-remembers
 * `shown` as `false` — and a node that is already on screen restarts the entrance from alpha 0,
 * ten dp low.
 *
 * That is not hypothetical: the vault list did exactly this. A just-saved row skipped the entrance
 * while its jade reveal wash was up, then `clearReveal()` fired 1.4 s later, the call site fell to
 * the `lkEnter` branch for the first time, and the row the user had just created blinked out and
 * jumped. With the monogram's glow riding on that row it read as the tile coming loose — reported
 * from hands-on use, 2026-08-11: "I created an entry and the folder glitches, it's like it tries
 * to escape ... since we introduced the sparkling around it."
 *
 * [enabled] is how a caller says "this one arrives without an entrance" *without* moving the call:
 * false at first composition means the node starts fully shown and no animation ever runs. Turning
 * it true later does nothing, deliberately — a node cannot arrive twice.
 */
@Composable
fun Modifier.lkEnter(
    delayMillis: Int = 0,
    travel: Dp = 10.dp,
    withScale: Boolean = false,
    enabled: Boolean = true,
): Modifier {
    // Seeded from `enabled` and then left alone: `animateFloatAsState` starts *at* its first target,
    // so seeding it true is a still frame at full opacity rather than an instant animation.
    var shown by rememberSaveable { mutableStateOf(!enabled) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = lkTween(LkMotion.Screen, delayMillis, LkMotion.Standard),
        label = "enter",
    )
    val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) { travel.toPx() }
    return this
        .graphicsLayer {
            translationY = (1f - progress) * travelPx
            if (withScale) {
                val s = 0.985f + 0.015f * progress
                scaleX = s
                scaleY = s
            }
        }
}

/**
 * The design comps' `ifRise` / `idRise`: fade *and* lift, on [LkMotion.Settle].
 *
 * Two things separate it from [lkEnter], and both are why it is not a parameter on that one:
 *
 * - **It animates alpha.** [lkEnter] moves and scales but never fades, because it entered the app
 *   as a screen-level entrance where the whole surface is already opaque. A block appearing inside
 *   a screen that is already on screen has to fade, or it reads as a jump.
 * - **It settles harder and faster** — [LkMotion.Settling] on [LkMotion.Settle], against 460ms on
 *   `Standard`. That is the difference between a panel that *lands* under the list and one that
 *   slides in behind it.
 *
 * Every caveat in [lkEnter]'s documentation applies here unchanged, and the first one is not
 * theoretical: this is a plain `@Composable` extension, so **never call it inside an `if`/`else`
 * that can flip while the node stays on screen**, or the node restarts its entrance from alpha 0.
 */
@Composable
fun Modifier.lkRise(
    delayMillis: Int = 0,
    travel: Dp = 12.dp,
    durationMillis: Int = LkMotion.Settling,
): Modifier {
    // Seeded false and flipped in an effect, never seeded true: `animateFloatAsState` starts *at*
    // its first target, so a constant 1f is a still frame at rest rather than an entrance. That
    // trap has now shipped twice in this repo — see the seal ring in LkSavePayoff.
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = lkTween(durationMillis, delayMillis, LkMotion.Settle),
        label = "rise",
    )
    val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) { travel.toPx() }
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * travelPx
    }
}

/**
 * "This is the thing you just made": a row sliding into the place it will keep, then one jade
 * flash that decays and never repeats.
 *
 * One modifier rather than two behaviours because the design comps insist on one vocabulary for it.
 * `Item Flows.dc.html`, on the file flow: *"Same jade flash as a new folder row, decaying. Two
 * flows, one vocabulary for 'this is the thing you just made'."* A newly created folder and a
 * freshly encrypted attachment are the same event to the user, and they should not have to learn
 * it twice.
 *
 * The flash fires **once**, on the composition where [arrived] first turns true, and then stops.
 * The comp is explicit about why: *"It never repeats — a row that keeps pulsing reads as
 * unfinished."* That also rules out an `InfiniteTransition` here, and keeps this inside
 * this app's ban on looping attention animations.
 *
 * Under reduced motion the row is simply already in place with no flash, which is the whole of what
 * the comp asks for in that mode.
 *
 * [shape] must be the row's own shape. A rounded row with a square flash behind it is worse than no
 * flash — the corners light up outside the row they belong to.
 */
@Composable
fun Modifier.lkArrival(
    arrived: Boolean,
    shape: Shape,
    travel: Dp = 10.dp,
    /**
     * The row's own identity — a folder id, an attachment id.
     *
     * Required whenever this is applied inside a list that can reorder, which is both of its
     * callers. A `remember` in a `forEach` body is keyed by *position*, and the list a new row
     * joins is re-sorted by the time it appears: without this the latch below belongs to whatever
     * row now occupies that slot, so the flash lands on the wrong row or on none.
     */
    key: Any? = Unit,
): Modifier {
    val reduce = LocalLkReduceMotion.current
    val flashColor = LkTheme.colors.jade
    // Latched, not derived: `arrived` is typically "this row is the newest one", which the caller
    // clears on a timer or on the next change. Latching means a recomposition for an unrelated
    // reason cannot replay the entrance, and a caller that never clears the flag still gets exactly
    // one flash.
    var played by remember(key) { mutableStateOf(false) }
    var running by remember(key) { mutableStateOf(false) }
    LaunchedEffect(arrived) {
        if (arrived && !played) {
            played = true
            running = true
        }
    }
    val slide by animateFloatAsState(
        targetValue = if (running || !arrived) 1f else 0f,
        animationSpec = lkTween(LkMotion.Row, easing = LkMotion.Settle),
        label = "arrival-slide",
    )
    // Runs 1 → 0, and it must *start* at 1: `animateFloatAsState` begins at its first target, so
    // the initial `running == false` frame seeds a full tint that then decays. Reading this the
    // other way round — a 0 → 1 value and `1f - it` at the draw — leaves the row permanently jade,
    // which is the opposite of a flash and is invisible in a screenshot taken a second later.
    val tint by animateFloatAsState(
        targetValue = if (running) 0f else 1f,
        animationSpec = lkTween(LkMotion.Flash, delayMillis = 260, easing = LkMotion.Standard),
        label = "arrival-flash",
    )
    if (!arrived || reduce) return this
    val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) { travel.toPx() }
    return this
        .graphicsLayer { translationY = -(1f - slide) * travelPx }
        .drawBehind {
            // 0.16 is the comp's `ifFlash` starting alpha.
            val alpha = 0.16f * tint
            if (alpha > 0.001f) {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = flashColor.copy(alpha = alpha),
                )
            }
        }
}

/**
 * A one-shot jade bloom behind the content — the app's "that worked" flash for something the user
 * did *to* a value rather than to a row.
 *
 * Behind, never over. The comp is specific about it for the generator's Copy: *"Behind the value,
 * not over it — the string stays readable while the confirmation happens."* A password being copied
 * is usually being read at the same moment, and a confirmation that obscures what it is confirming
 * is worse than none.
 *
 * [token] is a counter, not a boolean, so two copies in a row both bloom. A boolean that is already
 * true cannot fire again, and "I tapped Copy and nothing happened" is exactly the doubt this exists
 * to remove.
 *
 * Skipped entirely under reduced motion: it is decoration over a confirmation the label already
 * carries in words.
 */
@Composable
fun Modifier.lkBloom(
    token: Int,
    alignment: Offset = Offset(0.1f, 0f),
): Modifier {
    val reduce = LocalLkReduceMotion.current
    val bloomColor = LkTheme.colors.jade
    // An `Animatable`, not `animateFloatAsState`, and the difference is the whole behaviour.
    // `animateFloatAsState` only ever *animates toward* a target: to replay, you would set 0 and
    // then 1 again, and the second assignment retargets the in-flight animation from wherever it
    // has reached — roughly 1.0 — so the bloom never plays a second time. `snapTo` puts the value
    // back to 0 in one frame with no animation at all, which is the only way to restart one.
    val progress = remember { Animatable(1f) }
    // Resolved in the composition, not inside the effect: `lkTween` is a `@Composable`.
    val spec = lkTween<Float>(LkMotion.Bloom, easing = LkMotion.Settle)
    LaunchedEffect(token) {
        if (token == 0 || reduce) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
    val animated = progress.value
    if (reduce || animated >= 1f || animated <= 0f) return this
    return this.drawBehind {
        // opacity 0 → .55 at 55% of the way → 0, against a scale of .5 → 1.9. The comp's ifBloom.
        val fade = if (animated < 0.55f) animated / 0.55f else (1f - animated) / 0.45f
        val scale = 0.5f + 1.4f * animated
        val radius = size.minDimension * 0.75f * scale
        drawCircle(
            brush = Brush.radialGradient(
                0f to bloomColor.copy(alpha = 0.30f * fade),
                0.66f to Color.Transparent,
                center = Offset(size.width * alignment.x, size.height * alignment.y),
                radius = radius,
            ),
            radius = radius,
            center = Offset(size.width * alignment.x, size.height * alignment.y),
        )
    }
}

/**
 * The fold, made visible: a soft edge wherever a scroll region has more content past its border.
 *
 * The failure this fixes is never that the user cannot scroll. It is that nothing says there is
 * anything to scroll to — content is cut off flush against fixed chrome, which reads as the end of
 * the content rather than as content running underneath something. `LkSheetSurface` has drawn this
 * exact edge since a sheet's last row was measured being cut in half by the display edge; three
 * whole screens were left without it, and the personas found all three the same way. Onboarding's
 * Recovery Kit step ended at "Save as PDF" flush above the pinned Continue bar, with Print and
 * "Show QR code instead" — two of the three ways to save the kit — existing nowhere in the
 * accessibility tree until a blind upward swipe; at `font_scale 2.0` the QR mode's own confirm
 * button was a fourteen-pixel unlabeled strip against the same edge. On Password health in
 * landscape the five category rows scrolled past unseen between one swipe and the next.
 *
 * Drawn only on the side that has more, so a region whose content fits is pixel-identical to
 * before. Fading toward [color] rather than masking the content out: every caller sits directly on
 * `LkScreen`'s ground, and a gradient into that ground is what makes the content look like it
 * continues under the chrome instead of dissolving on its own.
 *
 * Goes *outside* `verticalScroll` in the modifier chain — the fade belongs to the viewport, which
 * stays put, not to the content, which moves.
 */
fun Modifier.lkScrollEdges(
    moreAbove: Boolean,
    moreBelow: Boolean,
    color: Color,
    height: Dp = 28.dp,
): Modifier = this.drawWithContent {
    drawContent()
    // Halved at most, so a viewport shorter than two fades cannot be painted over entirely. A
    // region that small is already the defect this is a symptom of, and a solid wash across it
    // would hide the little that did fit.
    val fade = height.toPx().coerceAtMost(size.height / 2f)
    if (fade <= 0f) return@drawWithContent
    if (moreAbove) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(color, Color.Transparent),
                startY = 0f,
                endY = fade,
            ),
            size = Size(size.width, fade),
        )
    }
    if (moreBelow) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, color),
                startY = size.height - fade,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - fade),
            size = Size(size.width, fade),
        )
    }
}

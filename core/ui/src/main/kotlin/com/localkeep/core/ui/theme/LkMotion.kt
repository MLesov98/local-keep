package com.localkeep.core.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The motion vocabulary: eight curves and a handful of durations, shared by every screen.
 *
 * Having exactly this many is the point. A design reads as one system when everything decelerates
 * the same way; it reads as a pile of separate screens when each one picks its own tween. These
 * are transcribed from the mockup's CSS `cubic-bezier` values.
 */
object LkMotion {
    /** The default. Fast out, long settle — used for anything entering or changing in place. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    /** Slight overshoot at the top. For things that *appear*: dialogs, chips, badges. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

    /** A bottom sheet's rise — overshoots ~1% past its rest position, then settles. */
    val Sheet: Easing = CubicBezierEasing(0.22f, 0.92f, 0.26f, 1f)

    /** A drawer's slide, marginally slower to settle than [Sheet] because it travels further. */
    val Drawer: Easing = CubicBezierEasing(0.22f, 0.9f, 0.24f, 1f)

    /** Progress that represents real work must be linear — easing it would be a lie. */
    val Progress: Easing = LinearEasing

    /**
     * A hard decelerate with a long tail — the curve every *arrival* in the design comps uses.
     *
     * Distinct from [Standard], and deliberately not folded into it. `cubic-bezier(.16,1,.3,1)`
     * reaches 90% of its travel in the first third of its duration and spends the rest settling;
     * `Standard` at `(.2,.8,.2,1)` is gentler at the front and arrives sooner. Side by side the
     * difference is a row that *lands* versus one that *slides in*, which is the whole character of
     * the item flows. Both are needed: `Standard` still owns screen transitions and field states,
     * where a hard front end would read as a jolt on every keystroke.
     *
     * Transcribed from the design comps, which use this one value for entrances, rises, dialogs and
     * list rows without exception — `Identity Capture.dc.html` and `Item Flows.dc.html` alike.
     */
    val Settle: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /**
     * A back-out overshoot — travels past its target and returns.
     *
     * `cubic-bezier(.34,1.56,.64,1)`, the comps' one curve for an object that *lands* rather than
     * arrives: the location pin dropping onto its spot, and a card digit dropping into its slot.
     * The 1.56 control point is what puts it past 1 and back, so this is never right for anything
     * whose end value is a colour or an alpha — an overshoot there clips instead of bouncing.
     */
    val Overshoot: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    /**
     * A symmetric in-and-out — the only curve here that reads as breathing rather than blinking.
     *
     * `cubic-bezier(.4,0,.6,1)`, transcribed from `Location Capture.dc.html`'s `lcEcho`, and within
     * 0.02 of the CSS `ease-in-out` its `lcBreathe` uses — so one token serves both rather than the
     * vocabulary growing by two. Distinct from [Standard] in the way that matters for a value that
     * returns to where it started: a fifth of the way through, this has moved 8% of its travel and
     * [Standard] has moved 68%. Every other curve in this file is front-loaded, and a breath that
     * decelerates is a blink.
     */
    val Breathe: Easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

    // Durations, in the mockup's own steps.
    const val Instant = 120
    const val Quick = 180
    const val Base = 260
    const val Screen = 460
    const val Rise = 550
    const val Panel = 480

    // The comps' own steps for things that arrive, confirm, or draw themselves. Named because the
    // alternative is the same six integers typed into six feature modules.

    /** A card, panel or field opening in place. */
    const val Settling = 340

    /** A crossfade that has to be *seen* to change — a strength band, a reticle locking. */
    const val Crossfade = 300

    /** A list row sliding into the position it will keep. */
    const val Row = 420

    /** A dialog or sheet arriving over a scrim, with [Settle]'s overshoot. */
    const val Dialog = 460

    /**
     * How far the vault travels in depth when it closes or opens — the one scale in the app that
     * carries meaning rather than emphasis. See [lkVaultSealEnter].
     *
     * The spec allows 4–6%; this is the top of it, and it is a number rather than a literal at four
     * call sites because the two directions have to be each other's inverse or the metaphor breaks
     * halfway through.
     */
    const val VaultDepth = 0.06f

    /** A one-shot radial behind something that just succeeded. */
    const val Bloom = 900

    /** The decaying "this is the thing you just made" tint on a freshly-arrived row. */
    const val Flash = 1200

    /** A stroke drawing itself along its own path — a seal ring closing. */
    const val Draw = 1300

    /**
     * A series of readings drawing in toward one point, and the ring that fires when they stop.
     *
     * `Location Capture.dc.html` uses this one value for both — the cloud's `transition:transform
     * 760ms` and the settle ring's `lcSettle 760ms` — because they are the same event seen twice.
     */
    const val Converge = 760

    /** An object dropping onto the spot it marks, with [Overshoot]. The comp's `lcPinDrop`. */
    const val Drop = 620

    /** One full breath of a loading indicator, out and back. The comp's `lcBreathe 1.8s`. */
    const val Breath = 1_800

    /** A value crossfading in place. The comp's `opacity 500ms` / `background 500ms` on the cloud. */
    const val Fade = 500

    /** A card turning over to show its other side. The comp's 620ms `rotateY`, on [Settle]. */
    const val Flip = 620

    /** A looping shimmer over an indeterminate progress track. */
    const val Shimmer = 1150

    /** Delay between successive items in a staggered list entrance. */
    const val StaggerStep = 34

    /** How many items still stagger before the rest just appear. Beyond this it reads as lag. */
    const val StaggerCap = 12
}

/**
 * True when the user has asked the system to remove animations.
 *
 * Provided by [com.localkeep.core.ui.theme.LocalKeepTheme]; defaults to `false` so a composable
 * used outside the theme (a preview, an autofill activity) still animates normally.
 */
val LocalLkReduceMotion = staticCompositionLocalOf { false }

/**
 * Whether the OS animation scale is zero — the "Remove animations" accessibility setting, and what
 * Developer Options' animation scales set too.
 *
 * Reading the setting rather than only offering an in-app switch matters: a user who has already
 * turned animations off system-wide has answered this question, and making them find a second
 * toggle inside one app is how an accessibility preference becomes a lie. Defaults to *animating*
 * when the value cannot be read, since silently freezing the UI for everyone is the worse failure.
 */
fun systemAnimationsDisabled(context: Context): Boolean = try {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
} catch (_: Exception) {
    false
}

/**
 * [systemAnimationsDisabled] as Compose state that actually tracks the setting.
 *
 * The theme used to `remember` a single reading, on the reasoning that "the setting cannot change
 * without the process being brought back to the foreground, which recomposes this anyway". It does
 * not: the theme composable is entered once by `setContent` and nothing it reads changes on resume,
 * so a user who turned on "Remove animations" and came back kept every animation until the process
 * was killed. Settings tells them in as many words that "motion follows your system 'remove
 * animations' setting", which made this a claim the app was not keeping.
 *
 * Two sources, because neither is sufficient alone: a [ContentObserver] catches the change while
 * the app is alive, and a re-read on `ON_RESUME` covers the OEMs whose Settings provider does not
 * notify for this key.
 */
@Composable
fun rememberSystemAnimationsDisabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var disabled by remember(context) { mutableStateOf(systemAnimationsDisabled(context)) }

    DisposableEffect(context, lifecycleOwner) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                disabled = systemAnimationsDisabled(context)
            }
        }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) disabled = systemAnimationsDisabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return disabled
}

/**
 * A tween that collapses to a single frame when motion is reduced.
 *
 * The animation still *runs* — it just arrives immediately. That keeps every call site identical in
 * both modes: state still lands where it should, `AnimatedVisibility` still composes and disposes
 * its children, and nothing has to branch on the preference. Only the travel disappears.
 */
@Composable
@ReadOnlyComposable
fun <T> lkTween(
    durationMillis: Int = LkMotion.Base,
    delayMillis: Int = 0,
    easing: Easing = LkMotion.Standard,
): FiniteAnimationSpec<T> = if (LocalLkReduceMotion.current) {
    tween(durationMillis = 0, delayMillis = 0, easing = LinearEasing)
} else {
    tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing)
}

/** A spring for gestures and press feedback, likewise flattened when motion is reduced. */
@Composable
@ReadOnlyComposable
fun <T> lkSpring(
    dampingRatio: Float = Spring.DampingRatioLowBouncy,
    stiffness: Float = Spring.StiffnessMediumLow,
): AnimationSpec<T> = if (LocalLkReduceMotion.current) {
    tween(durationMillis = 0)
} else {
    spring(dampingRatio = dampingRatio, stiffness = stiffness)
}

/**
 * Where a Regenerate glyph should be pointing after [pressCount] presses of it.
 *
 * A half-turn per press, counter-clockwise, matching the design's `rotate(-180deg)`. It is the only
 * confirmation that a tap did anything when the new value happens to look much like the old one, so
 * it has to fire on *every* press — including two in a row that produce similar output.
 *
 * That is why the input is the press count and not the generated value. Until 2026-08-10 the
 * generator screen computed `value.hashCode().toFloat() % 2f * 180f`, and it almost never moved: a
 * String hash is a full-range Int, Float carries a 24-bit mantissa, so any hash past 2^24 rounds
 * onto a grid whose step is at least 2 and the remainder is exactly 0. Simulated over that screen's
 * own alphabets, 99.57% of random-16 presses and 99.63% of 4-word presses produced no rotation at
 * all; the survivors took their direction from the hash, so even those were inconsistent.
 *
 * It lives here rather than in the generator because the item editor has the same button and must
 * behave identically — a user who has seen one spin reads the other as broken. Accumulating rather
 * than toggling between two absolute angles is deliberate. Unbounded growth is fine: `Modifier.rotate`
 * feeds `graphicsLayer`, which takes any angle, and Int overflow needs about 2.1 billion presses.
 *
 * Not `@Composable` — the reduced-motion branch belongs to the animation spec ([lkTween] collapses
 * to a single frame), not to the angle, which stays the same in both modes.
 */
fun lkRegenerateSpinDegrees(pressCount: Int): Float = pressCount * -180f

/**
 * Screen-to-screen transitions: a short rise that settles, plus a fade, in [LkMotion.Standard].
 *
 * **This used to slide sideways** — forward moved left, back moved right, so the stack had a
 * direction you could feel. The direction is still there; the axis changed. Every screen in the
 * design enters the same way, `translateY(10px)` settling to nothing, and there is no horizontal
 * movement anywhere in it.
 *
 * The axis is also what keeps navigation distinguishable from locking. Those move in *depth* (see
 * [lkVaultOpenEnter] below), and the distinction only survives if each owns one axis outright:
 *
 * - **Navigation translates** — movement *in* the plane. You went somewhere.
 * - **Lock and unlock scale** — movement *through* the plane. Something closed, or opened.
 *
 * The design also asks for a `scale(.985)` here, and it is deliberately dropped. Adding it would
 * have put a 1.5% scale-up at [LkMotion.Screen] beside unlocking's 4% scale-up at [LkMotion.Screen],
 * on curves nobody tells apart at 460ms — a difference of magnitude rather than of kind, which is
 * exactly the collapse the paragraph below was written to prevent. 1.5% is the least load-bearing
 * value in the whole design; the rise is the gesture that actually reads.
 *
 * Travel is small on purpose: a full-width slide, or a tall rise, is in the way by the fiftieth time
 * you have seen it in a session.
 *
 * Back inverts the gesture rather than keeping a separate axis: forward rises from below and settles,
 * back settles downward while the outgoing screen lifts away.
 *
 * These are not `@Composable`, because `NavHost`'s transition parameters are plain lambdas — so
 * [reduceMotion] has to be read at the call site and passed in, and so does [risePx]: the slide
 * lambdas receive pixels and there is no `LocalDensity` reachable inside them. When [reduceMotion]
 * is true every one of these collapses to an instant, travel-free swap.
 */
/**
 * The arriving screen is **opaque from its first frame**, and that is the load-bearing part.
 *
 * It used to fade in over [LkMotion.Screen] while the outgoing screen faded out over
 * [LkMotion.Quick], so for about 180 ms two whole screens were on top of each other and you could
 * read the vault list's tab bar through the item form. Worse, and this is what was actually being
 * reported as flickering for six days: the app shell decides whether to draw the bottom tab bar
 * from `currentBackStackEntryAsState`, which flips the instant `navigate()` is called — while this
 * transition is still showing the *old* screen. So the screen on its way out was re-measured with
 * the destination's chrome, its footer jumped, and only then did the crossing happen. Measured
 * whole-screen on the emulator, bottom third, leaving the Login form: 56 56 53 53 60 60 37 — the
 * 60 is the Save button after the tab bar pushed it up, on a screen the user had already left.
 *
 * An opaque arrival closes that window: whatever the screen underneath does while it is being torn
 * down, nobody can see it. The 10dp rise stays — it is the design's `translateY(10px)`, it is the
 * gesture that reads, and it costs nothing now that it is not carrying an alpha ramp with it.
 */
fun lkScreenEnter(reduceMotion: Boolean, risePx: Int): EnterTransition = EnterTransition.None

/**
 * Nothing. The arriving screen covers this one on its first frame, so any exit here is motion
 * played underneath something opaque — invisible at best, and at worst the re-layout above.
 */
fun lkScreenExit(reduceMotion: Boolean, risePx: Int): ExitTransition = ExitTransition.None

/**
 * Going back does not animate the screen you are going back to. It is already open.
 *
 * This used to be [lkRiseFade] inverted — the destination faded up from nothing and settled 10dp —
 * which is the *arrival* gesture, and running it on a pop plays arriving at a screen the user never
 * left. Reported three times, and the third report names the symptom exactly: *"it re-appears from
 * the bottom as if one just opened it instead of staying already opened"*. Measured on the emulator
 * at `animator_duration_scale 10`, mean luminance of the list body across rapid captures after the
 * back press: 32.7 → 35.0 → 35.5, a visible half-second ramp on a screen that should not have moved
 * at all.
 *
 * [EnterTransition.None], not a shortened fade: any duration is still an entrance, and the argument
 * here is that there is nothing to enter. The screen is drawn at full opacity in its final position
 * on the first frame.
 *
 * **The exit used to keep its animation, and that reasoning has been measured wrong.** The argument
 * was that during a pop the outgoing screen is drawn *above* the incoming one, so it is the only
 * thing that can be seen moving, and removing its fade leaves a hold and then a hard cut. Both
 * halves are true and the conclusion still does not follow, because of what else is happening in
 * that 460 ms: the shell has already switched its tab bar to the destination's (it reads
 * `currentBackStackEntryAsState`, which flips on the call, not on the crossing), so the screen
 * being faded out is being re-measured while it fades. Leaving the Login form, bottom third,
 * whole-screen on the emulator: 56 56 53 53 **60 60** 37 — the tab bar arrives on a screen the user
 * has left, pushes its Save button up, and the user watches that happen before the crossing starts.
 *
 * A fade cannot be shortened out of that; the window has to close. So the pop is now a swap: the
 * destination was never disturbed and is already drawn, and the screen on top of it is removed in
 * one frame. One change, which is the whole rule — between two settled screens the user may see
 * them hand over, and may not see a third state. `tools/crossing/` measures exactly that.
 */
fun lkScreenPopEnter(reduceMotion: Boolean, risePx: Int): EnterTransition = EnterTransition.None

fun lkScreenPopExit(reduceMotion: Boolean, risePx: Int): ExitTransition = ExitTransition.None

private fun lkRiseFade(reduceMotion: Boolean, risePx: Int, fromBelow: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(tween(0))
    } else {
        fadeIn(tween(LkMotion.Screen, easing = LkMotion.Standard)) +
            slideInVertically(tween(LkMotion.Screen, easing = LkMotion.Standard)) {
                if (fromBelow) risePx else -risePx
            }
    }

/**
 * The fade out is shorter than the fade in, and that asymmetry is not load-bearing. Read this before
 * changing it, because the obvious reading of it is wrong and was measured to be wrong.
 *
 * `78b53a6` wrote the pair as [LkMotion.Base] in (260 ms) against [LkMotion.Quick] out (180 ms).
 * `c155a63` then rebalanced the entrance to [LkMotion.Screen] (460 ms) for the rise and left the
 * exit where it was, taking the ratio from 1.4 to 2.6. Nothing argued the new ratio, and it looks
 * exactly like the cause of a real symptom: **every forward navigation passes through a stretch
 * dimmer than either the screen you left or the one you are going to.** Measured on the API 36
 * emulator, mean luminance of the frame body, vault list -> item detail, four cycles per build:
 * a flat 18.78, a floor of 10.51, then a climb to the detail screen's settled 13.37 — a floor 21%
 * below the darker of the two screens, for about half a second. The pop shows the same shape and
 * only escapes the description because the screen it arrives at is the brighter one.
 *
 * It is not this asymmetry. Eight builds, same script, same device, same measurement:
 *
 * | change                                        | floor | dip below the darker screen |
 * |-----------------------------------------------|-------|-----------------------------|
 * | none (this file as it stands)                 | 10.51 | 21.5%                       |
 * | fade out matched to fade in, 460/460          | 10.80 | 19.2%                       |
 * | both fades shortened to 180/180               | 10.61 | 20.6%                       |
 * | [lkStaggerDelay] forced to 0                  | 10.75 | 19.5%                       |
 * | nav-level `fadeIn` removed entirely           | 10.56 | 21.1%                       |
 * | fade out removed entirely                     | 11.30 | 15.5% — and see below       |
 * | **[Modifier.lkEnter]'s per-block alpha removed** | **12.00** | **10.4%**            |
 *
 * Every knob in *this file* is inert. The dip belongs to the arriving screen, not to the crossing:
 * its blocks each fade themselves in over [LkMotion.Screen] via [Modifier.lkEnter], so the content
 * is genuinely at a fraction of its final opacity for the first half-second no matter what the
 * navigation does. That is the entrance the design asks for, working. It is intended.
 *
 * **Do not "fix" it by dropping the fade out.** That was the most promising-looking candidate and it
 * is a regression: the outgoing screen is drawn *above* the incoming one during a pop, so with
 * nothing fading it hid the destination for 500 ms and then vanished between two consecutive frames,
 * 14.52 -> 18.77. A smooth ramp became a hold and a hard cut.
 *
 * The only lever that moves the number is deleting the per-block entrance, which is a product
 * decision about a signature piece of motion (spec §8.1), not a tuning fix — so it is left to be
 * taken deliberately rather than made here as a side effect. Measured 2026-08-12.
 */
private fun lkRiseFadeOut(reduceMotion: Boolean, risePx: Int, upward: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(tween(0))
    } else {
        fadeOut(tween(LkMotion.Quick, easing = LkMotion.Standard)) +
            slideOutVertically(tween(LkMotion.Screen, easing = LkMotion.Standard)) {
                if (upward) -risePx else risePx
            }
    }

/** The design's `translateY(10px)`. Resolved to pixels at the call site — see [lkScreenEnter]. */
val LkScreenRise: Dp = 10.dp

/**
 * Locking and unlocking, which are not navigation and must not look like it.
 *
 * Everything else in this app moves sideways: pushing forward slides left, popping back slides
 * right, and the stack has a direction you can feel. Locking has no direction — the vault is not
 * somewhere you left, it is something that closed. Given the lateral slide it read as "you have
 * arrived at the unlock page", which is the wrong sentence about the single most consequential state
 * change the product has.
 *
 * So these move in *depth* instead. Locking: the vault recedes and dims while the unlock screen
 * settles down over it from slightly above the plane. Unlocking is the exact inverse — the unlock
 * screen lifts away toward the viewer and the vault grows into place behind it. The travel is small
 * ([LkMotion.VaultDepth], the 6% top of the spec's 4–6%) for the same reason the screen slide is a
 * twelfth of the width: this happens every time the phone has been in a pocket, and a theatrical
 * version of it is unbearable by the fiftieth time.
 *
 * **The opacity and the travel are deliberately on different clocks, and that is the whole of what
 * changed on 2026-09-16.** They used to run together, and a scale and a fade of the same length
 * read as one thing — a fade. The visual pass recorded exactly that: unlock was reported as a
 * cross-fade, with a single 20 fps frame holding the Vault header, "Opening your vault…" and a
 * ghost of "Welcome back" all legible at once (`rec/flow01-unlock-z1`), and panic lock as a hard
 * cut with no depth move at all (`rec/flow12-settings`). Two screens legible at once is the same
 * defect [LkScreenPopEnterTest] records being taken out of ordinary crossings, arriving here by a
 * different route.
 *
 * So the arriving screen takes its opacity in [LkMotion.Instant] — long enough not to pop, far too
 * short to read as a dissolve — and then spends [LkMotion.Screen] travelling the last 6% under
 * [LkMotion.Emphasized]. What the user sees is one screen occluding the other and *settling*, which
 * is the sentence this transition is supposed to say. The outgoing screen keeps its own travel for
 * the same duration: it is underneath, so it is mostly inferred rather than watched, but if the
 * arriving screen is ever transparent for a frame the thing behind it must be moving in the same
 * direction rather than sitting still.
 *
 * Not `@Composable`, like the screen transitions above and for the same reason — NavHost's
 * transition parameters are plain lambdas, so [reduceMotion] is read at the call site and passed in.
 * When it is true all four collapse to an instant, travel-free swap.
 */
fun lkVaultSealEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(tween(0))
    } else {
        fadeIn(tween(LkMotion.Instant, easing = LkMotion.Standard)) +
            scaleIn(
                tween(LkMotion.Screen, easing = LkMotion.Emphasized),
                initialScale = 1f + LkMotion.VaultDepth,
            )
    }

fun lkVaultSealExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(tween(0))
    } else {
        fadeOut(tween(LkMotion.Instant, easing = LkMotion.Standard)) +
            scaleOut(
                tween(LkMotion.Screen, easing = LkMotion.Emphasized),
                targetScale = 1f - LkMotion.VaultDepth,
            )
    }

fun lkVaultOpenEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(tween(0))
    } else {
        fadeIn(tween(LkMotion.Instant, easing = LkMotion.Standard)) +
            scaleIn(
                tween(LkMotion.Screen, easing = LkMotion.Emphasized),
                initialScale = 1f - LkMotion.VaultDepth,
            )
    }

fun lkVaultOpenExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(tween(0))
    } else {
        fadeOut(tween(LkMotion.Instant, easing = LkMotion.Standard)) +
            scaleOut(
                tween(LkMotion.Screen, easing = LkMotion.Emphasized),
                targetScale = 1f + LkMotion.VaultDepth,
            )
    }

/**
 * The entrance delay for item [index] in a staggered list.
 *
 * Capped, and zero when motion is reduced: a 40-item vault must not take a second and a half to
 * finish appearing, and a user who turned animations off should see the list immediately.
 */
@Composable
@ReadOnlyComposable
fun lkStaggerDelay(index: Int): Int = when {
    LocalLkReduceMotion.current -> 0
    else -> minOf(index, LkMotion.StaggerCap) * LkMotion.StaggerStep
}

/**
 * Opens the drawer, arriving instantly when motion is reduced.
 *
 * The drawer was the one surface in the app that ignored the reduced-motion preference, and it could
 * not be fixed the way everything else here was: `ModalNavigationDrawer` takes no animation spec, so
 * there is no [lkTween] to hand it — the open/close travel is decided inside Material3 and is not
 * reachable from a call site. Settings promises that "motion follows your system 'remove animations'
 * setting", and the app's widest, slowest movement was the one place that promise did not hold.
 *
 * `snapTo` is the way through. It moves [DrawerState] to the target value without running the
 * animation at all, which is exactly what the reduced-motion contract asks for, and it leaves the
 * ordinary path untouched — [open] still animates for everyone else.
 *
 * **Scope, stated plainly:** this covers the programmatic open and close, which is how the drawer is
 * opened from the app bar and closed on navigation. A drawer *dragged* by the user still settles
 * under Material3's internal spring, because the settle is part of the gesture and is not exposed.
 * That is a genuine remaining gap, not one this helper quietly papers over.
 */
suspend fun DrawerState.lkOpen(reduceMotion: Boolean) {
    if (reduceMotion) snapTo(DrawerValue.Open) else open()
}

/** [lkOpen]'s counterpart. */
suspend fun DrawerState.lkClose(reduceMotion: Boolean) {
    if (reduceMotion) snapTo(DrawerValue.Closed) else close()
}

/**
 * Swaps one section of a screen for another when [target] changes, with the app's own curve.
 *
 * For a form whose middle changes shape while its frame stays put — the item editor's per-type
 * fields, most obviously. Switching from a Secure note to a 2FA code replaced eight rows in a single
 * frame while the header, the title well and the chips all stayed identical, so nothing on screen
 * marked that anything had happened (2026-07-29 hand-test). A short cross-fade with a few dp of
 * downward travel is enough: it says "this part is new" without making the user wait.
 *
 * `SizeTransform` is disabled deliberately. The sections differ in height by hundreds of dp, and
 * animating that pushes everything below them — including the Save button — up and down the screen
 * for a quarter of a second, which reads as the layout being unsure of itself.
 *
 * Under reduced motion it is an instant swap, like every other transition in the app.
 */
@Composable
fun <T> LkSectionSwap(
    target: T,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    content: @Composable (T) -> Unit,
) {
    val reduceMotion = LocalLkReduceMotion.current
    androidx.compose.animation.AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0)) using null
            } else {
                (
                    fadeIn(tween(LkMotion.Quick, easing = LkMotion.Standard)) +
                        androidx.compose.animation.slideInVertically(
                            tween(LkMotion.Quick, easing = LkMotion.Standard),
                        ) { it / 24 }
                    ) togetherWith
                    // Out faster than in: the old content should be gone before the new arrives,
                    // or the two are briefly legible at once and the swap reads as a flicker.
                    fadeOut(tween(LkMotion.Instant, easing = LkMotion.Standard)) using null
            }
        },
        label = "section",
        content = { content(it) },
    )
}

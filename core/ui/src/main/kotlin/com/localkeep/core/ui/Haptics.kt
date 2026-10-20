package com.localkeep.core.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The distinct things this app can say through touch (UI/UX spec §8.2).
 *
 * Deliberately named for the *event*, not the effect. Call sites should not be choosing between
 * "long press" and "text handle move" — those are Compose's names for waveforms, and picking them
 * per call site is how five copy buttons end up feeling like five different actions. Mapping an
 * event to a waveform is this file's job and only this file's job, which is also what makes the
 * eventual richer vibration patterns (C3's full scope) a change in one place.
 */
enum class Haptic {
    /** The vault opened. The one genuinely good moment in the app. */
    UNLOCK,

    /** A secret reached the clipboard. Confirms an action whose result is invisible. */
    COPY,

    /**
     * A secret came into view.
     *
     * Split from [CONCEAL] rather than one REVEAL signature because the platform's own vocabulary is
     * directional here (`TOGGLE_ON` / `TOGGLE_OFF`), and because in this app the two are not the same
     * event: one puts a password on the screen, the other takes it off. Both call sites already knew
     * which way they were going.
     */
    REVEAL,

    /** A revealed secret was hidden again. */
    CONCEAL,

    /** A TOTP code rolled over to the next window. */
    TOTP_ROLLOVER,

    /** Something failed: wrong password, failed re-auth, rejected input. */
    ERROR,

    /**
     * A change was committed: an item saved, a delete confirmed, an import finished.
     *
     * Maps to the same waveform as [UNLOCK] on every API level, which is not a mistake — the
     * platform has exactly one "what you asked for worked" and both events are that. They stay
     * separate enum entries because this file's premise is that call sites name the *event*, and a
     * Save button playing something called `UNLOCK` would be a lie that survives until someone
     * changes what unlocking feels like.
     */
    SUCCESS,

    /**
     * An ordinary control was activated — a button, a list row, a filter chip, a tab, a toggle.
     *
     * The catch-all, and deliberately the quietest thing with any presence: it fires more often than
     * every other signature combined, so anything with weight would become the app's dominant
     * sensation and train people to turn haptics off.
     */
    SELECT,
}

/**
 * Plays [Haptic] signatures, or does nothing when the user has asked for quiet.
 *
 * Obtain with [rememberHaptics] rather than constructing one, so the reduce-haptics preference is
 * read once per composition instead of on every tap.
 */
class Haptics internal constructor(
    private val feedback: HapticFeedback,
    private val silenced: Boolean,
) {
    fun play(signature: Haptic) {
        if (silenced) return
        feedback.performHapticFeedback(signature.feedbackTypeOn(Build.VERSION.SDK_INT))
    }
}

/**
 * Each event's own texture, degrading to the best one [sdkInt] actually has.
 *
 * **Why not `VibrationEffect` primitives.** The obvious way to build distinct textures is
 * `VibrationEffect.startComposition()` with `PRIMITIVE_CLICK` / `PRIMITIVE_THUD` / `PRIMITIVE_RISE`
 * and `Vibrator.arePrimitivesSupported` as the availability check. That path is closed here, and for
 * a reason worth stating rather than rediscovering: `Vibrator.vibrate` requires
 * `android.permission.VIBRATE`, which is on no flavour's allowlist — a guarantee with a test
 * behind it (`FossManifestTest`). Taking that route would have meant either breaking that promise or
 * shipping foss users a deliberately worse app. `View.performHapticFeedback` needs no permission at
 * all, and what it plays is tuned by the OEM for that device's actual actuator, which a
 * hand-authored amplitude curve is not.
 *
 * **Why the ladder.** Compose maps each of its types to a `HapticFeedbackConstants` int and calls
 * straight through with no version guard, so asking a device for a constant newer than its API level
 * gets silence rather than a fallback. The availability check therefore has to live here, and the
 * axis is the API level, since each constant landed at a known one:
 *
 *  - `CONFIRM` / `REJECT` / `GESTURE_END` — API 30
 *  - `TOGGLE_ON` / `TOGGLE_OFF` / `SEGMENT_TICK` / `SEGMENT_FREQUENT_TICK` — API 34
 *
 * From API 30 up, all seven signatures are distinct. On 28–29 Compose exposes only five constants at
 * all (long press, virtual key, keyboard tap, context click, text-handle move) so two pairs must
 * share, and the pairs were chosen to be the ones a user is least likely to be comparing:
 * [Haptic.REVEAL] with [Haptic.CONCEAL] (same field, same button), and [Haptic.SELECT] with
 * [Haptic.TOTP_ROLLOVER] (both light background ticks). That is a real limit of those releases,
 * recorded here and pinned by `HapticSignatureTest` rather than papered over with a fake pattern.
 *
 * Takes [sdkInt] as a parameter rather than reading `Build.VERSION.SDK_INT` directly so the mapping
 * — which is the whole of C3 — can be tested at every API level on a plain JVM.
 */
internal fun Haptic.feedbackTypeOn(sdkInt: Int): HapticFeedbackType {
    fun atLeast(sdk: Int, preferred: HapticFeedbackType, fallback: HapticFeedbackType) =
        if (sdkInt >= sdk) preferred else fallback

    return when (this) {
        // The vault opened: the platform's own "what you asked for worked".
        Haptic.UNLOCK, Haptic.SUCCESS ->
            atLeast(30, HapticFeedbackType.Confirm, HapticFeedbackType.ContextClick)
        // ...and its own "no". Falls back to the heaviest thing older releases have.
        Haptic.ERROR -> atLeast(30, HapticFeedbackType.Reject, HapticFeedbackType.LongPress)
        // A crisp, discrete confirmation for an action whose result is invisible.
        Haptic.COPY -> HapticFeedbackType.KeyboardTap
        // A switch flipping, in the direction it flipped.
        Haptic.REVEAL -> atLeast(
            34,
            HapticFeedbackType.ToggleOn,
            atLeast(30, HapticFeedbackType.GestureEnd, HapticFeedbackType.TextHandleMove),
        )
        Haptic.CONCEAL -> atLeast(34, HapticFeedbackType.ToggleOff, HapticFeedbackType.TextHandleMove)
        // The quietest thing available, deliberately: this one fires unattended every 30 seconds, and
        // anything with presence would become the app's most-felt sensation by a wide margin.
        Haptic.TOTP_ROLLOVER -> atLeast(
            34,
            HapticFeedbackType.SegmentFrequentTick,
            HapticFeedbackType.VirtualKey,
        )
        // A deliberate, discrete pick — a chip, a list row.
        Haptic.SELECT -> atLeast(
            34,
            HapticFeedbackType.SegmentTick,
            atLeast(30, HapticFeedbackType.ContextClick, HapticFeedbackType.VirtualKey),
        )
    }
}

/**
 * A [Haptics] bound to the current preference state.
 *
 * Reads [A11yPreferences.reduceHapticsState], which is Compose state, so toggling "Reduce haptics"
 * in Settings takes effect immediately rather than on the next app launch. The OS setting is folded
 * in here too; it cannot change while a screen is composed without the process being resumed, so it
 * needs no observability of its own.
 */
/**
 * Wraps [onClick] so it plays [signature] first. The shared components' way of being felt.
 *
 * Every tappable in `core/ui` takes a `haptic` parameter defaulted to [Haptic.SELECT] and routes it
 * through here, which is what turned haptics from a feature of six screens into a property of the
 * design system. Before this, the app had a carefully built seven-signature vocabulary wired to
 * roughly a dozen call sites: the FAB, the drawer, the bottom bar, every settings toggle, every
 * Save and every dialog button were silent to the touch.
 *
 * A null [signature] opts out, for the call sites that play their own signature at a moment other
 * than the tap — a copy that only happens after a re-auth succeeds, say.
 *
 * The `remember` is not an optimisation: `Haptics` is rebuilt whenever the reduce-haptics
 * preference changes, and keying on it is what makes the toggle in Settings take effect on the very
 * next tap rather than the next recomposition that happens to rebuild this lambda.
 */
@Composable
fun rememberHapticClick(signature: Haptic?, onClick: () -> Unit): () -> Unit {
    val haptics = rememberHaptics()
    return remember(haptics, signature, onClick) {
        if (signature == null) {
            onClick
        } else {
            { haptics.play(signature); onClick() }
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val preferQuiet by A11yPreferences.reduceHapticsState(context)
    val silenced = preferQuiet || A11yPreferences.systemHapticsDisabled(context)
    return remember(feedback, silenced) { Haptics(feedback, silenced) }
}

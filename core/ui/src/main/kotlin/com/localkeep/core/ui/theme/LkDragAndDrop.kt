package com.localkeep.core.ui.theme

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.localkeep.core.ui.decorative
import kotlin.math.roundToInt

/**
 * In-process drag and drop: press and hold a row, drag it onto a target, let go.
 *
 * ## Why none of this uses the platform's drag
 *
 * Android's own drag — `View.startDragAndDrop`, `Modifier.dragAndDropSource` — hands a drag shadow
 * to the **system window manager**, which draws it in a window this app does not own and therefore
 * outside the app's `FLAG_SECURE` surface. For a password manager that is not a styling preference,
 * it is a leak: the shadow of a row carrying a login title would be capturable and would show up in
 * the recents thumbnail. Everything here is drawn inside the app's own window and the payload never
 * leaves the process. `checkScreenSecurity` fails the build if either platform API appears in a
 * source set, so this paragraph cannot quietly stop being true.
 *
 * ## The pieces
 *
 * [LkDragSession] holds the state. [Modifier.lkPressAndDrag] starts a drag from a row and
 * [Modifier.lkDropTarget] registers somewhere to drop it. [LkDragOverlay] draws the ghost and
 * [LkDragDwellClock] fires spring-loads. The engine knows about payloads, targets and pixels, and
 * nothing at all about vaults.
 */
class LkDragSession {

    /** What is being dragged, or null when nothing is. */
    var payload: LkDragPayload? by mutableStateOf(null)

    /** Where the finger is, in root coordinates. */
    var fingerPosition: Offset by mutableStateOf(Offset.Zero)

    /** Where the dragged row was, so a rejected drop can spring back to it. */
    var ghostOrigin: Rect by mutableStateOf(Rect.Zero)

    /** The grab point's offset inside [ghostOrigin]; its x keeps the ghost level with the finger. */
    var grabOffset: Offset by mutableStateOf(Offset.Zero)

    /**
     * True from the moment the long press registers until the finger actually moves.
     *
     * Two things happen the instant the hold registers — the haptic and the ghost's lift — so a
     * held row is visibly *picked up*. The destination affordances, though, are a drag-in-motion
     * concern and wait for movement: the bin, the "move to parent" chip and the unfile strip appear
     * only once [isMoving] turns true. See the history behind that split:
     *
     *  - They first appeared only past touch slop, and a phone reported *"the trashbin doesn't
     *    appear until I move my finger"* (2026-08-18), so all of it was moved onto the hold.
     *  - That over-corrected: a red bin flashing under a stationary finger that is only opening the
     *    context menu reads as a threat where none was intended (2026-08-18). The lift is the
     *    "you are holding this" signal; the bin is the "here is where it can land" signal, and only
     *    the second one needs the finger to be moving. This is the platform norm — iOS lift, the
     *    Android launcher — where drop targets surface on drag, not on press.
     *
     * A release while this is still true is not a drop — it is the long press the row has always
     * had, and it opens the context menu. Without the distinction, holding a row and letting go
     * would deliver the payload to whatever sits under the stationary finger.
     */
    var pendingMove: Boolean by mutableStateOf(false)

    var hovered: LkDropTargetKey? by mutableStateOf(null)
        internal set

    var hoveredAcceptance: LkDropAcceptance by mutableStateOf(LkDropAcceptance.Neutral)
        internal set

    /**
     * Frame time at which the current hover began, in nanoseconds, or null when not dwelling.
     *
     * **Frame time, not wall clock.** `mainClock.advanceTimeBy` in a Compose UI test drives the
     * frame clock and nothing else, so a spring-load timed against `System.currentTimeMillis()`
     * could not be tested at all: the test would advance the clock, nothing would fire, and the
     * promised test-clock control would be a comment rather than a mechanism.
     */
    var dwellStartedAt: Long? by mutableStateOf(null)
        internal set

    /**
     * True while a drop's write is in flight.
     *
     * A second drag cannot start until it clears, because every target's bounds are stale until the
     * list has refreshed — dropping against them would land the payload somewhere it no longer is.
     */
    var committing: Boolean by mutableStateOf(false)

    internal val targets = SnapshotStateMap<LkDropTargetKey, LkRegisteredTarget>()

    /**
     * Frame time before which a freshly-registered target may not spring-load.
     *
     * A spring-load fires *because* the finger stopped, and firing disposes the row that fired it:
     * the list swaps in the entered folder's children, and whatever now renders at those pixels
     * would take an immediate drop from a finger that never moved. Dwelling on "Documents" would put
     * the payload in the first folder inside it. This window only defers the next spring; the drop
     * itself is guarded by [stationaryAt], because a rest longer than the window is still a rest.
     */
    internal var settleUntil: Long = 0L

    /**
     * Where the finger was when the registry last changed under it, or null once it has travelled.
     *
     * [settleUntil] only defers the *next spring-load*; it never stopped a drop. And no time window
     * could: a finger resting on a breadcrumb crumb springs the list to the root, then rests on
     * whatever folder row now renders under it, and a release ~0.6 s later filed the item into that
     * folder — a destination the user never aimed at. What separates the two cases is not time but
     * travel: a target the finger moved onto was chosen, a target that appeared under a stationary
     * finger was not. So [drop] refuses delivery while this is set, and [onDragTo] clears it once
     * the finger has moved past [moveSlopPx] from here.
     */
    internal var stationaryAt: Offset? = null

    /**
     * How far the finger must travel from [stationaryAt] to count as moving, in px.
     *
     * Set by [lkDragTracker] from the platform touch slop. A resting finger still reports small
     * moves, and treating any of them as travel would leave the guard above open on a real screen.
     */
    internal var moveSlopPx: Float = 0f

    val isDragging: Boolean get() = payload != null

    /**
     * A drag that has actually started moving — armed *and* past the point where a release still
     * means the context menu.
     *
     * [isDragging] is true from the moment a row is picked up, which is right for the ghost: the
     * lift is what a held row looks like. The destination affordances gate on this instead, so the
     * bin and the drop strips appear on movement rather than on the hold. See [pendingMove].
     */
    val isMoving: Boolean get() = payload != null && !pendingMove

    /** Ends the drag and forgets everything about it. */
    fun clear() {
        payload = null
        pendingMove = false
        hovered = null
        hoveredAcceptance = LkDropAcceptance.Neutral
        dwellStartedAt = null
        dwellResumePending = false
        stationaryAt = null
    }

    /**
     * Records where the finger is. The hit test itself runs on the next frame, in [LkDragDwellClock].
     *
     * Split that way so every position in this class is stamped with a **frame** time from one
     * clock. Hit-testing inline would need a timestamp here, and the only one available at a pointer
     * event is the wall clock — which agrees with the frame clock on a device and diverges from it
     * under `mainClock.advanceTimeBy`, making the dwell untestable in exactly the way
     * [dwellStartedAt] exists to avoid.
     */
    fun onDragTo(positionInRoot: Offset) {
        fingerPosition = positionInRoot
        val restedAt = stationaryAt ?: return
        if ((positionInRoot - restedAt).getDistance() > moveSlopPx) stationaryAt = null
    }

    /**
     * Runs one hit test at [position] and updates the hover state.
     *
     * Topmost registration wins an overlap: the "Move to parent" chip slides in over the first row
     * and has to take the drop rather than the row underneath it.
     */
    fun hitTest(position: Offset, frameTimeNanos: Long) {
        val carried = payload ?: return
        val hit = targets.entries
            .filter { it.value.bounds.contains(position) }
            .maxByOrNull { it.value.ordinal }

        val newKey = hit?.key
        if (newKey == hovered) {
            // Same target, and the finger has now travelled since the last spring-load: the dwell
            // starts here, from this frame, not from whenever the hover began.
            if (dwellResumePending && stationaryAt == null && hovered != null &&
                hoveredAcceptance != LkDropAcceptance.Rejects
            ) {
                dwellStartedAt = frameTimeNanos
                dwellResumePending = false
            }
            return
        }
        hovered = newKey
        hoveredAcceptance = hit?.value?.accepts?.invoke(carried) ?: LkDropAcceptance.Neutral
        // Anything but a refusal dwells. A rejecting target must not spring-load — navigating
        // somewhere the payload cannot land is a dead end with the payload still in hand — but a
        // `Neutral` target must, because that is exactly what a breadcrumb crumb is: somewhere to
        // go rather than something to drop into. Keying the dwell on `Accepts` alone would leave
        // every crumb inert and silently remove the "drag onto the back arrow" half of the feature.
        //
        // And not while the finger has not travelled since the last spring-load ([stationaryAt]):
        // a spring-load swaps the list under a resting finger, and the folder row that now renders
        // there would otherwise spring-load too after its own dwell - the finger resting on a
        // breadcrumb took the list to the root and then, still resting, into whichever folder sat
        // at those pixels. The dwell waits for movement past touch slop, the same travel [drop]
        // asks for, and then starts from the frame that saw it.
        val dwells = hoveredAcceptance != LkDropAcceptance.Rejects
        dwellStartedAt = if (dwells && stationaryAt == null) frameTimeNanos else null
        dwellResumePending = dwells && stationaryAt != null
    }

    /** True while a hover is waiting for the finger to move before its spring-load dwell begins. */
    private var dwellResumePending = false

    /**
     * Re-runs the hit test at the last known finger position and drops a hover whose target is gone.
     *
     * Called after anything that changes the registry — chiefly a spring-load. See [settleUntil]
     * and [stationaryAt].
     */
    fun rehitTest(frameTimeNanos: Long) {
        if (payload == null) return
        val current = hovered
        if (current != null && current !in targets) {
            hovered = null
            hoveredAcceptance = LkDropAcceptance.Neutral
            dwellStartedAt = null
        }
        settleUntil = frameTimeNanos + SETTLE_MILLIS * 1_000_000
        stationaryAt = fingerPosition
        // A hover that survived the swap keeps its target but not its dwell: the spring-load that
        // just fired used it up, and the next one waits for the finger to travel.
        if (hovered != null) {
            dwellStartedAt = null
            dwellResumePending = hoveredAcceptance != LkDropAcceptance.Rejects
        }
        hitTest(fingerPosition, frameTimeNanos)
    }

    /**
     * Delivers the payload to whatever is hovered and ends the drag.
     *
     * @return true when a target took it, so the caller can pick the swallow animation over the
     *   spring-back. A `NoOp` returns false and writes nothing — dropping an item on the folder it
     *   is already in is not an error and not a write. So does a release before the finger has
     *   moved since the last spring-load — see [stationaryAt].
     */
    fun drop(): Boolean {
        // Armed but never moved: a plain long press, whose meaning is the row's context menu.
        if (pendingMove) {
            clear()
            return false
        }
        val carried = payload ?: return false
        val target = hovered?.let(targets::get)
        val accepted = target != null &&
            hoveredAcceptance == LkDropAcceptance.Accepts &&
            stationaryAt == null
        if (accepted) target!!.onDrop(carried)
        clear()
        return accepted
    }
}

/** What a target thinks of the payload currently over it. */
enum class LkDropAcceptance {
    /** A drop here writes something. */
    Accepts,

    /** Refused — coral ring, no haptic, no toast, and no spring-load. */
    Rejects,

    /** Legal but pointless: the item is already in this folder. Silent, and no write. */
    NoOp,

    /** Nothing is hovered. */
    Neutral,
}

/** Identifies a drop target. Value-equal, so a re-registered target keeps its identity. */
@JvmInline
value class LkDropTargetKey(val value: String)

/** Anything a drag can carry. The engine never looks inside it. */
interface LkDragPayload {
    /** How many things are being dragged, for the ghost's count badge. */
    val count: Int
}

internal data class LkRegisteredTarget(
    val bounds: Rect,
    val accepts: (LkDragPayload) -> LkDropAcceptance,
    val springLoad: (() -> Unit)?,
    val onDrop: (LkDragPayload) -> Unit,
    /** Registration order, so the most recently composed target wins an overlap. */
    val ordinal: Int,
)

/**
 * How long a finger must rest on a folder before it opens.
 *
 * Roughly double the platform long press: long enough that passing over a folder on the way
 * somewhere else does not trigger it, short enough not to feel stuck. This is the one number here
 * with no source in the codebase, and it is a tuning target for the device pass rather than a
 * constant to defend.
 */
const val LK_SPRING_LOAD_MILLIS = 600L

/** How long a freshly-registered target stays inert under a stationary finger. */
private const val SETTLE_MILLIS = 250L

/**
 * Replaces `combinedClickable` on a draggable row, and re-provides everything it gave.
 *
 * This is the part that is easy to get wrong and hard to notice. `combinedClickable` is not merely a
 * gesture: Foundation's `AbstractClickableNode` is a `DelegatingNode` supplying **five** things at
 * once, and dropping any of them is a regression rather than a feature.
 *
 * 1. tap and long-press recognition,
 * 2. paired `PressInteraction.Press` / `Release` / `Cancel`,
 * 3. keyboard, D-pad and Switch Access focusability — the sole reason `lkFocusRing` ever lights up,
 * 4. `onClickLabel` / `onLongClickLabel` as TalkBack action hints,
 * 5. the default TalkBack activation, merged into the same semantics node as the row's own
 *    `customActions`.
 *
 * Each is re-provided below, in that order. The `semantics` block is chained rather than replacing,
 * so a row's existing `customActions` merges into the same node instead of forming a second stop.
 *
 * @param onLongPressRelease a long press ending without movement — today's row context menu.
 * @param dragEnabled false leaves tap, long press and focus intact and only withholds the drag.
 */
@Composable
fun Modifier.lkPressAndDrag(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onClickLabel: String,
    onLongPressRelease: () -> Unit,
    onLongPressLabel: String,
    onDragStart: (grabInRoot: Offset, origin: Rect) -> Unit,
    /**
     * The long press has registered and the finger has not moved yet.
     *
     * Separate from [onDragStart] because the two answer different questions: this one is "show the
     * user we are holding something", which is true the instant the hold is recognised, and that is
     * the other one is "a drag is now happening", which needs movement to distinguish it from the
     * context menu. Defaulted to [onDragStart] so a caller that does not care keeps the old timing.
     */
    onDragArmed: (grabInRoot: Offset, origin: Rect) -> Unit = onDragStart,
    /** Runs when the press ends without ever becoming a drag or a long press — a cancelled press. */
    onDragCancel: () -> Unit,
    dragEnabled: Boolean = true,
): Modifier {
    // The row's own bounds in root coordinates, so every position this hands out is in the same
    // frame the drop targets publish theirs in. Converting at the call site instead would make each
    // row responsible for a coordinate space it has no reason to know about, and one row getting it
    // wrong would be a drop landing on the wrong target rather than a visible bug.
    val bounds = remember { mutableStateOf(Rect.Zero) }
    return this
    .onGloballyPositioned { bounds.value = it.boundsInRoot() }
    .focusable(interactionSource = interactionSource)
    .semantics {
        role = Role.Button
        onClick(label = onClickLabel) { onClick(); true }
        onLongClick(label = onLongPressLabel) { onLongPressRelease(); true }
    }
    .pointerInput(dragEnabled) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = PressInteraction.Press(down.position)
            // `tryEmit`, not `emit`: `awaitEachGesture` runs in `AwaitPointerEventScope`, which is
            // `@RestrictsSuspension`, so it can neither suspend on the flow nor host a `launch`.
            // The interaction source's buffer is `DROP_OLDEST`, so this always succeeds, and the
            // one thing it could ever drop is an *older* Press — leaving a Release unpaired, which
            // `collectIsPressedAsState` handles by doing nothing. The dangerous direction is a
            // dropped Release, and this cannot produce one.
            interactionSource.tryEmit(press)

            // Every path below ends this press. `lkPressScale` reads `collectIsPressedAsState()`,
            // which stays true from Press until a matching Release or Cancel arrives and has no
            // timeout of its own — miss one and the row scales to 0.985 and never scales back.
            var settled = false
            fun endPress(cancelled: Boolean) {
                if (settled) return
                settled = true
                interactionSource.tryEmit(
                    if (cancelled) PressInteraction.Cancel(press) else PressInteraction.Release(press),
                )
            }

            val armed = awaitLongPressOrCancellation(down.id)
            if (armed == null) {
                // **`null` is ambiguous, and this is the bug it causes.** Foundation returns null
                // identically for "all pointers lifted before the timeout" (a tap) and "consumed or
                // out of bounds" (an ancestor LazyColumn claimed the gesture for a scroll). A naive
                // `if (result == null) onClick()` therefore opens an item every single time the user
                // starts scrolling with their finger resting on a row.
                //
                // The pointer's own state disambiguates: still pressed means something else took the
                // gesture; not pressed means the finger came up where it went down.
                //
                // **That test alone is not enough, and this is the bug it caused.** It separates an
                // *ancestor* stealing the gesture (finger still down) from a tap, but a *descendant*
                // that handled the tap as its own click leaves the finger up — identical to a row
                // tap. `awaitFirstDown(requireUnconsumed = false)` above means such a gesture reaches
                // here at all, so the row ran `onClick()` on top of whatever the child already did.
                // Measured on emulator-5556 on 2026-08-29: one tap on the vault list's inline
                // `Copy password for …` pill put the password on the clipboard *and* opened the item.
                //
                // Consumption is what tells them apart — a child's `clickable` consumes the change it
                // handled, and nothing on the plain-tap path through this node consumes anything (the
                // only `consume()` here is the drag branch below, which never reaches this code).
                // The same shape was fixed once before by moving the overflow control out into a
                // sibling overlay; doing that per-control leaves the next child to rediscover it, so
                // the guard belongs here.
                val change = currentEvent.changes.firstOrNull { it.id == down.id }
                val stillPressed = change?.pressed == true
                val handledByDescendant = change?.isConsumed == true
                if (stillPressed || handledByDescendant) {
                    endPress(cancelled = true)
                } else {
                    onClick()
                    endPress(cancelled = false)
                }
                return@awaitEachGesture
            }

            if (!dragEnabled) {
                onLongPressRelease()
                endPress(cancelled = false)
                return@awaitEachGesture
            }

            // The hold has registered. The affordances go up now rather than on movement — see
            // `LkDragSession.pendingMove`, which is what keeps a release from here meaning a drop.
            onDragArmed(bounds.value.topLeft + down.position, bounds.value)

            // Armed. Movement decides from here: past slop it is a drag, released without moving it
            // is the context menu. That is the same rule the rest of the app uses, and it resolves
            // the selection-mode collision too — hold still extends the selection, hold then move
            // drags it.
            //
            // **The row hands the drag off the instant it starts, and does not track it.** This
            // looks like an odd split and it is the only shape that works: a spring-load navigates
            // the list *while the finger is still down*, which disposes the very row the gesture is
            // running on. Its `pointerInput` node dies with it, the gesture coroutine is cancelled,
            // and neither `onDragEnd` nor `onDragCancel` ever runs — the payload is stranded and the
            // drop silently does nothing. Measured on emulator-5554 on 2026-08-18: dragging an item
            // onto a folder navigated into that folder correctly and then never wrote. Tracking
            // belongs to something that outlives the row, so `LkDragTracker` on the screen root owns
            // it from here.
            var dragging = false
            var change: PointerInputChange? = armed
            while (change != null && change.pressed) {
                if ((change.position - down.position).getDistance() > slop) {
                    dragging = true
                    change.consume()
                    onDragStart(bounds.value.topLeft + down.position, bounds.value)
                    break
                }
                change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            }

            when {
                dragging -> Unit
                // Both paths must take the affordances back down: the session was armed above, and
                // an abandoned hold that left a ghost on screen is worse than one that never lifted.
                change == null -> onDragCancel()
                else -> {
                    onDragCancel()
                    onLongPressRelease()
                }
            }
            endPress(cancelled = dragging || change == null)
        }
    }
}


/**
 * Tracks a drag for as long as it lasts, from the screen root rather than from the dragged row.
 *
 * Place this on the root `Box`'s modifier chain, above everything else. It exists because the row
 * cannot do this job: a spring-load navigates the list under a stationary finger and disposes the
 * row the gesture started on, taking its pointer-input node — and therefore the rest of the drag —
 * with it. The root survives every navigation the drag can cause.
 *
 * Events are read on the **Initial** pass so the tracker sees the pointer before the `LazyColumn`
 * does, and consumed so the list cannot turn the same movement into a fling underneath the ghost.
 */
fun Modifier.lkDragTracker(session: LkDragSession): Modifier = composed {
    // **This node's own root offset, and it is not zero.** A pointer change reports its position
    // relative to the node that received it, while `lkDropTarget` publishes `boundsInRoot()`. The
    // screen root sits below the status bar, so the two frames differ by exactly its height and
    // every hit test misses by that much — measured on emulator-5554 on 2026-08-18: a finger at
    // y=1010 reported y=878 against a folder row at 945..1071, so the drop found nothing and
    // silently did nothing. Converting here rather than at the targets, because root coordinates
    // are the one frame every participant can agree on.
    val origin = remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin.value = it.boundsInRoot().topLeft }
        .pointerInput(session) {
            session.moveSlopPx = viewConfiguration.touchSlop
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!session.isDragging) continue
                    val change = event.changes.firstOrNull() ?: continue
                    change.consume()
                    if (change.pressed) {
                        session.onDragTo(origin.value + change.position)
                    } else {
                        session.drop()
                    }
                }
            }
        }
}

/**
 * Registers somewhere a payload can be dropped, for as long as this composable is on screen.
 *
 * Bounds are published in **root** coordinates: the hit test runs against a finger position that is
 * also in root coordinates, and the two live in different composables.
 *
 * Composable, so the registration can be undone in `onDispose`. Without that, a folder row scrolled
 * out of the `LazyColumn` would keep a target sitting at the pixels it used to occupy, and the drop
 * would go to a row that is no longer there.
 *
 * @param accepts consulted on hover; drives the ring colour and whether a spring-load may fire.
 * @param springLoad fired after [LK_SPRING_LOAD_MILLIS] of dwell, only when [accepts] says `Accepts`.
 */
@Composable
fun Modifier.lkDropTarget(
    key: LkDropTargetKey,
    session: LkDragSession,
    accepts: (LkDragPayload) -> LkDropAcceptance,
    springLoad: (() -> Unit)? = null,
    onDrop: (LkDragPayload) -> Unit,
): Modifier {
    val ordinal = remember(key) { nextOrdinal() }
    DisposableEffect(key, session) {
        onDispose {
            session.targets.remove(key)
            if (session.hovered == key) {
                session.hovered = null
                session.hoveredAcceptance = LkDropAcceptance.Neutral
                session.dwellStartedAt = null
            }
        }
    }
    return this.onGloballyPositioned { coordinates ->
        session.targets[key] = LkRegisteredTarget(
            bounds = coordinates.boundsInRoot(),
            accepts = accepts,
            springLoad = springLoad,
            onDrop = onDrop,
            ordinal = ordinal,
        )
    }
}

private var ordinalCounter = 0

private fun nextOrdinal(): Int = ordinalCounter++

/**
 * Drives the dwell clock and fires spring-loads. One per screen, beside [LkDragOverlay].
 *
 * `withFrameNanos` rather than `delay`, for the reason in [LkDragSession.dwellStartedAt].
 */
@Composable
fun LkDragDwellClock(session: LkDragSession) {
    LaunchedEffect(session.isDragging) {
        if (!session.isDragging) return@LaunchedEffect
        while (true) {
            val now = withFrameNanos { it }
            // One hit test per frame rather than one per pointer event: a move can arrive several
            // times between frames and the result cannot change without a repaint anyway.
            session.hitTest(session.fingerPosition, now)
            if (now < session.settleUntil) continue
            val started = session.dwellStartedAt ?: continue
            if (session.hoveredAcceptance == LkDropAcceptance.Rejects) continue
            if ((now - started) / 1_000_000 < LK_SPRING_LOAD_MILLIS) continue
            val fire = session.hovered?.let(session.targets::get)?.springLoad ?: continue
            fire()
            session.rehitTest(now)
        }
    }
}

/**
 * How far through the current dwell we are, 0f–1f, for the ring around the hovered folder's icon.
 *
 * Reported under reduced motion too, and the caller draws it with `animated = false` — the precedent
 * is `LkDeriving.kt`, which passes exactly that to `LkProgressRing` so *"the arc jumps to each
 * checkpoint the instant it lands"*. This is real elapsed time in discrete steps, not a tween, so it
 * is not the kind of animation a user who turned animations off was turning off.
 */
@Composable
fun lkDwellProgress(session: LkDragSession): Float {
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(session.hovered, session.isDragging) {
        if (!session.isDragging) {
            progress = 0f
            return@LaunchedEffect
        }
        while (true) {
            val now = withFrameNanos { it }
            val started = session.dwellStartedAt
            progress = when (started) {
                null -> 0f
                else -> ((now - started) / 1_000_000f / LK_SPRING_LOAD_MILLIS).coerceIn(0f, 1f)
            }
        }
    }
    return progress
}

/**
 * The ghost under the finger.
 *
 * Placed as the **last child of the screen's root `Box`** — above the list and the FAB, never inside
 * the `LazyColumn`, which would clip it at the viewport edge and destroy it the moment the origin
 * row recycled.
 *
 * `decorative()`, this repo's own helper, rather than `invisibleToUser()`, which appears nowhere
 * else in the tree: the ghost duplicates a row TalkBack has already announced, and announcing the
 * same content twice mid-gesture is noise.
 *
 * **Gates on [LkDragSession.isMoving], not [LkDragSession.isDragging].** A stationary hold still
 * arms the drag (haptic fires from the gesture itself) but must not render this: the ghost is a
 * compact plate, not a clone of the row, so it renders offset from — and smaller than — the row
 * still sitting underneath it, and the two read as the same item shown twice. That is a stronger
 * "you are now dragging" signal than an armed-but-motionless hold should give; a phone report
 * confirmed it reads that way even with every destination affordance still correctly withheld
 * (2026-08-19). The ghost earns its place once the finger actually moves.
 */
@Composable
fun BoxScope.LkDragOverlay(
    session: LkDragSession,
    content: @Composable (LkDragPayload) -> Unit,
) {
    if (!session.isMoving) return
    val payload = session.payload ?: return
    val reduceMotion = LocalLkReduceMotion.current

    /**
     * Where this overlay's own box starts, in root coordinates.
     *
     * `offset` places relative to the parent, and the finger is reported in root space — the screen
     * root sits below the status bar, so offsetting by a root position drew the ghost exactly that
     * far below the finger. Measured on emulator-5554 (2026-08-18): a finger at y=1419 carried a
     * ghost whose top edge was at y=1488, a 132 px gap that is the status bar's height and the same
     * mismatch `lkDragTracker` exists to correct on the way in.
     *
     * `matchParentSize` rather than `fillMaxSize`, so the probe reads the container without
     * influencing what the container measures to.
     */
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    // The same probe reports the box's size, which is what the ghost is kept inside of below.
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    Spacer(
        Modifier.matchParentSize().onGloballyPositioned {
            overlayOrigin = it.boundsInRoot().topLeft
            overlaySize = it.size
        },
    )

    /**
     * The ghost's own size, so the grab point can be clamped into it.
     *
     * [LkDragSession.grabOffset] is measured against the **row**, and the ghost is deliberately not
     * a clone of the row — it is a compact plate, because a full-width row under a finger covers
     * the targets it is being aimed at. So the row's grab point routinely falls outside the plate:
     * grabbing a 1016 px row near its right edge and dragging to the left of the screen put the
     * plate entirely off the left edge, with the user carrying something they could no longer see
     * (emulator-5554, 2026-08-18). Clamping keeps the promise the field's own KDoc makes — the
     * ghost stays under the finger — for a ghost that is smaller than what was grabbed.
     */
    var ghostSize by remember { mutableStateOf(IntSize.Zero) }
    val grab = Offset(
        x = session.grabOffset.x.coerceIn(0f, ghostSize.width.toFloat()),
        y = session.grabOffset.y.coerceIn(0f, ghostSize.height.toFloat()),
    )
    val topLeft = ghostTopLeft(
        finger = session.fingerPosition,
        grab = grab,
        overlayOrigin = overlayOrigin,
        overlaySize = overlaySize,
        ghostSize = ghostSize,
        fingerGap = with(LocalDensity.current) { GHOST_FINGER_GAP.toPx() },
    )

    // The lift, and it expresses a cause rather than decorating one: the ghost grows and lifts off
    // the list at the moment the drag arms, so "this row has left the list and is now in your hand"
    // is legible without reading anything. Under reduced motion it is simply at rest at full size —
    // the ghost still appears and still follows the finger, because that is the interaction, not an
    // embellishment on it.
    val scale by animateFloatAsState(
        targetValue = if (reduceMotion) 1f else GHOST_SCALE,
        animationSpec = lkTween(LkMotion.Quick, easing = LkMotion.Emphasized),
        label = "drag-ghost-lift",
    )
    val elevation by animateFloatAsState(
        targetValue = if (reduceMotion) 0f else 1f,
        animationSpec = lkTween(LkMotion.Quick, easing = LkMotion.Emphasized),
        label = "drag-ghost-shadow",
    )

    Box(
        Modifier
            // Position is deliberately **not** animated. The ghost must track the finger, and a
            // tween on its offset is a ghost that lags behind the thing it is attached to — which
            // reads as dropped frames rather than as motion design.
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .scale(scale)
            .alpha(GHOST_ALPHA)
            .shadow(
                elevation = (GHOST_ELEVATION * elevation),
                shape = LkShape.lg,
                clip = false,
            )
            .onSizeChanged { ghostSize = it }
            .decorative(),
    ) {
        content(payload)
    }
}

/**
 * Where the ghost's top-left goes: [fingerGap] above the finger, and inside the overlay.
 *
 * **Above the finger, not under it** (S2-F-20, 2026-10-19). The ghost used to sit exactly under the
 * fingertip, which is the one place the user is aiming — it covered the folder label the row was
 * being dragged onto, so the drop was made blind. Its bottom edge now clears the finger by
 * [fingerGap]; only the horizontal grab point is kept, so the plate still tracks the finger sideways.
 * Drop hit-testing is untouched and stays on the finger position (`LkDragSession.fingerPosition`),
 * never on the ghost: the target under the fingertip is the one that receives the drop.
 *
 * Clamping the *grab point* into the ghost (see [LkDragOverlay]) stops the plate leaving the finger
 * behind; it does not stop the finger carrying the plate off the screen. With a pointer near x=140
 * the pill was drawn starting at x<0 (2C-F7, on a device 2026-09-20), so the user was holding
 * something they could only partly see — the same failure the grab clamp was written for, one step
 * further out. Both axes, because a ghost half off the top edge is the identical defect. With the
 * lift, a finger near the top of the screen is the case that bites: the ghost is held on screen and
 * so ends up over the finger there, which beats a ghost nobody can see.
 *
 * Unclamped until both boxes have been measured: on the first frame [overlaySize] and [ghostSize]
 * are zero, and clamping to a zero-width range would pin the ghost to the corner for that frame.
 *
 * Pure so the arithmetic can be pinned without a device — `LkDragGhostClampTest`.
 */
internal fun ghostTopLeft(
    finger: Offset,
    grab: Offset,
    overlayOrigin: Offset,
    overlaySize: IntSize,
    ghostSize: IntSize,
    fingerGap: Float,
): Offset {
    // `grab.y` is deliberately unused: the ghost is not anchored to where on the row the finger landed.
    val wanted = Offset(
        x = finger.x - grab.x - overlayOrigin.x,
        y = finger.y - fingerGap - ghostSize.height - overlayOrigin.y,
    )
    if (overlaySize == IntSize.Zero || ghostSize == IntSize.Zero) return wanted
    return Offset(
        x = wanted.x.coerceIn(0f, (overlaySize.width - ghostSize.width).coerceAtLeast(0).toFloat()),
        y = wanted.y.coerceIn(0f, (overlaySize.height - ghostSize.height).coerceAtLeast(0).toFloat()),
    )
}

/** Clear space between the ghost's bottom edge and the fingertip, so the target stays readable. */
private val GHOST_FINGER_GAP = 32.dp

/** How far the ghost lifts off the list, at rest under reduced motion. */
private val GHOST_ELEVATION = 12.dp

/** The lift: the same 1.03 the row already scales to when pressed. */
private const val GHOST_SCALE = 1.03f

/** Just short of opaque, so the row it is passing over stays readable underneath. */
private const val GHOST_ALPHA = 0.96f

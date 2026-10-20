package com.localkeep.core.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.R
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.lkExposeTestTags
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Modals: the two ways this app interrupts.
 *
 * Both existed only as Material components before, tinted with [LkColors.dialogTop] and otherwise
 * stock — which is why a dialog raised from a restyled screen looked like it came from a different
 * app. Material's `AlertDialog` cannot be talked into this design: it wants a start-aligned title, a
 * horizontal row of text buttons, its own paddings, and a flat container where everything else here
 * is a gradient behind a hairline ring.
 *
 * So the chrome lives here, once, and the difference between two dialogs is their content.
 *
 * **On windows.** A dialog and a sheet each get their own platform window, which does *not*
 * automatically carry the activity's `FLAG_SECURE`. Both default to `SecureFlagPolicy.Inherit`,
 * which copies it from the parent — do not override it. A dialog showing a revealed secret in the
 * app switcher would be a hole in the one guarantee this app makes everywhere else.
 */

/** What a dialog's confirm button is: which weight it takes, and what it does. */
data class LkDialogAction(
    val label: String,
    val onClick: () -> Unit,
    val emphasis: LkActionEmphasis = LkActionEmphasis.Primary,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
)

enum class LkActionEmphasis {
    /** Jade. The safe, expected thing. */
    Primary,

    /** Brass. A purchase — which must never look like a security action. */
    Pro,

    /**
     * Coral, and tinted rather than filled.
     *
     * Deliberately *not* the bright primary fill: jade is the app's "this is safe" colour and
     * putting "Delete forever" in it would be a lie told in the most legible object on screen. The
     * label always says what is destroyed, so colour is not carrying the warning alone.
     */
    Destructive,
}

/**
 * The app's dialog.
 *
 * Vertical actions, not Material's horizontal button row: the buttons here are full-width by design
 * (they are the same [LkPrimaryButton] the rest of the app uses), and a stack keeps the confirm
 * label readable however long it is — "Delete forever" and "Unlock Pro — €9.99" both fit.
 *
 * [icon] defaults to the product mark. That is the answer to "what is this dialog?" when the dialog
 * is the app speaking for itself; pass a glyph and an [accent] when it is about a specific thing,
 * and the header carries that instead.
 */
@Composable
fun LkDialog(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    accent: Color = LkTheme.colors.jade,
    confirm: LkDialogAction? = null,
    // A resource, not the literal it was: a default argument is still user-facing text, and this
    // one reached nine dialogs — every LkDialog that does not name its own dismiss — which all
    // said "Cancel" in French, German, Spanish and Portuguese. `checkNoHardcodedUiStrings` matched
    // `dismissLabel = "..."` at call sites and could not see the declaration; it can now.
    dismissLabel: String? = stringResource(R.string.core_ui_dialog_cancel),
    /** False for a dialog that must be answered — tapping outside and Back both stop working. */
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            // Material's dialog width is too narrow for a stacked action; we set our own bounds.
            usePlatformDefaultWidth = false,
        ),
    ) {
        // A dialog is its own window and gets no padding from the activity, so a dialog holding a
        // text field — folder rename, the passphrase prompts — would sit under the keyboard with
        // its confirm button unreachable and no way to scroll to it.
        LkDialogSurface(modifier = modifier.imePadding()) {
            LkModalMasthead(
                title = title,
                body = body,
                icon = icon,
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
            )
            content()
            if (confirm != null || dismissLabel != null) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (confirm != null) LkDialogConfirm(confirm)
                    if (dismissLabel != null) {
                        LkTextButton(dismissLabel, onDismiss, modifier = Modifier.lkTag("dialog-dismiss"))
                    }
                }
            }
        }
    }
}

@Composable
private fun LkDialogConfirm(action: LkDialogAction) {
    val c = LkTheme.colors
    // One tag for all three emphases: what the sweep needs is "answer this dialog", and which
    // button style the answer happens to wear is not something a flow should have to know.
    val tag = Modifier.lkTag("dialog-confirm")
    when (action.emphasis) {
        LkActionEmphasis.Primary -> LkPrimaryButton(
            text = action.label,
            onClick = action.onClick,
            enabled = action.enabled,
            icon = action.icon,
            modifier = tag,
        )

        LkActionEmphasis.Pro -> LkPrimaryButton(
            text = action.label,
            onClick = action.onClick,
            enabled = action.enabled,
            accent = LkButtonAccent.Brass,
            icon = action.icon,
            modifier = tag,
        )

        LkActionEmphasis.Destructive -> LkTintButton(
            text = action.label,
            onClick = action.onClick,
            accent = c.coral,
            // This branch was the only one of the three that dropped `action.enabled`, so
            // `LkDialogAction.enabled` was silently inert on exactly the dialogs where it matters
            // most — every destructive confirmation in the app. Measured on device 2026-09-20 on
            // the snapshot-restore gate: `dialog-confirm` dumped `enabled="true"` with its
            // password well empty, and the tap went through to a refusal instead of being
            // ignored. The duress setup and removal confirmations pass the same argument and were
            // equally unguarded. `LkTintButton` has carried the parameter since the Replace
            // control needed it; nothing here was passing it on.
            enabled = action.enabled,
            icon = action.icon,
            height = 54.dp,
            shape = LkShape.xl,
            modifier = tag,
        )
    }
}

/**
 * The dialog's container, without the header/action opinions.
 *
 * Exposed on its own for the modals that are not a question — the licence list, the QR scanner —
 * which need the same surface, entrance and bounds but supply their whole body themselves.
 */
@Composable
fun LkDialogSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LkTheme.colors
    val scroll = rememberScrollState()
    // The bound this surface shipped without, and the reason it needs one.
    //
    // There was a `widthIn` and nothing at all on the other axis, so a dialog whose content
    // outgrew the display neither clipped nor scrolled: the Column kept laying out, the window
    // clamped it at its own height, and the confirm and dismiss buttons ended up stacked on top of
    // each other against the bottom edge with the body text cut off above them. Measured here, on
    // a 360x640dp phone at font scale 2.0 with a two-sentence body: both buttons reported the same
    // bottom, 614dp — the same pixel, which is what "the actions are gone" looks like from a
    // semantics tree. The device signature is reported to be worse still — the window centres on
    // content taller than the display, so the masthead leaves through the top while the buttons
    // leave through the bottom — but that is a report, not something measured here. Either way
    // there is no gesture out of it, and a dialog raised with `dismissible = false` has no Back.
    //
    // Both halves are needed and neither is sufficient. A scroll with no height limit is exactly
    // as tall as its content, so nothing ever scrolls; a height limit with no scroll just moves
    // where the content is severed. `FolderDeleteDialogLocaleTest` named this gap in writing while
    // guarding one dialog's strings against it; this closes it for all of them.
    //
    // The IME term does not double-count the `imePadding()` [LkDialog] applies one level up. That
    // one moves the surface clear of the keyboard; this one shrinks what the surface may claim, so
    // there is somewhere clear to move it to. Without it a tall dialog over an open keyboard is
    // pushed off the top rather than off the bottom, which is not an improvement.
    val visible = LocalConfiguration.current.screenHeightDp.dp -
        with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    // One rung of margin above and below — the same order as the 24dp the surface already keeps on
    // its left and right, so a dialog that has to take the whole screen still reads as a dialog
    // sitting on it rather than a page.
    val maxHeight = (visible - LkSpace.lg * 2).coerceAtLeast(LkSpace.xl)
    Column(
        modifier = modifier
            // A dialog is its own window, so it does not inherit the flag LkScreen sets. Debug
            // builds only — see lkExposeTestTags.
            .lkExposeTestTags()
            .padding(horizontal = 24.dp)
            // 380dp is where the stacked buttons stop looking stranded on a tablet; the lower bound
            // keeps the masthead from wrapping "LocalKeep" on a 320dp-wide phone.
            .widthIn(min = 260.dp, max = 380.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .lkAppear()
            .lkSurface(
                LkShape.dialog,
                Brush.verticalGradient(listOf(c.dialogTop, c.dialogBottom)),
                c.ringStrong,
            )
            // Inside the surface so the gradient and its ring hold still while the content moves,
            // and after the bound so there is a viewport smaller than the content for it to move
            // in. A dialog that fits is untouched: `heightIn` is a maximum, and a scroll container
            // whose content is shorter than its viewport has nothing to scroll. The ordinary
            // delete confirmation measures the same 332.5dp with this in place as without it.
            .verticalScroll(scroll)
            .padding(horizontal = 22.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/**
 * Material's own drag thresholds for a bottom sheet, transcribed because they are `internal`.
 *
 * `BottomSheetDefaults.PositionalThreshold` and `.VelocityThreshold` are 56dp and 125dp in
 * material3 1.4.0 and are what `rememberModalBottomSheetState` hands every sheet in the app.
 * [LkSheet] has to build its own `SheetState` to control the initial value, which means passing
 * these itself, which means having them. Copying two numbers out of a library rots silently: if a
 * Material upgrade retunes a sheet's drag feel, this sheet keeps the old feel and nothing says so.
 * Re-check them against `BottomSheetDefaults` when material3 moves.
 */
internal val LkSheetPositionalThreshold = 56.dp
internal val LkSheetVelocityThreshold = 125.dp

/**
 * The [SheetState] [LkSheet] composes, factored out of it on 2026-09-13 so a configuration-change restoration test can drive it
 * without a full [ModalBottomSheet] window.
 *
 * Was a plain `remember(startExpanded, alreadyOpen)`, which a configuration change discards
 * outright: every one of this app's ~9 [LkSheet]-based sheets — add-item, vault-list filter, tag,
 * hide/duration, identity-capture disclosure, item-naming, hidden-items management — rebuilt from
 * scratch at `SheetValue.Hidden` on rotation, because [alreadyOpen] is seeded true only on
 * AddItemSheet's own nav-cancel-return path (`AddItemSheet.kt:262`); every other call site defaults
 * it false. `MainActivity` declares no `android:configChanges`, so the recreation is real, and
 * `ModalBottomSheet` then runs its own unconditional `sheetState.show()` `LaunchedEffect` against
 * that fresh Hidden state — replaying the sheet's ~600ms rise (`AddItemSheet.kt:274-275`) and
 * dropping a half-dragged detent back to Expanded/PartiallyExpanded, on every rotation.
 *
 * `rememberSaveable` with [SheetState.Saver] restores the actual last-known [SheetValue] instead
 * of forcing one. It is still seeded from [alreadyOpen] only on the very first composition (the
 * `init` lambda below) — the nav-return contract [alreadyOpen] documents is unchanged; a real
 * restore skips `init` entirely and rebuilds the state at the value the [SheetState.Saver] saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberLkSheetState(startExpanded: Boolean, alreadyOpen: Boolean): SheetState {
    val density = LocalDensity.current
    return rememberSaveable(
        startExpanded,
        alreadyOpen,
        saver = SheetState.Saver(
            startExpanded,
            { with(density) { LkSheetPositionalThreshold.toPx() } },
            { with(density) { LkSheetVelocityThreshold.toPx() } },
            { true },
            false,
        ),
    ) {
        SheetState(
            skipPartiallyExpanded = startExpanded,
            positionalThreshold = { with(density) { LkSheetPositionalThreshold.toPx() } },
            velocityThreshold = { with(density) { LkSheetVelocityThreshold.toPx() } },
            initialValue = if (alreadyOpen) SheetValue.Expanded else SheetValue.Hidden,
        )
    }
}

/**
 * The app's bottom sheet.
 *
 * The chrome — drag handle, gradient, title block, corner radius — was written once inside the add
 * sheet and had no way to reach any other sheet. It lives here now so the second and third sheet
 * cannot drift, which is the whole failure mode this pass exists to fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LkSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    startExpanded: Boolean = false,
    /**
     * Puts the sheet on screen with no entrance — already open, at rest, on the first frame.
     *
     * For a sheet that is being *restored* rather than opened: the user raised it, chose a row, and
     * came straight back out. The sheet was already open as far as they are concerned, so replaying
     * its rise is the app announcing an arrival at somewhere the user never left. Reported five
     * times before it was placed correctly, and the last report is the one that located it: *"I open
     * a folder/item view from + sign (any), I immediately go back and then the Add to vault view
     * with all items and folders appears again from the bottom (the animation)"*. The four fixes
     * before it were all aimed at navigation transitions; this is a sheet, and it animates itself.
     *
     * `ModalBottomSheet` has no way to say "skip the entrance" — it runs `sheetState.show()` in a
     * `LaunchedEffect` on first composition unconditionally. What it does have is a `SheetState`
     * whose initial value the caller chooses, and `show()` animating to a value the state is already
     * at is a no-op. So the sheet is handed a state that starts [SheetValue.Expanded] and there is
     * nothing left for the entrance to do. That is also why this cannot be built on
     * `rememberModalBottomSheetState`, which hard-codes `initialValue = Hidden`.
     */
    alreadyOpen: Boolean = false,
    /**
     * Plays the sheet's exit instead of taking it off screen between two frames.
     *
     * Dropping a sheet from the composition removes its whole window at once: the scrim goes from
     * full to nothing in a single frame, and whatever the sheet was covering is left bare and at
     * full brightness until the destination the user just chose has drawn. Measured from a phone
     * recording, 2026-08-21, at 55 fps: the vault list's mean luminance stepped 24.2 → 34.0 between
     * two frames on the tap and held there for five more before the new screen appeared. That bright
     * step, and its twin on the way back, are what was reported as *"the transition is weird there's
     * a flickering it's not smooth"*.
     *
     * So the caller says the sheet is *leaving* rather than deleting it, navigates in the same
     * breath, and Material's own exit — the sheet sliding down, the scrim fading — plays over the
     * arriving screen. [onDismiss] fires when that exit finishes, and dropping it from the
     * composition then is free: there is nothing left on screen to lose.
     */
    closing: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LkTheme.colors
    val scroll = rememberScrollState()
    // Built rather than remembered from `rememberModalBottomSheetState` — see [alreadyOpen]. The two
    // thresholds are what a drag has to beat to change the sheet's detent, and they are transcribed
    // rather than referenced because `BottomSheetDefaults.PositionalThreshold` and its velocity twin
    // are `internal` to Material — the same values `rememberModalBottomSheetState` passes, so this
    // sheet still drags exactly like every other one. `rememberSaveable`-backed since 2026-09-13
    // — see [rememberLkSheetState]'s KDoc for why a plain `remember` was wrong.
    val sheetState = rememberLkSheetState(startExpanded, alreadyOpen)
    // See [closing]. `hide()` suspends for the length of the exit, so [onDismiss] runs when the
    // sheet is actually off screen rather than when the caller decided it should be.
    LaunchedEffect(closing) {
        if (closing) {
            sheetState.hide()
            onDismiss()
        }
    }
    // Rows wait for the sheet to come to rest. A double tap on whatever raised it otherwise landed
    // its second tap on the row that had just slid under the finger — the add sheet's "File",
    // opening an editor nobody chose (QA 2026-10-03). Only the rise is guarded; a sheet already at
    // rest, or restored open ([alreadyOpen]), answers at once.
    val rising = sheetState.currentValue == SheetValue.Hidden && sheetState.targetValue != SheetValue.Hidden
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // `SheetState` itself is still deliberately not a parameter: it is an experimental Material
        // type, and exposing it would push an `@OptIn` onto every call site — exactly the kind of
        // Material detail this wrapper exists to keep out of feature modules. [startExpanded] and
        // [alreadyOpen] are the narrow, non-experimental way to ask for the two behaviours a caller
        // actually needs. The first is a list too long for the half-height detent, which otherwise
        // opens showing four of its nine rows with the fifth clipped mid-height — a rendering edge,
        // not an invitation to scroll. Reported from a phone, 2026-08-19: "'Location' doesn't open
        // location".
        sheetState = sheetState,
        shape = LkShape.sheet,
        containerColor = c.sheetTop,
        scrimColor = c.scrim,
        dragHandle = {
            // Named, because this Box replaces `BottomSheetDefaults.DragHandle` and that one
            // carries a contentDescription. Material wraps whatever this slot emits in a node that
            // merges its descendants and holds the expand/collapse/dismiss actions — so with a
            // nameless Box inside, TalkBack landed on a focusable, clickable node and said nothing
            // (`uiautomator` reported NAF on every sheet in the app, device 2026-09-20). The
            // description lands on the merged node exactly as Material\'s own does.
            val handleDescription = stringResource(R.string.core_ui_sheet_drag_handle)
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 14.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .background(c.washStrong, LkShape.xs)
                    .semantics { contentDescription = handleDescription },
            )
        },
    ) {
        Box(
            if (rising) {
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            } else {
                Modifier
            },
        ) {
            Column(
                modifier
                    // A sheet is its own window too — same reason as LkDialogSurface.
                    .lkExposeTestTags()
                    .background(Brush.verticalGradient(listOf(c.sheetTop, c.cardBottom)))
                    // 32.dp is visual spacing and says nothing about the keyboard; a sheet with a text
                    // field in it needs the real IME height on top of that.
                    .imePadding()
                    // Scrollable, because a sheet's content is not always shorter than the screen.
                    // Measured on a 1080x2400 device: the add-item sheet's six rows plus its header ran
                    // the last row to y=2400 exactly, with its subtitle clipped to 22 of 40 pixels and
                    // no way to reach the rest — the sheet simply ended at the edge of the display.
                    // A sheet that fits is unaffected; one that does not now scrolls instead of being
                    // cut off, which also covers the taller text sizes and shorter screens this was
                    // always one step away from on.
                    .verticalScroll(scroll)
                    .padding(start = 10.dp, end = 10.dp, bottom = 32.dp),
            ) {
                if (title != null) {
                    Column(
                        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(title, style = LkText.sheetTitle, color = c.textStrong)
                        if (subtitle != null) {
                            Text(subtitle, style = LkText.subtitle, color = c.textFaint)
                        }
                    }
                }
                content()
            }
            // The overflow affordance. Even at the full detent a list can outrun the screen — a
            // small display, a large font scale, a language with taller rows — and the failure mode
            // is not that the user cannot scroll but that nothing tells them there is anything to
            // scroll to: the last visible row is cut off flush with the display edge, which reads
            // as the end of the list. A fade means the content is running under something rather
            // than stopping at it. Drawn only while there is more below, so a sheet that fits is
            // visually unchanged, and not clickable so it cannot eat a tap on the row beneath it.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (scroll.canScrollForward) 28.dp else 0.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, c.cardBottom))),
            )
        }
    }
}

/**
 * A dialog's entrance: fade in while growing the last 5%.
 *
 * Distinct from [lkEnter], which travels upward — a dialog has no direction to come from, so it
 * arrives in place on [LkMotion.Emphasized]'s slight overshoot. Collapses to a single frame under
 * reduced motion like everything else, so the dialog is simply *there*.
 */
@Composable
private fun Modifier.lkAppear(): Modifier {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = lkTween(LkMotion.Dialog, easing = LkMotion.Settle),
        label = "dialog-appear",
    )
    val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) { DialogTravel.toPx() }
    val overshootPx = with(androidx.compose.ui.platform.LocalDensity.current) { DialogOvershoot.toPx() }
    return this
        // Alpha finishes at the overshoot rather than at rest: the last 30% of this animation is the
        // settle, and a dialog still fading in while it settles reads as a slow dialog.
        .alpha((progress / DialogPeak).coerceAtMost(1f))
        .graphicsLayer {
            // The comp's `ifDialog` keyframes: 0% at +20dp / .96, 70% at −2dp / 1.004, 100% at rest.
            // The overshoot is 4 thousandths of scale and two dp of travel — far too small to read
            // as a bounce, and exactly enough that the dialog arrives instead of stopping.
            val peaked = progress >= DialogPeak
            val leg = if (peaked) (progress - DialogPeak) / (1f - DialogPeak) else progress / DialogPeak
            translationY = if (peaked) -overshootPx * (1f - leg) else travelPx * (1f - leg) - overshootPx * leg
            val s = if (peaked) 1.004f - 0.004f * leg else 0.96f + 0.044f * leg
            scaleX = s
            scaleY = s
        }
}

/** `ifDialog`'s keyframe positions: the travel it starts from, and the 2dp it overshoots by. */
private val DialogTravel = 20.dp
private val DialogOvershoot = 2.dp
private const val DialogPeak = 0.7f

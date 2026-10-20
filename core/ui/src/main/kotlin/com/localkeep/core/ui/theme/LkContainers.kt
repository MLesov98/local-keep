package com.localkeep.core.ui.theme

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.scaleToBounds
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.localkeep.core.ui.lkExposeTestTags
import com.localkeep.core.ui.shellOverlayInset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.rememberHapticClick

/**
 * Containers: the four ways this design has of putting something on the ground.
 *
 * Keeping it to four — screen, card, lifted card, grouped list — is what stops surfaces from
 * drifting apart across feature modules. A screen that needs a fifth kind of box almost always
 * wants one of these with different content.
 */

/** A full screen: the ground, plus the accent aura behind the header. */
@Composable
fun LkScreen(
    modifier: Modifier = Modifier,
    accent: Color = LkTheme.colors.jade,
    auraAlpha: Float = 0.09f,
    auraBias: Float = 0.5f,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LkTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            // On the existing root rather than a wrapper, so the tree gains no node. Debug builds
            // only — see lkExposeTestTags.
            .lkExposeTestTags()
            .background(c.ground)
            .lkAuraTop(accent, auraAlpha, auraBias),
        content = content,
    )
}

/**
 * What a screen with a fixed header and pinned actions does when the keyboard leaves it too little
 * room: the header and the actions stop being pinned and scroll with the content, so the field the
 * user is typing into gets everything that is left. Pinned again the moment the keyboard closes.
 *
 * ONE rule, read from here by every screen that arranges itself this way — [LkActionScaffold]
 * publishes it as [LocalLkChromeUnpinned], and the screens that build their own frame (onboarding's
 * `StepColumn`, [LkFolderPickerScreen], the vault list's search header) call
 * [rememberLkKeyboardUnpinsChrome] directly — so the screens cannot disagree about when.
 *
 * Why it exists. A pinned header plus a pinned action bar plus `imePadding()` is three claims on one
 * window, and the scroll region in the middle gets what the other two leave. On a 308dp-wide window
 * at the largest display size (548dp tall, ~250dp of keyboard) that is 0dp: the New folder screen
 * lost its name field, Unlock lost its password well and onboarding's password step could only be
 * typed blind (SCL-001, SCL-003, SCL-006; landscape and 2.0 text size reach the same state).
 * Typing still worked, which is what made it read as a harness fault rather than as a defect.
 *
 * Decided by the room above the keyboard, not by the keyboard alone: with ordinary room, a Save
 * button sitting just over the keys is the better arrangement and stays (a Pixel-sized portrait
 * window has ~590dp above the keyboard). See [keyboardUnpinsChrome] for the threshold.
 */
@Composable
fun rememberLkKeyboardUnpinsChrome(): Boolean {
    val density = LocalDensity.current
    val imePx = WindowInsets.ime.getBottom(density)
    if (imePx <= 0) return false
    val windowHeight = LocalConfiguration.current.screenHeightDp.dp
    return keyboardUnpinsChrome(
        visibleHeight = windowHeight - with(density) { imePx.toDp() },
        fontScale = density.fontScale,
    )
}

/**
 * The height left above the keyboard, or the whole window when there is none. For callers that
 * have to cap a region at a share of it ([rememberLkKeyboardUnpinsChrome] says only whether).
 */
@Composable
fun rememberLkHeightAboveKeyboard(): Dp {
    val density = LocalDensity.current
    val imePx = WindowInsets.ime.getBottom(density)
    return LocalConfiguration.current.screenHeightDp.dp - with(density) { imePx.toDp() }
}

/**
 * True when [visibleHeight] — the window minus the keyboard — is under what a screen's pinned
 * chrome needs at this text size.
 *
 * The budget is 400dp at font scale 1.0 and grows with the scale, because the chrome is text: a
 * header, a field label and a button are about as tall again at 2.0, while the window is the same
 * number of dp. 400dp is measured, not asked for: it keeps the Unlock and item-editor arrangement
 * unchanged on a 360x800 window at 1.0 (500dp above the keyboard), and takes it over on a
 * 308x548 window (about 300dp) at any scale — the window the defect was found on.
 */
internal fun keyboardUnpinsChrome(visibleHeight: Dp, fontScale: Float): Boolean =
    visibleHeight < KeyboardChromeBudget * fontScale.coerceAtLeast(1f)

private val KeyboardChromeBudget = 400.dp

/**
 * [rememberLkKeyboardUnpinsChrome], as [LkActionScaffold] saw it. Read inside the scaffold's
 * `action` and `content` slots: a screen that adopts the rule leaves its action slot empty while
 * this is true and emits the same actions at the end of its scrolling content instead.
 *
 * Always provided and never acted on by the scaffold itself, because the scaffold cannot move
 * actions into a content slot it does not own: several callers' content already scrolls, and a
 * scroll inside a scroll throws. A caller that has not adopted the rule is unchanged.
 */
val LocalLkChromeUnpinned = compositionLocalOf { false }

/**
 * A screen whose content scrolls under a primary action that stays put.
 *
 * The one layout rule this app kept getting wrong. Every editor used to be a single scrolling
 * column with Save as its last child, which makes the action's position a function of how many
 * fields the item type happens to have: on a new Login, Save sat four full swipes below the fold
 * *before* the keyboard was even open, and touching any field pushed it further away. A user's
 * report of "the button was below the keyboard, I had to scroll to find it" was this.
 *
 * [action] is laid out last and measured first, so [content] gets exactly the height that is left.
 * `imePadding()` on the action is what lifts it above the keyboard, and it is applied *only* here —
 * padding a whole screen by the IME shrinks the content box instead, which crushes any bounded
 * region inside it rather than scrolling it (see MainActivity's note).
 *
 * **On a window too short for that, the action stops being pinned while the keyboard is up** — see
 * [rememberLkKeyboardUnpinsChrome]. This scaffold decides and publishes the answer as
 * [LocalLkChromeUnpinned]; a screen whose content scrolls adopts it by emitting its actions inside
 * that scroll instead of in [action] while it is true (`UnlockFrame` is the worked example).
 */
@Composable
fun LkActionScaffold(
    modifier: Modifier = Modifier,
    accent: Color = LkTheme.colors.jade,
    auraAlpha: Float = 0.09f,
    auraBias: Float = 0.5f,
    /**
     * Left and right margin of the action column.
     *
     * A parameter because it has to agree with whatever the *content* above it uses, or the primary
     * button's edges miss the wells it sits under by a few dp — which is the kind of thing nobody
     * can name and everybody can see. The item flows pass [LkFrame.horizontal] (26dp, the comp's
     * screen padding); list-framed screens keep the default.
     */
    horizontalPadding: Dp = LkFrame.screenHorizontal,
    /** Gap between stacked actions. `Item Flows.dc.html` stacks Save + a secondary at `gap:8px`. */
    actionSpacing: Dp = 0.dp,
    action: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LkTheme.colors
    // Lifted over the shell's floating overlays, as the vault list's FAB and the generator already
    // are: copying a password from an item editor put the clipboard countdown over Save for the
    // length of the countdown (QA 2026-10-03). Animated so the bar arrives with the card it avoids.
    val overlayLift by animateDpAsState(shellOverlayInset(), lkTween(LkMotion.Base), label = "action-overlay-lift")
    val unpinned = rememberLkKeyboardUnpinsChrome()
    CompositionLocalProvider(LocalLkChromeUnpinned provides unpinned) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .lkExposeTestTags()
                .background(c.ground)
                .lkAuraTop(accent, auraAlpha, auraBias),
        ) {
            Column(Modifier.weight(1f), content = content)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.ground)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(bottom = overlayLift)
                    .padding(horizontal = horizontalPadding)
                    .paddingUnlessEmpty(top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(actionSpacing),
                content = action,
            )
        }
    }
}

/**
 * Vertical padding that is only paid when the element has something in it.
 *
 * A screen that adopts the unpinned rule leaves [LkActionScaffold]'s action slot empty on purpose,
 * because the empty bar's `imePadding()` is what reserves the keyboard. The 10dp above and 14dp
 * below it were still applied, so the scroll region stopped 24dp short of the keys: a dead band, and
 * in landscape the difference between a 136px viewport and a 192px one (L-01).
 */
private fun Modifier.paddingUnlessEmpty(top: Dp, bottom: Dp): Modifier = layout { measurable, constraints ->
    val vertical = (top + bottom).roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = -vertical))
    if (placeable.height == 0) {
        layout(placeable.width, 0) { placeable.place(0, 0) }
    } else {
        layout(placeable.width, placeable.height + vertical) { placeable.place(0, top.roundToPx()) }
    }
}

/** A flat raised block. The default container for a group of controls. */
@Composable
fun LkCard(
    modifier: Modifier = Modifier,
    shape: Shape = LkShape.xxl,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LkTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .lkSurface(shape, c.surface, c.ring)
            .padding(padding),
        content = content,
    )
}

/**
 * A card with the lifted gradient — visibly the most important object on its screen.
 *
 * Reserved for the one block a screen is *about*: the generator's output, the health score, the
 * TOTP code. Using it twice on a screen destroys the distinction, which is the only thing it does.
 */
@Composable
fun LkLiftedCard(
    modifier: Modifier = Modifier,
    shape: Shape = LkShape.card,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LkTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .lkSurface(shape, c.cardFill, c.ringStrong)
            .padding(padding),
        content = content,
    )
}

/**
 * A grouped list: rows sharing one rounded container, separated by hairlines rather than gaps.
 *
 * The separator is drawn by the *row* ([LkGroupRow]) as a top edge, and suppressed on the first
 * one — so a group renders correctly no matter how many rows it turns out to have, including one.
 */
@Composable
fun LkGroup(
    modifier: Modifier = Modifier,
    shape: Shape = LkShape.xxl,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LkTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .lkSurface(shape, c.surface, c.ring)
            .clip(shape),
        content = content,
    )
}

/**
 * A group of *form fields* sharing one rounded container.
 *
 * The read and edit views of the same item were built to different rules: read mode chunks its
 * fields into [LkGroup] surfaces, edit mode left them loose on the ground with only a [Spacer]
 * between each one. The same data was therefore heavier and more fragmented while being edited than
 * while being read — each field's own uppercase label reading as its own unit of attention, because
 * nothing told the eye which labels belonged together.
 *
 * Distinct from [LkGroup], which is for full-bleed rows divided by hairlines and so carries no
 * padding of its own. A field group pads its edges and owns the gap between its children, because a
 * well needs air on both sides of its ring and every form that set that gap by hand set it
 * differently.
 *
 * The gap belongs to the container for the same reason: a caller that emits several children into
 * this Column gets them all spaced by [Arrangement.spacedBy], including any `Spacer` it adds — so
 * anything emitting more than one child must wrap itself rather than reach for a Spacer.
 */
@Composable
fun LkFieldGroup(
    modifier: Modifier = Modifier,
    shape: Shape = LkShape.xxl,
    padding: Dp = LkSpace.md,
    spacing: Dp = LkSpace.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LkTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .lkSurface(shape, c.surface, c.ring)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

private val noOpClick: () -> Unit = {}

/** A row inside an [LkGroup]. [first] suppresses the leading separator. */
@Composable
fun LkGroupRow(
    first: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    // A long-press action on the same row. When set, the row uses `combinedClickable` and,
    // deliberately, no press-wash: the long-press detector holds the PressInteraction open until its
    // timeout, so a wash tracking that interaction would linger ~1s after the finger lifts. Tap-only
    // rows keep the wash and their exact prior behavior.
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    /** The long-click's name for a screen reader, read from the actions menu the same way
     *  [onClickLabel] is read for the primary one. [LkLockButton]'s tap/panic-lock pair is the one
     *  place in `main` that already supplies both labels — this mirrors it. */
    onLongClickLabel: String? = null,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 15.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    /**
     * The gap between the row's children. `Start` — no gap — is what this row has always drawn, and
     * it is right for the shape nearly every caller uses: a `weight(1f)` text column with a trailing
     * chevron, where the weight itself does the separating.
     *
     * It is wrong, silently, for a row that *leads* with an icon. There the plate and the text are
     * adjacent children with nothing between them, and the first such row shipped with the glyph
     * touching the title (hidden items, reported from a phone 2026-08-21: *"where's the spacing?
     * Look how stacked they are"*). A caller had no way to say otherwise short of a hand-placed
     * `Spacer`, so this is the token-shaped way to say it.
     */
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    haptic: Haptic? = Haptic.SELECT,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    // Unconditional, and `noOpClick` is a stable top-level reference rather than a fresh `{}` — a
    // composable call may not be skipped on some recompositions and made on others, and a new
    // lambda identity every pass would defeat the `remember` inside it.
    val click = rememberHapticClick(haptic, onClick ?: noOpClick)
    Column(Modifier.fillMaxWidth()) {
        if (!first) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.separator))
        }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    when {
                        // Square: the row is a full-bleed strip inside the group's rounded card, so
                        // a rounded focus ring would sit inside its own edges and read as a chip.
                        onLongClick != null && onClick != null ->
                            Modifier
                                .lkFocusRing(RectangleShape)
                                .combinedClickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    role = Role.Button,
                                    onClickLabel = onClickLabel,
                                    onClick = click,
                                    onLongClick = onLongClick,
                                    onLongClickLabel = onLongClickLabel,
                                )
                        onLongClick != null ->
                            // `onClick` is null here: a long-press-only row (a read-only detail
                            // field's copy gesture, say). `combinedClickable` cannot expose a
                            // long-click action without also registering a click one —
                            // `AbstractClickableNode.applySemantics` calls `onClick(...)`
                            // unconditionally — so routing this through it the way the two-action
                            // branch above does left a fake tap that did nothing, and TalkBack
                            // still read it out as "double tap to activate". Detecting the gesture
                            // directly and adding only the long-click action keeps that action
                            // reachable — by touch, and from a screen reader's actions menu via
                            // `focusable` — with nothing advertised beside it that isn't real.
                            Modifier
                                .lkFocusRing(RectangleShape)
                                .focusable(interactionSource = interaction)
                                .pointerInput(onLongClick) {
                                    detectTapGestures(
                                        onLongPress = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onLongClick()
                                        },
                                    )
                                }
                                .semantics(mergeDescendants = true) {
                                    role = Role.Button
                                    onLongClick(onLongClickLabel) {
                                        onLongClick()
                                        true
                                    }
                                }
                        onClick != null ->
                            Modifier
                                .lkPressWash(interaction, RectangleShape)
                                .lkFocusRing(RectangleShape)
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    // A tappable row is a button to a screen reader, whatever it
                                    // looks like.
                                    role = Role.Button,
                                    onClickLabel = onClickLabel,
                                    onClick = click,
                                )
                        else -> Modifier
                    },
                )
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = verticalAlignment,
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
    }
}

/**
 * A screen header: title, optional subtitle, optional leading and trailing slots.
 *
 * Every top-level screen uses this, which is why "Vault", "Generator" and "Security" line up
 * exactly as you switch tabs — a detail that is invisible when right and obvious when wrong.
 *
 * The title and the slots are their own Row, with the subtitle *below* that row rather than inside
 * a column the slots are centred against. Centring against title+subtitle means the whole header is
 * positioned by how many lines the subtitle happens to occupy: the generator's two-line subtitle put
 * its title 7px above the vault's and its trailing control 14px below the security dashboard's, on
 * three screens whose whole point is that they look like one screen with the content swapped. The
 * subtitle also gets the full width now instead of stopping short at the trailing control, which is
 * what forced it onto two lines in the first place.
 *
 * **Two things it does when the window is small or the type is large.** (1) When the controls would
 * leave the title narrower than its widest word, they take a row of their own above it
 * (S-W4-F3, SCL-019). (2) In a short window at large type the subtitle is dropped if keeping it
 * would pin more than a third of the window; the header is then the title and its controls and
 * nothing else. Neither changes a window with room: at 411dp and 1.0 the header is the single row
 * and the subtitle the comps draw.
 */
@Composable
fun LkScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    /**
     * Keep the subtitle however short the window is. By default a subtitle is the one line that
     * yields when the pinned header would otherwise take more than a third of a short window
     * (under 560dp tall at font scale 1.5 or more); a subtitle that carries something the user must read - a notice, not
     * a caption - passes true.
     */
    keepSubtitle: Boolean = false,
) {
    val c = LkTheme.colors
    val density = LocalDensity.current
    val windowHeight = LocalConfiguration.current.screenHeightDp.dp
    val measurer = rememberTextMeasurer()
    val shortWindow = lkHeaderWindowIsShort(windowHeight, density.fontScale)
    // Subcomposed rather than laid out from children: whether the subtitle exists at all depends on
    // how tall the rest turned out, and a child that is composed but not placed is still in the
    // semantics tree, where a screen reader would read a line the screen does not show.
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val lead = leading?.let { slot ->
            subcompose(HeaderLead) { Box { slot() } }.single().measure(loose)
        }
        val trail = trailing?.let { slot ->
            subcompose(HeaderTrail) { Box { slot() } }.single().measure(loose)
        }
        val gap = RowGap.roundToPx()
        val slots = listOfNotNull(lead, trail)

        // One row, or two. The title is given what the controls leave; when that is narrower than
        // the widest word of the title - at 308dp and font scale 2.0 the menu and "Sperren" left
        // "Passwort-Gesundheit" 157px, and it broke "Pas / sw / ort- / Ges / und / heit" - the
        // controls take a row of their own above it and the title gets the full width. The rule the
        // vault list's title bar already used for this (SCL-019), made the header's own so every
        // screen has it. A hyphenating style cannot fix a slot narrower than a syllable.
        val beside = (width - slots.sumOf { it.width } - gap * slots.size).coerceAtLeast(0)
        val stacked = slots.isNotEmpty() && beside < lkHeaderWidestPiece(measurer, title)
        val titleWidth = if (stacked) width else beside
        // `heading()` is what puts a stop on TalkBack's navigate-by-heading gesture, which
        // is the standard way a screen-reader user orients on an unfamiliar screen. The app
        // had none anywhere, so that gesture found nothing on any screen and the only way
        // through was to swipe element by element from the top, every time. Set here rather
        // than at the call sites because every screen title in the app is this composable -
        // twelve of them - and a per-screen opt-in is a per-screen chance to forget.
        val titleP = subcompose(HeaderTitle) {
            Text(
                title,
                style = LkText.screenTitle,
                color = c.textStrong,
                modifier = Modifier.semantics { heading() },
            )
        }.single().measure(Constraints(maxWidth = titleWidth))

        val controlsHeight = slots.maxOfOrNull { it.height } ?: 0
        val titleTop = if (stacked) controlsHeight + StackGap.roundToPx() else 0
        val bodyHeight = if (stacked) titleTop + titleP.height else maxOf(controlsHeight, titleP.height)

        // The subtitle stays unless a short window cannot afford it: the header may take a third of
        // the window, the caller's own top padding included.
        val subtitleGap = SubtitleGap.roundToPx()
        val budget = (windowHeight / 3f - LkFrame.screenTop).roundToPx()
        val subtitleHeight = if (subtitle == null) 0 else measurer.measure(
            subtitle,
            LkText.subtitle,
            constraints = loose,
        ).size.height
        val subtitleP = if (subtitle != null &&
            (!shortWindow || keepSubtitle || bodyHeight + subtitleGap + subtitleHeight <= budget)
        ) {
            // No line budget, for the reason spelled out on the settings row summary: two lines is
            // a pixel budget disguised as a line count, and it holds only at the width and font
            // scale it was measured at. A screen subtitle is app-authored and short; if it needs a
            // third line at the largest display size, the honest thing is to give it one - unless
            // the window is so short that the header would crowd the content out.
            subcompose(HeaderSubtitle) {
                Text(subtitle, style = LkText.subtitle, color = c.textFaint)
            }.single().measure(loose)
        } else {
            null
        }

        val height = bodyHeight + if (subtitleP != null) subtitleGap + subtitleP.height else 0
        layout(width, height) {
            if (stacked) {
                lead?.place(0, (controlsHeight - lead.height) / 2)
                trail?.place(width - trail.width, (controlsHeight - trail.height) / 2)
                titleP.place(0, titleTop)
            } else {
                var x = 0
                if (lead != null) {
                    lead.place(x, (bodyHeight - lead.height) / 2)
                    x += lead.width + gap
                }
                titleP.place(x, (bodyHeight - titleP.height) / 2)
                if (trail != null) trail.place(width - trail.width, (bodyHeight - trail.height) / 2)
            }
            subtitleP?.place(0, bodyHeight + subtitleGap)
        }
    }
}

private const val HeaderLead = "lk-header-lead"
private const val HeaderTitle = "lk-header-title"
private const val HeaderTrail = "lk-header-trail"
private const val HeaderSubtitle = "lk-header-subtitle"
private val RowGap = 11.dp
private val StackGap = LkSpace.sm
private val SubtitleGap = 3.dp

/**
 * Whether the window is short enough, at this text size, that a pinned header has to be thrifty.
 *
 * Under 560dp tall at 1.5x or more: 548dp is the window the 308dp pass was run on, and any phone in
 * landscape at that text size is shorter. The same pairing - big text, small window - the keyboard
 * rule keys on, without needing a keyboard.
 */
internal fun lkHeaderWindowIsShort(windowHeight: Dp, fontScale: Float): Boolean =
    windowHeight < 560.dp && fontScale >= LkText.stackAboveFontScale

/**
 * The widest piece of [title] that cannot be broken without breaking a word: a run between spaces
 * or after a hyphen ("Passwort-" and "Gesundheit"), measured in the screen-title style.
 */
internal fun lkHeaderWidestPiece(measurer: TextMeasurer, title: String): Int =
    title.split(' ', '\u00AD').flatMap { word ->
        // Keep each hyphen with the piece before it: that is where a line may end.
        Regex("[^-]+-?").findAll(word).map { it.value }.toList()
    }.maxOfOrNull {
        measurer.measure(it, LkText.screenTitle, softWrap = false).size.width
    } ?: 0

/**
 * The gap between a section label and the group it heads.
 *
 * [LkSpace.sm] is "a label to its own field", and this is the same relationship one level up. The
 * point of naming it is not the number: it is that one relationship had three numbers — 9dp on the
 * item view, 10dp on Settings, 16dp on Backup — because every caller supplied its own spacer and no
 * two callers agreed (visual UX sweep, 2026-08-27).
 */
val LkSectionLabelGap: Dp = LkSpace.sm

/**
 * An uppercase section label above a group.
 *
 * `textMuted`, not `textFaint`. `textFaint` measures 5.18:1 on `ground` — the only step on the
 * ramp anywhere near the 4.5:1 floor, and the wrong default for a label whose job is to tell you
 * where one part of a screen ends. `textMuted` is 6.55:1 for the same weight and size.
 *
 * A *field* label is a different role and keeps `textFaint` deliberately — see [LkFieldLabel];
 * the comp draws every one of those at `#7A858B`, which is this token exactly.
 *
 * **It owns the gap below it** ([LkSectionLabelGap]). A caller that also emits a `Spacer` or a
 * `bottom =` padding of its own is now adding to it, not supplying it — and in a `spacedBy` column
 * that spacer was being spaced too, which is how one relationship ended up rendered at three
 * different heights. Remove the caller's gap; do not re-tune it.
 */
@Composable
fun LkSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LkTheme.colors.textMuted,
) {
    Text(
        text.uppercase(),
        style = LkText.sectionLabel,
        color = color,
        // Inside the caller's own modifier, so a caller's horizontal padding still frames the text
        // and this gap is measured from the label's own baseline box rather than from whatever the
        // caller wrapped it in.
        modifier = modifier.padding(bottom = LkSectionLabelGap),
    )
}

/**
 * Whether a monogram tile takes part in the shared-element morph on this crossing.
 *
 * Pulled out of the composable because it is the whole of the decision and none of the drawing, and
 * because the case it exists for — [leaving] paired with [morphsOnArrivalOnly] — is a state that a
 * screenshot taken one capture too late cannot see at all.
 *
 * Reduced motion opts out entirely: the screen transitions already collapse to a single frame under
 * it (see `lkScreenEnter`), and a lone element sliding across an otherwise still crossing would be
 * exactly the kind of motion the setting exists to remove.
 */
internal fun lkTileMorphs(
    paired: Boolean,
    reduceMotion: Boolean,
    morphsOnArrivalOnly: Boolean,
    leaving: Boolean,
): Boolean = paired && !reduceMotion && !(morphsOnArrivalOnly && leaving)

/**
 * The monogram tile that stands in for a favicon.
 *
 * There is no favicon and there never will be — fetching one is a network call, which this app does
 * not make (`checkNoNetworkingDeps`), and it would leak which sites are in the vault to whoever
 * serves the icon. The tile has to carry recognisability on its own, so it does it with a
 * deterministic colour derived from the title (see [lkTileColorsFor]) plus initials: at a glance
 * down the list you are matching a colour, not reading text.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun LkMonogramTile(
    title: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    shape: Shape = LkShape.md,
    textStyle: androidx.compose.ui.text.TextStyle = LkText.rowTitle.copy(
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
    ),
    /**
     * A stable identity (the item id) that makes this tile the same object in two destinations, so
     * it morphs between them instead of cross-fading. Non-null only where a pair exists — the vault
     * row and the item detail header. When the shared-transition scopes are absent (every other
     * caller, and under reduced motion where the layout never wraps a `SharedTransitionLayout` with
     * animation) the tile just draws in place.
     */
    sharedKey: String? = null,
    /**
     * Draws the tile as a blank placeholder: the skeleton's own surface, no glow, no glyph.
     *
     * For the one caller that must occupy this tile's place before it knows whose tile it is — the
     * item detail screen while the vault lookup runs. It cannot simply pass an empty [title]:
     * [lkTileColorsFor] hashes the string, so `""` is not neutral, it is the first colour in the
     * ramp, and [lkMonogram] renders it as "?". Both would be a stated identity, and stating the
     * wrong one for a frame is exactly the defect this exists to end. The node still carries
     * [sharedKey], because the morph needs a destination for the whole load or it has nowhere to
     * travel to.
     */
    placeholder: Boolean = false,
    /**
     * Marks this tile as the *arrival* end of the pair, so the morph runs on the way in and not on
     * the way back.
     *
     * The morph is the gesture of opening an item: the row's tile lifts out of the list and becomes
     * the detail screen's header. Played in reverse it is the same flight, and reported as such —
     * *"going back … it pops the animation of opening the item again … why are we re-initting
     * animation when going back?"* (2026-08-21). Measured on the emulator at
     * `animator_duration_scale 10`: on the pop the list is at 93% of its settled luminance in the
     * first capture after the press and fully settled in the second, while the tile is still in the
     * overlay halfway down the screen with the row it is heading for drawn empty. One element flying
     * over a screen that has already arrived, past rows it has nothing to do with. Going back is not
     * an event, and nothing should announce it.
     *
     * The two ends cannot decide this between them, because the crossing is symmetric — whichever
     * screen is leaving sits at [EnterExitState.PostExit] in both directions. So the *destination*
     * end declares itself: it registers its key only while arriving or at rest, and drops it while
     * leaving. With one end unregistered `sharedBounds` finds no pair, the row's tile simply draws
     * in place, and the forward morph — which nobody complained about — is untouched.
     */
    morphsOnArrivalOnly: Boolean = false,
) {
    val c = LkTheme.colors
    val tile = remember(title) { lkTileColorsFor(title) }
    val transitionScope = LocalSharedTransitionScope.current
    val visibilityScope = LocalNavAnimatedVisibilityScope.current
    val morph = lkTileMorphs(
        paired = sharedKey != null && transitionScope != null && visibilityScope != null,
        reduceMotion = LocalLkReduceMotion.current,
        morphsOnArrivalOnly = morphsOnArrivalOnly,
        leaving = visibilityScope?.transition?.targetState == EnterExitState.PostExit,
    )
    // Stated rather than defaulted. Without this the morph runs Compose's default spring, whose
    // curve is not one of this app's four and whose duration is not one of its six — the one element
    // that crosses between two screens would be the only thing on screen not moving in the app's own
    // vocabulary.
    //
    // `Screen`, matching the navigation transition exactly, and *not* the design's slightly longer
    // hero (.52s against its .46s screen). A shared element is drawn in the SharedTransitionLayout's
    // overlay, deliberately outside either screen's fade, so any overhang is time the tile spends at
    // full opacity above a screen that has already finished arriving — which is what read as the
    // icon trying to escape. The design can afford the overhang because it has no overlay and no
    // back transition at all; here the two have to land together. Drawing it inside the screen
    // instead (`renderInOverlayDuringTransition = false`) was tried and is worse: the tile vanishes
    // for several frames mid-crossing and is clipped flat by the header once it arrives.
    //
    // Built as a plain `tween` and not `lkTween`: BoundsTransform is a fun interface invoked outside
    // composition, and `lkTween` is @Composable @ReadOnlyComposable. The reduced-motion branch is
    // resolved here instead — belt and braces, since `morph` is already false under it.
    val morphMillis = if (LocalLkReduceMotion.current) 0 else LkMotion.Screen
    val morphBounds = remember(morphMillis) {
        BoundsTransform { _, _ -> tween(morphMillis, easing = LkMotion.Emphasized) }
    }
    val sharedModifier = if (morph) {
        with(transitionScope!!) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "monogram-$sharedKey"),
                animatedVisibilityScope = visibilityScope!!,
                boundsTransform = morphBounds,
                // `sharedBounds` + `scaleToBounds`, not `sharedElement`. The difference is the whole
                // bug. `sharedElement` animates the *bounds* and re-lays the content out at every
                // size, so the monogram snapped to its destination type size on the first frame and
                // the tile grew around a glyph that never moved: measured on a device at
                // `animator_duration_scale 10`, the container ran 140→158px while the glyph sat at
                // exactly 61×41px in every frame, and on the way back it was 28px tall from the
                // first frame while the container ran 129→110. `scaleToBounds` measures the content
                // once and places it through a scaling layer instead, which is precisely what the
                // design does — it renders the destination tile and animates
                // `transform: translate(...) scale(w/60)` to `none`. The corner radius stops
                // snapping for the same reason.
                resizeMode = scaleToBounds(),
            )
        }
    } else {
        Modifier
    }
    // The halo travels *with* the tile, on the same node. It used to sit on a wrapper outside the
    // shared node to stop it stretching — which was the right call under `sharedElement`, where the
    // content is remeasured at every intermediate size. Under `scaleToBounds` the whole node is
    // drawn once and scaled, so a radial gradient scales with the tile exactly as it should. The
    // old arrangement had a cost nobody had looked for: the wrapper stayed composed at *both* ends
    // for the length of the morph, leaving a tile-shaped glow with no tile in it sitting at the
    // destination while the tile was still travelling. That was the flicker.
    //
    // `drawBehind` precedes `lkSurface` because `lkSurface` is `background(...).lkRing(...)` with no
    // `clip`, so a draw modifier chained after it would paint over the tile's own fill.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .then(sharedModifier)
                // A 2-letter glyph styled at 15sp inside a box pinned by `.size` is exactly the
                // shape [LkText.stackAboveFontScale] documents: a dp box next to sp text has no
                // floor. Measured on device (API 35, 2026-09-02): a wide monogram ("MM") is 46.9dp
                // across at fontScale 2.0, and every monogram outgrows 42dp on both axes from about
                // 2.25 — while a narrow one ("BO") still fits at 2.0 (35.4×33.9dp), so the plate must
                // not move there. The 83×22px `Copy` overlap the fontscale walk reported was the row
                // TITLE, not this tile (`VaultItemRow`). `.size` pinned both the minimum and the
                // maximum, so the plate could not follow; `defaultMinSize` keeps the minimum and
                // drops the ceiling, costing nothing at fontScale 1.0 (the glyph already fits inside
                // 42dp/36dp there — see `monogramTileStaysAtDesignSizeAtDefaultFontScale`) and
                // letting the plate grow to whatever the glyph actually needs above it. Same shape
                // of fix as the buttons in `FontScaleTest`.
                //
                // Multiplying by `fontScale` outright, the way the health score ring does, was
                // rejected on purpose: that ring owns a whole card, this tile shares a 13dp-gapped
                // row with a title and a trailing action, and a flat 2x here would grow the plate
                // whether or not the glyph inside it needed the room. `VaultItemRow` already sizes
                // its `SelectionTick` to "same 42dp" as this tile so the row does not shift sideways
                // when selection mode turns on — content-driven growth is the smaller of the two
                // costs available from this file alone; keeping the row's other elements in step
                // with a grown tile is that row's fix, not this component's.
                .defaultMinSize(minWidth = size, minHeight = size)
                .drawBehind {
                    if (placeholder) return@drawBehind
                    val extent = this.size.minDimension
                    val centre = Offset(this.size.width / 2f, this.size.height / 2f + extent * GlowDrop)
                    val radius = extent * GlowRadius
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(tile.glow, Color.Transparent),
                            center = centre,
                            radius = radius,
                        ),
                        radius = radius,
                        center = centre,
                    )
                }
                .lkSurface(shape, if (placeholder) c.field else tile.container, c.ringStrong)
                // `lkSurface`'s own doc claims content is "clipped to [shape]"; `background(...)`
                // does not do that, it only paints its own fill inside the shape, which is exactly
                // what the comment above this Box already knew before this line existed ("`lkSurface`
                // ... with no `clip`"). Without it, growth from `defaultMinSize` above is the only
                // thing keeping the glyph inside the plate — this is the belt to that braces, and it
                // is what actually stops a stray glyph (an unusual font, a future style change)
                // from bleeding into the row instead of the tile. `LkGroup` clips the same way,
                // right after its own `lkSurface` call. Placed after the fill and ring (both already
                // shape-accurate on their own) and after `drawBehind`'s glow above, which is meant to
                // bleed past the edge — clipping it would turn the halo into a hard-edged disc.
                .clip(shape)
                // Read aloud, a monogram is "B, O" before every row title. It adds nothing the
                // adjacent title does not already say.
                .decorative(),
            contentAlignment = Alignment.Center,
        ) {
            if (!placeholder) {
                Text(remember(title) { lkMonogram(title) }, style = textStyle, color = tile.content)
            }
        }
    }
}

/**
 * The monogram halo, as fractions of the tile's own size so the 42dp list tile and the 60dp detail
 * header tile each get the right weight from one rule.
 *
 * The design specifies the two separately — `0 4px 12px` on the row and `0 10px 26px` on the header —
 * and a single proportional rule lands within a dp of both. One rule that two sizes share beats
 * per-call parity on a soft gradient nobody can measure.
 */
private const val GlowDrop = 0.14f
private const val GlowRadius = 0.85f

/**
 * An empty / zero-result state: soft icon plate, a headline, a line of prose, an optional action.
 *
 * Shared so that "no items", "no search results" and "nothing in trash" are visibly the same kind
 * of moment rather than three improvised layouts.
 */
@Composable
fun LkEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    /**
     * Runs when the icon plate is tapped, for a state whose plate genuinely *is* the way in — an
     * empty category offering to add the first item.
     *
     * Null makes the plate decoration, and decoration is what it then looks like: no surface, no
     * ring, a dimmer glyph. That visual split is the whole point. Wiring every plate was the first
     * answer and it was wrong — a magnifier above "Clear search" is an illustration of the
     * situation, not a button anyone would press to clear anything — but a plate that keeps a
     * card's fill and hairline while doing nothing is the lie the 2026-07-29 hand-tests kept
     * finding. So: tappable plates look raised and tappable; the rest stop pretending.
     */
    onIconTap: (() -> Unit)? = null,
    /** Screen-reader label for the tappable plate; required in spirit whenever [onIconTap] is set. */
    iconTapLabel: String? = null,
    /**
     * Drops the icon plate, for a region too short to hold both it and the sentence.
     *
     * The plate is 96dp plus its gap — 399px at density 3.5, and the vault list's empty state gets
     * a 611px viewport once the keyboard is up. Laid out in full, the plate was the *only* thing
     * that fitted: a magnifier alone over blank ground, with "Nothing here matches …" and the
     * "Search outside <Folder>" button that is the only way out of that dead end both below the
     * fold. The plate is scene-setting beside the sentence that explains the state, so when the two
     * cannot both fit, the sentence wins.
     *
     * No affordance goes with it. A tappable plate is always a second way to run the button that
     * `action` already shows — the plate and the button were deliberately wired to the same call —
     * so dropping the plate costs a shortcut, never the only route.
     */
    compact: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val c = LkTheme.colors
    val plateShape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp)
    val tappable = onIconTap != null
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = LkFrame.heroHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (!compact) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(plateShape)
                .then(
                    if (onIconTap != null) {
                        Modifier
                            .lkSurface(plateShape, c.cardFill, c.ring)
                            .clickable(role = Role.Button, onClick = onIconTap)
                            .labelled(iconTapLabel ?: title)
                    } else {
                        Modifier.decorative()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                // A decorative glyph sits back: it is scene-setting beside the sentence that
                // explains the state, not something to aim at.
                tint = if (tappable) c.textFaint else c.textFaint.copy(alpha = 0.55f),
                modifier = Modifier.size(if (tappable) 40.dp else 34.dp),
            )
        }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Centred explicitly: the Column centres a one-line title by its width, but a title that
            // wraps ("Identität / bestätigen") fills the column and is then start-aligned inside it,
            // drifting left of its centred body (SCL-032).
            Text(
                title,
                style = LkText.cardTitle,
                color = c.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                // Through [lkBrandedText], like the dialog masthead and the inline notes.
                lkBrandedText(body),
                // Balanced, not the Paragraph break `bodyQuiet` carries: that one is tuned for
                // left-aligned reading, where a short last line is invisible. Centred, it is a lone
                // word under the block — "continue." on the Recovery Kit gate.
                style = LkText.bodyQuiet.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
                color = c.textFaint,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        action?.invoke()
    }
}

/**
 * A quiet footnote block with an info glyph — the app's standing way of saying something honest and
 * slightly deflating ("a miss is not a guarantee", "this screen cannot be screenshotted").
 */
@Composable
fun LkFootnote(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = LkIcons.Info,
    /**
     * Tints the whole block instead of leaving it in the grey wash.
     *
     * For the small number of footnotes that are not fine print but the answer to the question the
     * user is actually holding — "can I skip this?" — where grey-on-grey is read as boilerplate and
     * skipped precisely by the person who needed it. The text lifts to [LkColors.textMuted] too,
     * because a tinted plate under `textFaint` reads as a disabled control rather than a live note.
     * Left null everywhere else on purpose: a page where every note is accented has no notes.
     */
    accent: Color? = null,
) {
    val c = LkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lkSurface(
                LkShape.xl,
                accent?.copy(alpha = 0.07f) ?: c.wash,
                accent?.copy(alpha = 0.18f) ?: c.separator,
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent ?: c.textFaint,
                modifier = Modifier.size(15.dp).padding(top = 1.dp),
            )
        }
        Text(text, style = LkText.caption, color = if (accent != null) c.textMuted else c.textFaint)
    }
}

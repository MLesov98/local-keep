package com.localkeep.core.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.rememberHapticClick

/**
 * The button family.
 *
 * Four weights, and the hierarchy between them is strict: there is at most one [LkPrimaryButton] on
 * a screen. It is the only element allowed to be bright, which is what makes "the next thing to do"
 * legible at a glance on a screen that is otherwise near-black.
 *
 * All of them are built from `Box` + `clickable` rather than Material's `Button`, because the
 * design needs a gradient fill, an inset hairline ring and press-scale feedback — three things
 * `ButtonDefaults` cannot express, and which a ripple over `#0E1214` cannot substitute for.
 */

/** The one bright thing on the screen. Jade gradient, dark ink. */
@Composable
fun LkPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Brass instead of jade — for the Pro purchase, which must not look like a security action. */
    accent: LkButtonAccent = LkButtonAccent.Jade,
    icon: ImageVector? = null,
    /**
     * Defaults to [Haptic.SELECT], but a primary button is very often the commit — Save, Create,
     * Unlock — and those call sites should pass [Haptic.SUCCESS] so the confirmation is felt.
     */
    haptic: Haptic? = Haptic.SELECT,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val click = rememberHapticClick(haptic, onClick)

    val fill: Brush = when {
        !enabled -> SolidColor(Color.Transparent)
        accent == LkButtonAccent.Brass -> c.brassFill
        else -> c.jadeFill
    }
    // Disabled is not "the same button, faded": a dimmed bright fill still reads as the primary
    // action and invites a tap that does nothing. It drops to a hollow plate instead - ring and
    // label, no fill - so the screen visibly has no primary action available yet.
    //
    // Transparent rather than `wash`, changed 2026-09-09. `wash` is also the *enabled* fill of
    // `LkSecondaryButton`, so a disabled primary and a live secondary were painting the identical
    // plate at the identical `LkShape.xl` and 52dp minimum, separated only by a hairline ring and
    // the label ink. On the card form's exit row the Continue and Discard plates measured
    // pixel-identical, which left the brightest control on a screen with no available primary
    // being Discard - the one that loses data. The KDoc above promises at most one bright element
    // per screen; it never promised the dim ones would be telling apart, and they have to be.
    // WCAG 1.4.11 exempts inactive components, so a hollow plate owes no non-text contrast and
    // `textFaint` remains its correct label ink.
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> c.textFaint
            accent == LkButtonAccent.Brass -> c.onBrass
            else -> c.onJade
        },
        animationSpec = lkTween(LkMotion.Base),
        label = "primary-content",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 52dp: every primary in `Item Flows.dc.html` is `height:52px`, and it is the same 52
            // as the secondary stacked 8dp beneath it — two commit-weight buttons of different
            // heights in one column is the sort of mismatch that reads as a rendering bug. Still a
            // minimum, not a fixed height, so the label can grow at a large font scale.
            .defaultMinSize(minHeight = 52.dp)
            .lkPressScale(interaction, pressedScale = 0.96f, shape = LkShape.xl)
            .background(fill, LkShape.xl)
            .then(if (enabled) Modifier else Modifier.lkRing(LkShape.xl, c.ring))
            // On the one bright surface in the app, a jade ring would vanish into the fill — the
            // focus indicator has to contrast with what it is drawn on, not with the page.
            .lkFocusRing(LkShape.xl, accent = c.onJade)
            // Announced as a button. Compose reports a `clickable` Box as a generic View, so
            // TalkBack read "Get started" where a real button says "Get started, button" — the word
            // that tells a screen-reader user this is something to activate rather than a label.
            // `LkChip` already carried `Role.Tab` for the same reason; every other control in the
            // system was missing its own.
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = click,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LabelRow(text = text, icon = icon, color = content, style = LkText.button)
    }
}

enum class LkButtonAccent { Jade, Brass }

// Dynamic type: these four heights are MINIMUMS, not fixed heights.
//
// `uiux:31` requires the type system to scale, and `.height(56.dp)` does not — at a large font
// scale the label is clipped inside a box that refuses to grow. `defaultMinSize` keeps the same
// resting size and the same 48dp-plus touch target while letting the button grow when the text
// needs the room. Filed as UX debt; closed 2026-07-28.
//
// The chip at the bottom of this file deliberately keeps `.height(34.dp)`: its comment explains
// that the fixed height is what `minTouchTarget()` (a `defaultMinSize`) is measured against, and
// swapping it would silently drop the 48dp floor it is there to guarantee.


/**
 * A quiet, washed button with a hairline ring. The usual "second thing you might do".
 *
 * Geometry and colour come from `Item Flows.dc.html`, which draws this shape six times — Photograph,
 * From gallery, "Scan the site's QR code", "Paste a link instead", the folder screen's Cancel/Done
 * and "Run the flow again" — always as `height:52px;border-radius:16px;background:rgba(255,255,255,.05);
 * box-shadow:inset 0 0 0 1px rgba(255,255,255,.09);color:#EDF1F2;font:600 15px/1`.
 *
 * It used to fill with [LkColors.field] (#101416), the *input well* colour, at 54dp and one type
 * step down. Filling a button with the colour that means "type here" is the mistake: on the item
 * form it put a well-coloured block directly under a run of actual wells, and the principal read
 * exactly that back on 2026-08-19 — the boxes were the wrong tone against the ground. [LkColors.wash]
 * is the comp's .05 white, which sits *above* the ground instead of below it, and the ring carries
 * the edge.
 *
 * [container], [contentColor] and [ring] exist for the one place in the app that needs the same
 * *shape* in a different voice: the location capture's Stop button, which `Location Capture.dc.html`
 * draws `background:transparent` with an amber ring and label while a reading is still improving.
 * Every other caller takes the defaults and gets the comp's washed button unchanged.
 *
 * [height] is a comp measurement, not an accessibility one — several callers pass 40–46dp to match a
 * denser row (`VaultListScreen`'s empty-state actions, onboarding's secondary prompts), and those are
 * exactly the shapes the design calls for. Coercing [height] itself up to [MinTouchTarget] would
 * "fix" the floor by silently redrawing every one of those buttons taller than its comp, which is a
 * design change wearing an accessibility fix's clothes. So the touch target and the painted card are
 * two nodes: the outer one carries [minTouchTarget] and `clickable` and is otherwise invisible; the
 * inner one paints at [height] exactly as before and is centred inside whatever the outer grew to.
 * Below the 48dp floor the outer sits taller than what's drawn — same shape as [LkChip]'s 34dp pill
 * inside its own 48dp target, and for the same reason: `defaultMinSize` only raises a dimension whose
 * *incoming* minimum is zero, so the split has to be a separate node or the outer's 48dp would pin
 * the inner's incoming constraint and grow the card too. `FontScaleTest` guards the case this
 * actually shipped broken: at fontScale 0.85 the label shrinks below what it takes to force the card
 * past [height], and a fixed-height read of this button would have measured under 48dp.
 */
@Composable
fun LkSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    haptic: Haptic? = Haptic.SELECT,
    container: Color? = null,
    contentColor: Color? = null,
    ring: Color? = null,
    /**
     * Applied to the icon glyph alone, under its size. Exists for the one caller whose icon has to
     * *move* while its label stays put — the coordinate capture's seeking pin — because the glyph
     * is otherwise unreachable from outside this function.
     */
    iconModifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val click = rememberHapticClick(haptic, onClick)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .minTouchTarget()
            .lkFocusRing(LkShape.xl)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = click,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = height)
                .lkPressScale(interaction, pressedScale = 0.96f, shape = LkShape.xl)
                // Disabled drops to a hollow plate, exactly as the disabled primary does since
                // 2026-09-09. `enabled` used to change the label ink and nothing else, so a
                // disabled secondary and a live one were the identical plate at the identical
                // shape and height - the same collapse that was fixed one rung up, one component
                // over, and missed here because the primary was the one being measured. Explicit
                // `container`/`ring` from a call site still win: a caller that has asked for a
                // specific plate has a reason, and this is the default, not a policy.
                .lkSurface(
                    LkShape.xl,
                    container ?: if (enabled) c.wash else Color.Transparent,
                    ring ?: if (enabled) c.ringStrong else c.ring,
                ),
            contentAlignment = Alignment.Center,
        ) {
            LabelRow(
                text = text,
                icon = icon,
                color = if (enabled) contentColor ?: c.textPrimary else c.textFaint,
                style = LkText.button,
                iconModifier = iconModifier,
            )
        }
    }
}

/**
 * The inline "add one more of these" control: a small pill that hugs its label at the start of the
 * row, not a full-width block.
 *
 * `Item Flows.dc.html` uses one shape for every such action — "Add website", "Add custom field",
 * "Add tag" — and it is always `align-self:flex-start;height:44px;padding:0 14px;border-radius:16px;
 * background:transparent;box-shadow:inset 0 0 0 1px rgba(255,255,255,.09);color:#C4CDD1;font:500 14px/1`.
 *
 * Transparent is the load-bearing part. These sit *inside* a labelled section, between the rows they
 * add to and the section below, so a filled block reads as another well and the section stops
 * looking like one thing. Stretched full-width it reads as the screen's main action, which on a form
 * whose main action is Save is a straightforward lie about importance — that is what "Add website"
 * looked like before this existed, a 54dp filled bar directly above the real primary button.
 *
 * The 44dp box is under the 48dp touch floor by design, so [minTouchTarget] restores the floor
 * without growing the drawn pill.
 */
@Composable
fun LkInlineAddButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = LkIcons.Plus,
    enabled: Boolean = true,
    haptic: Haptic? = Haptic.SELECT,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val click = rememberHapticClick(haptic, onClick)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .lkPressScale(interaction, pressedScale = 0.96f, shape = LkShape.xl)
            .lkSurface(LkShape.xl, Color.Transparent, c.ringStrong)
            .lkFocusRing(LkShape.xl)
            .minTouchTarget()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = click,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LabelRow(
            text = text,
            icon = icon,
            color = if (enabled) c.textSecondary else c.textFaint,
            style = LkText.navLabel,
            iconSize = 16.dp,
            horizontalPadding = 14.dp,
            gap = 8.dp,
        )
    }
}

/**
 * A tinted button in an accent colour — the "optional but recommended" weight.
 *
 * Used for the passphrase suggester (brass) and inline copy actions (jade). Tinted rather than
 * filled so it reads as an offer, not an instruction.
 *
 * Default [height] already sits on [MinTouchTarget], but `ItemEditScreen` pairs two of these at
 * 42dp for a denser row and that comp choice shouldn't cost a redraw to fix an accessibility gap.
 * Same split as [LkSecondaryButton]: the outer node is the 48dp-floor touch target and carries
 * `clickable`; the inner one paints at [height] and sits centred inside it. See that function's doc
 * for why this has to be two nodes rather than one more `defaultMinSize` in the same chain.
 */
@Composable
fun LkTintButton(
    text: String,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    /**
     * False while the action cannot be taken — the same parameter, for the same reason, as
     * [LkTextButton]'s. This was the last button in this file without one, and it is the one the
     * destructive actions use: `BackupSettingsScreen`'s Replace opened its confirmation during an
     * in-flight import, and `confirmImport` then dropped the confirmed tap at its own `isWorking`
     * guard with nothing written to the screen. It carries the `Disabled` semantics too, which a
     * faint label does not.
     */
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
    shape: Shape = LkShape.lg,
    haptic: Haptic? = Haptic.SELECT,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val click = rememberHapticClick(haptic, onClick)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .minTouchTarget()
            // Follows the button's own accent, so a brass Pro action doesn't sprout a jade ring and
            // read as a security control for the moment it holds focus.
            .lkFocusRing(shape, accent = accent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = click,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = height)
                .lkPressScale(interaction, shape = shape)
                .lkSurface(shape, accent.copy(alpha = 0.07f), accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            LabelRow(
                text = text,
                icon = icon,
                color = if (enabled) accent else c.textFaint,
                style = LkText.buttonSmall,
            )
        }
    }
}

/** A borderless text button. The lowest weight — "cancel", "not now". */
@Composable
fun LkTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False while the action cannot be taken. Without it a caller with an in-flight operation had
     * only [color] to work with — paint the label faint and leave the control live — so the button
     * looked unavailable, still took the tap, and the caller's own guard dropped it with nothing on
     * screen to say why. It also carries the `Disabled` semantics, which a grey label does not: a
     * screen reader otherwise reads a perfectly ordinary button.
     */
    enabled: Boolean = true,
    color: Color = LkTheme.colors.textMuted,
    haptic: Haptic? = Haptic.SELECT,
    /**
     * False for the one shape this control also has to take: a compact action sharing a row with
     * something else, rather than a full-width band under a form. It still keeps the 48dp floor,
     * which is the part that must not be traded away for a tighter row.
     */
    fillWidth: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val click = rememberHapticClick(haptic, onClick)
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            // 48dp, the platform floor (spec §2). It was 46 — two short, which is invisible on a
            // mockup and is exactly the kind of near-miss that makes a button feel unreliable for
            // anyone whose aim is not perfect. The primary and secondary buttons were always above
            // the floor; this one was the outlier, and it is the one under "Not now" and "Continue".
            .defaultMinSize(minHeight = 48.dp)
            .lkPressScale(interaction)
            // A borderless button has no edge of its own, so focus is the only thing that will ever
            // draw one — LkShape.lg gives it a sensible outline to take.
            .lkFocusRing(LkShape.lg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = click,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = LkText.buttonSmall,
            color = if (enabled) color else LkTheme.colors.textFaint,
            // Padding only in the compact form: the full-width band centres its label in the whole
            // row and needs none, while a hugging button whose text meets its own edges has no
            // press target beyond the glyphs.
            modifier = if (fillWidth) Modifier else Modifier.padding(horizontal = 10.dp),
        )
    }
}

/**
 * A square icon button.
 *
 * [label] is mandatory and becomes the accessibility name. An icon-only control with no description
 * is silent to a screen reader, and this design has a lot of them — making the parameter
 * non-optional is the only reliable way to stop one shipping unnamed.
 */
@Composable
fun LkIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 18.dp,
    tint: Color = LkTheme.colors.textSecondary,
    container: Color = LkTheme.colors.field,
    ring: Color = LkTheme.colors.ring,
    shape: Shape = LkShape.md,
    haptic: Haptic? = Haptic.SELECT,
    /**
     * Where the drawn plate sits inside the (larger) touch target.
     *
     * Centred by default, which is right everywhere except a screen edge: a header control at the
     * end of a 26dp-framed screen has its *touch box* flush with the frame, so the centred 40dp
     * plate floats (48 − 40) / 2 = 4dp short of the wells below it — a misalignment nobody can name
     * and everybody can see. `CenterEnd` for a trailing header control (`CenterStart` for a leading
     * one) parks the visible plate on the frame line and spends the spare 8dp of target inward.
     */
    plateAlignment: Alignment = Alignment.Center,
    /**
     * Non-null turns the button into a toggle whose on/off state reaches the accessibility tree.
     *
     * The vault list's Favourites star is one bit the user flips, and as a plain button its state
     * existed only as a tint — the same hole [LkChip] closed with `toggleable`. The caller still
     * chooses the visuals through [tint], [container] and [ring]; this only changes what the node
     * says it is.
     */
    checked: Boolean? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val click = rememberHapticClick(haptic, onClick)
    // Two nodes, and the split is load-bearing. `minTouchTarget()` is `defaultMinSize`, which raises
    // a dimension only when the incoming minimum constraint is exactly zero — so `.size(size)` above
    // it in one chain made it a silent no-op, and every icon button in the app was 40dp rather than
    // the 48dp floor for three months while a comment here claimed otherwise. The touchable node is
    // therefore the outer one, with nothing fixing its size, and the drawn plate is a sized child.
    // The focus ring rides the outer node because `onFocusChanged` has to sit in the same chain as
    // the `clickable` it observes; it now outlines the real target, which is the honest thing to
    // draw anyway.
    val touchable = if (checked == null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = click,
        )
    } else {
        Modifier.toggleable(
            value = checked,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onValueChange = { click() },
        )
    }
    Box(
        modifier = modifier
            .minTouchTarget()
            .lkFocusRing(shape)
            .then(touchable)
            .labelled(label),
        contentAlignment = plateAlignment,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .lkPressScale(interaction, pressedScale = 0.94f, shape = shape)
                .lkSurface(shape, container, ring),
            contentAlignment = Alignment.Center,
        ) {
            // Disabling drops the glyph to the faintest text step and leaves the plate alone: the
            // control keeps its position in the row (so nothing reflows when it becomes available)
            // while clearly not being something to tap yet.
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) tint else LkTheme.colors.textFaint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * A pill-shaped filter chip. Selected state is carried by fill, ring, colour — and a check glyph.
 *
 * And by semantics. It used to be a plain `clickable`, so the selected state existed only as
 * colour: in the accessibility tree an active filter chip was indistinguishable from an inactive
 * one, and a user who had turned two filters on had nothing to tell them so. `toggleable` carries
 * the on/off state into the tree — and unlike `selectable`, it leaves the chip clickable while it is
 * on, which is the only reason a filter can be turned back off without a mouse. See the modifier.
 *
 * **The check glyph closes the other half of that gap.** `uiux:31` says colour is never the sole
 * carrier of meaning, and semantics only reach a user who is running a screen reader. Selected and
 * unselected chips measured **1.21:1** against each other — well under any perceptual threshold —
 * so a sighted user with reduced colour discrimination had nothing at all. Recorded as UX debt 6-1
 * and 6-2, and deliberately fixed as ONE decision across the three surfaces that had it: this chip,
 * the drawer row, and the bottom-bar tab all now carry a filled jade shape when selected, not a
 * tint alone.
 *
 * The glyph replaces [icon] rather than joining it, so the pill's width does not jump on selection
 * and a row of chips does not reflow under the user's finger.
 */
@Composable
fun LkChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = LkTheme.colors.jade,
    icon: ImageVector? = null,
    haptic: Haptic? = Haptic.SELECT,
    /**
     * A glyph after the label, drawn whether or not the chip is selected.
     *
     * For a chip that is a *token* rather than a switch — the vault list's active-filter strip,
     * where every chip is on by construction and the only thing tapping it can do is remove it.
     * The check glyph up front still says "active"; a [LkIcons.Close] here says what the tap does.
     */
    trailing: ImageVector? = null,
    /**
     * The chip's name for a screen reader, when the visible [text] is not enough of one.
     *
     * A filter chip in a row of filter chips needs nothing here: `toggleable` announces the state,
     * the section heading says what is being filtered, and the label is the option. A *token* is
     * different — it stands alone below the search field, and its label is a bare category name.
     * Measured in a `uiautomator` dump 2026-09-02: the token's clickable node had no
     * `content-desc`, so TalkBack read "Logins" with nothing saying it was a filter or that
     * activating it removes one, beside neighbours that were properly named. Callers that render
     * a token pass "Remove filter: Logins" here; the visible text stays the short label.
     *
     * Applied on the same node as the toggle action, via [labelled], so it is one stop for a
     * screen reader and the tap is unaffected.
     */
    contentDescription: String? = null,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val click = rememberHapticClick(haptic, onClick)
    val container by animateColorAsState(
        // The comp's own unselected chip fill is `rgba(255,255,255,.035)` (`LocalKeep.dc.html`,
        // the vault filter row), which is [LkColors.washFaint] — a step below the 5.1% [wash]
        // this used, and the difference the principal was asking for by "darker".
        if (selected) accent.copy(alpha = 0.12f) else c.washFaint,
        lkTween(LkMotion.Quick),
        label = "chip-bg",
    )
    val ring by animateColorAsState(
        if (selected) accent.copy(alpha = 0.26f) else c.ring,
        lkTween(LkMotion.Quick),
        label = "chip-ring",
    )
    // `textTertiary`, not `textMuted`: 8.20:1 against the chip's own fill rather than 5.93:1.
    // An unselected chip is a live control, not secondary prose, and at 12.5sp the muted step
    // was the faintest thing on the vault list that anyone was expected to tap.
    val content by animateColorAsState(
        if (selected) accent else c.textTertiary,
        lkTween(LkMotion.Quick),
        label = "chip-fg",
    )

    // The pill stays 34dp because a row of them has to stay one line; the *target* is grown to the
    // 48dp floor around it. That has to be a separate outer node — `.height(34.dp)` in the same
    // chain pins the incoming minimum, and `minTouchTarget()` is `defaultMinSize`, which only raises
    // a dimension whose incoming minimum is zero. Chained after the height it grew the width and
    // silently did nothing to the height.
    Box(
        modifier = modifier
            .minTouchTarget()
            .lkFocusRing(LkShape.sm, accent = accent)
            .then(if (contentDescription != null) Modifier.labelled(contentDescription) else Modifier)
            // `toggleable`, not `selectable`. Compose maps `SemanticsProperties.Selected` to
            // `info.isClickable = false` and drops ACTION_CLICK entirely — "a selected item cannot
            // usefully be clicked" is true of a tab and false of every chip in this app, which all
            // toggle. The visible consequence: once Favourites was on, a TalkBack user had no way to
            // turn it back off, because the only control that could was no longer a control. Touch
            // was unaffected, which is why it survived a read-only sweep. Verified on device
            // 2026-08-10 — a selected chip emitted no clickable node in the uiautomator tree.
            .toggleable(
                value = selected,
                interactionSource = interaction,
                indication = null,
                onValueChange = { click() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .lkPressScale(interaction, shape = LkShape.sm)
                .lkSurface(LkShape.sm, container, ring)
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // contentDescription is null on purpose: `toggleable` already announces the on/off
            // state, and a second announcement would be noise. This glyph is for eyes.
            val glyph = if (selected) LkIcons.Check else icon
            if (glyph != null) {
                Icon(glyph, contentDescription = null, tint = content, modifier = Modifier.size(13.dp))
            }
            Text(text, style = LkText.chip, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailing != null) {
                Icon(trailing, contentDescription = null, tint = content, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/** A small uppercase badge: "2FA", "PRO", "REPROMPT". */
@Composable
fun LkBadge(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = LkTheme.colors.textFaint,
    container: Color = LkTheme.colors.wash,
) {
    Text(
        text = text.uppercase(),
        style = LkText.badge,
        color = accent,
        modifier = modifier
            .background(container, LkShape.xs)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun RowScope.iconOrNothing(
    icon: ImageVector?,
    color: Color,
    size: Dp,
    iconModifier: Modifier = Modifier,
) {
    if (icon != null) {
        Icon(icon, contentDescription = null, tint = color, modifier = iconModifier.size(size))
    }
}

@Composable
private fun LabelRow(
    text: String,
    icon: ImageVector?,
    color: Color,
    style: androidx.compose.ui.text.TextStyle,
    iconSize: Dp = 18.dp,
    /** The comp's inline pill hugs its label at 14px rather than the 16px a full-width button uses. */
    horizontalPadding: Dp = 16.dp,
    gap: Dp = 10.dp,
    iconModifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
        modifier = Modifier.defaultMinSize(minHeight = 1.dp).padding(horizontal = horizontalPadding),
    ) {
        iconOrNothing(icon, color, iconSize, iconModifier)
        // Up to two lines, and this is the other half of the dynamic-type fix.
        //
        // Making the four button heights minimums (`defaultMinSize`) was necessary and, on its own,
        // did nothing: with `maxLines = 1` the label can never need more than one line's height, so
        // the box never grew and a long label at a large accessibility font scale was ELLIPSIZED
        // rather than clipped. `FontScaleTest` caught this on its first run — the button measured
        // exactly 56.0dp at fontScale 2.0, which is what a fixed height looks like.
        //
        // Two rather than unbounded: `uiux:31` wants the type to scale, and a button that grows to
        // five lines stops reading as a button. `TextOverflow.Ellipsis` still catches the
        // pathological case, so nothing can push the action off screen.
        Text(
            text,
            style = style,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

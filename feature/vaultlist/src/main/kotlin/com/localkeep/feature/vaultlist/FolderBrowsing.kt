package com.localkeep.feature.vaultlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.rememberHaptics
import com.localkeep.core.ui.theme.lkPressAndDrag
import androidx.compose.ui.Alignment
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.getValue
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.lkTween
import androidx.compose.foundation.layout.Box
import com.localkeep.core.ui.theme.LkProgressRing
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkSurface
import java.util.UUID

/**
 * The path back out of a folder, as a strip of tappable crumbs.
 *
 * Horizontally scrollable rather than elided. An ellipsis in the middle of a path hides exactly the
 * crumbs a person reaches for — the root and the grandparent — and at eight levels of nesting on a
 * phone something has to give. Scrolling gives the width back without taking any target away, and
 * every crumb keeps its 48dp.
 */
@Composable
internal fun FolderBreadcrumb(
    path: List<Folder>,
    onCrumb: (Folder) -> Unit,
    onRoot: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * T2's drop target for one crumb — null for the root crumb, whose id is null.
     *
     * A crumb is **hover-fire, not drop**: resting on it navigates to that level with the payload
     * still in hand, which is the "drag onto the back arrow to go up" the feature was asked for.
     * Dropping *on* a crumb would be ambiguous — the user is pointing at a place to go, not at a
     * container — so the crumb accepts nothing and only springs.
     */
    crumbTarget: @Composable (UUID?) -> Modifier = { Modifier },
) {
    if (path.isEmpty()) return
    val colors = LkTheme.colors
    // The strip scrolls to its end whenever the path changes, so the crumb you are *in* is the one
    // on screen. Without it (2C-F4) arriving in a folder at depth 8 showed `All items > Clients >
    // Enterprise > North America` with the folder just opened off-screen to the right — the one
    // thing the strip exists to say. Worse at font_scale 2.0, where three of nine crumbs fit.
    //
    // Keyed on `maxValue` as well as the path because the two arrive a frame apart: when the path
    // grows the layout has not happened yet, so `maxValue` is still the *old* end and scrolling to
    // it would stop one crumb short. The second key re-runs the effect with the measured width.
    val scroll = rememberScrollState()
    val reduceMotion = LocalLkReduceMotion.current
    LaunchedEffect(path, scroll.maxValue) {
        if (reduceMotion) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Outside the scroll, so the fade belongs to the viewport and not to the content that
            // slides under it: a crumb cut by the left edge showed as a bare "ns" of "All items".
            .fadeLeadingEdgeWhileScrolled(scroll)
            .horizontalScroll(scroll)
            .semantics { contentDescription = crumbDescription(path) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.xs),
    ) {
        Crumb(
            text = stringResource(R.string.vaultlist_breadcrumb_root),
            onClick = onRoot,
            modifier = crumbTarget(null),
        )
        path.forEachIndexed { index, folder ->
            Icon(
                imageVector = LkIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(14.dp),
            )
            Crumb(
                text = folder.name,
                onClick = { onCrumb(folder) },
                modifier = crumbTarget(folder.id),
                // The last crumb is where you already are. It stays a target — tapping it is a
                // harmless no-op and disabling it would drop a 48dp box out of the strip — but it
                // reads as the current location rather than as somewhere to go.
                current = index == path.lastIndex,
            )
        }
    }
}

@Composable
private fun Crumb(
    text: String,
    onClick: () -> Unit,
    current: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LkTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (current) colors.textPrimary else colors.textMuted,
        // `minTouchTarget()` is `defaultMinSize`: it forces the 48dp floor onto the constraints
        // handed down to this `Text`, but `Text` doesn't center itself within a constraint bigger
        // than its own content — it stays top-start, which is what put the chevron beside it
        // (a plain `Icon`, centered normally by the outer `Row`) visibly below the crumb's label.
        // `wrapContentSize(Alignment.Center)` re-measures with the min loosened, then centers that
        // natural size back inside whatever floor `minTouchTarget()` forced.
        modifier = modifier
            .clickable(onClick = onClick)
            .minTouchTarget()
            .wrapContentSize(Alignment.Center)
            .padding(horizontal = LkSpace.xs),
    )
}

/** How wide the dissolve at the left edge of a scrolled crumb strip is. */
private val CrumbFadeWidth = 24.dp

/**
 * Dissolves the left edge of a horizontally scrolled strip while it is scrolled, so whatever
 * crosses that edge fades out instead of being sliced mid-glyph.
 *
 * `DstIn` over an offscreen layer: the alpha ramp multiplies the strip's own pixels rather than
 * painting a colour over them, so the screen's aura behind it is untouched. Read in the draw phase,
 * so a scroll never recomposes anything; at rest (nothing off to the left) nothing is drawn and the
 * strip is exactly what it was.
 */
internal fun Modifier.fadeLeadingEdgeWhileScrolled(scroll: ScrollState): Modifier =
    this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            if (scroll.value > 0) {
                val fade = CrumbFadeWidth.toPx().coerceAtMost(size.width)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startX = 0f,
                        endX = fade,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

private fun crumbDescription(path: List<Folder>): String =
    path.joinToString(separator = " / ") { it.name }

/** Air kept between a folder row's count/chevron cluster and the FAB once it has dodged clear. */
private val FolderRowFabGap = 8.dp

/** How a hovering drag payload would be received by a folder row. */
internal enum class FolderRowHover { None, Accepts, Rejects }

/**
 * One folder in the browse list: tap to go in.
 *
 * Named for the list it belongs to, because [FolderRow] is already the management screen's row
 * *model* in this same package.
 *
 * The count is over the whole subtree, matching the drawer's management screen. A parent reading
 * "0 items" while holding a child with forty is a number that sends the user looking for data that
 * is sitting one tap away.
 */
@Composable
internal fun FolderBrowseRow(
    folder: Folder,
    itemCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The parent's name, shown only when a sibling in this same list shares this folder's name. */
    disambiguateBy: String? = null,
    /**
     * Applied to the row's outer `Row`, for the vault list to register it as a drop target.
     *
     * Passed in rather than taking a session here, so this row stays a row: it knows how to look
     * like a folder and nothing about payloads or acceptance. Defaulted, so the picker and the
     * tests are unchanged by the drag existing.
     */
    dropTarget: Modifier = Modifier,
    /** How the hovering payload would be received, which is the only reason this row changes colour. */
    hoverState: FolderRowHover = FolderRowHover.None,
    /** How far through the 600 ms spring-load dwell this row is, 0f-1f. */
    dwellProgress: Float = 0f,
    /** Trashes the folder and its subtree. Named for the gesture that used to be its only route. */
    onLongPress: (() -> Unit)? = null,
    /**
     * Opens the rename dialog. Null wherever there is no dialog to open, which leaves the entry off
     * the menu rather than showing one that does nothing.
     */
    onRename: (() -> Unit)? = null,
    /**
     * Opens the destination picker for this folder — the non-gesture route to dragging it.
     *
     * Separate from [onLongPress] because the two are not the same capability: that one trashes the
     * subtree, this one re-files it. Null on every caller that has no picker to open, which leaves
     * the action off the row rather than announcing one that does nothing.
     */
    onMove: (() -> Unit)? = null,
    /** Opens the Hide… flow for this folder. Null where hiding is not offered. */
    onHide: (() -> Unit)? = null,
    /** True while this folder is concealed but shown (a reveal session): the row carries a marker. */
    hidden: Boolean = false,
    /** Stops hiding this folder — only meaningful, and only offered, while [hidden]. */
    onStopHiding: (() -> Unit)? = null,
    /**
     * The drag begins — grab point and origin both in root coordinates.
     *
     * Defaulted to a no-op, and [dragEnabled] defaulted to false, so the picker and the tests are
     * unchanged by the drag existing. Only the vault list wires them.
     */
    onDragStart: (grabInRoot: Offset, origin: Rect) -> Unit = { _, _ -> },
    /** The hold has registered; the drag's affordances go up before any movement. */
    onDragArmed: (grabInRoot: Offset, origin: Rect) -> Unit = onDragStart,
    onDragCancel: () -> Unit = {},
    dragEnabled: Boolean = false,
    /**
     * Window bounds of the floating control this row's count and chevron must not disappear under
     * — the vault list's FAB — or null when there is nothing to dodge.
     *
     * Exactly [VaultItemRow]'s parameter of the same name, doing the same job on the other kind of
     * row. Item rows have dodged the FAB since F-12; folder rows never did, so in landscape the
     * resting FAB was drawn straight over "6 ›" on the row beneath it — measured on a device
     * 2026-09-20, `fab-add-item [2195,556][2347,708]` over `vaultlist-folder-row
     * [288,566][2368,692]`, with the sibling row's own `6 ›` perfectly visible (2A-F13).
     *
     * Nothing interactive is lost — the whole row is one target and one TalkBack stop — but the
     * count is the only thing on the row that says how much is inside it.
     *
     * A lambda read in the layout phase, never composition, and null for every caller that has no
     * FAB: the folder picker, the folders screen, the tests.
     */
    avoidanceBounds: () -> Rect? = { null },
) {
    val colors = LkTheme.colors
    // Read before any shift is applied, for the reason `fabDodgeOffset` states: measuring the
    // cluster after it moved would make the shift its own trigger.
    var clusterAnchor by remember { mutableStateOf<Rect?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    var menuExpanded by remember(folder.id) { mutableStateOf(false) }
    val hasMenu = onRename != null || onMove != null || onLongPress != null ||
        onHide != null || (hidden && onStopHiding != null)
    val hideActionLabel = stringResource(R.string.vaultlist_hide_action)
    val stopHidingLabel = stringResource(R.string.vaultlist_stop_hiding)
    val label = disambiguateBy
        ?.let { stringResource(R.string.vaultlist_folder_row_disambiguated, folder.name, it) }
        ?: folder.name
    val trashActionLabel = stringResource(R.string.vaultlist_folder_action_move_to_trash, folder.name)
    val renameActionLabel = stringResource(R.string.vaultlist_folders_rename_action, folder.name)
    val moveActionLabel = stringResource(R.string.vaultlist_folder_action_move_folder, folder.name)
    val openLabel = stringResource(R.string.vaultlist_folder_action_open, folder.name)
    val description = pluralStringResource(
        R.plurals.vaultlist_folder_row_description,
        itemCount,
        label,
        itemCount,
    )
    // The ring says what a drop would do. Jade accepts, coral refuses, and the refusal is silent
    // and visual on purpose: a modal error interrupts a gesture the user is still holding.
    val ring by animateColorAsState(
        targetValue = when (hoverState) {
            FolderRowHover.Accepts -> colors.jade
            FolderRowHover.Rejects -> colors.coral
            FolderRowHover.None -> colors.ring
        },
        animationSpec = lkTween(LkMotion.Instant),
        label = "folder-drop-ring",
    )
    // A cause, not decoration: the row grows very slightly to say it is the thing that would
    // receive the drop. Collapses to nothing under reduced motion — `lkTween` returns a zero
    // duration there, so the row snaps to its hovered size and the *state* is still legible.
    val scale by animateFloatAsState(
        targetValue = if (hoverState == FolderRowHover.Accepts) 1.02f else 1f,
        animationSpec = lkTween(LkMotion.Instant),
        label = "folder-drop-scale",
    )
    // Boxed only so the menu anchors to this row. The row keeps the caller's modifier, so its
    // bounds — which are what the drop target reports — are unchanged by the box existing.
    Box {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .then(dropTarget)
            .lkSurface(LkShape.lg, colors.surface, ring)
            // `lkPressAndDrag`, not `clickable`: a folder is a payload as well as a destination
            // (§4.1), and until this row had the gesture, `FolderDragPayload` was a type nothing
            // could produce — the vault list's whole folder-move path was reachable only from the
            // accessibility action. Found by driving the app: the ghost, the acceptance rules and
            // the write were all in place and no gesture ever created one.
            //
            // A hold released without moving opens the menu, which is what a hold does on an item
            // row and what it did not do here — a folder row's hold dead-ended, so rename, move and
            // trash existed on this row for a screen reader and for nobody else. Firing the trash
            // directly instead would put an irreversible-looking write behind an accidental hold;
            // the menu is the version of that which asks first.
            .lkPressAndDrag(
                interactionSource = interaction,
                onClick = onClick,
                onClickLabel = openLabel,
                onLongPressRelease = { if (hasMenu) menuExpanded = true },
                onLongPressLabel = openLabel,
                onDragStart = onDragStart,
                onDragArmed = { grab, origin ->
                    haptics.play(Haptic.SELECT)
                    onDragArmed(grab, origin)
                },
                onDragCancel = onDragCancel,
                dragEnabled = dragEnabled,
            )
            .minTouchTarget()
            .padding(horizontal = LkSpace.md, vertical = LkSpace.sm)
            // One announcement for the row rather than three fragments: the name, the count and the
            // chevron are one thing to a screen reader, and the chevron is not a separate control.
            //
            // `clearAndSetSemantics` keeps the custom actions set *inside its own block* — it clears
            // descendants, not itself — which is why the folder-trash action can live here. Plain
            // `semantics { }` would default `mergeDescendants = false` and split this row back into
            // the three TalkBack stops the line above exists to prevent.
            .clearAndSetSemantics {
                contentDescription = description
                // Drag is gesture-only, so each thing it can do to a folder needs a route that is
                // not a gesture. Constructive first, destructive last, matching the item row.
                val actions = buildList {
                    onRename?.let { add(CustomAccessibilityAction(renameActionLabel) { it(); true }) }
                    onMove?.let { add(CustomAccessibilityAction(moveActionLabel) { it(); true }) }
                    if (hidden && onStopHiding != null) {
                        add(CustomAccessibilityAction(stopHidingLabel) { onStopHiding(); true })
                    } else {
                        onHide?.let { add(CustomAccessibilityAction(hideActionLabel) { it(); true }) }
                    }
                    onLongPress?.let { add(CustomAccessibilityAction(trashActionLabel) { it(); true }) }
                }
                if (actions.isNotEmpty()) customActions = actions
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cross-fades to an open folder while a payload is over it. A second glyph rather than a
        // tint alone, because a colour shift is the one signal a user with a colour-vision
        // deficiency may not get, and this is the row saying "I will take that".
        Box(contentAlignment = Alignment.Center) {
            // The dwell ring: real elapsed time toward the 600 ms that opens this folder, drawn as
            // an arc around the icon so the wait has a visible end rather than being a guess.
            //
            // **`animated = false`, and that is what makes it reduced-motion safe** — not an
            // exemption. The precedent is `LkDeriving`, which passes the same flag so "the arc jumps
            // to each checkpoint the instant it lands". This reports real progress in discrete
            // steps with no tween, which is not the kind of animation someone who turned animations
            // off was turning off. The two indicators that *do* disappear under reduced motion,
            // `LkSkeleton` and `LkPulseHalo`, are the ones that only pulse and report nothing.
            if (dwellProgress > 0f && hoverState == FolderRowHover.Accepts) {
                LkProgressRing(
                    progress = dwellProgress,
                    size = 28.dp,
                    strokeWidth = 2.dp,
                    color = colors.jade,
                    animated = false,
                )
            }
            Icon(
                imageVector = if (hoverState == FolderRowHover.Accepts) LkIcons.FolderOpen else LkIcons.Folder,
                contentDescription = null,
                tint = if (hoverState == FolderRowHover.Accepts) colors.jade else colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(LkSpace.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        // Amber, not coral: a concealed folder surfaced in a reveal session is a state, not a
        // failure. The marker is decorative — the row's contentDescription already names it — so it
        // is cleared from semantics to keep the row one TalkBack stop.
        if (hidden) {
            Text(
                text = stringResource(R.string.vaultlist_marker_hidden),
                style = MaterialTheme.typography.labelMedium,
                color = colors.amber,
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .padding(end = LkSpace.xs),
            )
        }
        // Count and chevron move together as one cluster, the way the item row's copy pill and
        // overflow do — shifted apart they would be pushed to the same target and land on top of
        // each other.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .onGloballyPositioned { clusterAnchor = it.boundsInWindow() }
                .offset {
                    fabDodgeOffset(
                        fab = avoidanceBounds(),
                        cluster = clusterAnchor,
                        gapPx = FolderRowFabGap.toPx(),
                    )
                },
        ) {
            Text(
                text = itemCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
            Spacer(Modifier.width(LkSpace.xs))
            Icon(
                imageVector = LkIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    // The same three actions the semantics block above already carries, in the same order:
    // constructive first, destructive last. A screen reader had them before a thumb did.
    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        onRename?.let { rename ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vaultlist_folder_action_rename)) },
                onClick = { menuExpanded = false; rename() },
            )
        }
        onMove?.let { move ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vaultlist_item_action_move_to_folder)) },
                onClick = { menuExpanded = false; move() },
            )
        }
        // Directly-hidden folder: offer to stop. Otherwise offer to hide. Never both.
        if (hidden && onStopHiding != null) {
            DropdownMenuItem(
                text = { Text(stopHidingLabel, color = colors.amber) },
                onClick = { menuExpanded = false; onStopHiding() },
            )
        } else {
            onHide?.let { hide ->
                DropdownMenuItem(
                    text = { Text(hideActionLabel) },
                    onClick = { menuExpanded = false; hide() },
                )
            }
        }
        onLongPress?.let { trash ->
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.vaultlist_menu_move_to_trash),
                        color = colors.coral,
                    )
                },
                onClick = { menuExpanded = false; trash() },
            )
        }
    }
    }
}

/**
 * How many folder rows a list shows before it folds the rest away behind one control.
 *
 * Folders are navigation, and navigation sits above the results it filters — so an unbounded folder
 * list pushes the items off the screen entirely. A vault whose top level holds twenty folders opened
 * onto twenty rows of chrome and no content, which is the "where are my items" version of this
 * screen. Three is the count that still reads as a list rather than a single suggestion, and it is
 * the number the design asks for.
 *
 * The cap applies wherever folder rows are listed — the top level, inside any folder, under any type
 * filter — because they are all the same list. An earlier attempt capped only a separate strip of
 * root-folder chips that sat beside these rows, which capped the copy of the list nobody was
 * complaining about; the strip is gone and this is the list.
 */
internal const val FolderRowCap = 3

/** The folder rows actually rendered: everything when [expanded], the first [FolderRowCap] otherwise. */
internal fun <T> foldersShown(folders: List<T>, expanded: Boolean): List<T> =
    if (expanded) folders else folders.take(FolderRowCap)

/**
 * How many folders the fold is currently hiding — zero when [expanded], and zero when the list is
 * short enough that nothing was folded. The overflow control renders only when this is positive or
 * the list is expanded, so a list of exactly three never grows a control that would do nothing.
 */
internal fun foldersFoldedAway(total: Int, expanded: Boolean): Int =
    if (expanded) 0 else (total - FolderRowCap).coerceAtLeast(0)

/** True when the folder list is long enough for the fold to be a real choice, expanded or not. */
internal fun folderOverflowVisible(total: Int): Boolean = total > FolderRowCap

/**
 * The one control that unfolds the folder list and folds it back.
 *
 * Deliberately not shaped like a folder row: no surface, no count, no chevron pointing right. It
 * sits in the same left gutter as the rows above it — the caret takes the folder icon's slot, so
 * its label starts on the same vertical line as the folder names — but it is jade rather than
 * neutral, which is this palette's way of saying a thing is an action rather than a destination.
 */
@Composable
internal fun FolderOverflowRow(
    foldedAway: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LkTheme.colors
    val label = if (expanded) {
        stringResource(R.string.vaultlist_folders_show_fewer)
    } else {
        pluralStringResource(R.plurals.vaultlist_folders_show_more, foldedAway, foldedAway)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .minTouchTarget()
            .padding(horizontal = LkSpace.md, vertical = LkSpace.sm)
            // One stop, and the label is the whole announcement: the caret says nothing a screen
            // reader needs that "Show 4 more folders" has not already said.
            .clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One glyph turned over rather than two, and the turn is animated because it has a cause:
        // it is the fold opening and closing, drawn on the control that did it. `lkTween` collapses
        // it to an instant flip when animations are off.
        val caret by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = lkTween(LkMotion.Quick),
            label = "folder-overflow-caret",
        )
        Icon(
            imageVector = LkIcons.ChevronDown,
            contentDescription = null,
            tint = colors.jade,
            modifier = Modifier
                .size(20.dp)
                .rotate(caret),
        )
        Spacer(Modifier.width(LkSpace.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.jade,
        )
    }
}

/**
 * Where an item sits, written out from the folder being browsed down to the item's own folder.
 *
 * A folder list shows its whole subtree, so browsing one folder can surface an item that actually
 * lives four levels below it — and the row named only the folder immediately holding it, which is a
 * name the user cannot see anywhere on screen and cannot navigate to. Reported from a phone on
 * 2026-08-18: *"the bottom one has an entry, when I enter the first folder I see it but I don't see
 * the path to it"*.
 *
 * Relative to the current scope rather than absolute: the crumbs above already say where you are,
 * and repeating them on every row spends the row's narrowest column restating the screen's title.
 * At the root the trail is the item's full path, which is right — there are no crumbs there.
 *
 * Long paths keep the ends and elide the middle, which is the half a person actually reads: the
 * folder it is in, and the branch it went down. Returns null for an item in no folder and for one
 * sitting directly in the folder being browsed — in both cases the screen has already said it.
 */
internal fun VaultListUiState.folderTrailFor(item: VaultItem, ellipsis: String): String? {
    val folderId = item.folderId ?: return null
    val path = FolderTree.pathOf(folders, folderId) ?: return null
    val scope = folderStack.lastOrNull()
    val below = if (scope == null) path else path.dropWhile { it.id != scope }.drop(1)
    return trailOf(below.map { it.name }, ellipsis)
}

/**
 * The same trail with no browse scope to measure against — the item's whole path.
 *
 * Trash uses this. Its rows said only the item's title and type, so Restore was blind until it
 * landed: two logins called "Bar 2" from different folders are the same row, on the one screen
 * where the choice is restore-or-destroy (2C-F8, on a device 2026-09-20). The vault list has named
 * the folder on every row since 2026-08-18 and this screen simply never inherited it.
 *
 * [folders] must include the *trashed* folders as well as the live ones: an item trashed along with
 * its folder has a `folderId` that resolves to neither `liveFolders` nor anything else the trash
 * screen would otherwise hold, and a path that cannot be resolved returns null rather than a
 * half-written one.
 */
internal fun folderTrail(folders: List<Folder>, folderId: UUID?, ellipsis: String): String? {
    val id = folderId ?: return null
    val path = FolderTree.pathOf(folders, id) ?: return null
    return trailOf(path.map { it.name }, ellipsis)
}

/** The shared shape of a trail: spell it out, or keep the ends and elide the middle. */
private fun trailOf(names: List<String>, ellipsis: String): String? = when {
    names.isEmpty() -> null
    names.size <= MAX_TRAIL_SEGMENTS -> names.joinToString(TRAIL_SEPARATOR)
    else -> listOf(names.first(), ellipsis, names.last()).joinToString(TRAIL_SEPARATOR)
}

/**
 * How many folder names a trail spells out before it elides.
 *
 * Three, because that is the longest that still reads as a place rather than as a string to parse —
 * and because the elided form is itself three segments, so the column never has to grow past what
 * it was already sized for.
 */
private const val MAX_TRAIL_SEGMENTS = 3

private const val TRAIL_SEPARATOR = " / "

/** The parent name a duplicate-named folder is disambiguated by, or null when it is unique here. */
internal fun VaultListUiState.disambiguatorFor(folder: Folder): String? =
    if (!isAmbiguous(folder)) {
        null
    } else {
        folder.parentId?.let { parentId -> folders.firstOrNull { it.id == parentId }?.name }
    }

/** The folder ids on the browse path, for a test to name without reaching into the list. */
internal fun VaultListUiState.stackIds(): List<UUID> = folderStack

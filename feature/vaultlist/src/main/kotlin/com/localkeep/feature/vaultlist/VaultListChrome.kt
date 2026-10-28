package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSearchField
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme

/** Air kept between the search row's last control and a resting FAB once the row has made room. */
private val FabRowGap = 8.dp

/**
 * How much of its width a row must give up to end clear of the FAB, or zero when it already does.
 *
 * The header scrolls with the list now, so a control in it can come to rest under the FAB exactly as
 * a row's trailing cluster can — and unlike a row's, it cannot slide left without landing on its
 * neighbour. So the row does not move a control, it gives up its *end*: the weighted field gives
 * way and the buttons beside it stay together. Capped at half the row so a very wide FAB reach
 * never reduces the field to nothing.
 *
 * [row] is the row's own full-width box, measured before any reserve this returns is applied —
 * the same rule `fabDodgeOffset` states for a row's cluster, for the same reason: measured after,
 * the reserve would be its own trigger and the row would breathe in and out.
 */
internal fun fabEndReserve(fab: Rect?, row: Rect?, gapPx: Float): Int {
    if (fab == null || row == null) return 0
    if (!row.overlaps(fab)) return 0
    val reserve = row.right + gapPx - fab.left
    return reserve.coerceIn(0f, row.width / 2f).toInt()
}

/**
 * Makes a full-width row end clear of a resting FAB.
 *
 * [fab] is read at measure time, never at composition: it is the list's own `fabDodgeBounds`, which
 * is null for the whole of a scroll, so nothing in the row moves under a moving finger and the row
 * is only ever narrowed while it is at rest and the FAB is actually on top of it. The row reports
 * its full width to its parent whatever it gives up, which is what keeps the box it is measured
 * from independent of the reserve.
 */
@Composable
internal fun Modifier.clearOfFab(fab: () -> Rect?): Modifier {
    var row by remember { mutableStateOf<Rect?>(null) }
    val gap = with(LocalDensity.current) { FabRowGap.toPx() }
    return this
        .onGloballyPositioned { row = it.boundsInWindow() }
        .layout { measurable, constraints ->
            val reserve = if (constraints.hasBoundedWidth) {
                fabEndReserve(fab(), row, gap).coerceAtMost(constraints.maxWidth)
            } else {
                0
            }
            val placeable = measurable.measure(
                constraints.copy(
                    minWidth = (constraints.minWidth - reserve).coerceAtLeast(0),
                    maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth - reserve else constraints.maxWidth,
                ),
            )
            layout(placeable.width + reserve, placeable.height) { placeable.place(0, 0) }
        }
}

/**
 * The search field with the two filter controls beside it.
 *
 * Lifted out of [VaultListScreen] so that what has to hold at the narrowest window — that the
 * Filter button is never left under the FAB at rest (SCL-007) — can be measured by a Robolectric
 * test; the screen itself is Hilt-backed and never reaches idle there.
 */
@Composable
internal fun VaultSearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    favouritesOn: Boolean,
    onToggleFavourites: () -> Unit,
    sheetFilterOn: Boolean,
    onOpenFilter: () -> Unit,
    modifier: Modifier = Modifier,
    searchModifier: Modifier = Modifier,
    fab: () -> Rect? = { null },
) {
    val c = LkTheme.colors
    val fontScale = LocalDensity.current.fontScale
    // The field's own composable, parameterised on the modifier that places it: weighted beside the
    // two buttons in the single row, full width above them in the stacked one.
    val field: @Composable (Modifier) -> Unit = { placement ->
        LkSearchField(
            value = query,
            onValueChange = onQueryChange,
            // The one-word default placeholder is what shows; the full scope is
            // the field's accessible name only. The long string was written for a
            // full-width well and clipped mid-list once the star and the filter
            // button moved in beside it — in German, past the verb, so the field
            // no longer said "search" in any word a sighted user could see.
            label = stringResource(R.string.vaultlist_search_placeholder),
            // Typing the reveal phrase here is how a reveal session starts — the
            // flow that proves the make-invisible feature has to find this field by
            // tag, not by the placeholder text, which is a different string in
            // every language.
            modifier = searchModifier
                .then(placement)
                .lkTag("vaultlist-search"),
            // The reveal-phrase entry point (F44, 2026-09-13): Password suppresses
            // the IME's own learning and suggestion strip for whatever is typed here,
            // ordinary queries and a reveal phrase alike. See LkSearchField's KDoc for
            // what this does and does not defend against.
            keyboardType = KeyboardType.Password,
        )
    }
    val controls: @Composable () -> Unit = {
        // Two axes, and they used to look like one row of identical chips.
        // Favourites narrows *within* a type ("favourite notes" is a real query),
        // so setting it and then a type leaves both on and ANDs them — which is
        // correct, and was completely illegible: a user who did exactly that got
        // "No matching items" with nothing on screen saying two filters were
        // active. Now the strip below names each one. Favourites stays a one-tap
        // star here rather than moving into the sheet: it is a boolean modifier,
        // not a tenth type, and its brass accent keeps saying so.
        LkIconButton(
            icon = LkIcons.Star,
            label = stringResource(R.string.vaultlist_chip_favourites),
            onClick = onToggleFavourites,
            checked = favouritesOn,
            tint = if (favouritesOn) c.brass else c.textSecondary,
            container = if (favouritesOn) c.brass.copy(alpha = 0.12f) else c.field,
            ring = if (favouritesOn) c.brass.copy(alpha = 0.26f) else c.ring,
            modifier = Modifier.lkTag("vaultlist-favourites"),
        )
        // Beside the search field, deliberately not in the header's trailing slot:
        // `compactTitle` reduces that header to icons in a short window, and a
        // filter control living there would have to earn its place against the two
        // that survive. Here it survives rotation by construction.
        // Lit for every axis the sheet owns — type, tag, invisible-only — and not
        // for Favourites, which has its own star.
        LkIconButton(
            icon = LkIcons.Filter,
            label = stringResource(R.string.vaultlist_filter_open),
            onClick = onOpenFilter,
            tint = if (sheetFilterOn) c.jade else c.textSecondary,
            container = if (sheetFilterOn) c.jade.copy(alpha = 0.12f) else c.field,
            ring = if (sheetFilterOn) c.jade.copy(alpha = 0.26f) else c.ring,
            modifier = Modifier.lkTag("vaultlist-filter"),
        )
    }
    // One row, or two. Beside the star and the Filter button the well has the row minus two 48dp
    // targets and their gaps, and once there is text it also pays for its own Clear button: at 308dp
    // and font scale 2.0 that left the editable area 48dp wide, the first letter of what was typed
    // half cut (SCL-024). Then the two buttons drop to a row of their own below the field and the
    // field takes the full width. Each row ends clear of a resting FAB on its own, because the FAB
    // can be on either.
    //
    // Decided on the room the query actually gets — [searchRowStacks] — not on [LkText.stacks]. That
    // rule stacked at font scale 1.5 on any width, so a 360dp Galaxy S20 at a large text size put
    // the two buttons alone on a line under a field that had room to spare beside them (2026-10-28).
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (!searchRowStacks(maxWidth, fontScale)) {
            Row(
                modifier = Modifier.clearOfFab(fab).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
            ) {
                field(Modifier.weight(1f))
                controls()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                Box(Modifier.clearOfFab(fab).fillMaxWidth()) { field(Modifier.fillMaxWidth()) }
                Row(
                    modifier = Modifier.clearOfFab(fab).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LkSpace.sm, Alignment.End),
                ) {
                    controls()
                }
            }
        }
    }
}

/** The star and the Filter button beside the well: two 48dp targets and their two gaps. */
private val SearchControlsWidth = 48.dp * 2 + LkSpace.sm * 2

/**
 * What [LkSearchField]'s well spends that is not query text, with text in it: 15dp of padding at
 * each end, the 18dp search glyph and its 12dp gap, and the 24dp Clear button with its own.
 */
private val SearchWellChrome = 15.dp * 2 + 18.dp + 12.dp + 24.dp + 12.dp

/**
 * The query width below which the buttons leave the field's line, per unit of font scale. 100dp at
 * 2.0 is SCL-024's floor; scaling it keeps roughly the same five or six characters visible at any
 * text size, so a large-but-not-largest setting is not stacked for room it already has.
 */
private val SearchQueryFloor = 50.dp

internal fun searchRowStacks(rowWidth: Dp, fontScale: Float): Boolean =
    rowWidth - SearchControlsWidth - SearchWellChrome < SearchQueryFloor * fontScale.coerceAtLeast(1f)

/**
 * The screen title with its two controls — the drawer button before it, Lock after it — and the
 * count line under it.
 *
 * When the controls would starve the title, they get a row of their own above it. One slot, three
 * siblings: at 308dp and font scale 2.0 the Lock button (icon and the word "Sperren") took 304 of
 * 626px and left "Zugangsdaten" 157px, so the heading — hyphenating correctly — broke as
 * "Zu-/gan/gs-/da-/ten": `gangs-` did not fit the slot, and a hyphenator only offers whole
 * syllables, so the line broke mid-syllable with no hyphen (SCL-019, device pass 2026-10-19). A
 * hyphenating style cannot fix a slot that is narrower than a syllable. The rule is the one the
 * rest of the app uses for a graphic beside a caption — [LkText.stacks] — so a 360dp phone at
 * normal text renders the single row the comps draw.
 */
@Composable
internal fun VaultTitleBar(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (!LkText.stacks(fontScale, maxWidth)) {
            LkScreenHeader(title = title, subtitle = subtitle, leading = leading, trailing = trailing)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    leading()
                    trailing()
                }
                LkScreenHeader(title = title, subtitle = subtitle)
            }
        }
    }
}

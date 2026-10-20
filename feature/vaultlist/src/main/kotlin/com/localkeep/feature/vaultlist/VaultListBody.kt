package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkEnter

/** The list's own side padding. Rows sit inside it; the header, the crumb and the empty panel bleed past it. */
internal val ListSideInset = 12.dp

/**
 * How many `LazyColumn` rows precede the first folder row: the header, plus the sticky breadcrumb
 * and the error line when they are shown. Everything that converts an item index into a row index
 * has to add this.
 */
internal fun leadingListRows(hasCrumb: Boolean, hasNotice: Boolean): Int =
    1 + (if (hasCrumb) 1 else 0) + (if (hasNotice) 1 else 0)

/** Takes back [inset] of the parent's side padding: measured [inset] wider each side, placed [inset] left. */
internal fun Modifier.bleedPast(inset: Dp): Modifier = layout { measurable, constraints ->
    val bleed = inset.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.offset(horizontal = 2 * bleed) else constraints
    val placeable = measurable.measure(wide)
    val width = if (constraints.hasBoundedWidth) placeable.width - 2 * bleed else placeable.width
    layout(width, placeable.height) { placeable.place(-bleed, 0) }
}

/**
 * Keeps this node's bottom edge above the keyboard, by exactly the part of the keyboard that is
 * actually over it.
 *
 * Not `imePadding()`, and the difference is the whole of L-02. `imePadding()` subtracts the IME
 * inset minus whatever insets an ancestor has *consumed* — and the shell pads this screen above its
 * floating tab bar with a plain `padding(innerPadding)`, which consumes nothing. So the full
 * keyboard height was taken a second time on top of the bar's own slot: at 308x548dp, font 2.0, the
 * list stopped at y=390 against a keyboard top of 619, a 229px blank band that cut the focused
 * search field in half (device pass 2026-10-19, `REG-P1-search-ime.png`).
 *
 * This reads where the node actually ends in the window and shrinks it by only what pokes past the
 * keyboard's top edge, so it is right whatever an ancestor did or did not consume — the tab bar
 * today, anything the shell pads with tomorrow. The node still reports its full size to its parent,
 * which is what keeps the position it measures from depending on the padding it applies; only its
 * child is given less.
 */
@Composable
internal fun Modifier.aboveKeyboard(): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    var bottomInWindow by remember { mutableIntStateOf(0) }
    return this
        .onGloballyPositioned { bottomInWindow = (it.positionInWindow().y + it.size.height).toInt() }
        .layout { measurable, constraints ->
            val keyboardTop = view.rootView.height - ime.getBottom(density)
            val overlap = if (constraints.hasBoundedHeight && ime.getBottom(density) > 0) {
                (bottomInWindow - keyboardTop).coerceIn(0, constraints.maxHeight)
            } else {
                0
            }
            val placeable = measurable.measure(
                constraints.copy(
                    minHeight = (constraints.minHeight - overlap).coerceAtLeast(0),
                    maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight - overlap else constraints.maxHeight,
                ),
            )
            layout(placeable.width, placeable.height + overlap) { placeable.place(0, 0) }
        }
}

/**
 * The vault list's skeleton: the header, the breadcrumb, the error line and the rows.
 *
 * Lifted out of [VaultListScreen] so the one thing that has to hold at the narrowest window at the
 * largest text — that the rows are reachable — can be measured by a Robolectric test; the screen
 * itself is Hilt-backed and never reaches idle there.
 */
@Composable
internal fun VaultListBody(
    listState: LazyListState,
    onCrumbHeight: (Int) -> Unit,
    header: @Composable () -> Unit,
    crumb: (@Composable () -> Unit)?,
    notice: (@Composable () -> Unit)?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** Applied to the header row — the screen hangs a do-nothing drop target on it, see the call site. */
    headerModifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val bleed = Modifier.bleedPast(ListSideInset)
    // The header's own gap to what follows it, and the 14dp the list used to start below the pinned
    // header — carried by whichever leading row is last, so the rhythm is the same with or without
    // a crumb and an error line.
    val tail = 14.dp
    LazyColumn(
        state = listState,
        modifier = Modifier.aboveKeyboard().then(modifier).fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "list-header") {
            Column(
                modifier = bleed
                    .then(headerModifier)
                    .padding(horizontal = LkFrame.screenHorizontal)
                    .padding(
                        top = LkFrame.screenTop,
                        // The gap above the crumb lives here, not on the crumb: stuck to the top of
                        // the window the crumb should start at the edge, not 16dp down it.
                        bottom = when {
                            crumb != null || notice != null -> LkSpace.md
                            else -> tail
                        },
                    )
                    .lkEnter(),
                verticalArrangement = Arrangement.spacedBy(LkSpace.md),
            ) {
                header()
            }
        }
        if (crumb != null) {
            // Sticky, and solid: rows slide under it, so it has to hide them. `ground` is the colour
            // the screen paints behind everything.
            stickyHeader(key = "list-crumb") {
                Box(
                    bleed
                        .fillMaxWidth()
                        .background(LkTheme.colors.ground)
                        .padding(horizontal = LkFrame.screenHorizontal)
                        // Less than the 14dp the unpinned list used to start below the crumb: this
                        // band is solid and stays on screen while the crumb is stuck, where every
                        // dp of it is a dp the rows do not get.
                        .padding(bottom = if (notice == null) LkSpace.sm else 0.dp)
                        .onSizeChanged { onCrumbHeight(it.height) },
                ) { crumb() }
            }
        }
        if (notice != null) {
            item(key = "list-notice") {
                Box(
                    bleed
                        .fillMaxWidth()
                        .padding(horizontal = LkFrame.screenHorizontal)
                        .padding(top = LkSpace.md, bottom = tail),
                ) { notice() }
            }
        }
        content()
    }
}

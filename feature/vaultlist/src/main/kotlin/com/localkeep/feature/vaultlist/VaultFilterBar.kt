package com.localkeep.feature.vaultlist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.theme.LkChip
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSheet
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkSurface

/**
 * One narrowing the list is under right now, as the token strip below the search field names it.
 *
 * Only the axes the user cannot otherwise see, and that is the whole point of the strip: the type
 * and Favourites used to sit in one row of identical chips and were read as one axis, so a user who
 * set both got "No matching items" with nothing on screen saying two filters were on. Search has
 * its own field and the browsed folder has the breadcrumb, so neither is repeated here — repeating
 * the folder was tried in the count line and read as calling navigation a filter (`countLabel`,
 * 2026-08-12).
 *
 * [Tag] carries the tag as authored: it is user data, shown verbatim, never a resource. [Hidden] can
 * only exist inside a reveal session — see `FilterSheetContent` for why the control behind it is
 * composed only then.
 */
internal sealed interface ActiveFilter {
    data class Type(val filter: ItemTypeFilter) : ActiveFilter
    data object Favourites : ActiveFilter
    data class Tag(val name: String) : ActiveFilter
    data object Hidden : ActiveFilter
}

/**
 * The tokens the strip shows, in a fixed order — type, Favourites, tag, invisible-only — so they
 * never shuffle under a finger. Pure so the strip's contents can be pinned without a repository —
 * see `ActiveFilterTokensTest`.
 */
internal fun VaultListUiState.activeFilters(): List<ActiveFilter> = listOfNotNull(
    typeFilter?.let { ActiveFilter.Type(it) },
    ActiveFilter.Favourites.takeIf { showFavoritesOnly },
    tagFilter?.let { ActiveFilter.Tag(it) },
    ActiveFilter.Hidden.takeIf { showHiddenOnly },
)

/**
 * The glyph each type filter stands behind — in the sheet, and on its token.
 *
 * Moved here from the command drawer when the drawer's ITEMS rows came out (2026-09-02); the map
 * was `private` in `app`, and `app → feature:vaultlist` is the dependency direction, so a sheet in
 * this module could not have reached it there.
 */
internal val ItemTypeFilter.icon: ImageVector
    get() = when (this) {
        ItemTypeFilter.LOGIN -> LkIcons.Key
        ItemTypeFilter.TOTP -> LkIcons.Clock
        ItemTypeFilter.CARD -> LkIcons.Card
        ItemTypeFilter.IDENTITY -> LkIcons.Identity
        ItemTypeFilter.NOTE -> LkIcons.Note
        ItemTypeFilter.FILE -> LkIcons.Attachment
        // The Passkey glyph, matching ItemContentIcon.kt's typeIcon() — one concept, one icon, which
        // is the rule established on 2026-09-13 when this branch drew
        // LkIcons.Fingerprint against a row drawing LkIcons.Key.
        //
        // What that fix settled on was LkIcons.Key for both, and F-13 (2026-09-16) is why it moved
        // again: this sheet lists Logins directly above Passkeys, and the two rows were a
        // pixel-identical key. The rule is unchanged — both sides of the concept moved together —
        // only the glyph is new, and LkIcons.Passkey exists for nothing else.
        ItemTypeFilter.PASSKEY -> LkIcons.Passkey
        ItemTypeFilter.COORDINATE -> LkIcons.Pin
        ItemTypeFilter.PHRASE -> LkIcons.SeedPhrase
    }

/**
 * The active-filter strip: one removable token per narrowing, or nothing.
 *
 * Every token is a selected [LkChip] with a close glyph after its label, so it carries the same
 * check-plus-semantics the chip row did — colour was measured at 1.21:1 between the chip's two
 * states and has already failed here once as the only carrier. Tapping a token is the only thing
 * it can do, and `toggleable` announces it as on, which is what a screen reader needs to hear to
 * know a tap turns it off.
 *
 * Every token is also *named* — "Remove filter: Logins" — because its visible label is a bare
 * category name. Without the name, a screen reader stopped on the token and heard "Logins", with
 * nothing saying it was a filter or that activating it removes one, while the star and the filter
 * button either side of it announced properly (measured in a `uiautomator` dump, 2026-09-02).
 * The name goes through [LkChip]'s own parameter so it lands on the toggleable node, not on a
 * child adrift from the action.
 *
 * Horizontally scrollable so the strip never wraps to a second line and pushes the list down.
 * `minTouchTarget()` on the row is what reserves the strip's height while it lingers empty — see
 * the caller for why it lingers.
 */
@Composable
internal fun ActiveFilterStrip(
    filters: List<ActiveFilter>,
    onRemove: (ActiveFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .minTouchTarget()
            .horizontalScroll(rememberScrollState())
            .lkTag("vaultlist-filter-strip"),
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        filters.forEach { filter ->
            when (filter) {
                is ActiveFilter.Type -> LkChip(
                    text = stringResource(filter.filter.labelRes),
                    contentDescription = stringResource(
                        R.string.vaultlist_filter_token_remove,
                        stringResource(filter.filter.labelRes),
                    ),
                    selected = true,
                    onClick = { onRemove(filter) },
                    trailing = LkIcons.Close,
                    modifier = Modifier.lkTag("filter-token-${filter.filter.name.lowercase()}"),
                )
                // Brass, as the star beside the search field is: the cross-axis modifier keeps its
                // own accent so the strip does not flatten the two axes back into one colour.
                ActiveFilter.Favourites -> LkChip(
                    text = stringResource(R.string.vaultlist_chip_favourites),
                    contentDescription = stringResource(
                        R.string.vaultlist_filter_token_remove,
                        stringResource(R.string.vaultlist_chip_favourites),
                    ),
                    selected = true,
                    onClick = { onRemove(filter) },
                    accent = c.brass,
                    trailing = LkIcons.Close,
                    modifier = Modifier.lkTag("filter-token-favourites"),
                )
                // The tag as authored, and no leading glyph: a selected LkChip draws its check in
                // the icon slot, so one would never show. What says this is a tag and not a tenth
                // type is the count line ("filter: tag work") and the sheet the token came from.
                is ActiveFilter.Tag -> LkChip(
                    text = filter.name,
                    contentDescription = stringResource(R.string.vaultlist_filter_token_remove, filter.name),
                    selected = true,
                    onClick = { onRemove(filter) },
                    trailing = LkIcons.Close,
                    modifier = Modifier.lkTag("filter-token-tag"),
                )
                ActiveFilter.Hidden -> LkChip(
                    text = stringResource(R.string.vaultlist_filter_hidden_only),
                    contentDescription = stringResource(
                        R.string.vaultlist_filter_token_remove,
                        stringResource(R.string.vaultlist_filter_hidden_only),
                    ),
                    selected = true,
                    onClick = { onRemove(filter) },
                    trailing = LkIcons.Close,
                    modifier = Modifier.lkTag("filter-token-hidden"),
                )
            }
        }
    }
}

/**
 * The filter sheet: every item type (single-select), the vault's tags (single-select), the
 * invisible-only switch inside a reveal session, and "Clear all".
 *
 * Instant-apply like every other filter control in the app, and it closes on a pick because a
 * single-select has nothing left to say once one row is chosen — the tap cost of filtering is
 * meant to go from one to two, not three. Favourites is deliberately not here: it is a modifier
 * that composes with a type ("favourite notes" is a real query), it keeps its one-tap star beside
 * the search field, and charging it a sheet round-trip would be this change's clearest regression.
 *
 * Pro types keep the brass padlock the drawer's rows used to carry. The row still works — picking
 * Cards shows the Pro pitch in the list, the same screen the drawer's destination showed — it is
 * the item type that is Pro, and disabling the row would hide that the feature exists.
 */
@Composable
internal fun FilterSheet(
    state: VaultListUiState,
    onPickType: (ItemTypeFilter?) -> Unit,
    onPickTag: (String?) -> Unit,
    onToggleHiddenOnly: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    LkSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.vaultlist_filter_sheet_title),
        modifier = Modifier.lkTag("vaultlist-filter-sheet"),
    ) {
        FilterSheetContent(
            state = state,
            onPickType = onPickType,
            onPickTag = onPickTag,
            onToggleHiddenOnly = onToggleHiddenOnly,
            onClearAll = onClearAll,
        )
    }
}

/**
 * The sheet's body, separate from the modal that hosts it so `FilterSheetContentTest` can render it
 * on its own — what the sheet offers is a function of [state], and that is the contract to pin.
 *
 * **The invisible-only control is composed only while [VaultListUiState.revealSessionActive].**
 * That is a security decision, not a layout one. Hidden entries sit behind a reveal phrase typed
 * into the search field, and the feature's value is that nothing on screen says they exist. A
 * switch that was always in this sheet — disabled, or a no-op — would announce to anyone who
 * opened it that there is something to show, which is exactly what a coerced unlock must not learn.
 * Inside a session the entries are already on screen under an amber banner, so the control leaks
 * nothing that is not already visible. Guarded a second time in the ViewModel, which ignores the
 * toggle outside a session.
 *
 * The tags section is omitted outright when the vault has none: an empty heading is a control that
 * does nothing. The tag values are user data, shown as authored; only the heading is a resource.
 */
@Composable
internal fun ColumnScope.FilterSheetContent(
    state: VaultListUiState,
    onPickType: (ItemTypeFilter?) -> Unit,
    onPickTag: (String?) -> Unit,
    onToggleHiddenOnly: () -> Unit,
    onClearAll: () -> Unit,
) {
    LkSectionLabel(
        text = stringResource(R.string.vaultlist_filter_section_type),
        modifier = Modifier.padding(horizontal = LkSpace.md),
    )
    OptionRow(
        label = stringResource(R.string.vaultlist_chip_all),
        icon = LkIcons.Layers,
        selected = state.typeFilter == null,
        tag = "filter-type-all",
        onClick = { onPickType(null) },
    )
    ItemTypeFilter.entries.forEach { filter ->
        OptionRow(
            label = stringResource(filter.labelRes),
            icon = filter.icon,
            selected = state.typeFilter == filter,
            tag = "filter-type-${filter.name.lowercase()}",
            onClick = { onPickType(filter) },
        )
    }
    if (state.availableTags.isNotEmpty()) {
        Spacer(Modifier.height(LkSpace.md))
        LkSectionLabel(
            text = stringResource(R.string.vaultlist_filter_section_tags),
            modifier = Modifier.padding(horizontal = LkSpace.md),
        )
        // Chips rather than rows: a tag is one word, and a vault can have dozens. Each chip is
        // toggleable, so the selected one announces as on and a second tap turns it off — that is
        // what makes a single-select work without an "any tag" row.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LkSpace.md)
                .lkTag("filter-tags"),
            horizontalArrangement = Arrangement.spacedBy(LkSpace.xs),
        ) {
            state.availableTags.forEach { tag ->
                val selected = state.tagFilter == tag
                LkChip(
                    text = tag,
                    selected = selected,
                    onClick = { onPickTag(if (selected) null else tag) },
                    icon = LkIcons.Tag,
                    modifier = Modifier.lkTag("filter-tag-$tag"),
                )
            }
        }
    }
    if (state.revealSessionActive) {
        Spacer(Modifier.height(LkSpace.md))
        LkSectionLabel(
            text = stringResource(R.string.vaultlist_filter_section_hidden),
            modifier = Modifier.padding(horizontal = LkSpace.md),
        )
        OptionRow(
            label = stringResource(R.string.vaultlist_filter_hidden_only),
            icon = LkIcons.EyeOff,
            selected = state.showHiddenOnly,
                tag = "filter-hidden-only",
            onClick = onToggleHiddenOnly,
            role = Role.Switch,
        )
    }
    Spacer(Modifier.height(LkSpace.md))
    // Only while there is something to clear: a control that does nothing is the "All" chip
    // lit over a narrowed list all over again.
    if (state.activeFilters().isNotEmpty()) {
        LkTextButton(
            text = stringResource(R.string.vaultlist_filter_clear_all),
            onClick = onClearAll,
            modifier = Modifier.lkTag("vaultlist-filter-clear"),
        )
    }
    Spacer(Modifier.height(LkSpace.md))
}

/**
 * One option in the sheet. A type row is `selectable` with [Role.RadioButton], because that section
 * is single-select and that is the state a screen reader should hear; the invisible-only row is
 * `toggleable` with [Role.Switch], because a selected `selectable` drops its click action and a
 * switch has to stay pressable to turn off. The check glyph at the end is the same non-colour
 * carrier the chips and the drawer rail use.
 */
@Composable
private fun OptionRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    role: Role = Role.RadioButton,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    // No locked state, and no Pro marking of any kind. Filtering is never refused: the quota caps
    // creation only — "the cap limits creation, it never limits import, and it never limits
    // display" (`AttachmentQuota`, `ProTypeQuota`) — so a free vault may filter to Cards whether it
    // holds one, none, or twenty imported ones, and `onPickType` has never consulted the tier.
    //
    // Until 2026-09-04 these rows carried `locked = filter.requiresPro && !entitled`: the *old*
    // gate rule, from before Cards and Identity documents became one-each quotas on 2026-09-03. So
    // every free user saw a padlock and a PRO badge on Cards and Identities permanently, on a
    // control that worked perfectly, whether or not they had spent anything. That is a paid-feature
    // claim on a free action, and it is the loudest place the app made it — the add sheet at least
    // only mislabelled a row that really was refusing.
    val accent = c.jade
    val content = if (selected) c.jade else c.textPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lkTag(tag)
            .clip(LkShape.sm)
            .lkPressScale(interaction, pressedScale = 0.985f, shape = LkShape.sm)
            .then(
                if (role == Role.Switch) {
                    Modifier.toggleable(
                        value = selected,
                        interactionSource = interaction,
                        indication = null,
                        role = role,
                        onValueChange = { onClick() },
                    )
                } else {
                    Modifier.selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = null,
                        role = role,
                        onClick = onClick,
                    )
                },
            )
            .minTouchTarget()
            // One node per row, with the Pro marker spoken as part of the name rather than as a
            // second node adrift from what it refers to.
            .labelled(label)
            .padding(horizontal = LkSpace.md, vertical = LkSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .lkSurface(LkShape.md, accent.copy(alpha = 0.07f), accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Text(
            label,
            style = LkText.rowTitle,
            color = content,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(LkIcons.Check, contentDescription = null, tint = c.jade, modifier = Modifier.size(16.dp))
        }
    }
}

package com.localkeep.feature.vaultlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.annotation.StringRes
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.Card
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.File
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.rememberHaptics
import com.localkeep.core.ui.theme.LkBadge
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkMonogramTile
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkFocusRing
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkPressAndDrag
import com.localkeep.core.ui.theme.lkRing
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.core.ui.lkTag

/**
 * How much jade a just-saved row carries at the peak of its reveal.
 *
 * Slightly above the selection wash's 0.10 so the two are distinguishable if they ever land on the
 * same row, and low enough that the title's contrast is unaffected.
 */
private const val RevealWashAlpha = 0.16f

/**
 * The row's own top and bottom inset.
 *
 * Named, and not left as a literal in the padding call, because a second thing depends on it: the
 * overflow control is a sibling *outside* the padded container (see the overlay below), and in the
 * stacked layout it has to land on the same line as the copy pill, which sits exactly this far
 * above the row's bottom edge. Two literals that have to agree are one literal waiting to drift.
 * The value is what the row has always used — this is a naming, not a respacing.
 */
private val RowVerticalInset = 11.dp

/**
 * The row's own inset on the leading edge, and the strip at its trailing edge the overflow plate is
 * drawn in. Named because [LkText.stacks] is asked for the width *left to the content* — the row's
 * width minus these two — and a literal in the padding call and another in that subtraction are two
 * numbers that have to agree.
 */
private val RowStartInset = 10.dp
private val RowEndReserve = 58.dp

/**
 * One vault-list row (UI/UX spec §4.1): monogram tile, title, a subtitle that identifies *which*
 * account this is, and inline quick-copy that acts without opening the item.
 *
 * The subtitle used to repeat the item's type, which the icon already conveys — so five Google
 * logins were five identical-looking rows. It now shows the username (or the nearest equivalent
 * per type), which is the thing that actually distinguishes them.
 *
 * **Why favourite and trash are no longer buttons.** They used to sit in the row as two more text
 * buttons, which made every row four controls wide and left the title fighting for space on a
 * narrow phone — and they are both *rare* actions competing for permanent space with the common
 * one. They moved to a long-press menu, and, crucially, to real
 * [CustomAccessibilityAction]s on the row: TalkBack lists them in its actions menu, so a screen
 * reader user reaches them in one gesture rather than having to find and long-press. That is
 * strictly better than what the buttons offered, which is the only reason removing them is
 * acceptable.
 */
@Composable
internal fun VaultItemRow(
    item: VaultItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTrash: () -> Unit,
    onQuickCopy: (QuickCopy) -> Unit,
    /**
     * The drag, in the row's own local coordinates.
     *
     * Defaulted to no-ops so every other caller of this row — the picker, the tests — is unchanged
     * by the drag existing. Only the vault list wires them.
     */
    onDragStart: (grabInRoot: Offset, origin: Rect) -> Unit = { _, _ -> },
    /** The hold has registered; the drag's affordances go up before any movement. */
    onDragArmed: (grabInRoot: Offset, origin: Rect) -> Unit = onDragStart,
    onDragCancel: () -> Unit = {},
    dragEnabled: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * Selection mode is on for the whole list. Passed down rather than inferred from [selected],
     * because an unselected row still has to behave differently once the mode is running: a tap
     * adds it to the selection instead of opening it.
     */
    selecting: Boolean = false,
    selected: Boolean = false,
    /**
     * The name of the folder this item is filed in, or null for the many items in none.
     *
     * Passed in rather than read off [item], which carries only a `folderId`. Until this existed,
     * filing an item into a folder produced **no visible change anywhere the user was looking** —
     * the row was identical before and after, the item stayed in the main list, and only the item's
     * own detail screen ever named the folder. That is what "I added one item into a folder, it
     * never went there" describes: the model was working and invisible.
     */
    folderName: String? = null,
    /**
     * True for the item the editor has just saved, for as long as the list is pointing it out.
     *
     * A one-shot emphasis with a cause behind it, not decoration: the list has just cleared its
     * filters and scrolled here, and this is what says why. It fades in and back out on its own —
     * which is also what keeps it distinct from the selection wash, whose whole job is to persist.
     */
    revealed: Boolean = false,
    onToggleSelected: () -> Unit = {},
    onStartSelecting: () -> Unit = {},
    /**
     * Window bounds of the floating control this row's trailing cluster must not disappear under —
     * the vault list's FAB — or null when there is nothing to dodge.
     *
     * A lambda read in the layout phase, never composition, so a row learns about the FAB without
     * recomposing. The list hands it `null` while the list is scrolling (see
     * [VaultListScreen]'s `fabDodgeBounds`), which is the whole of "dodge only at rest": the FAB is
     * off screen then, so there is nothing to avoid and nothing moves under a moving finger.
     * Null is also every other caller — the folder picker, the tests — which have no FAB at all.
     */
    avoidanceBounds: () -> Rect? = { null },
    /**
     * Opens the destination picker for this item — the non-gesture route to dragging it.
     *
     * Null on the picker and the tests, which have no sheet to open. An action announced but wired
     * to nothing is worse than an absent one: TalkBack reads it out and it silently does nothing.
     */
    onMoveToFolder: (() -> Unit)? = null,
    /** Opens the Hide… flow for this item. Null where hiding is not offered (picker, tests). */
    onHide: (() -> Unit)? = null,
    /** True while this item is concealed but shown (a reveal session): the row carries a marker. */
    hidden: Boolean = false,
    /** Stops hiding this item — only meaningful, and only offered, while [hidden]. */
    onStopHiding: (() -> Unit)? = null,
    /** Opens the explicit settings/editor destination for this item. */
    onSettings: (() -> Unit)? = null,
) {
    val c = LkTheme.colors
    val title = item.title.ifBlank { stringResource(R.string.vaultlist_item_untitled) }
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }

    // One resting position for the whole trailing cluster, taken from its rightmost control.
    // Both trailing controls take the *same* shift from it: see [fabDodgeOffset] for why a control
    // may never be measured after its own shift, and why two controls may not be dodged apart.
    var clusterAnchor by remember { mutableStateOf<Rect?>(null) }
    // Whether this row is dodging the FAB resting on it, held for as long as the FAB is: see
    // [FabDodgeLatch] for why the dodge cannot be re-decided from the cluster's position each frame.
    val dodgeLatch = remember { FabDodgeLatch() }

    val favoriteLabel = if (item.favorite) {
        stringResource(R.string.vaultlist_row_remove_favourite, title)
    } else {
        stringResource(R.string.vaultlist_row_add_favourite, title)
    }
    val deselectLabel = stringResource(R.string.vaultlist_row_deselect, title)
    val selectLabel = stringResource(R.string.vaultlist_row_select, title)
    val openLabel = stringResource(R.string.vaultlist_row_open, title)
    val extendSelectionLabel = stringResource(R.string.vaultlist_row_extend_selection)
    val moreActionsLabel = stringResource(R.string.vaultlist_row_more_actions)
    val settingsActionLabel = stringResource(R.string.vaultlist_menu_settings)
    val selectedWord = stringResource(R.string.vaultlist_row_state_selected)
    val notSelectedWord = stringResource(R.string.vaultlist_row_state_not_selected)
    val moveToTrashLabel = stringResource(R.string.vaultlist_row_move_to_trash, title)
    val selectMultipleLabel = stringResource(R.string.vaultlist_row_select_multiple)
    val moveToFolderLabel = stringResource(R.string.vaultlist_item_action_move_to_folder)
    val hideActionLabel = stringResource(R.string.vaultlist_hide_action)
    val stopHidingLabel = stringResource(R.string.vaultlist_stop_hiding)
    val hiddenMarker = stringResource(R.string.vaultlist_marker_hidden)
    val subtitleText = item.subtitle(
        loginFallback = stringResource(R.string.vaultlist_subtitle_login_fallback),
        totpFallback = stringResource(R.string.vaultlist_subtitle_totp_fallback),
        cardFallback = stringResource(R.string.vaultlist_subtitle_card_fallback),
        identityFallback = stringResource(R.string.vaultlist_subtitle_identity_fallback),
        noteText = stringResource(R.string.vaultlist_subtitle_note),
        fileText = stringResource(R.string.vaultlist_subtitle_file),
        passkeyText = stringResource(R.string.vaultlist_subtitle_passkey),
        coordinateText = stringResource(R.string.vaultlist_subtitle_coordinate),
        phraseText = stringResource(R.string.vaultlist_subtitle_phrase),
    )
    val descriptionSelecting = stringResource(
        R.string.vaultlist_row_description_selecting,
        title,
        subtitleText,
        if (selected) selectedWord else notSelectedWord,
    )
    // A favourited row announces its state. Left off the non-favourite case deliberately — a "not
    // favourite" clause on every other row is noise, and the star is a positive marker, not a
    // toggle whose off-state needs narrating. In selecting mode the description already ends in the
    // selection state and that is what the user is acting on, so favourite stays out of it there.
    val base = stringResource(rowDescriptionRes(item.favorite), title, subtitleText)
    // Wrapped rather than given its own pair of favourite/non-favourite variants: one composing
    // string keeps the count at three instead of four in each of five catalogues, and it is the only
    // shape that stays right if a third state is ever added. The visual marker and the spoken one
    // land together — the type icon beside it is silent, and that gap is recorded below, but this
    // pass is not going to widen it.
    // The row's own detail line for attachments, and the spoken half of it. The item detail has
    // shown a paperclip and a count since attachments existed; the *row* showed nothing, so a note
    // with two encrypted files read `Wifi note (BW), Secure note` and looked like every other note
    // (4C-12, on a device 2026-09-20). Same plural wording as `itemedit_detail_attachments_files`,
    // so the two surfaces cannot disagree about what "2 files" means.
    val attachmentCount = item.attachments.size
    val attachmentsText = if (attachmentCount == 0) {
        null
    } else {
        pluralStringResource(R.plurals.vaultlist_row_attachments, attachmentCount, attachmentCount)
    }
    val withFolder = if (folderName != null) {
        stringResource(R.string.vaultlist_row_description_folder, base, folderName)
    } else {
        base
    }
    // Wrapped the same way the folder clause is, and for the same reason: one composing string
    // rather than a second set of favourite/non-favourite variants in each of five catalogues.
    // Spoken as well as drawn, deliberately — the type icon beside it is already silent and that
    // gap is recorded below, but a new marker is not going to be added to it.
    val description = if (attachmentsText != null) {
        stringResource(R.string.vaultlist_row_description_attachments, withFolder, attachmentsText)
    } else {
        withFolder
    }
    // A row shown during a reveal session carries the same "Invisible" word it draws, in its spoken
    // name as well. The drawn marker is decorative to the accessibility tree on the grounds that
    // this description already says it, and until it did, a screen-reader user could not tell a
    // concealed row from an ordinary one. Appended through the plain "%1$s, %2$s" joiner so no
    // new catalogue entry is needed for a word each catalogue already has.
    val spokenDescription = if (hidden) {
        stringResource(R.string.vaultlist_row_description, description, hiddenMarker)
    } else {
        description
    }
    val spokenDescriptionSelecting = if (hidden) {
        stringResource(R.string.vaultlist_row_description, descriptionSelecting, hiddenMarker)
    } else {
        descriptionSelecting
    }
    val requiresConfirmationLabel = stringResource(R.string.vaultlist_row_requires_confirmation, title)
    val totpBadgeText = stringResource(R.string.vaultlist_chip_totp)
    // Rise (550ms) rather than a shorter token: this has to be noticed by someone whose eyes are
    // still on the Save button they just pressed, not read by someone already looking at the row.
    // No `label`: it is only an inspector aid, and `checkNoHardcodedUiStrings` cannot tell one from
    // a user-facing string. `LkIndicators.kt` is on `config/i18n-allowlist.txt` for exactly this,
    // but that list only ever shrinks — adding a file to silence the gate is what it exists to stop.
    val revealWash by animateFloatAsState(
        targetValue = if (revealed) RevealWashAlpha else 0f,
        animationSpec = lkTween(LkMotion.Rise),
    )

    val quick = item.quickCopy()
    // Read once: the title's line budget and the row's whole shape both turn on it.
    val fontScale = LocalDensity.current.fontScale

    // Four children on one line — a 42dp tile, the title column, the copy pill, the overflow plate —
    // is a budget that only holds while the text is small. At the system's largest display size the
    // window is 308dp; take the list's 12dp gutters, this row's own 10dp/58dp insets and the two
    // 13dp gaps, and 216dp is left, of which the tile and the pill claim 124dp at font scale 2.0.
    // The title column absorbs whatever remains, and what remained was 66dp: "SeedReuseA" rendered
    // as "Seed / Reus / eA", three characters to a line (measured 2026-08-31, 560dpi).
    //
    // The trailing controls do not stay put on one row: the row a resting FAB covers slides its
    // cluster left to clear it, and that shift is most of what the title column has, so the pill
    // would come to rest *on* the title and the row's own name be drawn through its copy button.
    // That was the whole of the defect at 411dp, where width is not tight at all.
    //
    // Clamping the dodge is not the fix: its job is to keep the cluster out from under the FAB, and
    // a clamp puts it back there. [trailingClusterReserve] narrows the title's *content* by the
    // same amount instead. When the name will not fit beside the cluster ([vaultRowStacks]) the
    // row stacks: the name takes a line of its own at full width and the cluster moves down beside
    // the subtitle. Same reasoning as the strength meter and the health ring:
    // a dp-sized graphic beside sp-sized text is a layout with no floor. Dropping the pill above the
    // threshold was the cheaper-looking alternative and is wrong — the overflow menu offers
    // favourite, select, hide and trash, and no copy at all, so it would take the one-tap secret away
    // from exactly the reader who most needs it.
    //
    // Font scale is only a proxy for the room the title is left, and a poor one at the narrow end
    // (SCL-008): at 308dp and 1.0 the row's content is 216dp, the tile, the pill and two gaps take
    // 128 of it, and a FAB dodge on top left the title one glyph. So the row also stacks on the width
    // it is actually given, through [LkText.stacks], and is measured below inside the row's own
    // `BoxWithConstraints` — see [RowStartInset] and [RowEndReserve] for what comes off that width.
    // 360dp and up at 1.0 through 1.4 still render the row the comps specify, to the pixel.

    val leading: @Composable () -> Unit = {
        // The tick takes the monogram's place rather than sitting beside it: same 42dp, so the
        // titles do not shift sideways when the mode turns on and the list stays readable
        // through the change.
        if (selecting) SelectionTick(selected) else LkMonogramTile(title, sharedKey = item.id.toString())
    }

    // The name, and under it what kind of thing this is and where it is filed. Two lambdas rather
    // than one column because the stacked layout (see [vaultRowStacks]) sets them on different terms:
    // the name takes the full width, and the subtitle shares its line with the trailing cluster.
    val titleLine: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Two lines *at the default text size*, and proportionally more above it. The
            // title is the only thing identifying the row, and spec §2 forbids truncating
            // critical content at large font sizes. A flat `maxLines = 2` was the earlier
            // answer to exactly that clause, and it only looked like one: doubling the text
            // size halves the characters a line holds, so a fixed line count is a shrinking
            // character budget. At font scale 2.0 it cut the name — "Banque Populaire
            // co..." — which is the failure the cap was added to prevent.
            //
            // Scaling the count keeps the budget in characters roughly flat instead. The
            // ceiling of five is for the pathological title; rows are user-named and
            // nothing bounds their length, so this cannot be uncapped the way an
            // app-authored settings summary can. The subtitle stays at one line — it is a
            // disambiguator, and losing its tail costs far less than losing the name.
            val titleLines = (2f * fontScale).roundToInt().coerceIn(2, 5)
            Text(
                title,
                style = LkText.rowTitle,
                color = c.textPrimary,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (item.favorite) {
                // A brass dot rather than a star glyph: at 5dp it is a rhythm marker you
                // notice scanning the column, without adding a fifth icon to every row.
                Box(
                    Modifier
                        .size(5.dp)
                        .background(c.brass, androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
    }

    val subtitleLine: @Composable (Modifier) -> Unit = { lineModifier ->
        Row(
            modifier = lineModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // What kind of thing this row is. The monogram identifies the *item*; nothing
            // identified its type, so a card and a login were told apart only by reading
            // the grey line — and a card's subtitle is its masked number, which does not
            // say "card" at all.
            //
            // No contentDescription, and one would not be read anyway: the row sets its own
            // contentDescription in `.semantics`, which replaces everything below it.
            //
            // That leaves a gap this icon makes visible rather than creates — a card's
            // subtitle is its masked number, so the row announces "Amex travel, dot dot dot
            // 4242" and never the word "card". Sighted users now get the type; screen
            // readers still do not. Closing it means threading the type name into
            // `vaultlist_row_description`, which is a five-catalogue change to a string
            // this pass did not otherwise touch.
            Icon(
                item.content.typeIcon(),
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(12.dp),
            )
            Text(
                subtitleText,
                style = LkText.subtitle,
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Same treatment as the type icon to its left, one step quieter: this answers
            // "where is it filed", which is a lesser question than "what is it". Absent
            // entirely for an item in no folder — a "Folder: none" marker on most rows in
            // the vault would cost every row to inform a few.
            if (folderName != null) {
                Icon(
                    LkIcons.Folder,
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    folderName,
                    style = LkText.subtitle,
                    color = c.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // Beside the folder trail, one step quieter again, and drawn only when there is
            // something to say — see [attachmentsText].
            if (attachmentsText != null) {
                Icon(
                    LkIcons.Attachment,
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    attachmentsText,
                    style = LkText.subtitle,
                    color = c.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // Amber, not coral: a concealed item surfaced in a reveal session is a state,
            // not a failure. Decorative — the row's contentDescription and its Stop hiding
            // action already carry the meaning, so a second spoken node would double it.
            if (hidden) {
                Text(
                    hiddenMarker,
                    style = LkText.badge,
                    color = c.amber,
                    modifier = Modifier.decorative(),
                )
            }
            if (item.hasTotpBadge()) LkBadge(totpBadgeText)
        }
    }

    val details: @Composable (Modifier) -> Unit = { detailsModifier ->
        Column(detailsModifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            titleLine()
            subtitleLine(Modifier)
        }
    }

    val trailing: @Composable (Modifier) -> Unit = { trailingModifier ->
        when {
            quick != null -> {
                val quickLabel = stringResource(quick.shortLabelRes)
                val quickAction = stringResource(quick.actionLabelRes)
                QuickCopyPill(
                    label = quickLabel,
                    description = stringResource(R.string.vaultlist_quickcopy_description, quickAction, title),
                    onClick = {
                        haptics.play(Haptic.COPY)
                        onQuickCopy(quick)
                    },
                    // A static shift, applied only on the one row a resting FAB actually covers.
                    // The scroll-driven version of this was deleted with F-12 because it moved the
                    // action column on rows nothing was covering; the list now hands this `null`
                    // for the whole of a scroll, so the offset is computed once, on one row, while
                    // nothing is moving.
                    modifier = trailingModifier.offset {
                        fabDodgeOffset(
                            fab = avoidanceBounds(),
                            cluster = clusterAnchor,
                            gapPx = FabDodgeGap.toPx(),
                            engaged = dodgeLatch.engagedFor(avoidanceBounds(), clusterAnchor),
                        )
                    },
                )
            }
            // A gated row shows a padlock plate where the copy button would be. The absence of
            // a control is itself the message: this one cannot be copied without opening it.
            //
            // It takes the cluster's shift like the copy pill does. The overflow button beside it
            // dodges a FAB that covers this row, and a plate left behind at the resting position
            // is the control it lands on, with the button painted over the padlock.
            item.reprompt -> Box(
                modifier = trailingModifier
                    .offset {
                        fabDodgeOffset(
                            fab = avoidanceBounds(),
                            cluster = clusterAnchor,
                            gapPx = FabDodgeGap.toPx(),
                            engaged = dodgeLatch.engagedFor(avoidanceBounds(), clusterAnchor),
                        )
                    }
                    .size(32.dp)
                    .background(c.wash, LkShape.sm)
                    .labelled(requiresConfirmationLabel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    LkIcons.Lock,
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }

    // The stack decision is measured, not read off the font scale. Stacking at a fixed 1.5 put the
    // copy pill alone on a second line under "Login 1" on a 360dp Galaxy S20 at a large text size,
    // with the room beside the name plainly empty (2026-10-28) — while at 1.3 the same rule kept
    // "accounts.google.com" on one line and broke it mid-word. What decides is whether the title's
    // widest word, and a floor of room for the rest of it, fit the column the one-line layout leaves.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pillLabel = quick?.let { stringResource(it.shortLabelRes) }
    val widestWordPx = remember(title, measurer, fontScale) {
        title.split(' ', '\n', '\t').filter { it.isNotEmpty() }
            .maxOfOrNull { measurer.measure(it, LkText.rowTitle, softWrap = false, maxLines = 1).size.width } ?: 0
    }
    val pillLabelPx = remember(pillLabel, measurer, fontScale) {
        pillLabel?.let { measurer.measure(it, QuickCopyPillLabel, softWrap = false, maxLines = 1).size.width } ?: 0
    }

    BoxWithConstraints(modifier.lkTag("vaultlist-row-${item.id}")) {
        val content = maxWidth - RowStartInset - RowEndReserve
        val trailingWidth = when {
            quick != null -> maxOf(48.dp, QuickCopyPillChrome + with(density) { pillLabelPx.toDp() })
            item.reprompt -> 32.dp
            else -> 0.dp
        }
        // Shared by both layouts - see the call sites for when each applies. One definition, because
        // `FabDodgeAtRestTest` counts the readers of the FAB's bounds and two copies would drift.
        val clusterReservePx: Density.() -> Int = {
            trailingClusterReserve(
                fab = avoidanceBounds(),
                cluster = clusterAnchor,
                gapPx = FabDodgeGap.toPx(),
                engaged = dodgeLatch.engagedFor(avoidanceBounds(), clusterAnchor),
            )
        }
        val detailsReserve = Modifier.reserveForTrailingCluster(clusterReservePx)
        // The row a resting FAB covers slides its cluster left and narrows the title by the same
        // amount, so that width is not the title's to count on. Read here, in composition, so the
        // decision sees it: on a 360dp screen at 1.5 the dodge left "Login 1" split over two lines
        // with its subtitle cut to "Lo…" (device pass 2026-10-28). [FabDodgeLatch] holds once engaged
        // and stacking moves the cluster down, not sideways, so the stacked row keeps the reserve
        // that put it there and does not flip back.
        val stacked = vaultRowStacks(
            contentWidth = content,
            trailingWidth = trailingWidth,
            favoriteDot = item.favorite,
            widestTitleWord = with(density) { widestWordPx.toDp() },
            fontScale = fontScale,
            dodge = with(density) { density.clusterReservePx().toDp() },
        )
        // The stacked answer to the same dodge, for a row with no trailing control of its own: the
        // plate gets a line under the details instead of taking width from them.
        val clusterLine = Modifier.reserveLineForTrailingCluster {
            if (clusterReservePx() > 0) clusterAnchor?.height?.roundToInt() ?: 0 else 0
        }
        // One chain, two containers: everything that makes this node the row — its press scale, its
        // washes, its focus ring, its gesture node and its semantics — is identical whether the
        // children sit on one line or two, and duplicating it per branch is how the two drift.
        val frame = Modifier
                .fillMaxWidth()
                .lkPressScale(interaction, pressedScale = 0.985f)
                // Selected rows get a jade wash. The tick on the left is the primary signal — the
                // wash exists so a half-selected list is readable at a glance while scrolling, not
                // only by inspecting each row's leading edge.
                .background(
                    if (selected) c.jade.copy(alpha = 0.10f) else Color.Transparent,
                    LkShape.lg,
                )
                // Drawn over the selection wash rather than instead of it, so a reveal landing on
                // an already-selected row adds to it and neither signal is lost.
                .background(c.jade.copy(alpha = revealWash), LkShape.lg)
                // `indication = null` below removes the platform's own focus/press affordance, which
                // is right for the press (lkPressScale replaces it) and wrong for focus: it left the
                // vault list — the app's primary screen — with no visible focus at all for a
                // keyboard, D-pad or switch-access user. Applied before the gesture node, as in
                // LkGroupRow, so the ring wraps the focusable node.
                .lkFocusRing(LkShape.lg)
                // `lkPressAndDrag`, not `combinedClickable`. It re-provides all five of that
                // node's contributions — see its KDoc — and adds the one thing it cannot: a long
                // press that becomes a drag when the finger moves. Movement is what decides, here
                // as everywhere else in the app, which is also what resolves the collision in
                // selection mode: hold still and the selection extends, hold then move and it drags.
                .lkPressAndDrag(
                    interactionSource = interaction,
                    onClick = { if (selecting) onToggleSelected() else onClick() },
                    onClickLabel = if (selecting) {
                        if (selected) deselectLabel else selectLabel
                    } else {
                        openLabel
                    },
                    onLongPressRelease = {
                        haptics.play(Haptic.SELECT)
                        // Long-press already owned the context menu, so selection is entered from
                        // inside it rather than by stealing the gesture. Once the mode is running,
                        // though, a long-press extending the selection is what every list on the
                        // platform does, and a menu appearing over a half-made selection is not.
                        if (selecting) onToggleSelected() else menuOpen = true
                    },
                    onLongPressLabel = if (selecting) extendSelectionLabel else moreActionsLabel,
                    onDragStart = onDragStart,
                    // Arming is the "you are now holding this" event: the buzz and the ghost's lift
                    // fire together here. The bin and the drop strips are deliberately *not* part of
                    // it — they belong to a drag in motion and wait for movement (see
                    // `LkDragSession.isMoving`), so a plain hold-and-release shows a lift and then
                    // the context menu, never a coral flash.
                    onDragArmed = { grab, origin ->
                        haptics.play(Haptic.SELECT)
                        onDragArmed(grab, origin)
                    },
                    onDragCancel = onDragCancel,
                    dragEnabled = dragEnabled,
                )
                .semantics {
                    contentDescription = if (selecting) spokenDescriptionSelecting else spokenDescription
                    customActions = buildList {
                        onSettings?.let { settings ->
                            add(CustomAccessibilityAction(settingsActionLabel) { settings(); true })
                        }
                        add(CustomAccessibilityAction(favoriteLabel) { onToggleFavorite(); true })
                        // Filing by drag has no keyboard equivalent (§8 of the drag design), so this
                        // is the parity route rather than a convenience: without it, moving an item
                        // into a folder from this screen is reachable only by making a selection.
                        onMoveToFolder?.let { move ->
                            add(CustomAccessibilityAction(moveToFolderLabel) { move(); true })
                        }
                        // Concealment is a constructive action, so it sits above trash. A
                        // directly-hidden row surfaced in a reveal session offers Stop hiding
                        // instead; never both.
                        if (hidden && onStopHiding != null) {
                            add(CustomAccessibilityAction(stopHidingLabel) { onStopHiding(); true })
                        } else {
                            onHide?.let { hide ->
                                add(CustomAccessibilityAction(hideActionLabel) { hide(); true })
                            }
                        }
                        add(CustomAccessibilityAction(moveToTrashLabel) { onTrash(); true })
                        // Reachable without a long-press: TalkBack's long-press is not the app's,
                        // and a mode you can only enter by gesture is a mode a screen-reader user
                        // does not have.
                        add(
                            CustomAccessibilityAction(selectMultipleLabel) {
                                if (selecting) onToggleSelected() else onStartSelecting()
                                true
                            },
                        )
                    }
                }
                // The overflow control is a sibling overlay, outside this row's custom gesture
                // node. Keeping it inside made lkPressAndDrag observe its tap as a row tap too,
                // reopening a file viewer when the user only wanted the row menu. The end reserve
                // is the strip that overlay lands in; the vertical inset is what puts it on the
                // same line as the pill once the row stacks.
                .padding(start = RowStartInset, end = RowEndReserve, top = RowVerticalInset, bottom = RowVerticalInset)

        if (stacked) {
            // A row with no copy pill and no padlock plate (a card, a note) has nothing beside its
            // subtitle, so the overflow plate - bottom-anchored - sits on the subtitle's line, and a
            // dodge slides it onto the subtitle: the last digits of a masked card number (SCL-009).
            // The reserve is a line, not width: the plate drops onto a line of its own beneath the
            // text. A row that does have a trailing control keeps it beside the subtitle, already
            // clear of the text, and needs nothing.
            Row(
                modifier = if (quick == null && !item.reprompt) frame.then(clusterLine) else frame,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                leading()
                // The name gets the whole width, and the cluster moves down to the subtitle's line
                // rather than onto one of its own. Until 2026-10-28 it took a third line, end-aligned
                // under the text, and the row read as broken: a name, then a dead half-row with two
                // buttons at its far end, and the same gap again on the next stacked row.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    titleLine()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        // The subtitle gives up the dodge, as the one-line layout's details do: the
                        // cluster beside it slides left on the row a resting FAB covers, and without
                        // the reserve it slid onto the subtitle's text — "birdwatcCopy" (final sweep,
                        // 360dp / 1.5, 2026-10-28).
                        // Only where there is a control beside it to slide: a row with none (a card)
                        // already gives a dodged plate a line of its own, and narrowing its subtitle as
                        // well cut the masked number's last four digits (SCL-009).
                        subtitleLine(
                            Modifier.weight(1f)
                                .then(if (quick != null || item.reprompt) detailsReserve else Modifier),
                        )
                        trailing(Modifier)
                    }
                }
            }
        } else {
            Row(
                modifier = frame,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                leading()
                // Only here. Stacked, the cluster has the row's last line to itself and there is
                // nothing beside it for the dodge to slide onto — see [trailingClusterReserve].
                details(Modifier.weight(1f).then(detailsReserve))
                trailing(Modifier)
            }
        }

        // Long-press remains the fast path, but a visible affordance makes the same menu
        // reachable with a keyboard, TalkBack, or a user who does not discover gestures. This is
        // deliberately outside the row's custom pointer-input node: nested taps are otherwise
        // observed by lkPressAndDrag as row opens as well as menu opens.
        //
        // It dodges the FAB exactly as the quick-copy pill does, and for a reason the pill does not
        // have: a resting FAB sits on top of this button on the one row it covers, and a tap in the
        // overlap opens the add sheet instead of the row menu — the affordance this button exists
        // to provide, taken away on the row nearest the thumb. The anchor goes on the wrapper, never
        // on the button itself: see [fabDodgeOffset] for why reading a shifted control's own
        // position never settles.
        //
        // Stacked, the trailing cluster shares the row's last line with the subtitle, so this sits with
        // the pill. Both controls are bottom-anchored — the pill by ending the padded container, this
        // one by [RowVerticalInset] under a bottom alignment — which is what keeps them on one line
        // whatever the type does to their heights. The inset sits *before* the position callback, so
        // the anchor still reports the button's own box and the dodge is unaffected.
        Box(
            Modifier
                .align(if (stacked) Alignment.BottomEnd else Alignment.CenterEnd)
                .padding(bottom = if (stacked) RowVerticalInset else 0.dp)
                .onGloballyPositioned { clusterAnchor = it.boundsInWindow() },
        ) {
            LkIconButton(
                icon = LkIcons.MoreDots,
                label = moreActionsLabel,
                onClick = { menuOpen = true },
                modifier = Modifier
                    .offset {
                        fabDodgeOffset(
                            fab = avoidanceBounds(),
                            cluster = clusterAnchor,
                            gapPx = FabDodgeGap.toPx(),
                            engaged = dodgeLatch.engagedFor(avoidanceBounds(), clusterAnchor),
                        )
                    }
                    .lkTag("vaultlist-row-${item.id}-overflow"),
                size = 36.dp,
                iconSize = 24.dp,
                plateAlignment = Alignment.Center,
            )
            // Anchored inside the overflow button's own Box, not as a sibling of it in the row.
            // As a child of the full-width row the menu opened at the row's left edge, floating
            // over the avatar and title of the item it belongs to while the button that opened it
            // sat at the trailing edge (measured [32,1358][453,2030] against a button at
            // [922,1203][1048,1329], visual sweep 2026-09-01). Nesting it here costs nothing: the
            // anchor Box already reports its own bounds for the FAB dodge, and DropdownMenu
            // positions against its parent.
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = c.dialogTop,
                // The ring and the radius are what make this read as the same object family as the
                // dialogs; a flat, sharp-cornered popup over a screen of 16–26dp radii is the tell.
                // `LkShape.dialog`, not `lg`: the comment above has always said the radius is what
                // does this, but `lg` is 15dp against the dialog family's 26dp, so the sentence and
                // the value disagreed. Visual sweep, 2026-09-01.
                shape = LkShape.dialog,
                border = BorderStroke(1.dp, c.ringStrong),
            ) {
                onSettings?.let { settings ->
                    // "Edit", in the same grey as its neighbours. It read
                    // "Settings" under a sliders glyph and in jade until 2026-09-11, which was
                    // three separate claims this entry cannot support: the word names app settings
                    // rather than this item, the glyph says configuration, and jade is reserved for
                    // primary action — so the vaguest row in a menu about one item was also its
                    // loudest. It opens the item editor, which the detail screen's own header has
                    // always called Edit; one action needs one name.
                    //
                    // The glyph was left behind by that fix and caught by F-14 on 2026-09-16: it was
                    // still LkIcons.Settings, byte-identical to the sliders on the drawer's
                    // "Settings" row, so the picture went on saying what the word had stopped
                    // saying. LkIcons.Edit is a pencil and means this record, not the app.
                    DropdownMenuItem(
                        modifier = Modifier.lkTag("vaultlist-row-${item.id}-settings"),
                        text = { Text(settingsActionLabel, style = LkText.body) },
                        leadingIcon = {
                            Icon(
                                LkIcons.Edit,
                                contentDescription = null,
                                tint = c.textSecondary,
                                modifier = Modifier.size(17.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            haptics.play(Haptic.SELECT)
                            settings()
                        },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            if (item.favorite) {
                                stringResource(R.string.vaultlist_menu_remove_favourite)
                            } else {
                                stringResource(R.string.vaultlist_menu_add_favourite)
                            },
                            style = LkText.body,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            LkIcons.Star,
                            contentDescription = null,
                            tint = if (item.favorite) c.brass else c.textMuted,
                            modifier = Modifier.size(17.dp),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        haptics.play(Haptic.SELECT)
                        onToggleFavorite()
                    },
                )
                // Same position it holds in the accessibility-action list above, and present for the
                // same reason that list gives: filing by drag has no keyboard equivalent. It was in
                // that list and *not* here, which made this the one row action reachable only with
                // TalkBack running — a switch-access user, or anyone on a physical keyboard, could
                // favourite, hide, select and trash an item but could not file it. Every other
                // action on this row has both routes; this one now does too.
                onMoveToFolder?.let { move ->
                    DropdownMenuItem(
                        text = { Text(moveToFolderLabel, style = LkText.body) },
                        leadingIcon = {
                            Icon(
                                LkIcons.Folder,
                                contentDescription = null,
                                tint = c.textMuted,
                                modifier = Modifier.size(17.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            haptics.play(Haptic.SELECT)
                            move()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.vaultlist_menu_select_multiple), style = LkText.body) },
                    leadingIcon = {
                        Icon(
                            LkIcons.Check,
                            contentDescription = null,
                            tint = c.textMuted,
                            modifier = Modifier.size(17.dp),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        haptics.play(Haptic.SELECT)
                        onStartSelecting()
                    },
                )
                // Directly-hidden item: offer to stop. Otherwise offer to hide. Never both. Above
                // trash, matching the accessibility action order: constructive first, destructive last.
                if (hidden && onStopHiding != null) {
                    DropdownMenuItem(
                        text = { Text(stopHidingLabel, style = LkText.body, color = c.amber) },
                        leadingIcon = {
                            Icon(
                                LkIcons.Eye,
                                contentDescription = null,
                                tint = c.amber,
                                modifier = Modifier.size(17.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onStopHiding()
                        },
                    )
                } else {
                    onHide?.let { hide ->
                        DropdownMenuItem(
                            text = { Text(hideActionLabel, style = LkText.body) },
                            leadingIcon = {
                                Icon(
                                    LkIcons.EyeOff,
                                    contentDescription = null,
                                    tint = c.textMuted,
                                    modifier = Modifier.size(17.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                hide()
                            },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.vaultlist_menu_move_to_trash), style = LkText.body, color = c.coralText) },
                    leadingIcon = {
                        Icon(
                            LkIcons.Trash,
                            contentDescription = null,
                            tint = c.coral,
                            modifier = Modifier.size(17.dp),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onTrash()
                    },
                )
            }
        }
    }
}

/**
 * The selection tick, in the monogram's footprint.
 *
 * An unselected row shows an empty ring rather than nothing: an absent control would leave a hole
 * where the monogram was, and the row would read as broken rather than as unselected. Decorative to
 * a screen reader — the row's own contentDescription already ends in "selected"/"not selected", and
 * a second node saying it again is how one row becomes two announcements.
 */
@Composable
private fun SelectionTick(selected: Boolean) {
    val c = LkTheme.colors
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(if (selected) c.jade.copy(alpha = 0.16f) else c.wash, LkShape.md)
            // Unselected ring is [controlIdle], not [ring]. `ring` is chrome — a hairline between
            // two surfaces, identifying nothing — and drawn in it this tick measured **1.10:1**
            // against the row background (visual sweep, 2026-09-01), so the empty state had no
            // pixels a reader could be sure were there. `controlIdle` is the token measured for
            // exactly this case; its KDoc names "an unchecked tick box's ring", `LkContrastTest`
            // pins it at 3.79:1 worst-case, and `LkCheckRow` already uses it for the same job.
            // The ring carries the contrast, not the fill, which is the shape of that precedent.
            .lkRing(LkShape.md, if (selected) c.jade.copy(alpha = 0.42f) else c.controlIdle)
            .decorative(),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                LkIcons.Check,
                contentDescription = null,
                tint = c.jade,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** Air kept between the trailing cluster and the FAB once the cluster has dodged clear of it. */
private val FabDodgeGap = 8.dp

/**
 * How far this row's trailing cluster must slide left to clear the FAB, or zero when it already
 * does.
 *
 * **Measured bounds, not a band.** The predecessor of this function eased the shift in over a 32dp
 * vertical approach, which made every row near the FAB move a little and was the whole of F-12: the
 * action column sat at two different x positions down one list. There is no easing left. The rects
 * either intersect or they do not, so exactly one row — the one a resting FAB is actually drawn on
 * top of — moves, and it moves by a fixed amount.
 *
 * "At rest" is not decided here: the list passes `null` for [fab] while it is scrolling, which is
 * the only state in which a shift could be seen to animate. See `fabDodgeBounds`.
 *
 * [cluster] must be the cluster's position **before** any shift this returns is applied. Reading it
 * after would make the shift its own trigger — slide left, stop overlapping, slide back, forever —
 * which is why the anchor hangs on a wrapper that does not move and only the children inside it are
 * offset. Both trailing controls take this one shift, measured from the rightmost of them, for the
 * same reason: dodged independently they would be pushed to the same target rather than aside
 * together, and the overflow plate would come to rest on top of the copy pill it sits beside. That
 * shipped once (2026-08-30) and left a 27dp sliver still copying, under the 48dp floor.
 */
internal fun fabDodgeOffset(fab: Rect?, cluster: Rect?, gapPx: Float, engaged: Boolean = false): IntOffset {
    if (fab == null || cluster == null) return IntOffset.Zero
    if (!engaged && !cluster.overlaps(fab)) return IntOffset.Zero
    val shift = cluster.right + gapPx - fab.left
    if (shift <= 0f) return IntOffset.Zero
    return IntOffset(-shift.roundToInt(), 0)
}

/**
 * How much width the details column has to give back while the trailing cluster is dodging.
 *
 * [fabDodgeOffset] is applied through `Modifier.offset`, which moves a control *after* everything
 * has been measured. Nothing else in the row learns that it moved — the title column is given the
 * Row's whole remaining `weight(1f)` either way — so the cluster slides left onto text that was laid
 * out assuming the cluster was still parked at the end. With a one-line title there is empty space
 * there and nothing shows; with a title long enough to wrap, line one runs the full width and the
 * opaque overflow plate is drawn straight through its last word. Measured 2026-09-06 at the
 * *default* font scale: the eighth row's plate at `[728,1842][854,1968]` against the same control at
 * `[922,…][1048,…]` on every other row, over a title occupying `[202,1831][896,1931]`.
 *
 * Returned as a reserve rather than by clamping the dodge, because clamping puts the cluster back
 * under the FAB, which is the defect the dodge exists to prevent. Read at measure time, never at
 * composition: `avoidanceBounds` is deliberately a lambda over state the row reads only at
 * placement, and hoisting it into composition would recompose every visible row. Converges in one
 * pass, because the column it narrows sits on `weight(1f)` and the cluster is an overlay on the
 * row's outer Box, so neither anchor this is computed from can move in response to it.
 */
internal fun trailingClusterReserve(fab: Rect?, cluster: Rect?, gapPx: Float, engaged: Boolean = false): Int =
    -fabDodgeOffset(fab, cluster, gapPx, engaged).x

/**
 * Remembers that a row has started dodging the FAB, until the FAB goes away.
 *
 * The decision "does the cluster overlap the FAB" cannot be taken fresh on every layout, because on
 * a stacked row with no copy pill the dodge changes the answer. The dodge reserves width in the
 * title column ([trailingClusterReserve]); a narrower title can wrap onto another line; the row
 * grows and the bottom-anchored overflow button moves down, out from under the FAB — so the dodge
 * switches off, the title unwraps, the row shrinks, and the button is back under the FAB. One state
 * a frame, for ever (L-05: the button drawn behind the FAB in the screenshot and reported 174px
 * left and 74px lower in the dump taken a moment later; under Robolectric the same geometry never
 * reaches idle).
 *
 * So the decision is taken once, when the cluster first overlaps, and held. It is released the
 * moment [fab] is null — and the list hands null for the whole of a scroll (`fabDodgeBounds`), which
 * is the only way a row can move relative to the FAB — so the next rest decides afresh. Held, the
 * shift is still computed from the cluster's own x, which does not change when the row grows, so
 * the button settles one gap left of the FAB instead of flickering.
 *
 * A plain field, not Compose state: it is a memo of what layout already decided, written from
 * inside layout, and an observed write there would be exactly the invalidation this exists to stop.
 * Every caller in one pass passes the same inputs and gets the same answer.
 */
internal class FabDodgeLatch {
    private var engaged = false

    fun engagedFor(fab: Rect?, cluster: Rect?): Boolean {
        engaged = fab != null && cluster != null && (engaged || cluster.overlaps(fab))
        return engaged
    }
}

/**
 * Hands [trailingClusterReserve] to the details column: same width in the row, less room for text.
 *
 * The column keeps the full width the Row gave it and only its *content* is measured narrower, so
 * the trailing controls stay exactly where they were and the title wraps or ellipsises before it
 * reaches them instead of being drawn under them.
 */
private fun Modifier.reserveForTrailingCluster(reservePx: Density.() -> Int): Modifier =
    layout { measurable, constraints ->
        // `minWidth` has to come down with `maxWidth`. A `weight(1f)` child is measured under
        // **fixed** width constraints — `fill = true` is the default, so minWidth == maxWidth — and
        // narrowing only the maximum leaves maxWidth < minWidth, which Compose throws on. That
        // crashed the vault list on launch, and compile, unit tests, lint and the whole gate were
        // green when it did: nothing but the device had an opinion.
        val outer = if (constraints.hasBoundedWidth) constraints.maxWidth else measurable.maxIntrinsicWidth(constraints.maxHeight)
        val target = (outer - reservePx()).coerceIn(0, outer)
        val placeable = measurable.measure(
            constraints.copy(minWidth = minOf(constraints.minWidth, target), maxWidth = target),
        )
        // Full width still reported, so the trailing controls do not move and the anchor the dodge
        // is measured from cannot shift under it.
        layout(outer, placeable.height) { placeable.place(0, 0) }
    }

/**
 * Grows the content downward by [extraPx] without moving or resizing it: the stacked answer to the
 * dodge, where [reserveForTrailingCluster] is the one-line answer.
 *
 * The overlay plate is bottom-anchored on the row, so height added under the details is exactly
 * the line it moves onto, clear of the text. Read at measure time from the same latched dodge as
 * every other shift, so it holds for as long as the FAB rests and is released with it.
 */
private fun Modifier.reserveLineForTrailingCluster(extraPx: Density.() -> Int): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val extra = extraPx().coerceAtLeast(0)
        layout(placeable.width, placeable.height + extra) { placeable.place(0, 0) }
    }

/** The inline copy control: a jade-tinted pill, the only accent in an otherwise neutral row. */
@Composable
private fun QuickCopyPill(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .lkPressScale(interaction, pressedScale = 0.93f, shape = LkShape.sm)
            .lkSurface(LkShape.sm, c.jade.copy(alpha = 0.08f), c.jade.copy(alpha = 0.16f))
            .lkFocusRing(LkShape.sm)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            // The pill draws about 28dp tall, which is what it should look like next to a list
            // row's text — but 28dp is also what it was to a finger. `minTouchTarget` expands the
            // hit rectangle without changing the drawing, so the control stays small and stops
            // being hard to hit. Applied after `clickable`, or the extra area is outside it.
            .minTouchTarget()
            .padding(horizontal = 11.dp, vertical = 9.dp)
            .labelled(description),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(LkIcons.Copy, contentDescription = null, tint = c.jade, modifier = Modifier.size(12.dp))
        Text(label, style = QuickCopyPillLabel, color = c.jade)
    }
}

private val QuickCopyPillLabel get() = LkText.badge.copy(letterSpacing = LkText.chip.letterSpacing)

/** Everything in [QuickCopyPill] but its label: 11dp padding each side, the 12dp glyph, its 6dp gap. */
private val QuickCopyPillChrome = 11.dp * 2 + 12.dp + 6.dp

/** Room the title column keeps for a name beyond its widest word, per unit of font scale. */
private val RowTitleFloor = 48.dp

/**
 * Whether the row stacks: the name on a line of its own, the trailing cluster beside the subtitle.
 * Only when the title column the one-line layout would leave — content minus the 42dp tile, the 13dp
 * gaps, the trailing control, the favourite dot and any FAB dodge — cannot hold the title's widest word whole, or
 * [RowTitleFloor] at this text size. Width alone no longer decides: at 308dp that rule stacked every
 * row, "Bank" included, and SCL-008 — the FAB dodge starving the title at that width — is answered
 * by counting the dodge itself.
 */
internal fun vaultRowStacks(
    contentWidth: Dp,
    trailingWidth: Dp,
    favoriteDot: Boolean,
    widestTitleWord: Dp,
    fontScale: Float,
    dodge: Dp = 0.dp,
): Boolean {
    val trailing = if (trailingWidth > 0.dp) trailingWidth + 13.dp else 0.dp
    val dot = if (favoriteDot) 11.dp else 0.dp
    val column = contentWidth - 42.dp - 13.dp - trailing - dot - dodge
    return column < maxOf(widestTitleWord, RowTitleFloor * fontScale.coerceAtLeast(1f))
}

/**
 * The non-selecting row description resource — the favourite variant when [favorite], the plain one
 * otherwise. Split out so the choice is testable without composing the row: the favourite state was
 * invisible to a screen reader until this branch existed (spec §3), and a silent revert to the
 * single resource is exactly the regression worth pinning.
 */
@StringRes
internal fun rowDescriptionRes(favorite: Boolean): Int =
    if (favorite) R.string.vaultlist_row_description_favourite else R.string.vaultlist_row_description

/** What an inline copy control should put on the clipboard for a given row. */
internal sealed interface QuickCopy {
    /** What the button shows. Kept short so a row of controls still fits. */
    @get:StringRes val shortLabelRes: Int

    /** What the button *does*, spelled out for a screen reader — "Copy" alone names no object. */
    @get:StringRes val actionLabelRes: Int

    data class Password(val value: String) : QuickCopy {
        override val shortLabelRes = R.string.vaultlist_quickcopy_copy
        override val actionLabelRes = R.string.vaultlist_quickcopy_copy_password
    }

    /** Generated at tap time — ticking a live code in every row would be pure waste. */
    data class TotpCode(val totp: Totp) : QuickCopy {
        override val shortLabelRes = R.string.vaultlist_quickcopy_code
        override val actionLabelRes = R.string.vaultlist_quickcopy_copy_totp
    }
}

/**
 * The copy control this row should offer, or null for none.
 *
 * Items marked "reprompt" deliberately get no inline copy: that flag means the user wants a
 * challenge before the secret leaves cover, and a one-tap copy from the list would be a trivial
 * way around it. Those rows open the item instead, where the §5.2 gate applies.
 */
private fun VaultItem.quickCopy(): QuickCopy? {
    if (reprompt) return null
    return when (val body = content) {
        is Login -> when {
            body.password.isNotBlank() -> QuickCopy.Password(body.password)
            body.totp != null -> QuickCopy.TotpCode(body.totp!!)
            else -> null
        }
        is Totp -> QuickCopy.TotpCode(body)
        else -> null
    }
}

/**
 * Whether this row earns the one-word badge that flags a property worth spotting from the list.
 *
 * Only on logins that carry a TOTP secret — it answers "do I need my authenticator app for this
 * one?" without opening the entry. The badge text itself ("2FA") is resolved by the caller, a
 * composable — this stays a plain, non-composable predicate so it needs no string params.
 */
private fun VaultItem.hasTotpBadge(): Boolean = when (val body = content) {
    is Login -> body.totp != null
    else -> false
}

/**
 * The line under the title: whatever tells two items of the same type apart.
 *
 * The `*Fallback`/`*Text` parameters are the localized fallback words, resolved by the caller — a
 * composable — via `stringResource`; this stays a plain, testable function otherwise.
 */
internal fun VaultItem.subtitle(
    loginFallback: String,
    totpFallback: String,
    cardFallback: String,
    identityFallback: String,
    noteText: String,
    fileText: String,
    passkeyText: String,
    coordinateText: String,
    phraseText: String,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = when (val body = content) {
    is Login -> body.username.ifBlank { loginFallback }
    is Totp -> listOf(body.issuer, body.account)
        .filter(String::isNotBlank)
        .joinToString(" · ")
        .ifBlank { totpFallback }
    is Card -> body.number.takeLast(4).let { if (it.isBlank()) cardFallback else "•••• $it" }
    is Identity -> listOf(body.firstName, body.lastName)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .ifBlank { identityFallback }
    is Note -> noteText
    is File -> fileText
    // The account, the way a Login shows its username — not the bare type word, which is what
    // every passkey row showed until 2026-09-20. Three passkeys for one site were three rows
    // reading `Passkey Test — example.test` / `Passkey`, indistinguishable at exactly the moment
    // one of them has to be picked (2C-F13).
    //
    // The same class of field as a Login's username and not a secret: the system credential sheet
    // already shows it, and so does the passkey detail's ACCOUNT row. The Coordinate and SeedPhrase
    // branches below withhold their content because *their* identifying field is the secret itself;
    // an account name is not.
    //
    // The creation date follows, so several passkeys minted for the **same** account read
    // differently as well (2026-09-21): the credential provider's sheet entries carry the same
    // `account · date` since the release-sweep fix wave, and the row the user sees in the vault
    // should agree with the row they pick in the sheet. Localised through the same MEDIUM style.
    is Passkey -> body.userName.ifBlank { passkeyText } + " · " +
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(body.createdAt.atZone(zone))
    // A constant, and the only branch here that ignores its own content entirely. Every other type
    // puts an identifying field on this line; a coordinate's identifying field *is* the position,
    // and a list row is the one place it must never appear — rows are read over shoulders, are what
    // a screen reader announces while scrolling, and are what a search result renders. The title
    // the user typed is what tells two saved locations apart.
    is Coordinate -> coordinateText
    // A constant type name, like Note/File/Passkey and for the same reason as Coordinate: the only
    // identifying field a phrase has is the words, and a list row — read over shoulders, announced
    // by a screen reader, rendered in a search result — is exactly where they must never appear. The
    // title the user gave ("what it opens") is what tells two saved phrases apart.
    is SeedPhrase -> phraseText
}

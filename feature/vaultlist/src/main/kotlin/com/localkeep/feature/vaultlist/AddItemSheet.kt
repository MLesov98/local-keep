package com.localkeep.feature.vaultlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.ProTypeQuota
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.theme.LkBadge
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkSheet
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkSurface

/**
 * The item types offered by the add sheet (UI/UX spec §4.4).
 *
 * `FILE` is here so the sheet is the single discoverable "add" entry point, but it is not an
 * [com.localkeep.feature.itemedit.ItemEditUiState.ItemType] — files have their own editor, so the
 * caller routes it separately.
 */
enum class NewItemType(
    // Was a plain String, on the reasoning that a short token read by tests with no Android context
    // did not need extracting. The en-XA device pass (2026-07-30) showed the cost: these are the
    // title of every row in this sheet, and they rendered in English under a translated build while
    // their descriptions did not. Enum constructor arguments are invisible to
    // checkNoHardcodedUiStrings, so nothing caught it but a screen.
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    /**
     * The whole "Add a card" sentence, for the empty state that offers this type.
     *
     * Separate from [labelRes] because an article is not decoration: it is gendered in fr/es/pt and
     * English needs "an" before a vowel. Building it as "Add a " + label rendered "Add a identity".
     */
    @param:StringRes val addActionRes: Int,
    val requiresPro: Boolean,
    /**
     * How many of this type a free vault may create, or null for the types that are not metered
     * and therefore can never refuse.
     *
     * Read straight off the quota objects rather than written down again here: a limit recorded in
     * two places is one that will eventually disagree with itself, and this one is already on the
     * screen — the badge renders it, and the refusal dialog behind the tap says the same number.
     */
    val freeLimit: Int? = null,
) {
    // Login is the dominant real-world use case for a password manager — the primary item type
    // users create. It comes first in the sheet to reflect that primacy.
    LOGIN(R.string.vaultlist_type_login, R.string.vaultlist_type_login_description, R.string.vaultlist_empty_add_login, requiresPro = false),
    // Password is the route to a generated password while creating a login — the form opens with
    // the password already made. This is an item row, not a tool shortcut: what it produces is a
    // Login with a generated password, not a standalone Password record. It is deliberately NOT
    // called "Generator" because a row named after the tool would be the only one that names a
    // destination rather than a thing the vault ends up holding.
    PASSWORD(R.string.vaultlist_type_password, R.string.vaultlist_type_password_description, R.string.vaultlist_empty_add_password, requiresPro = false),
    TOTP(R.string.vaultlist_type_totp, R.string.vaultlist_type_totp_description, R.string.vaultlist_empty_add_totp, requiresPro = false),
    NOTE(R.string.vaultlist_type_note, R.string.vaultlist_type_note_description, R.string.vaultlist_empty_add_note, requiresPro = false),
    // Not `requiresPro`, unlike Card and Identity: files are *quota*-limited rather than gated, so
    // a free vault holds several of them. `requiresPro` is the binary question "may this tier create
    // this type at all", and for files the answer is now yes — how many is a separate check that
    // needs a vault count, which lives with the add routing. See AttachmentQuota.
    //
    // Ordered with the free rows for that reason. It sat last while it was Pro, and leaving it
    // there once it went free produced four jade rows, two brass, then one jade again — the colour
    // that carries the Pro distinction stopped reading as a group and started reading as noise.
    FILE(
        R.string.vaultlist_type_file,
        R.string.vaultlist_type_file_description,
        R.string.vaultlist_empty_add_file,
        requiresPro = false,
        freeLimit = AttachmentQuota.FREE_LIMIT,
    ),
    // Free, and last of the free block. Not Pro because there is nothing to meter: a coordinate is
    // four numbers, it needs no blob store and no quota, and gating the one item type that has to
    // be created *where you are standing* behind a purchase prompt would make it useless at the
    // only moment it is worth anything.
    COORDINATE(R.string.vaultlist_type_coordinate, R.string.vaultlist_type_coordinate_description, R.string.vaultlist_empty_add_coordinate, requiresPro = false),
    // Free, and last of the free block. Not Pro for the same reason nothing whose entire value is a
    // secret should be — an item type behind a purchase prompt is a place to *not* keep the secret,
    // which is the opposite of the point. It needs no blob store and no quota: it is words.
    PHRASE(R.string.vaultlist_type_phrase, R.string.vaultlist_type_phrase_description, R.string.vaultlist_empty_add_phrase, requiresPro = false),

    // The Pro rows last, together. Brass is doing the work of a section heading here, so the block
    // has to be contiguous or it means nothing.
    CARD(
        R.string.vaultlist_type_card,
        R.string.vaultlist_type_card_description,
        R.string.vaultlist_empty_add_card,
        requiresPro = true,
        freeLimit = ProTypeQuota.FREE_LIMIT,
    ),
    IDENTITY(
        R.string.vaultlist_type_identity,
        R.string.vaultlist_type_identity_description,
        R.string.vaultlist_empty_add_identity,
        requiresPro = true,
        freeLimit = ProTypeQuota.FREE_LIMIT,
    ),
    ;

    /** Files have a separate editor; every other type opens the shared item form. */
    val isFile: Boolean get() = this == FILE

    /**
     * What the rest of the app calls this type — the vault category, matching
     * [com.localkeep.feature.vaultlist.ItemTypeFilter]'s labels exactly.
     *
     * [labelRes] is the name of one thing you are about to create ("Card"), which is right on the
     * add sheet and wrong everywhere else. The Pro dialog used it and so announced "Card is Pro",
     * two taps after the drawer had called the same feature "Cards". Anything that talks *about*
     * the feature rather than about the item being made uses this.
     *
     * Same seven resources [ItemTypeFilter] uses, deliberately: the drawer, this sheet and the Pro
     * prompt have to say one word per category in every language, and two copies of a noun is how
     * they came to disagree in English.
     */
    @get:StringRes
    val categoryLabelRes: Int
        get() = when (this) {
            // "Logins", not "Passwords". PASSWORD is a route into the Login form, not a sixth
            // category — the drawer must not grow a section for a kind of item that does not exist.
            PASSWORD -> R.string.vaultlist_category_logins
            LOGIN -> R.string.vaultlist_category_logins
            TOTP -> R.string.vaultlist_category_totp
            NOTE -> R.string.vaultlist_category_notes
            CARD -> R.string.vaultlist_category_cards
            IDENTITY -> R.string.vaultlist_category_identities
            FILE -> R.string.vaultlist_category_files
            COORDINATE -> R.string.vaultlist_category_coordinates
            PHRASE -> R.string.vaultlist_category_phrases
        }

    internal val icon: ImageVector
        get() = when (this) {
            // The same die the onboarding "Suggest one" link uses — one glyph means "generated" in
            // both places rather than each screen inventing its own.
            PASSWORD -> LkIcons.Dice
            LOGIN -> LkIcons.Key
            TOTP -> LkIcons.Clock
            NOTE -> LkIcons.Note
            CARD -> LkIcons.Card
            IDENTITY -> LkIcons.Identity
            FILE -> LkIcons.Attachment
            COORDINATE -> LkIcons.Pin
            PHRASE -> LkIcons.SeedPhrase
        }
}

/**
 * Whether the add sheet belongs on screen this composition.
 *
 * Three separate reasons, and the third is the one that had to be a *composition* answer rather
 * than an effect. [reopenRequested] is the navigation layer saying "the user backed out of what the
 * sheet opened"; it is already true on the vault list's very first composition after the pop,
 * because the flag is written before `popBackStack`. Reading it in a `LaunchedEffect` and flipping
 * [open] from there is correct but a beat late — the effect runs after the composition it belongs
 * to, so the list drew twice with no sheet and no scrim on it. Measured on the emulator at 14 fps:
 * three consecutive frames of bare, un-dimmed vault list before the sheet slammed back on.
 *
 * [leaving] is the sheet that is on its way out and still visibly there — see [LkSheet.closing].
 */
internal fun addSheetVisible(open: Boolean, leaving: Boolean, reopenRequested: Boolean): Boolean =
    open || leaving || reopenRequested

/**
 * Whether that sheet arrives already open, with no entrance to play — see [LkSheet.alreadyOpen].
 *
 * Same reason [addSheetVisible] takes [reopenRequested]: the sheet's `SheetState` is built once, on
 * the composition where the sheet first appears, so "this one is a restoration" has to be known
 * then. A frame later is a sheet that has already started rising.
 */
internal fun addSheetAlreadyOpen(restored: Boolean, reopenRequested: Boolean): Boolean =
    restored || reopenRequested

/**
 * Bottom sheet of item types, replacing the two plain "+ New Item" / "+ Add File" buttons that
 * previously sat in the list header.
 *
 * Pro types stay visible and are marked rather than hidden: a user who has spent their one free
 * Card should still be able to see that LocalKeep stores cards. Selecting one raises the refusal
 * instead of dropping them into a form they can't save.
 *
 * The Pro rows are brass throughout — icon tint, icon plate, ring and badge — so the distinction
 * is carried by more than one cue, and the row still reads as available rather than disabled.
 *
 * **Both metered rows are marked together, whether or not either allowance is spent** (2026-09-04).
 * Between 2026-09-03 and that date, brass and the badge appeared only once a type's one free slot
 * was used, which is per-type accurate and reads as something else entirely: a free vault holding
 * one identity and no card showed Identity badged PRO beside an unmarked Card, and the sheet
 * appeared to say identities are a paid feature and cards are not. Reported as exactly that. Two
 * item types governed by one identical rule must not be able to look like different offers, so the
 * marking now follows the *rule* — brass for both whenever the tier is metered — and the badge
 * carries which state that particular allowance is in. See [ProTypeMark].
 */
@Composable
internal fun AddItemSheet(
    isPro: Boolean,
    /**
     * Types whose free allowance is spent, mapped to how many of them the vault actually holds.
     *
     * Was `requiresPro && !isPro`, computed here. It moved out on 2026-09-03, when Cards and
     * Identity documents stopped being gated outright and became one-each quotas (`ProTypeQuota`):
     * a free vault holding no card may create one, and a padlock on a row that is about to work is
     * the same lie the drawer used to tell about Files. Only the caller can know the counts, so
     * only the caller can answer this.
     *
     * A map rather than a set since 2026-09-04, because the badge now names the count — "1 OF 1"
     * beside Card, "7 OF 7" beside File. A badge naming only a state could not answer the question
     * its reader actually has, which is whether the marking describes their own data or says this
     * type is special; three successive versions of it were asked exactly that, by the same person,
     * in one evening. The number settles it on sight.
     *
     * Empty for a Pro vault, and empty for a free vault that has not spent an allowance yet.
     */
    spentAllowances: Map<NewItemType, Int> = emptyMap(),
    onSelect: (NewItemType) -> Unit,
    onDismiss: () -> Unit,
    /** Task 6: "New folder" as the sheet's first row, above every item type. Null hides the row. */
    onNewFolder: (() -> Unit)? = null,
    /**
     * True when this sheet is coming back after the user backed out of the thing they picked from
     * it, rather than being raised by the FAB. It arrives already open — see [LkSheet.alreadyOpen].
     */
    restored: Boolean = false,
    /**
     * True from the moment a row is tapped until the sheet has finished sliding away — see
     * [LkSheet.closing]. The chooser leaves under its own motion while the screen it chose arrives
     * behind it, instead of blinking out and leaving the vault list bare for a fifth of a second.
     */
    closing: Boolean = false,
) {
    LkSheet(
        onDismiss = onDismiss,
        alreadyOpen = restored,
        closing = closing,
        title = stringResource(R.string.vaultlist_add_sheet_title),
        subtitle = stringResource(R.string.vaultlist_add_sheet_subtitle),
        // Five grid rows (ten cells: New folder plus nine types) still overrun the half-height
        // detent on a 1080x2400 device, clipping the Pro row flush with the display edge — Card and
        // Identity would exist with nothing on screen to say so. Open expanded so the whole grid is
        // present without a scroll.
        startExpanded = true,
    ) {
        // **No per-row stagger.** The rows used to enter one at a time on [lkStaggerDelay], and with
        // ten of them the last one started 306 ms in and finished 460 ms after that — 766 ms of
        // content still arriving, *on top of* the sheet's own entrance, which is itself around
        // 600 ms measured on the emulator 2026-08-20. Close to a second and a half before the sheet
        // stopped moving.
        //
        // It reads far worse than that arithmetic suggests, because [Modifier.lkEnter] translates
        // but deliberately never fades: every row is fully opaque the whole time, so this is not a
        // list appearing, it is ten visible rows sliding upward at ten different moments. Reported
        // from a phone 2026-08-20: "a transition comes that tries to reach the top screen sloowwlyy
        // and it does in about a second or so".
        //
        // A stagger earns its place when a list is the screen and the eye needs somewhere to start.
        // Here the *sheet* is the thing arriving, it already animates, and the rows are its content
        // — they should be in place by the time it lands, not chasing it.
        AddTypeGrid(
            isPro = isPro,
            spentAllowances = spentAllowances,
            onSelect = onSelect,
            onNewFolder = onNewFolder,
            modifier = Modifier.padding(start = AddGridInset),
        )
        Spacer(Modifier.height(LkSpace.sm))
    }
}

/**
 * The sheet's grid of cells, as its own composable so it can be measured without the sheet.
 *
 * Split out of [AddItemSheet] on 2026-09-08 to make [AddItemSheetRowHeightTest] possible.
 * `ModalBottomSheet` draws in a window of its own, and that window re-provides `LocalDensity` from
 * the platform — so a test that wraps the sheet in a `CompositionLocalProvider(LocalDensity …)` to
 * pick a font scale changes nothing inside it. Measured: every cell came back 126 px at all of
 * 1.0, 1.15, 1.3, 1.5 and 2.0, on a device where 1.3 visibly wraps a label. Rendered directly, the
 * override applies, and the row geometry under test is identical either way.
 */
@Composable
internal fun AddTypeGrid(
    isPro: Boolean,
    spentAllowances: Map<NewItemType, Int> = emptyMap(),
    onSelect: (NewItemType) -> Unit,
    onNewFolder: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // **A two-column grid, ten cells, New folder first.** Nine item types plus New folder make
    // ten, which lays out as five rows of two — five down the left, five down the right. New
    // folder leads because the folder an item goes into is chosen before the item, and it is a
    // full cell here rather than the separate full-width banner it used to be: beside the grid, a
    // lone banner row read as a different kind of control and cost the sheet an extra row-height
    // of entrance travel — the only lever `ModalBottomSheet` leaves, since
    // `rememberModalBottomSheetState` exposes no `animationSpec` and the sheet's distance can
    // only be shortened by giving it less to be.
    //
    // Plain `Row`s, deliberately. `LazyVerticalGrid` throws inside `LkSheet`'s
    // `.verticalScroll` (same-axis nesting) and `FlowRow` is still experimental.
    //
    // The Pro rows stay last and together — brass is doing the work of a section heading, so the
    // block has to be contiguous. Folder plus the seven free types is eight cells (four whole
    // rows), then Card and Identity fill the fifth row alone; chunking the free and Pro groups
    // separately is what guarantees that whatever the free count.
    BoxWithConstraints(modifier) {
        val (proTypes, freeTypes) = NewItemType.entries.partition { it.requiresPro }
        // New folder is not a NewItemType — it makes a container, not an item — so the cells are
        // modelled as a small sum type rather than a bare list of enum entries, and it is
        // prepended to the free block so the brass Pro pair stays whole on the last row.
        val freeCells = buildList<AddCell> {
            onNewFolder?.let { add(AddCell.NewFolder(it)) }
            freeTypes.forEach { add(AddCell.Type(it)) }
        }
        val proCells = proTypes.map { AddCell.Type(it) }
        // **One cell per row when two columns cannot hold the labels.** Half a 308dp window at font
        // scale 2.0 leaves a label 55dp, and "Password", "Recovery" and "location" are each wider
        // than that, so the breaker cut them at the last letter that fit — "Passw/ord" — and
        // `maxLines = 2` then dropped whatever came after, the "n" of "GPS location", with no
        // ellipsis to say anything was missing (SCL-013). German started at 1.0. Shrinking the type
        // is not available (it is the user's choice) and neither is a fixed font-scale threshold,
        // because the same scale fits at 411dp and not at 308dp, so the grid measures its own
        // labels against the width a cell would give them and stacks when any of them would break
        // inside a word or need a third line. See [addGridStacks].
        val labels = buildList {
            if (onNewFolder != null) add(stringResource(R.string.vaultlist_add_new_folder))
            NewItemType.entries.forEach { add(stringResource(it.labelRes)) }
        }
        val stacked = addGridStacks(maxWidth, labels)
        val rows = if (stacked) {
            (freeCells + proCells).map { listOf(it) }
        } else {
            freeCells.chunked(2) + proCells.chunked(2)
        }
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
            rows.forEach { rowCells ->
                // `IntrinsicSize.Min` on the row, `fillMaxHeight()` on both cells: the pair is as
                // tall as whichever of the two needs more, and neither is left short.
                //
                // Without it a label that wraps grows only its own cell, and the sibling keeps its
                // one-line height and sits top-aligned against it. Measured on emulator-5554
                // (1080x2400, 420 dpi) 2026-09-08: at font scale 1.3 "Recovery phrase" takes two
                // lines and 170 px while "GPS location" beside it stays at 126 px, so the gap above
                // the Card row reads as 55 px where every other row gap is 11 px. Reported from a
                // Galaxy S24 Ultra as "more spacing between GPS location and Card than for others".
                //
                // Row-local rather than a fixed two-line height for every cell: at font scale 1.0
                // nothing wraps, and reserving the second line would add five rows of empty space
                // to a sheet whose height is deliberately minimal — see the entrance-travel note
                // above. What the eye compares is the two cells beside each other, not two rows.
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(LkSpace.xs),
                ) {
                    rowCells.forEach { cell ->
                        when (cell) {
                            is AddCell.NewFolder -> NewFolderCell(
                                onClick = cell.onClick,
                                stacked = stacked,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            is AddCell.Type -> AddTypeCell(
                                type = cell.type,
                                mark = proTypeMark(isPro, cell.type in spentAllowances),
                                used = spentAllowances[cell.type] ?: 0,
                                onClick = { onSelect(cell.type) },
                                stacked = stacked,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                    // An odd count would stretch the last cell full-width and read as a different
                    // kind of row. With New folder folded in, the free block is even, so this only
                    // guards the onNewFolder-null case (nine cells, the phrase row left half-width).
                    // Stacked rows are one cell wide by design and want the whole width.
                    if (!stacked && rowCells.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The width a cell's label gets in the two-column grid: half the grid, less the gutter between the
 * cells, the cell's padding either side, the 32dp plate and the gap after it. The terms are
 * [AddTypeCell]'s own, written down once so the stacking rule and the layout cannot disagree.
 */
internal fun twoColumnLabelWidth(gridWidth: Dp): Dp =
    (gridWidth - LkSpace.xs) / 2 - LkSpace.sm * 3 - AddCellPlate

/** The glyph plate in a cell. */
private val AddCellPlate = 32.dp

/** Lines a label may take in a cell before the row is considered to have lost its text. */
private const val AddLabelMaxLines = 2

/**
 * Whether a label laid out in a cell breaks only between words and fits [maxLines].
 *
 * A line that ends inside a word is the defect: nothing tells the breaker where a word may be
 * split, so it cuts at the last letter that fits. Read off the layout rather than guessed from
 * widths, because the breaker's own opinion of where a word ends (a hyphen, a non-breaking space)
 * is what the reader sees.
 */
internal fun labelBreaksOnlyBetweenWords(layout: TextLayoutResult, maxLines: Int): Boolean {
    if (layout.lineCount > maxLines) return false
    val text = layout.layoutInput.text.text
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line, visibleEnd = false)
        if (end <= 0 || end >= text.length) continue
        val atBoundary = text[end - 1].isWhitespace() || text[end - 1] == '-' || text[end].isWhitespace()
        if (!atBoundary) return false
    }
    return true
}

/**
 * Whether the grid drops to one cell per row at this width, for these labels.
 *
 * True when any label, laid out in the width [twoColumnLabelWidth] would give it, breaks inside a
 * word or needs more than [AddLabelMaxLines]. Measured with the real style at the real density, so
 * the font scale and the locale's wording are both already in the answer: 411dp at 2.0 keeps two
 * columns in English where 308dp does not, and German stacks at 1.0 on the narrow floor.
 */
@Composable
private fun addGridStacks(gridWidth: Dp, labels: List<String>): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(gridWidth, labels, density) {
        val budget = with(density) { twoColumnLabelWidth(gridWidth).roundToPx() }.coerceAtLeast(0)
        labels.any { label ->
            val layout = measurer.measure(
                text = label,
                style = LkText.rowTitle,
                constraints = Constraints(maxWidth = budget),
            )
            !labelBreaksOnlyBetweenWords(layout, AddLabelMaxLines)
        }
    }
}

/**
 * The style and line budget of a cell's label.
 *
 * Two columns: the plain row title on at most two lines, which [addGridStacks] has already shown to
 * break only between words. One column: the label has the whole row, so the cap is lifted — a letter
 * must never be dropped without an ellipsis — and the style hyphenates, for the one case stacking
 * cannot cure, a single German compound ("Wiederherstellungsphrase") wider than the row itself.
 * Cell labels are the app's own copy, so a hyphen drawn in one is not a lie about a value.
 */
private fun cellLabelStyle(stacked: Boolean): TextStyle =
    if (stacked) {
        LkText.rowTitle.copy(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto)
    } else {
        LkText.rowTitle
    }

private fun cellLabelMaxLines(stacked: Boolean): Int = if (stacked) Int.MAX_VALUE else AddLabelMaxLines

/**
 * How far the grid sits in from the sheet's own 10dp content edge, on the start side.
 *
 * The sheet insets its title 14dp inside that edge — a 24dp column — while a cell draws its glyph
 * plate [LkSpace.sm] inside its own bounds, which put the plates at 18dp and about 7dp left of the
 * heading directly above them. The grid is brought in by the difference, so the plates and the
 * heading share one start edge. Start only: nothing draws an end edge for the eye to compare, and
 * each cell's label budget is tight enough already.
 */
internal val AddGridInset = 14.dp - LkSpace.sm

/** One cell of the add grid: either New folder (a container) or one [NewItemType]. */
private sealed interface AddCell {
    data class NewFolder(val onClick: () -> Unit) : AddCell
    data class Type(val type: NewItemType) : AddCell
}

/**
 * The sheet's "New folder" cell — a container, not an item type, so it is styled and behaves like
 * [AddTypeCell] without being one of [NewItemType]'s entries. First in the grid: the folder a new
 * item goes into is a decision made before the item, not after.
 *
 * No chevron. It used to carry one because it opened a further screen, but every cell in this grid
 * opens a next step and none of the others announce it — a chevron on one cell alone read as a
 * different class of control. The naming step is signalled by the grid convention now, not a glyph.
 */
@Composable
private fun NewFolderCell(onClick: () -> Unit, stacked: Boolean, modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.vaultlist_add_new_folder)
    Row(
        modifier = modifier
            .lkTag("add-type-new-folder")
            .lkPressScale(interaction, pressedScale = 0.985f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .minTouchTarget()
            .padding(horizontal = LkSpace.sm, vertical = LkSpace.sm)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Box(
            modifier = Modifier
                .size(AddCellPlate)
                .lkSurface(LkShape.md, c.jade.copy(alpha = 0.07f), c.jade.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(LkIcons.Folder, contentDescription = null, tint = c.jade, modifier = Modifier.size(16.dp))
        }
        // maxLines = 2 to match [AddTypeCell], so a folder label that wraps in some locale keeps the
        // same baseline as the type cells beside it.
        Text(
            label,
            style = cellLabelStyle(stacked),
            color = c.textPrimary,
            maxLines = cellLabelMaxLines(stacked),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What, if anything, a type's cell says about the free tier's allowance for it.
 *
 * **One rule: a cell is marked when, and only when, selecting it will refuse.** Everything else is
 * jade and silent, including a metered type whose slot is still there — because a row that works is
 * a row that works, and there is nothing to warn about.
 *
 * Two states rather than the three this briefly had on 2026-09-04, when an unspent allowance was
 * also marked and badged "1 FREE". Three things killed that:
 *
 * - **Brass means "this costs money"** (`LkColors`), so "1 FREE" on a brass plate contradicts its
 *   own background. The catalogue felt it first: two of three adversarial voters rejected the
 *   French, and it ships as "incluse" rather than "gratuite" because no one could say the thing the
 *   design rested on.
 * - **In `foss` it never goes away.** `FossEntitlement.canPurchase` is permanently false and a
 *   release build's `isPro` with it, so two cells sat plated brass from install, for ever, in a
 *   build that sells nothing.
 * - **A badge cannot teach a cap anyway.** The refusal dialog does that a second later, with the
 *   real count and the free remedy, and it fires on *selecting* the type — before the form opens,
 *   before a keystroke. What a mark can usefully say is "this one will bounce", and only a spent
 *   allowance is that.
 *
 * The parity that change existed to fix is kept, and is now structural rather than a rule about
 * colour: every metered type marks on the same condition, so two types governed by one rule can
 * never render as two different offers. See `AddSheetProTypeParityTest`.
 */
internal enum class ProTypeMark {
    /** Selecting this works. Jade, no badge — a free type, a Pro vault, or an allowance in hand. */
    None,

    /** Selecting this refuses. Brass, and the badge says Pro. */
    Spent,
}

/**
 * [ProTypeMark] for one cell.
 *
 * Answerable from `spent` alone: the caller is the only thing that can count the vault (see
 * [AddItemSheet]'s `spentAllowances`), and it already folds `isPro` into that count — every quota flag
 * in `VaultListUiState` comes from an `exceedsFreeLimit(used, isPro)` that is false for a Pro
 * vault. `isPro` is taken anyway and asserted against, because a mark on an entitled vault is the
 * one failure this function must not be able to produce: brass drawn on the two types a Pro user
 * has actually paid for reads as a distinction that is not there.
 *
 * Deliberately not keyed on [NewItemType.requiresPro]. File is capped at seven
 * (`AttachmentQuota.FREE_LIMIT`) while `requiresPro = false`, so keying on it marked two of the
 * three refusable types and left the third looking unconditionally available.
 */
internal fun proTypeMark(isPro: Boolean, spent: Boolean): ProTypeMark =
    if (spent && !isPro) ProTypeMark.Spent else ProTypeMark.None

@Composable
private fun AddTypeCell(
    type: NewItemType,
    mark: ProTypeMark,
    /** How many of [type] the vault holds. Only read when [mark] is [ProTypeMark.Spent]. */
    used: Int,
    onClick: () -> Unit,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    // Brass whenever the type is metered for this viewer, spent or not — never `type.requiresPro`
    // alone. Keying the accent off `requiresPro` meant an entitled user saw Card and Identity
    // plated brass among eight jade rows with no badge and nothing else to explain it: hue carrying
    // a meaning by itself, and the wrong meaning at that, since those two types are exactly the
    // ones they have paid for. Keying it off "allowance spent" — which is what this was between
    // 2026-09-03 and 2026-09-04 — had the opposite failure: the two metered types stopped matching
    // each other, and the one with a spent slot read as the paid feature.
    val accent = if (mark == ProTypeMark.None) c.jade else c.brass
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(type.labelRes)
    val description = stringResource(type.descriptionRes)
    // A count, not a word. Nothing in this app is tier-gated any more — Cards and Identity documents
    // became one-each quotas on 2026-09-03, files always were, and `AddDestinationTest` asserts that
    // no route can produce `ProBlocked` at all — so this badge went from "PRO" to "USED" to the
    // number over the course of 2026-09-04, and only the number stopped the question. "PRO" named a
    // gate that does not exist ("Identity says that it's pro only but not card?"). "USED" named a
    // state but withheld the one fact that distinguishes *your vault holds one* from *this type is
    // special*.
    //
    // The number was right and its shape was not. "1 OF 1" reads cleanly and "10 OF 1" does not:
    // import never charges against the cap, so a vault that arrived from another manager sits above
    // it, and on 2026-09-17 a 10-card 25-identity vault rendered "10 OF 1" and "25 OF 1" — a
    // fraction that cannot exist (V3-05). The cap and the holding are two facts, so the badge names
    // them as two: "limit 1 · 10 held", the same pair in the same order as the refusal dialog one
    // tap behind it, and true at every count rather than only below the cap.
    //
    // Spoken as well as drawn, from the one string, so the cell cannot say two different things to
    // two different readers.
    val spentBadgeText = type.freeLimit?.let {
        pluralStringResource(R.plurals.vaultlist_badge_allowance_spent, used, used, it)
    }

    Row(
        modifier = modifier
            .lkTag("add-type-${type.name.lowercase()}")
            .lkPressScale(interaction, pressedScale = 0.985f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .minTouchTarget()
            .padding(horizontal = LkSpace.sm, vertical = LkSpace.sm)
            // One node per cell: as three, a screen reader reads the icon, then the label, then the
            // description, and the Pro marker is announced adrift from what it refers to.
            //
            // [description] is still appended here even though the cell no longer draws it. That is
            // the point of folding it: the sentence answers a question asked once, so it stops
            // costing every sighted user a second line for ever, and a screen-reader user — who
            // meets each row exactly once, in sequence — keeps it in full.
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(label)
                    // The badge, spoken. A sighted user reads the brass plate and the PRO badge as
                    // one cell; without this a screen-reader user meets a row that sounds ordinary
                    // right up until selecting it raises a refusal.
                    if (mark == ProTypeMark.Spent) spentBadgeText?.let { append(", ").append(it) }
                    append(". ")
                    append(description)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Box(
            modifier = Modifier
                .size(AddCellPlate)
                .lkSurface(LkShape.md, accent.copy(alpha = 0.07f), accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(type.icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        // Two lines, and it is measurement rather than caution. Half a 392 dp device, less the
        // sheet's own 10 dp margins, the 4 dp gutter, 8 dp of cell padding either side, the 32 dp
        // plate and its 8 dp gap, leaves 128 dp for the label (122 dp since the grid took its
        // [AddGridInset] start inset, which Spanish below still clears by wrapping). Spanish
        // "Inicio de sesión" is about
        // 125 dp at 15 sp Medium — inside it by 3 dp at font scale 1.0 and outside it at anything
        // larger. One line would clip in Spanish on the very first screen of the add flow.
        // Label over badge, not label beside badge (V3-05). The two used to share one line: a cell
        // is half a 392 dp device less its margins, plate and padding — 128 dp for both — and
        // "1 OF 1" fitted in that only because it was seven characters. A badge that names the cap
        // and the holding is two to three times that, and beside the label it would leave French
        // "Identité" around 24 dp, which is a clipped word rather than a tight one. Stacked, the
        // badge gets the whole 128 dp and the label gets it back.
        //
        // The row's `IntrinsicSize.Min` already hands both cells the taller one's height, so the
        // sibling of a badged cell grows with it rather than sitting top-aligned against it.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
        ) {
            Text(
                label,
                style = cellLabelStyle(stacked),
                // Quieter only where selecting the row will refuse. An unspent allowance is a row
                // that works, so it keeps the primary label even though it is plated brass.
                color = if (mark == ProTypeMark.Spent) c.textSecondary else c.textPrimary,
                maxLines = cellLabelMaxLines(stacked),
            )
            // No chevron. Two columns leave no room for one, and a chevron on a chooser cell claims
            // a navigation depth this sheet does not have — [NewFolderCell] drops its own for the
            // same reason, so every cell in the grid reads as one class of control.
            //
            // The badge is where the two metered states differ, and it is deliberately the *only*
            // place they differ in kind: brass says "this tier meters this", the numbers say which
            // side of the allowance the vault is on. A row that says nothing until the slot is gone
            // teaches the cap by enforcing it, which is the complaint this replaced.
            if (mark == ProTypeMark.Spent) {
                spentBadgeText?.let {
                    LkBadge(it, accent = c.brass, container = c.brass.copy(alpha = 0.12f))
                }
            }
        }
    }
}

package com.localkeep.core.ui.theme

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.R
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.minTouchTarget
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * One row of an open folder list, in terms this module can draw.
 *
 * **Deliberately not `Folder`.** `core:ui` is the design system and depends on nothing but
 * `core:common` — it has never known what a vault item is, and a folder picker is not a good enough
 * reason to teach it. [id] is opaque here: the caller says what identifies a row and gets the same
 * value back on selection. The tree walk that produces these rows is domain logic and lives where
 * the domain does, in `core:model`'s `FolderPickerState`.
 *
 * @param name what to show, already disambiguated by the caller when two siblings share a word, and
 *   already carrying its item count — the comp's rows read "Personal (12)".
 * @param depth indent level; the caller caps it, since the tree's depth limit is a domain rule.
 * @param hasChildren whether this folder holds subfolders — the only rows that get a chevron.
 * @param expanded whether those subfolders are currently in the list below it.
 *
 * **`hasChildren`/`expanded` came back on 2026-08-20**, on the principal's request: *"I want to have
 * the ability to hide a folder if way too nested — hide its subfolders and remain only root level
 * folders."* They were dropped on 2026-08-19 because the comp draws the tree flat, and flat is still
 * the default: every row arrives expanded, so a picker that is never touched looks exactly as the
 * comp does. The chevron only takes something away once the user asks it to.
 */
data class LkFolderRow<out K : Any>(
    val id: K,
    val name: String,
    val depth: Int,
    val hasChildren: Boolean = false,
    val expanded: Boolean = true,
)

/**
 * Remembers which folders the user has folded away, across a rotation.
 *
 * The set is *collapsed* ids rather than expanded ones, so a folder created or imported while the
 * picker is open arrives open — the default this screen keeps from the comp — instead of arriving
 * hidden because nothing had put it in an expanded set yet.
 *
 * Keyed by [UUID] because every caller's folder id is one; `core:ui` still knows nothing about what
 * a folder contains.
 */
val LkCollapsedFoldersSaver: Saver<Set<UUID>, Any> = listSaver(
    save = { ids -> ids.map(UUID::toString) },
    restore = { saved -> saved.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet() },
)

/** Whether a typed new-folder name may be used, and why not when it may not. */
enum class LkFolderNameStatus {
    /** Usable. */
    Free,

    /** A sibling of the target parent already has it — the create will be refused. */
    TakenHere,

    /** Some other folder has it, which is legal: two parents may each hold a "Receipts". */
    TakenElsewhere,
}

/**
 * Where an item is filed, stated in one row, with the way to change it.
 *
 * Replaces the disclosure that used to expand a whole folder tree inside the form. That control
 * had two properties the comp does not: it grew an already-scrolling screen by however many folders
 * the vault holds, and it had no back — the system back key collapsed a disclosure, which is not
 * what a user who has just navigated somewhere expects it to do. `Item Flows.dc.html` puts the list
 * on a screen of its own ([LkFolderPickerScreen]) and leaves this row behind to name the current
 * filing, which is the only part worth space in the form.
 *
 * @param selectedName null when the item is filed nowhere; the row then reads "None" in the
 *   placeholder colour, like every other unset field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LkFolderField(
    selectedName: String?,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LkFieldLabel(stringResource(R.string.core_ui_folder_label))
        // A flow, not a [Row] with the name in `weight(1f)` (SCL-022). The weighted name is handed
        // what the glyph and the action leave, and the action's label is unwrappable: at 308dp and
        // font scale 2.0 that left 31dp, and the folder name broke into fragments one or two letters
        // wide. [FlowRow] measures the name and the action whole and drops the action under the name
        // exactly when the pair stops fitting, at whichever width, scale and language that is. At
        // ordinary sizes both fit and `SpaceBetween` pins the action to the right edge, the row the
        // comp draws.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.field, LkShape.lg)
                .lkRing(LkShape.lg, c.ring)
                .clickable(
                    onClickLabel = stringResource(R.string.core_ui_folder_change_a11y),
                    onClick = onChange,
                )
                .minTouchTarget()
                .padding(horizontal = 15.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                // The gap rides on the name so that it is part of what has to fit: a name that just
                // fits would otherwise touch the action.
                modifier = Modifier.padding(end = LkSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Icon(
                    LkIcons.Folder,
                    contentDescription = null,
                    // Jade once something is filed, faint while nothing is: the colour carries the same
                    // fact as the word, for anyone scanning the form rather than reading it.
                    tint = if (selectedName == null) c.textFaint else c.jade,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    selectedName ?: stringResource(R.string.core_ui_folder_none),
                    style = LkText.body,
                    color = if (selectedName == null) c.textFaint else c.textPrimary,
                )
            }
            // The affordance, in words. A lone chevron on a row whose value is already legible does
            // not say whether the tap opens a chooser or a detail screen.
            Text(
                stringResource(R.string.core_ui_folder_change),
                style = LkText.buttonSmall,
                color = c.jade,
            )
        }
    }
}

/**
 * The folder chooser, as `Item Flows.dc.html` draws it.
 *
 * Its closed row used to read "No folder", which is a sentence with two meanings and the wrong one
 * is the one people took: not "this item is filed nowhere" but "this app has no folders", i.e. a
 * failure. That row is now [LkFolderField] and says "None"; this screen is where the choosing
 * happens, and it says in its own subtitle what a folder here *is* — vault-local, nestable, one per
 * item — because that is the question being asked at the moment it is on screen.
 *
 * **Matched to the comp property by property on 2026-08-19**, after a first pass built from the
 * screenshot rather than the markup shipped four things the comp does not have: a back chevron
 * beside the title, filled row backgrounds, a per-row trash, and an expand chevron on every folder
 * with children. The comp has none of them. What it has instead:
 *
 * - **No header button.** The title sits at the left margin. Leaving is the system back key or the
 *   secondary button at the bottom, both of which already exist and neither of which costs a
 *   control beside the title.
 * - **Transparent rows** with a hairline ring; only the selected row is filled, and in jade.
 * - **The whole tree, flat, indented, and expanded on arrival.** A chooser that *opens* with half the
 *   vault's folders hidden behind chevrons makes the user open things to find out whether they
 *   wanted them, so nothing starts collapsed. Since 2026-08-20 a row with children carries a
 *   chevron the user may close — for the vault whose tree is deep enough that the list is the
 *   problem — plus one control that takes the whole list back to its roots. Both are additive: an
 *   untouched picker is still the comp's flat list.
 * - **No delete.** Making a folder from here is a step in filing something; destroying one is
 *   management, and management has a screen.
 *
 * Selecting a row commits and returns in one tap — the comp keeps a check mark and waits for Done,
 * which costs a tap on the only action anyone comes here for.
 *
 * **Two callers, one screen.** The item editor opens it to *choose* where an item is filed. The
 * vault list opens it to *make* a folder — same list, same rows, same naming card, but the row that
 * carries the tick is the new folder's **parent** rather than an item's filing, and the card is open
 * on arrival because tapping "New folder" has already said what the user came to do. That is the
 * whole difference, so it is expressed as words rather than as a mode: [title], [subtitle],
 * [rootLabel] and the two click labels are what change, and the naming card is opened by the caller
 * passing a non-null [newFolderName] with no [onCreateStart].
 *
 * It replaced a modal `NewFolderDialog` on the vault list (2026-08-19). The dialog could not show
 * the folders that already exist — so a name was typed with no sight of the ones it had to be
 * distinct from — and it fixed the parent to whatever folder was being browsed, with a sentence
 * saying so. Here the parent is a row you can tap.
 *
 * @param onPick receives the chosen id, or null for the root row. In pick mode the caller is
 *   expected to leave; in create mode it records the parent and stays.
 * @param onCreateStart null when this caller does not offer making folders; the button is then absent
 *   rather than present-and-inert. Also null for a caller whose card is open from the start.
 * @param onCreateCancel abandons the naming card. For a screen that exists only to name a folder,
 *   that means leaving it.
 * @param newFolderName non-null exactly while the create card is open.
 * @param arrivedId flashes one row that has just been created. Presentation only — a caller that
 *   loses it to a rotation has lost nothing. Non-null also means "a folder was made on this visit",
 *   which turns the way out from Cancel into Done — see [folderPickerLeavesOnDismiss].
 * @param title the screen's heading. Defaults to the item-editor's "Folder".
 * @param subtitle the sentence under it, which states what is being asked at the moment it is asked.
 * @param rootLabel the first row's text — "None" when filing an item, "At the top level" when
 *   choosing a parent. Same row, and it means the same thing: no folder above this.
 */
@Composable
fun <K : Any> LkFolderPickerScreen(
    rows: List<LkFolderRow<K>>,
    selectedId: K?,
    onPick: (K?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opens or closes one folder's subfolders. Null for a caller that does not track expansion; the
     * chevrons are then absent rather than present-and-inert.
     */
    onToggleExpanded: ((K) -> Unit)? = null,
    /** Closes every folder at once, leaving the root level. Null hides the control. */
    onCollapseAll: (() -> Unit)? = null,
    /** Reopens everything. Null hides the control. */
    onExpandAll: (() -> Unit)? = null,
    onCreateStart: (() -> Unit)? = null,
    onCreateCancel: () -> Unit = {},
    newFolderName: String? = null,
    onNewFolderNameChange: (String) -> Unit = {},
    onCreateConfirm: () -> Unit = {},
    newFolderStatus: LkFolderNameStatus = LkFolderNameStatus.Free,
    arrivedId: K? = null,
    title: String = stringResource(R.string.core_ui_folder_screen_title),
    subtitle: String = stringResource(R.string.core_ui_folder_screen_subtitle),
    rootLabel: String = stringResource(R.string.core_ui_folder_none),
    rootClickLabel: String = stringResource(R.string.core_ui_folder_remove_a11y),
    // @Composable, because the default resolves a string resource and every caller's does too.
    rowClickLabel: @Composable (String) -> String = {
        stringResource(R.string.core_ui_folder_move_to_a11y, it)
    },
) {
    val c = LkTheme.colors
    val naming = newFolderName != null
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // One path for the pinned Create button and the IME's Done, and the same in both callers. A
    // create is a small reset (principal, 2026-10-14: "Clicking create folder should hide the
    // textbox menu for texting and focus on create new folder name etc — it has to be a small reset
    // each time one adds a new folder."; repeated 2026-10-15 for the vault list's New folder
    // screen): the keyboard goes before the write, and focus goes with it because a field that
    // still holds focus raises the IME again on the next recomposition. What happens to the card
    // is the caller's — the editors collapse it, the vault list empties it and keeps it open — but
    // neither leaves a keyboard up over the folder that was just made.
    //
    // Only reached when the name is committable: the button is disabled and Done is guarded
    // otherwise, so a refused name (a sibling already has it) keeps its focus and its keyboard.
    val confirmCreate: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus()
        onCreateConfirm()
    }
    // One rule with the other keyboard-up screens (see [rememberLkKeyboardUnpinsChrome]): while the
    // keyboard leaves a short window too little room, the header and the Create / Cancel block
    // scroll with the list instead of being pinned around it. Pinned, the three claims on the window
    // left the scroll region 0dp at 308dp wide — the name field, Create and Cancel were all gone and
    // nothing scrolled (SCL-001), on the one screen whose keyboard opens by itself.
    val unpinned = rememberLkKeyboardUnpinsChrome()
    val header: @Composable (Boolean) -> Unit = { pinned ->
        // Built here rather than with LkScreenHeader: the comp gives this screen no header
        // control at all, and every LkScreenHeader call site in the app passes one. The type and
        // spacing are the same tokens the header uses — LkText.screenTitle is the comp's 21/500
        // /-.026 exactly — so this is the header's typography without the button slot.
        Column(
            Modifier
                // The entrance plays once, on the pinned header. Inside the scroll (keyboard up on a
                // short window) the header has just been moved there, and replaying it would flash.
                .then(if (pinned) Modifier.lkEnter() else Modifier)
                .fillMaxWidth()
                // 26dp and 46dp: `Item Flows.dc.html` pads every screen in the set
                // `90px 26px 32px` over a 44px status bar, and the app is already inset by
                // `systemBars` at the root. No `HeaderTitleInset` correction is needed here the
                // way the item form needs one — this screen has no control beside its title, so
                // the title *is* the first row and 46 lands it exactly.
                // The scroll that holds the header when it is not pinned already carries the horizontal
                // margin, and a short window has no height to spend on 46dp of air.
                .then(if (pinned) Modifier.padding(horizontal = LkFrame.horizontal) else Modifier)
                .padding(top = if (pinned) 46.dp else LkSpace.xs),
            verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            // Beside the title, not under it. Only a tree with something to fold gets this at
            // all — on the vault most people have, a handful of root folders and nothing
            // beneath them, it never appears and the screen is the comp's exactly. Putting it
            // on the title's own row is what keeps that true *vertically* as well: a control
            // on a line of its own costs 49px, which was enough to push the first row of the
            // list under the fold on the screen that opens with the naming field focused.
            val anyChildren = rows.any { it.hasChildren }
            val foldable = anyChildren && (onCollapseAll != null || onExpandAll != null)
            // A flow, not a weighted row: in German "Alle einklappen" measured 189 of 256dp at the
            // 308dp floor and the title took the 67dp left, so "Neuer Ordner" broke into four
            // fragments (SCL-018). Unweighted, the title keeps its natural width and the control
            // moves to its own line when the two do not fit together, instead of starving it.
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = LkText.screenTitle,
                    color = c.textStrong,
                )
                if (foldable) {
                    val allCollapsed = rows.none { it.depth > 0 }
                    LkTextButton(
                        text = stringResource(
                            if (allCollapsed) {
                                R.string.core_ui_folder_expand_all
                            } else {
                                R.string.core_ui_folder_collapse_all
                            },
                        ),
                        onClick = {
                            // Put the keyboard away first. Folding the tree is a *navigation*
                            // action — the whole reason to tap it is to see more of the list —
                            // and with the naming field focused the IME covers roughly half the
                            // screen, so the rows this control just revealed appear behind it.
                            // Reported from hands-on use 2026-08-20: "when I do collapse all I
                            // expect the keyboard to hide".
                            keyboard?.hide()
                            if (allCollapsed) onExpandAll?.invoke() else onCollapseAll?.invoke()
                        },
                        fillWidth = false,
                    )
                }
            }
            Text(subtitle, style = LkText.bodyQuiet, color = c.textFaint)
        }
    }
    // Create is pinned with Cancel, not parked inside the naming card.
    //
    // The comp is explicit about it — the card holds `label / input / hint` and the buttons are a
    // separate `flex-direction:column;gap:8px` block at the bottom of the screen — and the reason
    // shows up the moment the list is longer than the viewport: a commit button inside a scrolling
    // card scrolls away with it, so the one action the screen exists for is somewhere the user has to
    // go and find. It also drew a second full-width button *inside* a card that already had a jade
    // ring, which read as two nested states of the same thing.
    //
    // Unpinned, it rides directly under the card instead — outside its ring, inside its reveal — so
    // the scroll that brings the field above the keyboard brings Create with it. Following the card as
    // the next item of the scroll, it sat under the viewport's edge on a 360x640 window, and a tap
    // there made nothing (`LkFolderPickerKeyboardUnpinsTest`).
    val createButton: @Composable () -> Unit = {
        if (naming) {
            val canCreate = canCreateFolder(newFolderName, newFolderStatus)
            // Resolved at composition, never inside the semantics lambda: `stringResource`
            // is a composable call and the lambda is not a composition
            // (LocalContextGetResourceValueCall is an error in this repo for the same
            // reason).
            val inertReason = createFolderInertReason(newFolderName, newFolderStatus)
                ?.let { stringResource(it) }
            LkPrimaryButton(
                text = stringResource(R.string.core_ui_folder_create_ready_a11y),
                onClick = confirmCreate,
                enabled = canCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        // Says *why* it is inert rather than leaving a screen-reader user
                        // to guess.
                        if (inertReason == null) {
                            Modifier
                        } else {
                            Modifier.semantics { contentDescription = inertReason }
                        },
                    ),
            )
        }
    }
    val actions: @Composable (Boolean) -> Unit = { pinned ->
        // Pinned, as the comp pins it: the reason to make a folder usually arrives after reading
        // the ones that exist, and on a vault with more folders than fit, an action at the end
        // of the scroll is an action the user has to go looking for.
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (pinned) Modifier.padding(horizontal = LkFrame.horizontal) else Modifier)
                // Unpinned, the scroll spaces this block `xs` from Create above it; the comp's gap
                // between the two buttons is `sm`.
                .padding(top = if (pinned) 0.dp else LkSpace.sm - LkSpace.xs)
                // 32dp, the comp's bottom padding — `gutterPlain` (40dp) is the gutter under a
                // *scrolling* column, and this column is pinned.
                .padding(bottom = if (pinned) 32.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            if (!naming && onCreateStart != null) {
                LkPrimaryButton(
                    text = stringResource(R.string.core_ui_folder_new_button),
                    icon = LkIcons.Plus,
                    onClick = onCreateStart,
                )
            }
            if (pinned) createButton()
            // Cancel abandons the name being typed and stays; Done leaves with the filing
            // untouched. Two different retreats, so they are never the same word.
            //
            // Once a folder has been made here and the field is empty again there is nothing
            // left to abandon, and "Cancel" on a screen that just did what it was asked reads
            // as an undo. It becomes Done and leaves — see [folderPickerLeavesOnDismiss].
            val leaves = folderPickerLeavesOnDismiss(naming, newFolderName, createdAny = arrivedId != null)
            LkSecondaryButton(
                text = stringResource(
                    if (leaves) {
                        R.string.core_ui_folder_screen_done
                    } else {
                        R.string.core_ui_folder_screen_cancel
                    },
                ),
                onClick = if (leaves) onBack else onCreateCancel,
            )
        }
    }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // Where the new folder will go, stated on the naming card itself (D3-F-01). With the keyboard up
    // the list opens scrolled to the card, which sits under every folder, so the row that carries the
    // tick — the parent, in create mode — can be a long way above the viewport and the user cannot
    // see where the folder will be made. Only in create mode (no [onCreateStart], card open from the
    // start): in pick mode the ticked row is the item's filing, not the new folder's parent, and the
    // caption would say something false. Null when the ticked row is hidden inside a folded branch,
    // rather than claiming the top level for a parent that is not.
    val destination: String? = when {
        !naming || onCreateStart != null -> null
        selectedId == null -> stringResource(R.string.core_ui_folder_new_destination_top)
        else -> rows.firstOrNull { it.id == selectedId }
            ?.let { stringResource(R.string.core_ui_folder_new_destination_inside, it.name) }
    }
    // Attached to whichever row carries the tick, so the caption can scroll to it.
    val selectionReveal = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    LkScreen(modifier = modifier, accent = c.jade, auraAlpha = 0.08f, auraBias = 0.5f) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (!unpinned) header(true)

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LkFrame.horizontal)
                    .padding(top = LkSpace.md, bottom = LkSpace.md),
                verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
            ) {
                if (unpinned) header(false)
                // The comp's list opens with None as an ordinary row carrying the tick, not as a
                // separate control above the folders. It is the current answer for most items, and
                // the way back out of a folder for the rest.
                LkFolderOption(
                    name = rootLabel,
                    selected = selectedId == null,
                    depth = 0,
                    clickLabel = rootClickLabel,
                    onClick = { onPick(null) },
                    modifier = if (selectedId == null) Modifier.bringIntoViewRequester(selectionReveal) else Modifier,
                )
                rows.forEach { row ->
                    // The folder just made may sort anywhere in the list, and the keyboard is
                    // down, so nothing else would bring it into view. Scrolled to once per
                    // arrival; the flash below then lands on a row that is on screen.
                    val reveal = remember(row.id) { BringIntoViewRequester() }
                    if (row.id == arrivedId) {
                        LaunchedEffect(arrivedId) {
                            // After a frame, so the request is made against the layout that has
                            // the new row (and the emptied field) in it.
                            withFrameNanos { }
                            runCatching { reveal.bringIntoView() }
                        }
                    }
                    // The row the caller marks as the current answer, brought on screen for the same
                    // reason: it may be anywhere in a long tree, and nothing else scrolls to it, so
                    // the screen opened on a list that did not show which folder was chosen. Again
                    // when the keyboard's height changes, because the viewport has just lost that
                    // much of its bottom and the row can have gone under it. After a frame, so the
                    // request measures against the layout the inset has already shrunk. Minimal
                    // scrolling: a row already on screen does not move, so tapping one is not a jump.
                    if (row.id == selectedId) {
                        LaunchedEffect(selectedId, imeBottom) {
                            withFrameNanos { }
                            runCatching { reveal.bringIntoView() }
                        }
                    }
                    LkFolderOption(
                        name = row.name,
                        selected = row.id == selectedId,
                        depth = row.depth,
                        clickLabel = rowClickLabel(row.name),
                        onClick = { onPick(row.id) },
                        hasChildren = row.hasChildren,
                        expanded = row.expanded,
                        onToggle = onToggleExpanded?.let { toggle -> { toggle(row.id) } },
                        modifier = Modifier
                            .bringIntoViewRequester(reveal)
                            .then(
                                if (row.id == selectedId) Modifier.bringIntoViewRequester(selectionReveal) else Modifier,
                            )
                            .lkArrival(
                                arrived = row.id == arrivedId,
                                shape = LkShape.xl,
                                key = row.id,
                            ),
                    )
                }
                if (newFolderName != null) {
                    Spacer(Modifier.size(LkSpace.sm))
                    LkNewFolderCard(
                        name = newFolderName,
                        status = newFolderStatus,
                        onNameChange = onNewFolderNameChange,
                        onCreate = confirmCreate,
                        treeArrived = rows.isNotEmpty(),
                        destination = destination,
                        onShowDestination = {
                            // Not hiding the keyboard: the user is mid-name, and the viewport already
                            // ends at the keys. Minimal scroll, so a row already in view does not move.
                            scope.launch { runCatching { selectionReveal.bringIntoView() } }
                        },
                        below = { if (unpinned) createButton() },
                    )
                }
                if (unpinned) actions(false)
            }

            if (!unpinned) actions(true)
        }
    }
}

/**
 * Naming a new folder, in place under the list it will join.
 *
 * `Item Flows.dc.html` calls this "the shortest flow in the app, and the one most likely to be
 * abandoned halfway", and the three things it asks for are all about not losing someone mid-name:
 * the field opens *under* the list rather than on a new screen, so the folders it will sit among
 * stay visible; the button says what it will do rather than "Save"; and the hint under the field
 * answers the question the name itself raises.
 *
 * **The duplicate hint departs from the comp, and the comp is wrong here.** It says a duplicate is
 * "not an error and not a block", because two folders may share a name in different parents. Half
 * true: a name that collides with a *sibling* is refused and one that collides anywhere else is
 * accepted. So a sibling collision is stated as a refusal *before* the tap, with Create disabled —
 * better than the shipped behaviour, which accepted the tap and then raised an error at the bottom
 * of a form taller than the screen — and a non-sibling collision is stated as the reassurance it is.
 */
@Composable
private fun LkNewFolderCard(
    name: String,
    status: LkFolderNameStatus,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    /** Whether the folder list above the card has anything in it yet — see the reveal below. */
    treeArrived: Boolean,
    /** "Inside D7" / "At the top level": where this folder will be made. Null states nothing. */
    destination: String? = null,
    /** Scrolls the list to the row [destination] names. */
    onShowDestination: () -> Unit = {},
    /** Under the card's ring and inside its reveal: the picker's Create, while it is not pinned. */
    below: @Composable () -> Unit = {},
) {
    val c = LkTheme.colors
    // **Focused on arrival, in both callers** (principal, 2026-10-14 for the editors and 2026-10-15
    // for the vault list's New folder screen): opening the card has already said "I want to type a
    // name", so the field takes focus and raises the keyboard. The vault list's screen used to wait
    // for a tap instead, so the IME would not cover the parent rows (reported 2026-08-20: "I click
    // and immediately keyboard opens.. when if user wants to add a folder in a subfolder
    // somewhere?"). That was overruled; the parent rows stay reachable because the list scrolls
    // inside `imePadding()`, and the keyboard goes away on Collapse all, on Create, and on the
    // system back key like any other.
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // After a frame, so the field is attached and laid out before it is asked for focus;
        // `requestFocus` on a node not yet in the tree throws.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    // The scroll to the card. On a vault with a deep tree the
    // card is appended below every folder, so the screen opened showing the tree and a disabled
    // `Create folder, type a name first` with the well it is asking about ten scroll gestures away
    // — a screen telling you to type a name and showing you nowhere to type it (2C-F5, on a device
    // 2026-09-20). Scrolling to the card is the fix rather than pinning the field at the top,
    // because the card sits under the list deliberately (`Item Flows.dc.html`): the folders it will
    // join have to stay visible.
    //
    // Keyed on the measured size so the request is made after layout, and re-made when the card
    // grows — which is what the duplicate-name hint changing height does.
    //
    // And re-made once when the tree arrives. The vault list's New folder screen loads its folders
    // off the main thread, so on a cold first open the card was laid out at the top of an empty
    // list, asked to be shown, already was — and was then pushed under every folder by rows that
    // landed a frame later, its size unchanged (device pass on the release build, 2026-10-04).
    // Keyed on *whether* there are rows, not on the rows: folding a branch changes them too, and
    // scrolling the user back to the card while they look for a parent would fight them.
    // `LkFolderPickerNameFieldOnArrivalTest` drives both arrivals on a device.
    val reveal = remember { BringIntoViewRequester() }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    //
    // And re-made when the keyboard's height changes. The card is raised on arrival, the field takes
    // focus in the same breath, and the IME arrives a few frames *after* the request has been
    // satisfied: the scroller then loses the height the keyboard takes, nothing asks again, and the
    // card ends up with its lower edge — the name field's hint, the amber "a folder here is already
    // called that" — behind the pinned Create bar. One frame of slack so the request measures
    // against the viewport the inset has already shrunk, as [LkTextField]'s own reveal does.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(cardSize, treeArrived, imeBottom) {
        if (cardSize.height > 0) {
            withFrameNanos { }
            runCatching { reveal.bringIntoView() }
        }
    }
    val canCreate = name.isNotBlank() && status != LkFolderNameStatus.TakenHere
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(reveal)
            .onSizeChanged { cardSize = it },
        verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .lkRise()
                // The lifted gradient, not the flat surface. The comp fills this card
                // `linear-gradient(150deg,#12171A,#0C1012)` — [LkColors.cardFill] — over a jade .22
                // ring, and it is the one card on the screen, so it is allowed to be the lifted one.
                .lkSurface(LkShape.card, c.cardFill, c.jade.copy(alpha = 0.22f))
                .padding(LkSpace.md),
            verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            // Visible, not just spoken. The comp puts a `<label>New folder name</label>` above the well
            // and a *different* string inside it as the placeholder — the label says what the field is
            // for and survives typing; the placeholder is an example and does not. This string was
            // already carried as the field's a11y name, so a screen reader had it and the screen did
            // not.
            LkFieldLabel(stringResource(R.string.core_ui_folder_new_label))
            LkTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.core_ui_folder_new_placeholder),
                label = stringResource(R.string.core_ui_folder_new_label),
                shape = LkShape.xl,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canCreate) onCreate() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (destination != null) {
                // Under the field it qualifies, above the hint about the name. Tappable: it is the
                // shortcut to the ticked row, which with the keyboard up is usually off screen.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .minTouchTarget()
                        .clip(LkShape.sm)
                        .clickable(role = Role.Button, onClick = onShowDestination),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
                ) {
                    Icon(
                        LkIcons.Folder,
                        contentDescription = null,
                        tint = c.jade,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        destination,
                        style = LkText.caption,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val hint = stringResource(
                when (status) {
                    LkFolderNameStatus.TakenHere -> R.string.core_ui_folder_new_hint_taken
                    LkFolderNameStatus.TakenElsewhere -> R.string.core_ui_folder_new_hint_elsewhere
                    LkFolderNameStatus.Free -> R.string.core_ui_folder_new_hint
                },
            )
            Text(
                hint,
                style = LkText.caption,
                // Amber, not coral: nothing is broken and nothing was lost. The user is being told the
                // name is unavailable here, before spending a tap on it.
                color = if (status == LkFolderNameStatus.TakenHere) c.amber else c.textFaint,
                // Announced, because the button going inert is otherwise a silent refusal.
                modifier = if (status == LkFolderNameStatus.TakenHere) Modifier.announce(hint) else Modifier,
            )
        }
        below()
    }
}

/**
 * Whether the picker's way out leaves the screen (true) or only abandons the name being typed (false).
 *
 * Making a folder here does not end the visit — the card stays open and empty so the next one costs
 * a name and no re-opening (2026-10-12: "What if I want to create 5 or 10 folders or 20?"). So the
 * second button and the Back key, which used to be "cancel the naming" whenever the card was open,
 * mean *leave* once something has been made and nothing is mid-typing. [createdAny] is the caller's
 * `arrivedId != null`: presentation state, so a rotation that loses it falls back to Cancel, which
 * still works — it closes the card, and Done follows.
 *
 * Public because the editors' Back handlers outside this file apply the same rule.
 */
fun folderPickerLeavesOnDismiss(naming: Boolean, name: String?, createdAny: Boolean): Boolean =
    !naming || (createdAny && name.isNullOrBlank())

/** Whether [LkNewFolderCard]'s name is committable — shared with the pinned Create button. */
private fun canCreateFolder(name: String?, status: LkFolderNameStatus): Boolean =
    !name.isNullOrBlank() && status != LkFolderNameStatus.TakenHere

/**
 * What the pinned Create button says when [canCreateFolder] refuses, or null while it is live.
 *
 * A typed name a sibling already has is its own reason: it was read as "type a name first" over a
 * field that held one (device pass 2026-10-04).
 *
 * Internal so the choice can be asserted on the JVM: [LkFolderPickerScreen] itself never reaches
 * idle under Robolectric (see `LkFolderPickerChevronA11yTest`).
 */
@StringRes
internal fun createFolderInertReason(name: String?, status: LkFolderNameStatus): Int? = when {
    canCreateFolder(name, status) -> null
    name.isNullOrBlank() -> R.string.core_ui_folder_create_needs_name_a11y
    else -> R.string.core_ui_folder_create_name_taken_a11y
}

/**
 * One row of the folder list, drawn to the comp's row.
 *
 * `height:56px; border-radius:16px; gap:12px; padding:0 16px 0 {indent}`, a 20px folder glyph, the
 * name at 15/500/-.012, and an 18px tick on the selected row. Every value below is the token that
 * already equals it: [LkShape.xl] is 16dp, [LkText.rowTitle] is 15/500/-.012, and the palette
 * (#F4F7F8, #C4CDD1, #7A858B, #3ED9AE) is [LkColors] unchanged.
 *
 * **Unselected rows are transparent**, carrying only a hairline ring. A first pass filled them with
 * `c.field` and the list read as a stack of wells rather than a list of choices — the selected row
 * has to be the only filled thing on the screen for the fill to mean "this one".
 *
 * The indent is inside the row, not a Spacer before it, so every card keeps the full width of the
 * screen and the column has one left edge instead of one per depth.
 */
@Composable
private fun LkFolderOption(
    name: String,
    selected: Boolean,
    depth: Int,
    clickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hasChildren: Boolean = false,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
) {
    val c = LkTheme.colors
    val toggle = onToggle.takeIf { hasChildren }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .lkSurface(
                LkShape.xl,
                if (selected) c.jade.copy(alpha = 0.07f) else Color.Transparent,
                if (selected) c.jade.copy(alpha = 0.24f) else c.ring,
            )
            .clickable(onClickLabel = clickLabel, onClick = onClick)
            // 16dp at the root and 38dp one level in, which is the comp's pair; deeper levels
            // continue the same 22dp step rather than stopping, since this tree goes to 8.
            // The end padding halves when the chevron is there, because the chevron carries its own
            // 48dp target and would otherwise sit 16dp off the edge with its glyph 30dp in.
            .padding(start = 16.dp + (depth * 22).dp, end = if (toggle != null) 4.dp else 16.dp)
            // Spoken as a state rather than left to the tick, which a screen reader cannot see.
            .labelled(
                if (selected) stringResource(R.string.core_ui_folder_selected_a11y, name) else name,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            LkIcons.Folder,
            contentDescription = null,
            tint = if (selected) c.jade else c.textFaint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            name,
            style = LkText.rowTitle,
            color = if (selected) c.textStrong else c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                LkIcons.Check,
                contentDescription = null,
                tint = c.jade,
                modifier = Modifier.size(18.dp),
            )
        }
        if (toggle != null) {
            // Its own target inside the row: tapping the chevron must not also file the item, and
            // tapping the row must not fold the branch away under the finger. One glyph, rotated
            // rather than swapped, so the direction it turns says which way the list is about to
            // move.
            val turn by animateFloatAsState(
                if (expanded) 0f else -90f,
                lkTween(LkMotion.Quick),
                label = "folder-chevron",
            )
            // The chevron sets its own mergeDescendants boundary via `clickable`, so it is never
            // absorbed into the row's `.labelled(...)` above — it needs a name and a role of its
            // own, or it announces as an unnamed, un-roled stop (LkIconButton's KDoc names the same
            // trap). `onClickLabel` alone is not enough: it becomes the "double tap to…" action hint,
            // not the node's name.
            val toggleLabel = stringResource(
                if (expanded) {
                    R.string.core_ui_folder_collapse_a11y
                } else {
                    R.string.core_ui_folder_expand_a11y
                },
                name,
            )
            Box(
                Modifier
                    .size(48.dp)
                    .clip(LkShape.sm)
                    .clickable(
                        onClickLabel = toggleLabel,
                        role = Role.Button,
                        onClick = toggle,
                    )
                    .labelled(toggleLabel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    LkIcons.ChevronDown,
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
                )
            }
        }
    }
}

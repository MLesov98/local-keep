package com.localkeep.feature.vaultlist

import android.app.Activity
import android.os.SystemClock
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.billing.PurchaseOutcome
import com.localkeep.core.common.sortedByDisplayName
import com.localkeep.core.model.Card
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderRelevance
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Identity
import com.localkeep.core.model.live
import com.localkeep.core.ui.theme.LkDragPayload
import com.localkeep.core.ui.theme.LkDropAcceptance
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.orderVaultItems
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.LkUnlockNotices
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.ProTypeQuota
import com.localkeep.core.vault.ForegroundVisibilityCoordinator
import com.localkeep.core.vault.MakeInvisibleAvailability
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.HideOutcome
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.RevealAttemptOutcome
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultVisibilitySnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.TreeSet
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The drawer's ITEMS group (UI/UX spec §4.3) narrows the same list rather than opening separate
 * screens, so search, favorites and row actions keep working identically under every filter.
 *
 * The label is a resource id, not a String: these seven are the drawer's entire ITEMS section, and
 * as plain Kotlin literals they were the one part of the drawer that stayed English under every
 * translation. Callers resolve with `stringResource` — the rule this codebase sets for ViewModels.
 */
enum class ItemTypeFilter(
    @param:StringRes val labelRes: Int,
    /**
     * The whole "nothing here yet" sentence, not the noun to drop into one.
     *
     * A category name interpolated into a template cannot be made grammatical: French elides the
     * preposition before a vowel, Portuguese agrees in gender and number, German capitalises. Each
     * language writes seven sentences instead, and the code stops caring which language it is in.
     */
    @param:StringRes val emptyTitleRes: Int,
) {
    LOGIN(R.string.vaultlist_category_logins, R.string.vaultlist_empty_no_logins_yet),
    TOTP(R.string.vaultlist_category_totp, R.string.vaultlist_empty_no_totp_yet),
    CARD(R.string.vaultlist_category_cards, R.string.vaultlist_empty_no_cards_yet),
    IDENTITY(R.string.vaultlist_category_identities, R.string.vaultlist_empty_no_identities_yet),
    NOTE(R.string.vaultlist_category_notes, R.string.vaultlist_empty_no_notes_yet),
    FILE(R.string.vaultlist_category_files, R.string.vaultlist_empty_no_files_yet),

    /**
     * Passkeys are saved by the credential provider rather than by any add flow, which is exactly
     * why the list needs to reach them: they are the one item type that arrives without the user
     * putting it there, and until this entry existed there was no way to see what had accumulated.
     */
    PASSKEY(R.string.vaultlist_category_passkeys, R.string.vaultlist_empty_no_passkeys_yet),
    COORDINATE(R.string.vaultlist_category_coordinates, R.string.vaultlist_empty_no_coordinates_yet),
    PHRASE(R.string.vaultlist_category_phrases, R.string.vaultlist_empty_no_phrases_yet),
    ;

    internal fun matches(item: VaultItem): Boolean = when (this) {
        LOGIN -> item.content is Login
        TOTP -> item.content is Totp
        CARD -> item.content is Card
        IDENTITY -> item.content is Identity
        NOTE -> item.content is Note
        FILE -> item.content is File
        PASSKEY -> item.content is Passkey
        COORDINATE -> item.content is Coordinate
        PHRASE -> item.content is SeedPhrase
    }

    /**
     * Pro is what the vault holds beyond passwords. Single source — the drawer padlock, the
     * upsell empty state and the add gate all read this, so they can never drift apart.
     *
     * [FILE] left this set on 2026-07-29. Files and photos became a *quota* — seven free since
     * 2026-09-03, five before that, see `AttachmentQuota` — and a padlock beside a category the
     * user is allowed to put things in would be a lie told by the drawer. Running out of slots is
     * reported at the first add past the cap, where the number can actually be named.
     */
    val requiresPro: Boolean
        get() = this == CARD || this == IDENTITY

    // `parse(raw: String?)` lived here until 2026-09-07. Its only caller was the `vault/{type}`
    // destination, which the filter-bar rework of 2026-09-02 stopped navigating to and this sweep
    // removed; the type filter is ViewModel state now and never arrives as a route argument.
}

/**
 * One or more items being dragged.
 *
 * The whole selection when a selection is running and the grabbed row is part of it; otherwise just
 * the row. Anything else surprises: grabbing a selected row and moving one item reads as the
 * selection having been silently dropped.
 */
data class ItemsDragPayload(val ids: Set<UUID>) : LkDragPayload {
    override val count: Int get() = ids.size
}

/**
 * One folder being dragged. **Singular, and that is a decision rather than an omission.**
 *
 * There is no selection state for folders anywhere in this screen: `FolderBrowseRow` takes no
 * `selecting`, `selected` or `onToggleSelected`, and `state.selectedIds` is populated exclusively by
 * item rows. A plural folder payload would be a count the UI cannot produce, and building folder
 * selection to make it produceable is a different feature.
 */
data class FolderDragPayload(val folderId: UUID) : LkDragPayload {
    override val count: Int get() = 1
}

/** An inline error a hide flow shows without committing, keeping the sheet open. */
enum class HideFlowError { PhraseCollision, InvalidDuration }

/**
 * The in-progress "Hide…" flow: which target, whether the vault still needs a first reveal phrase,
 * and any inline error to show. Null when no sheet is open. Held in UI state (not saved) so the
 * duration sheet and the first-phrase dialog are a single flow the ViewModel drives.
 */
data class HideFlowState(
    val target: HiddenTarget,
    val label: String,
    val needsPhrase: Boolean = false,
    /** The duration chosen at the first step, carried across the phrase step so it is not re-asked. */
    val pendingUntil: Instant? = null,
    val error: HideFlowError? = null,
)

/**
 * The flow once its phrase field has been edited: the inline error is dropped.
 *
 * A collision is a verdict on one particular phrase. Left up, it stayed under a valid replacement
 * (coral ring and "that phrase is taken" beneath text that is not that phrase) until the next
 * submit, and the user was left unable to tell whether the new phrase had been looked at.
 */
internal fun HideFlowState.afterPhraseEdited(): HideFlowState =
    if (error == null) this else copy(error = null)

data class VaultListUiState(
    val items: List<VaultItem> = emptyList(),
    val searchQuery: String = "",
    val showFavoritesOnly: Boolean = false,
    val typeFilter: ItemTypeFilter? = null,
    /**
     * The one tag the list is narrowed to, or null. Single-select like [typeFilter], and for the
     * same reason: one token names it, and clearing it is one tap. A tag is user data — the value
     * is displayed verbatim wherever it appears and never goes through the string catalogue.
     */
    val tagFilter: String? = null,
    /**
     * Every distinct tag on a visible item, in display-name order — what the filter sheet offers.
     * Computed in [refresh] from the same visibility snapshot the items come from, so outside a
     * reveal session a concealed item's tags are not in it: a tag only that item carries would
     * otherwise announce, from the filter sheet, that a hidden entry exists.
     */
    val availableTags: List<String> = emptyList(),
    /**
     * True while the list is narrowed to concealed entries. Only ever true inside a reveal session
     * — the control that sets it is composed only under [revealSessionActive], and every path that
     * ends the session clears it, so it cannot outlive the session and leave an invisible filter
     * over an empty list.
     */
    val showHiddenOnly: Boolean = false,
    /**
     * Folders holding at least one item of [typeFilter], plus their ancestors. Null when no type
     * chip is on, which is not the same as empty — empty means "the filter is on and nothing
     * matches anywhere", and that is a state the strip must render as no folders at all.
     *
     * Computed in [refresh] from the same visibility snapshot the items come from, so a concealed
     * item cannot make a folder relevant.
     */
    val relevantFolderIds: Set<UUID>? = null,
    /**
     * This session was opened with the Recovery Kit, so the list offers the password reset once.
     * Session-scoped: it comes from [LockState.Unlocked] and is cleared on lock with everything else.
     */
    val openedWithRecoveryKit: Boolean = false,
    @param:StringRes val error: Int? = null,
    /** True only when [error] came from a failed *read*, which is the case with nothing else to press. */
    val loadFailed: Boolean = false,
    /**
     * True once a scan has published, whether it succeeded or failed.
     *
     * The empty panel asks whether [items] and `childFolders` are empty, and until this flag existed
     * it had no way to tell "the vault holds nothing" from "the first scan has not landed yet" —
     * both are the default `emptyList()`. `scanAndPublish` runs on `Dispatchers.Default`, so the
     * screen composes at least once before it publishes, and the panel it composed said "Nothing
     * here yet" over a vault that has plenty. Short in practice (the scan is an in-memory pass over
     * an already-decrypted vault), which is why this is a flag rather than a skeleton — but a
     * screen whose whole job is to reassure someone their vault is intact should never assert it is
     * empty on the strength of not having looked. 45-1.
     *
     * Not `projectionVersion > 0`, which is bumped by the same publish and would nearly do. That
     * counter is monotonic by contract and a lock does not reset it, so once a vault has been open
     * in this process it would answer "scanned" for every session afterwards — including the one
     * frame after the next unlock, which is the frame this exists for.
     */
    val hasScanned: Boolean = false,
    val isPro: Boolean = false, // 14b — cards/identities gate
    /**
     * The free tier's file-and-photo slots are all gone (see `AttachmentQuota`).
     *
     * Kept in state rather than computed at the tap, because the add sheet has to decide between
     * opening the file editor and raising the upgrade prompt, and dropping someone into a form that
     * cannot save is the failure this exists to prevent. Always false for Pro.
     */
    val attachmentQuotaFull: Boolean = false,
    /**
     * How many file-and-photo slots the vault is actually using.
     *
     * Carried beside [attachmentQuotaFull] because the refusal has to state a real number. It used
     * to state `FREE_LIMIT` twice — "5 of 5 used" was a literal, not a count — so a vault sitting
     * above the cap (import never charges against it) rendered the identical sentence after every
     * deletion, alongside the instruction "remove one to make room" that the app knew was false.
     * Zero when the vault could not be read, which is also when [attachmentQuotaFull] is false.
     */
    val attachmentQuotaUsed: Int = 0,
    /**
     * The free tier's single Card slot is taken (see `ProTypeQuota`). Always false for Pro.
     *
     * Kept in state for the same reason [attachmentQuotaFull] is: the add sheet decides between
     * opening the editor and raising the refusal at the tap, and a vault walk at that moment is
     * both slow and — while the vault is locking — capable of throwing.
     */
    val cardQuotaFull: Boolean = false,
    /** How many Cards the vault holds. Reports the truth above the cap; see `ProTypeQuota.used`. */
    val cardQuotaUsed: Int = 0,
    /** The free tier's single Identity slot is taken (see `ProTypeQuota`). Always false for Pro. */
    val identityQuotaFull: Boolean = false,
    /** How many Identity documents the vault holds. Truthful above the cap, like [cardQuotaUsed]. */
    val identityQuotaUsed: Int = 0,
    val canPurchase: Boolean = false,
    /**
     * Play's own localised price, or null when this build cannot know it — see
     * [com.localkeep.core.billing.Entitlement.displayPrice]. Null selects the no-price wording;
     * it must never be replaced by a literal amount.
     */
    val proPrice: String? = null,
    /**
     * Monotonic counter, bumped once per published item scan.
     *
     * It says "a scan has landed since you last looked", and that is the whole of what it does. It
     * is the only signal that says so: `folders` is written by a different producer in the
     * `presentationState` collector and lands earlier, so waiting on a folder to assert on an item
     * is waiting on the wrong thing — `VaultListHiddenVisibilityInstrumentedTest` reads this
     * counter for exactly that reason, and used to flake before it did.
     *
     * It is also copied into [com.localkeep.core.model.MediaViewerFocusRequest] on a viewer launch,
     * where nothing reads it. This comment used to claim the viewer correlated launches by it; it
     * never has. Staleness there is re-derived live, from the repository's visibility snapshot
     * against the launch's `visibleOrderedItemIds` — a stronger check than a version compare, and
     * the one to keep looking at if a stale-viewer bug ever appears.
     */
    val projectionVersion: Long = 0L,
    /**
     * The multi-select working set.
     *
     * Ids rather than items so a background refresh — a save landing, the vault re-reading — cannot
     * leave the selection holding stale copies of rows that have since changed. Empty means no
     * selection mode; there is no separate boolean to keep in step with it.
     */
    val selectedIds: Set<UUID> = emptySet(),
    /**
     * True when some selected item passes every filter and is off screen only because of where the
     * list is browsing — a selection dragged into another folder stays selected. Set by [refresh];
     * read through [hiddenSelectionSubtitle], so the header does not blame a filter that is not on.
     */
    val selectionOutsideBrowse: Boolean = false,
    /**
     * How many items match [searchQuery] but were filtered out of [items] by the chips.
     *
     * Zero unless a search is running — with no query "outside the filter" just means "the rest of
     * the vault", which is not something the user asked about.
     *
     * This exists so a scoped search can offer a way out instead of dead-ending. Searching for a
     * login while the 2FA chip is lit used to say "No matching items" and stop there, with the
     * matching item sitting in the vault and nothing on screen admitting it. The alternative —
     * letting the query quietly escape the chip — was rejected: a chip that stays lit while showing
     * items it excludes is a control lying about what is on screen, and the design mock filters by
     * type before it ever looks at the query.
     */
    val matchesOutsideFilter: Int = 0,
    /**
     * The item just saved by the editor, which the list scrolls to and briefly emphasises.
     *
     * Null at rest. Cleared once the highlight has run — see [VaultListViewModel.clearReveal] — so
     * that a later refresh, a lock, or a return from some other screen does not replay it.
     */
    val revealedItemId: UUID? = null,
    /**
     * True while the vault-wide reveal phrase has been typed *this session*, so hidden entries are
     * showing. Distinct from [revealedItemId] (the post-save one-shot scroll highlight): this is the
     * concealment reveal session, drives the persistent revealed header, and is cleared by any lock,
     * conceal, or "Hide again now". Never persisted.
     */
    val revealSessionActive: Boolean = false,
    /**
     * Whole seconds left in the reveal session, or null when none is running.
     *
     * The banner is persistent because the state behind it is, and a persistent banner with no
     * stated end is the part that reads as a nag rather than as information. With the number on it
     * the same pixels answer the question the user actually had — *when does this stop?* — and the
     * answer is "on its own, shortly". Ticked in the ViewModel rather than in the composable so the
     * banner stays a pure function of state and a test can advance it without a frame clock.
     */
    val revealSecondsRemaining: Int? = null,
    /** The open Hide… flow, or null. Drives the duration sheet and first-phrase dialog. */
    val hideFlow: HideFlowState? = null,
    /**
     * Folder ids concealed right now — populated even in a revealed session, where the folders show
     * but carry a marker. Empty unless a reveal session is active (nothing concealed is in [folders]
     * otherwise). Lets a revealed row show the amber "Hidden" marker and a "Stop hiding" action.
     */
    val concealedFolderIds: Set<UUID> = emptySet(),
    /**
     * Item ids concealed right now — populated even in a revealed session, where the items show but
     * carry a marker. Empty unless a reveal session is active. Lets a revealed row show the amber
     * "Hidden" marker and a "Stop hiding" action; an ancestry-concealed item (hidden only because a
     * parent folder is) is *not* in this set, so it gets the marker but no misleading stop action.
     */
    val concealedItemIds: Set<UUID> = emptySet(),
    /**
     * The subset of [concealedItemIds]/[concealedFolderIds] concealed by a lease on the record
     * itself — the rows that may offer "Stop hiding". An ancestry-only descendant is in the concealed
     * sets (so it shows the marker) but not here (so it offers no stop action that would do nothing).
     */
    val directlyHiddenItemIds: Set<UUID> = emptySet(),
    val directlyHiddenFolderIds: Set<UUID> = emptySet(),
    /**
     * True while a bulk action's full-vault commit is in flight.
     *
     * Exposed so the UI can show the same in-flight note the other commit paths use: a bulk
     * edit re-encrypts the whole vault (~2.4 s at 10,000 items), and with the flag private the
     * list looked dead after the dialog closed and a re-tapped action returned silently at
     * [bulkEdit]'s guard with nothing to say why.
     */
    val bulkEditRunning: Boolean = false,
    /**
     * The vault's folders — offered by the bulk move sheet, and by the folder strip above the list.
     *
     * Free tier, like folders themselves.
     */
    val folders: List<Folder> = emptyList(),
    /**
     * Where in the folder hierarchy the list is browsing, root first; empty means the root.
     *
     * A path rather than a single id, because folders are containers now and the user drills in and
     * backs out. Kept here rather than in the nav graph: a folder id in a route would survive
     * process death and drop someone back into a folder they last saw days ago, and it would put a
     * vault identifier in a navigation argument, which nothing else in this app does.
     *
     * Deliberately not persisted across a lock or a tab change, for the same reason the chips are
     * not: this is a view the user set up a moment ago, and coming back to a vault showing a third
     * of its items is the kind of "where did everything go" that reads as data loss.
     */
    val folderStack: List<UUID> = emptyList(),
    /**
     * Every folder in the browsed subtree, including the folder itself — the set the item filter
     * tests against. Empty at the root.
     *
     * Computed once per refresh rather than per item: at 10,000 items the alternative is a tree walk
     * per row per keystroke.
     */
    val descendantIds: Set<UUID> = emptySet(),
    /**
     * How many items the header counts: the whole vault at the root, the browsed subtree inside a
     * folder — under whatever chips, tag or search are running.
     *
     * Not `items.size`, and that is the whole of F-31. At the root [items] holds only what is filed
     * nowhere (see [folderScopePredicate]), so the header read "45 items · 5 folders" over a vault
     * holding 58 — it also made the import wizard look like it had lied, because the wizard
     * correctly reported 16 new items while this number moved 41 → 45. Inside a folder the two
     * numbers are equal by construction, since the subtree *is* what the list shows there.
     *
     * Zero until the first projection lands, which the header never renders — `projectionVersion`
     * gates that; see `countLabel`.
     */
    val scopeItemCount: Int = 0,
    /**
     * Bumped once each time the narrowing changes, so the screen can put the viewport back at the top.
     *
     * A `LazyListState` keeps a first-visible *index*, and an index means nothing once the dataset
     * under it has been replaced. Remove a type token from a list scrolled halfway and the viewport
     * stays at whatever row now happens to hold that index: the folder rows, the FAVORITES heading
     * and ALL ITEMS were all above the fold with nothing on screen saying the list had reset
     * (F-10, `shots/046-filter-removed-notop.png`). The same applies to drilling into a folder,
     * where the index survives the whole subtree changing under it.
     *
     * A counter rather than a boolean, because two consecutive filter changes must be two requests
     * and a boolean cannot say so; monotonic, so the screen's effect keys on it and nothing has to
     * be acknowledged back into the ViewModel. Not raised for a refresh that changed nothing about
     * the narrowing — a save landing, or the vault re-reading, must not throw away the user's place.
     */
    val scrollToTopRequest: Long = 0L,
    /** Item counts per folder id, counted over each folder's whole subtree. */
    val folderItemCounts: Map<UUID, Int> = emptyMap(),
    /**
     * The same subtree counts with no type filter applied — what an operation on the whole folder
     * moves, rather than what the active chip admits.
     *
     * Two maps because the two numbers answer different questions and only ever agree when no chip
     * is on. [folderItemCounts] belongs next to a filter and must stay narrowed by it, or a folder
     * row reads "Receipts, 1 item" beside a chip that excluded that very item. `trashFolder` has no
     * such narrowing, so its confirmation has to count from here — see `trashFolderAction`.
     */
    val folderSubtreeItemCounts: Map<UUID, Int> = emptyMap(),
) {
    /** The folder being browsed, if it still exists. */
    val activeFolder: Folder? get() = folders.firstOrNull { it.id == folderStack.lastOrNull() }

    /** The crumb strip: the browsed folder's ancestors, root first. Empty at the root. */
    val breadcrumb: List<Folder> get() = folderStack.mapNotNull { id -> folders.firstOrNull { it.id == id } }

    /**
     * Whether the narrowing in force is a *flat, cross-folder view of items* — a search, Favorites,
     * a tag, or the invisible-only narrowing.
     *
     * One property, because two places had drifted apart and the gap was user-visible. The folder
     * rows already treated all four the same way (see [childFolders]) and hid themselves; the folder
     * *axis* ([folderScopePredicate]) only knew about search, so at the root Favorites still let
     * through unfiled items only. A vault whose one favourite lived in a folder therefore rendered
     * "1 item · filter: favorites" over "No matching items" — a false count and an unreachable
     * favourite (2A-F12, 2026-09-20). Spec §4.1: Favorites is a flat view across the whole vault.
     */
    val isFlatView: Boolean
        get() = searchQuery.isNotBlank() || showFavoritesOnly || tagFilter != null || showHiddenOnly

    /**
     * The folder rows shown above the items — the browsed folder's direct children, name-sorted.
     *
     * Hidden while a query is running: folder rows are navigation, not results, and a list that
     * answered a search with a folder the query did not match would be claiming a hit it did not
     * have.
     */
    /**
     * The root-level folders the strip offers, and the number the header counts.
     *
     * One property so the two cannot disagree. They did: with the "2FA" chip on, the header said
     * "4 folders" while the strip was about to stop offering them, and a count of things that are
     * not on screen is worse than no count.
     *
     * Empty under Favorites and under a search for the same reason [childFolders] is — see there.
     */
    val rootFolders: List<Folder>
        get() = if (isFlatView) {
            emptyList()
        } else {
            folders.filter { it.parentId == null }
                .filter { relevantFolderIds?.contains(it.id) ?: true }
        }

    // Lazy, not a getter: the screen reads this several times per composition, and each read
    // re-filtered and re-collated the whole folder list (S1-09, 2026-10-02). The state is
    // immutable, so one answer per instance is the same answer.
    val childFolders: List<Folder> by lazy {
        if (isFlatView) {
            // Hidden under search, and under Favorites for the same reason: both are cross-folder
            // views of *items*, and a folder row is navigation, not a result. Favorites is a flat,
            // curated set — listing every folder (most holding no favourite) beside "0 items" is
            // what read as wrong. With this empty and no items, the list falls to its empty state
            // rather than claiming folders are favourites. A tag and the invisible-only narrowing
            // are the same kind of view, and get the same treatment.
            emptyList()
        } else {
            // The type chips used to be exempt, on the reasoning that you should still be able to
            // navigate while filtering. Measured on a device 2026-08-20 with the "2FA" chip on and
            // no 2FA item in the vault: the header said "0 items · 4 folders" and offered all four,
            // one of them captioned "1 item" — a count of things the filter had just excluded. That
            // is the Favorites argument three lines up, on a second axis, and the exemption was
            // wrong. Navigation into a folder with nothing of this type in it is a dead end.
            //
            // Ancestors survive because [relevantFolderIds] includes them: a folder three levels
            // down is only reachable through the two above it.
            val parent = folderStack.lastOrNull()
            folders.filter { it.parentId == parent }
                .filter { relevantFolderIds?.contains(it.id) ?: true }
                .sortedByDisplayName { it.name }
        }
    }

    /**
     * The items that live in the browsed folder itself, above the section break.
     *
     * A folder list has always shown its whole subtree, which is the right default — a parent
     * reading "nothing here" while holding forty items one level down sends people looking for data
     * they still have. What it did not do was say which of the rows were actually *here*, so the
     * list mixed two different answers to "where is this" and the only cue was the trail on a row.
     *
     * Never split while a query or a filter is running: those results are already a cross-folder
     * answer, and grouping them by depth would be sorting the search by something the user did not
     * ask about.
     */
    val directItems: List<VaultItem>
        get() = if (!isSectioned) items else items.filter { it.folderId == folderStack.last() }

    /**
     * Whether the FAVORITES section heading is on screen, which is when "filter: favorites" in the
     * count line only repeats it. The heading needs a favourite to head and is suppressed while a
     * search runs, so both conditions are the screen's own (`VaultListScreen`'s item loop).
     */
    val favouritesHeadingShown: Boolean
        get() = showFavoritesOnly && searchQuery.isBlank() && directItems.any { it.favorite }

    /** The rest of the subtree's items — the "In subfolders" section at the foot of the list. */
    val nestedItems: List<VaultItem>
        get() = if (!isSectioned) emptyList() else items.filter { it.folderId != folderStack.last() }

    /**
     * The item rows in the order the list renders them, direct rows first.
     *
     * One property so the list and the reveal-scroll cannot disagree about where a row is; they are
     * two places that both have to know the order, and the second one is easy to forget.
     */
    val orderedItems: List<VaultItem> get() = directItems + nestedItems

    /** Whether the item rows are split into "here" and "further down". */
    private val isSectioned: Boolean
        get() = folderStack.isNotEmpty() &&
            searchQuery.isBlank() &&
            !showFavoritesOnly &&
            typeFilter == null &&
            tagFilter == null &&
            !showHiddenOnly

    /**
     * True when the browsed folder holds subfolders but no items of its own.
     *
     * Distinct from empty: the screen shows the folder rows and one quiet line, not the empty state,
     * which would otherwise claim there is nothing here while listing several things.
     *
     * Never at the root, and that is a fix rather than an omission — the line says "No items in
     * this folder", and at the root there is no folder to be in. A vault whose items are all filed
     * showed it over the folder strip on first unlock, which reads as the vault having lost them.
     * Found on a device, 2026-08-12.
     */
    val hasOnlySubfolders: Boolean
        get() = folderStack.isNotEmpty() && items.isEmpty() && childFolders.isNotEmpty()

    /**
     * Whether two folders visible in the current scope share a name, which is legal at rest — two
     * vaults merging by id produce it — and needs disambiguating in the rows when it happens.
     */
    fun isAmbiguous(folder: Folder): Boolean =
        childFolders.count { it.name.equals(folder.name, ignoreCase = true) } > 1

    val selecting: Boolean get() = selectedIds.isNotEmpty()

    /** True when every row currently on screen is selected — drives the select-all/none toggle. */
    val allVisibleSelected: Boolean
        get() = items.isNotEmpty() && items.all { it.id in selectedIds }

    /**
     * How many selected items the current filter/search/sort is not showing.
     *
     * The selection survives a filter change (see [VaultListViewModel.refresh]) — only pruned
     * against items that no longer exist in the vault at all, never against what a chip or a
     * search happens to be narrowed to. This is what lets the header stay honest about the gap
     * ("3 selected · 2 hidden by filter") instead of quietly implying the hidden ones were
     * deselected.
     */
    val hiddenSelectedCount: Int
        get() = if (selectedIds.isEmpty()) 0 else (selectedIds - items.mapTo(mutableSetOf()) { it.id }).size

    /**
     * The selection header's line about [hiddenSelectedCount], or null when nothing is hidden.
     * "Hidden by filter" only when no hidden row is off screen for any other reason — see
     * [selectionOutsideBrowse].
     */
    @get:PluralsRes
    val hiddenSelectionSubtitle: Int?
        get() = when {
            hiddenSelectedCount == 0 -> null
            selectionOutsideBrowse -> R.plurals.vaultlist_selection_not_in_list_subtitle
            else -> R.plurals.vaultlist_selection_hidden_subtitle
        }
}

/**
 * Whether any transient narrowing — favourites, a type other than the destination's own, a folder,
 * or a search — is currently applied. The guard that stops a reset re-running (and re-reading the
 * vault) when the list is already showing everything.
 *
 * @param initialFilter the destination's own type filter, which is *not* a transient narrowing: a
 *   per-type drawer destination is meant to stay on its type, so resetting to it is "already clear".
 */
/**
 * Prunes [VaultListUiState.selectedIds] against [existingIds] — every id still present *anywhere*
 * in the vault, unfiltered by the current type chip, search, or folder.
 *
 * Deliberately not pruned against [VaultListUiState.items], which is only the current filter's
 * result: a selected row that a filter change hides is not gone, it is just off screen, and the
 * selection has to keep naming it so a bulk action still reaches it and the header can still say
 * "N hidden by filter". An id drops out of the selection here only when the row it names no longer
 * exists at all — trashed individually, or the vault swapped out from under it.
 */
/**
 * Which items the folder axis lets through, given where the user is and whether the narrowing in
 * force is a flat, cross-folder view.
 *
 * Extracted from `refresh` so the three cases can be asserted without an unlocked vault behind
 * them — they are the whole of what "folders are containers" means to the list, and two of them are
 * counter-intuitive enough to be worth pinning:
 *
 *  - **Inside a folder**, the *subtree* matches. An item two levels down is not hidden by the
 *    folder the user is looking straight at.
 *  - **At the root, in a flat view** ([VaultListUiState.isFlatView] — a search, Favorites, a tag or
 *    the invisible-only narrowing), everything matches. These are cross-folder views of *items*:
 *    the folder rows hide themselves under all four for that reason, so the axis has to let filed
 *    items through or the view narrows to nothing it is willing to navigate to. Search behaves
 *    exactly as it did before folders were containers; narrowing it here would also zero the
 *    "matches outside" counter the escape offer is built on.
 *  - **At the root, browsing**, only unfiled items match. Filed items live in their folders, and
 *    listing them here as well would make the hierarchy decorative.
 */
internal fun folderScopePredicate(
    scope: UUID?,
    flatView: Boolean,
    descendants: Set<UUID>,
): (VaultItem) -> Boolean = { item ->
    when {
        scope != null -> item.folderId in descendants
        flatView -> true
        else -> item.folderId == null
    }
}

/**
 * What the count line under the title counts — see [VaultListUiState.scopeItemCount].
 *
 * The header's scope and the *list's* scope are deliberately not the same thing at the root. The
 * list shows unfiled items because folders are containers and listing their contents twice would
 * make the hierarchy decorative; the header is a statement about the vault, and one that stops at
 * the unfiled items is false by however much the user has filed (F-31: `45 items · 5 folders` over
 * 58 items, 2026-09-16). Inside a folder the two agree, because there the subtree is both.
 *
 * @param narrowedCount items passing every filter *except* the folder axis — the whole vault when
 *   nothing else is narrowing it.
 * @param scopedCount the same list with the folder axis applied: exactly what the rows show.
 */
internal fun headerItemCount(scope: UUID?, narrowedCount: Int, scopedCount: Int): Int =
    if (scope == null) narrowedCount else scopedCount

/**
 * Everything that decides which rows the list shows and in what order, as one comparable value.
 *
 * Two states with equal signatures are the same view of the vault, so the viewport may be left where
 * the user put it; two that differ are a new list, and an index carried across is meaningless.
 * Deliberately excludes the items themselves — a save landing or a background re-read is not a
 * change of view, and treating it as one would jump the list under a reader's thumb.
 */
internal fun VaultListUiState.narrowingSignature(): List<Any?> =
    listOf(typeFilter, showFavoritesOnly, tagFilter, showHiddenOnly, searchQuery, folderStack)

/**
 * What Back undoes next on the vault list, or null when there is nothing left and the key belongs to
 * the system.
 *
 * In the order the user built them, most recent first. Back used to know about the selection and the
 * folder only, so a Favorites token or a live search sat on screen while Back closed the app
 * outright — and relaunching dropped the filter anyway, so the state that stopped the user leaving
 * was not even the state they came back to (F-08: `mCurrentFocus` moved to another app with the
 * search still applied).
 *
 * [initialFilter] is the destination's own type — the drawer's per-type screens *are* their filter —
 * so it is not a narrowing and Back must not clear it, or Back on a "Logins" screen would strand the
 * user there showing everything.
 */
internal fun VaultListUiState.resolveListBack(initialFilter: ItemTypeFilter?): ListBackAction? = when {
    selecting -> ListBackAction.ClearSelection
    searchQuery.isNotEmpty() -> ListBackAction.ClearSearch
    typeFilter != initialFilter || showFavoritesOnly || tagFilter != null || showHiddenOnly ->
        ListBackAction.ClearFilters
    folderStack.isNotEmpty() -> ListBackAction.LeaveFolder
    else -> null
}

/** The stages [resolveListBack] walks. One name per thing Back can undo here. */
internal enum class ListBackAction { ClearSelection, ClearSearch, ClearFilters, LeaveFolder }

/**
 * Moves the browse path to [path], clearing the multi-select if the path actually changed.
 *
 * One function rather than the rule repeated at each call site, because it *was* repeated and the
 * fifth site did not get it: `revealItem` popped to the root with a live selection still naming rows
 * inside the folder it had left. The selection survives both a tab change and an item round-trip, so
 * the path is real — select rows inside a folder, save a login from the generator, land back at the
 * root, and the bulk bar offers a delete that reaches items nothing on screen names. That is exactly
 * what clearing-on-navigate exists to prevent (D8), and pruning instead of clearing was rejected for
 * the same reason.
 *
 * Only a *move* clears. A refresh recomputing the same path must not drop a selection the user is
 * still building.
 */
internal fun VaultListUiState.withBrowsePath(path: List<UUID>): VaultListUiState =
    if (path == folderStack) this else copy(folderStack = path, selectedIds = emptySet())

/**
 * The browse path a reveal of an item filed under [itemFolderId] leaves the list on.
 *
 * Kept when the item is already inside the subtree being browsed — a save-into-the-open-folder round
 * trip has to come back to the same folder. Otherwise the list goes to the item's own folder, and
 * only an unfiled item (or a folder with no live path to it) goes to the root.
 *
 * "Otherwise the root" was the rule until 2026-10-02, and it is right for an unfiled item only: the
 * root lists nothing that is filed ([folderScopePredicate]). A login saved into a folder from the
 * generator, or from a list that was already at the root, landed on a list that did not contain it,
 * with a reveal waiting for a row that was never going to arrive (S1-04).
 *
 * @param scopeDescendants the browsed folder and everything under it; read only when [current] is
 *   not the root.
 * @param itemFolderPath the item's folder and its ancestors, root first — null when the chain is
 *   unknown or broken. A trashed link anywhere in it is no way in either: the list would truncate the
 *   path at that crumb and leave the reveal pointing at a folder nobody can open.
 */
internal fun revealBrowsePath(
    current: List<UUID>,
    scopeDescendants: Set<UUID>,
    itemFolderId: UUID?,
    itemFolderPath: List<Folder>?,
): List<UUID> = when {
    itemFolderId == null -> emptyList()
    current.isNotEmpty() && itemFolderId in scopeDescendants -> current
    itemFolderPath == null || itemFolderPath.any { it.deletedAt != null } -> emptyList()
    else -> itemFolderPath.map { it.id }
}

/**
 * Ends a reveal whose row a scan has now looked for and not found.
 *
 * [revealedAtScanStart] is what the scan was *started* with, not what is current: a reveal raised
 * after the scan began has not been looked for by it, and dropping that one here would lose a
 * highlight that is about to resolve. Left standing, an unresolved id is not harmless — the screen's
 * scroll-to-top effect stands down while one is pending, so a single reveal that never lands switches
 * the reset-to-top off for every filter and folder change after it (S1-04).
 */
internal fun VaultListUiState.withUnresolvedRevealDropped(revealedAtScanStart: UUID?): VaultListUiState =
    if (revealedAtScanStart != null &&
        revealedItemId == revealedAtScanStart &&
        items.none { it.id == revealedAtScanStart }
    ) {
        copy(revealedItemId = null)
    } else {
        this
    }

internal fun VaultListUiState.withSelectionPrunedTo(existingIds: Set<UUID>): VaultListUiState =
    copy(selectedIds = selectedIds intersect existingIds)

/**
 * Drops any selected id that concealment, not a filter, is the reason it is off screen.
 *
 * [concealedItemIds] is populated whether or not a reveal session is active (see
 * `VaultVisibility.project`), so it cannot by itself say whether a concealed row is currently on
 * screen — during a reveal session it is, and unconditionally subtracting [concealedItemIds] here
 * would drop a legitimate in-session selection the moment the user reveals a hidden row. Comparing
 * against [VaultListUiState.items] (already the freshly-scanned projection by the time this runs)
 * answers that: `concealedItemIds - items` is empty while revealed — those rows are still in
 * `items` — and becomes exactly the concealed set once the session ends and `items` drops them.
 *
 * Unlike [withSelectionPrunedTo], which lets a row a mere filter is hiding stay selected on
 * purpose, a concealed row must not: [VaultListViewModel.bulkEdit] reads `selectedIds` straight off
 * state and would otherwise reach a record the user can no longer see to confirm, and
 * [VaultListUiState.hiddenSelectedCount] would announce its existence in the header.
 */
internal fun VaultListUiState.withConcealedPruned(concealedItemIds: Set<UUID>): VaultListUiState {
    val onScreen = items.mapTo(mutableSetOf()) { it.id }
    return copy(selectedIds = selectedIds - (concealedItemIds - onScreen))
}

/**
 * What the select-all/none header button does: toggles every row the *current* filter shows,
 * without touching any selected row the filter is hiding.
 *
 * Union/subtract rather than replace — replacing the whole selection with just the visible set
 * would silently drop any hidden-but-selected rows the moment the user tapped this under a
 * narrower filter than the one they picked those rows under.
 */
internal fun VaultListUiState.withVisibleSelectionToggled(): VaultListUiState {
    val visibleIds = items.mapTo(mutableSetOf()) { it.id }
    val next = if (allVisibleSelected) selectedIds - visibleIds else selectedIds + visibleIds
    return copy(selectedIds = next, error = null)
}

internal fun VaultListUiState.hasTransientNarrowing(initialFilter: ItemTypeFilter?): Boolean =
    typeFilter != initialFilter ||
        showFavoritesOnly ||
        tagFilter != null ||
        showHiddenOnly ||
        searchQuery.isNotEmpty() ||
        folderStack.isNotEmpty()

/**
 * Drops every transient narrowing at once, back to the destination's own [initialFilter]. Favourites
 * and folder and search all go together — a "show me everything" that left one filter standing is
 * the no-op that made the drawer's "All items" look broken.
 */
internal fun VaultListUiState.withTransientFiltersCleared(initialFilter: ItemTypeFilter?): VaultListUiState =
    copy(
        typeFilter = initialFilter,
        showFavoritesOnly = false,
        tagFilter = null,
        showHiddenOnly = false,
        searchQuery = "",
        folderStack = emptyList(),
        error = null,
    )

/**
 * Whether the "All" chip is showing as the current state of its row.
 *
 * Its row carries two axes — Favourites and the item type — and "All" is the off position for both.
 * It used to track only the type, so with Favourites on it rendered as selected while the list was
 * still narrowed, and tapping it did nothing at all: the chip labelled "All" was lit, the list said
 * "No matching items", and there was no way to tell the two states apart. This still reflects only
 * those two axes, not the folder path — a folder browsed with no type filter running already reads
 * as "All" here, correctly, since the chip's highlight is about the row it sits in. What tapping it
 * *does* is a different question: see [VaultListViewModel.showEveryType] for why the tap resets the
 * folder path even on a click this function alone would call a no-op.
 */
internal fun VaultListUiState.showsEveryType(): Boolean =
    typeFilter == null && !showFavoritesOnly && tagFilter == null && !showHiddenOnly

/**
 * Whether the empty list should offer to widen the search instead of offering to clear it.
 *
 * All three conditions matter. Without a query there is nothing to widen; with rows on screen there
 * is no dead end to escape; and with nothing matching elsewhere the offer would lead to a second
 * empty screen, which is worse than the honest "nothing matches" it replaced.
 */
/**
 * How many search hits the chips are hiding — the count that decides whether the empty screen offers
 * to widen the search or only to clear it.
 *
 * [matchingCount] is the search result before any chip touched it and [scopedCount] is what survived
 * them, so the difference is exactly "matches you cannot see from here", whichever axis narrowed:
 * folder scope, type chip, or favourites.
 *
 * It used to be gated on `scope != null` as well, reasoned as "at the root the query already sees
 * everything". That reason is wrong, and the finding is about the reason: at the root a *type* chip
 * narrows just as a folder does, so searching for a note's title with the 2FA chip up produced
 * "nothing matches" and offered only to clear — while the item sat one chip away.
 * `searchEverywhere()` already clears all three axes, so the action behind the offer was correct the
 * whole time; only the count gating it was not. The button's own copy already handles both cases,
 * naming the folder when there is one and saying "Search all items" when there is not.
 */
internal fun matchesOutsideNarrowing(searching: Boolean, matchingCount: Int, scopedCount: Int): Int =
    if (!searching) 0 else matchingCount - scopedCount

internal fun VaultListUiState.offersWiderSearch(): Boolean =
    searchQuery.isNotBlank() && items.isEmpty() && matchesOutsideFilter > 0

/**
 * What tapping "All" does: the off position for both axes in its row, plus a reset of the folder
 * path back to the root — see [VaultListViewModel.showEveryType] for why the folder axis is folded
 * in here even though [showsEveryType] (the chip's own highlight) does not track it. Routed through
 * [withBrowsePath] rather than setting `folderStack` directly: that is the one place a browse-path
 * change clears the selection too, and it is the same primitive `closeToRoot` and the breadcrumb's
 * root crumb already use — a second way to empty the stack is exactly the parallel state a folder
 * reset must not invent.
 */
internal fun VaultListUiState.withEveryTypeShown(): VaultListUiState =
    copy(typeFilter = null, showFavoritesOnly = false, tagFilter = null, showHiddenOnly = false, error = null)
        .withBrowsePath(emptyList())

@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val lockManager: VaultLockManager,
    private val entitlement: Entitlement,
    private val visibilityCoordinator: ForegroundVisibilityCoordinator,
) : ViewModel() {
    // isPro seeded rather than left to the collector below, which runs on viewModelScope: until its
    // first emission a Pro user reads as free, and here that means the drawer's Pro item types wear
    // a padlock for a frame.
    private val _state = MutableStateFlow(
        VaultListUiState(isPro = entitlement.isPro.value, canPurchase = entitlement.canPurchase,
            proPrice = entitlement.displayPrice.value),
    )
    val state: StateFlow<VaultListUiState> = _state.asStateFlow()

    /** See [applyInitialTypeFilter]. */
    private var initialTypeFilterApplied = false

    /** The destination's own filter, and therefore what [onEnteredList] resets back to. */
    private var initialFilter: ItemTypeFilter? = null

    /**
     * [initialFilter], for the screen's Back handler.
     *
     * Read-only and exposed for one caller: `resolveListBack` has to tell a filter the user set from
     * the one the destination was opened with, and the screen cannot see the difference — the nav
     * argument is applied once, here, and the chips own it afterwards (see [applyInitialTypeFilter]).
     */
    internal val initialTypeFilter: ItemTypeFilter? get() = initialFilter

    /**
     * The narrowing the last published scan was under, so the next one can tell whether it changed.
     *
     * Held here rather than in the state: it is a comparison against the *previous* publish, and a
     * state field holding its own previous value is how the two get out of step. See
     * [VaultListUiState.scrollToTopRequest].
     */
    private var lastNarrowing: List<Any?>? = null

    /** See [noteOpeningItem]. */
    private var openingItem = false

    /** See [noteLeavingList]. */
    private var recreating = false

    /**
     * Called as the list leaves composition, saying whether the Activity is being torn down for a
     * configuration change rather than because the user navigated away.
     *
     * This ViewModel outlives the recreation, so it — not the composable, which cannot tell the two
     * apart — is where the answer has to be kept until [onEnteredList] asks for it.
     */
    fun noteLeavingList(recreating: Boolean) {
        this.recreating = recreating
    }

    init {
        // The list is a foreground consumer of the projection for as long as this ViewModel lives, so
        // it holds one reference on the shared expiry coordinator: the single nearest-lease timer runs
        // while the list exists and is released in onCleared. VM-scoped rather than STARTED-scoped is a
        // deliberate simplification — a retained-but-backgrounded list only causes the timer to fire a
        // cheap projection recompute it would otherwise skip, never a save or a leak.
        visibilityCoordinator.acquire()
        // Driven by the lock state rather than called eagerly here. Compose Navigation builds the
        // HOME destination — and therefore this ViewModel — while the unlock is still completing, so
        // an eager refresh() ran against a locked repository, threw, and left "Vault action could not
        // be completed" pinned above the list. On an empty vault nothing else ever triggers another
        // refresh, so the banner was permanent. Verified from the stack trace on a device:
        // VaultListViewModel.<init> -> refresh -> requireVault -> IllegalStateException.
        //
        // lockState is a StateFlow, so this also covers the already-unlocked case with no separate
        // eager call, and it makes the list heal itself across any later lock/unlock cycle.
        viewModelScope.launch {
            lockManager.lockState.collect { lockState ->
                if (lockState is LockState.Unlocked) {
                    // Whatever the last lock took down and wanted said — see [LkUnlockNotices]. For
                    // the longer unlock duration, since it lands with the unlock itself.
                    LkUnlockNotices.postPending()
                    _state.update { it.copy(openedWithRecoveryKit = lockState.openedWithRecoveryKit) }
                    loadFolders()
                    refresh()
                } else {
                    // A lock supersedes any in-flight reveal attempt; the lock manager has already
                    // concealed the session, so this only stops a late KDF success from revealing.
                    lockManager.invalidateHiddenRevealAttempt(lastRevealGeneration)
                    // Don't keep item titles in UI state once the vault is shut. Folder names and
                    // the selection go with them — a selection surviving a lock would mean the next
                    // person to unlock inherits a bulk-delete already aimed and loaded.
                    _state.update {
                        it.copy(
                            items = emptyList(),
                            // A count of the vault is as much a fact about it as the titles are, and
                            // it outlives them by a frame otherwise.
                            scopeItemCount = 0,
                            folders = emptyList(),
                            openedWithRecoveryKit = false,
                            selectedIds = emptySet(),
                            revealSessionActive = false,
                            showHiddenOnly = false,
                            // A browse path is a view set up a moment ago, not a preference:
                            // reopening the vault still inside a folder reads as a third-empty
                            // vault. loadFolders keeps its dead-folder reconciliation only as the
                            // within-unlock delete safety net.
                            folderStack = emptyList(),
                            error = null,
                            // Cleared with them. `items` is back to the default `emptyList()`, and
                            // that is once again "not established" rather than "nothing is there" —
                            // the next unlock's scan is what re-establishes it.
                            hasScanned = false,
                        )
                    }
                    // The next unlock's first scan is not a narrowing *change*, whatever this lock
                    // reset behind it. See [VaultListUiState.scrollToTopRequest].
                    lastNarrowing = null
                }
            }
        }
        viewModelScope.launch {
            // The one shared presentation flow, so every re-render reason routes through the same
            // projection: a save, a reveal/conceal, and an exact lease-expiry boundary all emit here.
            // `loadFolders` alongside `refresh`, not `refresh` alone — a folder rename/duplicate/delete
            // from the chip strip's own menu (a second FolderViewModel `refresh` never touches
            // `state.folders`) has to reach the strip too. Guarded on Unlocked so the initial value
            // and the Locked emission do not race the unlock and pin the read-error banner (the
            // lockState collector owns the clear-on-lock).
            //
            // The reveal flag is combined in rather than read off the side. With nothing concealed a
            // revealed projection equals a concealed one, the StateFlow drops the equal value, and
            // ending the session reached nobody: after the only invisible entry was made visible,
            // "Stop showing" and the two-minute expiry both left the banner up (device pass
            // 2026-10-04).
            combine(repository.presentationState, lockManager.revealSession.revealed, ::Pair)
                .collectLatest { (presentation, revealed) ->
                    // The invisible-only narrowing goes with the session it belongs to. "Stop showing"
                    // and a lease expiry both land here; without this the filter would survive them as
                    // an empty list under a token the sheet can no longer offer to remove.
                    _state.update {
                        it.copy(
                            revealSessionActive = revealed,
                            showHiddenOnly = it.showHiddenOnly && revealed,
                            revealSecondsRemaining = if (revealed) it.revealSecondsRemaining else null,
                        )
                    }
                    if (presentation is VaultPresentationState.Unlocked) {
                        loadFolders(presentation.snapshot)
                        refresh()
                    }
                }
        }
        viewModelScope.launch {
            // The reveal session's countdown. `collectLatest`, so a fresh reveal cancels the previous
            // countdown rather than leaving two of them writing to the same field — a second phrase
            // restarts the window, and the older loop would otherwise reach zero first and publish a
            // number the session has already moved past.
            //
            // The clock is only read here. The *expiry* itself belongs to VaultLockManager, which owns
            // the session and conceals it whether or not this screen is composed; if this loop were
            // the thing that ended the session, backgrounding the list would leave concealment off.
            // This loop renders the same deadline and stops when the manager nulls it.
            lockManager.revealSession.expiresAtElapsedMs.collectLatest { deadline ->
                if (deadline == null) {
                    _state.update { it.copy(revealSecondsRemaining = null) }
                    return@collectLatest
                }
                while (true) {
                    val left = revealSecondsLeft(deadline, SystemClock.elapsedRealtime())
                    _state.update { it.copy(revealSecondsRemaining = left) }
                    if (left <= 0) break
                    delay(1_000L)
                }
            }
        }
        viewModelScope.launch {
            // The quota flag rides along, because it is a function of the tier as much as of the
            // count: completing a purchase must clear the cap immediately, not at the next refresh.
            // Play answers asynchronously, so a screen already open when the price lands has to
            // pick it up — otherwise the first upsell of a session permanently reads the no-price
            // wording even though the price arrived a second later.
            launch {
                entitlement.displayPrice.collect { p -> _state.update { it.copy(proPrice = p) } }
            }
            entitlement.isPro.collect { pro ->
                val used = quotaUsed()
                val (cards, identities) = typeQuotaUsed()
                _state.update {
                    it.copy(
                        isPro = pro,
                        attachmentQuotaUsed = used,
                        attachmentQuotaFull = AttachmentQuota.exceedsFreeLimit(used, pro),
                        cardQuotaUsed = cards,
                        cardQuotaFull = ProTypeQuota.exceedsFreeLimit(cards, pro),
                        identityQuotaUsed = identities,
                        identityQuotaFull = ProTypeQuota.exceedsFreeLimit(identities, pro),
                    )
                }
            }
        }
    }

    fun unlockPro(activity: Activity) {
        // Play's sheet is another process: without declaring the excursion, the auto-lock grace
        // timer runs during checkout and throws the user to the unlock screen mid-purchase. Same
        // reason the import wizard declares its file picker.
        lockManager.beginExternalPicker()
        viewModelScope.launch {
            try {
                reportPurchase(entitlement.purchase(activity))
            } finally {
                lockManager.endExternalPicker()
            }
        }
    }

    /**
     * Says what a finished purchase did, for the outcomes that would otherwise say nothing at all.
     *
     * The result used to be discarded here and at Settings' and the item editor's copies of this
     * function, so the paths that return before Play's sheet ever appears — no billing connection,
     * no product details, a launch Play refused — left the price button looking inert. Being offline
     * is the ordinary state of this app's users, so that was the ordinary experience of trying to pay.
     */
    private fun reportPurchase(outcome: PurchaseOutcome) = when (outcome) {
        // A completed purchase flips `isPro`, which announces itself; a cancel is the user's own
        // doing; a refused second tap has the flow that refused it still on screen.
        PurchaseOutcome.Purchased,
        PurchaseOutcome.Cancelled,
        PurchaseOutcome.AlreadyInFlight,
        -> Unit
        // Not an error, and deliberately not on the error channel: nothing failed and there is
        // nothing to retry. LkNotices outlives this screen, which matters because the same purchase
        // is raised from a sheet that closes on the tap.
        PurchaseOutcome.Pending ->
            LkNotices.post(com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_pending)
        // A notice, not `error`: the sheet that raised this closed on the tap, and unlike Failed
        // there is no retry to offer here — the action it names is in Settings.
        PurchaseOutcome.AlreadyOwned ->
            LkNotices.post(com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_already_owned)
        // `loadFailed` stays false: the list loaded fine, and the retry this needs is another tap
        // on the price button, not a reload.
        PurchaseOutcome.Failed -> _state.update {
            it.copy(error = com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_failed)
        }
    }

    /** The last reveal-attempt generation this VM submitted, so a new query can invalidate it. */
    private var lastRevealGeneration: Long = 0

    fun onSearchChanged(query: String) {
        _state.update { it.copy(searchQuery = query, error = null) }
        // A new query supersedes any in-flight reveal attempt. The prior blocking KDF still runs to
        // completion and accounts a wrong guess, but invalidating its generation stops a late success
        // revealing for a query the user has already moved past.
        lockManager.invalidateHiddenRevealAttempt(lastRevealGeneration)
        // Debounced, unlike every other refresh trigger. A keystroke is not a decision — it is one
        // frame of a word being typed — and each one used to run a full scan of the vault, building
        // the searchable text for every item, on the main thread. Ten characters meant ten complete
        // passes over the vault to show the result of the tenth. The onboarding strength meter
        // already debounces at this interval for the same reason.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            // [scanAndPublish], not [refresh]: refresh opens by cancelling `searchJob`, which is
            // this coroutine, so the reveal probe below never ran.
            scanAndPublish()
            maybeAttemptReveal(query)
        }
    }

    /**
     * When a search returns zero *visible* results globally (not merely an empty current scope), the
     * query might be the reveal phrase. Submit it to the lock-manager gate; on success the hidden
     * entries join the session and the box clears, and on failure nothing is shown, spoken or
     * buzzed — the search simply stays empty, so the box is not an oracle for whether a phrase exists.
     */
    private suspend fun maybeAttemptReveal(query: String) {
        // The rollback policy's other half. Disabling this strands nobody: the authenticated Settings
        // screen is the documented recovery route and is never gated, and the search box stays a
        // plain search box rather than becoming an oracle for a phrase it will no longer accept.
        if (!MakeInvisibleAvailability.SEARCH_PHRASE_REVEAL_ENABLED) return
        if (query.isBlank() || lockManager.revealSession.revealed.value) return
        // Off the main dispatcher: this is a whole-vault projection plus a whole-vault search, and
        // it runs on the trailing edge of the search debounce — while the user is still typing, so
        // on the main thread it stutters the keystroke after it. The KDF attempt below is the lock
        // manager's to place; only the probe moves here.
        val visibleMatch = withContext(Dispatchers.Default) {
            val snapshot = runCatching { repository.visibilitySnapshot() }.getOrNull()
                ?: return@withContext null
            val visibleIds = snapshot.liveItems.mapTo(HashSet()) { it.id }
            repository.search(query).any { it.id in visibleIds }
        } ?: return
        if (visibleMatch) return // a visible item matches; not a phrase

        val generation = lockManager.nextRevealAttemptGeneration()
        lastRevealGeneration = generation
        // Every query that finds nothing reaches here, so this is the one Argon2id call in the app
        // that an ordinary search can trigger — see [rejectingOnOutOfMemory] for what a heap with no
        // room for it now does. It is deliberately NOT skipped for a query that merely looks unlike a
        // phrase, a repeat of the last one, or a vault with no phrase at all: a miss, a hit and "no
        // phrase configured" must cost the same, or the search box tells whoever is typing in it
        // whether something is hidden. The same indistinguishability is why an ordinary miss counts
        // towards the throttle; that accounting is `VaultLockManager`'s.
        val outcome = rejectingOnOutOfMemory {
            lockManager.attemptHiddenReveal(query.toCharArray(), generation)
        }
        if (outcome == RevealAttemptOutcome.Revealed) {
            _state.update { it.copy(searchQuery = "", selectedIds = emptySet(), revealSessionActive = true) }
            loadFolders()
            refresh()
        }
    }

    fun setTypeFilter(filter: ItemTypeFilter?) {
        if (_state.value.typeFilter == filter) return
        _state.update { it.copy(typeFilter = filter, error = null) }
        refresh()
    }

    /** Narrows the list to items carrying [tag] — exactly, as authored — or clears it with null. */
    fun setTagFilter(tag: String?) {
        if (_state.value.tagFilter == tag) return
        _state.update { it.copy(tagFilter = tag, error = null) }
        refresh()
    }

    /**
     * Narrows the list to the entries a reveal session is showing, or widens it again.
     *
     * A no-op outside a reveal session, deliberately and not only because the control is absent
     * then: with nothing concealed in the projection the filter could only ever produce an empty
     * list, and an empty list with no visible cause is the state the token strip exists to prevent.
     */
    fun toggleHiddenOnly() {
        if (!_state.value.revealSessionActive) return
        _state.update { it.copy(showHiddenOnly = !it.showHiddenOnly, error = null) }
        refresh()
    }

    /**
     * Drills into [folderId], pushing it onto the browse path.
     *
     * The selection is **cleared**, not pruned. Pruning would have kept rows the user picked in the
     * folder they came from and then acted on them from a screen where they are not visible — a
     * bulk delete reaching items nothing on screen names. Clearing is the honest one, and it is what
     * every file browser does.
     */
    fun openFolder(folderId: UUID) {
        if (_state.value.folderStack.lastOrNull() == folderId) return
        _state.update { it.withBrowsePath(it.folderStack + folderId).copy(error = null) }
        refresh()
    }

    /** Jumps straight to [path], root first — the drawer's folder rows hand one of these over. */
    fun openFolderPath(path: List<UUID>) {
        if (_state.value.folderStack == path) return
        _state.update { it.withBrowsePath(path).copy(error = null) }
        refresh()
    }

    /** Back out one level. Returns false at the root, so the screen can let the key through. */
    fun navigateBack(): Boolean {
        val current = _state.value.folderStack
        if (current.isEmpty()) return false
        _state.update { it.withBrowsePath(current.dropLast(1)).copy(error = null) }
        refresh()
        return true
    }

    /** Back to the root in one step — what a breadcrumb's leftmost crumb and the escape offer do. */
    fun closeToRoot() {
        if (_state.value.folderStack.isEmpty()) return
        _state.update { it.withBrowsePath(emptyList()).copy(error = null) }
        refresh()
    }

    /**
     * Seeds the filter from the destination's navigation argument, once.
     *
     * The screen used to push the nav argument in from a `LaunchedEffect`, which restarts every
     * time the destination is recomposed — including on every pop back from an item. So the chip
     * the user had tapped was silently reset on return while the search text, held right here in
     * the same ViewModel, survived. This ViewModel outlives those pops; the argument is an initial
     * value, not a continuous input, and treating it as one is what made the chip look broken.
     */
    fun applyInitialTypeFilter(filter: ItemTypeFilter?) {
        if (initialTypeFilterApplied) return
        initialTypeFilterApplied = true
        initialFilter = filter
        setTypeFilter(filter)
    }

    /**
     * Called when the user opens an item, so returning from it does *not* reset the list.
     *
     * See [onEnteredList] for why the two cases have to be told apart.
     */
    fun noteOpeningItem() {
        openingItem = true
    }

    /**
     * Called whenever the list is entered, and decides whether the narrowing survives.
     *
     * Two ways to arrive here and they want opposite things. Coming back from an item you were
     * working through a filtered list, and wiping the filter under you is infuriating. Coming back
     * from another tab — or a cold start — you have left and returned, and a list still narrowed by
     * a chip you tapped minutes ago just looks empty: the reported case was Favourites plus Notes
     * surviving a trip to the Generator and reading as "no items", with nothing on screen saying
     * why. So: keep it across an item, drop it across a tab.
     *
     * Resets to [initialFilter] rather than to null, because for the per-type destinations reached
     * from the drawer the filter *is* the destination — clearing that would strand the user on a
     * "Logins" screen showing everything.
     *
     * A third way to arrive, and it wants what the first one does: the screen was rebuilt for a
     * configuration change. The user has not left anything — they turned the phone — so the chip and
     * the search text they were looking at have to still be there when it comes back. See
     * [noteLeavingList].
     */
    fun onEnteredList() {
        if (recreating) {
            recreating = false
            return
        }
        if (openingItem) {
            openingItem = false
            return
        }
        resetTransientFilters()
    }

    /**
     * Drops every transient narrowing at once, back to the destination's own [initialFilter].
     *
     * The drawer's "All items" is the one intuitive "show me everything" control, and it did nothing
     * when the list was already on plain HOME with a Favourites chip set — the route did not change,
     * so nothing cleared the filters. This is what it calls now, via [HomeFilter]; it is also the
     * reset [onEnteredList] runs when the list is re-entered from another tab.
     */
    fun clearTransientFilters() {
        resetTransientFilters()
    }

    /**
     * One Back press on the vault list. Returns false when there was nothing to undo, so the caller
     * can let the key through to the system.
     *
     * The staging lives in [resolveListBack] — pure, and therefore pinned by `ListBackHandlingTest`
     * without a repository behind it — and this is only the doing. One entry point rather than a
     * `BackHandler` per stage, because the stages are ordered and four independent `enabled` flags
     * are four chances for two of them to be true at once.
     */
    fun onBack(): Boolean {
        when (_state.value.resolveListBack(initialFilter)) {
            ListBackAction.ClearSelection -> clearSelection()
            ListBackAction.ClearSearch -> onSearchChanged("")
            ListBackAction.ClearFilters -> clearFilterTokens()
            ListBackAction.LeaveFolder -> navigateBack()
            null -> return false
        }
        return true
    }

    /**
     * Drops the filter tokens — type, favourites, tag, invisible-only — and nothing else.
     *
     * Narrower than [clearTransientFilters] on purpose: Back walks the narrowings off one layer at a
     * time, and a Back that also threw away the search text and the folder the user had walked into
     * would be one press doing three undos.
     */
    fun clearFilterTokens() {
        val current = _state.value
        if (current.typeFilter == initialFilter &&
            !current.showFavoritesOnly &&
            current.tagFilter == null &&
            !current.showHiddenOnly
        ) {
            return
        }
        _state.update {
            it.copy(
                typeFilter = initialFilter,
                showFavoritesOnly = false,
                tagFilter = null,
                showHiddenOnly = false,
                error = null,
            )
        }
        refresh()
    }

    private fun resetTransientFilters() {
        val current = _state.value
        if (!current.hasTransientNarrowing(initialFilter)) return
        _state.update { it.withTransientFiltersCleared(initialFilter) }
        refresh()
    }

    /**
     * The "All" chip. See [showsEveryType] for the highlight; this is what the tap does.
     *
     * Also resets folder browsing to the root — "All" is supposed to mean everything, from the top,
     * and leaving the browsed folder standing while only the type/Favourites axes cleared was the
     * bug this closes: filter to Logins inside a folder, tap All, and the list read as "everything"
     * while one folder's subtree was still quietly excluded. The no-op guard checks the folder path
     * too, not just [showsEveryType]: a folder opened with no type filter running already shows as
     * "All" on the chip, and skipping the reset in exactly that state is the one case this exists for.
     */
    fun showEveryType() {
        val current = _state.value
        if (current.showsEveryType() && current.folderStack.isEmpty()) return
        _state.update { it.withEveryTypeShown() }
        refresh()
    }

    fun toggleFavorites() {
        _state.update { it.copy(showFavoritesOnly = !it.showFavoritesOnly, error = null) }
        refresh()
    }

    fun toggleFavorite(id: UUID) {
        val nowFavorite = repository.getAllItems().find { it.id == id }?.let { !it.favorite }
        mutate(
            // The row's only favourite signal is a 5dp brass dot, and the list silently re-sorts
            // the toggled item to the top — both invisible to a screen reader. Announce the
            // outcome so the toggle is not silent. (The old KDoc claimed "the favourite star
            // flipping" reported it; that star no longer exists in the row.)
            after = {
                nowFavorite?.let {
                    LkNotices.post(
                        if (it) R.string.vaultlist_notice_favourited
                        else R.string.vaultlist_notice_unfavourited,
                    )
                }
            },
        ) {
            // A toggle is the read-modify-write most obviously broken by an unguarded read: the
            // flip has to be computed from the *stored* flag, or two writers racing both read the
            // same value and one of them lands a no-op. `nowFavorite` above is for the
            // announcement only; the item may have gone since, hence the soft return.
            if (nowFavorite == null) return@mutate
            repository.updateWith(id) { it.copy(favorite = !it.favorite) }
        }
    }

    /**
     * Single-item trash deliberately asks nothing first — see the dialog in `VaultListScreen`,
     * which exists for the *count* rather than the act. The offer to undo is what pays for that:
     * the row goes immediately, and the way back is one tap on the confirmation rather than a
     * prompt on every discard.
     *
     * The three notice strings are resolved by the caller via `stringResource` and passed in:
     * `LkNotices.post` (core/ui) has no `@StringRes` overload, so this ViewModel cannot resolve
     * them itself without holding a Context.
     */
    fun moveToTrash(id: UUID, movedNotice: String, undoLabel: String, restoredNotice: String) {
        mutate(
            after = { LkNotices.post(movedNotice, actionLabel = undoLabel) { undoTrash(id, restoredNotice) } },
        ) { repository.moveToTrash(id) }
    }

    private fun undoTrash(id: UUID, restoredNotice: String) {
        mutate(scope = undoScope, after = { LkNotices.post(restoredNotice) }) { repository.restoreFromTrash(id) }
    }


    // --- Multi-select -------------------------------------------------------------------------
    //
    // Free, and it has to be: importing, folders and tags all moved to the free tier, which means a
    // free vault can now arrive with several hundred logins in it and no way to file them except one
    // at a time. Gating the bulk actions would put the wall back one step further along — charging
    // at the door of a room the user has finally been let into.

    fun toggleSelected(id: UUID) {
        _state.update { current ->
            val next = if (id in current.selectedIds) current.selectedIds - id else current.selectedIds + id
            current.copy(selectedIds = next, error = null)
        }
    }

    fun selectAllVisible() {
        // Visible, not "all in the vault": the search box and the type chips are the user's stated
        // scope, and a select-all that quietly reached past them would be the single easiest way to
        // trash the wrong two hundred items. See [withVisibleSelectionToggled] for why this unions
        // rather than replaces.
        _state.update { it.withVisibleSelectionToggled() }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    /**
     * Files every selected item into [folderId], or out of any folder when it is null.
     *
     * @param noChangeNotice see [bulkEdit] — resolved by the caller via `stringResource`.
     */
    fun bulkMoveToFolder(folderId: UUID?, noChangeNotice: String, undoLabel: String, undoneNotice: String) {
        val now = Instant.now()
        // The folder can be deleted from the drawer while the sheet is open. Filing into an id that
        // is gone would put every selected item where no list can show it — silently, and reported
        // as a success. The no-change path already exists and says the right thing.
        if (folderId != null && _state.value.folders.none { it.id == folderId }) {
            LkNotices.postRefusal(noChangeNotice)
            clearSelection()
            return
        }
        // Read before the write the undo has to reverse. A bulk move was the one re-filing on this
        // screen with no way back — the drag offers Undo, bulk trash offers Undo — and the folders
        // the items came from were gone with it (QA 2026-10-03).
        val previous = draggedItems(_state.value.selectedIds).associate { it.id to it.folderId }
        bulkEdit(
            noChangeNotice = noChangeNotice,
            confirmation = { n ->
                LkNotices.Message.Plural(
                    if (folderId == null) R.plurals.vaultlist_bulk_removed_from_folder
                    else R.plurals.vaultlist_bulk_moved,
                    n,
                )
            },
            undo = { ids ->
                LkNotices.Message.Text(undoLabel) to { undoMove(previous.filterKeys { it in ids }, undoneNotice) }
            },
            transform = bulkMoveTransform(folderId, now),
        )
    }

    /**
     * Files every item in [previous] back where it was — the bulk move's undo, with the drag's rule
     * for a folder trashed inside the undo window: the root instead (see [withDragMoveUndone]).
     */
    private fun undoMove(previous: Map<UUID, UUID?>, undoneNotice: String) {
        // [undoScope], not `viewModelScope`: see there.
        undoScope.launch {
            runCatching {
                val revertAt = Instant.now()
                repository.mutate { mutator ->
                    val liveFolders = mutator.folders.filter { it.deletedAt == null }.mapTo(HashSet()) { it.id }
                    for (index in mutator.items.indices) {
                        val item = mutator.items[index]
                        val reverted = item.withDragMoveUndone(previous, liveFolders, revertAt)
                        if (reverted != item) mutator.items[index] = reverted
                    }
                }
            }
                .onSuccess { loadFolders(); refresh(); LkNotices.post(undoneNotice) }
                .onFailure { _state.update { s -> s.copy(error = ACTION_ERROR, loadFailed = false) } }
        }
    }

    // ---- drag and drop ----

    /**
     * Whether a drag may start at all.
     *
     * Only a cross-folder search disables it. An earlier reading disabled the drag for *any* search
     * or type filter, which is wrong against `folderScopePredicate`: with a folder open the
     * predicate is `item.folderId in descendants` regardless of whether a query is running, and the
     * type filter narrows that scoped set rather than replacing it. Browsing a folder — searching
     * inside it, filtering it — is still a folder view with a well-defined "where did this come
     * from", so a drag out of it means something. Only at the root with a query is the list
     * genuinely cross-folder, and there a row's origin is unknowable.
     */
    /** The folder the list is showing, read live — a drag outlives the navigations it causes. */
    val browsedFolderId: UUID?
        get() = _state.value.folderStack.lastOrNull()

    val dragEnabled: Boolean
        get() = !(_state.value.folderStack.isEmpty() && _state.value.searchQuery.isNotBlank())

    /**
     * The dragged items as the **vault** holds them, not as this screen is currently listing them.
     *
     * `_state.value.items` is the visible list, and a spring-load navigates away from it with the
     * payload still in hand. One hop into a folder and the dragged item is out of scope, so
     * [acceptanceFor] saw an empty set and refused every target after the first — nested descent
     * could never reach a second level — and [commitDrag] captured an empty `previous` map, so Undo
     * silently restored nothing. Measured on emulator-5554 on 2026-08-18: a drag that spring-loaded
     * into one folder was refused by every target inside it.
     *
     * A full scan rather than a lookup because the repository exposes the list, not an index; it
     * runs on a hover *change*, not per frame.
     */
    private fun draggedItems(ids: Set<UUID>): List<VaultItem> =
        runCatching { repository.getAllItems().filter { it.id in ids } }.getOrDefault(emptyList())

    /**
     * What dropping [payload] onto [targetFolderId] would do — null meaning the root.
     *
     * Consulted on every hover, so it is a pure read of current state and never writes.
     */
    fun acceptanceFor(payload: LkDragPayload, targetFolderId: UUID?): LkDropAcceptance {
        val folders = _state.value.folders
        return when (payload) {
            is FolderDragPayload -> {
                val id = payload.folderId
                when {
                    targetFolderId == null ->
                        if (folders.firstOrNull { it.id == id }?.parentId == null) {
                            LkDropAcceptance.NoOp
                        } else {
                            LkDropAcceptance.Accepts
                        }
                    // Self and subtree: the move that detaches a whole branch from the vault.
                    FolderTree.wouldBeDescendant(folders, id, targetFolderId) -> LkDropAcceptance.Rejects
                    folders.firstOrNull { it.id == id }?.parentId == targetFolderId -> LkDropAcceptance.NoOp
                    // The `+ 1` is why this goes through the helper. Dropping *into* a target makes
                    // that target the new parent, so the payload lands one level below it — and the
                    // payload's own height counts too. Without both, a childless folder dropped on a
                    // folder already at the limit computes 8 and is accepted.
                    FolderTree.wouldExceedDepth(folders, id, targetFolderId) -> LkDropAcceptance.Rejects
                    else -> LkDropAcceptance.Accepts
                }
            }
            is ItemsDragPayload ->
                itemsDropAcceptance(draggedItems(payload.ids), folders, targetFolderId)
            else -> LkDropAcceptance.Rejects
        }
    }

    /**
     * The folders the "Move to folder…" sheet should offer for [payload]: every destination a drop
     * on it would not refuse.
     *
     * One call for the whole list rather than [acceptanceFor] once per folder. For items that is a
     * [draggedItems] — a fresh copy of the vault, filtered — per folder, and the sheet asked it
     * during composition, so a few hundred folders over ten thousand items were millions of element
     * visits and megabytes of garbage on the main thread each time the screen recomposed with the
     * sheet open (S1-08, 2026-10-02). The payload is resolved once here; what is left per folder is a
     * comparison.
     */
    fun moveDestinationsFor(payload: LkDragPayload): List<Folder> = moveDestinations(
        folders = _state.value.folders,
        payload = payload,
        resolveItems = ::draggedItems,
        acceptanceFor = ::acceptanceFor,
    )

    /**
     * What dropping [payload] on the list's own background would do.
     *
     * Not the same question as [acceptanceFor] with the browsed folder, because the background is
     * the one target nobody aims at. A folder list shows its **whole subtree**, so an item living
     * three levels down still has a row here; releasing over that row — or over any empty space
     * beside it — used to re-file the item into the folder being browsed and post "1 item moved"
     * with an undo. Reported on 2026-08-18: *"pasting it to the same position from where I picked
     * it moves it somewhere, I need to click undo"*, and reproduced on emulator-5554 the same day —
     * an item shown as "Bank, Login, in Alpha" was dropped back on its own row and left Alpha.
     *
     * So: anything already inside this subtree is a [LkDropAcceptance.NoOp] here. Putting something
     * back where it came from is not a move, and the background cannot tell the difference between
     * a deliberate release and a hand coming to rest.
     *
     * Promoting a nested item into the folder being browsed is still available, and deliberately
     * only through gestures that name their destination: the folder rows, the parent pill, the
     * unfile strip, and the row menu's "Move…". The rule is scoped to a real folder — at the root
     * the list holds only unfiled items, and a payload carried up there by a crumb is being unfiled
     * on purpose, which [acceptanceFor] already resolves.
     */
    fun backgroundAcceptanceFor(payload: LkDragPayload): LkDropAcceptance {
        val target = browsedFolderId ?: return acceptanceFor(payload, null)
        val subtree = FolderTree.descendantIds(_state.value.folders, target)
        val alreadyHere = when (payload) {
            is ItemsDragPayload -> draggedItems(payload.ids)
                .let { moving -> moving.isNotEmpty() && moving.all { it.folderId in subtree } }
            // `descendantIds` includes the root of the walk, so a folder whose parent is the browsed
            // folder is caught by the same test that catches one nested deeper.
            is FolderDragPayload ->
                _state.value.folders.firstOrNull { it.id == payload.folderId }?.parentId in subtree
            else -> false
        }
        return if (alreadyHere) LkDropAcceptance.NoOp else acceptanceFor(payload, target)
    }

    /**
     * Performs an accepted drop.
     *
     * Every writing drop posts an undo: a single-finger gesture that re-files data has to be
     * reversible in one tap, and a drag is far easier to make by accident than a menu is. A failed
     * write surfaces the screen's ordinary action error and — because [refresh] runs either way —
     * the list never shows the payload in a home the write did not reach.
     *
     * @param onSettled runs once the write has landed, so the screen can clear `session.committing`.
     *   Target bounds are stale until then, which is why a second drag is blocked meanwhile.
     */
    fun commitDrag(
        payload: LkDragPayload,
        targetFolderId: UUID?,
        movedNotice: LkNotices.Message,
        undoLabel: String,
        undoneNotice: String,
        onSettled: () -> Unit,
    ) {
        viewModelScope.launch {
            val undo = runCatching {
                when (payload) {
                    is FolderDragPayload -> {
                        val previous = _state.value.folders.firstOrNull { it.id == payload.folderId }?.parentId
                        repository.moveFolder(payload.folderId, targetFolderId)
                        suspend { repository.moveFolder(payload.folderId, previous) }
                    }
                    is ItemsDragPayload -> {
                        val previous = draggedItems(payload.ids).associate { it.id to it.folderId }
                        // One encrypted save for the whole multi-item drop, not one per item. A
                        // `setItemFolder` in a forEach re-serialised and re-encrypted the ENTIRE vault
                        // (~8 MiB at 10k items) once per dragged id — N drops meant N full commits,
                        // sequentially. repository.mutate folds them into a single commit, exactly as
                        // bulkMoveToFolder already does for the same folder-reassignment. Only items
                        // whose folder actually changes are touched, matching setItemFolder's own
                        // skip-if-unchanged so an item already in the target keeps its updatedAt.
                        val now = Instant.now()
                        repository.mutate { mutator ->
                            for (index in mutator.items.indices) {
                                val item = mutator.items[index]
                                if (item.id in payload.ids && item.folderId != targetFolderId) {
                                    mutator.items[index] = item.copy(folderId = targetFolderId, updatedAt = now)
                                }
                            }
                        }
                        suspend {
                            val revertAt = Instant.now()
                            repository.mutate { mutator ->
                                // The folder an item came from may have been trashed inside the undo
                                // window (the notice stands for eight seconds, and the drawer's Folders
                                // screen posts no notice of its own to replace it). Filing the item
                                // back into it left it live in a folder normal browsing cannot reach.
                                // 2026-10-02: the root instead, as [bulkRestoreTrashed] does.
                                val liveFolders = mutator.folders
                                    .filter { it.deletedAt == null }
                                    .mapTo(HashSet()) { it.id }
                                for (index in mutator.items.indices) {
                                    val item = mutator.items[index]
                                    val reverted = item.withDragMoveUndone(previous, liveFolders, revertAt)
                                    if (reverted != item) mutator.items[index] = reverted
                                }
                            }
                        }
                    }
                    else -> error("unknown payload")
                }
            }
                .onFailure { failure ->
                    // A scope cancellation is cooperative control flow, not a drag failure — rethrow
                    // it rather than mapping it to ACTION_ERROR, which would flash a spurious error
                    // for a move that may already have committed. Mirrors the runOperation pattern.
                    if (failure is CancellationException) throw failure
                    // A notice, not the inline error: the refresh() below re-publishes the list and
                    // clears `error` as it does, so the inline one was wiped before it drew — a move
                    // refused because the target already held that name said nothing (QA 2026-10-03).
                    postFolderWriteRefusal(failure, ACTION_ERROR)
                }
                .getOrNull()

            loadFolders()
            refresh()
            onSettled()
            if (undo == null) return@launch
            LkNotices.post(
                message = movedNotice,
                actionLabel = LkNotices.Message.Text(undoLabel),
            ) {
                // [undoScope], not `viewModelScope`: see there.
                undoScope.launch {
                    runCatching { undo() }
                        .onSuccess { loadFolders(); refresh(); LkNotices.post(undoneNotice) }
                        .onFailure { _state.update { s -> s.copy(error = ACTION_ERROR, loadFailed = false) } }
                }
            }
        }
    }

    /** Moves a whole folder to the trash — the drag's Trash dock, and §8's custom action. */
    fun trashFolderFromDrag(
        folderId: UUID,
        notice: (Int, Int) -> LkNotices.Message,
        undoLabel: String,
        undoneNotice: String,
        onSettled: () -> Unit,
    ) {
        viewModelScope.launch {
            val outcome = runCatching { repository.trashFolder(folderId) }
                .onFailure { _state.update { s -> s.copy(error = ACTION_ERROR, loadFailed = false) } }
                .getOrNull()
            loadFolders()
            refresh()
            onSettled()
            if (outcome == null) return@launch
            // With an undo, as trashing an item has: a whole subtree went in one tap, with no
            // confirmation on this path, and the way back was the Trash screen (QA 2026-10-03).
            LkNotices.post(
                message = notice(outcome.folderCount, outcome.itemCount),
                actionLabel = LkNotices.Message.Text(undoLabel),
            ) {
                // [undoScope], not `viewModelScope`: see there.
                undoScope.launch {
                    runCatching { repository.restoreFolder(folderId) }
                        .onSuccess { loadFolders(); refresh(); LkNotices.post(undoneNotice) }
                        .onFailure { _state.update { s -> s.copy(error = ACTION_ERROR, loadFailed = false) } }
                }
            }
        }
    }

    /**
     * Makes a folder under the one being browsed and files the selection into it, in that order.
     *
     * The sheet's inline create. Without it, a user whose destination does not exist yet has to
     * abandon a selection they have just built by hand, go and make the folder, and rebuild it.
     *
     * A failure here — a blank name, a sibling that already has it, the depth limit — surfaces as
     * the screen's ordinary action error and the selection is left intact, so the move can be
     * retried against a folder made some other way.
     */
    fun createFolderAndMoveSelection(name: String, noChangeNotice: String, undoLabel: String, undoneNotice: String) {
        viewModelScope.launch {
            val created = runCatching { repository.createFolder(name, _state.value.folderStack.lastOrNull()) }
                .onFailure { failure ->
                    _state.update { s -> s.copy(error = folderWriteError(failure, ACTION_ERROR), loadFailed = false) }
                }
                .getOrNull() ?: return@launch
            loadFolders()
            bulkMoveToFolder(created.id, noChangeNotice, undoLabel, undoneNotice)
        }
    }

    /**
     * Makes a folder under [parentId] for the add sheet's New folder screen, then runs [onCreated]
     * with the new folder's id.
     *
     * That screen holds its own instance of this ViewModel and renders none of its state, so a
     * refusal is posted as a notice rather than set as [VaultListUiState.error], and [onCreated]
     * runs only once the folder exists and the list has been re-read. It used to *leave* the screen
     * from here, which made twenty folders twenty trips through the add sheet; the screen now stays
     * and clears its own field (2026-10-12). It also used to leave *first*: a name held by a folder
     * in Trash, or a ninth level, closed it and created nothing, with nothing said (QA 2026-10-03).
     */
    fun createFolder(name: String, parentId: UUID?, onCreated: (UUID) -> Unit) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        var createdId: UUID? = null
        mutate(
            after = {
                loadFolders()
                createdId?.let(onCreated)
            },
            failed = { failure -> postFolderWriteRefusal(failure, ACTION_ERROR) },
        ) { createdId = repository.createFolder(clean, parentId).id }
    }

    /**
     * Adds [tag] to every selected item, leaving tags they already carry alone.
     *
     * Additive on purpose. A bulk *replace* would silently discard whatever filing the user had
     * already done on some of the selection, and nothing on the screen would say it had happened.
     */
    fun bulkAddTag(tag: String, noChangeNotice: String) {
        val clean = tag.trim()
        if (clean.isEmpty()) return
        val now = Instant.now()
        bulkEdit(
            noChangeNotice = noChangeNotice,
            confirmation = { n -> LkNotices.Message.Plural(R.plurals.vaultlist_bulk_tagged, n) },
        ) { item ->
            if (item.tags.any { it.equals(clean, ignoreCase = true) }) item
            else item.copy(tags = item.tags + clean, updatedAt = now)
        }
    }

    /**
     * The notice's undo restores exactly the ids this call touched, in one commit — see
     * [bulkRestoreTrashed]. Found 2026-08-31: the confirm dialog says "this
     * can be undone", but until this fix the notice offered no undo action at all, so reversing a
     * bulk trash of N items meant N individual restores through the Trash screen, each its own
     * full-vault re-encrypt.
     */
    fun bulkTrash(noChangeNotice: String, undoLabel: String) {
        val now = Instant.now()
        // Already-trashed items are left alone. `mutate` does no validation of its own, unlike
        // `moveToTrash`, which asserts the item is not already deleted — so without this an item
        // that was trashed individually while still selected would have its `deletedAt` overwritten
        // and its retention clock restarted from zero. [changedIds] excludes them from the undo set
        // too: their transform is a no-op, so an undo built from the raw selection would wrongly
        // restore items this run never touched.
        bulkEdit(
            noChangeNotice = noChangeNotice,
            confirmation = { n -> LkNotices.Message.Plural(R.plurals.vaultlist_bulk_trashed, n) },
            undo = { ids -> LkNotices.Message.Text(undoLabel) to { bulkRestoreTrashed(ids) } },
        ) { item ->
            if (item.deletedAt != null) item else item.copy(updatedAt = now, deletedAt = now)
        }
    }

    /**
     * Restores exactly [ids] from Trash in one commit — the undo [bulkTrash] wires onto its
     * notice.
     *
     * Not routed through [bulkEdit]: that reads the *live* selection, which bulkTrash already
     * cleared by the time this can run, and may since hold something unrelated the user picked
     * afterward. [ids] is the frozen set the notice's closure was built with.
     */
    private fun bulkRestoreTrashed(ids: Set<UUID>) {
        if (ids.isEmpty() || bulkEditRunning) return
        bulkEditRunning = true
        _state.update { it.copy(bulkEditRunning = true) }
        var restored = emptySet<UUID>()
        // [undoScope], not `viewModelScope`: this is the bulk-trash notice's Undo. See there.
        undoScope.launch {
            runCatching {
                repository.mutate { mutator ->
                    // The folder the item came out of may have been trashed inside the undo window
                    // — the notice stands for eight seconds, which is ample. Clearing `deletedAt`
                    // alone put the item back pointing at a folder normal browsing cannot reach, so
                    // an undo that reported success left the item invisible: not in the folder, not
                    // at the root, not in the trash. Landing it at the root instead is the same
                    // answer the purge path gives (`if (parent !in surviving) folderId = null`) and
                    // the same answer folder restore gives by climbing to the nearest live
                    // ancestor. The root is always reachable, which is the property that matters.
                    val liveFolders = mutator.folders
                        .filter { it.deletedAt == null }
                        .mapTo(HashSet()) { it.id }
                    val transform: (VaultItem) -> VaultItem = { item ->
                        if (item.deletedAt == null) {
                            item
                        } else {
                            val orphaned = item.folderId?.let { it !in liveFolders } == true
                            item.copy(
                                updatedAt = Instant.now(),
                                deletedAt = null,
                                folderId = if (orphaned) null else item.folderId,
                            )
                        }
                    }
                    restored = changedIds(mutator.items, ids, transform)
                    for (index in mutator.items.indices) {
                        val item = mutator.items[index]
                        if (item.id in ids) mutator.items[index] = transform(item)
                    }
                }
            }.onSuccess {
                _state.update { it.copy(bulkEditRunning = false) }
                loadFolders()
                refresh()
                if (restored.isNotEmpty()) {
                    LkNotices.post(LkNotices.Message.Plural(R.plurals.vaultlist_bulk_restored, restored.size))
                }
            }.onFailure {
                _state.update { it.copy(error = ACTION_ERROR, loadFailed = false, bulkEditRunning = false) }
            }
            bulkEditRunning = false
        }
    }

    /**
     * Applies [transform] to every selected item in **one** encrypted save.
     *
     * repository.mutate batches the whole edit behind a single commit. Looping updateItem() instead
     * would re-encrypt and rewrite the entire vault once per item — on the two-hundred-item import
     * this exists for, that is two hundred full-file writes where one will do.
     *
     * The selection clears on success only. A failed bulk edit leaves the rows selected so the user
     * can simply try again, rather than having to reconstruct which two hundred they had picked.
     */
    /**
     * @param confirmation announced on success, given the number of items the transform actually
     *   changed — not the number selected. The two differ whenever a transform declines an item
     *   (a tag it already carries, a row already in the trash), and a confirmation that counted
     *   the selection would be telling the user something that did not happen.
     * @param undo when given, builds the notice's action label and callback from exactly the ids
     *   the transform changed — never invoked when nothing changed. Only [bulkTrash] passes one;
     *   [bulkMoveToFolder] and [bulkAddTag] leave it null and get a plain confirmation, matching
     *   how [moveToTrash] alone among the single-item actions offers an undo.
     */
    private fun bulkEdit(
        noChangeNotice: String,
        confirmation: (Int) -> LkNotices.Message,
        undo: ((changedIds: Set<UUID>) -> Pair<LkNotices.Message, () -> Unit>)? = null,
        transform: (VaultItem) -> VaultItem,
    ) {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        // The selection clears on *success*, so it is still there for a second tap that lands
        // before the first save returns — and a full-vault encrypt-and-write is long enough for
        // that to be an ordinary double tap rather than a race. The second pass is harmless in
        // itself (all three transforms are idempotent) but it is not free: it emits another
        // onSaved, which takes another rolling snapshot, and the ring only holds five — so a
        // duplicate of the state just saved evicts a genuinely older generation. Guarded here
        // rather than per-action, matching ItemEditViewModel.save and applyImport.
        if (bulkEditRunning) return
        bulkEditRunning = true
        _state.update { it.copy(bulkEditRunning = true) }
        var touched = emptySet<UUID>()
        viewModelScope.launch {
            runCatching {
                repository.mutate { mutator ->
                    touched = changedIds(mutator.items, ids, transform)
                    for (index in mutator.items.indices) {
                        val item = mutator.items[index]
                        if (item.id in ids) mutator.items[index] = transform(item)
                    }
                }
            }.onSuccess {
                _state.update { it.copy(selectedIds = emptySet(), bulkEditRunning = false) }
                loadFolders()
                refresh()
                val message = bulkNotice(touched.size, noChangeNotice, confirmation)
                val action = undo?.takeIf { touched.isNotEmpty() }?.invoke(touched)
                if (action != null) {
                    LkNotices.post(message, actionLabel = action.first, onAction = action.second)
                } else {
                    LkNotices.post(message)
                }
            }.onFailure {
                _state.update { it.copy(error = ACTION_ERROR, loadFailed = false, bulkEditRunning = false) }
            }
            bulkEditRunning = false
        }
    }

    /** Read and written only from the main dispatcher, which is where every bulk action starts. */
    private var bulkEditRunning = false

    /**
     * Read straight off the unlocked vault, the same way the item editor's picker does.
     *
     * `.live` is where trashed folders leave the app: everything downstream of here — the browse
     * path, the drawer tree, the folder names strip, the move sheet — reads `state.folders` and so
     * inherits the filter for free. The repository is the other side of this boundary and keeps the
     * raw list on purpose.
     */
    private suspend fun loadFolders(projected: VaultVisibilitySnapshot? = null) {
        // Off the main dispatcher, for the reason [refresh] gives eight lines below: this is the
        // same whole-vault projection, on the same triggers, and it used to run on
        // `Dispatchers.Main.immediate` — a frame dropped after every save, favourite toggle, trash
        // and bulk action, which is the moment the list is being watched for confirmation the
        // action landed. `sortedByDisplayName` goes with it: it builds a fresh `java.text.Collator`
        // per call.
        //
        // [projected] is the snapshot the caller already has. `VaultPresentationState.Unlocked`
        // carries the one `VaultVisibility.project` just produced, and the collector used to throw
        // it away and re-derive it — six passes over `vault.items` plus a folder ancestry walk, for
        // an answer already in hand.
        val (snapshot, folders) = withContext(Dispatchers.Default) {
            // Through the projection, not the raw vault: this filters trashed folders (as `.live`
            // did) and additionally conceals hidden folders and their subtrees unless a reveal
            // session is active, so the browse path, drawer tree, name strip and move sheet all
            // inherit concealment.
            val resolved = projected ?: runCatching { repository.visibilitySnapshot() }.getOrNull()
            resolved to resolved?.liveFolders.orEmpty().sortedByDisplayName { it.name }
        }
        _state.update { current ->
            current.copy(
                folders = folders,
                concealedFolderIds = snapshot?.concealedFolderIds.orEmpty(),
                directlyHiddenFolderIds = snapshot?.directlyHiddenFolderIds.orEmpty(),
                // A browse path through a folder that no longer exists matches nothing, and the
                // breadcrumb that would explain why is gone with it — so the list reads as an empty
                // vault with no way back. Truncated at the first missing crumb rather than cleared,
                // so deleting a subfolder leaves the user in its parent instead of at the root.
                // Renaming is safe (the id survives); deleting the folder, or locking and opening a
                // different vault, is what this catches.
                folderStack = current.folderStack.takeWhile { id -> folders.any { it.id == id } },
            )
        }
    }

    fun lock() {
        lockManager.lock()
    }

    override fun onCleared() {
        // Balances the acquire in init: the last release cancels the shared expiry timer.
        visibilityCoordinator.release()
        super.onCleared()
    }

    /**
     * "Hide again now": conceals the whole reveal session at once (not a per-row lease change), so
     * every hidden entry disappears again. The lock manager flips the session and the shared
     * projection re-renders the list without them.
     */
    fun hideAgainNow() {
        lockManager.concealHiddenSession()
    }

    /** Opens the Hide… duration sheet for [target] (a folder or item), titled [label]. */
    fun beginHide(target: HiddenTarget, label: String) {
        _state.update { it.copy(hideFlow = HideFlowState(target, label)) }
    }

    /**
     * The user typed in the reveal-phrase field: whatever inline error the last submit raised is
     * about the text that is no longer there. See [afterPhraseEdited].
     */
    fun onHidePhraseEdited() {
        _state.update { st -> st.hideFlow?.let { st.copy(hideFlow = it.afterPhraseEdited()) } ?: st }
    }

    /** Closes the Hide… flow, committing nothing. */
    fun dismissHide() {
        _state.update { it.copy(hideFlow = null) }
    }

    /**
     * Commits the open Hide… flow at [until]. [firstPhrase] is supplied only from the first-phrase
     * step and is wiped by the repository. A [HideOutcome.PhraseRequired] result flips the flow into
     * its phrase step; a collision or invalid duration is shown inline and the sheet stays open.
     */
    fun confirmHide(until: Instant, firstPhrase: CharArray? = null) {
        val flow = _state.value.hideFlow ?: return
        viewModelScope.launch {
            when (repository.hide(flow.target, until, firstPhrase)) {
                HideOutcome.Hidden -> {
                    _state.update { it.copy(hideFlow = null) }
                    // Re-arm the shared expiry timer. The projection already updated — a hide is a
                    // save — but the timer was armed for whatever the nearest lease was when this
                    // screen acquired the coordinator, and a shorter lease set just now would
                    // otherwise never wake it. Without this the "5 minutes" preset simply does not
                    // un-hide until something else happens to recompute.
                    visibilityCoordinator.reschedule()
                }
                HideOutcome.PhraseRequired ->
                    _state.update { it.copy(hideFlow = flow.copy(needsPhrase = true, pendingUntil = until, error = null)) }
                HideOutcome.PhraseCollision ->
                    _state.update { it.copy(hideFlow = flow.copy(error = HideFlowError.PhraseCollision)) }
                HideOutcome.InvalidDuration ->
                    _state.update { it.copy(hideFlow = flow.copy(error = HideFlowError.InvalidDuration)) }
                // The target vanished or the built verifier was somehow invalid — nothing the user can
                // fix in this sheet, so close it and surface the generic action error.
                HideOutcome.TargetAbsent, HideOutcome.InvalidVerifier ->
                    _state.update { it.copy(hideFlow = null, error = ACTION_ERROR) }
            }
        }
    }

    /** Stops hiding [target] — clears its lease so it is visible again (used from a revealed row). */
    /** Dismisses the Recovery-Kit note. One tap, and it does not come back this session. */
    fun dismissRecoveryKitNote() = _state.update { it.copy(openedWithRecoveryKit = false) }

    fun stopHiding(target: HiddenTarget) {
        mutate {
            repository.stopHiding(target)
            // Same reason as `confirmHide`, in the other direction: dropping the lease that *was*
            // the nearest expiry leaves the timer armed for an instant that no longer matters.
            visibilityCoordinator.reschedule()
        }
    }

    /**
     * Locks AND blocks biometric unlock until the master password is entered again — for handing
     * over an unlocked phone under pressure, where a fingerprint could otherwise be compelled.
     * Reachable by long-pressing Lock (UI/UX spec §10.2: "always one gesture away").
     */
    fun panicLock() {
        lockManager.panicLock()
    }

    /**
     * @param after runs once the change is on disk and the list has been re-read. Used to announce
     *   the result; actions whose own control already reports the outcome — the favourite star
     *   flipping, for one — pass nothing and stay quiet.
     * @param scope where the write runs. `viewModelScope` for everything the user does on this
     *   screen; [undoScope] for an Undo, whose button can outlive the screen — see there.
     */
    private fun mutate(
        scope: CoroutineScope = viewModelScope,
        after: suspend () -> Unit = {},
        failed: ((Throwable) -> Unit)? = null,
        block: suspend () -> Unit,
    ) {
        // Guarded like [bulkEdit], and for the harm [bulkEdit] already documents: a duplicate commit
        // emits another `onSaved`, which takes another rolling snapshot, and the five-slot ring then
        // drops a genuinely older generation. This path was left open while the bulk one was
        // closed, and it is the heavier of the two to double-tap — an inline trash with an undo, a
        // favourite that re-sorts the list under the finger.
        if (mutating) return
        mutating = true
        scope.launch {
            try {
                runCatching { block() }
                    .onSuccess {
                        refresh()
                        after()
                    }
                    .onFailure { failure ->
                        if (failed != null) {
                            failed(failure)
                        } else {
                            _state.update { it.copy(error = folderWriteError(failure, ACTION_ERROR), loadFailed = false) }
                        }
                    }
            } finally {
                // `finally`, so a cancelled scope cannot latch the flag and leave every row action
                // silently dead for the rest of the ViewModel's life.
                mutating = false
            }
        }
    }

    /** Read and written only from the main dispatcher, which is where every row action starts. */
    private var mutating = false

    /** The debounce timer for [onSearchChanged]; cancelled and restarted on each keystroke. */
    private var searchJob: Job? = null

    /** The in-flight [refresh] scan; a newer refresh cancels it so a superseded scan cannot publish. */
    private var refreshJob: Job? = null

    private fun refresh() {
        // Any pending debounced search is now stale: this refresh already reflects the current
        // query, and letting the timer fire afterwards would re-run the same scan for nothing.
        //
        // Only for callers that are *not* the debounce timer. The timer calls [scanAndPublish]
        // directly, because `searchJob` is that coroutine's own job: cancelling it here cancelled
        // the caller, and everything after the call — the reveal probe, and therefore the phrase
        // the search box advertises — died at the next suspension point. See [onSearchChanged].
        searchJob?.cancel()
        scanAndPublish()
    }

    /** The list scan itself, without the pending-debounce cancel [refresh] opens with. */
    private fun scanAndPublish() {
        // The whole-vault scan/search/sort/count runs off the main thread. At the 10k-item scale this
        // app targets it is tens of milliseconds, and it used to land on whichever dispatcher the
        // caller (a chip tap, a folder open, the search debounce) was on — the main thread — dropping
        // frames on those taps. Offloaded to Dispatchers.Default, mirroring HealthDashboardViewModel.
        // A newer refresh cancels an in-flight one, and the isActive guard below stops a superseded
        // scan from overwriting fresher state.
        refreshJob?.cancel()
        // Read here, on the main dispatcher, so it names the reveal this scan was *started* with — see
        // [withUnresolvedRevealDropped].
        val revealedAtScanStart = _state.value.revealedItemId
        refreshJob = viewModelScope.launch(Dispatchers.Default) {
        val scan = runCatching {
            val current = _state.value
            // Every list source goes through the presentation projection, so a concealed item is
            // absent here (and in the search escape count, folder counts and pickers) unless a reveal
            // session is active — in which case the projection admits it and the row carries a marker.
            val snapshot = repository.visibilitySnapshot()
            val visibleIds = snapshot.liveItems.mapTo(HashSet()) { it.id }
            val matching = if (current.searchQuery.isBlank()) {
                snapshot.liveItems
            } else {
                repository.search(current.searchQuery).filter { it.id in visibleIds }
            }
            val scope = current.folderStack.lastOrNull()
            val descendants = scope?.let { repository.folderDescendants(it) }.orEmpty()
            val searching = current.searchQuery.isNotBlank()
            // Split in two at the folder axis so the header can count the vault while the list shows
            // the folder. Everything above the split narrows by what the user asked for (chips, tag,
            // search, concealment); the folder axis below it is where the user *is*. See
            // [headerItemCount].
            val narrowed = matching
                .filter { !current.showFavoritesOnly || it.favorite }
                .filter { current.typeFilter?.matches(it) ?: true }
                // Case-insensitively, because the editor already is: `addTag` refuses a
                // duplicate with `equals(tag, ignoreCase = true)` and tells the user in as many
                // words that "Work" and "work" are the same tag. An exact-match filter made that
                // sentence false one screen over - pick the "Work" chip and the items tagged
                // "work" are simply absent, with nothing on screen suggesting a second spelling
                // exists to look under.
                .filter { item ->
                    val wanted = current.tagFilter ?: return@filter true
                    item.tags.any { it.equals(wanted, ignoreCase = true) }
                }
                // Against the snapshot's own concealed set, which is empty outside a reveal session
                // — so even a stale flag could only ever narrow to nothing, never widen.
                .filter { !current.showHiddenOnly || it.id in snapshot.concealedItemIds }
            val inBrowse = folderScopePredicate(scope, current.isFlatView, descendants)
            val scoped = narrowed.filter(inBrowse)
            // Favorites pin to the top of the full list (spec §4.1), not just under the Favorites
            // chip — otherwise "pinned" only means "filtered".
            // Titles order under [displayNameComparator], the app-wide collation. The
            // `lowercase(Locale.ROOT)` it replaced was pinned to ROOT so a Turkish device could not
            // fold 'I' to a dotless 'i' and disagree with the matcher that found the item; the
            // collator never lowercases at all, so that hazard leaves with it, and an accented title
            // stops sorting after the whole unaccented alphabet.
            Scan(
                items = orderVaultItems(scoped, directFolderId = scope),
                // Free: `matching` is the search result before any chip touched it, so the two
                // counts are already in hand and no second pass over the vault is needed.
                outsideFilter = matchesOutsideNarrowing(searching, matching.size, scoped.size),
                scopeItemCount = headerItemCount(scope, narrowed.size, scoped.size),
                descendantIds = descendants,
                folderItemCounts = subtreeCounts(snapshot.liveItems, current.typeFilter),
                folderSubtreeItemCounts = subtreeCounts(snapshot.liveItems, null),
                relevantFolderIds = current.typeFilter?.let { filter ->
                    FolderRelevance.relevantFolderIds(snapshot.liveItems, current.folders) { filter.matches(it) }
                },
                concealedItemIds = snapshot.concealedItemIds,
                directlyHiddenItemIds = snapshot.directlyHiddenItemIds,
                // From every visible item, not from `scoped`: the sheet offers the tags the vault
                // has, not the tags the current narrowing happens to leave standing.
                // Deduped case-insensitively, first spelling wins - the other half of the same
                // rule. A plain set offered "Work" and "work" as two chips, and picking either one
                // could only ever show part of what the user had tagged.
                availableTags = snapshot.liveItems
                    .flatMapTo(TreeSet(String.CASE_INSENSITIVE_ORDER)) { it.tags }
                    .sortedByDisplayName { it },
                // A selected item that passes every filter and is still not listed is off screen
                // because of the browse position — see [VaultListUiState.selectionOutsideBrowse].
                selectionOutsideBrowse = current.selectedIds.isNotEmpty() &&
                    narrowed.any { it.id in current.selectedIds && !inBrowse(it) },
            )
        }
        if (!isActive) return@launch
        scan.onSuccess { (items, outsideFilter, scopeItemCount, descendants, counts, unfilteredCounts, concealedItemIds, directlyHiddenItemIds, relevantFolderIds, availableTags, selectionOutsideBrowse) ->
            // Clearing the error is the point of this line. Without it a single transient failure —
            // and the reliable one is the first refresh racing the unlock, when the repository is
            // still locked — pins "Vault action could not be completed" above the list for the rest
            // of the session, over a vault that is loading perfectly well. Seen on a device.
            //
            // The selection is pruned against the *whole* vault, not against `items` (the current
            // filter/search result). It used to be pruned to what was on screen, which silently
            // discarded the selection the moment a filter change hid it — switching the type chip
            // from Logins to 2FA with a login selected dropped out of multi-select entirely, with no
            // prompt. Now a selected id is only ever removed here because the row it named is gone
            // from the vault (trashed individually, or the vault swapped out from under it) —
            // everything else stays selected, just hidden; see [VaultListUiState.hiddenSelectedCount].
            // Bulk actions read `selectedIds` directly (see `bulkEdit`), so a hidden-but-selected
            // item is still acted on — the header's promise stays true either way. The one
            // exception is concealment (see [withConcealedPruned], applied below): a row a reveal
            // session put back out of sight is dropped from the selection outright, the same way a
            // row trashed out from under the selection is, rather than staying selected-but-hidden.
            // Both reads guarded, because they are outside the `runCatching` that produced `scan`
            // and they touch the vault again. A lock landing in the window between the scan
            // succeeding and this line makes `getAllItems` throw `IllegalStateException("vault is
            // locked")` from `requireVault` — on Dispatchers.Default, inside a `launch`, with no
            // handler above it, which is a process crash rather than a failed refresh. The window
            // is small and entirely ordinary: the auto-lock timer and the screen-off receiver both
            // fire without regard to what the list is doing.
            //
            // Returning is the whole response. A locked vault is not a refresh that failed and must
            // not paint the error state — `lockState` has its own collector that clears the list,
            // and it has already run or is about to.
            val vaultReads = runCatching {
                repository.getAllItems().mapTo(mutableSetOf()) { it.id } to quotaUsed()
            }.getOrNull() ?: return@launch
            val (existing, quotaUsed) = vaultReads
            val (cardsUsed, identitiesUsed) = typeQuotaUsed()
            _state.update { current ->
                // Asked here rather than at each chip, star and search keystroke: every one of them
                // ends in a scan, and one comparison at the single point of publish cannot be the
                // call site somebody forgets. The narrowing is read from `current`, which already
                // carries the change that triggered this scan.
                val narrowing = current.narrowingSignature()
                val narrowingChanged = lastNarrowing != null && lastNarrowing != narrowing
                lastNarrowing = narrowing
                current.copy(
                    items = items,
                    projectionVersion = current.projectionVersion + 1L,
                    scrollToTopRequest = current.scrollToTopRequest + if (narrowingChanged) 1L else 0L,
                )
                    .withSelectionPrunedTo(existing)
                    .withConcealedPruned(concealedItemIds)
                    .copy(
                        // Counted from the whole vault, never from `items` — that list is already
                        // narrowed by the search box and the type chips, so counting it would let
                        // the cap lift itself the moment the user filtered to Logins.
                        attachmentQuotaUsed = quotaUsed,
                        attachmentQuotaFull = AttachmentQuota.exceedsFreeLimit(quotaUsed, current.isPro),
                        // Same rule as the attachment cap: counted from the whole vault, never from
                        // `items`, which the search box and the type chips have already narrowed.
                        // Counting the filtered list would let the cap lift itself the moment the
                        // user filtered to Logins.
                        cardQuotaUsed = cardsUsed,
                        cardQuotaFull = ProTypeQuota.exceedsFreeLimit(cardsUsed, current.isPro),
                        identityQuotaUsed = identitiesUsed,
                        identityQuotaFull = ProTypeQuota.exceedsFreeLimit(identitiesUsed, current.isPro),
                        matchesOutsideFilter = outsideFilter,
                        scopeItemCount = scopeItemCount,
                        descendantIds = descendants,
                        folderItemCounts = counts,
                        folderSubtreeItemCounts = unfilteredCounts,
                        concealedItemIds = concealedItemIds,
                        directlyHiddenItemIds = directlyHiddenItemIds,
                        relevantFolderIds = relevantFolderIds,
                        availableTags = availableTags,
                        selectionOutsideBrowse = selectionOutsideBrowse,
                        error = null,
                        loadFailed = false,
                        hasScanned = true,
                    )
                    // After `items` is in place, because that is what it looks the row up in.
                    .withUnresolvedRevealDropped(revealedAtScanStart)
            }
        }.onFailure {
            _state.update {
                it.copy(
                    items = emptyList(),
                    matchesOutsideFilter = 0,
                    // With nothing read, the header has nothing true to say about the vault's size
                    // either — and `loadFailed` is what the screen renders instead of a count.
                    scopeItemCount = 0,
                    relevantFolderIds = null,
                    error = LOAD_ERROR,
                    loadFailed = true,
                    // Set on this path too. A scan that failed has still looked, and the panel is
                    // already withheld by `loadFailed` — leaving it false here would make a read
                    // failure indistinguishable from a scan that never ran.
                    hasScanned = true,
                )
            }
        }
        }
    }

    /**
     * One scan's two answers: what to show, and how much the chips are hiding from the same query.
     *
     * A pair rather than two reads of the repository — the second number is a by-product of the
     * first, and computing it separately would mean scanning the vault twice to draw one screen.
     */
    private data class Scan(
        val items: List<VaultItem>,
        val outsideFilter: Int,
        /** What the header counts; see [VaultListUiState.scopeItemCount]. */
        val scopeItemCount: Int,
        val descendantIds: Set<UUID>,
        val folderItemCounts: Map<UUID, Int>,
        /** The same counts unnarrowed; see [VaultListUiState.folderSubtreeItemCounts]. */
        val folderSubtreeItemCounts: Map<UUID, Int>,
        val concealedItemIds: Set<UUID>,
        val directlyHiddenItemIds: Set<UUID>,
        /** Null when no type chip is on; see [VaultListUiState.relevantFolderIds]. */
        val relevantFolderIds: Set<UUID>?,
        val availableTags: List<String>,
        val selectionOutsideBrowse: Boolean,
    )

    /**
     * Points the list at [id] — the item the editor has just saved.
     *
     * Drops every transient narrowing first. The item is new, so any chip, folder or search that was
     * applied a moment ago was chosen for something else and is now the only reason the user cannot
     * see what they made. Favourites too: a new item is not favourited, so leaving that on would
     * reveal the row into a list it is excluded from.
     *
     * The destination's own [initialFilter] is deliberately *not* preserved here, unlike
     * [withTransientFiltersCleared]. On the drawer's "2FA codes" that filter is exactly what hides a
     * new login, and honouring it would reproduce the bug this exists to fix.
     */
    fun revealItem(id: UUID) {
        // The browse path survives only when the item is actually in it. Keeping it unconditionally
        // is what a save-into-the-open-folder round trip needs; keeping it when the item landed
        // somewhere else would highlight a row that is not on screen.
        //
        // And where it did land is where the list goes (2026-10-02): the root lists only unfiled
        // items, so the old "not in this folder, go to the root" left a row filed anywhere but the
        // root with nowhere to be shown. See [revealBrowsePath].
        val browsePath = runCatching {
            val current = _state.value.folderStack
            val itemFolderId = repository.getAllItems().firstOrNull { it.id == id }?.folderId
            revealBrowsePath(
                current = current,
                scopeDescendants = current.lastOrNull()?.let(repository::folderDescendants).orEmpty(),
                itemFolderId = itemFolderId,
                itemFolderPath = itemFolderId?.let(repository::folderPathOf),
            )
        }.getOrDefault(emptyList())
        _state.update {
            // Through withBrowsePath, so a reveal that moves the user takes the selection with it.
            // This was the one stack mutation that set the path directly, and it popped to the root
            // leaving a selection that named rows in the folder it had just left.
            it.withBrowsePath(browsePath).copy(
                typeFilter = null,
                showFavoritesOnly = false,
                tagFilter = null,
                showHiddenOnly = false,
                searchQuery = "",
                revealedItemId = id,
                error = null,
            )
        }
        refresh()
    }

    /** Ends the highlight once it has been seen, so a later refresh does not re-run it. */
    fun clearReveal() {
        _state.update { it.copy(revealedItemId = null) }
    }

    /**
     * Drops the chips but keeps the query — the way out of a search that matched nothing *here*.
     *
     * Deliberately not `withTransientFiltersCleared`, which also clears the search text: the user
     * asked for these words, and the whole point of this action is to run them against the rest of
     * the vault. Clearing the query would answer a question they did not ask.
     */
    fun searchEverywhere() {
        val current = _state.value
        if (current.showsEveryType() && current.folderStack.isEmpty()) return
        // Pops to the root as well as dropping the chips. Leaving the breadcrumb standing while
        // showing items from outside it would make the crumb strip a control lying about what is on
        // screen — the same reason a lit chip may not show items it excludes.
        _state.update {
            it.copy(
                typeFilter = null,
                showFavoritesOnly = false,
                tagFilter = null,
                showHiddenOnly = false,
                folderStack = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    /**
     * Whether the free tier's file-and-photo slots are all taken.
     *
     * Swallows a throw rather than propagating it: the only realistic failure is the vault locking
     * mid-read, and the safe answer to "is this user out of slots" in that moment is no. A false
     * positive would refuse an add the user is entitled to; the add paths in `:feature:files`
     * re-check against a live repository anyway, so nothing gets through on the strength of this.
     */
    /**
     * How many items sit in each folder's whole subtree.
     *
     * Subtree rather than direct, and the same number in both places it is rendered — the folder
     * rows in the list and the drawer's management screen. A parent reading "0 items" while holding
     * a child with forty is a count that makes the user look for data that is right there.
     */
    /**
     * How many items each folder subtree holds — of [typeFilter], when one is on.
     *
     * The filter argument is not decoration. Without it a folder surfaced under the "2FA" chip
     * announced the count of everything in it, so "Receipts, 1 item" appeared beside a filter that
     * had excluded that very item. A count shown next to a filter has to be a count of what the
     * filter admits, or it is not a count of anything the user can see.
     */
    private fun subtreeCounts(liveItems: List<VaultItem>, typeFilter: ItemTypeFilter?): Map<UUID, Int> =
        runCatching {
            // Over the caller's projected [liveItems], not a fresh `visibilitySnapshot()`: this is
            // called twice per scan now — once narrowed, once not — and the scan already holds the
            // projection. The projection, rather than getAllItems(), because the latter filters only
            // deleted items, so a concealed item still counted and a folder holding nothing but
            // hidden entries read "1" while its own list said "no items". liveItems conceals in a
            // normal session and admits in a reveal session, so the count matches the rows exactly
            // in both.
            val items = liveItems.filter { typeFilter?.matches(it) ?: true }
            // See [FolderSubtreeCounts]: one pass up each item's ancestor chain, rather than a
            // descendant-set lookup per folder crossed with every item.
            FolderSubtreeCounts.of(_state.value.folders, items)
        }.getOrDefault(emptyMap())

    /**
     * Slots in use across the whole vault, or zero when it could not be read.
     *
     * Zero is the same safe answer the verdict used to default to on its own: a read racing an
     * auto-lock must not refuse an add the user is entitled to, and the add paths in
     * `:feature:files` re-check against a live repository anyway.
     */
    private fun quotaUsed(): Int =
        runCatching { AttachmentQuota.used(repository) }.getOrDefault(0)

    /**
     * How many Cards or Identity documents the vault holds, for the free-tier allowance.
     *
     * Swallows a throw for the same reason [quotaUsed] does: the only realistic failure is the
     * vault locking mid-read, and the safe answer to "is this user out of slots" in that moment is
     * no. A false positive would refuse a creation the user is entitled to, and `ItemEditViewModel`
     * re-checks against a live repository at save anyway, so nothing gets through on this alone.
     */
    private fun typeQuotaUsed(): Pair<Int, Int> = runCatching {
        // One vault walk, both counts — a walk per question is how a list gets slow, and the D1
        // device pass at 10,000 items is the standing reminder. Asked of ProTypeQuota rather than
        // read here: the raw inventory has a committed caller allowlist (HiddenChokepointTest) and
        // this would have been a second entry on it with no justification the first did not cover.
        val byKind = ProTypeQuota.usedFromRepository(repository)
        (byKind[ProTypeQuota.Kind.CARD] ?: 0) to (byKind[ProTypeQuota.Kind.IDENTITY] ?: 0)
    }.getOrDefault(0 to 0)

    /** Re-reads the vault after a failed load. Exposed because that failure leaves nothing else to press. */
    fun retryLoad() = refresh()

    private companion object {
        // Two different situations used to share one string. A failed *mutation* leaves the control
        // that failed on screen — the row's trash icon, the selection still made — so the message
        // only has to say that trying again is the move. A failed *read* renders an empty list, and
        // "Vault action could not be completed" then describes an action the user never took while
        // showing them what looks like an emptied vault. That one needs its own words and its own
        // way out.
        val ACTION_ERROR = R.string.vaultlist_error_action
        val LOAD_ERROR = R.string.vaultlist_error_load_vault

        /**
         * Where an Undo runs. The scope belongs to the process, not to this ViewModel.
         *
         * [LkNotices] is a singleton that outlives screens, and the Undo this list posts is a closure
         * stored in it. The list is popped and rebuilt by Folders > open a folder, a finished import
         * and a save from the generator — each a `navigate(HOME) { popUpTo(HOME) { inclusive = true } }`
         * that clears this ViewModel — and a notice posted just before is still on screen, with a
         * button that launched on a cancelled `viewModelScope` and did nothing, silently (S1-06,
         * 2026-10-02). `TrashUndoBus` documents the same trap for the detail screen.
         *
         * Nothing here needs the dead ViewModel's scope to make the list right afterwards: the live
         * list re-reads from `presentationState`, which emits on every vault write. `Main.immediate`
         * is what `viewModelScope` uses, and `SupervisorJob` keeps one failed restore from taking
         * every later Undo down with it.
         */
        val undoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

/**
 * Long enough to swallow a burst of typing, short enough not to feel laggy.
 *
 * File-level rather than in the companion above because the trash screen debounces its own search
 * on the same interval, for the same reason, and two screens that scan the same vault per keystroke
 * should not be able to disagree about how long a burst of typing is.
 */
internal const val SEARCH_DEBOUNCE_MS = 250L

/**
 * What a finished bulk edit says, given how many items it actually changed.
 *
 * The zero case used to say nothing at all. Counting real changes rather than the size of the
 * selection is right — a tag every selected item already carries changes nothing, and a
 * confirmation counting the selection would report something that did not happen — but silence
 * made that outcome identical to a dead button: the selection cleared, the list did not move, and
 * nothing said why.
 *
 * Returns a [LkNotices.Message] rather than a String: the confirmations are `<plurals>`, which
 * cannot be resolved without a Context, and this runs in a ViewModel. The host resolves it.
 *
 * @param noChangeNotice resolved by the caller from `vaultlist_notice_no_bulk_change` — kept a
 *   parameter, not a module constant, so this function stays testable without an Android context.
 */
internal fun bulkNotice(
    changed: Int,
    noChangeNotice: String,
    confirmation: (Int) -> LkNotices.Message,
): LkNotices.Message =
    if (changed > 0) confirmation(changed) else LkNotices.Message.Text(noChangeNotice)

/**
 * Which ids within [targetIds] would actually change if [transform] ran against [items] — the set
 * [bulkEdit] counts, announces, and (for [VaultListViewModel.bulkTrash]) hands to the notice's
 * undo. Never the raw selection: a selection can include ids [transform] is a no-op on — a tag an
 * item already carries, a row already in Trash, a row [VaultListViewModel.bulkRestoreTrashed]
 * already brought back by hand — and treating a no-op as "changed" is wrong both for the count
 * shown and, for an undo built from it, for what gets touched. Extracted so this — the part of
 * bulk-undo fix that decides *which* ids a bulk-trash undo restores — is testable without a
 * repository or a coroutine, matching [bulkNotice] above.
 */
internal fun changedIds(
    items: List<VaultItem>,
    targetIds: Set<UUID>,
    transform: (VaultItem) -> VaultItem,
): Set<UUID> = items.asSequence()
    .filter { it.id in targetIds }
    .filter { transform(it) != it }
    .mapTo(mutableSetOf()) { it.id }

/**
 * [VaultListViewModel.bulkMoveToFolder]'s transform: files an item into [folderId], null meaning the
 * root, and leaves an item already there untouched.
 *
 * Untouched includes `updatedAt`. Stamping every selected item made [changedIds] count each one as
 * moved, so two top-level items sent to "No folder" announced "2 items removed from folder" and the
 * no-change notice was unreachable (device pass 2026-10-04) — the rule [VaultListViewModel.bulkAddTag]
 * and the drag's own commit already follow.
 */
internal fun bulkMoveTransform(folderId: UUID?, now: Instant): (VaultItem) -> VaultItem = { item ->
    if (item.folderId == folderId) item else item.copy(folderId = folderId, updatedAt = now)
}

/**
 * What dropping the already-resolved [moving] items onto [targetFolderId] — null meaning the root —
 * would do. The item half of `VaultListViewModel.acceptanceFor`, taking the items rather than
 * looking them up so a caller asking about many folders resolves them once.
 */
internal fun itemsDropAcceptance(
    moving: List<VaultItem>,
    folders: List<Folder>,
    targetFolderId: UUID?,
): LkDropAcceptance = when {
    moving.isEmpty() -> LkDropAcceptance.Rejects
    // Silent, and no write. Dropping something back where it already is is not an
    // error and should not cost a whole-vault re-encrypt.
    moving.all { it.folderId == targetFolderId } -> LkDropAcceptance.NoOp
    targetFolderId != null && folders.none { it.id == targetFolderId } -> LkDropAcceptance.Rejects
    else -> LkDropAcceptance.Accepts
}

/**
 * The folders among [folders] that a drop of [payload] would not refuse — the "Move to folder…"
 * sheet's list.
 *
 * [resolveItems] is called at most once, and not per folder: for an item payload it is a read of the
 * whole vault, and the sheet asked it inside composition for each of a few hundred folders.
 */
internal fun moveDestinations(
    folders: List<Folder>,
    payload: LkDragPayload,
    resolveItems: (Set<UUID>) -> List<VaultItem>,
    acceptanceFor: (LkDragPayload, UUID?) -> LkDropAcceptance,
): List<Folder> {
    if (payload !is ItemsDragPayload) {
        return folders.filter { acceptanceFor(payload, it.id) != LkDropAcceptance.Rejects }
    }
    val moving = resolveItems(payload.ids)
    return folders.filter { itemsDropAcceptance(moving, folders, it.id) != LkDropAcceptance.Rejects }
}

/**
 * This item as it should be once the drag that moved it is undone.
 *
 * [previous] maps each dragged id to the folder it came from. A folder that is no longer live is not
 * a place to put it back: the item would be live, filed under a trashed folder, and visible in no
 * list that browses — not in that folder, not at the root, not in Trash. It goes to the root, which
 * is always reachable (S1-05, 2026-10-02; `VaultListViewModel.bulkRestoreTrashed` gives the same
 * answer for the same reason).
 *
 * An item the drag did not move is returned untouched, as is one already where it belongs, so an
 * undo never restamps `updatedAt` on a row it left in place.
 */
internal fun VaultItem.withDragMoveUndone(
    previous: Map<UUID, UUID?>,
    liveFolders: Set<UUID>,
    at: Instant,
): VaultItem {
    if (id !in previous) return this
    val restored = previous[id]?.takeIf { it in liveFolders }
    return if (folderId == restored) this else copy(folderId = restored, updatedAt = at)
}

/**
 * Runs one reveal-phrase attempt, answering a heap with no room for it the way a wrong guess is
 * answered.
 *
 * The attempt derives an Argon2id key at the floor cost — 19 MiB — and every other in-app derivation
 * is wrapped in `guardKdfMemory` (internal to `:core:vault`, which is why this is not that). This is
 * the one an ordinary search can trigger, on the trailing edge of the debounce with nobody waiting on
 * it, and an `OutOfMemoryError` escaping from it surfaced as an uncaught exception in
 * `viewModelScope`: a crash for typing a word that matched nothing (S1-07, 2026-10-02).
 *
 * Catching an `Error` is deliberate, on the reasoning `guardKdfMemory` gives: the failed allocation
 * is the derivation's own block array, unreachable the moment the frame unwinds, so the heap
 * recovers and nothing has been written. [RevealAttemptOutcome.Rejected] is the only answer that
 * leaves the search box what it is meant to be — nothing shown, spoken or buzzed.
 */
internal suspend fun rejectingOnOutOfMemory(
    attempt: suspend () -> RevealAttemptOutcome,
): RevealAttemptOutcome = try {
    attempt()
} catch (_: OutOfMemoryError) {
    RevealAttemptOutcome.Rejected
}

/**
 * Whole seconds left before a reveal session ends, floored at zero and rounded *up* so the banner
 * shows "1" for the whole of the last second rather than sitting on "0" for it.
 *
 * Pure and clock-injected — the countdown the banner renders is decided here, and
 * `RevealSessionCountdownTest` holds it to that without a device or a frame clock.
 */
internal fun revealSecondsLeft(expiresAtElapsedMs: Long, nowElapsedMs: Long): Int {
    val remaining = expiresAtElapsedMs - nowElapsedMs
    if (remaining <= 0L) return 0
    // Ceiling division: 1 ms left is still "1 second", never "0 seconds" under a banner that is
    // demonstrably still on screen.
    return ((remaining + 999L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

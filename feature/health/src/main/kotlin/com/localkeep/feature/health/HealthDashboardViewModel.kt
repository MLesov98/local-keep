package com.localkeep.feature.health

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.health.SecurityDashboard
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.foldDiacritics
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HealthDashboardUiState(
    val loading: Boolean = true,
    @param:StringRes val error: Int? = null,
    val weakCount: Int = 0,
    val reusedCount: Int = 0,
    val oldCount: Int = 0,
    val breachedCount: Int = 0,
    /** Items naming an account another item already names. Not a password fault — see below. */
    val duplicateCount: Int = 0,
    /**
     * How many *distinct* items have a password fault: the union of breached, reused and weak.
     *
     * The categories are independent sets and one password is routinely in several of them — a weak
     * password shared by two logins is two items, sitting in both the weak set and the reused set.
     * Summing the counts turned those two into "4 things worth fixing" in a three-item vault: a
     * number the user could not reconcile with anything on screen and could never drive down by
     * fixing what they could see. Only the scan knows how far the sets overlap, so the union is
     * carried here rather than re-derived from the counts.
     */
    val affectedCount: Int = 0,
    /** Raw asset provenance. Never rendered — the footnote shows the date parsed out of it. See [BreachCorpusFootnote]. */
    val breachFilterVintage: String = "",
    /** Entries in the loaded corpus, for the footnote's size claim. A lower bound, so it may understate. */
    val breachCorpusEntries: Long = 0L,
    /** False when the corpus failed to open or parse — the screen hides the breach card entirely. */
    val breachDataAvailable: Boolean = true,
    val selectedCategory: SecurityDashboard.Category? = null,
    /** Every item the selected category names, before [categoryQuery] narrows it. */
    val categoryItems: List<VaultItem> = emptyList(),
    /** The drill-down dialog's search text. Reset whenever [selectedCategory] changes. */
    val categoryQuery: String = "",
    /** How many items the scan covered — the denominator every count below is a fraction of. */
    val scannedCount: Int = 0,
    /**
     * Distinct non-empty passwords actually looked up against the breach corpus (and scored for
     * weakness) this scan. Zero both for an empty vault and for a vault whose Logins have no
     * password — [scannedCount] alone cannot distinguish those from a real, completed check. See
     * [SecurityDashboard.Summary.passwordsChecked].
     */
    val passwordsChecked: Int = 0,
    /**
     * How far the in-progress first scan has got, 0f..1f, or null while nothing measurable has
     * happened yet, or once nothing is running.
     *
     * Threaded out of [SecurityDashboard.analyze]'s per-distinct-password zxcvbn loop, the scan's
     * dominant cost. Seeded null, not 0f, at the start of a scan (F-40): `analyze()` retries/loads
     * the breach corpus before it ever calls back, and that phase is real, unreported time — a
     * hardcoded 0f there claimed "no work done" for a phase whose size is actually unknown, which
     * is what a QA recording caught as the ring sitting stuck at 0% for ~2s before jumping straight
     * to 50% then 100%. It flips to a real, monotonically increasing fraction the instant the first
     * `onProgress` callback lands — [SecurityDashboard] knows the true total before scoring starts,
     * so from that point on it is determinate for the rest of the scan, same as before. Set only
     * while [loading] is true: a re-scan (`loading` stays false — see [refresh]) never shows the
     * scanning card this drives, so there is nothing for it to report there.
     */
    val scanProgress: Float? = null,
    /**
     * For every item [SecurityDashboard.Category.REUSED] names: how many total items (itself
     * included) share its password. See [SecurityDashboard.Summary.reusedGroupSizes]; carried here
     * so [filteredRows] can badge a reused row without re-deriving the grouping.
     */
    val reusedGroupSizes: Map<UUID, Int> = emptyMap(),
    /**
     * Live Logins this scan could not see, because they are concealed in the current session.
     *
     * The screen says so, and until 2026-09-20 it did not. With seven entries invisible the same
     * vault read `100` / "Nothing worth fixing" / "Every password here is unique, not weak, and not
     * in the offline breach set." while visible it read `88` with two reused, one weak and one
     * compromised (3A-F33). The Invisible-items screen does disclose the trade going in, but the
     * dashboard is where the claim is made and "here" was carrying the whole of the qualification.
     *
     * Zero in a reveal session, where the concealed entries *are* scanned: counted against the set
     * actually handed to the scan rather than against the leases, so the note follows what was
     * measured rather than what is hidden. Ids only — a concealed entry's content is not in the
     * snapshot — so this counts entries, not passwords, which is what the copy says.
     */
    val excludedCount: Int = 0,
) {
    /**
     * [categoryItems] narrowed by [categoryQuery], matched against title the same way the dialog
     * row displays it — the field a user would type into search is the only field they can see.
     *
     * Folded through [foldDiacritics] on both sides, the way the vault list and trash searches are.
     * A bare `contains(ignoreCase = true)` left "Café Wifi" unreachable by "cafe" here alone, and a
     * search that silently returns nothing is indistinguishable from the item not existing — on the
     * one screen a user opens precisely because something is already wrong with that item.
     */
    val filteredItems: List<VaultItem>
        get() = if (categoryQuery.isBlank()) {
            categoryItems
        } else {
            val needle = categoryQuery.trim().lowercase(Locale.ROOT).foldDiacritics()
            categoryItems.filter {
                it.title.lowercase(Locale.ROOT).foldDiacritics().contains(needle)
            }
        }

    /** [filteredItems] resolved into drill-down rows for [selectedCategory]. See [HealthCategoryRow]. */
    internal val filteredRows: List<HealthCategoryRow>
        get() = categoryRows(filteredItems, selectedCategory, reusedGroupSizes)

    /**
     * A 0–100 headline score.
     *
     * Deliberately a plain, stated formula rather than a proprietary-feeling number: each finding
     * costs a share of the score in proportion to how much of the vault it affects, weighted by how
     * badly it fails. A breach match is the worst thing on this screen (the password is known to
     * attackers), reuse is next (one breach cascades), weakness after that, and age is a nudge
     * rather than a fault — a fourteen-year-old passphrase that is long and unique is fine.
     *
     * Duplicates cost the least of all, and are here rather than absent because they *do* eventually
     * bite: change the password on one copy and autofill keeps offering the stale one. But nothing
     * about the passwords is wrong, so the penalty is a nudge below even age.
     *
     * An empty vault scores 100: there is nothing wrong with it, and a scary number on a fresh
     * install would be a lie told for engagement.
     */
    val score: Int
        get() {
            // Both nothings score 100 - an empty vault and a vault whose Logins carry no password
            // are each genuinely faultless. What must not happen is *presenting* that 100 as a
            // result: the ring takes `passwordsChecked` into account at its call site and shows a
            // dash instead, and [verdictRes] says "nothing to check" rather than "nothing worth
            // fixing". This early return is the arithmetic guard only.
            if (scannedCount <= 0 || passwordsChecked <= 0) return 100
            val n = scannedCount.toDouble()
            val penalty = (breachedCount / n) * 45 +
                (reusedCount / n) * 30 +
                (weakCount / n) * 20 +
                (oldCount / n) * 8 +
                (duplicateCount / n) * 5
            return (100 - penalty).coerceIn(0.0, 100.0).toInt()
        }

    /**
     * The one-line verdict beside the score. Names the count, never only a colour (spec §2).
     *
     * `null` means "one or more items have a password fault" — that case is a plural
     * (`affectedCount` is already on this state) and is resolved with `pluralStringResource` at
     * the call site rather than formatted here; a ViewModel-owned state never holds display text.
     */
    val verdictRes: Int?
        @StringRes get() = when {
            // `passwordsChecked` for the same reason [adviceRes] and [footnoteRes] use it: a vault
            // of password-less Logins is scanned but never scored, so every count below is 0 by
            // arithmetic rather than by anything having been checked. Gating on `scannedCount`
            // alone let that state fall through to "Nothing worth fixing" - a clean bill of health
            // issued without a single password having been looked at, on the one screen whose
            // entire job is answering "are my passwords OK". A browser CSV export reaches this
            // state routinely: `CsvColumnMapper` keeps a row that has a URL or a username, so
            // password-less Logins import as live items.
            scannedCount == 0 || passwordsChecked == 0 -> R.string.health_verdict_nothing_to_check
            affectedCount == 0 && oldCount == 0 && duplicateCount == 0 -> R.string.health_verdict_nothing_worth_fixing
            affectedCount == 0 && oldCount == 0 -> R.string.health_verdict_healthy_duplicates
            affectedCount == 0 -> R.string.health_verdict_healthy_ageing
            else -> null
        }

    /**
     * Which plural the `null` case of [verdictRes] is read from.
     *
     * Both say how many items need fixing; they differ only in the word they open with. That word
     * used to be "Good" for every non-zero count, so ten logins sharing `123456` - score 5, every
     * one breached - read "Good, with 10 things worth fixing" beside a ring reading 5 and advice
     * to treat the passwords as compromised, and TalkBack read the contradiction aloud
     * (2026-10-02). "Good" is now earned: no breach match and a score of [GOOD_VERDICT_MIN_SCORE]
     * or more. A breach match rules it out on its own, because that is the one finding the advice
     * line calls compromised rather than worth fixing, however small a share of the vault it is.
     */
    @get:PluralsRes
    val faultVerdictPluralRes: Int
        get() = if (breachedCount == 0 && score >= GOOD_VERDICT_MIN_SCORE) {
            R.plurals.health_verdict_affected_count
        } else {
            R.plurals.health_verdict_affected_count_attention
        }

    /**
     * The breach advice line, as a `<plurals>` resolved with [breachedCount], or null when the
     * advice is not about a breach. It names the passwords it is about, so a fixed singular read
     * "the flagged password" above a card counting fifteen of them. When this is non-null
     * [adviceRes] is null; exactly one of the two drives the line.
     */
    @get:PluralsRes
    val advicePluralRes: Int?
        get() = if (scannedCount > 0 && breachedCount > 0) R.plurals.health_advice_breached else null

    /**
     * What to do first, as a plain string, or null while [advicePluralRes] has the line. Ordered by
     * consequence, not by count.
     */
    val adviceRes: Int?
        @StringRes get() = when {
            scannedCount == 0 -> R.string.health_advice_empty
            breachedCount > 0 -> null
            reusedCount > 0 -> R.string.health_advice_reused
            weakCount > 0 -> R.string.health_advice_weak
            oldCount > 0 -> R.string.health_advice_old
            duplicateCount > 0 -> R.string.health_advice_duplicate
            // The breach half of this sentence is only true when a breach scan actually ran.
            // `breachedCount` is 0 both for "checked, no hits" and for "the corpus never loaded" —
            // and, just as important, for "every Login here has an empty password", which leaves
            // `breachDataAvailable` true with nothing having been looked up. `passwordsChecked > 0`
            // is what actually distinguishes a completed check from either kind of nothing-to-report.
            breachDataAvailable && passwordsChecked > 0 -> R.string.health_advice_clean_breach_checked
            else -> R.string.health_advice_clean_no_breach_data
        }

    /**
     * Which footnote to show under the breach-corpus disclosure.
     *
     * Mirrors [adviceRes]'s breach-checked branch: `breachDataAvailable` alone says the corpus is
     * loaded, not that anything was looked up in it. A fresh vault or a vault of password-less
     * Logins has a loaded corpus and zero lookups, and the footnote must say what the corpus IS
     * rather than claim it was consulted (see [R.string.health_footnote_breach_not_yet_checked]).
     */
    val footnoteRes: Int
        @StringRes get() = when {
            !breachDataAvailable -> R.string.health_footnote_breach_unavailable
            passwordsChecked > 0 -> R.string.health_footnote_breach_available_undated
            else -> R.string.health_footnote_breach_not_yet_checked
        }

    companion object {
        /**
         * The lowest [score] at which the faulted verdict may open with "Good". A judgement call,
         * not a derived figure: below it, the ring beside the word no longer supports it.
         */
        const val GOOD_VERDICT_MIN_SCORE = 80
    }
}

/**
 * The state a fresh scan begins in (F-40).
 *
 * `scanProgress` is seeded null (indeterminate), never a hardcoded 0f: [SecurityDashboard.analyze]
 * retries/loads the breach corpus before its first `onProgress` callback, and that phase is real,
 * unreported time — a QA recording caught the old 0f seed as the ring sitting stuck at 0% for ~2s
 * before jumping straight to 50% then 100%, which read as fake progress rather than a phase that
 * genuinely could not yet report. It becomes a real, monotonically increasing fraction the instant
 * [withScanProgress] is first applied.
 */
internal fun HealthDashboardUiState.startingScan(firstScan: Boolean): HealthDashboardUiState = copy(
    loading = firstScan,
    scanProgress = null,
    error = null,
)

/**
 * How many concealed entries this scan could not see.
 *
 * Not `concealedItemIds.size`. Those ids are populated whether or not a reveal session is running
 * (`VaultVisibilitySnapshot`), and in a revealed session the concealed entries **are** in
 * `liveItems` and therefore **are** scanned — so counting the leases would put "7 hidden entries
 * were not checked" under a score that had just checked all seven. Counted against the set actually
 * handed to the scan instead, so the note follows what was measured rather than what is hidden.
 *
 * Nor every concealed id that is missing from the scan. [concealedItemIds] covers trashed items and
 * every content type, and the scan only ever looks at live Logins - so a hidden note, a hidden card
 * or a hidden trashed login was counted as "not checked" though the scan never would have checked it
 * (2026-10-02). [vaultItems] is the whole live vault, concealed included, and says which ids are
 * Logins; only those can have been missed.
 *
 * A free function so that distinction is tested rather than asserted.
 */
internal fun excludedFromScan(
    concealedItemIds: Set<UUID>,
    scanned: List<VaultItem>,
    vaultItems: List<VaultItem>,
): Int {
    if (concealedItemIds.isEmpty()) return 0
    val seen = scanned.mapTo(HashSet(scanned.size), VaultItem::id)
    return vaultItems.count { it.id in concealedItemIds && it.id !in seen && !it.isDeleted && it.content is Login }
}

/**
 * Applies one `onProgress` callback from [SecurityDashboard.analyze] to [HealthDashboardUiState.scanProgress].
 *
 * A no-op once [HealthDashboardUiState.loading] is false: a superseded scan's `checkCancelled` is
 * what stops it from getting this far in the common case, but this guard means even a callback that
 * does land from a stale scan cannot paint progress over a screen that has already moved past the
 * scanning card. `total <= 0` cannot happen by [SecurityDashboard.analyze]'s own contract (it never
 * calls back with nothing to score), but is guarded here anyway rather than trusted blindly.
 */
internal fun HealthDashboardUiState.withScanProgress(scanned: Int, total: Int): HealthDashboardUiState =
    if (loading && total > 0) copy(scanProgress = scanned.toFloat() / total) else this

/**
 * One row in a category's drill-down list (F-30).
 *
 * [secondary] and [sharedWithOthersCount] are populated only for
 * [SecurityDashboard.Category.REUSED] — two reused Logins routinely share a title with nothing on
 * the row to tell them apart or say which password they share (a QA vault showed "LocalKeep
 * Autofill Test" twice). [secondary] is the item's username, the same disambiguator the vault list
 * already shows under a login's title; null when the Login has none saved, rather than a fabricated
 * placeholder. [sharedWithOthersCount] is how many OTHER items share this one's password, not
 * counting itself, so it can be dropped straight into a plural string.
 */
internal data class HealthCategoryRow(
    val item: VaultItem,
    val secondary: String? = null,
    val sharedWithOthersCount: Int? = null,
    /**
     * When the item was last saved, set only when there is no [secondary] to tell it apart by —
     * a recovery phrase or a note has no username, and two duplicates of one read as the same row.
     * Rendered by the composable, which owns the locale.
     */
    val savedAt: java.time.Instant? = null,
)

/**
 * Builds [HealthCategoryRow]s for one category's drill-down list.
 *
 * Every category's rows say which entry is which, not only Reused's: Duplicate, Weak and Compromised
 * lists were bare titles, so two copies of one entry read identically until tapped. The username is
 * the line when there is one; otherwise the saved date, so a phrase or a note still differs from its
 * twin. The shared-with count stays Reused's alone.
 */
internal fun categoryRows(
    items: List<VaultItem>,
    category: SecurityDashboard.Category?,
    reusedGroupSizes: Map<UUID, Int>,
): List<HealthCategoryRow> = items.map { item ->
    val username = (item.content as? Login)?.username?.ifBlank { null }
    HealthCategoryRow(
        item = item,
        secondary = username,
        sharedWithOthersCount = if (category == SecurityDashboard.Category.REUSED) {
            reusedGroupSizes[item.id]?.minus(1)?.takeIf { it > 0 }
        } else {
            null
        },
        // The epoch is a missing timestamp, not a save date: an item stored with updatedAt 0 drew
        // "Saved Jan 1, 1970", a statement about when it was saved that is simply false.
        savedAt = if (username == null) item.updatedAt.takeIf { it.toEpochMilli() > 0 } else null,
    )
}

/**
 * The number of items with a password fault, counted once each.
 *
 * A union rather than a sum of the category counts: [SecurityDashboard] buckets independently, so an
 * item that is both weak and reused is in two sets and would otherwise be reported twice.
 * Duplicates and age are excluded on purpose — neither says anything is wrong with the password, and
 * the verdict they feed says so in its own words.
 */
internal fun SecurityDashboard.Summary.affectedItemCount(): Int =
    (breached.itemIds + reused.itemIds + weak.itemIds).size

/**
 * Publishes a finished scan into the screen state as one atomic update.
 *
 * This was `state.value = state.value.copy(...)` from `Dispatchers.Default`, with the drill-down
 * list built from `state.value.selectedCategory` inside the argument list - after the receiver had
 * been read and before it was written back. The main thread writes the same flow when the user opens
 * a category or types in its search box, and anything it wrote in between was overwritten: the
 * dialog just opened snapped shut, the last typed character reverted (2026-10-02). `update` retries
 * against the newer state instead, so [categoryItemsFor] runs again on a retry and must be safe to.
 *
 * [categoryItemsFor] re-derives the open drill-down from the new summary, because it is filtered by
 * the ids the *scan* found: an item the user has just fixed stays in "Weak passwords" until the list
 * is rebuilt, and the fix appears to have done nothing at exactly the moment the user is looking
 * for confirmation that it did.
 */
internal fun MutableStateFlow<HealthDashboardUiState>.publishScan(
    summary: SecurityDashboard.Summary,
    excluded: Int,
    categoryItemsFor: (SecurityDashboard.Category) -> List<VaultItem>,
) {
    update { current ->
        current.copy(
            loading = false,
            scanProgress = null,
            weakCount = summary.weak.count,
            reusedCount = summary.reused.count,
            oldCount = summary.old.count,
            breachedCount = summary.breached.count,
            duplicateCount = summary.duplicate.count,
            affectedCount = summary.affectedItemCount(),
            breachFilterVintage = summary.breachFilterVintage,
            breachCorpusEntries = summary.breachCorpusEntries,
            breachDataAvailable = summary.breachDataAvailable,
            scannedCount = summary.scannedCount,
            passwordsChecked = summary.passwordsChecked,
            reusedGroupSizes = summary.reusedGroupSizes,
            excludedCount = excluded,
            categoryItems = current.selectedCategory?.let(categoryItemsFor) ?: current.categoryItems,
        )
    }
}

@HiltViewModel
class HealthDashboardViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val securityDashboard: SecurityDashboard,
) : ViewModel() {

    private val _state = MutableStateFlow(HealthDashboardUiState())
    val state: StateFlow<HealthDashboardUiState> = _state.asStateFlow()

    private var lastSummary: SecurityDashboard.Summary? = null

    init {
        refresh()
        // The scan is a snapshot, and until this it was a snapshot of whenever the ViewModel
        // happened to be constructed. Fixing a weak password and coming back showed the same score,
        // the same counts, and the item still sitting in the "Weak passwords" list — for the rest of
        // the session, because the ViewModel survives on the back stack and nothing re-ran the scan.
        // The screen that exists to tell you whether you have fixed anything was structurally
        // incapable of saying yes.
        //
        // One collector, not two (F4-1). This used to also `collect` `repository.onSaved` directly
        // to catch a commit from anywhere — this screen's own drill-down, the vault list, bulk
        // actions, an import, autofill saving a new login — alongside this `presentationState`
        // collector, kept for the one change `onSaved` cannot see: a hidden lease reaching its
        // expiry with nothing committed to report it. But `presentationState` (VaultRepository.kt)
        // already recomputes on every `onSaved` emission as well as on lock/unlock and on that
        // expiry, so the direct collector was a second delivery of the same save — one commit ran
        // the ~19.6s scan twice for a single edit. `presentationState` alone covers every case the
        // pair used to.
        //
        // Guarded on Unlocked, and that is not decoration: `presentationState` emits `Locked` on
        // the way to the unlock destination, and refreshing there would scan a locked repository
        // and paint the scan-failed error over the screen as it leaves.
        viewModelScope.launch {
            repository.presentationState.collect { presentation ->
                if (presentation is VaultPresentationState.Unlocked) refresh()
            }
        }
    }

    /** The scan in flight, so a newer one can cancel it. See [refresh]. */
    private var scanJob: Job? = null

    /**
     * Re-scans the live vault. zxcvbn scoring + breach lookups run off the main thread.
     *
     * Only ever one scan at a time. `onSaved` fires once per vault commit, so an import or a bulk
     * edit calls this several times in a row; each call used to start an independent coroutine that
     * wrote `lastSummary` and the whole UI state when it happened to finish. Scan duration tracks
     * item count, so the earlier scan is not reliably the first to finish — an older, smaller set of
     * counts routinely landed on top of the newer one and stayed there until the next save. On the
     * one screen whose job is to tell you whether your fix took effect.
     */
    fun refresh() {
        val firstScan = lastSummary == null
        // Only the *first* scan is allowed to blank the screen. A re-scan has last scan's numbers
        // to keep showing, and `loading` swaps the whole dashboard for a scanning card — so
        // flipping it on every vault save would replace a screen the user is reading with a
        // spinner, repeatedly, to arrive at almost the same numbers.
        _state.update { it.startingScan(firstScan = firstScan) }
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                // Through the projection, not getAllItems(): a concealed item must not surface in the
                // Weak/Reused/Old/Breached tallies or their drill-downs. liveItems conceals in a normal
                // session and admits in a reveal session, so health matches the vault list either way.
                val snapshot = repository.visibilitySnapshot()
                val items = snapshot.liveItems
                // getAllItems() only for the types of the concealed ids: nothing from it reaches the
                // scan or the UI, and the projection above carries ids alone for what it conceals.
                val excluded = if (snapshot.concealedItemIds.isEmpty()) {
                    0
                } else {
                    excludedFromScan(snapshot.concealedItemIds, items, repository.getAllItems())
                }
                val summary = securityDashboard.analyze(
                    items,
                    onProgress = { scanned, total ->
                        // Guarded on `loading` inside withScanProgress, not on this coroutine's own
                        // activeness: a superseded scan's checkCancelled below is what stops it from
                        // getting this far in the common case, but the guard here means even a
                        // callback that does land from a stale scan cannot paint progress over a
                        // screen that has already moved past the scanning card.
                        _state.update { it.withScanProgress(scanned, total) }
                    },
                    // F4-1: without this, cancelling a superseded scan only ever stopped its *result*
                    // from being published (the ensureActive() below) — the zxcvbn loop itself, the
                    // scan's dominant cost, ran to completion regardless, because analyze() is a
                    // plain synchronous call with no suspension point of its own for `scanJob?.cancel()`
                    // to land on. Checking here gives it one.
                    checkCancelled = { ensureActive() },
                )
                // Cancellation is cooperative and nothing between here and the state write
                // suspends, so a scan that has already been superseded arrives here uninterrupted
                // and would still publish its results. Asking explicitly is what makes the cancel
                // above mean anything.
                ensureActive()
                lastSummary = summary
                _state.publishScan(summary, excluded) { itemsIn(summary, it) }
            } catch (e: CancellationException) {
                // A newer scan cancelled this one. Rethrow rather than let the handler below report
                // it: being superseded is not a scan failure, and writing `loading = false` plus an
                // error from a coroutine that has been replaced would clobber the live scan's state.
                throw e
            } catch (_: Exception) {
                // Not `e.message`. What reaches here is written for a log — "vault is locked" from
                // requireVault's own check, a decompression failure naming the breach-set file —
                // and this string is rendered on screen and read out by TalkBack. An auto-lock
                // under a running scan is the one cause the user can do anything about, so that is
                // what it names.
                _state.update {
                    it.copy(
                        loading = false,
                        scanProgress = null,
                        error = R.string.health_error_scan_failed,
                    )
                }
            }
        }
    }

    /**
     * Shows the items behind one category (the "tap-through to a filtered vault list").
     *
     * Guarded, like every other read in this class. [itemsIn] goes to `getAllItems`, which asserts
     * the vault is unlocked with `check` — and this screen is one someone reads for a while, so an
     * auto-lock landing under it is the ordinary case rather than the exotic one. The lock does
     * navigate to the unlock screen, but the tap and the navigation are both main-thread messages
     * and nothing orders them: a tap already queued when the lock lands is delivered first, and an
     * IllegalStateException off a tap handler is a crash, not an error message.
     */
    fun selectCategory(category: SecurityDashboard.Category) {
        val summary = lastSummary ?: return
        val items = try {
            itemsIn(summary, category)
        } catch (_: Exception) {
            // No error banner. The vault locking is not a failure of this screen, and the unlock
            // screen is already on its way — a message about the security dashboard would arrive on
            // top of it describing something the user did not do.
            return
        }
        // categoryQuery resets to "" here: a query typed into the previous category's dialog
        // (e.g. "Weak") has no business narrowing an unrelated one that just opened.
        _state.update { it.copy(selectedCategory = category, categoryItems = items, categoryQuery = "") }
    }

    /** Narrows the open drill-down dialog by title. No-op once [selectedCategory] is null. */
    fun onCategoryQueryChanged(query: String) {
        _state.update { it.copy(categoryQuery = query) }
    }

    /** The live vault items one category of [summary] names. */
    private fun itemsIn(
        summary: SecurityDashboard.Summary,
        category: SecurityDashboard.Category,
    ): List<VaultItem> {
        val ids = when (category) {
            SecurityDashboard.Category.WEAK -> summary.weak.itemIds
            SecurityDashboard.Category.REUSED -> summary.reused.itemIds
            SecurityDashboard.Category.OLD -> summary.old.itemIds
            SecurityDashboard.Category.BREACHED -> summary.breached.itemIds
            SecurityDashboard.Category.DUPLICATE -> summary.duplicate.itemIds
        }
        // Same projection source as the scan: a drill-down id left over from a prior revealed scan
        // must not resurface a now-concealed item after conceal/lock.
        return repository.visibilitySnapshot().liveItems.filter { it.id in ids }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedCategory = null, categoryItems = emptyList(), categoryQuery = "") }
    }

}

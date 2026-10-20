package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.util.UUID

/**
 * Aggregates the four password-health categories over the live (decrypted, in-memory)
 * vault. Scoped to Login items' passwords only (roadmap §B) — Card PINs/CVVs and
 * Identity fields are not part of "password health". Takes its [BreachFilter] by
 * constructor injection (`HealthModule` wires it) rather than reading a singleton —
 * this class has no Android/DI knowledge of its own.
 */
class SecurityDashboard(
    private val breachFilter: BreachFilter,
    /**
     * Weak verdicts this session has already computed, so a re-scan only pays for passwords that
     * changed. See [StrengthCache] for why its keys are HMAC tags under an ephemeral key and what
     * has to call [forgetStrengthScores].
     *
     * Declared here rather than at the end, because [isBreached] has to stay the last parameter:
     * a trailing lambda at a call site (`SecurityDashboard(filter) { ... }`) binds to whichever
     * function-typed parameter is last, and several tests are written that way.
     */
    private val strengthCache: StrengthCache = StrengthCache(),
    /**
     * The weak-password verdict for one password, defaulting to the real analyzer. Injected only so
     * a test can count how often it is asked: scoring every item and scoring every distinct password
     * produce identical summaries, so the call count is the one observable difference between them.
     */
    private val isWeakPassword: (String) -> Boolean = StrengthAnalyzer::isWeak,
    /**
     * The breach verdict for one password, defaulting to [breachFilter]. Injected for exactly the
     * reason above and no other: probing every item and probing every distinct password produce
     * identical summaries, so the call count is the only observable difference between them, and
     * [BreachFilter] is final.
     */
    private val isBreached: (String) -> Boolean = breachFilter::isPotentiallyBreached,
) {

    enum class Category { WEAK, REUSED, OLD, BREACHED, DUPLICATE }

    /**
     * Drops every remembered strength verdict and wipes the key they were held under.
     *
     * Called when the vault locks — see `feature/health`'s `forgetStrengthScoresOnLock`, which is
     * what makes the cache's lifetime the session's rather than the process's.
     */
    fun forgetStrengthScores() = strengthCache.forget()

    data class CategoryResult(val category: Category, val itemIds: Set<UUID>) {
        val count: Int get() = itemIds.size
    }

    data class Summary(
        val weak: CategoryResult,
        val reused: CategoryResult,
        val old: CategoryResult,
        val breached: CategoryResult,
        /**
         * The same account entered twice — not a password problem at all, which is why it is last
         * and why it costs the score less than anything else. See [DuplicateDetector].
         */
        val duplicate: CategoryResult,
        /**
         * Raw provenance string baked into the asset by the maintainer-run builder.
         *
         * Deliberately NOT shown to the user: it names a corpus by its upstream repo path
         * ("SecLists Pwdb_top-10000000, 2026-07"), which answers a question no user asked and
         * cannot act on. The footnote states [breachCorpusEntries] and the date parsed out of this
         * instead. Kept on the summary because it is the only record of where the bytes came from,
         * which is what an auditor reading a release wants.
         */
        val breachFilterVintage: String,
        /** Entries in the loaded corpus, for the footnote's size claim. A lower bound — see [BreachFilter.filterEntryCount]. */
        val breachCorpusEntries: Long,
        /** False when the corpus failed to open or to parse — not "scanned, zero hits". */
        val breachDataAvailable: Boolean,
        /**
         * How many items were actually scanned: live Logins, which is the only thing this scan
         * looks at.
         *
         * Reported here rather than left to the caller because the caller cannot know it without
         * re-deriving the filter below — and when it guessed (`items.size`, the whole vault) every
         * count above became a fraction of the wrong denominator. A vault of 5 logins and 45 notes,
         * two of the logins weak and one reused, scored 98 instead of 86.
         */
        val scannedCount: Int,
        /**
         * Distinct non-empty passwords actually looked up against [breachFilter] (and scored for
         * weakness) this scan — the same set as `distinctPasswords` below.
         *
         * [scannedCount] counts live Login *items*, which can be nonzero while this is zero: a
         * password-less Login (routine after a browser import that drops passwords) is scanned but
         * contributes no password to check. [breachDataAvailable] alone cannot tell that apart from
         * "the corpus loaded and found nothing" — both leave [breachDataAvailable] true and every
         * count at zero — so a caller that wants to know whether a breach lookup actually ran needs
         * this, not just `breachDataAvailable`.
         */
        val passwordsChecked: Int,
        /**
         * For every item in [reused]: how many total items (itself included) share its password.
         *
         * F-30: two reused Logins routinely share a title (a QA vault had "LocalKeep Autofill Test"
         * twice), and [reused] alone — a flat id set — cannot say which items are reused *with each
         * other* versus reused as part of an unrelated pair. This is that grouping, flattened to the
         * one number a drill-down row needs: "shares a password with N others" reads directly off
         * `size - 1`. Empty for every other category.
         */
        val reusedGroupSizes: Map<UUID, Int> = emptyMap(),
    )

    /**
     * Scans all non-trashed Login items and buckets them into weak / reused / old / potentially-breached.
     *
     * This used to take an `includeBreached` flag, because breach matching was the one Pro-gated
     * half of the scan: weak and reused are arithmetic over the vault the user already has, while
     * the corpus is a shipped, maintained artefact. That gate is gone — breach matching is free in
     * both flavours (see the 2026-07-29 tier table, which lists it in neither column) — so the flag
     * had exactly one live value and every branch on it was dead.
     */
    fun analyze(
        items: List<VaultItem>,
        /**
         * Reports the strength pass's progress, once per distinct password scored: `scanned` counts
         * up to `total`, and `total` is known and reported before the first call — the caller never
         * has to guess a denominator. Default no-op; a caller that does not care about progress (every
         * test, and any future non-UI caller) pays nothing for it.
         */
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
        /**
         * Called once per distinct password, before it is scored, so a caller running this off a
         * cancellable coroutine can stop the loop instead of burning CPU on a scan whose result will
         * be discarded. `analyze()` itself has no suspension point of its own — a `Job.cancel()`
         * around a call to it cannot interrupt it — so this is the only seam a caller has to make
         * cancellation actually stop the work, not just stop the *publishing* of its result. Default
         * no-op, so every existing caller is unaffected.
         */
        checkCancelled: () -> Unit = {},
    ): Summary {
        // ensureLoaded() up front, unconditionally — isPotentiallyBreached() below only runs (and
        // so only lazily loads) when there's at least one non-empty-password Login item. An empty
        // (or all-Note) vault would otherwise leave hasData/filterVintage reporting "no data" on a
        // build with a real asset, which is wrong — hasData must reflect whether this build HAS
        // breach data, not whether analyze() happened to need to consult it this call.
        // Once per scan, a failure that could have been a blip gets another go. Without this, a
        // single unlucky load — an import runs several scans back to back — left every later scan
        // in the process reporting no breach data, and the screen stated that as a fact about the
        // device. A corrupt or absent asset is not retried; see retryLoadIfTransientlyFailed.
        breachFilter.retryLoadIfTransientlyFailed()
        breachFilter.ensureLoaded()

        val loginItems = items.filter { !it.isDeleted && it.content is Login }

        fun password(item: VaultItem) = (item.content as Login).password

        // One zxcvbn measurement per DISTINCT password, not one per item. The verdict is a pure
        // function of the password, so fanning one measurement back out over every item that shares
        // it reports exactly the same set as measuring each item separately — while a vault where
        // the same password is entered a dozen times (which is precisely the vault this screen
        // exists to complain about) pays for it once. Measuring is the expensive half by a wide
        // margin: zxcvbn's matching is superlinear in length, and a large vault spent tens of
        // seconds here while the reuse, age and breach passes stayed cheap.
        //
        // Cached across scans as well, through [strengthCache] — this used to be refused here, and
        // the refusal was about lifetime rather than about caching: a cross-scan map keyed by
        // plaintext, or by a bare hash sitting next to the salt that made it, would be an
        // offline-crackable artefact surviving exactly the state (locked) in which nothing
        // password-derived is supposed to exist. [StrengthCache] keys by an HMAC tag under an
        // ephemeral session key and wipes that key on lock, which answers the lifetime objection
        // without giving up the saving. The saving is the whole scan: this screen re-scans on every
        // vault commit, so editing one login re-measured every other password in the vault (1B-F6,
        // 221.8 s at 12 148 items). The first scan is unchanged.
        // Materialized rather than left as a lazy sequence: [onProgress] reports the total before
        // scoring starts (F4-2 — the total is known here, so the caller never has to show an
        // indeterminate spinner over a job whose size it could have been told), which means the size
        // has to exist before the loop that reports it does.
        val distinctPasswords = loginItems.asSequence()
            .map { password(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        val totalToScore = distinctPasswords.size
        var scoredCount = 0
        // Read once, before the loop: a lock that lands mid-scan must stop this scan from caching
        // anything further, which only works if the scan knows which session it started in.
        val cacheGeneration = strengthCache.generation()
        val weakByPassword = distinctPasswords.associateWith { pwd ->
            // F4-1: checked once per distinct password — this loop's own unit of work — so a scan
            // superseded mid-flight also stops doing the work, rather than running to completion
            // regardless. HealthDashboardViewModel.refresh already stops a stale result from being
            // *published* (its ensureActive() call after analyze() returns), but analyze() has no
            // suspension point of its own for a coroutine cancellation to land on, so without this
            // check the CPU cost was paid in full either way.
            checkCancelled()
            // Progress still counts every distinct password, cached or not: `total` was reported
            // before the loop started and a run that skipped its cached entries would stop short
            // of it, which is a progress bar that never reaches the end.
            val isWeak = strengthCache.getOrPut(pwd, cacheGeneration, isWeakPassword)
            scoredCount++
            onProgress(scoredCount, totalToScore)
            isWeak
        }

        val weakIds = loginItems
            .filter { item -> weakByPassword[password(item)] == true }
            .map { it.id }
            .toSet()

        // Before reuse, because reuse needs it: a password shared only by copies of one account is a
        // duplicate, not a reuse (2026-10-02).
        val duplicateGroups = DuplicateDetector.findDuplicates(items).values

        val reusedGroups = ReuseOldDetector.findReused(items, sameAccount = duplicateGroups)
        val reusedIds = reusedGroups.values.flatten().map { it.id }.toSet()
        // Item id -> size of the group it was found in (itself included) — see [Summary.reusedGroupSizes].
        val reusedGroupSizes = reusedGroups.values
            .flatMap { group -> group.map { it.id to group.size } }
            .toMap()

        val oldIds = ReuseOldDetector.findOld(items).map { it.id }.toSet()

        // Flattened and deduped: findDuplicates keys an item once per host it lists, so one item can
        // legitimately appear in more than one group. The dashboard wants "how many items are
        // affected", not "how many groups exist".
        val duplicateIds = duplicateGroups.flatten().map { it.id }.toSet()

        // isPotentiallyBreached() is safe to call unconditionally: with no data it just returns
        // false for everything, so breachedIds is correctly empty — breachDataAvailable is the
        // separate signal for "there's no data to show a card for" vs. "zero hits".
        //
        // Deduped over `distinctPasswords` for the same reason `weakByPassword` above is, and it
        // reports the same set for the same reason: the verdict is a pure function of the password.
        // A probe is far cheaper than a zxcvbn measurement — a SHA-256, a multiply and a bounded
        // Rice decode — so this is not the fix for a slow scan; it is the pass being written the
        // way its neighbour already is, so that the vault with the most reuse stops being the one
        // that repeats the most lookups. An empty password is absent from the map and so scores
        // false, which is the `isNotEmpty()` guard this used to carry.
        val breachedByPassword = distinctPasswords.associateWith { isBreached(it) }

        val breachedIds = loginItems
            .filter { item -> breachedByPassword[password(item)] == true }
            .map { it.id }
            .toSet()

        return Summary(
            weak = CategoryResult(Category.WEAK, weakIds),
            reused = CategoryResult(Category.REUSED, reusedIds),
            old = CategoryResult(Category.OLD, oldIds),
            breached = CategoryResult(Category.BREACHED, breachedIds),
            duplicate = CategoryResult(Category.DUPLICATE, duplicateIds),
            breachFilterVintage = breachFilter.filterVintage(),
            breachCorpusEntries = breachFilter.filterEntryCount(),
            breachDataAvailable = breachFilter.hasData,
            scannedCount = loginItems.size,
            passwordsChecked = totalToScore,
            reusedGroupSizes = reusedGroupSizes,
        )
    }
}

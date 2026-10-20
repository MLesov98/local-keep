package com.localkeep.core.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val PRODUCT_ID_PRO_UNLOCK = "pro_unlock"

private const val LOG_TAG = "LocalKeepBilling"

/** Acknowledge attempts before falling back to the next launch's [PlayBillingEntitlement.restore]. */
private const val ACK_ATTEMPTS = 3

/** Startup reconciliation attempts before leaving the retry to the next purchase, restore or
 * launch. */
private const val SYNC_ATTEMPTS = 4

/** Gap after the first failed reconciliation, doubled each round (10s, 20s, 40s). Long enough to
 * outlast the connectivity blips that cause most of these, short enough to land inside a session. */
private const val SYNC_RETRY_DELAY_MS = 10_000L

@Singleton
class PlayBillingEntitlement @Inject constructor(
    @ApplicationContext context: Context,
) : Entitlement, PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = EntitlementCache(context)
    private val connection = BillingConnectionManager(context, purchasesUpdatedListener = this)

    private val _isPro = MutableStateFlow(false)
    override val isPro: StateFlow<Boolean> = _isPro

    /** Set only after the cached value is in [_isPro], never before — see [Entitlement.isProLoaded]. */
    private val _isProLoaded = MutableStateFlow(false)
    override val isProLoaded: StateFlow<Boolean> = _isProLoaded
    override val canPurchase: Boolean = true

    /**
     * Play's localised price, once Play has told us. Null until then, and null if it never does.
     *
     * `queryProUnlockDetails` already fetched `ProductDetails` for the purchase flow and threw the
     * price away, so the app showed a euro literal to every store on earth. This keeps it. It is
     * only ever set from `formattedPrice`, never from a constant — a fallback amount here would
     * reintroduce exactly the defect the field exists to remove, in a place harder to notice.
     */
    private val _displayPrice = MutableStateFlow<String?>(null)
    override val displayPrice: StateFlow<String?> = _displayPrice

    /** Bridges the async [onPurchasesUpdated] callback back into the in-flight [purchase] call.
     * Null when no purchase flow is in progress. */
    private var pendingResult: CompletableDeferred<PurchaseFlowOutcome>? = null

    /** The acknowledge-and-grant started by the last PURCHASED callback. [purchase] joins it so its
     * return value describes what actually happened rather than what was about to. */
    private var unlockJob: Job? = null

    /** Whether Play has ever actually answered a purchase query in this process. False means
     * [isPro] is only the offline cache talking, which is what makes a later retry worth doing. */
    @Volatile
    private var reconciledWithPlay = false

    init {
        // Single coroutine so the two steps are ordered: reflect the cached value first (offline,
        // instant), THEN reconcile with Play. Two independent coroutines raced — if the cache read
        // finished after the sync, a stale cached `true` could stomp a freshly-synced `false` and
        // silently re-grant a revoked/refunded entitlement until the next restart. Sequencing makes
        // the authoritative Play result always win.
        scope.launch {
            _isPro.value = runCatching { cache.isProCached.first() }
                .onFailure { cause -> Log.w(LOG_TAG, "entitlement cache unreadable", cause) }
                .getOrDefault(false)
            _isProLoaded.value = true
            reconcileAtStartup()
        }
    }

    override suspend fun purchase(activity: Activity): PurchaseOutcome {
        val client = ensureReady(USER_ACTION_CONNECT) ?: return PurchaseOutcome.Failed

        // Play has never answered, so `isPro` is the offline cache and may simply be wrong. Ask now
        // rather than sending an owner into a billing flow that can only come back
        // ITEM_ALREADY_OWNED: buying on another device, or reinstalling after the startup
        // reconciliation failed, arrives here looking exactly like a free user.
        if (!reconciledWithPlay && syncFromPlaySafely()) return PurchaseOutcome.Purchased

        val details = queryProUnlockDetails(client) ?: return PurchaseOutcome.Failed
        // Misconfigured product.
        details.oneTimePurchaseOfferDetails ?: return PurchaseOutcome.Failed

        // One purchase flow at a time. A second caller used to overwrite `pendingResult`, so Play's
        // callback for the *first* purchase completed the *second* caller's deferred and the first
        // one waited forever — and `unlockJob` was overwritten the same way, so a caller could end
        // up joining a job belonging to someone else's purchase. Two Pro buttons on screen at once
        // (the Security card and a locked item type) makes the double tap that triggers it ordinary
        // rather than exotic.
        //
        // Refusing the second call is right rather than merely convenient: Play itself only shows
        // one billing sheet, so the second flow was never going to happen. The caller stays silent
        // about it — the first flow is the one on screen.
        if (pendingResult?.isActive == true) return PurchaseOutcome.AlreadyInFlight

        val deferred = CompletableDeferred<PurchaseFlowOutcome>()
        pendingResult = deferred

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .build()

        val launchResult = client.launchBillingFlow(activity, params)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            pendingResult = null
            // Play refuses the launch outright when the account already owns a one-time product,
            // and that arrives here rather than through the listener — the sheet never opens, so
            // `onPurchasesUpdated` is never called. Reporting it as Failed sent the user to
            // "check your connection and try again", which is wrong twice: the connection is fine,
            // and the retry returns this same code every time.
            if (classifyPurchaseResponse(launchResult.responseCode) == PurchaseResponseKind.AlreadyOwned) {
                return resolveAlreadyOwned()
            }
            return PurchaseOutcome.Failed
        }

        return when (deferred.await()) {
            // The unlock itself is NOT done here — [onPurchasesUpdated] owns it, on a scope that
            // outlives this caller. All that is left is to report truthfully, which means waiting
            // for that work to finish rather than assuming it did.
            is PurchaseFlowOutcome.Purchased -> {
                unlockJob?.join()
                if (_isPro.value) PurchaseOutcome.Purchased else PurchaseOutcome.Failed
            }
            PurchaseFlowOutcome.UserCancelled -> PurchaseOutcome.Cancelled
            // Not yet PURCHASED. Play will fire the listener again when it clears, and that path
            // unlocks on its own — it no longer needs anyone to still be awaiting here.
            PurchaseFlowOutcome.Pending -> PurchaseOutcome.Pending
            PurchaseFlowOutcome.AlreadyOwned -> resolveAlreadyOwned()
            is PurchaseFlowOutcome.Failed -> PurchaseOutcome.Failed
        }
    }

    /**
     * Turns `ITEM_ALREADY_OWNED` into either an unlock or an honest dead-end explanation.
     *
     * The re-query is the whole point. Code 7 means *some* Play account owns the unlock, and the
     * app's own view of ownership is stale by definition at that moment — it was built from
     * `queryPurchasesAsync` at startup, possibly against a different account than the one the user
     * just picked in the billing sheet. Asking again is authoritative, and when it finds the
     * purchase the user gets Pro without touching anything: [PurchaseOutcome.Purchased] is silent
     * because the `isPro` flip announces itself.
     *
     * When it does not — the owning account is genuinely not the one Play is signed into here —
     * there is nothing this code can do, and saying so is the fix. Before this existed the answer
     * was "The purchase could not be started. Check your connection and try again", which sent a
     * user with a working connection into an unbounded retry loop against an error that is
     * deterministic. `AlreadyOwned` carries copy pointing at Restore purchases instead.
     *
     * `syncFromPlaySafely` rather than `syncFromPlay`: an unreachable Play here must not throw out
     * of a purchase call, and it must not revoke Pro either — that helper keeps the cached value on
     * failure, which is the same rule the startup path follows.
     */
    private suspend fun resolveAlreadyOwned(): PurchaseOutcome =
        alreadyOwnedOutcome(syncGrantedPro = syncFromPlaySafely())

    override suspend fun restore(): Boolean = syncFromPlaySafely(USER_ACTION_CONNECT)

    // --- PurchasesUpdatedListener ---

    /**
     * **This, and not [purchase], is what grants Pro.**
     *
     * The unlock used to be a side effect of whoever happened to be awaiting [pendingResult], which
     * meant it was only as durable as that caller's coroutine. Two ordinary situations lost a paid
     * purchase outright, and an unacknowledged purchase is auto-refunded by Google after three days:
     *
     * - Play's sheet runs in another process, so opening it backgrounds LocalKeep and starts the
     *   auto-lock grace timer — 5s on the Aggressive preset. Picking a payment method beats that.
     *   The lock pops the back stack, every nav-scoped ViewModel is cleared, and the `await()` is
     *   cancelled. Play then completed into a deferred nobody was listening to.
     * - A PENDING purchase (cash at a kiosk, a bank redirect, family approval) resolves minutes or
     *   hours later. That second callback arrives with no purchase call in flight at all.
     *
     * So acknowledging is unconditional and runs on [scope], which is tied to the process rather
     * than to any screen. [pendingResult] is now only how the in-flight caller learns the outcome.
     */
    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val outcome = when (classifyPurchaseResponse(result.responseCode)) {
            PurchaseResponseKind.Ok -> {
                val purchase = purchases?.firstOrNull { it.products.contains(PRODUCT_ID_PRO_UNLOCK) }
                when {
                    purchase == null -> PurchaseFlowOutcome.Failed(result)
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED -> PurchaseFlowOutcome.Purchased(purchase)
                    purchase.purchaseState == Purchase.PurchaseState.PENDING -> PurchaseFlowOutcome.Pending
                    else -> PurchaseFlowOutcome.Failed(result)
                }
            }
            PurchaseResponseKind.UserCancelled -> PurchaseFlowOutcome.UserCancelled
            // Not a failure: the account owns it. `purchase()` re-queries on this and unlocks when
            // the re-query finds it, so the user who owns Pro on the account they just chose gets
            // it here rather than being told to check their connection.
            PurchaseResponseKind.AlreadyOwned -> PurchaseFlowOutcome.AlreadyOwned
            PurchaseResponseKind.Failed -> PurchaseFlowOutcome.Failed(result)
        }
        // Assigned before the deferred is completed, so a caller that resumes immediately still
        // sees the job it needs to join.
        if (outcome is PurchaseFlowOutcome.Purchased) {
            unlockJob = scope.launch { acknowledgeAndUnlock(outcome.purchase) }
        } else if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            // Play also delivers refunds and revocations through this callback. It is NOT an
            // inventory, though — it reports the purchases of the flow that was just launched — so
            // an absent product here cannot be read as "no longer owned" without taking Pro away
            // from a paying user who simply cancelled. Re-query instead, which is authoritative,
            // and let syncFromPlay decide. Previously revocation was only ever noticed at process
            // start or on an explicit Restore, so a refunded user kept Pro for the whole session.
            scope.launch { syncFromPlaySafely() }
        }
        pendingResult?.complete(outcome)
        pendingResult = null
    }

    // --- internals ---

    /** Connects within [budget] — see [ConnectBudget]. `null` means "can't reach Play right now" —
     * callers treat that as "purchase/restore unavailable", never as "not Pro". */
    private suspend fun ensureReady(budget: ConnectBudget = BACKGROUND_CONNECT): BillingClient? {
        val client = connection.billingClient
        if (client.isReady) return client
        val connected = connectWithin(budget) {
            connection.connectOnce().responseCode == BillingClient.BillingResponseCode.OK
        }
        return if (connected) client else null
    }

    private suspend fun queryProUnlockDetails(client: BillingClient): ProductDetails? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_PRO_UNLOCK)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        val details = result.productDetailsList?.firstOrNull()
        // Captured on the way past rather than through a second query: this is the only call that
        // asks Play about the product, and it already runs before the purchase sheet opens.
        // Left untouched when Play returns no offer details, so a partial answer cannot blank a
        // price we had already learned.
        details?.oneTimePurchaseOfferDetails?.formattedPrice?.let { _displayPrice.value = it }
        return details
    }

    /**
     * Acknowledges the purchase and grants Pro. Idempotent — safe to call from the listener, from
     * the startup sync, or twice for the same token.
     *
     * The grant is deliberately **not** conditional on the acknowledgement landing. The user has
     * paid; withholding the thing they bought because a network call failed is the wrong way to be
     * wrong. What the failure does cost is the three-day acknowledgement window, so the retry is
     * real (three attempts) and [syncFromPlay] re-acknowledges anything still outstanding at the
     * next launch, which is the actual safety net.
     */
    private suspend fun acknowledgeAndUnlock(purchase: Purchase) {
        val client = if (purchase.isAcknowledged) null else ensureReady()
        if (client != null) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            for (attempt in 0 until ACK_ATTEMPTS) {
                // non-consumable INAPP: acknowledge, never consume
                val result = client.acknowledgePurchase(ackParams)
                if (result.responseCode == BillingClient.BillingResponseCode.OK) break
                if (attempt < ACK_ATTEMPTS - 1) delay(1_000L shl attempt) // 1s, then 2s
            }
        }
        _isPro.value = true
        cache.setPro(true)
    }

    /**
     * The startup half of the [init] sequence, and the only reconciliation nobody has to ask for.
     *
     * It has to be guarded, because this coroutine has no exception handler: anything thrown here
     * reaches the thread's default handler and takes the process down at startup, and the cache read
     * failing is exactly when reaching Play matters most. Guarding alone was not enough, though —
     * one silent attempt left [isPro] sitting on whatever the cache said (`false` after a reinstall
     * of a build the user already paid for) with no second try until the process restarted. So the
     * failure is logged and retried on a backoff, and [reconciledWithPlay] carries the "Play never
     * answered" fact past this function so [purchase] can retry later in the session.
     */
    private suspend fun reconcileAtStartup() {
        retryWithBackoff(SYNC_ATTEMPTS, SYNC_RETRY_DELAY_MS) {
            syncFromPlaySafely()
            reconciledWithPlay
        }
    }

    /** [syncFromPlay] with the throw contained and recorded. Every caller runs on [scope], which
     * has no exception handler, so a billing failure must never be what ends the process. Answers
     * with the last known entitlement when the sync could not complete. */
    private suspend fun syncFromPlaySafely(budget: ConnectBudget = BACKGROUND_CONNECT): Boolean =
        runCatching { syncFromPlay(budget) }
            .onFailure { cause -> Log.w(LOG_TAG, "Play entitlement sync failed", cause) }
            .getOrDefault(_isPro.value)

    /**
     * Single source-of-truth reconciliation: ask Play what the account owns, mirror it into
     * [isPro] + [EntitlementCache]. Used at startup AND by the explicit "Restore purchases" action
     * (spec §11 — `queryPurchasesAsync` + local cache + restore, one code path for both).
     */
    private suspend fun syncFromPlay(budget: ConnectBudget): Boolean {
        val owned = when (val ownership = queryOwnership(budget)) {
            // Play could not be asked. The cached value stands, and [reconciledWithPlay] stays
            // false so this is attempted again rather than being the session's only shot.
            PlayOwnership.Unreachable -> return _isPro.value
            is PlayOwnership.Answered -> ownership.proPurchase
        }
        if (owned != null && !owned.isAcknowledged) {
            acknowledgeAndUnlock(owned)
        }
        val isProNow = owned != null
        _isPro.value = isProNow
        cache.setPro(isProNow)
        reconciledWithPlay = true
        return isProNow
    }

    /** One round-trip to the authority. [PlayOwnership.Unreachable] is deliberately distinct from
     * `Answered(null)`: "Play did not answer" must never be read as "the account owns nothing",
     * which would revoke Pro from a paying user on a bad connection. */
    private suspend fun queryOwnership(budget: ConnectBudget): PlayOwnership {
        val client = ensureReady(budget) ?: return PlayOwnership.Unreachable
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            return PlayOwnership.Unreachable
        }
        return PlayOwnership.Answered(
            result.purchasesList.firstOrNull { purchase ->
                purchase.products.contains(PRODUCT_ID_PRO_UNLOCK) &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            },
        )
    }
}

/**
 * Runs [attempt] until it reports success or [attempts] rounds are spent, waiting [initialDelayMs]
 * after the first failure and doubling that wait each round. Returns whether any attempt succeeded.
 */
internal suspend fun retryWithBackoff(
    attempts: Int,
    initialDelayMs: Long,
    attempt: suspend () -> Boolean,
): Boolean {
    repeat(attempts) { round ->
        if (attempt()) return true
        if (round < attempts - 1) delay(initialDelayMs shl round)
    }
    return false
}

/**
 * How long reaching Play may take: [attempts] connects, [retryDelayMs] after the first failure
 * (doubled each round, none after the last), and at most [attemptTimeoutMs] for any one connect.
 *
 * 2026-10-02 (S5-09): there used to be one budget for everything — five connects at 1 s doubling,
 * with the 16 s wait also spent after the last one, and no limit on a connect whose callback never
 * came. A tap on "Unlock Pro" or "Restore purchases" with Play unreachable sat silent for 31 s,
 * Restore holding every other Settings control disabled the whole time, and a hung connect held it
 * for ever. A user action now gets [USER_ACTION_CONNECT]; the work nobody is waiting on keeps
 * [BACKGROUND_CONNECT], and the startup reconciliation still retries over minutes around it.
 */
internal data class ConnectBudget(
    val attempts: Int,
    val retryDelayMs: Long,
    val attemptTimeoutMs: Long,
)

/** Startup reconciliation and acknowledgement: nothing on screen is waiting for these. */
internal val BACKGROUND_CONNECT = ConnectBudget(attempts = 5, retryDelayMs = 1_000L, attemptTimeoutMs = 10_000L)

/** A tap someone is watching. A healthy Play connects in well under a second. */
internal val USER_ACTION_CONNECT = ConnectBudget(attempts = 2, retryDelayMs = 1_000L, attemptTimeoutMs = 3_000L)

/** Whether [connectOnce] succeeded within [budget]. A connect that never answers counts as failed. */
internal suspend fun connectWithin(budget: ConnectBudget, connectOnce: suspend () -> Boolean): Boolean =
    retryWithBackoff(budget.attempts, budget.retryDelayMs) {
        withTimeoutOrNull(budget.attemptTimeoutMs) { connectOnce() } == true
    }

/** What one [PlayBillingEntitlement] ownership query learned. */
private sealed interface PlayOwnership {
    /** Play answered: [proPurchase] is the owned Pro unlock, or null when the account owns none. */
    data class Answered(val proPurchase: Purchase?) : PlayOwnership

    /** Play could not be reached, or refused to answer. Says nothing about ownership. */
    data object Unreachable : PlayOwnership
}

/**
 * How the purchase flow reads one Play response code.
 *
 * Extracted for the same reason as [retryWithBackoff]: it is the decision that was wrong, and it is
 * the only part of the purchase path that can be exercised without a `BillingClient`, an `Activity`
 * and a live Play connection. `core/billing` has no mocking framework, so a decision that stays
 * inline inside `onPurchasesUpdated` is a decision no test can reach.
 *
 * Both entry points classify through this one function, which is the point: `ITEM_ALREADY_OWNED`
 * arrives *either* as a refused `launchBillingFlow` (the sheet never opens) *or* through the
 * listener, and those are two separate `when`s that previously had no reason to agree with each
 * other. One of them handling code 7 and the other not is exactly the kind of drift that produced
 * the original dead end.
 */
internal enum class PurchaseResponseKind { Ok, UserCancelled, AlreadyOwned, Failed }

/** Maps a Play `BillingResponseCode` to the kind of thing the purchase flow should do about it. */
internal fun classifyPurchaseResponse(responseCode: Int): PurchaseResponseKind = when (responseCode) {
    BillingClient.BillingResponseCode.OK -> PurchaseResponseKind.Ok
    BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseResponseKind.UserCancelled
    // Not lumped in with the failures. The account owns the product; there is nothing to retry and
    // nothing wrong with the connection, so the copy and the remedy both differ.
    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseResponseKind.AlreadyOwned
    else -> PurchaseResponseKind.Failed
}

/**
 * What an `ITEM_ALREADY_OWNED` becomes once ownership has been re-queried.
 *
 * Separated from the re-query itself so the decision is testable without Play: the query is I/O,
 * this is the rule. [PurchaseOutcome.Purchased] is deliberately silent — the `isPro` flip announces
 * itself, so a user whose account did own it just gets Pro.
 */
internal fun alreadyOwnedOutcome(syncGrantedPro: Boolean): PurchaseOutcome =
    if (syncGrantedPro) PurchaseOutcome.Purchased else PurchaseOutcome.AlreadyOwned

/** Outcome of one purchase-flow round-trip, bridged from [PurchasesUpdatedListener]. */
private sealed interface PurchaseFlowOutcome {
    data class Purchased(val purchase: Purchase) : PurchaseFlowOutcome
    data object Pending : PurchaseFlowOutcome
    data object UserCancelled : PurchaseFlowOutcome

    /**
     * `ITEM_ALREADY_OWNED` (response code 7). Kept apart from [Failed] because it is not a failure
     * and is not retryable — Play will not sell a one-time product to an account that owns it.
     */
    data object AlreadyOwned : PurchaseFlowOutcome
    data class Failed(val result: BillingResult) : PurchaseFlowOutcome
}

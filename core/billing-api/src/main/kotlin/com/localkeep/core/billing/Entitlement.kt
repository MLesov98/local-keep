package com.localkeep.core.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single Pro/Free authority (roadmap §G). `play`'s [PlayBillingEntitlement] drives this from
 * a real Play Billing connection; `foss`'s `FossEntitlement` is a permanent free-tier stub (§N2).
 *
 * Consumed ONLY by the UI layer (feature modules, via [com.localkeep.core.ui.ProGate] or a direct
 * `isPro` check) — never by `:core:vault` or any other business-logic module.
 */
/**
 * How an [Entitlement.purchase] call ended.
 *
 * This was a `Boolean`, and all three call sites discarded it — so every path that returns without
 * Play's sheet ever appearing (no billing connection, no product details, a launch Play refused)
 * produced no message anywhere and the price button simply did nothing. Reporting those needs more
 * than one bit, because the answers must not all be said the same way: a cancel is the user's own
 * doing and wants silence, and a pending purchase is already on its way, so answering it with "try
 * again" would invite a second charge.
 */
enum class PurchaseOutcome {
    /** Bought, acknowledged, and [Entitlement.isPro] is already true. Nothing left to say. */
    Purchased,

    /** The user dismissed Play's sheet. Say nothing — they caused it. */
    Cancelled,

    /**
     * A purchase flow is already open and this second call was refused. Also silent: the first
     * flow is still running and is what the user is looking at.
     */
    AlreadyInFlight,

    /**
     * Play took the purchase but it has not cleared yet (cash at a kiosk, a bank redirect, family
     * approval). Play's listener finishes it asynchronously and the eventual `isPro` flip announces
     * itself, so this needs "still being confirmed" copy and must never be offered a retry.
     */
    Pending,

    /**
     * Play refused the launch because this account already owns the Pro unlock, and re-querying
     * did not turn that into an unlock here.
     *
     * Distinct from [Failed] because the remedy is the opposite one. Nothing is wrong with the
     * connection and retrying the purchase produces this same answer forever — Play will not sell
     * a one-time product twice. The reachable cause is an account mismatch: the app reconciled
     * against the account signed into Play at startup, and the user picked a different one in the
     * billing sheet. So this is the one outcome whose copy must point at Restore purchases rather
     * than at a retry.
     *
     * Only surfaces when re-querying failed to grant Pro. When the re-query *does* find the
     * purchase, the caller gets [Purchased] instead and says nothing, because `isPro` flipping
     * announces itself.
     */
    AlreadyOwned,

    /**
     * Nothing was bought and nothing is in flight: no billing connection, no product details, a
     * misconfigured product, or a launch Play refused. The only retryable outcome.
     */
    Failed,
}

interface Entitlement {
    /** True once the user owns the Pro unlock (always `false` in `foss`, §N2). Safe to read with
     * no network — reflects the last-known state (offline cache in `play`, a constant in `foss`). */
    val isPro: StateFlow<Boolean>

    /**
     * False only while [isPro] still holds its initial placeholder — `play` before its offline
     * cache has been read. A false-to-true flip of [isPro] before this turns true is that read
     * arriving, not a purchase (2026-10-02, S5-10). Always true where [isPro] is known at once.
     */
    val isProLoaded: StateFlow<Boolean> get() = ALWAYS_LOADED

    /**
     * Whether this build can sell the unlock (true only in `play`). ProGate uses it to
     * choose between a purchase prompt and an informational locked note (§N2).
     */
    val canPurchase: Boolean

    /**
     * Play's own localised price for the unlock, or null when this build cannot know it.
     *
     * Null in three cases, and they must all render the same way: `foss` (which has no billing and
     * never sells anything, §N2), `play` before the product details have arrived, and `play` when
     * the store could not be reached. **There is no literal fallback.** A hardcoded amount is what
     * this field exists to remove — the app said "\u20ac9.99" to every store on earth, including the
     * ones where Play charges something else in another currency. A UI reading this must use the
     * no-price wording when it is null rather than substituting a number of its own.
     *
     * Display copy only, exactly as the constant it replaces was. Play Billing remains the authority
     * on what the user is actually charged, and its own sheet is what they confirm.
     */
    val displayPrice: StateFlow<String?>

    /** Launches the purchase flow (Play Billing UI in `play`; always [PurchaseOutcome.Failed] in
     * `foss` — there is no unlock path in the free-tier build). [PurchaseOutcome.Purchased] only
     * once the purchase is confirmed PURCHASED and acknowledged. */
    suspend fun purchase(activity: Activity): PurchaseOutcome

    /** Re-syncs entitlement from the source of truth (Play's `queryPurchasesAsync` in `play`;
     * always `false` in `foss`). Used by the explicit "Restore purchases" action AND at startup. */
    suspend fun restore(): Boolean

    // ---------------------------------------------------------------------------------------
    // TEMPORARY — on-device QA only. Remove this block (and FossEntitlement's overrides, and the
    // "Developer" group in SettingsScreen) once tier testing is done.
    //
    // There is no other way to see the Pro side of the app on a real device: `foss` has no
    // purchase path by construction (§N2) and `play` needs a published SKU and a licensed test
    // account. Both members default to the inert answer, so `play` inherits them unchanged and
    // PlayBillingEntitlement stays the only thing that can ever grant Pro in a shipped build.
    // ---------------------------------------------------------------------------------------

    /** True only in a debuggable `foss` build. Gates the Settings row that flips the tier. */
    val debugTierOverridable: Boolean get() = false

    /** Flips [isPro] for QA. No-op everywhere [debugTierOverridable] is false. */
    fun setDebugPro(pro: Boolean) = Unit
}

private val ALWAYS_LOADED: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

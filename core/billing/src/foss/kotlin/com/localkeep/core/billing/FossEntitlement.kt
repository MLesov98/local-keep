package com.localkeep.core.billing

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `foss` flavor stub. No Play Billing dependency, no DataStore, no network — §N2: `foss` is
 * permanently free tier, so there is no unlock to sell and no purchase path to reach. `purchase()`
 * and `restore()` always return `false` — no unlock path exists in this build — so any 14b call
 * site that invokes them behaves safely in both flavors without an `if (isFoss)` branch anywhere.
 *
 * This is what makes spec §11's "`foss` build: fully offline, verifiable" claim
 * literally true — this class contains no code path that could reach the network even in error.
 */
@Singleton
class FossEntitlement @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Entitlement {

    // -------------------------------------------------------------------------------------
    // TEMPORARY — on-device QA only. Delete this block and restore
    //     override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
    // to put §N2 back exactly as it was.
    //
    // Guarded on the *installed package* being debuggable rather than on BuildConfig, so a
    // release APK is false at runtime no matter how it was assembled — the override cannot
    // survive into a shipped build even by a packaging mistake. Persisted, so the tier chosen
    // for a QA session survives the process death that auto-lock and app-switching cause.
    // -------------------------------------------------------------------------------------
    private val debuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val prefs by lazy {
        context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
    }

    // Defaults to Pro in a debug build: the whole point of the override is that the paid side is
    // otherwise unreachable, and the free side is what you get by simply turning it off.
    private val _isPro = MutableStateFlow(
        if (debuggable) prefs.getBoolean(KEY_DEBUG_PRO, true) else false,
    )

    override val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    override val debugTierOverridable: Boolean get() = debuggable

    override fun setDebugPro(pro: Boolean) {
        if (!debuggable) return
        prefs.edit().putBoolean(KEY_DEBUG_PRO, pro).apply()
        _isPro.value = pro
    }

    // §N2: foss has no billing, no network, and no unlock path. Unchanged by the override above —
    // the QA switch grants the *entitlement*, it does not invent a way to buy one.
    override val canPurchase: Boolean = false

    // Permanently null, and deliberately not a constant. `foss` sells nothing, so it has no price
    // to state; the QA override above grants the entitlement without inventing a way to buy one,
    // and it must not invent an amount either. Every reader treats null as "use the no-price
    // wording", which is the only honest thing this build can say.
    override val displayPrice: StateFlow<String?> = MutableStateFlow(null)

    override suspend fun purchase(activity: Activity): PurchaseOutcome = PurchaseOutcome.Failed
    override suspend fun restore(): Boolean = false

    private companion object {
        const val DEBUG_PREFS = "lk_debug_tier"
        const val KEY_DEBUG_PRO = "debug_pro"
    }
}

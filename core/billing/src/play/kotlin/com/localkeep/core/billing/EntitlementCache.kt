package com.localkeep.core.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.billingDataStore: DataStore<Preferences> by preferencesDataStore(name = "billing_entitlement")

/**
 * Local, offline-readable cache of the Pro entitlement (spec §11: "cached locally via
 * `queryPurchasesAsync` so it keeps working offline after the purchase"). Written after every
 * successful [PlayBillingEntitlement] purchase/restore/startup sync — this is what lets `isPro`
 * stay `true` in airplane mode after a previous successful purchase, since Play Billing itself
 * needs connectivity to verify anything.
 */
internal class EntitlementCache(private val context: Context) {
    private val proKey = booleanPreferencesKey("is_pro")

    /**
     * Reads as "not Pro" when the store is unreadable rather than throwing.
     *
     * `DataStore.data` throws [IOException] on a truncated or corrupt preferences file — a power
     * loss mid-write, a partial device-transfer restore. Without this guard that exception escaped
     * the `scope.launch` in [PlayBillingEntitlement]'s init, which has no handler, and killed the
     * process at startup. Worse than the crash: the Play reconciliation is sequenced immediately
     * after this read, so a throw here also skipped the one thing that could have repaired the
     * answer. Failing to `false` is safe precisely because that reconciliation still runs, and
     * Play — not this file — is the authority.
     */
    val isProCached: Flow<Boolean> = context.billingDataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs -> prefs[proKey] ?: false }

    suspend fun setPro(value: Boolean) {
        // A failed write costs the offline cache, not the entitlement: `isPro` is already correct
        // in memory and Play is re-queried at the next launch.
        runCatching { context.billingDataStore.edit { prefs -> prefs[proKey] = value } }
    }
}

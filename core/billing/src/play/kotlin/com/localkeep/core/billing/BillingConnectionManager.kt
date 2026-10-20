package com.localkeep.core.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns the single [BillingClient] connection for [PlayBillingEntitlement]. `enablePendingPurchases`
 * with `enableOneTimeProducts()` is mandatory as of Billing Library v6+ for INAPP products — the
 * client throws at construction without it. Retry-with-backoff lives in [PlayBillingEntitlement]
 * (`ensureReady`); this class only wraps the raw connect callback into a single suspend call.
 */
internal class BillingConnectionManager(
    context: Context,
    purchasesUpdatedListener: PurchasesUpdatedListener,
) {
    val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build(),
        )
        .build()

    /** Suspends until `onBillingSetupFinished` fires once. Does not retry — callers loop with
     * backoff (see [PlayBillingEntitlement.ensureReady]) if the result isn't OK. */
    suspend fun connectOnce(): BillingResult = suspendCancellableCoroutine { cont ->
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (cont.isActive) cont.resume(result) {}
            }

            override fun onBillingServiceDisconnected() {
                // No reconnect loop here by design: the next purchase()/restore()/startup sync
                // calls ensureReady() again, which re-invokes connectOnce().
            }
        })
    }
}

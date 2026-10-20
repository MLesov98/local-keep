package com.localkeep.core.billing

import com.android.billingclient.api.BillingClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `ITEM_ALREADY_OWNED` is not a failure, and must never be answered with a retry.
 *
 * The dead end this pins: response code 7 fell into the `else ->` of both purchase-result `when`s
 * and became `PurchaseOutcome.Failed`, whose copy is *"The purchase could not be started. Check
 * your connection and try again."* Every word of that is wrong for this code. The connection is
 * fine; Play refused because the account already owns a one-time product, and it will refuse
 * identically forever — so the user is told to retry an operation that is deterministic, and the
 * one action that could fix it (Restore purchases) is never mentioned.
 *
 * Reachable without anything exotic: two Google accounts on one device. The app reconciles against
 * whichever is signed into Play at startup and finds no purchase, so it offers the price button;
 * the user picks the other account in Play's sheet, and that one owns Pro.
 *
 * ### Why these functions exist to be tested at all
 *
 * `core/billing` declares JUnit and nothing else — no mocking framework, no Robolectric. A decision
 * left inline inside `onPurchasesUpdated` needs a `BillingClient`, an `Activity` and a live Play
 * connection to reach, which is why the purchase state machine had no tests before this. The
 * classification and the already-owned rule were pulled out as pure functions for the same reason
 * `retryWithBackoff` was, and this is what that buys.
 */
class AlreadyOwnedIsNotAFailureTest {

    @Test
    fun `ITEM_ALREADY_OWNED does not classify as a failure`() {
        assertNotEquals(
            PurchaseResponseKind.Failed,
            classifyPurchaseResponse(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED),
            "code 7 fell into the failure branch, which renders " +
                "core_ui_pro_gate_purchase_failed — 'check your connection and try again' at a " +
                "user whose connection is fine and whose retry cannot ever succeed",
        )
        assertEquals(
            PurchaseResponseKind.AlreadyOwned,
            classifyPurchaseResponse(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED),
        )
    }

    @Test
    fun `the ordinary codes keep the meaning they had`() {
        // The control. Without it, a change that classified *everything* as AlreadyOwned would
        // satisfy the case above while telling a user with a genuinely broken connection that they
        // already own Pro — worse than the bug being fixed, and invisible to a single assertion.
        assertEquals(PurchaseResponseKind.Ok, classifyPurchaseResponse(BillingClient.BillingResponseCode.OK))
        assertEquals(
            PurchaseResponseKind.UserCancelled,
            classifyPurchaseResponse(BillingClient.BillingResponseCode.USER_CANCELED),
        )
        assertEquals(
            PurchaseResponseKind.Failed,
            classifyPurchaseResponse(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED),
        )
        assertEquals(
            PurchaseResponseKind.Failed,
            classifyPurchaseResponse(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE),
        )
        assertEquals(
            PurchaseResponseKind.Failed,
            classifyPurchaseResponse(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE),
        )
        assertEquals(
            PurchaseResponseKind.Failed,
            classifyPurchaseResponse(BillingClient.BillingResponseCode.DEVELOPER_ERROR),
        )
    }

    @Test
    fun `an unknown code is a failure rather than an unlock`() {
        // Play adds response codes. Whatever they mean, the safe default is the retryable error —
        // never AlreadyOwned, which would suppress a real problem, and never Ok.
        assertEquals(PurchaseResponseKind.Failed, classifyPurchaseResponse(9999))
        assertEquals(PurchaseResponseKind.Failed, classifyPurchaseResponse(-99))
    }

    @Test
    fun `an already-owned purchase unlocks when the re-query finds it`() {
        // The whole reason `resolveAlreadyOwned` re-queries: code 7 means *some* account owns it,
        // and the app's view of ownership is stale by definition at that moment. When the fresh
        // query grants Pro the user simply gets it, and hears nothing — Purchased is silent because
        // the isPro flip announces itself.
        assertEquals(PurchaseOutcome.Purchased, alreadyOwnedOutcome(syncGrantedPro = true))
    }

    @Test
    fun `an already-owned purchase that still will not unlock says so instead of offering a retry`() {
        // The genuine dead end: the owning account is not the one Play is signed into here, or Play
        // could not be reached. Nothing this code can do — and saying that, with Restore purchases
        // named, is the fix.
        val outcome = alreadyOwnedOutcome(syncGrantedPro = false)
        assertEquals(PurchaseOutcome.AlreadyOwned, outcome)
        assertNotEquals(
            PurchaseOutcome.Failed,
            outcome,
            "Failed is documented as 'the only retryable outcome' and renders retry copy; this " +
                "outcome is the one case that is never retryable",
        )
    }
}

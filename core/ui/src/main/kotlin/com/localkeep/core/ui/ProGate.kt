package com.localkeep.core.ui

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.net.Uri
import androidx.compose.runtime.Composable

/**
 * Gates [content] behind [isPro], falling back to [lockedContent] otherwise.
 *
 * The foss/play Pro-vs-Free branch itself is NOT decided here: the one remaining call site
 * (`SettingsScreen`'s duress row) makes that call in its own `ProUpsell`, which is where
 * `entitlement.canPurchase` is actually read (§N2 — `foss` carries no billing code and never shows
 * purchase UI; the transaction happens in the other build, inside Play's own flow). [lockedContent]
 * has no default: a call site that forgets to pass one fails to compile, rather than falling
 * through to a runtime default nothing exercises.
 */
@Composable
fun ProGate(
    isPro: Boolean,
    content: @Composable () -> Unit,
    lockedContent: @Composable () -> Unit,
) {
    if (isPro) content() else lockedContent()
}


/**
 * The unlock price, written once — and now **not to be displayed**.
 *
 * It used to be written six times — `ProGate`, two places in `VaultListScreen`, two in
 * `SettingsScreen`, three in `ItemEditScreen` — and every one of them said **€3.99** while the
 * agreed price in the 2026-07-29 tiers-and-pricing decision was €9.99. Six copies is
 * why: the price moved in the document and there was no single place to move it in the code.
 *
 * This is display copy only. Play Billing is the authority on what a user is actually charged, and
 * it localises the currency; this string is what the app says *before* the purchase sheet opens, so
 * it must not be the only thing anyone reads. If the SKU price changes, it changes here too.
 *
 * **Superseded 2026-07-31.** Writing it once fixed the six-copies-drifting problem and left the
 * real one untouched: it is a euro literal, so every store outside the euro zone was told an amount
 * Play would not charge. The UI now reads `Entitlement.displayPrice`, which is Play's own
 * `formattedPrice`, and falls back to wording with no amount rather than to a number. The constant
 * stays only as a record of the spec'd tier, and is an ERROR to use — a warning would have been re-adopted by the next call site.
 */
@Deprecated(
    "Display copy must use Entitlement.displayPrice — Play's own localised price — and the " +
        "_no_price wording when it is null. This literal is euros on every store on earth.",
    level = DeprecationLevel.ERROR,
)
const val PRO_PRICE = "€9.99"

/**
 * Opens this app's Play listing.
 *
 * `market://` first so it lands in the Play app rather than a browser tab, falling back to the web
 * URL. Both are wrapped: a self-distributed install may be on a device with no Play Store and no
 * browser at all, and an uncaught ActivityNotFoundException there would crash the app from a
 * button whose entire job is to be helpful.
 *
 * No `<queries>` entry is needed for this. Package-visibility filtering on Android 11+ applies to
 * *querying* for resolvers — `resolveActivity`, `queryIntentActivities` — not to firing an implicit
 * intent and handling the failure, which is what this does.
 */
fun Context.openPlayListing() {
    val id = PLAY_APPLICATION_ID
    val tries = listOf(
        "market://details?id=$id",
        "https://play.google.com/store/apps/details?id=$id",
    )
    for (uri in tries) {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}

/**
 * The `play` flavour's application id, which is what its Play listing is keyed on.
 *
 * Hardcoded rather than read from `BuildConfig`: this code runs in the **foss** build, whose own
 * application id is `com.localkeep.foss` — so the one id it must never use here is its own.
 */
private const val PLAY_APPLICATION_ID = "com.localkeep"

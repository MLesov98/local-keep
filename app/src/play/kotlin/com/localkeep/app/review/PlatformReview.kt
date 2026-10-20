package com.localkeep.app.review

import android.app.Activity
import android.util.Log
// The suspend extensions live in `core.ktx`, NOT beside ReviewManager in `core.review` — verified
// by javap on the resolved review-ktx-2.0.2.aar (class ReviewManagerKtxKt), after importing them
// from the obvious package failed to resolve. Same shape of trap as Play Billing's `billing-ktx`.
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Asks Play to show its in-app review card. `play` flavour only.
 *
 * The flavour split here is a same-signature top-level function per source set rather than
 * `:core:billing`'s interface-plus-Hilt-module pattern, deliberately: [Entitlement] is injected
 * into a dozen feature modules and needs a bound type, whereas this has exactly one call site in
 * [com.localkeep.app.MainActivity] and lives inside `:app`, which is already flavoured. An
 * interface with two implementations and two Hilt modules would be five files to dispatch one
 * call. `:core:billing`'s api/impl split is not being repeated for a second reason too: it exists
 * because air-gapped `:credential` needed the *type* without Play Billing's transitive graph.
 * Nothing air-gapped needs this one.
 *
 * ## What this cannot tell you
 *
 * Nothing. The task completes the same way whether the user rated, dismissed the card, or never
 * saw it because Play's quota suppressed it. There is no success path to branch on, so both the
 * happy path and the failure path here do the same thing: return. [ReviewGate.onAsked] is called
 * by the caller either way.
 *
 * ## Why the failure is swallowed
 *
 * A ReviewException means Play could not produce a review flow — no Play Store on the device, a
 * sideloaded build, an unrecognised app id during testing. None of those are the user's problem
 * and none of them have a sensible message, so this must never surface anything. It is logged at
 * debug only; nothing goes to the log that a user's vault would care about, and an
 * enum from Play qualifies as nothing.
 */
internal suspend fun launchPlatformReview(activity: Activity) {
    runCatching {
        val manager = ReviewManagerFactory.create(activity)
        val info = manager.requestReview()
        manager.launchReview(activity, info)
    }.onFailure { e ->
        Log.d("ReviewPrompt", "Play declined to produce a review flow: ${e::class.simpleName}")
    }
}

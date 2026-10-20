package com.localkeep.autofill

import android.app.ActivityOptions
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi

/**
 * The creator's opt-in that lets the platform actually launch our save confirmation.
 *
 * An [android.service.autofill.AutofillService] answers a save request with
 * `SaveCallback.onSuccess(intentSender)`, and the platform — not this app — starts that Activity.
 * Since Android 10 that start is subject to the background-activity-launch rules, and they are
 * evaluated against the PendingIntent's *creator* as well as its sender. When the vault locks
 * between the system's own "Save password to LocalKeep?" sheet and the launch, this app has no
 * visible window, and the start is refused outright — measured on API 35, 2026-09-17:
 *
 * ```
 * Background activity launch blocked! [callingPackage: com.localkeep.foss; … SaveConfirmActivity …
 *   realCallingUidHasAnyVisibleWindow: false; realCallingUidProcState: CACHED_RECENT …]
 * START … (BAL_BLOCK) result code=102
 * ```
 *
 * Nothing reports that back: `onSuccess` is the last the service hears, so the credential the user
 * had already agreed to save disappeared with no message anywhere. API 34 added the opt-in that
 * says "this PendingIntent is one I made for the platform to launch on my behalf", which is exactly
 * what this one is — it goes only to the platform, is `FLAG_IMMUTABLE`, and opens a screen the user
 * asked for one gesture earlier. Granting it is narrower than it sounds: it authorises *this*
 * PendingIntent, not the app.
 *
 * Below API 34 there is no such opt-in and [PendingSaveQueue] is the answer. It stays the fallback
 * above 34 too — this lifts the common case, it does not make the refusal impossible.
 *
 * ## Why the API-34 types live behind a private function
 *
 * The same reason [InlineSuggestions] exists, and `checkNoApi30Signatures` is the scar:
 * `minSdk` is 28, ART resolves a class when an instruction names it, and a `Build.VERSION.SDK_INT`
 * guard around a *call* does not cover a type named in a signature — a suspend function spills its
 * parameters into a continuation and check-casts them back on resume, generated code with nowhere
 * to put a guard. So the only member that names [ActivityOptions] or its API-34 constant is one
 * that cannot be reached below 34, and the value crosses the boundary as a plain [Bundle], which
 * has existed since API 1.
 */
internal object CreatorBackgroundActivityStart {

    /**
     * Options to pass to `PendingIntent.getActivity`, or null where the platform has no opt-in.
     *
     * Null is a complete answer: the five-argument `getActivity` overload has accepted a null
     * options bundle since API 16, and on 28-33 the queue is what recovers a refused launch.
     */
    fun optionsBundle(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) allowed() else null

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun allowed(): Bundle =
        ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
            .toBundle()
}

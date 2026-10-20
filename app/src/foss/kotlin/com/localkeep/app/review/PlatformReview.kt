package com.localkeep.app.review

import android.app.Activity

/**
 * The `foss` half of the review split: nothing happens.
 *
 * The free build is self-distributed — there is no store listing for `com.localkeep.foss` and no
 * Play Store relationship to ask about. More to the point, `com.google.android.play:review` pulls
 * `com.google.android.gms:play-services-tasks`, and `com.google.android.gms` is on
 * `checkNoNetworkingDeps`' blocklist in the root build. The library is wired in as
 * `playImplementation` so it is not merely unused in `foss` — it is not on the classpath at all,
 * and `checkFlavorPermissions` proves the merged `foss` manifest still asks for nothing but the
 * biometric pair and CAMERA.
 *
 * This file is the reason that holds. Deleting it does not disable the feature in `foss`; it
 * breaks the `foss` build, which is the failure mode worth having.
 */
@Suppress("UNUSED_PARAMETER")
internal suspend fun launchPlatformReview(activity: Activity) = Unit

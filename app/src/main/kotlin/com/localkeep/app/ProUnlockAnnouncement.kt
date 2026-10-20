package com.localkeep.app

/**
 * [message] to post when the tier changes, or null when nothing should be said.
 *
 * Buying Pro used to produce no affirmative moment anywhere — the gates simply stopped firing,
 * which from the outside is indistinguishable from nothing having happened (2026-07-29).
 *
 * The trap this exists to avoid: `isPro` is a flow, so its FIRST emission after a cold start is
 * simply the current tier. Announcing on that would congratulate an existing Pro user on a purchase
 * they made weeks ago, every single launch. [previous] is therefore null until a value has been
 * seen, and only a genuine false-to-true transition says anything.
 *
 * [message] is resolved by the caller via `stringResource` — this function is plain Kotlin, not a
 * composable, and must not hold display strings itself (see the app extraction brief's ViewModel
 * rule, which applies here for the same reason).
 */
internal fun proUnlockAnnouncement(previous: Boolean?, current: Boolean, message: String): String? =
    if (previous == false && current) {
        message
    } else {
        null
    }

/** One observation of the tier: what the announcement has now [seen], and what to [say], if anything. */
internal data class ProTierObservation(val seen: Boolean?, val say: String?)

/**
 * [proUnlockAnnouncement], fed only values that mean something.
 *
 * 2026-10-02 (S5-10): `isPro` in a play build starts as a placeholder `false` and becomes the
 * cached tier when an asynchronous DataStore read lands. When that read lost the race with first
 * composition, the seed was the placeholder and the cache arriving read as a purchase, so a paying
 * user was told "Pro unlocked" on a cold start. Nothing is seen until [loaded] says the value is
 * real, which makes the first real value the seed, as the function above intends.
 */
internal fun observeProTier(seen: Boolean?, isPro: Boolean, loaded: Boolean, message: String): ProTierObservation =
    if (!loaded) {
        ProTierObservation(seen = seen, say = null)
    } else {
        ProTierObservation(seen = isPro, say = proUnlockAnnouncement(seen, isPro, message))
    }

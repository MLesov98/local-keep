package com.localkeep.feature.health

import com.localkeep.core.vault.VaultPresentationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Makes the Health scan's strength cache live exactly as long as the unlocked session.
 *
 * `SecurityDashboard` remembers a weak/not-weak verdict per distinct password so a re-scan only
 * pays for what changed (1B-F6). Those verdicts are keyed by an HMAC tag under a key held only in
 * memory, and `forgetStrengthScores` wipes both — but something has to call it, and the obvious
 * candidate does not work: `HealthDashboardViewModel` is destroyed when the screen leaves the back
 * stack, while the dashboard is a `@Singleton` that outlives it. A cache whose eraser dies first is
 * a cache that survives the lock.
 *
 * So the subscription is created beside the dashboard itself, in `HealthModule`, on a scope with
 * the same lifetime. `VaultRepository.presentationState` is an eagerly-started `StateFlow` on the
 * repository's own singleton scope, so it publishes `Locked` the instant the vault locks whether or
 * not any screen is watching.
 *
 * [forget] rather than the dashboard, so the wiring can be driven without scanning a vault to
 * observe its effect.
 */
internal fun CoroutineScope.forgetStrengthScoresOnLock(
    presentationState: Flow<VaultPresentationState>,
    forget: () -> Unit,
): Job = launch {
    presentationState.collect { presentation ->
        if (presentation is VaultPresentationState.Locked) forget()
    }
}

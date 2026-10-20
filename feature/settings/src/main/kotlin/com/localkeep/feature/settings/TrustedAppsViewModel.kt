package com.localkeep.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.vault.AppSignatureCache
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.TrustedApp
import com.localkeep.core.vault.VaultLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrustedAppsUiState(
    val loading: Boolean = true,
    val apps: List<TrustedApp> = emptyList(),
)

/**
 * The autofill trust-management surface (UI/UX spec §10.1): every app↔domain grant the user has
 * allowed, and the ability to take one back. Reads and writes the same [AppSignatureCache] the
 * autofill service confirms against, so a removal here is the removal the service sees — no second
 * store to drift out of sync.
 *
 * **A decoy session sees an empty list.** The trust store is device-global — it lives in its own
 * DataStore, not in the vault — so this screen showed a coercer holding the duress password the
 * real user’s bank, work SSO, exchange and mail provider. That is a disclosure of the victim’s
 * account inventory on its own, and it is also a tell: the decoy holds eight mundane seeded logins
 * and no credential for any of those sites, so a trust list naming them says plainly that this is
 * not the vault that filled them. Settings used to withhold the row instead, which was its own,
 * larger tell (release sweep 2026-09-20, 3A-F23) — the row is back and the *list* is what is
 * scoped. Empty is what a real vault that has trusted nothing shows, and says nothing about either
 * vault.
 */
@HiltViewModel
class TrustedAppsViewModel @Inject constructor(
    private val cache: AppSignatureCache,
    private val lockManager: VaultLockManager,
) : ViewModel() {

    private val _state = MutableStateFlow(TrustedAppsUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-reads the store. `AppSignatureCache.trustedApps()` is a snapshot, not a flow, so a grant
     * made in a browser while this screen was open left it showing "Nothing trusted yet" until the
     * user backed out and returned (device, 2026-09-20). The screen calls this on every resume,
     * which is when a user comes back from the browser that made the grant.
     */
    fun refreshOnResume() = refresh()

    private fun refresh() {
        viewModelScope.launch {
            // Fail closed: anything but an open real session shows nothing. A locked vault has no
            // business listing the trust store either, and reading the session here rather than in
            // the composable means no future caller of this ViewModel can forget to.
            val realSession = (lockManager.lockState.value as? LockState.Unlocked)
                ?.isDuress == false
            val apps = if (realSession) cache.trustedApps() else emptyList()
            _state.update { it.copy(loading = false, apps = apps) }
        }
    }

    /**
     * Removes one app↔site grant. Autofill will confirm it again next time it is offered.
     *
     * Guarded on the session for the same reason [refresh] is, and not only because the list is
     * empty there: a decoy session must not be able to *write* the real user’s trust store either,
     * and a stale recomposition is a call this ViewModel would otherwise honour.
     */
    fun forgetDomain(packageName: String, domain: String) {
        if (isDecoySession()) return
        viewModelScope.launch {
            cache.forgetPair(packageName, domain)
            refresh()
        }
    }

    /** Removes an app entirely — every grant and its pinned signing identity. */
    fun forgetApp(packageName: String) {
        if (isDecoySession()) return
        viewModelScope.launch {
            cache.forgetApp(packageName)
            refresh()
        }
    }

    /** True unless the vault is open and open as the real one. Locked counts as "not real". */
    private fun isDecoySession(): Boolean =
        (lockManager.lockState.value as? LockState.Unlocked)?.isDuress != false
}

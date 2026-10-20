package com.localkeep.feature.vaultlist

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Asks the vault list to drop every transient narrowing — favourites, type, folder and search — and
 * show the whole vault again.
 *
 * The drawer's "All items" is the intuitive "show me everything" control, but it navigates to
 * [com.localkeep.app.navigation.AppNavGraph.HOME], which is a no-op when the list is *already* the
 * plain HOME destination (see `topLevelNavDecision`). So a user who tapped the Favourites chip and
 * then reached for "All items" got a silent nothing: the chip stayed active and the list stayed
 * empty. The transient filters live in [VaultListViewModel], not in the route, so navigation alone
 * cannot clear them.
 *
 * A reactive signal rather than a [com.localkeep.feature.settings.SettingsFocus]-style take-on-entry
 * flag, precisely because the broken case never re-enters: the vault list stays composed while the
 * drawer opens over it, so nothing consumes a one-shot value read at composition. A collector that
 * is already alive is exactly what a `SharedFlow` emission reaches.
 *
 * `replay = 0` is deliberate, though the destination it originally defended against is gone: this
 * guarded the drawer's per-type vault routes ("vault/CARD"), removed in favour of the filter sheet
 * (2026-09-07) — a freshly composed vault list starts with no filters now, so a
 * replayed reset would have nothing to wipe. Left non-replaying anyway: only a list already on
 * screen when "All items" is tapped should react to it.
 */
object HomeFilter {

    private val _resets = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emissions the composed vault list collects to clear its filters. */
    val resets: SharedFlow<Unit> = _resets.asSharedFlow()

    /**
     * Asks any composed vault list to return to the unfiltered view. Non-suspending — `tryEmit`
     * succeeds against the buffer so a click handler can call it without a scope, and it is a no-op
     * when no list is listening.
     */
    fun requestReset() {
        _resets.tryEmit(Unit)
    }
}

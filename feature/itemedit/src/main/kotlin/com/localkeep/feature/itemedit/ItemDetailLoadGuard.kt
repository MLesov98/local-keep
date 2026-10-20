package com.localkeep.feature.itemedit

/**
 * Whether the detail screen is currently holding some *other* item than the one it was asked for.
 *
 * `ItemEditUiState.isLoading` was written to answer this and cannot, because it defaults to `false`
 * and is only raised from inside `loadItem` — which runs from a `LaunchedEffect`, by definition
 * after the first composition. So frame one rendered the loaded-content branch over whatever the
 * state happened to hold: a blank title, resolved to "Untitled", giving a monogram whose colour is
 * derived from that word rather than from the item the user just tapped. The shared-element morph
 * then began on the wrong tile, the skeleton took over a frame later, and the right tile arrived
 * after that. Brief, intermittent, and exactly the "icon glitch" that was reported.
 *
 * Identity is the honest test, and it is true on frame one by construction. A fresh state carries a
 * random `itemId` that cannot match the route's; navigating item-to-item leaves the *previous*
 * item's id in place, which will not match either. `isLoading` is still consulted, because it is
 * what covers the second half — the window while the vault scan runs.
 *
 * `isLoading` cannot simply default to `true` instead: `ItemEditScreen` shares this state for
 * creating a new item, which never calls `loadItem`, and would show a skeleton forever.
 */
internal fun isShowingWrongItem(state: ItemEditUiState, requestedId: String): Boolean =
    state.isLoading || state.itemId.toString() != requestedId

/**
 * Whether the vault was asked for this item and did not have it.
 *
 * The other half of [isShowingWrongItem], and the reason that one is not enough on its own. Both are
 * true while the screen is held, but they are held for opposite reasons: one is "not yet", the other
 * is "not ever". Told apart only by whether a lookup has already run, which is why [lookupRan] is a
 * parameter rather than something derivable from [state] — `loadItem`'s not-found branch sets
 * `error` and deliberately does *not* adopt the requested id, since adopting it would make the
 * screen claim to be showing an item that was never found.
 *
 * `error` cannot stand in for [lookupRan] either, however much it looks like it can: `loadFolders()`
 * runs immediately after the construction-time load in the ViewModel's `init`, and the private
 * `update {}` helper clears `error` on every call, so a not-found message set during construction is
 * gone before anything composes. A test written against it failed with
 * `expected:<2131624192> but was:<null>`.
 *
 * [state] having `isLoading` set still means "not yet" — a reload of an item that *is* present runs
 * through here between raising and clearing that flag.
 */
internal fun isMissingItem(state: ItemEditUiState, requestedId: String, lookupRan: Boolean): Boolean =
    lookupRan && !state.isLoading && state.itemId.toString() != requestedId

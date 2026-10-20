package com.localkeep.core.vault

/**
 * The only safe rollback for hidden entries, expressed as two compile-time constants.
 *
 * Once a vault has been saved at schema 5 an older APK refuses to open it, so "roll back" cannot
 * mean "ship the previous build" after release. What it can mean is an emergency build that stops
 * *offering* the feature while still reading, writing and honouring everything already committed.
 * That is the shape these two flags have, and it is why they are `const val` rather than a
 * preference or a remote flag: a user-toggleable switch would let someone disable concealment on a
 * vault that is currently concealing, which is the one transition that must never be reachable by
 * accident.
 *
 * What an emergency build with both flags `false` still does, unconditionally and by design:
 *
 * - decodes and writes schema 5, including every lease pair and the reveal-phrase verifier;
 * - conceals — [VaultVisibility.project] never consults these flags, so an entry hidden by the
 *   previous build stays hidden rather than reappearing in a list the user did not expect;
 * - recovers — the authenticated Settings screen, Stop hiding and Change duration keep working, so
 *   nobody is locked out of their own entries by a build shipped to protect them;
 * - expires — a lease that runs out still makes its record ordinary.
 *
 * What it stops offering: the Hide action on items and folders, and the search-box phrase reveal.
 * Both are entry points to *creating* new hidden state, which is the thing an emergency build wants
 * to stop. `MakeInvisibleRollbackTest` pins that separation, because the tempting mistake is to gate
 * the recovery path with the same flag and turn a feature pause into data the user cannot reach.
 */
object MakeInvisibleAvailability {

    /** Whether Hide… is offered on items and folders. False disables creating new hidden state. */
    const val HIDE_ACTIONS_ENABLED: Boolean = true

    /**
     * Whether typing the reveal phrase into the search box reveals.
     *
     * Disabling this does not strand anyone: the authenticated Settings screen is the documented
     * recovery route and is never gated, and `vaultlist_hide_phrase_body` promises exactly that
     * route rather than promising the search box will always work.
     */
    const val SEARCH_PHRASE_REVEAL_ENABLED: Boolean = true
}

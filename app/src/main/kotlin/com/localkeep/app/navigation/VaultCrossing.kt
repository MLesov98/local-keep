package com.localkeep.app.navigation

/**
 * Which of the three kinds of crossing the NavHost is being asked to draw.
 *
 * [SEAL] and [OPEN] are the vault closing and opening — the depth move in `LkMotion`. [ORDINARY] is
 * every other screen change, which is an instantaneous swap (see `LkScreenPopEnterTest`).
 */
internal enum class VaultCrossing { SEAL, OPEN, ORDINARY }

/**
 * Decides a crossing from the two routes it runs between, and nothing else.
 *
 * Pulled out of [AppNavGraph]'s four transition lambdas for the reason [topLevelNavDecision] was
 * pulled out of the shell: the decision is the whole of what can be wrong and none of what needs a
 * device. The visual pass of 2026-09-15 reported panic lock as a hard cut with no depth move at
 * all, and the first thing worth knowing is whether the graph is even selecting the lock transition
 * on that path — panic lock reaches `UNLOCK` from Settings, not from the vault list, and it is the
 * only lock the user triggers from a screen that is not `HOME`.
 *
 * It is: every route in the graph leaves for `UNLOCK` the same way, because the decision is made
 * from the *destination*, not from where the lock was pressed. There was no bypass. What there was
 * is a 260 ms scale under a 260 ms fade, which is a fade — fixed in `lkVaultSealEnter`. These cases
 * are what stops a future route ever being special-cased out of it.
 *
 * Both arguments are nullable because `NavDestination.route` is: a destination created without one
 * is not `UNLOCK` and must not be mistaken for it.
 */
internal fun vaultCrossing(from: String?, to: String?): VaultCrossing = when {
    // Asked of the destination first. A lock reached by any route must look like a lock, and the
    // one crossing where both ends could match — unlock to unlock — does not exist.
    to == AppNavGraph.UNLOCK -> VaultCrossing.SEAL
    from == AppNavGraph.UNLOCK -> VaultCrossing.OPEN
    else -> VaultCrossing.ORDINARY
}

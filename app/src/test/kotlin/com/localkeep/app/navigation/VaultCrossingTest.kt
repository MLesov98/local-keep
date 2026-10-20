package com.localkeep.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every route in the graph closes the vault the same way, whichever screen the lock was pressed on.
 *
 * The visual pass of 2026-09-15 filed panic lock as a hard cut with no depth move, beside unlock
 * which at least cross-faded — two different-looking answers to the same question, which is what a
 * bypassed transition looks like. The first thing to rule out is a route the graph is not applying
 * the lock motion to, and panic lock is the plausible candidate: it is the only lock a user fires
 * from a screen that is not the vault list, reaching `UNLOCK` from Settings
 * (`SettingsScreen.kt` → `SettingsViewModel.panicLock`), and the graph then pops the *whole* graph
 * inclusive rather than popping back (`MainActivity`).
 *
 * It was not a bypass — the decision is made from the destination, so Settings and the vault list
 * and a half-typed editor all take the same path — and pinning that is worth more than the
 * hypothesis that prompted it, because the way it *would* go wrong in future is a special case
 * added for one of these routes. See [vaultCrossing] for what was actually wrong.
 */
class VaultCrossingTest {

    @Test
    fun `the vault seals from every screen a lock can be fired on`() {
        // The Lock button on the vault list — the ordinary case.
        assertEquals(VaultCrossing.SEAL, vaultCrossing(AppNavGraph.HOME, AppNavGraph.UNLOCK))
        // Panic lock, which is a Settings row and nothing else.
        assertEquals(VaultCrossing.SEAL, vaultCrossing(AppNavGraph.SETTINGS, AppNavGraph.UNLOCK))
        // The auto-lock timer, which fires wherever the user happened to be.
        assertEquals(VaultCrossing.SEAL, vaultCrossing("item/new/login", AppNavGraph.UNLOCK))
        assertEquals(VaultCrossing.SEAL, vaultCrossing(AppNavGraph.IMPORT, AppNavGraph.UNLOCK))
        assertEquals(VaultCrossing.SEAL, vaultCrossing(AppNavGraph.RECOVERY_KIT, AppNavGraph.UNLOCK))
        // A lock that interrupts first-run onboarding, which is the one state with no HOME behind it.
        assertEquals(VaultCrossing.SEAL, vaultCrossing(AppNavGraph.ONBOARDING, AppNavGraph.UNLOCK))
    }

    @Test
    fun `the vault opens whatever it opens onto`() {
        assertEquals(VaultCrossing.OPEN, vaultCrossing(AppNavGraph.UNLOCK, AppNavGraph.HOME))
        // The credential provider can unlock the vault from outside this Activity; MainActivity
        // then navigates off the unlock screen on its own. Still an opening.
        assertEquals(VaultCrossing.OPEN, vaultCrossing(AppNavGraph.UNLOCK, AppNavGraph.BACKUP))
    }

    @Test
    fun `an ordinary screen change is neither`() {
        assertEquals(VaultCrossing.ORDINARY, vaultCrossing(AppNavGraph.HOME, AppNavGraph.SETTINGS))
        assertEquals(
            VaultCrossing.ORDINARY,
            vaultCrossing(AppNavGraph.HOME, AppNavGraph.HEALTH_DASHBOARD),
        )
        // Onboarding hands straight to the vault list when the password it just took opened the
        // vault. Nothing sealed and nothing was unsealed, so this must not borrow the lock motion.
        assertEquals(VaultCrossing.ORDINARY, vaultCrossing(AppNavGraph.ONBOARDING, AppNavGraph.HOME))
    }

    @Test
    fun `a destination with no route is not the unlock screen`() {
        assertEquals(VaultCrossing.ORDINARY, vaultCrossing(null, null))
        assertEquals(VaultCrossing.SEAL, vaultCrossing(null, AppNavGraph.UNLOCK))
        assertEquals(VaultCrossing.OPEN, vaultCrossing(AppNavGraph.UNLOCK, null))
    }
}

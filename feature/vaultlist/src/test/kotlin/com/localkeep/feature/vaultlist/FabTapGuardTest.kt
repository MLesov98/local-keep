package com.localkeep.feature.vaultlist

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A notice leaving drops the FAB back out of its slot, and the finger that was reaching for the
 * notice's action lands on the FAB as it arrives. Taps are ignored for [FAB_SETTLE_MS] from the
 * moment the FAB starts moving down, and only then: the lift (a notice arriving) and a steady FAB
 * both take taps as ever.
 */
class FabTapGuardTest {

    @Test
    fun `a tap just after the fab starts moving back is ignored`() {
        val guard = FabTapGuard()
        guard.observe(inset = 80.dp, nowMs = 0)
        guard.observe(inset = 0.dp, nowMs = 1_000)
        assertFalse(guard.accepts(nowMs = 1_000 + FAB_SETTLE_MS - 1))
    }

    @Test
    fun `the fab takes taps again once it has settled`() {
        val guard = FabTapGuard()
        guard.observe(inset = 80.dp, nowMs = 0)
        guard.observe(inset = 0.dp, nowMs = 1_000)
        assertTrue(guard.accepts(nowMs = 1_000 + FAB_SETTLE_MS))
    }

    @Test
    fun `a fab that never moved takes taps at once`() {
        val guard = FabTapGuard()
        guard.observe(inset = 0.dp, nowMs = 0)
        assertTrue(guard.accepts(nowMs = 1))
    }

    @Test
    fun `lifting for an arriving notice is not guarded`() {
        // Control: the guard is about the FAB returning, not about it moving. Locking it while a
        // notice arrives would swallow a deliberate tap for no reason.
        val guard = FabTapGuard()
        guard.observe(inset = 0.dp, nowMs = 0)
        guard.observe(inset = 80.dp, nowMs = 1_000)
        assertTrue(guard.accepts(nowMs = 1_001))
    }
}

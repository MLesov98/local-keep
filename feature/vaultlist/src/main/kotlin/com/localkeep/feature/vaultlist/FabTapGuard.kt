package com.localkeep.feature.vaultlist

import androidx.compose.ui.unit.Dp

/** How long the FAB ignores taps after it starts moving back out of a notice's slot. */
internal const val FAB_SETTLE_MS = 250L

/**
 * Swallows the tap that was meant for a notice's action.
 *
 * The FAB lifts over the shell's floating notices and slides back down when one leaves, into the
 * very slot the notice's action button occupied. A finger already on its way to that button lands
 * on the FAB instead and opens the add sheet. Held for [FAB_SETTLE_MS] from the first frame of the
 * descent, which outlasts the slide, and only on the way down: a lift is the FAB getting out of
 * the way, and a tap during it is deliberate.
 */
internal class FabTapGuard {
    private var lastInset: Dp? = null
    private var ignoreUntilMs = Long.MIN_VALUE

    /** Feed the inset the FAB is heading for each time it changes. */
    fun observe(inset: Dp, nowMs: Long) {
        val previous = lastInset
        if (previous != null && inset < previous) ignoreUntilMs = nowMs + FAB_SETTLE_MS
        lastInset = inset
    }

    fun accepts(nowMs: Long): Boolean = nowMs >= ignoreUntilMs
}

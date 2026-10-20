package com.localkeep.app.navigation

import androidx.compose.material3.DrawerValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A back press belongs to the command drawer for as long as the drawer is on screen — including
 * while it is still moving.
 *
 * `DrawerState.isOpen` is the wrong question to ask, which is what these four cases pin down:
 * `isOpen` reads `AnchoredDraggableState.settledValue` (verified against material3's own bytecode:
 * `getCurrentValue` → `getSettledValue`), so for the whole open animation it is still `Closed`
 * while the sheet is plainly on screen. The target value closes that window, and the current value
 * covers the other end of it, while the sheet slides back out.
 *
 * **What this cannot prove, and did not: that the shell actually wins the press.** These four
 * assertions were green on 2026-09-20 against a build where 2A-F10 was completely unfixed on a
 * device, because the predicate was only ever half the mechanism — the other half is which
 * callback the `OnBackPressedDispatcher` hands the press to, and no function of two enums can say
 * anything about that. `DrawerOwnsBackOverNavHostTest` is the one that can; read it before
 * trusting this file as coverage of the finding.
 */
class DrawerConsumesBackTest {

    @Test
    fun `a closed drawer leaves back alone`() {
        assertFalse(drawerConsumesBack(DrawerValue.Closed, DrawerValue.Closed))
    }

    @Test
    fun `a drawer still sliding open already owns back`() {
        // settled = Closed, heading for Open. This is the case that shipped broken: the press fell
        // through and popped the screen the drawer was about to cover.
        assertTrue(
            drawerConsumesBack(DrawerValue.Closed, DrawerValue.Open),
            "back during the open animation must close the drawer, not navigate underneath it",
        )
    }

    @Test
    fun `a settled open drawer owns back`() {
        assertTrue(drawerConsumesBack(DrawerValue.Open, DrawerValue.Open))
    }

    @Test
    fun `a drawer still sliding closed keeps back until it has gone`() {
        // The mirror of the open case: the sheet is still drawn, so navigating under it would look
        // exactly like the finding.
        assertTrue(
            drawerConsumesBack(DrawerValue.Open, DrawerValue.Closed),
            "back during the close animation must not navigate under a sheet that is still on screen",
        )
    }
}

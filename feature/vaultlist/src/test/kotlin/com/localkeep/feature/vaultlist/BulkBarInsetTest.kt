package com.localkeep.feature.vaultlist

import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How much room the vault list leaves at its foot while a multi-select is live.
 *
 * F-11's reachability half. The list reserved [LkFrame.gutterFab] — a constant sized for the FAB —
 * and the bulk-action bar takes the FAB's place while being taller than it and growing with the
 * text. At the largest display size the bar's three labels wrap and the constant stops covering it,
 * which leaves the last row under a bar no amount of scrolling can move.
 *
 * The bar's *occlusion* of rows mid-scroll is not this: a floating bar covers what it floats over by
 * construction, and the surface underneath it is opaque (`sheetTop`, #141A1C — decoded from
 * `shots/048-multiselect.png`, uniform across the bar). Only the end of the list can be made safe,
 * and this is the arithmetic that makes it so.
 */
class BulkBarInsetTest {

    @Test
    fun `a short bar still gets at least the FAB's gutter`() {
        assertEquals(LkFrame.gutterFab, bulkBarListInset(40.dp))
    }

    @Test
    fun `a bar taller than the constant pushes the list up by its own height`() {
        // 120dp is the constant; a bar of 110dp plus its 24dp margin is already past it.
        val inset = bulkBarListInset(110.dp)
        assertTrue(
            inset > LkFrame.gutterFab,
            "a 110dp bar needs more than the FAB's ${LkFrame.gutterFab}, got $inset",
        )
        assertTrue(
            inset >= 110.dp + 24.dp,
            "the reservation must clear the bar and the margin it floats on, got $inset",
        )
    }

    @Test
    fun `the reservation grows with the bar, one for one`() {
        val small = bulkBarListInset(110.dp)
        val large = bulkBarListInset(150.dp)
        assertEquals(40.dp, large - small)
    }
}

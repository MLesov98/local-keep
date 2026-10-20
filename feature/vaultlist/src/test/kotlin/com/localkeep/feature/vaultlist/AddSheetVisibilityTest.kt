package com.localkeep.feature.vaultlist

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The add sheet must never leave a frame of bare vault list behind it.
 *
 * Two separate frames-late bugs met on this screen, and both looked like the same thing to the
 * person holding the phone — the list flashing at full brightness with no sheet and no scrim on it,
 * for a fifth of a second, once on the way into the chosen screen and once on the way back.
 *
 * Going in, the sheet was deleted from the composition on the tap, which takes its whole window
 * down between two frames while the destination is still being built. Coming back, the reopen flag
 * was consumed in a `LaunchedEffect`, which runs *after* the composition that could have used it,
 * so the restored list drew twice bare before the sheet returned.
 *
 * Both are now composition-time answers, and these are them.
 */
class AddSheetVisibilityTest {

    @Test
    fun `a reopen request shows the sheet on the same composition that carries it`() {
        // The pop's first frame: the flag is set before popBackStack, so it is already true here,
        // while the screen's own `showAddSheet` is still the false it was saved as.
        assertTrue(addSheetVisible(open = false, leaving = false, reopenRequested = true))
    }

    @Test
    fun `a reopen request brings the sheet back already open`() {
        assertTrue(addSheetAlreadyOpen(restored = false, reopenRequested = true))
    }

    @Test
    fun `a sheet that is sliding away is still on screen`() {
        // `showAddSheet` goes false on the tap — it is the saveable flag, and leaving it true would
        // put the sheet back over the item the user just saved. The exit is what keeps it drawn.
        assertTrue(addSheetVisible(open = false, leaving = true, reopenRequested = false))
    }

    @Test
    fun `an open sheet is on screen and plays its entrance`() {
        assertTrue(addSheetVisible(open = true, leaving = false, reopenRequested = false))
        assertFalse(addSheetAlreadyOpen(restored = false, reopenRequested = false))
    }

    @Test
    fun `nothing showing means no sheet`() {
        assertFalse(addSheetVisible(open = false, leaving = false, reopenRequested = false))
    }
}

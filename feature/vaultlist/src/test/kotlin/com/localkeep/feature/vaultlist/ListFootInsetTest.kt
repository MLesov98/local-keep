package com.localkeep.feature.vaultlist

import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The list reserves the FAB's gutter at its foot only while something is there to reserve it for.
 *
 * With the keyboard up the FAB is not drawn and the list already ends at the keyboard, so the
 * reserve was an empty band of about 90dp between the last result and the keyboard, with a row
 * sliced above it. A live selection keeps its reserve: the bulk bar is on screen.
 */
class ListFootInsetTest {

    private val resting = LkFrame.gutterFab

    @Test
    fun theKeyboardDropsTheFabReserve() {
        val inset = listFootInset(keyboardUp = true, selecting = false, resting = resting)
        assertTrue(inset < resting, "the FAB is hidden while the keyboard is up; $inset still reserved for it")
    }

    @Test
    fun withoutTheKeyboardTheRestingReserveIsUntouched() {
        assertEquals(resting, listFootInset(keyboardUp = false, selecting = false, resting = resting))
    }

    @Test
    fun aLiveSelectionKeepsItsReserveEvenWithTheKeyboardUp() {
        val bar = 180.dp
        assertEquals(bar, listFootInset(keyboardUp = true, selecting = true, resting = bar))
    }
}

package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * What a Location item's one action slot says in each state of a capture.
 *
 * Three earlier fixes on this screen each solved one complaint and set up the next:
 *  - 2026-09-03, *"how come we have 2 saves?"* — Save and "Keep the rough reading" were both live.
 *  - F-25, 2026-09-15 — Save was then hidden while capturing, so its slot moved three times in one flow.
 *  - 2026-10-09, *"why is Save even visible before Take a reading?"* — Save was then kept on screen
 *    but disabled, and a disabled outlined plate read as a button that could be pressed.
 *
 * The slot below answers all three at once: one slot that never moves, at most one commit-shaped
 * action in it, and no disabled button anywhere — a control that cannot act is simply not drawn.
 */
class LocationActionSlotTest {

    @Test
    fun `an empty form offers only the capture, with no Save on screen`() {
        val slot = locationActions(capturing = false, offerable = false, hasFix = false)
        assertEquals(LocationAction.TAKE_READING, slot.main)
        assertNull(slot.secondary, "nothing else is on screen before the first reading")
    }

    @Test
    fun `a search with nothing usable yet offers only Cancel`() {
        val slot = locationActions(capturing = true, offerable = false, hasFix = false)
        assertEquals(LocationAction.CANCEL, slot.main)
        assertNull(slot.secondary)
    }

    /** Interim readings land in the form while the search runs, so `hasFix` is true here too. */
    @Test
    fun `an interim reading that is not yet worth keeping still offers only Cancel`() {
        val slot = locationActions(capturing = true, offerable = false, hasFix = true)
        assertEquals(LocationAction.CANCEL, slot.main)
        assertNull(slot.secondary)
    }

    @Test
    fun `a usable reading offers Keep, with Cancel as the way out`() {
        for (hasFix in listOf(false, true)) {
            val slot = locationActions(capturing = true, offerable = true, hasFix = hasFix)
            assertEquals(LocationAction.KEEP_READING, slot.main)
            assertEquals(LocationAction.CANCEL, slot.secondary)
        }
    }

    @Test
    fun `a kept reading offers Save, with a new reading as the retry`() {
        val slot = locationActions(capturing = false, offerable = false, hasFix = true)
        assertEquals(LocationAction.SAVE, slot.main)
        assertEquals(LocationAction.RETAKE, slot.secondary)
    }

    /**
     * The 2026-09-03 rule as an invariant: Save never shares the screen with the capture control.
     * Before this slot existed it was held by disabling Save, which is the plate the 2026-10-09
     * report objected to.
     */
    @Test
    fun `Save never appears while a capture is running`() {
        for (offerable in listOf(false, true)) {
            for (hasFix in listOf(false, true)) {
                val slot = locationActions(capturing = true, offerable = offerable, hasFix = hasFix)
                assertNotEquals(LocationAction.SAVE, slot.main)
                assertNotEquals(LocationAction.SAVE, slot.secondary)
            }
        }
    }

    /** The 2026-10-09 rule: no state draws Save before there is a reading for it to write. */
    @Test
    fun `Save never appears without a reading`() {
        for (capturing in listOf(false, true)) {
            for (offerable in listOf(false, true)) {
                val slot = locationActions(capturing = capturing, offerable = offerable, hasFix = false)
                assertNotEquals(LocationAction.SAVE, slot.main)
                assertNotEquals(LocationAction.SAVE, slot.secondary)
            }
        }
    }
}

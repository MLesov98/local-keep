package com.localkeep.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tile morph runs on the way into an item and never on the way back out.
 *
 * The morph is what *opening* an item looks like: the row's tile lifts out of the list and becomes
 * the detail screen's header. Run in reverse it is the same flight, and it was read as the opening
 * animation being replayed — measured at `animator_duration_scale 10`, the list was already at 93%
 * of its settled luminance while the tile was still halfway down the screen and the row it was
 * heading for was drawn empty. Going back is not an event.
 *
 * The crossing is symmetric — whichever screen is leaving sits at `PostExit` in both directions — so
 * the direction is expressed by the destination end alone declaring that it only morphs on arrival.
 * Both halves of that are pinned here: drop either and the fix silently reverts to the old flight.
 */
class LkTileMorphTest {

    @Test
    fun `the list row morphs while it leaves, which is the item opening`() {
        assertTrue(
            lkTileMorphs(
                paired = true,
                reduceMotion = false,
                morphsOnArrivalOnly = false,
                leaving = true,
            ),
        )
    }

    @Test
    fun `the detail header does not morph while it leaves, which is the pop`() {
        assertFalse(
            lkTileMorphs(
                paired = true,
                reduceMotion = false,
                morphsOnArrivalOnly = true,
                leaving = true,
            ),
        )
    }

    @Test
    fun `the detail header morphs while it arrives`() {
        assertTrue(
            lkTileMorphs(
                paired = true,
                reduceMotion = false,
                morphsOnArrivalOnly = true,
                leaving = false,
            ),
        )
    }

    @Test
    fun `an unpaired tile never morphs, and reduced motion never morphs`() {
        assertFalse(
            lkTileMorphs(
                paired = false,
                reduceMotion = false,
                morphsOnArrivalOnly = false,
                leaving = false,
            ),
        )
        assertFalse(
            lkTileMorphs(
                paired = true,
                reduceMotion = true,
                morphsOnArrivalOnly = false,
                leaving = false,
            ),
        )
    }
}

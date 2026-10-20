package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the card face announces must be what the card face draws.
 *
 * The wizard's review step draws the last four digits — that landed 2026-09-11 so that the step
 * asking "Check it over" could actually be used to check the field most likely to carry a typo. The
 * contentDescription went on saying "number hidden" regardless, so a sighted user could check the
 * number there and a screen-reader user was told there was nothing to check. Found on a device by
 * the pre-iOS sweep the same day, in a dump reading
 * `Visa card, number hidden, expires 01 / 20` beside a face showing `•••• •••• •••• 1111`.
 *
 * These drive [cardNumberAnnouncement] rather than the composable, which needs a device to observe.
 */
class CardNumberAnnouncementTest {

    @Test
    fun `a masked face announces the digits it is showing`() {
        assertEquals(
            CardNumberAnnouncement.Ending("1111"),
            cardNumberAnnouncement("4111111111111111", masked = true),
            "the review step draws the last four, so it must announce them",
        )
    }

    @Test
    fun `an unmasked face announces the whole number`() {
        assertEquals(
            CardNumberAnnouncement.Full,
            cardNumberAnnouncement("4111111111111111", masked = false),
        )
    }

    @Test
    fun `nothing typed is still hidden`() {
        assertEquals(CardNumberAnnouncement.Hidden, cardNumberAnnouncement("", masked = true))
        assertEquals(CardNumberAnnouncement.Hidden, cardNumberAnnouncement("", masked = false))
    }

    /**
     * Below four digits the face masks nothing, so announcing "ending 123" is the truth and a fixed
     * four-character window would have been a different lie from the one this replaced.
     */
    @Test
    fun `a part-typed number announces exactly what is drawn`() {
        assertEquals(
            CardNumberAnnouncement.Ending("123"),
            cardNumberAnnouncement("123", masked = true),
        )
    }
}

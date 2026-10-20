package com.localkeep.feature.vaultlist

import com.localkeep.core.vault.RevealSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the reveal banner counts down.
 *
 * The banner is persistent, and that is correct — it tracks a state, and hiding it while the state
 * held would leave the app quietly showing concealed entries with nothing on screen saying so. What
 * made it read as a nag (reported 2026-09-02: *"the yellow window doesn't disappear until I click,
 * even if I go to another folder"*) is that it named no end. The state now has one, and this is the
 * arithmetic that puts it on screen.
 *
 * Clock-injected on purpose: the number the user sees is decided here, not by a frame clock, so it
 * can be asserted at the boundaries that actually matter — the last second, and the moment past the
 * deadline.
 */
class RevealCountdownTest {

    @Test
    fun `a full window reads as the whole window`() {
        assertEquals(120, revealSecondsLeft(expiresAtElapsedMs = 120_000L, nowElapsedMs = 0L))
    }

    @Test
    fun `time already spent is gone from the count`() {
        assertEquals(75, revealSecondsLeft(expiresAtElapsedMs = 120_000L, nowElapsedMs = 45_000L))
    }

    /**
     * The rounding is up, and it is the point of the function existing at all. Truncating would sit
     * the banner on "0" for a whole second while it is demonstrably still on screen and the entries
     * are demonstrably still showing — a countdown that lies at exactly the moment it matters most.
     */
    @Test
    fun `the last second still reads as one second`() {
        assertEquals(1, revealSecondsLeft(expiresAtElapsedMs = 1_000L, nowElapsedMs = 1L))
        assertEquals(1, revealSecondsLeft(expiresAtElapsedMs = 1_000L, nowElapsedMs = 999L))
    }

    @Test
    fun `the deadline itself reads as zero`() {
        assertEquals(0, revealSecondsLeft(expiresAtElapsedMs = 1_000L, nowElapsedMs = 1_000L))
    }

    /**
     * A late tick — the device slept through the deadline, or the loop was descheduled — must floor
     * at zero rather than count upwards into a negative. The manager conceals on the same deadline,
     * so this frame is the last one the banner ever draws; drawing "-14" on it would be the app's
     * final word on the subject.
     */
    @Test
    fun `a tick after the deadline never goes negative`() {
        assertEquals(0, revealSecondsLeft(expiresAtElapsedMs = 1_000L, nowElapsedMs = 60_000L))
    }

    /**
     * The banner renders `m:ss` from this, so a window that is not a whole number of minutes must
     * still divide cleanly. 120 s is 2:00; anything that made this return 119 on the first tick would
     * show 1:59 the instant the phrase was accepted, which reads as a session already running out.
     */
    @Test
    fun `the first tick of a real session shows the window, not one less`() {
        val started = 10_000L
        val deadline = started + RevealSession.WINDOW_MS
        val first = revealSecondsLeft(deadline, started)
        assertEquals((RevealSession.WINDOW_MS / 1_000L).toInt(), first)
        assertTrue(first % 60 == 0, "the window is not a whole number of minutes; check the m:ss copy")
    }
}

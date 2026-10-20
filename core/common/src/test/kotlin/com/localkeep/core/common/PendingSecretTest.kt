package com.localkeep.core.common

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [PendingSecret] is three lines of code, and the one that matters is the one that forgets.
 *
 * It holds a plaintext password outside the vault for the length of one navigation. If [take] ever
 * stops consuming, that password stays in the process for the rest of the session and is handed to
 * the *next* item the user creates — silently, since a prefilled password field looks like one the
 * user filled. Cheap to assert, expensive to notice.
 *
 * Since 2026-09-15 it carries a second field, the entropy of the draw that produced the password
 * ([PendingSecret.Offer.entropyBits]) — see F-29. The forgetting has to cover that too: a bit count
 * left behind would be attached to the next password the editor adopts and would describe a
 * different string, which is the same class of defect as the password itself surviving.
 */
class PendingSecretTest {

    @AfterTest
    fun tearDown() = PendingSecret.clear()

    @Test
    fun `take hands over the offered secret exactly once`() {
        PendingSecret.offer("correct-horse-battery-staple")

        assertEquals("correct-horse-battery-staple", PendingSecret.take()?.secret)
        assertNull(PendingSecret.take(), "the secret survived being collected")
    }

    /**
     * The generator names its value from a bit count, and the item form scores a *typed* password
     * with zxcvbn. Carrying only the string meant the editor re-derived a verdict from the
     * characters and contradicted the one the user had just read — F-29, 2026-09-15. Nothing
     * downstream can recover the figure, because a string does not say which pool it came from, so
     * it travels here or not at all.
     */
    @Test
    fun `the draw's own entropy travels with the secret`() {
        PendingSecret.offer("correct-horse-battery-staple", 77.5)

        val offer = PendingSecret.take()

        assertEquals("correct-horse-battery-staple", offer?.secret)
        assertEquals(77.5, offer?.entropyBits)
    }

    /** A handover with no measure behind it says so, rather than inventing a number. */
    @Test
    fun `an unmeasured handover carries no bit count`() {
        PendingSecret.offer("typed-by-hand")

        assertNull(PendingSecret.take()?.entropyBits)
    }

    @Test
    fun `nothing is waiting until something is offered`() {
        assertNull(PendingSecret.take())
    }

    @Test
    fun `clear discards a handover nobody collected`() {
        // The abandoned path: the user asked to save a generated password and then locked the vault
        // instead. Nothing consumes it, so the lock has to.
        PendingSecret.offer("abandoned")

        PendingSecret.clear()

        assertNull(PendingSecret.take())
    }

    @Test
    fun `a second offer replaces the first rather than queueing`() {
        // Two taps on "Save as a new login" must not leave the older password to surface later.
        PendingSecret.offer("first")
        PendingSecret.offer("second")

        assertEquals("second", PendingSecret.take()?.secret)
        assertNull(PendingSecret.take())
    }

    /**
     * And the bit count goes with it. A replacement that kept the previous draw's entropy would
     * hand the editor a figure describing a password it never received — the verdict would be
     * wrong, and wrong in a way that looks authoritative because it is stated in bits.
     */
    @Test
    fun `a replacement carries its own entropy, never the previous draw's`() {
        PendingSecret.offer("first", 128.0)
        PendingSecret.offer("second", 77.5)

        assertEquals(77.5, PendingSecret.take()?.entropyBits)
    }

    /** Both halves are forgotten together, because they are one object. */
    @Test
    fun `clear discards the bit count with the password`() {
        PendingSecret.offer("abandoned", 128.0)

        PendingSecret.clear()

        assertNull(PendingSecret.take())
    }
}

package com.localkeep.core.common

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The handoff that stops a freshly-saved item from vanishing.
 *
 * Saving a login from the drawer's "2FA codes" returned the user to a list that could not show it —
 * "2FA codes · 1 item", the new login nowhere, and nothing saying it had saved (device-confirmed
 * 2026-08-10). These pin the one property the fix depends on: the id is delivered exactly once.
 */
class PendingRevealTest {

    @BeforeTest
    @AfterTest
    fun reset() = PendingReveal.clear()

    @Test
    fun `an offered id is delivered once`() {
        PendingReveal.offer("abc")
        assertEquals("abc", PendingReveal.take())
    }

    /**
     * The load-bearing one. Without single delivery, every later return to the list — a pop back
     * from any item, a tab switch — would re-clear the filters and re-highlight a row the user
     * stopped caring about several screens ago.
     */
    @Test
    fun `a taken id is not delivered again`() {
        PendingReveal.offer("abc")
        PendingReveal.take()
        assertNull(PendingReveal.take())
    }

    @Test
    fun `nothing offered means nothing to reveal`() {
        assertNull(PendingReveal.take())
    }

    /** Two saves without an intervening list: the newer item is the one worth pointing at. */
    @Test
    fun `a second offer replaces the first`() {
        PendingReveal.offer("first")
        PendingReveal.offer("second")
        assertEquals("second", PendingReveal.take())
    }

    /** Locking abandons the reveal — see LocalKeepApplication's lock-state collector. */
    @Test
    fun `clear drops a pending reveal`() {
        PendingReveal.offer("abc")
        PendingReveal.clear()
        assertNull(PendingReveal.take())
    }
}

package com.localkeep.core.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * How long a tap on "Unlock Pro" or "Restore purchases" waits for an unreachable Play (S5-09,
 * 2026-10-02).
 *
 * One budget served everything: five connects at 1 s doubling, with the 16 s wait spent after the
 * last one too — 31 s of silence before "could not be started", while Restore held every other
 * Settings control disabled. A connect whose callback never came held it for ever. User actions
 * now get [USER_ACTION_CONNECT], and every connect is bounded on its own.
 *
 * Virtual time, so seconds of budget cost nothing to run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectBudgetTest {

    @Test
    fun `a user action gives up on an unreachable Play within seconds`() = runTest {
        var attempts = 0
        val connected = connectWithin(USER_ACTION_CONNECT) {
            attempts++
            false
        }
        assertFalse(connected)
        assertTrue(
            currentTime <= 5_000,
            "a tap waited ${currentTime}ms for a Play that answered 'no' at once",
        )
        assertTrue(attempts >= 2, "a user action should still retry once, not give up on a blip")
    }

    @Test
    fun `a connect that never answers is abandoned`() = runTest(timeout = 10.seconds) {
        val connected = connectWithin(USER_ACTION_CONNECT) { awaitCancellation() }
        assertFalse(connected)
        assertTrue(
            currentTime <= 10_000,
            "a connect with no callback held the caller for ${currentTime}ms",
        )
    }

    @Test
    fun `a connect that answers is used at once`() = runTest {
        assertTrue(connectWithin(USER_ACTION_CONNECT) { true })
        assertEquals(0L, currentTime)
    }

    /** The work nobody is watching keeps its patience — and is bounded now too. */
    @Test
    fun `background work still retries harder than a tap`() = runTest(timeout = 10.seconds) {
        var attempts = 0
        val connected = connectWithin(BACKGROUND_CONNECT) {
            attempts++
            awaitCancellation()
        }
        assertFalse(connected)
        assertTrue(attempts > USER_ACTION_CONNECT.attempts)
    }
}

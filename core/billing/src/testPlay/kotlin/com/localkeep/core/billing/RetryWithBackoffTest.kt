package com.localkeep.core.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RetryWithBackoffTest {

    @Test
    fun `stops at the first attempt that succeeds`() = runBlocking {
        var attempts = 0
        val succeeded = retryWithBackoff(attempts = 4, initialDelayMs = 1L) {
            attempts++
            true
        }
        assertTrue(succeeded)
        assertEquals(1, attempts)
    }

    @Test
    fun `keeps trying after a failure`() = runBlocking {
        var attempts = 0
        val succeeded = retryWithBackoff(attempts = 4, initialDelayMs = 1L) {
            attempts++
            attempts == 3
        }
        assertTrue(succeeded)
        assertEquals(3, attempts)
    }

    @Test
    fun `gives up once the attempt budget is spent`() = runBlocking {
        var attempts = 0
        val succeeded = retryWithBackoff(attempts = 4, initialDelayMs = 1L) {
            attempts++
            false
        }
        assertFalse(succeeded)
        assertEquals(4, attempts)
    }

    @Test
    fun `waits longer after each failure`() = runBlocking {
        val startedAt = System.nanoTime()
        retryWithBackoff(attempts = 3, initialDelayMs = 20L) { false }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        // 20ms then 40ms — two waits for three attempts, doubling each round.
        assertTrue(elapsedMs >= 60, "backoff waited only ${elapsedMs}ms")
    }
}

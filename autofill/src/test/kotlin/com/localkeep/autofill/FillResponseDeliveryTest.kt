package com.localkeep.autofill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A fill response small enough to cross one binder transaction, delivered without crashing.
 *
 * Found 2026-10-02: one dataset per matching login with no ceiling, and `callback.onSuccess` called
 * outside the `runCatching` that guarded the build, on a coroutine scope with no exception handler.
 * `FillCallback` rethrows a failed binder call as a RuntimeException, so a host matching enough
 * logins would have killed this process on top of the app being filled. The size at which that
 * happens is an estimate; whether the platform throws is its behaviour and needs a device.
 */
class FillResponseDeliveryTest {

    @Test
    fun `a host matching a hundred logins gets the first twenty`() {
        val rows = (1..100).toList()

        assertEquals((1..MAX_FILL_DATASETS).toList(), rows.cappedForOneResponse())
    }

    @Test
    fun `a short list is untouched`() {
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).cappedForOneResponse())
    }

    @Test
    fun `a delivery that throws is reported, not rethrown onto the thread`() {
        var reported: Throwable? = null

        deliverGuarded(onError = { reported = it }) {
            throw RuntimeException("android.os.TransactionTooLargeException")
        }

        assertIs<RuntimeException>(reported)
    }

    @Test
    fun `a delivery that succeeds reports nothing`() {
        var delivered = false
        var reported: Throwable? = null

        deliverGuarded(onError = { reported = it }) { delivered = true }

        assertEquals(true, delivered)
        assertEquals(null, reported)
    }
}

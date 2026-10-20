package com.localkeep.core.common

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * [runCatchingOutcome] is the catch-all wrapper around parsing, crypto and file work, so whatever it
 * keeps about a failure travels with the error object into view models, logs and debuggers.
 * Exception messages from those layers quote the input they choked on — a password, a TOTP URI, a
 * decrypted line of an export — so the throwable must not be carried along.
 */
class OutcomeTest {

    private class LeakyFailure(secret: String) : IllegalStateException("could not parse '$secret'")

    @Test
    fun `a successful block is passed through unwrapped`() {
        assertEquals(Outcome.Success(7), runCatchingOutcome { 7 })
    }

    @Test
    fun `a thrown failure becomes a generic Unknown error`() {
        val outcome = runCatchingOutcome { error("boom") }

        assertEquals(Outcome.Failure(AppError.Unknown("Unexpected failure")), outcome)
    }

    @Test
    fun `the caught throwable is not retained as the cause`() {
        val secret = "correct-horse-battery-staple"

        val outcome = runCatchingOutcome { throw LeakyFailure(secret) }

        val error = (outcome as Outcome.Failure).error
        assertNull(error.cause, "the exception chain survived and can be walked back to the secret")
        assertFalse(secret in error.message, "the secret reached a user-visible message")
        assertFalse(secret in error.toString(), "the secret reached the error's own toString")
    }

    @Test
    fun `cancellation is rethrown rather than reported as a failure`() {
        assertFailsWith<CancellationException> {
            runCatchingOutcome { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `map transforms a success and leaves a failure untouched`() {
        assertEquals(Outcome.Success(4), Outcome.Success(2).map { it * 2 })

        val failure = Outcome.Failure(AppError.Validation("nope"))
        assertEquals(failure, failure.map { error("must not run") })
    }

    @Test
    fun `flatMap chains and short-circuits`() {
        assertEquals(
            Outcome.Success("2"),
            Outcome.Success(2).flatMap { Outcome.Success(it.toString()) },
        )

        val failure = Outcome.Failure(AppError.NotFound("gone"))
        assertEquals(failure, failure.flatMap { Outcome.Success(it) })
    }

    @Test
    fun `fold and getOrNull expose the two branches`() {
        assertEquals("ok:1", Outcome.Success(1).fold({ "ok:$it" }, { "err:${it.message}" }))
        assertEquals(
            "err:gone",
            Outcome.Failure(AppError.NotFound("gone")).fold({ "ok:$it" }, { "err:${it.message}" }),
        )

        assertEquals(1, Outcome.Success(1).getOrNull())
        assertNull(Outcome.Failure(AppError.NotFound("gone")).getOrNull())
    }
}

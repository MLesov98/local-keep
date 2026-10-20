package com.localkeep.core.vault

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A memory failure on unlock must be retried before either memory message reaches the screen.
 *
 * Filed 2026-09-08 on a fresh `LocalKeep_API_29` AVD: onboard, tap **Open my vault**, type the
 * password, and the answer was `unlock_error_needs_more_memory` — "open it on a phone with more
 * memory" — about a three-item vault that device had just made. Force-stopping and unlocking again
 * worked, three times out of three. So the app already had the right answer available; it simply
 * reported the wrong one first, on a data-loss-shaped topic, to a brand-new user.
 *
 * The retry is safe because the failing path has no effects: `VaultLockManager.unlock`'s
 * `OutOfMemoryError` handler is reached from inside the derivation, before a session is published
 * and before anything is written, and `recordFailure` belongs to a slot scan that ran to
 * completion. A memory failure has never cost the backoff, so a second attempt costs none either.
 */
class UnlockMemoryRetryTest {

    @Test
    fun `a memory failure that clears on the next attempt is never reported`() = runTest {
        val answers = mutableListOf(UnlockResult.VAULT_NEEDS_MORE_MEMORY, UnlockResult.REAL)
        assertEquals(UnlockResult.REAL, retryingMemoryFailures { answers.removeAt(0) })
        assertEquals(0, answers.size, "the second attempt must actually run")
    }

    /** The transient sibling takes the same second chance — both come from the same handler. */
    @Test
    fun `a transient memory failure that clears on the next attempt is never reported`() = runTest {
        val answers = mutableListOf(UnlockResult.VAULT_MEMORY_TRANSIENT, UnlockResult.DURESS)
        assertEquals(UnlockResult.DURESS, retryingMemoryFailures { answers.removeAt(0) })
    }

    /**
     * The control that keeps this a retry rather than a loop: a vault that genuinely does not fit
     * still gets told so, and after exactly one extra derivation.
     */
    @Test
    fun `a memory failure that persists is reported`() = runTest {
        var attempts = 0
        val result = retryingMemoryFailures {
            attempts++
            UnlockResult.VAULT_NEEDS_MORE_MEMORY
        }
        assertEquals(UnlockResult.VAULT_NEEDS_MORE_MEMORY, result)
        assertEquals(UNLOCK_MEMORY_ATTEMPTS, attempts)
    }

    /**
     * The other control: nothing else is retried. A wrong password answered twice would derive
     * twice for every typo, and `WRONG_CREDENTIAL` is the one result that has already been counted
     * against the unlock backoff by the time it is returned.
     */
    @Test
    fun `a non-memory result is answered on the first attempt`() = runTest {
        for (result in listOf(
            UnlockResult.WRONG_CREDENTIAL,
            UnlockResult.VAULT_UNREADABLE,
            UnlockResult.VAULT_FROM_NEWER_VERSION,
            UnlockResult.INTERRUPTED,
            UnlockResult.ALREADY_UNLOCKED,
            UnlockResult.REAL,
        )) {
            var attempts = 0
            val answered = retryingMemoryFailures {
                attempts++
                result
            }
            assertEquals(result, answered)
            assertEquals(1, attempts, "$result must not be retried")
        }
    }
}

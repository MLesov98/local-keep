package com.localkeep.feature.vaultlist

import com.localkeep.core.vault.RevealAttemptOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Found 2026-10-02, the half that was a defect rather than a design: a search that
 * matches nothing runs a 19 MiB Argon2id derivation (`maybeAttemptReveal` → `attemptHiddenReveal`),
 * and unlike every other in-app derivation it was not guarded against the heap having no room for it.
 * The `OutOfMemoryError` escaped through the debounce coroutine, which has no handler.
 *
 * The other half — that ordinary no-result searches count towards the reveal throttle — is the
 * design, not a defect, and is deliberately untouched. A miss, a hit and "no phrase configured" must
 * cost the same, or the search box becomes an oracle for whether something is hidden; the same
 * property is why an ordinary miss is indistinguishable from a wrong guess and counted as one.
 */
class RevealProbeMemoryGuardTest {

    @Test
    fun `a heap with no room for the derivation reads as a wrong guess`() {
        val outcome = runBlocking {
            rejectingOnOutOfMemory { throw OutOfMemoryError("Failed to allocate a 19922960 byte allocation") }
        }

        // FAILS WITHOUT THE FIX: the error propagated to the caller.
        assertEquals(RevealAttemptOutcome.Rejected, outcome)
    }

    @Test
    fun `the guard does not disturb a result`() {
        runBlocking {
            assertEquals(RevealAttemptOutcome.Revealed, rejectingOnOutOfMemory { RevealAttemptOutcome.Revealed })
            assertEquals(RevealAttemptOutcome.Rejected, rejectingOnOutOfMemory { RevealAttemptOutcome.Rejected })
        }
    }

    @Test
    fun `cancellation is not swallowed`() {
        // The debounce is cancelled by the next keystroke; mapping that to Rejected would let a
        // cancelled coroutine carry on as though it had been answered.
        assertFailsWith<CancellationException> {
            runBlocking { rejectingOnOutOfMemory { throw CancellationException("superseded") } }
        }
    }

    @Test
    fun `other failures are not swallowed either`() {
        assertFailsWith<IllegalStateException> {
            runBlocking { rejectingOnOutOfMemory { throw IllegalStateException("not a memory failure") } }
        }
    }

    @Test
    fun `the search box reaches the derivation only through the guard`() {
        val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListViewModel.kt")
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val lines = source.readLines()
        val call = lines.indexOfFirst { it.contains("lockManager.attemptHiddenReveal(") }
        assertTrue(call >= 0, "VaultListViewModel no longer calls lockManager.attemptHiddenReveal")
        val around = lines.subList(maxOf(0, call - 2), call + 1)
        assertTrue(
            around.any { it.contains("rejectingOnOutOfMemory") },
            "lockManager.attemptHiddenReveal must be called inside rejectingOnOutOfMemory { }. " +
                "Unguarded, a search that matches nothing can throw OutOfMemoryError from the " +
                "debounce coroutine. Found:\n${around.joinToString("\n")}",
        )
        assertEquals(
            1,
            lines.count { it.contains("lockManager.attemptHiddenReveal(") },
            "a second, unguarded call site for attemptHiddenReveal exists",
        )
    }
}

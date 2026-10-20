package com.localkeep.core.vault

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test: saving a Recovery Kit must never do its file I/O on the main thread.
 *
 * `commitPreparedRecoveryKit` reads the whole of `vault.enc`, copies it, writes it back and calls
 * `fd.sync()` plus a directory fsync — a synchronous sequence with no suspension point, over a file
 * measured at ~8 MiB for a 10,000-item vault. It had no dispatcher of its own, and all three of its
 * production call sites reach it from `RecoveryKitViewModel` through `viewModelScope.launch`, whose
 * dispatcher is `Dispatchers.Main.immediate`. So finishing the Recovery Kit flow ran every byte of
 * it on the UI thread. Found 2026-09-13 — the same
 * defect class `commit` was rewritten for, and `unlock` before that.
 *
 * **Why this is a source assertion and not a behavioural one.** Same reasoning as
 * [UnlockDispatcherTest]: the function needs an Android `Context` and a real container, so
 * exercising it belongs in the instrumented suite, and even there "which thread did the write run
 * on" means instrumenting the dispatcher. What can be checked exactly is the property that was
 * missing — that the dispatcher lives in the function rather than in its callers, where one caller
 * will eventually forget.
 */
class RecoveryKitCommitDispatcherTest {

    private val source = java.io.File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")

    @Test
    fun `committing a Recovery Kit owns its dispatcher instead of trusting call sites`() {
        val body = bodyOf("suspend fun commitPreparedRecoveryKit(")

        val switch = body.indexOfFirst { it.contains("withContext(Dispatchers.IO)") }
        val mutex = body.indexOfFirst { it.contains("operationMutex.withLock") }
        val write = body.indexOfFirst { it.contains("AtomicVaultWriter.write(") }

        assertTrue(
            switch >= 0,
            "commitPreparedRecoveryKit() must switch to Dispatchers.IO itself. Without it, the " +
                "whole-file read, the copy, the write and two fsyncs run on whatever thread the " +
                "caller is on — Dispatchers.Main.immediate, for all three RecoveryKitViewModel " +
                "call sites. Found here:\n${body.joinToString("\n")}",
        )
        assertTrue(mutex >= 0, "commitPreparedRecoveryKit() no longer takes operationMutex")
        assertTrue(
            switch < mutex,
            "the dispatcher switch must precede operationMutex.withLock, or the lock is taken — " +
                "and everything under it runs — on the caller's thread",
        )
        assertTrue(write >= 0, "commitPreparedRecoveryKit() no longer writes the container")
        assertTrue(
            switch < write,
            "the write and its fsync are the expensive half; they must sit inside the switch",
        )
    }

    /**
     * The control. `commit` already owns its dispatcher and documents why; this fails if the fix
     * above is ever "unified" by moving both switches out to the call sites.
     */
    @Test
    fun `saving the vault still owns its dispatcher too`() {
        val body = bodyOf("suspend fun commit(newVault: Vault)")

        val switch = body.indexOfFirst { it.contains("withContext(Dispatchers.IO)") }
        val mutex = body.indexOfFirst { it.contains("operationMutex.withLock") }

        assertTrue(switch >= 0, "commit() must switch to Dispatchers.IO itself")
        assertTrue(mutex >= 0, "commit() no longer takes operationMutex")
        assertTrue(switch < mutex, "commit()'s dispatcher switch must precede operationMutex.withLock")
    }

    /**
     * The slice this test reads, from a declaration to its closing brace.
     *
     * Both ends are asserted rather than assumed. A source-scanning test that slices after a marker
     * it never found reads the whole file instead, and then passes on someone else's `withContext`
     * — this repo has been burned by exactly that, so the anchor and the bound are both failures
     * with their own message. The bound is the first line that is exactly four spaces and a closing
     * brace, which in this file's style is a member's own close and nothing nested inside it.
     */
    private fun bodyOf(declaration: String): List<String> {
        if (!source.isFile) {
            fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        }
        val lines = source.readLines()
        val start = lines.indexOfFirst { it.contains(declaration) }
        assertTrue(start >= 0, "VaultLockManager no longer declares `$declaration`")
        val end = lines.drop(start + 1).indexOfFirst { it == "    }" }
        assertTrue(
            end >= 0,
            "no member-level closing brace after `$declaration` — the slice is unbounded",
        )
        val body = lines.subList(start, start + 1 + end + 1)
        assertTrue(
            body.size in 2..200,
            "the slice for `$declaration` is ${body.size} lines, which is not a function body",
        )
        return body
    }
}

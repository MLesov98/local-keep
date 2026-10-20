package com.localkeep.core.vault

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test: unlock must never derive on the main thread.
 *
 * `VaultLockManager.unlock` runs two calibrated Argon2id slot derivations — ~250 ms each by
 * construction (`VaultInitializer` calibrates to `targetMillisPerSlot = 250L`) — plus a vault file
 * read. Three of its four call sites wrapped it in `withContext(Dispatchers.Default)` themselves.
 * `OnboardingViewModel.createVault`, the mandatory first-run path, did not, so on a brand-new vault
 * all of that ran on `Dispatchers.Main.immediate` behind a progress indicator that could not
 * animate.
 *
 * The fix moved the dispatcher inside `unlock`, matching what `commit` already documents about
 * owning its own — because a requirement that every call site has to remember is one a call site
 * will eventually forget, and this one already had.
 *
 * **Why this is a source assertion and not a behavioural one.** `unlock` needs an Android `Context`
 * and a real vault container, so exercising it belongs in the instrumented suite — and even there,
 * asserting "which thread did the KDF run on" means instrumenting the dispatcher. What can be
 * checked cheaply and exactly is the property that was actually missing: that the dispatcher lives
 * in the function rather than in its callers. This test fails if someone removes it, which is the
 * regression that matters. Stated plainly rather than dressed up as more than it is.
 */
class UnlockDispatcherTest {

    private val source = java.io.File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")

    @Test
    fun `unlock owns its dispatcher instead of trusting call sites to supply one`() {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val lines = source.readLines()

        // Matched on the opening of the parameter list rather than its full text. It used to name
        // `unlock(password: CharArray)` exactly, and broke the day a second parameter was added
        // (the progress reporter, 2026-08-10) — which is a change this test has no opinion about.
        val signature = lines.indexOfFirst { it.contains("suspend fun unlock(") }
        assertTrue(signature >= 0, "VaultLockManager no longer declares unlock(")

        // The dispatcher switch must come before operationMutex is taken — not somewhere deeper,
        // where only part of the work would move. The body starts at the `): UnlockResult` line,
        // wherever the parameter list ends.
        //
        // Bounded by the mutex rather than by a fixed three-line window, which is what this
        // asserted until 2026-09-11. That window broke the moment a `var` and its comment were
        // declared ahead of the switch (so the OutOfMemoryError handler could tell a vault that
        // does not fit the device from a process that is merely full — G9), which is a change this
        // test has no opinion about. Declaring a local does no work; taking the mutex and deriving
        // does, and that is what must happen on Default.
        val open = lines.drop(signature).indexOfFirst { it.contains("): UnlockResult") } + signature
        assertTrue(open >= signature, "unlock() no longer returns UnlockResult")
        val switch = lines.drop(open).indexOfFirst { it.contains("withContext(Dispatchers.Default)") }
        val mutex = lines.drop(open).indexOfFirst { it.contains("operationMutex.withLock") }
        val body = lines.subList(open, minOf(open + 12, lines.size)).joinToString("\n")
        assertTrue(
            switch >= 0,
            "unlock() must switch to Dispatchers.Default itself. Without it, a caller that forgets " +
                "runs two ~250 ms Argon2id derivations plus a file read on the main thread — which " +
                "is exactly what OnboardingViewModel.createVault did until 2026-07-28. Found here:\n$body",
        )
        assertTrue(mutex >= 0, "unlock() no longer takes operationMutex")
        assertTrue(
            switch < mutex,
            "the dispatcher switch must precede operationMutex.withLock, or the lock is taken — and " +
                "everything under it runs — on the caller's thread. Found here:\n$body",
        )
    }

    /**
     * The second KDF entry point on this class, which forgot the lesson above.
     *
     * `attemptHiddenReveal` runs a floor-cost Argon2id (`KdfParams.FLOOR` — 19 MiB, two passes,
     * BouncyCastle, no suspension point) and its only production caller schedules it on
     * `Dispatchers.Main.immediate`. Its `withContext(NonCancellable)` looked like a dispatcher
     * switch and is not one: `NonCancellable` is a `Job` element and carries no
     * `ContinuationInterceptor`, so `withContext` takes its same-interceptor undispatched path and
     * the derivation ran on the caller's thread. Every search that matched nothing visible — a
     * typo — blocked the UI thread for one full derivation, and unconditionally, because
     * `dummyRevealVerifier` makes a vault that never used hidden entries pay the same cost.
     *
     * The design doc required this off the main thread and nothing ever implemented it. `Default`
     * rather than the `IO` the doc names: this is CPU- and memory-bound, and it matches `unlock`.
     *
     * `NonCancellable` is not decoration and must stay. The search-box caller cancels the very job
     * this runs inside, and it is only the substituted `Job` that lets execution reach the KDF at
     * all — a "cleanup" to a plain `withContext(Dispatchers.Default)` silently breaks the reveal.
     */
    @Test
    fun `the hidden-reveal derivation owns its dispatcher too`() {
        val text = source.readText()
        val signature = text.indexOf("suspend fun attemptHiddenReveal(")
        assertTrue(signature >= 0, "VaultLockManager no longer declares attemptHiddenReveal(")

        val body = text.substring(signature, text.indexOf("suspend fun", signature + 1).takeIf { it > signature } ?: text.length)
        // Anchored on `return withContext(` rather than `withContext(`, so the KDoc and the
        // comment beside the call — both of which name the two context elements in prose — cannot
        // satisfy this test on the code's behalf.
        val switch = body.indexOf("return withContext(")
        assertTrue(switch >= 0, "attemptHiddenReveal no longer switches context at all")
        val call = body.substring(switch, body.indexOf(')', switch) + 1)
        assertTrue(
            call.contains("Dispatchers.Default"),
            "attemptHiddenReveal must switch to Dispatchers.Default itself: NonCancellable alone " +
                "carries no ContinuationInterceptor, so the 19 MiB Argon2id ran on whatever thread " +
                "the caller was on — Dispatchers.Main.immediate, for the search box. Found: $call",
        )
        assertTrue(
            call.contains("NonCancellable"),
            "NonCancellable must stay: the search-box caller cancels the job this runs in, and " +
                "removing it stops the reveal reaching the KDF at all. Found: $call",
        )
    }

    /**
     * The third KDF entry point, and the one with the most callers (2026-10-02, S5-07).
     *
     * `reauthenticate` runs a full slot-0 Argon2id — the vault's own calibrated cost, seconds on a
     * mid-range phone — and took the mutex on whatever thread called it. The Hidden-items gate, the
     * Recovery Kit gate, Export and Restore-snapshot all call it from a bare
     * `viewModelScope.launch`, so the derivation ran on `Main.immediate` and the progress label
     * those gates set a line earlier could not draw. Two other callers hopped for themselves, which
     * is the same "every call site must remember" arrangement `unlock` was moved away from.
     */
    @Test
    fun `reauthenticate owns its dispatcher too`() {
        val text = source.readText()
        val signature = text.indexOf("suspend fun reauthenticate(password: CharArray)")
        assertTrue(signature >= 0, "VaultLockManager no longer declares reauthenticate(password: CharArray)")
        val body = text.substring(signature, text.indexOf("\n    /**", signature).takeIf { it > signature } ?: text.length)

        // `{` included, so prose in a comment naming the dispatcher cannot satisfy this.
        val switch = body.indexOf("withContext(Dispatchers.Default) {")
        val mutex = body.indexOf("operationMutex.withLock")
        assertTrue(
            switch >= 0,
            "reauthenticate must switch to Dispatchers.Default itself — four gates call it from " +
                "Main.immediate and ran a full Argon2id on the UI thread. Found:\n$body",
        )
        assertTrue(mutex >= 0, "reauthenticate no longer takes operationMutex")
        assertTrue(switch < mutex, "the switch must precede operationMutex.withLock. Found:\n$body")

        // The wipe must enclose the switch, so a caller cancelled mid-derivation still wipes.
        val tryIndex = body.indexOf("try {")
        val finallyIndex = body.lastIndexOf("} finally {")
        assertTrue(tryIndex in 0 until switch, "the try whose finally wipes the password must open before the switch")
        assertTrue(
            finallyIndex > switch && "Zeroizer.wipe(password)" in body.substring(finallyIndex),
            "Zeroizer.wipe(password) must stay in the outermost finally, outside withContext",
        )
    }

    @Test
    fun `the password wipe stays outside the dispatcher switch`() {
        // The `finally` must not move inside `withContext`, or a cancellation during the derivation
        // would skip it and leave the master password resident in the CharArray.
        val text = source.readText()
        val finallyIndex = text.indexOf("} finally {", text.indexOf("suspend fun unlock("))
        assertTrue(finallyIndex > 0, "unlock() no longer has a finally block")
        val wipeIndex = text.indexOf("Zeroizer.wipe(password)", finallyIndex)
        assertTrue(
            wipeIndex > finallyIndex,
            "Zeroizer.wipe(password) must sit in unlock()'s own finally, outside withContext, so a " +
                "cancelled derivation still wipes the master password",
        )
    }
}

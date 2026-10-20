package com.localkeep.core.vault

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test: a journal write that fails must not unwind a rotation that already committed.
 *
 * `runBlobReKeyPass` re-encrypts every stored blob from the retired DEK to the fresh one, rewriting
 * `DekRotationJournal` after each. By the time it runs, `changeMasterPassword` has already written
 * the container, transferred the session DEK, revoked the Recovery Kit and deleted the
 * device-wrapped copy — the password *has* changed. Its own KDoc says so: "Failures are counted,
 * not thrown: a rotation that has already committed must still report itself as done".
 *
 * It did not do that. Only `hasBlob`/`reKeyBlob` sat inside the `runCatching`; the journal rewrite
 * lived in the `onSuccess` and `onFailure` lambdas, which Kotlin runs *outside* it. So a
 * `DekRotationJournal.write` that threw — a rename that does not finalize, or an IOException from
 * a full disk, the case the surrounding comments name repeatedly — escaped `rotateDekUnderSlotZero`
 * and `changeMasterPassword` and reached `SettingsViewModel.runOperation`, which has no branch for
 * it and reports `GENERIC_FAILURE`: "That didn't go through. Try again."
 *
 * What that costs is worse than the wrong sentence. The success path is what tells Settings the
 * Recovery Kit is gone and biometric unlock is off — both of which just happened — and what reports
 * the local snapshots `StaleContainerSweeper` destroyed. On the throw the user is told nothing
 * changed while both recovery routes are stale at once and local backup copies have been deleted.
 *
 * **Why this is a source assertion and not a behavioural one.** `runBlobReKeyPass` is private, and
 * everything around it needs an Android `Context` and a real container — this module's vault-lock
 * suite is instrumented for exactly that reason. What can be checked cheaply and exactly is the
 * property that was missing: that no journal write inside the pass can throw past the lambda it
 * sits in. Stated plainly rather than dressed up as more than it is; the same trade
 * [UnlockDispatcherTest] documents.
 */
class BlobReKeyJournalFailureTest {

    private val source = java.io.File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")

    /** Calls that touch the filesystem and therefore can throw on a full or failing disk. */
    private val throwingCalls = listOf("DekRotationJournal.write(", "store.opensUnder(")

    @Test
    fun `every journal rewrite in the blob pass is contained`() {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val lines = source.readLines()

        val start = lines.indexOfFirst { it.contains("private fun runBlobReKeyPass(") }
        assertTrue(start >= 0, "VaultLockManager no longer declares runBlobReKeyPass(")
        val end = lines.drop(start).indexOfFirst { it.contains("return BlobReKeyResult(") } + start
        assertTrue(end > start, "runBlobReKeyPass no longer ends with its BlobReKeyResult")
        val body = lines.subList(start, end)

        val guarded = body.indices.filter { index ->
            throwingCalls.any { body[index].contains(it) }
        }
        assertTrue(
            guarded.isNotEmpty(),
            "expected the pass to still rewrite the journal per blob — this test found no call to " +
                "guard, which means it is no longer checking anything",
        )

        guarded.forEach { index ->
            // Walk back to the lambda this call sits in. `onSuccess`/`onFailure` run outside the
            // enclosing runCatching, so a guard has to appear between the lambda's `{` and the call.
            var cursor = index
            var protected = false
            while (cursor > 0) {
                val line = body[cursor]
                if (line.contains("runCatching {")) {
                    protected = true
                    break
                }
                if (line.contains(".onSuccess {") || line.contains(".onFailure {")) break
                cursor--
            }
            assertTrue(
                protected,
                "line ${start + index + 1} of VaultLockManager.kt runs a throwing call outside any " +
                    "runCatching, inside an onSuccess/onFailure lambda:\n  ${body[index].trim()}\n" +
                    "The rotation has already committed by this point. A failure here reports a " +
                    "password change that DID happen as one that did not, and hides the destroyed " +
                    "snapshots and the revoked Recovery Kit with it.",
            )
        }
    }
}

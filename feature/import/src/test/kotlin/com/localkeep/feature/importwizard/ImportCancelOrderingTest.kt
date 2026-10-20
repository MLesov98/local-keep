package com.localkeep.feature.importwizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test: a long import must not keep running after the wizard is left.
 *
 * Backing out of the import wizard mid-parse used to do two wrong things. It zeroed `pendingBytes`
 * while a KDBX parse — Argon2 for the better part of a minute, per the code's own comment — was
 * still reading it; and, because nothing held the coroutine, that parse survived and wrote state to
 * a screen the user had left, either dropping an error onto the instructions step or setting
 * `step = PREVIEW` and pulling them into a preview of the import they had just cancelled.
 *
 * **What this test proves, and what it does not.** It pins the *structure* of the fix: that `back()`
 * goes through `cancelParseThenDiscard`, that the wipe is behind `join()`, and that `onCleared`
 * still wipes unconditionally. It does NOT observe the behaviour, because `ImportWizardViewModel`
 * needs a `Context`, a `VaultRepository` and a `VaultLockManager` to construct, `feature/import` has
 * no test source set at all today, and this repo ships no Robolectric. The behavioural version is an
 * instrumented test and is recorded as owed rather than quietly skipped.
 *
 * Every assertion here fails if the fix is reverted, which is the property a regression test has to
 * have. It is not a substitute for driving the wizard.
 */
class ImportCancelOrderingTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardViewModel.kt")

    /**
     * The body of one member, from its opening brace to the first closing brace at member indent.
     *
     * A first version sliced on the next `\n    fun `, which silently ran past the `private fun`
     * inserted between back() and onPasswordChanged and swallowed its body — so the "back() must not
     * call discardPending() directly" assertion failed against correct code. Slice on the brace.
     */
    private fun bodyOf(marker: String): String =
        text().substringAfter(marker).substringBefore("\n    }")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    @Test
    fun `back cancels the parse instead of wiping a buffer someone is reading`() {
        // The marker is the private overload, not the public `fun back()`, which is now a one-line
        // delegate: `back()` takes a `discardConfirmed` flag so that answering the discard dialog
        // can bypass the guard that raises it (see ImportPreviewDiscardGuardTest).
        val body = bodyOf("private fun back(discardConfirmed: Boolean) {")
        assertTrue(
            body.contains("cancelParseThenDiscard()"),
            "back() must cancel the in-flight parse before discarding its buffer. Calling " +
                "discardPending() directly zeroes the array a running parse is still reading.",
        )
        assertTrue(
            !body.contains("discardPending()"),
            "back() must not call discardPending() directly — that is the unordered wipe this fix " +
                "removed. Route it through cancelParseThenDiscard().",
        )
    }

    @Test
    fun `the wipe waits for the parse to actually stop`() {
        val body = bodyOf("private fun cancelParseThenDiscard() {")
        assertTrue(body.contains("job.cancel()"), "cancelParseThenDiscard must cancel the job")
        val joinIndex = body.indexOf("job.join()")
        val wipeIndex = body.indexOf("discardPending()", joinIndex.coerceAtLeast(0))
        assertTrue(joinIndex > 0, "the wipe must wait on job.join()")
        assertTrue(
            wipeIndex > joinIndex,
            "discardPending() must come AFTER job.join(). Kotlin cancellation is cooperative and " +
                "the parse body is a blocking call into KeePassJava2, so cancel() does not stop " +
                "Argon2 mid-derivation — wiping immediately after it just narrows the window on the " +
                "same bug.",
        )
    }

    @Test
    fun `onCleared still wipes unconditionally`() {
        // The deferred wipe rides on viewModelScope, which dies with the ViewModel. onCleared is
        // what guarantees the raw export is zeroed even if the join never gets to run.
        val body = bodyOf("override fun onCleared() {")
        assertTrue(
            body.contains("discardPending()"),
            "onCleared must still wipe directly — it is the backstop for a deferred wipe whose " +
                "scope was cancelled first",
        )
    }
}

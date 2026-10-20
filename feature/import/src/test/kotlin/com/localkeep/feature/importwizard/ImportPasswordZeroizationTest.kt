package com.localkeep.feature.importwizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test for a deferred finding from 2026-08-28: `ImportUiState.password` is a `String`, so the KDBX master password existed as an
 * immutable, un-zeroizable value from the first character typed. `submitPassword()` used to make
 * that worse than it had to be — it read `state.password`, launched the parse coroutine, and only
 * cleared the state field in a `finally` block that ran *after* `parse()` returned. A KDBX parse
 * runs Argon2id for the better part of a minute (see `ImportCancelOrderingTest`), so the plaintext
 * password sat reachable in `ImportUiState` — outside the ViewModel's control, visible to anything
 * holding the StateFlow — for the whole of that window.
 *
 * The fix converts to a `CharArray` and clears `password` from state in the same, synchronous
 * `_state.update` call that starts the parse, before `parseJob` is even assigned — matching
 * `UnlockViewModel.unlockWithPassword`, which clears its own `password` field before launching a
 * derivation of the same kind. Compose has no CharArray-native text field, so `LkPasswordField`
 * still needs a `String` to bind to while the user types; this narrows how long the *finished*
 * password stays reachable once it is no longer needed, it does not remove that copy — the same
 * honest scope recorded for this finding, and the same T3-out-of-scope tier as the
 * CharArray discipline it generalises.
 *
 * Same source-reading approach as [ImportCancelOrderingTest] and [ImportWizardHardeningTest], and
 * for the same reason: `feature/import` has no Robolectric, so a test that actually runs the
 * ViewModel is not available here. This pins the *structure* of the fix instead — it fails if the
 * fix is reverted, which is the property a regression test has to have.
 */
class ImportPasswordZeroizationTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardViewModel.kt")

    private fun bodyOf(marker: String): String =
        text().substringAfter(marker).substringBefore("\n    }")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    @Test
    fun `submitPassword clears the state field before launching the parse, not after it returns`() {
        val body = bodyOf("fun submitPassword() {")
        val launchIndex = body.indexOf("parseJob = viewModelScope.launch")
        assertTrue(launchIndex >= 0, "submitPassword() must still launch the parse coroutine")

        val beforeLaunch = body.substring(0, launchIndex)
        assertTrue(
            beforeLaunch.contains("password = \"\""),
            "submitPassword() must clear ImportUiState.password to \"\" before parseJob is " +
                "assigned — clearing it only in a `finally` after parse() returns leaves the " +
                "plaintext password reachable in state for the whole of the Argon2id derivation, " +
                "which runs for the better part of a minute.",
        )
    }

    @Test
    fun `the coroutine body converts a CharArray captured before it was launched, not state read from inside it`() {
        val body = bodyOf("fun submitPassword() {")
        val launchIndex = body.indexOf("parseJob = viewModelScope.launch")
        assertTrue(launchIndex >= 0, "submitPassword() must still launch the parse coroutine")

        val afterLaunch = body.substring(launchIndex)
        assertTrue(
            afterLaunch.contains("parse(bytes, passwordChars"),
            "the launched coroutine must pass a CharArray captured before launch — reading " +
                "`_state.value.password` from inside the coroutine would reintroduce a window " +
                "where the plaintext String is still needed after the field is meant to be clear.",
        )
    }
}

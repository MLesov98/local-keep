package com.localkeep.feature.unlock

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A rejected Recovery Kit keeps the words on screen.
 *
 * The error says "Check the order and the spelling — all 24 have to be right", and the same update
 * cleared the field, so the advice named something the app had just thrown away. A 24-word phrase
 * transcribed from paper fails most often on one transposition, and this is the flow a user reaches
 * only after losing their password: every failed attempt cost a full retype of all 24. Found by the
 * F14 QA persona, 2026-08-30.
 *
 * Retaining them exposes nothing new — the field is unmasked while being typed, by design, for the
 * same transcription reason. What must not change is the success path, which still clears.
 *
 * Asserted on the source because `UnlockViewModel` needs a `VaultLockManager` and a `Context` to
 * construct, and this module has no Robolectric; the same technique, and the same limitation, as
 * `ImportCancelOrderingTest`.
 */
class RecoveryWordsSurviveFailureTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/unlock/UnlockViewModel.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    /** The rejection branch, from its own condition to the return that ends it. */
    private fun wrongCredentialBranch(): String =
        text().substringAfter("if (result == UnlockResult.WRONG_CREDENTIAL) {")
            .substringBefore("return@launch")

    @Test
    fun `a rejected phrase is not erased`() {
        val branch = wrongCredentialBranch()
        assertTrue(
            "recoveryWords" !in branch,
            "the failure branch must not touch recoveryWords, or the error's advice names words " +
                "the app has deleted. Found: $branch",
        )
    }

    /** The control. If the success path stopped clearing, the words would outlive the unlock. */
    @Test
    fun `a successful unlock still clears them`() {
        assertTrue(
            """_state.update { it.copy(recoveryWords = "") }""" in text(),
            "the success path must still clear the words",
        )
    }

    /**
     * The error and the behaviour have to keep agreeing. If the message ever stops asking the user
     * to check what they typed, retaining it is no longer justified by this reasoning — and if it
     * still asks, the branch above must still keep them.
     */
    @Test
    fun `the failure message still asks the user to check what they typed`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val message = strings.substringAfter("""<string name="unlock_recovery_failed_message">""")
            .substringBefore("</string>")
        assertTrue(
            "order" in message.lowercase() || "spelling" in message.lowercase(),
            "Found: $message",
        )
    }
}

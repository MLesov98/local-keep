package com.localkeep.credential

import androidx.biometric.BiometricPrompt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A user saying no inside the provider must reach the calling app as a cancellation.
 *
 * Device evidence, 2026-10-04 on emulator-5562 (API 36, foss release) with `com.localkeep.passkeytest`:
 * Cancel or Back on "Allow this app?" came back as `CreateCredentialException.TYPE_UNKNOWN / native
 * app binding declined`, and leaving the "Confirm it's you" prompt as `TYPE_UNKNOWN / user
 * verification failed`. Dismissing the system's own sheet in the same flow gives
 * `TYPE_USER_CANCELED`, so a relying party can tell the two apart — and it treats Unknown as an
 * error to report or retry, where a cancel is a choice to respect silently.
 *
 * The API names this case: `CreateCredentialCancellationException` and
 * `GetCredentialCancellationException`, "the user intentionally cancels the flow".
 */
class CredentialCancelIsReportedAsCancelTest {

    private val source =
        File("src/main/kotlin/com/localkeep/credential/CredentialActivity.kt").readText()

    private fun body(name: String): String =
        source.substringAfter("private suspend fun $name(").substringBefore("\n    private ")

    @Test
    fun `leaving the freshness prompt is a cancellation`() {
        assertEquals(
            Verification.CANCELLED,
            biometricRefusal(BiometricPrompt.ERROR_USER_CANCELED, passwordOffered = false),
        )
    }

    @Test
    fun `dismissing the locked-vault prompt is a cancellation`() {
        assertEquals(
            Verification.CANCELLED,
            biometricRefusal(BiometricPrompt.ERROR_USER_CANCELED, passwordOffered = true),
        )
    }

    /**
     * Control: `ERROR_CANCELED` stays a failure. androidx documents it as the sensor becoming
     * unavailable — another operation, a user switch — not as something the user did, which is
     * the line `isUserChoice` in `core/crypto` draws for every other prompt in the app.
     */
    @Test
    fun `a prompt the system cancelled is not reported as the user's choice`() {
        assertEquals(
            Verification.FAILED,
            biometricRefusal(BiometricPrompt.ERROR_CANCELED, passwordOffered = false),
        )
    }

    /**
     * The wiring: driving [CredentialActivity] needs Credential Manager intents, so — as in
     * `CredentialActivityLockGuardTest` — the routes are asserted on the source.
     */
    @Test
    fun `declining the native binding consent is reported as a cancellation`() {
        val handleCreate = body("handleCreate")
        val decline = handleCreate.substringAfter("!nativeConfirmed) {").substringBefore("}")
        assertTrue(
            "finishCreateCancelled(" in decline,
            "Cancel/Back on 'Allow this app?' must finish with the cancellation exception. " +
                "Found: $decline",
        )
    }

    @Test
    fun `every verification entry point routes a cancel to a cancellation finisher`() {
        listOf(
            "handleCreate" to "finishCreateCancelled(",
            "handleGet" to "finishGetCancelled(",
            "handleUnlock" to "finishGetCancelled(",
        ).forEach { (name, finisher) ->
            val text = body(name)
            assertTrue(
                "Verification.CANCELLED" in text && finisher in text,
                "$name must route Verification.CANCELLED to $finisher. Found: $text",
            )
        }
        assertTrue(
            "CreateCredentialCancellationException(" in source &&
                "GetCredentialCancellationException(" in source,
            "the cancellation finishers must use the API's cancellation exceptions",
        )
    }

    /** The reprompt-flagged password's fresh check is the fourth place a prompt can be left. */
    @Test
    fun `the reprompt check routes a cancel to the cancellation finisher`() {
        val reprompt = body("handleGet")
            .substringAfter("val fresh = confirmFreshBiometric(")
            .substringBefore("val passkeyContent")
        assertTrue(
            "Verification.CANCELLED" in reprompt && "finishGetCancelled(" in reprompt,
            "a dismissed reprompt check must be reported as a cancellation. Found: $reprompt",
        )
    }

    @Test
    fun `cancelling the master-password dialog is a cancellation`() {
        val dialog = source.substringAfter("private suspend fun unlockWithMasterPassword(")
            .substringBefore("\n    /**")
        assertTrue(
            "answers.receive() ?: return Verification.CANCELLED" in dialog,
            "the dialog's dismiss button is the user declining, not a failed verification. " +
                "Found: $dialog",
        )
    }
}

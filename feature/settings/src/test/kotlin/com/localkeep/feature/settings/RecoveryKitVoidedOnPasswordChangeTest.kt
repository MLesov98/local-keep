package com.localkeep.feature.settings

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.vault.LocalKeepSettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A master-password change revokes the Recovery Kit, so the app must stop believing one exists.
 *
 * Both change routes rotate the data key, and the rotation writes a fresh noise block over the
 * recovery region — the 24 words are revoked on disk, deliberately, so a retired kit fails at once
 * rather than at the moment someone is relying on it. The flag that feeds the Settings row means
 * "a kit exists" (`RecoveryKitGateTest` pins that nothing but a real export may set it), and it had
 * no false path at all: the row went on showing its neutral summary over a kit that opened nothing,
 * and the one-time notice about the void is an in-composition note that dies with the ViewModel.
 * UI/UX spec 2026-07-23 §3.3 requires that row to carry the consequence *while no kit exists*.
 *
 * The two structural cases are asserted against the source for the reason `RecoveryKitGateTest`
 * gives for the same move: the behavioural alternative needs a real unlocked vault, and both
 * `VaultLockManager.changeMasterPassword` and `setMasterPasswordWithRecoveryKit` are `internal` to
 * `:core:vault`, so no test in this module can stand a `ChangeResult` up to drive the path. What is
 * worth pinning is structural anyway: *that* the clear happens, and *that* it happens only after
 * the change succeeded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryKitVoidedOnPasswordChangeTest {

    private fun viewModelSource(): String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsViewModel.kt").readText()

    private fun passwordChangeBody(): String =
        codeOnly(viewModelSource())
            .substringAfter("private fun runPasswordChange(")
            .substringBefore("\n    }")

    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `settings can forget a kit that has stopped working`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        LocalKeepSettings.setRecoveryKitSaved(context)
        assertTrue(LocalKeepSettings.isRecoveryKitSaved(context), "precondition: a kit was exported")

        LocalKeepSettings.forgetRecoveryKitSaved(context)

        assertFalse(
            LocalKeepSettings.isRecoveryKitSaved(context),
            "a revoked kit must read as no kit, or the Settings row cannot carry the debt",
        )
    }

    @Test
    fun `a voided kit is cleared from settings by the password change`() {
        val body = passwordChangeBody()
        assertTrue(
            "forgetRecoveryKitSaved" in body,
            "runPasswordChange must clear the saved-kit flag; the rotation revoked the kit and " +
                "nothing else in the app ever writes that flag false. Body: $body",
        )
        assertTrue(
            "oldRecoveryKitVoided" in body.substringBefore("forgetRecoveryKitSaved"),
            "the clear must be guarded by the result's own report that the kit was voided, not " +
                "run unconditionally.",
        )
    }

    @Test
    fun `a refused change leaves the existing kit alone`() {
        // The load-bearing ordering. A refused change — a mistyped current password, wrong 24 words
        // — leaves the old data key and the old kit both live. Clearing the flag there would put a
        // permanent "no kit" warning on a kit that still opens the vault, which is the same class
        // of lie in the opposite direction.
        val body = passwordChangeBody()
        val refusal = body.indexOf("if (!result.success)")
        val clear = body.indexOf("forgetRecoveryKitSaved")
        assertTrue(refusal >= 0, "the refusal check must still be here. Body: $body")
        assertTrue(clear >= 0, "the clear must still be here. Body: $body")
        assertTrue(
            clear > refusal,
            "the saved-kit flag must only be cleared after the change is known to have succeeded.",
        )
    }
}

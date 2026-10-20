package com.localkeep.credential

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.UnlockResult
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A locked vault must not dead-end a credential ceremony (2C-F14).
 *
 * Device evidence, 2026-09-20 on emulator-5554 with no biometrics enrolled: creating a passkey with
 * the vault locked failed with `CreateCredentialException.TYPE_UNKNOWN / biometric unlock is
 * unavailable — open LocalKeep and unlock with your master password`. Honest, but terminal: the
 * ceremony is over, and the only route on is to leave the calling app, open LocalKeep and start
 * again. Autofill's `AuthActivity` has offered an in-flow master password all along.
 *
 * Three things are pinned. The dialog exists and can be answered; a locked vault reaches it when —
 * and only when — the biometric unlock is *unavailable* rather than merely refused; and a failed
 * attempt names its real cause instead of accusing the user of mistyping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialUnlockDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val submitted = mutableListOf<String>()
    private var cancelled = 0

    private fun show(error: String? = null, working: Boolean = false) {
        compose.setContent {
            LocalKeepTheme {
                CredentialUnlockDialog(
                    error = error,
                    working = working,
                    onSubmit = { submitted += it },
                    onCancel = { cancelled++ },
                )
            }
        }
    }

    @Test
    fun `the ceremony offers a master password when the vault is locked`() {
        show()
        compose.onNodeWithText("Unlock LocalKeep").assertIsDisplayed()
        compose.onNodeWithText("Your vault is locked. Enter your master password to continue here.")
            .assertIsDisplayed()
        compose.onNodeWithTag("dialog-confirm").assertIsDisplayed()
        compose.onNodeWithTag("dialog-dismiss").assertIsDisplayed()
    }

    /**
     * An empty field is not a guess. `VaultLockManager.unlock` records a knowledge-factor failure
     * against the brute-force throttle for one, so a stray tap would cost a real attempt and answer
     * with the wrong-password message.
     */
    @Test
    fun `an empty password cannot be submitted`() {
        show()
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("hunter2")
        compose.onNodeWithTag("dialog-confirm").assertIsEnabled()
    }

    @Test
    fun `the typed password reaches the unlock`() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("TestVaultAbc99")
        compose.onNodeWithTag("dialog-confirm").performClick()
        assertEquals(listOf("TestVaultAbc99"), submitted)
        assertEquals(0, cancelled, "submitting also cancelled the ceremony")
    }

    @Test
    fun `declining is an answer the ceremony can report`() {
        show()
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertEquals(1, cancelled)
        assertTrue(submitted.isEmpty(), "declining submitted a password")
    }

    /** While Argon2id runs, the control says so and cannot be pressed again. */
    @Test
    fun `a derivation in flight is named and not re-submittable`() {
        show(working = true)
        compose.onNodeWithText("Unlocking…").assertIsDisplayed()
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
    }

    /**
     * `LkPasswordField.label` is a semantics name, not a drawn one: the well alone read as an unnamed
     * box over a third-party app, and with no eye there was no way to check a long master password
     * before spending an attempt on it.
     */
    @Test
    fun `the password well is named on screen`() {
        show()
        compose.onNodeWithText("MASTER PASSWORD").assertIsDisplayed()
    }

    @Test
    fun `the password can be revealed and hidden again`() {
        show()
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide password").performClick()
        compose.onNodeWithContentDescription("Show password").assertIsDisplayed()
    }

    @Test
    fun `a failed attempt shows what actually went wrong`() {
        show(error = "Wrong password")
        compose.onNodeWithText("Wrong password").assertIsDisplayed()
    }

    /**
     * The mapping, which is the half that would rot silently: every non-success has to reach its
     * own message rather than the wrong-password wording. A throttled attempt, an unreadable vault
     * file and a lock landing inside the derivation are not a mistyped password.
     */
    @Test
    fun `each failure names its own cause`() {
        val wrong = credentialUnlockMessage(
            CredentialUnlockOutcome.Failed(UnlockResult.WRONG_CREDENTIAL),
        )
        listOf(
            UnlockResult.VAULT_UNREADABLE,
            UnlockResult.VAULT_FROM_NEWER_VERSION,
            UnlockResult.INTERRUPTED,
            UnlockResult.BIOMETRIC_UNAVAILABLE,
            UnlockResult.VAULT_NEEDS_MORE_MEMORY,
            UnlockResult.VAULT_MEMORY_TRANSIENT,
        ).forEach { result ->
            assertNotEquals(
                wrong,
                credentialUnlockMessage(CredentialUnlockOutcome.Failed(result)),
                "$result was reported as a mistyped password",
            )
        }
        assertEquals(
            CredentialUnlockText.Throttled(3),
            credentialUnlockMessage(CredentialUnlockOutcome.Throttled(2_400)),
            "a throttle must round its remaining wait up to whole seconds",
        )
        assertEquals(
            CredentialUnlockText.None,
            credentialUnlockMessage(CredentialUnlockOutcome.Success),
        )
    }

    /**
     * The wiring. Driving [CredentialActivity] needs Credential Manager intents and a real
     * biometric prompt, neither of which exists here, so the two facts that make the dialog
     * reachable are asserted on the source — the same idiom, and the same reason, as
     * `CredentialActivityLockGuardTest`.
     *
     * Both halves matter. A locked vault must fall back to the password, and it must fall back
     * only when the biometric cannot run or the user asked for the password: a cancelled prompt is
     * an answer, and retrying it as a password prompt would turn "not now" into a second demand.
     * Which outcomes fall back is pinned behaviourally by `LockedCeremonyPasswordRouteTest`, against
     * the top-level [openLockedVault]; this test pins that the activity actually goes through it.
     */
    @Test
    fun `a locked vault reaches the password only when the biometric cannot run`() {
        val source =
            File("src/main/kotlin/com/localkeep/credential/CredentialActivity.kt").readText()
        val fallback = source.substringAfter("private suspend fun openLockedVault(")
            .substringBefore("\n    /**")
        assertTrue(
            "biometric = { unlockWithBiometric() }" in fallback,
            "openLockedVault must still try the CryptoObject-bound biometric first. Found: $fallback",
        )
        assertTrue(
            "password = { unlockWithMasterPassword() }" in fallback,
            "openLockedVault must fall back to the master password, or a locked vault dead-ends " +
                "the ceremony again. Found: $fallback",
        )
        listOf("ensurePasskeyVerification", "ensureUnlocked").forEach { entry ->
            val body = source.substringAfter("private suspend fun $entry(").substringBefore("\n    /**")
            assertTrue(
                "LockState.Locked -> openLockedVault()" in body,
                "$entry must route its Locked branch through openLockedVault. Found: $body",
            )
        }
    }
}

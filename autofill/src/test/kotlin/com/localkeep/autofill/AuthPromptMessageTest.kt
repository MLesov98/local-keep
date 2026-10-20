package com.localkeep.autofill

import com.localkeep.core.vault.UnlockResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The autofill gate must not accuse the user of mistyping when they did not.
 *
 * `LockState` spells out why: told their password is wrong, someone with a damaged vault retypes
 * it, fails, and concludes the vault is lost. The unlock screen has said the true thing for a
 * while; this gate collapsed every failure into "Couldn't authenticate" until now.
 *
 * Asserts on [AuthPromptText] identity, not resolved English text: resolving a string resource
 * needs a `Context`, and this module's unit tests run on the plain JVM with no Robolectric
 * dependency. The resource id `authPromptMessage` picks is exactly what these tests are
 * responsible for — see `AuthPromptText.resolve` for where the id becomes displayed text.
 */
class AuthPromptMessageTest {

    @Test
    fun aDamagedVaultIsNotReportedAsAWrongPassword() {
        val text = authPromptMessage(AuthOutcome.Failed(UnlockResult.VAULT_UNREADABLE))

        assertEquals(AuthPromptText.Plain(R.string.autofill_auth_error_vault_unreadable), text)
        assertNotEquals(AuthPromptText.Plain(R.string.autofill_auth_error_wrong_password), text)
    }

    @Test
    fun aLockDuringDerivationIsNotReportedAsAWrongPassword() {
        val text = authPromptMessage(AuthOutcome.Failed(UnlockResult.INTERRUPTED))

        assertEquals(AuthPromptText.Plain(R.string.autofill_auth_error_interrupted), text)
        assertNotEquals(AuthPromptText.Plain(R.string.autofill_auth_error_wrong_password), text)
    }

    /** The throttle says how long, because that is the only thing that helps. */
    @Test
    fun aThrottledAttemptSaysHowLongIsLeft() {
        assertEquals(
            AuthPromptText.Throttled(seconds = 8),
            authPromptMessage(AuthOutcome.Throttled(retryAfterMs = 7_200)),
        )
    }

    /** And the one case that really is a mistyped password still says so plainly. */
    @Test
    fun aWrongPasswordStillSaysWrongPassword() {
        assertEquals(
            AuthPromptText.Plain(R.string.autofill_auth_error_wrong_password),
            authPromptMessage(AuthOutcome.Failed(UnlockResult.WRONG_CREDENTIAL)),
        )
    }
    /**
     * The case that would have been reported as a mistyped password.
     *
     * `VAULT_NEEDS_MORE_MEMORY` means the vault's own Argon2id cost exceeds what this device gives
     * one app — the password is very probably right and no amount of retyping changes anything.
     * Until 2026-07-29 this mapper ended in `else -> "Wrong password"`, so the case inherited the
     * one message it must never carry. The `else` is gone; this is what stops it coming back.
     */
    @Test
    fun aVaultTooLargeForTheDeviceIsNotReportedAsAWrongPassword() {
        val text = authPromptMessage(AuthOutcome.Failed(UnlockResult.VAULT_NEEDS_MORE_MEMORY))

        assertEquals(AuthPromptText.Plain(R.string.autofill_auth_error_needs_more_memory), text)
        assertNotEquals(AuthPromptText.Plain(R.string.autofill_auth_error_wrong_password), text)
    }
}

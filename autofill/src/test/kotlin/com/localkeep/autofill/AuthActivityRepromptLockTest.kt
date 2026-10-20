package com.localkeep.autofill

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A reprompt row can outlive the session that built it, and the gate has to notice.
 *
 * The auto-lock timer runs on wall clock while an autofill row sits in another app's menu — the
 * race `PickerActivity` documents at its own lock check — and ACTION_SCREEN_OFF locks at once. When
 * the row is finally tapped, `AuthActivity` opens in `MODE_REPROMPT` and calls
 * `VaultLockManager.reauthenticate`, which throws `VaultSessionEndedException` on a locked vault
 * before it reaches any KDF or throttle. That was caught and reported as `UnlockResult.INTERRUPTED`
 * — "That took long enough for the vault to re-lock. Try again." — advice that could never come
 * true, because nothing on this screen could unlock the vault and every retry took the same throw.
 * Of the four autofill activities this was the only one that never read `lockState` at all.
 *
 * Nothing is relaxed by unlocking here instead: `unlock` verifies the same master password against
 * the same slot, shares the same knowledge-factor backoff, and re-enters the duress-aware session
 * that keeps a concealed item out of `finishWithDataset`.
 *
 * The decision is asserted directly; the wiring is asserted on the source, because driving the
 * activity itself needs a real autofill session over a third-party form — the same reason
 * `PickerLockCancelTest` reads its own activity's source.
 */
class AuthActivityRepromptLockTest {

    private val source =
        File("src/main/kotlin/com/localkeep/autofill/AuthActivity.kt").readText()

    private fun onCreateBody(): String =
        source.substringAfter("override fun onCreate(").substringBefore("private fun authenticate(")

    private fun authenticateBody(): String =
        source.substringAfter("private fun authenticate(")
            .substringBefore("private suspend fun finishWithResponse(")

    @Test
    fun `a reprompt against a locked vault opens the vault rather than re-verifying`() {
        assertTrue(
            autofillGateMustUnlock(AuthActivity.MODE_REPROMPT, unlocked = false),
            "a reprompt on a locked vault must unlock; re-verifying can only ever fail",
        )
    }

    @Test
    fun `a reprompt against an open vault still re-verifies`() {
        // The control. Re-auth is the point of a reprompt when there is a session to re-verify;
        // a fix that unlocked unconditionally would turn every per-item challenge into a no-op
        // against an already-open vault.
        assertFalse(
            autofillGateMustUnlock(AuthActivity.MODE_REPROMPT, unlocked = true),
            "an open vault must still be re-verified, not re-unlocked",
        )
    }

    @Test
    fun `the unlock gate unlocks whatever the lock state says`() {
        // The other control: MODE_UNLOCK is reached from a locked response, and a stale "unlocked"
        // reading must not divert it into a re-auth that cannot rebuild the fill response.
        assertTrue(autofillGateMustUnlock(AuthActivity.MODE_UNLOCK, unlocked = false))
        assertTrue(autofillGateMustUnlock(AuthActivity.MODE_UNLOCK, unlocked = true))
    }

    @Test
    fun `the prompt is titled from the lock state`() {
        val body = onCreateBody()
        assertTrue(
            body.contains("lockManager.lockState"),
            "onCreate must read the lock state to know which gate it is drawing; found: $body",
        )
    }

    @Test
    fun `the attempt consults the lock state before choosing re-authentication`() {
        val body = authenticateBody()
        val read = body.indexOf("lockManager.lockState")
        val reauth = body.indexOf("lockManager.reauthenticate(")
        assertTrue(
            reauth >= 0,
            "the reprompt must still be able to re-authenticate an open vault; found: $body",
        )
        assertTrue(
            read in 0 until reauth,
            "the lock state must be read at the moment of the attempt, before re-authentication " +
                "is chosen — the vault can lock while the password is being typed; found the " +
                "read at $read, the re-auth at $reauth",
        )
    }
}

package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Minting a Recovery Kit costs a fresh challenge.
 *
 * An unlocked, unattended phone used to be two taps from a durable credential. Both the drawer and
 * the Settings row land on this screen, "Create a Recovery Kit" asked for nothing, and "Show the QR
 * code" then "I captured the QR code" commits — the whole path in-app, with no system dialog to
 * interrupt it. What the attacker walks away with is 24 words that open the vault after it locks,
 * and the owner's paper kit is revoked in the same move. Revealing a *stored* seed phrase already
 * cost a challenge (`seedPhrase` is in `ALWAYS_REAUTH_FIELDS`), so the vault's own recovery words
 * costing nothing was the asymmetry. Opportunistic physical access is T2, in scope by the threat
 * model. Found 2026-08-30, kept as PARTIAL on verification: the reachability is real,
 * the finding's "silently revokes the paper kit at Create" is not — revocation happens at commit.
 *
 * Asserted on the source for the two wiring facts a JVM test cannot otherwise reach: a
 * `BiometricPrompt`-free ViewModel still needs a `VaultLockManager`, and a composable gate is a
 * branch, not a value. The same technique, and the same reason, as `RecoveryKitGateTest`'s
 * `readiness is derived from the export alone`.
 */
class RecoveryKitAuthGateTest {

    private val viewModelSource =
        File("src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitViewModel.kt").readText()
    private val screenSource =
        File("src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitScreen.kt").readText()

    /**
     * The default, which is the whole gate. Every other assertion here is about not losing it: a
     * state that starts authorized is a gate that has already been passed by nobody.
     */
    @Test
    fun `a fresh state is not authorized`() {
        assertFalse(RecoveryKitUiState().authorized)
    }

    /**
     * Leaving the screen must re-arm it. `forget()` resets the whole state object, so this holds by
     * construction today — and this is the test that notices if it ever becomes a field-by-field
     * copy that keeps `authorized` because it looked harmless.
     */
    @Test
    fun `forgetting the screen re-arms the gate`() {
        assertTrue(
            viewModelSource.contains("_state.value = RecoveryKitUiState()"),
            "forget() and revokeAuthorization() must reset the whole state object, not clear " +
                "fields one at a time — a copy that keeps `authorized` re-opens the gate for free.",
        )
    }

    /** Fail closed at the funnel, not only at the button that is no longer composed. */
    @Test
    fun `createKit refuses an unauthorized session`() {
        val body = viewModelSource
            .substringAfter("fun createKit() {")
            .substringBefore("prepareRecoveryKit")
        assertTrue(
            "if (!_state.value.authorized) return" in body,
            "createKit must return before prepareRecoveryKit when the gate is closed. Found: $body",
        )
    }

    /**
     * The gate is the first branch, ahead of the one that decides between the explanation and the
     * words. Ordering is the assertion: a gate checked after `state.mnemonic.isBlank()` would let a
     * kit that survived in state render itself before anyone was asked for anything.
     */
    @Test
    fun `the screen composes the gate before anything about the kit`() {
        val gate = screenSource.indexOf("if (!state.authorized) {")
        val kit = screenSource.indexOf("state.mnemonic.isBlank()")
        assertTrue(gate > 0, "the screen must branch on state.authorized")
        assertTrue(kit > 0, "the screen must still branch on whether a kit has been made")
        assertTrue(
            gate < kit,
            "the authorization branch must come first; found the gate at $gate and the kit at $kit",
        )
    }

    /**
     * The gate must not become a duress oracle. `reauthenticateForCurrentVault` answers about
     * whichever vault this session opened, so nothing here may ask which one that is — a message
     * that differed by a single word between the real and the decoy session is the whole game.
     */
    @Test
    fun `nothing in the gate branches on duress`() {
        // `isDuress` is the only way to ask, so its absence from the code is the assertion. Both
        // files *discuss* duress at length in comments, and must keep doing so — the reasoning is
        // why the branch is missing, and a reader who cannot see it will add one back.
        val code = (viewModelSource + screenSource)
            .lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "isDuress" in code,
            "the Recovery Kit gate must report identical resource ids in both sessions",
        )
    }
}

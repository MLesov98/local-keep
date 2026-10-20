package com.localkeep.credential

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for 38-2 (`verdicts/38-2.json`, CONFIRMED).
 *
 * `unlockWithBiometric` mapped `UnlockResult` to `Verification` with a trailing
 * `else -> Verification.FAILED`, whose message is "user verification failed". Three results fell
 * into it that are not verification failures at all:
 *
 *  - `ALREADY_UNLOCKED`, which `UnlockResult`'s own KDoc calls "not an error: the user asked for an
 *    open vault and the vault is open" and whose documented reason for existing is this very
 *    surface. `VaultLockManager.unlockWithBiometric` returns it whenever something else opened the
 *    vault between this activity's `lockState` read and its own check — and the whole biometric
 *    prompt sits inside that window, plus the operation mutex makes the call wait out an in-flight
 *    master-password unlock. By then a CryptoObject-bound Class 3 biometric has already passed, so
 *    the calling app was told the finger did not match for a request that should simply succeed.
 *  - `INTERRUPTED`, a lock landing inside the derivation — which `CredentialActivityLockGuardTest`
 *    calls "the recurring exposure" for this activity.
 *  - the vault layer's failed-attempt throttle, which `checkThrottle` throws as
 *    `com.localkeep.core.vault.VaultLockedException` *before* any unwrap is attempted. This file
 *    declares its own file-private `VaultLockedException` meaning something else, so the throttle
 *    was not caught by name and landed in the bare `catch (_: Exception)`: a lockout reported to
 *    the calling app as a retryable verification failure.
 *
 * Asserted on the source, matching `CredentialActivityLockGuardTest`'s own stated reason: driving
 * this activity needs Credential Manager intents and a real biometric prompt, neither of which
 * exists on the JVM, and this module has no Robolectric.
 */
class BiometricUnlockResultMappingTest {

    private val source =
        File("src/main/kotlin/com/localkeep/credential/CredentialActivity.kt").readText()

    private val unlockWithBiometric: String =
        source.substringAfter("private suspend fun unlockWithBiometric(): Verification {")
            .substringBefore("\n    /**")

    /**
     * Production line under test: `UnlockResult.ALREADY_UNLOCKED` in the OK branch of the `when` in
     * `unlockWithBiometric`. Remove it from that branch and this test goes red.
     */
    @Test
    fun `an already-open vault is not reported as a failed verification`() {
        val okBranch = unlockWithBiometric
            .substringAfter("UnlockResult.REAL")
            .substringBefore("Verification.OK")

        assertTrue(
            "ALREADY_UNLOCKED must map to Verification.OK — the biometric already passed and the " +
                "vault is open, which is exactly what the request asked for; found: $okBranch",
            "UnlockResult.ALREADY_UNLOCKED" in okBranch,
        )
    }

    /**
     * Production lines under test: the exhaustive arms of the `when` in `unlockWithBiometric`.
     * Collapse any of them back into an `else ->` and this test goes red.
     *
     * Control assertion, which held *before* the fix and must keep holding: `BIOMETRIC_UNAVAILABLE`
     * still maps to `Verification.UNAVAILABLE`. That distinction is the whole point of the
     * `Verification` enum's own KDoc ("says nothing about the master password, and must never be
     * reported as if it did"), and an exhaustive-`when` rewrite is exactly the kind of edit that
     * could flatten it by accident.
     */
    @Test
    fun `the unlock-result mapping is exhaustive rather than swept into an else`() {
        assertFalse(
            "the trailing `else ->` must be gone: it read 'anything I have not thought about is a " +
                "verification failure', and an exhaustive `when` is what makes the next " +
                "UnlockResult addition a build failure instead of a silent mislabel; found: " +
                unlockWithBiometric,
            "else -> Verification" in unlockWithBiometric,
        )

        val everyResult = listOf(
            "REAL",
            "DURESS",
            "ALREADY_UNLOCKED",
            "BIOMETRIC_UNAVAILABLE",
            "INTERRUPTED",
            "WRONG_CREDENTIAL",
            "VAULT_UNREADABLE",
            "VAULT_FROM_NEWER_VERSION",
            "VAULT_NEEDS_MORE_MEMORY",
        )
        everyResult.forEach { result ->
            assertTrue(
                "UnlockResult.$result must be named in the mapping, or the `when` is not " +
                    "exhaustive and the compiler cannot flag the next addition",
                "UnlockResult.$result" in unlockWithBiometric,
            )
        }

        assertTrue(
            "control: BIOMETRIC_UNAVAILABLE must still map to Verification.UNAVAILABLE, not be " +
                "flattened into the exhaustive rewrite",
            "UnlockResult.BIOMETRIC_UNAVAILABLE -> Verification.UNAVAILABLE" in unlockWithBiometric,
        )
    }

    /**
     * Production lines under test: `UnlockResult.INTERRUPTED -> Verification.INTERRUPTED` and the
     * `catch (_: VaultThrottledException)` arm in `unlockWithBiometric`, plus the aliased import.
     * Delete either arm and this test goes red.
     */
    @Test
    fun `a lock or a throttle is reported as interrupted, not as a bad fingerprint`() {
        assertTrue(
            "the vault layer's throttle must be imported under an alias — this file declares its " +
                "own file-private VaultLockedException meaning something else entirely",
            "import com.localkeep.core.vault.VaultLockedException as VaultThrottledException" in source,
        )
        assertTrue(
            "a lock landing inside the derivation is not a verification failure",
            "UnlockResult.INTERRUPTED -> Verification.INTERRUPTED" in unlockWithBiometric,
        )
        // Against the *last* bare catch: the first one in this function belongs to the
        // `decryptCipher` try above, which the throttle cannot reach.
        val typed = unlockWithBiometric.indexOf("catch (_: VaultThrottledException)")
        val bare = unlockWithBiometric.lastIndexOf("catch (_: Exception)")
        assertTrue(
            "the throttle must be caught by name, ahead of the bare catch on the unlock call, or " +
                "a lockout reaches the calling app as 'user verification failed' with advice to " +
                "retry; found: $unlockWithBiometric",
            typed in 0 until bare,
        )
    }

    /**
     * Production lines under test: the `Verification.INTERRUPTED` branches ahead of each
     * `finishCreateError`/`finishGetError` in `handleCreate`, `handleGet` and `handleUnlock`.
     * Delete any one and this test goes red for that entry point.
     *
     * Without them the new verdict would still be reported through the Unknown exception, which is
     * the API's "this will not work" — the opposite of what a lock or a throttle means. This file
     * already uses the Interrupted finishers for exactly that at three other sites.
     */
    @Test
    fun `every verification entry point routes interrupted to the interrupted finisher`() {
        fun body(name: String): String =
            source.substringAfter("private suspend fun $name(").substringBefore("\n    private ")

        listOf(
            Triple("handleCreate", "ensurePasskeyVerification()", "finishCreateInterrupted("),
            Triple("handleGet", "ensurePasskeyVerification()", "finishGetInterrupted("),
            Triple("handleUnlock", "ensureUnlocked()", "finishGetInterrupted("),
        ).forEach { (name, entry, finisher) ->
            val text = body(name)
            assertTrue("$name must still verify via $entry", entry in text)
            assertTrue(
                "$name must route Verification.INTERRUPTED to $finisher rather than to the " +
                    "Unknown-exception finisher; found: $text",
                "Verification.INTERRUPTED" in text && finisher in text,
            )
        }
    }
}

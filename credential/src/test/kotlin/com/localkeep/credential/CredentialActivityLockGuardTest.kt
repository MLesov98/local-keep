package com.localkeep.credential

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vault can lock while the biometric prompt is on screen, and reading it afterwards must not
 * kill the process.
 *
 * `handleGet` raises a prompt and then reads the vault. The auto-lock timer has no foreground gate,
 * so it can expire during a prompt the user then successfully answers; `visibilitySnapshot()` goes
 * through `requireVault()`, whose `check(...)` throws a plain `IllegalStateException`, and the read
 * sat outside the only `runCatching` in the function — inside the bare `lifecycleScope.launch` in
 * `onCreate`. An uncaught throw there kills the process on top of whatever app raised the sheet,
 * instead of the interrupted result this file already knows how to build. The passkey path is the
 * recurring exposure, because it always confirms a fresh biometric.
 *
 * `handleUnlock`'s `CredentialEntryFactory.response` read is the same shape through a narrower
 * window, and the credential-build catch tested for a private exception type this activity's own
 * `requireDek` throws — so even a throw raised *inside* that `runCatching` by the repository was
 * routed to "credential use failed" rather than to the interrupted path.
 *
 * Asserted on the source: driving this activity needs Credential Manager intents and a real
 * biometric prompt, neither of which exists on the JVM, and this module has no Robolectric.
 */
class CredentialActivityLockGuardTest {

    private val source =
        File("src/main/kotlin/com/localkeep/credential/CredentialActivity.kt").readText()

    private fun body(name: String): String =
        source.substringAfter("private suspend fun $name(").substringBefore("\n    private ")

    @Test
    fun `the post-prompt vault read in handleGet cannot escape as a crash`() {
        val handleGet = body("handleGet")
        val read = handleGet.indexOf("repository.visibilitySnapshot()")
        assertTrue("the vault read must still be there; found: $handleGet", read >= 0)
        val guard = handleGet.lastIndexOf("runCatching {", read)
        assertTrue(
            "the post-verification vault read must sit inside a runCatching. Found: $handleGet",
            guard in 0 until read,
        )
        // The nearest preceding runCatching must be the one wrapping this read, not an earlier
        // unrelated one — `handleGet` opens two before it reaches the vault, and matching either of
        // those would let the assertion pass over the unguarded read it exists to catch.
        assertTrue(
            "the vault read must be the first statement inside that runCatching. Found: $handleGet",
            handleGet.substring(guard + "runCatching {".length, read).isBlank(),
        )
    }

    @Test
    fun `a locked vault is reported as interrupted, not as a failure`() {
        val handleGet = body("handleGet")
        assertTrue(
            "a read that fails because the vault re-locked must finish with " +
                "finishGetInterrupted(LOCKED_MESSAGE). Found: $handleGet",
            handleGet.split("finishGetInterrupted(LOCKED_MESSAGE)").size - 1 >= 2,
        )
    }

    @Test
    fun `a lock during the credential build is not misread as a use failure`() {
        assertTrue(
            "the credential-build catch tests a private type only this activity throws; the " +
                "repository throws a plain IllegalStateException, so the live lock state has to " +
                "be consulted too",
            "cause is VaultLockedException || lockManager.lockState.value !is LockState.Unlocked"
                in body("handleGet"),
        )
    }

    @Test
    fun `the entry-building read in handleUnlock cannot escape as a crash`() {
        val handleUnlock = body("handleUnlock")
        val read = handleUnlock.indexOf("CredentialEntryFactory.response(")
        assertTrue("the response build must still be there; found: $handleUnlock", read >= 0)
        val guard = handleUnlock.lastIndexOf("runCatching {", read)
        assertTrue(
            "CredentialEntryFactory.response must sit inside a runCatching. Found: $handleUnlock",
            guard in 0 until read,
        )
        assertTrue(
            "the response build must be the first statement inside that runCatching, not a later " +
                "one — this function opens two before it. Found: $handleUnlock",
            handleUnlock.substring(guard + "runCatching {".length, read).isBlank(),
        )
        assertTrue(
            "and a locked vault must report interrupted. Found: $handleUnlock",
            "finishGetInterrupted(LOCKED_MESSAGE)" in handleUnlock,
        )
    }
}

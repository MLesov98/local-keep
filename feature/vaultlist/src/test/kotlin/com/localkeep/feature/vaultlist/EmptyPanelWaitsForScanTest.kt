package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test for 45-1 (`verdicts/45-1.json`, PARTIAL).
 *
 * `VaultListUiState.items` and `folders` both default to `emptyList()`, and the real lists arrive
 * only when `scanAndPublish`'s `Dispatchers.Default` coroutine publishes. The empty-panel guard
 * asked emptiness and nothing else, so it could not tell "this vault holds nothing" from "the first
 * scan has not landed yet" and composed "Nothing here yet" over a vault with plenty in it — on the
 * one screen whose job is to reassure someone their vault is intact.
 *
 * Deliberately *not* a skeleton. The verifier's correction stands: by every reachable path to HOME
 * the vault is already decrypted before this ViewModel is built, and the scan is an in-memory pass,
 * so the window is short and the claim of "a perceivable flash on every cold unlock" was overstated.
 * A flag costs one boolean and removes the false assertion; a loading treatment would be building
 * for a stall that has not been measured.
 *
 * Asserted partly on the source, for the same reason `EmptyPanelGuardTest` gives: the defect is a
 * composable guard, and discriminating "no panel" from "a panel the test could not find" would need
 * the whole screen plus an entitlement ambient. The state half is a real object, constructed here.
 */
class EmptyPanelWaitsForScanTest {

    private val screen =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")

    private val viewModel =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListViewModel.kt")

    /** Comments name the failure this prevents; they must not be what keeps the test green. */
    private fun code(file: File): String {
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    /**
     * The default is the whole point: a freshly constructed state has not scanned, so the panel is
     * withheld. Revert `hasScanned`'s default to `true` and this test goes red.
     *
     * Control assertion, true before the fix and still true: a default state is empty and has not
     * failed to load — i.e. it satisfies every *other* term of the guard. That is exactly why the
     * panel used to compose, and it is what makes `hasScanned` load-bearing rather than decorative.
     */
    @Test
    fun `a freshly built state has not scanned yet`() {
        val fresh = VaultListUiState()
        assertFalse(
            fresh.hasScanned,
            "a state nobody has scanned into must not claim to have scanned",
        )
        assertTrue(
            fresh.items.isEmpty() && fresh.childFolders.isEmpty() && !fresh.loadFailed,
            "control: the default state satisfies every other term of the empty-panel guard, " +
                "which is why the panel composed before a scan had run",
        )
        assertTrue(
            VaultListUiState(hasScanned = true).hasScanned,
            "control: the flag is a real input, not a constant",
        )
    }

    /**
     * Production line under test: `state.hasScanned &&` in the empty-panel guard in
     * `VaultListScreen`. Delete that one term and this test goes red.
     */
    @Test
    fun `the empty panel waits for the first scan`() {
        val guard = code(screen).substringAfter("if (!state.loadFailed", missingDelimiterValue = "")
            .substringBefore("EmptyVault(")
            .ifEmpty { fail("the empty panel is no longer guarded on !state.loadFailed") }
        assertTrue(
            "state.hasScanned" in guard,
            "the empty panel must also ask whether a scan has landed — an unscanned list is empty " +
                "by default, not by fact. Found: $guard",
        )
    }

    /**
     * Production lines under test: `hasScanned = true` on both the success and the failure branch of
     * `scanAndPublish`. Remove either and this test goes red — remove the success one and the panel
     * can never compose at all, which is a worse bug than the one being fixed.
     */
    @Test
    fun `both outcomes of a scan record that it looked`() {
        val scan = code(viewModel).substringAfter("private fun scanAndPublish() {", missingDelimiterValue = "")
            .substringBefore("\n    /**")
            .ifEmpty { fail("scanAndPublish is no longer where this test looks") }
        assertTrue(
            "hasScanned = true" in scan.substringBefore(".onFailure {"),
            "the successful publish must record that the vault was read, or the empty panel can " +
                "never compose for a genuinely empty vault. Found: $scan",
        )
        assertTrue(
            "hasScanned = true" in scan.substringAfter(".onFailure {"),
            "a scan that failed has still looked; leaving the flag false would make a read failure " +
                "indistinguishable from a scan that never ran. Found: $scan",
        )
    }

    /**
     * Production line under test: `hasScanned = false` in the lock branch of the `lockState`
     * collector, alongside the `items = emptyList()` it belongs with. Delete it and this test goes
     * red: after a lock the list is back to the default empty, and a stale `true` would make it
     * "nothing is there" instead of "not established yet".
     */
    @Test
    fun `a lock puts the list back to not-yet-scanned`() {
        val clear = code(viewModel).substringAfter("lockManager.invalidateHiddenRevealAttempt", missingDelimiterValue = "")
            .substringBefore("\n            }\n        }")
            .ifEmpty { fail("the clear-on-lock block is no longer where this test looks") }
        assertTrue(
            "items = emptyList()" in clear && "hasScanned = false" in clear,
            "the lock clears items to emptyList(); the flag saying that emptiness is a fact must " +
                "be cleared with them. Found: $clear",
        )
    }
}

package com.localkeep.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Setting or rotating the reveal phrase must not derive on the main thread.
 *
 * The same rule [UnlockDispatcherTest] states for `unlock` and `attemptHiddenReveal`, applied to
 * the two entry points it never reached. `hide` (the first-ever Hide…, where the first phrase and
 * the first hide are one commit) and `changeRevealPhrase` both ran `phraseCollidesWithVisible` — a
 * whole-vault projection plus a whole-vault search, the pair `VaultListViewModel.maybeAttemptReveal`
 * had already been moved off Main for — and then a floor-cost Argon2id, with no dispatcher hop
 * anywhere between them and a bare `viewModelScope.launch {}` caller, which is
 * `Dispatchers.Main.immediate`.
 *
 * A source assertion for the reason `UnlockDispatcherTest` gives: what can be checked cheaply and
 * exactly is that the dispatcher lives in the function rather than in whichever caller happens to
 * remember it.
 */
class RevealPhraseDispatcherTest {

    private val source = File("src/main/kotlin/com/localkeep/core/vault/VaultRepository.kt")

    private fun lines(): List<String> {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readLines()
    }

    /** The body of [name], up to the next declaration at the same indentation. */
    private fun body(name: String): String {
        val all = lines()
        val start = all.indexOfFirst { it.contains(name) }
        assertTrue(start >= 0, "VaultRepository no longer declares $name")
        val end = all.drop(start + 1).indexOfFirst { it == "    }" } + start + 1
        assertTrue(end > start, "could not find the end of $name")
        return all.subList(start, end + 1).joinToString("\n")
    }

    @Test
    fun `the phrase gate owns its dispatcher instead of trusting call sites to supply one`() {
        val all = lines()
        val start = all.indexOfFirst { it.contains("private suspend fun derivePhraseGate(") }
        assertTrue(start >= 0, "VaultRepository no longer declares derivePhraseGate(")
        val body = all.subList(start, minOf(start + 4, all.size)).joinToString("\n")

        assertTrue(
            "withContext(Dispatchers.Default)" in body,
            "derivePhraseGate must switch to Dispatchers.Default itself. Its two callers reach it " +
                "from a bare viewModelScope.launch {}, which is Dispatchers.Main.immediate. " +
                "Found:\n$body",
        )
        val hopped = body.substringAfter("withContext(Dispatchers.Default)", missingDelimiterValue = "")
        assertTrue(
            "phraseCollidesWithVisible(" in hopped && "RevealPhraseGate.create(" in hopped,
            "both halves must be inside the hop — the whole-vault projection-plus-search as well " +
                "as the KDF. On a large vault the search is plausibly the bigger of the two. " +
                "Found:\n$body",
        )
        assertTrue(
            "NonCancellable" !in body,
            "NonCancellable must NOT be copied over from attemptHiddenReveal. It is there because " +
                "the search box cancels the job its KDF runs in; nothing cancels a hide, so here it " +
                "would only make a user-initiated action uncancellable. Found:\n$body",
        )
    }

    @Test
    fun `neither commit path derives or searches on the caller's thread`() {
        for (name in listOf("suspend fun hide(", "suspend fun changeRevealPhrase(")) {
            val body = body(name)
            assertTrue(
                "RevealPhraseGate.create(" !in body,
                "$name must reach the KDF through derivePhraseGate, not directly. Found:\n$body",
            )
            assertTrue(
                "phraseCollidesWithVisible(" !in body,
                "$name must reach the collision check through derivePhraseGate, not directly — it " +
                    "is a whole-vault projection plus a whole-vault search. Found:\n$body",
            )
            assertTrue(
                "derivePhraseGate(" in body,
                "$name no longer goes through derivePhraseGate at all",
            )
        }
    }

    /**
     * Control. This held before the dispatcher hop existed and must keep holding: the `finally` may
     * not move inside `withContext`, or a cancellation during the derivation would skip it and
     * leave the reveal phrase resident in the CharArray. Same constraint
     * [UnlockDispatcherTest] pins for unlock's master password.
     */
    @Test
    fun `the phrase wipe stays in the outer finally`() {
        for ((name, phrase) in listOf(
            "suspend fun hide(" to "Zeroizer.wipe(firstPhrase)",
            "suspend fun changeRevealPhrase(" to "Zeroizer.wipe(newPhrase)",
        )) {
            val body = body(name)
            val finallyIndex = body.indexOf("} finally {")
            assertTrue(finallyIndex > 0, "$name no longer has a finally block")
            assertTrue(
                body.indexOf(phrase) > finallyIndex,
                "$phrase must sit in $name's own finally, outside any withContext, so a cancelled " +
                    "derivation still wipes the phrase",
            )
        }
    }
}

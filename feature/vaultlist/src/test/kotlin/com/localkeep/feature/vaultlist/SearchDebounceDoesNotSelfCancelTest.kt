package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The debounced search coroutine must not run a function that cancels the coroutine itself.
 *
 * `refresh` opens with `searchJob?.cancel()`, which is right for every other caller — a chip tap or
 * a folder open supersedes a keystroke still waiting out its debounce. It is wrong for the one
 * caller that *is* the debounce timer: there `searchJob` is the running coroutine's own job, so the
 * call cancels its caller, and the next cancellation-checking suspension point throws. That point
 * exists as of the reveal probe's `withContext(Dispatchers.Default)`, so
 * `lockManager.attemptHiddenReveal` was unreachable from the search box — the phrase
 * `vaultlist_hide_phrase_body` tells the user to type there was silently ignored, and nothing in
 * the list looked wrong because the scan itself is launched on `viewModelScope` and survives.
 *
 * Asserted on the source, in the style of [VaultListMainThreadWorkTest], because the behavioural
 * version would need a lock-manager double: `VaultLockManager` and `VaultRepository` are final
 * classes built from a `Context` and a `KeystoreManager`, and this module's unit-test classpath
 * carries neither a mocking library nor `kotlinx-coroutines-test`. What is checked is the property
 * that broke, not a name: whichever function the debounce calls, that function must not cancel
 * `searchJob`.
 */
class SearchDebounceDoesNotSelfCancelTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListViewModel.kt")

    private fun lines(): List<String> {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readLines()
    }

    /** The block opened by [opener], up to the first line that is exactly [closer]. */
    private fun block(opener: String, closer: String): List<String> {
        val all = lines()
        val start = all.indexOfFirst { it.contains(opener) }
        assertTrue(start >= 0, "VaultListViewModel no longer contains `$opener`")
        val end = all.drop(start + 1).indexOfFirst { it == closer } + start + 1
        assertTrue(end > start, "could not find the end of `$opener`")
        return all.subList(start, end + 1)
    }

    /** The body of the debounce coroutine `onSearchChanged` assigns to `searchJob`. */
    private fun debounceBody(): List<String> =
        block("searchJob = viewModelScope.launch {", "        }")

    /** The name of the zero-argument function the debounce calls to run the list scan. */
    private fun scanCalledByDebounce(): String {
        val call = Regex("""^\s{12}([A-Za-z][A-Za-z0-9_]*)\(\)$""")
        val names = debounceBody().mapNotNull { call.find(it)?.groupValues?.get(1) }
        assertEquals(
            1, names.size,
            "expected exactly one bare zero-argument call in the debounce body, which is the list " +
                "scan; found $names in:\n${debounceBody().joinToString("\n")}",
        )
        return names.single()
    }

    @Test
    fun `the scan the debounce runs does not cancel the debounce`() {
        val name = scanCalledByDebounce()
        val body = block("private fun $name()", "    }").joinToString("\n")
        assertFalse(
            "searchJob?.cancel()" in body,
            "the debounce coroutine calls $name(), and $name() cancels searchJob — which at that " +
                "moment is the very coroutine executing it. The next cancellation-checking " +
                "suspension point after it (maybeAttemptReveal's withContext(Dispatchers.Default)) " +
                "then throws CancellationException, so lockManager.attemptHiddenReveal is never " +
                "reached and the reveal phrase does nothing when typed into the search box. " +
                "Found:\n$body",
        )
    }

    @Test
    fun `refresh still cancels a pending debounce for every other caller`() {
        val body = block("private fun refresh()", "    }").joinToString("\n")
        assertTrue(
            "searchJob?.cancel()" in body,
            "refresh() must keep cancelling the debounce timer: a chip tap or a folder open " +
                "already reflects the current query, so letting a stale keystroke's timer fire " +
                "afterwards re-runs the same whole-vault scan for nothing. Found:\n$body",
        )
        assertTrue(
            "${scanCalledByDebounce()}()" in body,
            "refresh() must run the same scan the debounce runs, or the two paths can drift. " +
                "Found:\n$body",
        )
    }
}

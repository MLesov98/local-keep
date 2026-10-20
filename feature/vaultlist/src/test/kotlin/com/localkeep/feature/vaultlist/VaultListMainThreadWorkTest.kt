package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The whole-vault projections the vault list runs stay off the main thread.
 *
 * `refresh` already documents why: the scan "used to land on whichever dispatcher the caller ... was
 * on — the main thread — dropping frames on those taps". Two of its neighbours were left behind.
 *
 * - `loadFolders` re-ran `VaultVisibility.project` — six passes over `vault.items` plus a folder
 *   ancestry walk — and then sorted the folders through a freshly built `java.text.Collator`, on
 *   `Dispatchers.Main.immediate`, after every save, favourite toggle, trash, bulk action and
 *   reveal-lease expiry. It was also redundant on the busiest of those paths: the
 *   `presentationState` emission that triggers it *carries* the snapshot
 *   (`VaultPresentationState.Unlocked.snapshot`) and the collector threw it away.
 * - `maybeAttemptReveal` ran a projection and a whole-vault search on the main thread on the
 *   trailing edge of the search debounce — while the user is still typing.
 *
 * Source assertions, and the same reasoning as `UnlockDispatcherTest`: what can be checked cheaply
 * and exactly is that the dispatcher switch lives in the function rather than in whichever caller
 * happens to remember it. Stated plainly rather than dressed up as more than it is.
 */
class VaultListMainThreadWorkTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListViewModel.kt")

    private fun lines(): List<String> {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readLines()
    }

    /** The body of [name], up to the next declaration at the same indentation. */
    private fun body(name: String): String {
        val all = lines()
        val start = all.indexOfFirst { it.contains(name) }
        assertTrue(start >= 0, "VaultListViewModel no longer declares $name")
        val end = all.drop(start + 1).indexOfFirst { it == "    }" } + start + 1
        assertTrue(end > start, "could not find the end of $name")
        return all.subList(start, end + 1).joinToString("\n")
    }

    @Test
    fun `loadFolders owns its dispatcher instead of running on whichever one the caller is on`() {
        val body = body("private suspend fun loadFolders(")
        assertTrue(
            "withContext(Dispatchers.Default)" in body,
            "loadFolders must switch to Dispatchers.Default itself. Without it the whole-vault " +
                "visibility projection and the Collator sort run on Dispatchers.Main.immediate " +
                "after every commit — the one moment the list is being watched. Found:\n$body",
        )
    }

    @Test
    fun `the presentation emission's own snapshot is used instead of re-projecting`() {
        assertTrue(
            "loadFolders(presentation.snapshot)" in lines().joinToString("\n"),
            "VaultPresentationState.Unlocked carries the snapshot VaultVisibility.project just " +
                "produced; re-projecting it walks the whole vault a second time for the same answer",
        )
    }

    @Test
    fun `the reveal probe's projection and search run off the main thread`() {
        val body = body("private suspend fun maybeAttemptReveal(")
        val offloaded = body.substringAfter("withContext(Dispatchers.Default)", missingDelimiterValue = "")
        assertTrue(
            "repository.visibilitySnapshot()" in offloaded && "repository.search(query)" in offloaded,
            "maybeAttemptReveal's whole-vault projection and search must sit inside " +
                "withContext(Dispatchers.Default). They run on the trailing edge of the search " +
                "debounce, so on the main thread they stutter the keystroke after them. Found:\n$body",
        )
    }
}

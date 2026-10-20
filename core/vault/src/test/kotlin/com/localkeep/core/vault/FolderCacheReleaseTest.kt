package com.localkeep.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locking must drop the folder caches, not only the search index.
 *
 * `cachedFolders` holds the vault's whole `List<Folder>` — every folder name the user typed, in
 * plaintext. It was written in exactly one place, `folderDescendants`, and cleared in exactly one
 * place: the one-shot waiter armed from inside `searchIndex()`. Nothing else armed that waiter, so
 * the release ran only for a session that had *searched*. Browsing folders and never typing into
 * the search field left the full folder list resident past `lock()` in a `@Singleton` repository —
 * the same "locked, but the plaintext is still reachable" state the index release exists to
 * prevent, reached by the more common of the two journeys.
 *
 * The exposure ends at the next folder read after a re-unlock, because `folderDescendants` replaces
 * a stale list on its first `cachedFolders != folders` comparison. On a device left locked there is
 * no such read, so the window is unbounded in exactly the situation that matters.
 *
 * **These are source assertions, and deliberately so.** The behaviour they stand for — a coroutine
 * observing `lockManager.lockState` — needs a `VaultLockManager`, which needs an Android `Context`;
 * this module's unit tests run on a bare JVM with no Robolectric, so there is no `Context` to give
 * it. The runtime half of this path is covered on a device by `SearchIndexTest`. Same reasoning,
 * and the same idiom, as `UnlockDispatcherTest` and `BlobReKeyJournalFailureTest` next door.
 */
class FolderCacheReleaseTest {

    private val source = File("src/main/kotlin/com/localkeep/core/vault/VaultRepository.kt")

    /**
     * Code with its comments removed.
     *
     * The scan has to read code, not prose — this file explains the release at length, and a guard
     * that counted the explanation would either pass on a broken file or teach the next person to
     * delete the explanation. Same helper as `HiddenChokepointTest`.
     */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** The braced body of the first declaration matching [signature]. */
    private fun bodyOf(code: String, signature: String): String {
        val start = code.indexOf(signature)
        if (start < 0) fail("could not find `$signature` in ${source.name}")
        val open = code.indexOf('{', code.indexOf(')', start))
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(open, i + 1)
            }
        }
        fail("unbalanced braces after `$signature` in ${source.name}")
    }

    @Test
    fun `the site that populates the folder cache arms the release that clears it`() {
        val code = codeOnly(source.readText())
        val body = bodyOf(code, "fun folderDescendants(")
        assertTrue(
            "cachedFolders = folders" in body,
            "folderDescendants no longer populates the folder cache — this test is aimed at the " +
                "wrong function and must be re-pointed, not deleted",
        )
        assertTrue(
            "armPlaintextCacheRelease()" in body,
            "folderDescendants caches the vault's whole folder list but does not arm the release " +
                "that drops it at lock, so a session that browses without searching keeps every " +
                "folder name in memory past lock()",
        )
    }

    /**
     * Arming is idempotent, and that is the whole reason the fix is one call rather than a second
     * waiter: `compareAndSet` means the search path and the folder path cannot both start one.
     */
    @Test
    fun `arming is guarded by compareAndSet so two call sites cannot start two waiters`() {
        val code = codeOnly(source.readText())
        val body = bodyOf(code, "fun armPlaintextCacheRelease(")
        assertTrue(
            "compareAndSet(false, true)" in body,
            "two call sites now arm the release; without the compare-and-set guard each would " +
                "launch its own coroutine on the repository scope",
        )
        for (field in listOf("searchIndex = null", "cachedFolders = null", "indexedVault = null")) {
            assertTrue(field in body, "the release no longer clears `$field`")
        }
    }

    /**
     * A new populate site would reintroduce the leak silently, so the count is pinned rather than
     * left to review. If this fails because a second site was added, arm the release there too and
     * raise the number — do not raise the number alone.
     */
    @Test
    fun `the folder cache is populated at exactly one site`() {
        val code = codeOnly(source.readText())
        // Captured and compared rather than excluded with a negative lookahead: `\s*` can match
        // zero characters, so `cachedFolders\s*=\s*(?!null)` backtracks onto the space before
        // `null` and counts the clear as a populate. That version reported 2 sites where there was
        // 1, which is the kind of miscount that makes a guard useless in both directions.
        val populates = Regex("""cachedFolders\s*=\s*(\S+)""").findAll(code)
            .count { it.groupValues[1] != "null" }
        assertEquals(
            1,
            populates,
            "every site that populates cachedFolders must arm the release that clears it",
        )
    }
}

package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The notices that report, at unlock, something the lock lost or replaced are posted for
 * `LkNotices.UNLOCK_MS`, and nothing else posted at unlock is.
 *
 * Three device testers on 2026-10-05 unlocked straight past these lines at the ordinary four
 * seconds — they land with the unlock itself, while the user is still watching the unlock screen
 * give way — and reported the loss as silent. The duration lives in core/ui and is pinned by
 * `LkNoticesTest`; what can still go wrong is a call site posting one of these through the ordinary
 * `post`, which compiles and looks right. So this reads the call sites.
 *
 * Asserted against the source for the reason `RestoreNoticeNamesMissingFilesTest` gives: these
 * posts sit inside MainActivity's lock collector and a `withResumed` block, and which function
 * posts them is a property of the call site.
 */
class UnlockLossNoticesOutlastTheUnlockTest {

    private fun code(relative: String): String {
        val file = File(repoRoot(), relative)
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        // Comments out: this codebase explains each call beside it, and prose naming a resource
        // would otherwise be read as the call.
        return file.readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    private val mainActivity get() = code("app/src/main/kotlin/com/localkeep/app/MainActivity.kt")

    /** The `LkNotices` function whose call encloses [resource] — the nearest one before it. */
    private fun poster(code: String, resource: String): String {
        val at = code.indexOf(resource)
        assertTrue(at >= 0, "'$resource' is no longer posted here — update this test, or it asserts nothing")
        return Regex("""LkNotices\.(post\w*)\(""").findAll(code.substring(0, at)).lastOrNull()?.groupValues?.get(1)
            ?: fail("no LkNotices call before '$resource'")
    }

    @Test
    fun `an edit the lock discarded is reported for the unlock duration`() {
        assertEquals("postAtUnlock", poster(mainActivity, "R.string.app_notice_edit_dropped_by_lock"))
    }

    @Test
    fun `a restored snapshot is reported for the unlock duration`() {
        assertEquals("postAtUnlock", poster(mainActivity, "R.string.app_notice_vault_restored"))
        assertEquals("postAtUnlock", poster(mainActivity, ".backup_status_restore_complete_missing_files"))
    }

    /**
     * `postAtUnlock` defaults to Tone.Refusal (a loss), so a restore that came back whole has to
     * ask for Success or it renders coral under a sentence that says the opposite.
     */
    @Test
    fun `a fully restored snapshot is drawn as a success`() {
        val code = mainActivity
        val at = code.indexOf("R.string.app_notice_vault_restored")
        assertTrue(at >= 0, "the restore notice is no longer posted here — update this test")
        val call = code.substring(at, code.indexOf(")", at) + 1)
        assertTrue("Tone.Success" in call, "the full-restore notice must pass tone = LkNotices.Tone.Success: $call")
    }

    @Test
    fun `an edit the item editor queued for the next unlock is reported for the unlock duration`() {
        val list = code("feature/vaultlist/src/main/kotlin/com/localkeep/feature/vaultlist/VaultListViewModel.kt")
        assertTrue(
            "LkUnlockNotices.postPending()" in list,
            "the vault list must post what the lock queued through LkUnlockNotices.postPending, which " +
                "carries the unlock duration; a bare take() handed to post shows it for four seconds",
        )
    }

    /**
     * The control: the cloud-backup question is posted at the same unlock but reports nothing lost.
     * It carries an action, so it already gets the action duration, and it stays on the ordinary post.
     */
    @Test
    fun `the cloud backup question is still an ordinary notice`() {
        assertEquals("post", poster(mainActivity, "R.string.app_notice_cloud_backup_unchosen,"))
    }

    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("repo root not found above ${File("").absolutePath}")
}

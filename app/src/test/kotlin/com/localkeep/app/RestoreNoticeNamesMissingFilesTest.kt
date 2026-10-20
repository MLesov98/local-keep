package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A restore that could not find every file it references says so.
 *
 * Red before the fix. A rolling snapshot holds `vault.enc` and nothing else — no media section — so
 * restoring an older generation can bring back an item whose attachment `BlobGarbageCollector`
 * already swept as orphaned. The item returns; the file it points at does not. The post-unlock
 * notice reported "Vault restored" either way.
 *
 * The import path solved exactly this with `missingBlobRefCount` and has for a long time. The
 * restore path counted nothing, so the one operation that replaces the whole vault was also the one
 * that reported success without qualification — and the user meets the gap days later, with no way
 * to tell a restore that dropped files from one that did not.
 *
 * The count is written by `RollingBackupManager` and read here rather than on the Backup screen for
 * a mechanical reason: `restoreVaultFile` force-locks the vault and pops the nav graph before that
 * screen can render anything, so the post-unlock notice is the first surface that still exists.
 *
 * Asserted against the source: this is a branch inside a `withResumed` block in an Activity's
 * composition, and what the defect actually is — which of two notices gets posted — is a property of
 * the call site.
 */
class RestoreNoticeNamesMissingFilesTest {

    private val source: String
        get() {
            val relative = "src/main/kotlin/com/localkeep/app/MainActivity.kt"
            val direct = File(relative)
            val file = if (direct.isFile) direct else File(repoRoot(), "app/$relative")
            if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
            return file.readText()
        }

    /**
     * The restore branch, comments removed.
     *
     * Stripped for two reasons, and the first one bit while this test was being written: the
     * rationale beside this branch is longer than the branch, so a fixed window taken from the
     * anchor was entirely comment and the assertions read nothing. The second is the usual one —
     * this codebase explains each decision next to it, so a scan that cannot tell prose from code
     * passes with the fix reverted.
     */
    private val restoreBlock: String
        get() {
            val text = source.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            val anchor = "BackupPreferences.consumeRestoreNoticePending(this@MainActivity)"
            assertTrue(
                anchor in text,
                "anchor '$anchor' is gone from MainActivity.kt — update it, or this test scans the " +
                    "whole file and asserts nothing",
            )
            return text.substringAfter(anchor).take(1400)
        }

    @Test
    fun `a restore missing files reports how many`() {
        val block = restoreBlock
        assertTrue(
            "consumeRestoreNoticePendingMissingBlobRefCount" in block,
            "the notice must read the count RollingBackupManager wrote, or the plumbing ends in a " +
                "preference nothing reads. Found:\n$block",
        )
        assertTrue(
            "backup_status_restore_complete_missing_files" in block,
            "a restore that dropped files must name how many; found:\n$block",
        )
        assertTrue(
            "if (missing > 0)" in block,
            "the counted notice must be conditional on there actually being something missing; " +
                "found:\n$block",
        )
    }

    /**
     * The control, and it must stay green while the test above goes red.
     *
     * The ordinary restore — every referenced file present — must keep its own plain confirmation.
     * Replacing it with the counted plural for all restores would put "0 referenced files are no
     * longer on this device" in front of every user who had nothing wrong, which is a sentence that
     * invents a worry to reassure about.
     *
     * This assertion holds with the fix reverted as well, which is what makes it a control rather
     * than a second copy of the test above. It first shipped here carrying the `if (missing > 0)`
     * check too, which made it go red alongside — a control that fails when the fix is removed
     * proves nothing about the fix.
     */
    @Test
    fun `a clean restore still says only that it worked`() {
        val block = restoreBlock
        assertTrue(
            "R.string.app_notice_vault_restored" in block,
            "the unqualified confirmation must survive for the case where nothing is missing",
        )
    }

    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("repo root not found above ${File("").absolutePath}")
}

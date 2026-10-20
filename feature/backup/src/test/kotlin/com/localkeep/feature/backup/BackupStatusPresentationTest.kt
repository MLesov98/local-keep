package com.localkeep.feature.backup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which Backup status is work in progress, and when the "file read" strip gives way to the buttons.
 *
 * S9-F-11 and S9-F-04 (functional pass, 2026-10-15). "Exporting…" wore the same jade tick as
 * "Exported 209 MB", and a successful read scrolled to a top-of-page strip naming two buttons that
 * were a screen below it, with the same instruction repeated beside them.
 */
class BackupStatusPresentationTest {

    @Test
    fun `the five in-flight statuses are progress`() {
        listOf(
            R.string.backup_status_exporting,
            R.string.backup_status_reading_file,
            R.string.backup_status_merging,
            R.string.backup_status_replacing_vault,
            R.string.backup_status_restoring,
        ).forEach { res ->
            assertTrue(
                isProgressStatus(BackupMessage.Res(res)),
                "$res reports work still running but would be drawn with the success tick",
            )
        }
    }

    @Test
    fun `control - results are not progress`() {
        assertFalse(isProgressStatus(null))
        assertFalse(isProgressStatus(BackupMessage.Res(R.string.backup_status_import_complete)))
        assertFalse(isProgressStatus(BackupMessage.Res(R.string.backup_status_kdf_recalibrated)))
        assertFalse(isProgressStatus(BackupMessage.Size(R.string.backup_status_exported, 209_000_000L)))
        assertFalse(isProgressStatus(BackupMessage.Res(R.string.backup_status_file_read)))
    }

    @Test
    fun `a staged file's read strip gives way to the buttons that answer it`() {
        assertTrue(
            fileReadPromptReplacesStatus(BackupMessage.Res(R.string.backup_status_file_read), importStaged = true),
            "the strip still competes with the inline prompt beside Merge and Replace",
        )
    }

    @Test
    fun `control - the strip stays for anything that is not a staged file`() {
        assertFalse(fileReadPromptReplacesStatus(BackupMessage.Res(R.string.backup_status_file_read), importStaged = false))
        assertFalse(fileReadPromptReplacesStatus(BackupMessage.Res(R.string.backup_status_exporting), importStaged = true))
        assertFalse(fileReadPromptReplacesStatus(null, importStaged = true))
    }
}

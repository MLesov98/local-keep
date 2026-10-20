package com.localkeep.feature.backup

/**
 * How the Backup screen presents the one status it holds, decided apart from the composable so the
 * screen and its tests read the same rule.
 */

/**
 * Whether [status] reports work still running rather than a result.
 *
 * Progress is drawn with the neutral spinner glyph. It was drawn with the jade success tick, the
 * same one a finished export gets, so the screen claimed success for 209 MB while it was still
 * being written.
 */
internal fun isProgressStatus(status: BackupMessage?): Boolean =
    status is BackupMessage.Res && status.res in PROGRESS_STATUSES

private val PROGRESS_STATUSES = setOf(
    R.string.backup_status_exporting,
    R.string.backup_status_reading_file,
    R.string.backup_status_merging,
    R.string.backup_status_replacing_vault,
    R.string.backup_status_restoring,
)

/**
 * Whether the "File read — choose Merge or Replace" strip is dropped in favour of the staged
 * section itself.
 *
 * Every status scrolls the screen to the top, where the strip sits; the buttons it names are a
 * screen lower, and a second line beside them repeated the instruction after the user had scrolled
 * down to find them.
 */
internal fun fileReadPromptReplacesStatus(status: BackupMessage?, importStaged: Boolean): Boolean =
    importStaged && status is BackupMessage.Res && status.res == R.string.backup_status_file_read

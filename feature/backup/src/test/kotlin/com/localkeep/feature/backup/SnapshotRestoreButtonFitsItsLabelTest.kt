package com.localkeep.feature.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A snapshot row's Restore button is as wide as its label.
 *
 * Found 2026-10-03: pinned to a third of the row, German's "Wiederherstellen" broke mid-word as
 * "Wiederherst / ellen" on every snapshot row (German screenshot). Pinned on the source, in the
 * style of [BackupInFlightControlsTest], because a JVM test cannot lay out a German string.
 */
class SnapshotRestoreButtonFitsItsLabelTest {

    @Test
    fun `the restore button takes its label's width, not a fraction of the row`() {
        val file = File("src/main/kotlin/com/localkeep/feature/backup/BackupSettingsScreen.kt")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        val source = file.readText()
        val start = source.indexOf("text = stringResource(R.string.backup_snapshot_restore_button)")
        assertTrue(start >= 0, "the snapshot restore button is gone")
        val call = source.substring(start, source.indexOf("onClick", start))

        // FAILS WITHOUT THE FIX: `Modifier.fillMaxWidth(0.34f)`.
        assertTrue(
            "IntrinsicSize.Max" in call && "fillMaxWidth(0." !in call,
            "the restore button must size to its label. Found:\n$call",
        )
    }
}

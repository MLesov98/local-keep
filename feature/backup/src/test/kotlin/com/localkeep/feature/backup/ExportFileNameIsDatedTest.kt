package com.localkeep.feature.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The export's save dialog proposes a dated name, so a second backup is not `vault-export (1).vault`.
 *
 * Device report C3 finding 6 (2026-10-04): every export proposed the same `vault-export.vault`, so
 * the system picker answered the second one with `(1)`, and a folder of backups could only be told
 * apart by opening each. The date is the whole addition — nothing that identifies the vault or the
 * device goes into a name that ends up on a USB stick or in cloud storage.
 *
 * Read through the resource and the date helper together, which is exactly what
 * `BackupSettingsScreen` hands the picker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportFileNameIsDatedTest {

    private fun proposedName(date: LocalDate): String =
        ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.backup_export_filename, exportFileDate(date))

    @Test
    fun `the proposed name carries the export date`() {
        assertEquals("LocalKeep-2026-10-05.vault", proposedName(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `two days propose two names`() {
        // The control: a name that ignored its date would pass the test above only by coincidence
        // of the day it was written, and would still collide on the second backup.
        assertNotEquals(proposedName(LocalDate.of(2026, 1, 9)), proposedName(LocalDate.of(2026, 1, 10)))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `a translated device proposes the same ISO name`() {
        assertEquals("LocalKeep-2026-10-05.vault", proposedName(LocalDate.of(2026, 10, 5)))
    }
}

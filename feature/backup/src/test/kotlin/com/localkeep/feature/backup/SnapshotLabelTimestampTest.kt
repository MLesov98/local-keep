package com.localkeep.feature.backup

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals

/**
 * A snapshot row says when the snapshot was taken, and that has to survive a master-password change.
 *
 * `StaleContainerSweeper` re-keys every container beside the live vault during a rotation, and it
 * does it by writing a sibling and renaming it over the original — so `File.lastModified()`, which
 * the rows used to be labelled with, is reset on all five at once. Driven on device 2026-09-15: five
 * snapshots taken at 4:40, 4:26, 4:18, 4:10 and 4:08 PM all read `Sep 15, 2026, 5:39 PM` after a
 * password change, leaving a list whose only purpose is "go back to how it was at time T" offering
 * five identical T's (fixed 2026-09-15).
 *
 * The filename is the copy that survives, because the sweep renames each container back onto the
 * name it already had. Each test below therefore sets an mtime that disagrees with the name, which
 * is exactly the state a rotation leaves behind.
 */
class SnapshotLabelTimestampTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** The moment a rotation would have stamped on every snapshot at once. */
    private val sweepTime = Instant.parse("2026-09-15T17:39:00Z").toEpochMilli()

    private fun snapshot(name: String, lastModified: Long): File =
        temp.newFile(name).apply { setLastModified(lastModified) }

    @Test
    fun `a snapshot whose mtime was reset is still labelled by the time in its name`() {
        val file = snapshot("vault-20260915-164000.enc", sweepTime)

        assertEquals(
            Instant.parse("2026-09-15T16:40:00Z").toEpochMilli(),
            snapshotLabelEpochMillis(file),
        )
    }

    /**
     * The collision suffix two saves in the same UTC second produce. It is not part of the
     * timestamp, and reading it as one would leave the row unlabelled rather than merely wrong.
     */
    @Test
    fun `a collision-suffixed snapshot is labelled by the time in its name too`() {
        val file = snapshot("vault-20260915-162600-1.enc", sweepTime)

        assertEquals(
            Instant.parse("2026-09-15T16:26:00Z").toEpochMilli(),
            snapshotLabelEpochMillis(file),
        )
    }

    /**
     * The control that must stay green. Without it a change that simply returned a constant, or one
     * that dropped the mtime path entirely, would pass the two tests above.
     */
    @Test
    fun `a file that is not a snapshot falls back to its modification time`() {
        val file = snapshot("not-a-snapshot.enc", sweepTime)

        assertEquals(sweepTime, snapshotLabelEpochMillis(file))
    }

    /**
     * Regression for 3B-F7 (release sweep 2026-09-20): a snapshot row carried a timestamp and
     * nothing else, so choosing between five snapshots of a 175-item vault nothing said which one
     * was fuller — directly above a warning that restoring one replaces the current vault and
     * anything saved since is gone.
     */
    @Test
    fun `a snapshot row states how big the snapshot is, not only when it was taken`() {
        val small = snapshot("vault-20260920-092355.enc", sweepTime).apply { writeBytes(ByteArray(1_024)) }
        val large = snapshot("vault-20260920-092406.enc", sweepTime).apply { writeBytes(ByteArray(64_000)) }

        assertEquals(1_024L, snapshotRowFacts(small).sizeBytes)
        assertEquals(64_000L, snapshotRowFacts(large).sizeBytes)
    }

    /** The timestamp half is unchanged, and the facts object carries the same value it always did. */
    @Test
    fun `the row's timestamp is still the one in the snapshot's name`() {
        val file = snapshot("vault-20260915-164000.enc", sweepTime)

        assertEquals(snapshotLabelEpochMillis(file), snapshotRowFacts(file).takenAtMillis)
    }

    /**
     * Five snapshots taken minutes apart and then re-keyed together still read as five distinct
     * moments — the user-visible claim F-43 is about, rather than one file's parse.
     */
    @Test
    fun `five snapshots re-keyed in one rotation keep five distinct labels`() {
        val files = listOf(
            "vault-20260915-164000.enc",
            "vault-20260915-162600.enc",
            "vault-20260915-161800.enc",
            "vault-20260915-161000.enc",
            "vault-20260915-160800.enc",
        ).map { snapshot(it, sweepTime) }

        val labels = files.map(::snapshotLabelEpochMillis)

        assertEquals(5, labels.toSet().size)
    }
}

package com.localkeep.feature.backup

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RollingBackupPruneTest {

    private val dir: File = Files.createTempDirectory("rolling-backups").toFile()

    @AfterTest
    fun removeTempDir() {
        dir.deleteRecursively()
    }

    private fun name(n: Int) = "vault-20260727-%06d.enc".format(n)

    /** Oldest first, so [count] is also the newest generation. */
    private fun writeSnapshots(count: Int) {
        (1..count).forEach { n -> File(dir, name(n)).writeBytes(byteArrayOf(n.toByte())) }
    }

    private fun remaining() = RollingBackupManager.snapshotsNewestFirst(dir).map { it.name }

    @Test
    fun `pruning keeps the newest generations and reports nothing when the deletes succeed`() {
        writeSnapshots(8)

        val undeleted = RollingBackupManager.prune(dir)

        assertTrue(undeleted.isEmpty(), "unexpected failures: $undeleted")
        assertEquals(
            (8 downTo 8 - RollingBackupManager.MAX_SNAPSHOTS + 1).map(::name),
            remaining(),
        )
    }

    @Test
    fun `a snapshot that refuses to delete is reported rather than treated as pruned`() {
        writeSnapshots(8)
        val stuck = name(2)

        val undeleted = RollingBackupManager.prune(dir) { it.name != stuck && it.delete() }

        assertEquals(listOf(stuck), undeleted.map { it.name })
        assertTrue(File(dir, stuck).exists())
        assertFalse(File(dir, name(1)).exists())
        assertFalse(File(dir, name(3)).exists())
    }

    @Test
    fun `when no delete succeeds every stale snapshot is reported and none is lost`() {
        writeSnapshots(8)

        val undeleted = RollingBackupManager.prune(dir) { false }

        assertEquals(listOf(name(3), name(2), name(1)), undeleted.map { it.name })
        assertEquals(8, remaining().size)
    }

    @Test
    fun `a directory at or under the limit is left alone and reports nothing`() {
        writeSnapshots(RollingBackupManager.MAX_SNAPSHOTS)
        var attempts = 0

        val undeleted = RollingBackupManager.prune(dir) {
            attempts++
            it.delete()
        }

        assertEquals(0, attempts)
        assertTrue(undeleted.isEmpty())
        assertEquals(RollingBackupManager.MAX_SNAPSHOTS, remaining().size)
    }
}

package com.localkeep.core.vault

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `StaleContainerSweeper.sweep` promises "Never throws", and an `OutOfMemoryError` used to break it.
 *
 * The sweep reads whole vault containers into heap — `classify` starts with `file.readBytes()`, and
 * `convert` does it again — while its only guards are `catch (_: Exception)`, which does not catch
 * an `Error`. It runs *after* `VaultLockManager` has already written the rotated vault to disk, with
 * that method's own `currentBytes` and `updatedFile` still live, so it sits at the high-water mark
 * of the whole operation on a minSdk-28 device whose heap growth limit is 48 MiB.
 *
 * What escaped was not a crash, which is what makes it worth a regression test rather than a
 * comment. `SettingsViewModel` wraps the rotation in `runCatching`, which catches `Throwable`, and a
 * non-`OperationFailure` lands on the generic-failure arm — so a rotation that had **succeeded** was
 * reported as "could not be completed", and retrying the form checked the *current* password, which
 * was by then the new one, and answered "wrong current password". The user is told twice that
 * nothing changed, while it has, and every container the loop never reached is still open under the
 * credential the rotation existed to retire.
 *
 * The `OutOfMemoryError` here is real and raised by the line under test rather than injected around
 * it: `kotlin.io.readBytes` checks `length()` before allocating and throws `OutOfMemoryError` when a
 * file will not fit in an `Int`-sized array, so a `File` that reports a huge length reaches
 * `file.readBytes()` inside `classify` and fails there — deterministically, without a huge file and
 * without disturbing this JVM's heap.
 */
class StaleContainerSweepOutOfMemoryTest {

    private val dir: File = Files.createTempDirectory("stale-container-sweep").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A real, small file that lies about its size. See the class KDoc. */
    private class UnreadablyLargeFile(real: File) : File(real.path) {
        override fun length(): Long = Long.MAX_VALUE
    }

    private fun container(name: String): File =
        File(dir, name).apply { writeBytes(ByteArray(16)) }

    /**
     * The control. If a future stdlib change made `readBytes` fail some other way — or not fail at
     * all — every assertion below would pass while testing nothing, so the mechanism is asserted
     * before it is relied on.
     */
    @Test
    fun `the fixture really does raise an OutOfMemoryError from readBytes`() {
        val huge = UnreadablyLargeFile(container("control.bin"))
        assertFailsWith<OutOfMemoryError> { huge.readBytes() }
    }

    @Test
    fun `a container too large to read is reported unreachable instead of throwing`() {
        val huge = UnreadablyLargeFile(container("vault.enc.bak"))

        val result = StaleContainerSweeper.sweep(
            files = listOf(huge),
            backupsDir = dir,
            oldEncKey = ByteArray(32),
            newEncKey = ByteArray(32),
            newSlots = emptyList(),
        )

        assertEquals(
            listOf("vault.enc.bak"),
            result.unreachable,
            "a container the sweep could not read is neither converted nor destroyed, so it must " +
                "be reported as the invariant not holding rather than escaping as an Error",
        )
        assertEquals(0, result.converted)
        assertEquals(emptyList(), result.destroyed)
    }

    /**
     * The consequence that made this medium rather than low: the loop aborted, so every container
     * after the failing one stayed open under the retired credential.
     */
    @Test
    fun `one unreadable container does not abandon the ones after it`() {
        val first = UnreadablyLargeFile(container("first.bin"))
        val second = UnreadablyLargeFile(container("second.bin"))

        val result = StaleContainerSweeper.sweep(
            files = listOf(first, second),
            backupsDir = dir,
            oldEncKey = ByteArray(32),
            newEncKey = ByteArray(32),
            newSlots = emptyList(),
        )

        assertEquals(
            listOf("first.bin", "second.bin"),
            result.unreachable,
            "the sweep stopped at the first container it could not read, leaving the rest open " +
                "under the credential this rotation exists to retire",
        )
    }

    /**
     * `convert` deletes its temp only on the `Exception` path, so an `Error` mid-conversion leaves a
     * `.sweep.tmp` behind and this cleanup is the only thing that removes it. It has to survive the
     * failure, which is why it moved into a `finally`.
     */
    @Test
    fun `a half-written conversion is still cleaned up after an unreadable container`() {
        val leftover = File(dir, "snapshot-1.bin.sweep.tmp").apply { writeBytes(ByteArray(8)) }
        val huge = UnreadablyLargeFile(container("snapshot-2.bin"))

        StaleContainerSweeper.sweep(
            files = listOf(huge),
            backupsDir = dir,
            oldEncKey = ByteArray(32),
            newEncKey = ByteArray(32),
            newSlots = emptyList(),
        )

        assertFalse(
            leftover.exists(),
            "a half-written conversion survived the sweep and can be mistaken for a snapshot",
        )
        assertTrue(File(dir, "snapshot-2.bin").exists(), "the fixture itself must not be deleted")
    }
}

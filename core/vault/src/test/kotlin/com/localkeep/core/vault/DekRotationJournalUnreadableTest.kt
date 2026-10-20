package com.localkeep.core.vault

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A DEK rotation journal that could not be read must not be mistaken for one with nothing in it.
 *
 * The two branches end in opposite places. "Nothing in it unwraps" deletes the file, and
 * [DekRotationJournal] argues that at length: such a journal belongs to a rotation whose header
 * write never landed, so every blob is still under the live key and nothing is lost. "Could not be
 * read" says nothing of the kind — the bytes may unwrap perfectly on the next attempt — and the
 * deletion it used to reach destroys the only wrapped copy of the retired DEKs, leaving the blobs
 * sealed under them permanently unopenable.
 */
class DekRotationJournalUnreadableTest {

    private val dir: File = Files.createTempDirectory("dek-rotation-journal").toFile()
    private val journal = File(dir, "dek-rotation.journal")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /**
     * The read is injected rather than provoked with file permissions, which a test runner running
     * as root would ignore.
     */
    @Test
    fun `a journal whose bytes cannot be read is signalled, not reported as empty`() {
        journal.writeText("nonce=AAAA\nold=AAAA\nref=blob-a\n")

        assertFailsWith<DekRotationJournal.UnreadableException> {
            DekRotationJournal.readFrom(journal, ByteArray(32)) { throw IOException("input/output error") }
        }

        assertTrue(journal.isFile, "the journal must survive a failure to read it")
    }

    /** The control: the deletion the file actually argues for still happens. */
    @Test
    fun `a journal that was read and unwraps nothing is still deleted`() {
        journal.writeText("nonce=not-base64!\nold=not-base64!\nref=blob-a\n")

        val pending = DekRotationJournal.readFrom(journal, ByteArray(32))

        assertEquals(emptyList(), pending)
        assertFalse(journal.exists(), "a journal that opens nothing is dropped rather than re-read forever")
    }
}

package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.Zeroizer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Rotations stack, and the journal has to survive that.
 *
 * A pass that ends with blobs left behind — a full disk, a damaged blob — leaves its retired key
 * still needed by exactly those blobs. If the user then changes the master password again, the
 * second rotation writes a journal of its own. While that write replaced the file wholesale, the
 * first rotation's key went with it and the blobs sealed under it became unreadable by anything on
 * the device: silent, permanent loss of the user's media, while the settings screen told them
 * "It will finish the next time you unlock; nothing is lost."
 *
 * These tests pin the carry-forward at the journal itself: the file format's own promise, that
 * every entry handed to a write comes back wrapped under the incoming key, held independently of
 * any caller. The invariant they guard — a retired key stays recoverable for as long as some blob
 * still owes it — is the whole of what was lost.
 *
 * They are deliberately not the only cover, and were never sufficient on their own. A caller can
 * satisfy every one of them and still destroy a generation by handing the *next* write a shorter
 * list, which is exactly what `rotateDekUnderSlotZero`'s own blob pass did. That shape is pinned
 * end to end in `DekRotationResumeTest`, driving [VaultLockManager.changeMasterPassword] twice
 * through a store that fails on demand.
 */
@RunWith(AndroidJUnit4::class)
class DekRotationJournalGenerationsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal get() = File(context.filesDir, "dek-rotation.journal")

    @BeforeTest
    fun setUp() {
        journal.delete()
    }

    @AfterTest
    fun tearDown() {
        journal.delete()
    }

    @Test
    fun aSecondRotationKeepsTheFirstRotationsRetiredKey() {
        val dekA = EnvelopeCrypto.generateDek()
        val dekB = EnvelopeCrypto.generateDek()
        val dekC = EnvelopeCrypto.generateDek()

        // Rotation one: A retired under B, one blob left behind still sealed under A.
        DekRotationJournal.write(context, listOf(DekRotationJournal.Pending(dekA, listOf("blob-a"))), dekB)

        // Rotation two, as VaultLockManager performs it: read what is pending while the outgoing
        // DEK is still held, then write every generation back under the incoming one.
        val carried = DekRotationJournal.read(context, dekB)
        assertEquals(1, carried.size, "the first rotation's entry must be readable under its own new DEK")
        assertContentEquals(dekA, carried.single().oldDek)
        DekRotationJournal.write(
            context,
            carried + DekRotationJournal.Pending(dekB, listOf("blob-b")),
            dekC,
        )
        carried.forEach { Zeroizer.wipe(it.oldDek) }

        val after = DekRotationJournal.read(context, dekC)
        assertEquals(2, after.size, "both retired generations must survive the second rotation")
        val byRef = after.associateBy { it.refs.single() }
        assertContentEquals(dekA, byRef.getValue("blob-a").oldDek, "the older retired key was destroyed")
        assertContentEquals(dekB, byRef.getValue("blob-b").oldDek)
        after.forEach { Zeroizer.wipe(it.oldDek) }
    }

    @Test
    fun draining_one_generation_leaves_the_others_intact() {
        val dekA = EnvelopeCrypto.generateDek()
        val dekB = EnvelopeCrypto.generateDek()
        val dekC = EnvelopeCrypto.generateDek()
        DekRotationJournal.write(
            context,
            listOf(
                DekRotationJournal.Pending(dekA, listOf("blob-a")),
                DekRotationJournal.Pending(dekB, listOf("blob-b")),
            ),
            dekC,
        )

        // Generation A finishes; B still owes a blob. This is the per-blob rewrite the re-key pass
        // performs, and the bug it guards against is that rewrite dropping every other generation.
        val entries = DekRotationJournal.read(context, dekC)
        val remaining = entries.filterNot { it.refs == listOf("blob-a") }
        DekRotationJournal.write(context, remaining, dekC)
        entries.forEach { Zeroizer.wipe(it.oldDek) }

        val after = DekRotationJournal.read(context, dekC)
        assertEquals(1, after.size)
        assertEquals(listOf("blob-b"), after.single().refs)
        after.forEach { Zeroizer.wipe(it.oldDek) }
    }

    @Test
    fun anEmptyEntryListDeletesTheJournal() {
        val dekA = EnvelopeCrypto.generateDek()
        val dekB = EnvelopeCrypto.generateDek()
        DekRotationJournal.write(context, listOf(DekRotationJournal.Pending(dekA, listOf("blob-a"))), dekB)
        assertTrue(journal.isFile)

        // The last blob landing is what actually retires the key: nothing left to do, no file.
        DekRotationJournal.write(context, listOf(DekRotationJournal.Pending(dekA, emptyList())), dekB)
        assertFalse(journal.isFile, "a journal with no work left must not keep a retired key on disk")
    }

    @Test
    fun aJournalFromAnotherVaultIsDroppedWhole() {
        val dekA = EnvelopeCrypto.generateDek()
        val dekB = EnvelopeCrypto.generateDek()
        val stranger = EnvelopeCrypto.generateDek()
        DekRotationJournal.write(context, listOf(DekRotationJournal.Pending(dekA, listOf("blob-a"))), dekB)

        // Belongs to a rotation whose header write never landed, so every blob is still under the
        // key this session holds. Keeping it would mean re-reading a file no key can open, forever.
        assertEquals(emptyList(), DekRotationJournal.read(context, stranger))
        assertFalse(journal.isFile)
    }
}

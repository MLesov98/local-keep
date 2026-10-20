package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.File as FileContent
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * A blob re-key pass has to survive the process dying halfway through it.
 *
 * The retired DEK is the only key that can read the blobs that have not been converted yet, and
 * the moment the rotation commits it stops existing anywhere the next process could find it. So it
 * is written to a journal wrapped under the *new* DEK — recoverable only by a session that could
 * already open the vault — and deleted when the last blob lands. Without that, a kill between the
 * container write and the final blob is indistinguishable from the bug this all replaced.
 */
@RunWith(AndroidJUnit4::class)
class DekRotationResumeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal get() = File(context.filesDir, "dek-rotation.journal")

    private lateinit var lockManager: VaultLockManager

    @BeforeTest
    fun setUp() = runBlocking {
        wipe()
        createFreshVault()
        lockManager = newLockManager()
        lockManager.unlock(password())
        val now = Instant.now()
        VaultRepository(lockManager).addItem(
            VaultItem(
                id = UUID.randomUUID(),
                title = "Scan",
                createdAt = now,
                updatedAt = now,
                content = FileContent(
                    filename = "scan.jpg",
                    mime = "image/jpeg",
                    blobRef = FIRST_REF,
                    thumbnailRef = SECOND_REF,
                ),
            ),
        )
        Unit
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        wipe()
    }

    @Test
    fun anInterruptedPassIsFinishedByTheNextUnlock() = runBlocking {
        val dekBefore = lockManager.currentDek().copyOf()
        val halfBroken = RecordingStore(failOn = SECOND_REF)
        lockManager.registerBlobReKeyStore(halfBroken)

        val result = MasterPasswordChanger.changePassword(lockManager, password(), newPassword())

        assertTrue(result.success, "the rotation itself commits — a failed blob must not undo it")
        assertEquals(1, result.blobsReKeyed)
        assertEquals(1, result.blobsLeftBehind)
        assertTrue(journal.exists(), "the unfinished blob keeps the retired key recoverable")
        val dekAfter = lockManager.currentDek().copyOf()
        lockManager.lock()

        // A cold start: a brand-new manager, the new password, and no memory of the pass at all.
        val resumed = newLockManager()
        val working = RecordingStore(failOn = null)
        resumed.registerBlobReKeyStore(working)
        resumed.unlock(newPassword())

        assertEquals(1, resumed.resumeBlobReKey(), "the blob left behind is re-keyed on unlock")
        assertEquals(listOf(SECOND_REF), working.reKeyed, "and only that one — never a second pass")
        assertContentEquals(
            dekBefore,
            working.fromDek,
            "the resumed pass reads under the retired key the journal carried across the restart",
        )
        assertContentEquals(dekAfter, working.toDek)
        assertFalse(journal.exists(), "finishing the pass is what finally retires the old key")
        resumed.lock()
    }

    /**
     * A lock landing mid-pass must not reach into the key the pass is sealing with.
     *
     * Both callers hand [runBlobReKeyPass] the array that *is* the live session's DEK, and [lock]
     * wipes that array in place under `stateMonitor` alone — it never takes `operationMutex`, so
     * nothing serialises it against a pass in flight. A screen-off during a long re-key therefore
     * used to seal every remaining blob under 32 zero bytes, and silently: AES-GCM does not object
     * to a zero key, so each one was counted as re-keyed and the journal holding the retired key
     * was deleted at the end. The user's only copy of those files was then unreadable *and* under a
     * publicly known key, while the UI reported the password change as fully successful.
     *
     * The assertion is on the key the store was handed, not on the counts, because the counts stay
     * green through the bug — that is what made it invisible. The rest of the suite cannot catch
     * this: [newLockManager] sets `autoLockTimeoutMs = 0`, so no auto-lock coroutine is ever
     * created and no other test has a second thread that could call [lock] at all.
     */
    @Test
    fun aLockDuringThePassDoesNotZeroTheKeyTheRemainingBlobsAreSealedWith() = runBlocking {
        val started = CountDownLatch(1)
        val locked = CountDownLatch(1)
        val store = RecordingStore(failOn = null, onFirstBlob = {
            started.countDown()
            locked.await(WAIT_SECONDS, TimeUnit.SECONDS)
        })
        lockManager.registerBlobReKeyStore(store)

        val changer = Thread {
            runBlocking { MasterPasswordChanger.changePassword(lockManager, password(), newPassword()) }
        }
        changer.start()

        // Everything between starting that thread and joining it runs inside `try`, so a failure
        // here can never leave the pass parked on the latch. It did once: the control assertion
        // threw before `locked.countDown()`, the changer stayed blocked inside `reKeyBlob` holding
        // the operation mutex, and the *next* test failed with "vault locked during change the
        // master password" — a leak that made this test look like it had broken a neighbour.
        val reached: Boolean
        try {
            reached = started.await(WAIT_SECONDS, TimeUnit.SECONDS)
            if (reached) lockManager.lock()
        } finally {
            locked.countDown()
            changer.join(JOIN_MILLIS)
        }
        // Generous, because the wait spans an Argon2id derivation. At five seconds this passed
        // alone and timed out inside the full 175-test suite, where the device is busy — a
        // scheduling artefact reported as a product failure.
        assertTrue(reached, "control: the pass really did reach a blob")

        assertEquals(
            2,
            store.toDeks.size,
            "control: both blobs were attempted, so the second one ran after the lock",
        )
        store.toDeks.forEachIndexed { index, dek ->
            assertFalse(
                dek.all { it == 0.toByte() },
                "blob $index was sealed under an all-zero key — lock() wiped the pass's own DEK",
            )
        }
        assertContentEquals(
            store.toDeks[0],
            store.toDeks[1],
            "both blobs must land under the same key, whatever the lock did to the session",
        )
    }

    /**
     * A second rotation that has blobs of its own must not erase the first rotation's retired key.
     *
     * The carry-forward is written correctly — the entries read under the outgoing DEK are re-wrapped
     * under the incoming one before the container changes — and then the rotation's own blob pass
     * overwrote it. That pass rewrites the journal after every blob, as a whole-file replace, so any
     * generation not handed to it as `others` is gone; the call site was passing nothing. The blobs
     * the first rotation left behind were then sealed under a key that existed nowhere on the device,
     * while the settings screen reported a clean password change.
     *
     * The rotation with *no* blobs of its own never had the defect: it short-circuits before the pass
     * and makes no journal write at all, so the carried entry survives. Only this shape loses data.
     */
    @Test
    fun aSecondRotationsOwnBlobPassKeepsTheFirstRotationsRetiredKey() = runBlocking {
        val retired = lockManager.currentDek().copyOf()
        lockManager.registerBlobReKeyStore(RecordingStore(failOn = SECOND_REF))

        val first = MasterPasswordChanger.changePassword(lockManager, password(), newPassword())
        assertTrue(first.success)
        assertEquals(1, first.blobsLeftBehind, "control: one blob is still owed to the retired key")

        // The second rotation has blobs of its own — that is the whole condition. A working store,
        // so its own pass runs to completion and rewrites the journal after each blob.
        lockManager.registerBlobReKeyStore(RecordingStore(failOn = null))
        val second = MasterPasswordChanger.changePassword(lockManager, newPassword(), thirdPassword())
        assertTrue(second.success)

        val current = lockManager.currentDek().copyOf()
        val after = DekRotationJournal.read(context, current)
        val carried = after.firstOrNull { it.oldDek.contentEquals(retired) }
        assertNotNull(
            carried,
            "the second rotation's blob pass erased the key its own carry-forward had just saved",
        )
        assertEquals(
            listOf(SECOND_REF),
            carried.refs,
            "and it must still owe exactly the blob the first pass could not convert",
        )

        after.forEach { Zeroizer.wipe(it.oldDek) }
        Zeroizer.wipe(retired)
        Zeroizer.wipe(current)
    }

    /**
     * A lock landing between two generations of a resumed pass must not reach the key either.
     *
     * [runBlobReKeyPass] copies the key it seals with, which protects one call and only one call.
     * [resumeBlobReKey] makes one call per retired generation and used to re-read `unlocked.dek` off
     * the live state each time round, so a lock during generation N left every later generation
     * copying 32 zero bytes — sealing that generation's blobs under a publicly known key *and*
     * rewriting the journal under it, which destroys every retired key the file was carrying. The
     * counts stay green throughout, which is why the assertions here are on the key the store was
     * handed and on whether the journal is still readable, never on how many blobs were converted.
     *
     * `operationMutex` does not serialise [lock]; it takes `stateMonitor` alone. And this path takes
     * no [withSessionDek] borrow, so [wipeSessionDek]'s drain is never armed for it either.
     */
    @Test
    fun aLockBetweenGenerationsDoesNotZeroTheKeyTheNextGenerationSealsWith() = runBlocking {
        val sessionDek = lockManager.currentDek().copyOf()
        val oldest = EnvelopeCrypto.generateDek()
        val newer = EnvelopeCrypto.generateDek()
        DekRotationJournal.write(
            context,
            listOf(
                DekRotationJournal.Pending(oldest, listOf(FIRST_REF)),
                DekRotationJournal.Pending(newer, listOf(SECOND_REF)),
            ),
            sessionDek,
        )

        val started = CountDownLatch(1)
        val locked = CountDownLatch(1)
        val store = RecordingStore(failOn = null, onFirstBlob = {
            started.countDown()
            locked.await(WAIT_SECONDS, TimeUnit.SECONDS)
        })
        lockManager.registerBlobReKeyStore(store)

        val resumer = Thread { runBlocking { lockManager.resumeBlobReKey() } }
        resumer.start()

        // Same containment as the pass test above: a failure here must never leave the resume
        // parked on the latch holding the operation mutex into the next test.
        val reached: Boolean
        try {
            reached = started.await(WAIT_SECONDS, TimeUnit.SECONDS)
            if (reached) lockManager.lock()
        } finally {
            locked.countDown()
            resumer.join(JOIN_MILLIS)
        }
        assertTrue(reached, "control: the resume really did reach the first generation's blob")

        store.toDeks.forEachIndexed { index, dek ->
            assertFalse(
                dek.all { it == 0.toByte() },
                "generation $index sealed under an all-zero key — lock() reached the resume's DEK",
            )
            assertContentEquals(sessionDek, dek, "generation $index sealed under a key from nowhere")
        }
        val after = DekRotationJournal.read(context, sessionDek)
        val stillOwed = after.firstOrNull { it.oldDek.contentEquals(newer) }
        assertNotNull(
            stillOwed,
            "the journal was rewritten under a zeroed key, so every retired key in it was lost",
        )
        assertEquals(listOf(SECOND_REF), stillOwed.refs, "and the unconverted blob stays owed")

        after.forEach { Zeroizer.wipe(it.oldDek) }
        Zeroizer.wipe(oldest)
        Zeroizer.wipe(newer)
        Zeroizer.wipe(sessionDek)
    }

    /** The journal is wrapped under the DEK the rotation installed, so nothing else can open it. */
    @Test
    fun aJournalThatDoesNotBelongToThisSessionIsDiscardedNotActedOn() = runBlocking {
        val strangerDek = EnvelopeCrypto.generateDek()
        DekRotationJournal.write(
            context,
            listOf(DekRotationJournal.Pending(strangerDek, listOf(FIRST_REF))),
            strangerDek,
        )
        assertTrue(journal.exists(), "control: a journal really is there to be found")

        val store = RecordingStore(failOn = null)
        lockManager.registerBlobReKeyStore(store)

        assertEquals(0, lockManager.resumeBlobReKey())
        assertTrue(store.reKeyed.isEmpty(), "a blob must never be re-keyed under a key from nowhere")
        assertFalse(journal.exists(), "and it is dropped rather than re-read at every unlock forever")
        Zeroizer.wipe(strangerDek)
    }

    /**
     * A blob converted by a pass that died before it could rewrite the journal is owed nothing, and
     * the journal has to be able to tell.
     *
     * The pass seals the blob first and shrinks the journal second, so a kill between the two leaves
     * a ref listed for work that is already done. Re-keying it again fails — it is no longer sealed
     * under the retired key the pass reads with — and it failed again at every later unlock, so the
     * pass could never finish and the journal carrying the retired DEK was never dropped.
     *
     * What settles it is the probe, not a count of attempts: the blob authenticates under the key
     * the pass is converting *to*, which is proof it was already converted. The assertion on the
     * probed key is the load-bearing one — a probe that accepted any key at all would drop refs
     * whose blobs are still under the retired one.
     */
    @Test
    fun aBlobAlreadyConvertedByAnInterruptedPassLetsTheRotationFinish() = runBlocking {
        val sessionDek = lockManager.currentDek().copyOf()
        val retired = EnvelopeCrypto.generateDek()
        DekRotationJournal.write(
            context,
            listOf(DekRotationJournal.Pending(retired, listOf(FIRST_REF))),
            sessionDek,
        )
        val store = ProbingStore(opensUnder = setOf(FIRST_REF))
        lockManager.registerBlobReKeyStore(store)

        assertEquals(0, lockManager.resumeBlobReKey(), "nothing is re-keyed — the blob already was")
        assertFalse(journal.exists(), "that one ref is what kept the retired key on disk forever")
        assertContentEquals(
            sessionDek,
            store.probedWith.single(),
            "the proof is that it opens under the key the pass converts to, not under any key",
        )

        Zeroizer.wipe(retired)
        Zeroizer.wipe(sessionDek)
    }

    /**
     * A blob that opens under neither key is genuinely damaged, and its generation's key stays.
     *
     * This is the half of the fix that was deliberately not taken: abandoning a ref after N failed
     * attempts would clear the journal, and the journal holds the only surviving copy of the retired
     * DEK — every other ref in that entry is still sealed under it. So the damaged blob's entry is
     * kept, whole, and the rotation stays unfinished rather than becoming unrecoverable.
     *
     * Entries are dropped one at a time for the same reason, which is what the second generation
     * here pins: proof about one blob must never reach another generation's key.
     */
    @Test
    fun aBlobThatOpensUnderNeitherKeyKeepsItsGenerationsRetiredKey() = runBlocking {
        val sessionDek = lockManager.currentDek().copyOf()
        val damagedGeneration = EnvelopeCrypto.generateDek()
        val convertedGeneration = EnvelopeCrypto.generateDek()
        DekRotationJournal.write(
            context,
            listOf(
                DekRotationJournal.Pending(damagedGeneration, listOf(FIRST_REF)),
                DekRotationJournal.Pending(convertedGeneration, listOf(SECOND_REF)),
            ),
            sessionDek,
        )
        val store = ProbingStore(opensUnder = setOf(SECOND_REF))
        lockManager.registerBlobReKeyStore(store)

        assertEquals(0, lockManager.resumeBlobReKey())

        val after = DekRotationJournal.read(context, sessionDek)
        assertEquals(1, after.size, "only the entry with proof behind it may leave")
        assertEquals(listOf(FIRST_REF), after.single().refs)
        assertContentEquals(
            damagedGeneration,
            after.single().oldDek,
            "the damaged blob's only key must survive its own failure",
        )

        after.forEach { Zeroizer.wipe(it.oldDek) }
        Zeroizer.wipe(damagedGeneration)
        Zeroizer.wipe(convertedGeneration)
        Zeroizer.wipe(sessionDek)
    }

    /**
     * Every re-key fails, which is exactly what a blob converted by an interrupted pass does — it is
     * no longer sealed under the retired key it is being read with. [opensUnder] is the only thing
     * that separates that from a blob that is genuinely damaged.
     */
    private class ProbingStore(private val opensUnder: Set<String>) : BlobReKeyStore {
        /** Each key the probe was handed, copied at the moment of the call. */
        val probedWith = mutableListOf<ByteArray>()

        override fun hasBlob(blobRef: String): Boolean = true

        override fun reKeyBlob(blobRef: String, fromDek: ByteArray, toDek: ByteArray): Unit =
            throw java.io.IOException("simulated blob failure")

        override fun opensUnder(blobRef: String, dek: ByteArray): Boolean {
            probedWith += dek.copyOf()
            return blobRef in opensUnder
        }
    }

    private class RecordingStore(
        private val failOn: String?,
        private val onFirstBlob: (() -> Unit)? = null,
    ) : BlobReKeyStore {
        val reKeyed = mutableListOf<String>()
        var fromDek: ByteArray? = null
        var toDek: ByteArray? = null

        /**
         * Every key the pass sealed with, copied at the moment it was handed over. Copying here is
         * the whole point: the pass must be holding its own array, so a wipe of the session's DEK
         * after this call cannot reach back and change what was recorded.
         */
        val toDeks = mutableListOf<ByteArray>()

        private var seen = 0

        override fun hasBlob(blobRef: String): Boolean = true

        override fun reKeyBlob(blobRef: String, fromDek: ByteArray, toDek: ByteArray) {
            if (seen++ == 0) onFirstBlob?.invoke()
            if (blobRef == failOn) throw java.io.IOException("simulated blob failure")
            this.fromDek = fromDek.copyOf()
            this.toDek = toDek.copyOf()
            toDeks += toDek.copyOf()
            reKeyed += blobRef
        }
    }

    private fun VaultLockManager.currentDek(): ByteArray =
        (lockState.value as LockState.Unlocked).dek

    private fun newLockManager() =
        VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }

    private fun password() = "correct horse battery staple".toCharArray()

    private fun newPassword() = "a different long passphrase".toCharArray()

    private fun thirdPassword() = "a third long passphrase entirely".toCharArray()

    private fun createFreshVault() {
        val password = password()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, KdfParams.FLOOR)
        try {
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = KdfParams.FLOOR,
                    wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
                    commitment = EnvelopeCrypto.slotCommitment(dek),
                ),
            )
        } finally {
            Zeroizer.wipe(password)
            Zeroizer.wipe(dek)
            Zeroizer.wipe(kek)
        }
    }

    private fun wipe() {
        context.filesDir.listFiles()?.forEach(File::deleteRecursively)
    }

    private companion object {
        const val FIRST_REF = "blob-one"
        const val WAIT_SECONDS = 60L
        const val JOIN_MILLIS = 60_000L
        const val SECOND_REF = "blob-two"
    }
}

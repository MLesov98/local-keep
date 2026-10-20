package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * **A replaced Recovery Kit must be dead in every container on disk, not only in `vault.enc`.**
 *
 * The gate copy on the Recovery Kit screen says making a new kit retires the one the user may
 * already be holding, and the code did that for exactly one file. Minting a kit rotates nothing —
 * it splices a new wrapped-key block into the live container and stops — while the write that
 * installs it moves the previous generation to `vault.enc.bak`, and `backups/` holds the newest
 * five saves. Every one of those still carried the retired kit's block, wrapping the *same* DEK, so
 * the 24 words the app had just declared void went on unwrapping the live data key out of a file
 * sitting beside the live vault. Found 2026-09-13.
 *
 * Asserted against the bytes on disk rather than against a call count: a fix that "ran the sweep"
 * but left a container behind passes the second and fails the first.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryKitReplacementRevocationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    @BeforeTest
    fun setup() {
        cleanupFiles()
        runBlocking { VaultInitializer.create(context, MASTER_PASSWORD.toCharArray()) }
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
        repository = VaultRepository(lockManager)
    }

    @AfterTest
    fun teardown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    /**
     * The invariant. After a kit replacement, the retired words open nothing left on disk — and the
     * new ones open all of it, which is what separates this from a fix that simply strips blocks.
     */
    @Test
    fun theRetiredKitStopsOpeningEveryContainerOnDisk() = runBlocking {
        val (kitA, snapshots) = fixtureWithKitAEverywhere()
        val bak = File(context.filesDir, AtomicVaultWriter.BACKUP_FILE)

        val kitB = assertNotNull(saveANewRecoveryKit(), "the replacement kit must commit")
        assertFalse(kitA == kitB, "the two kits must not have produced the same words")

        val wordsA = kitA.split(" ")
        assertFalse(
            kitOpens(wordsA, bak),
            "the retired Recovery Kit still unwraps the data key from ${bak.name}",
        )
        snapshots.forEach {
            assertFalse(
                kitOpens(wordsA, it),
                "the retired Recovery Kit still unwraps the data key from snapshot ${it.name}",
            )
        }

        // Spliced, not stripped. Stripping would revoke the kit the user has just written down in
        // every snapshot they might later restore, which is a different way to break the same
        // promise — so the new kit has to be present, not merely the old one absent.
        val wordsB = kitB.split(" ")
        assertTrue(kitOpens(wordsB, bak), "the new kit must open ${bak.name}")
        snapshots.forEach {
            assertTrue(kitOpens(wordsB, it), "the new kit must open snapshot ${it.name}")
        }
    }

    /**
     * The control, green before the fix and after it. A kit replacement must not cost the user a
     * rollback: the `.bak` has to survive, still restore, and still open under the master password
     * with its items intact. A "fix" that deleted the stale containers passes the test above and
     * fails this one.
     */
    @Test
    fun theBackupContainerStillRestoresAndOpensAfterAKitReplacement() = runBlocking {
        fixtureWithKitAEverywhere()
        val bak = File(context.filesDir, AtomicVaultWriter.BACKUP_FILE)

        assertNotNull(saveANewRecoveryKit(), "the replacement kit must commit")

        assertTrue(bak.isFile, "vault.enc.bak must not be deleted by a kit replacement")
        val bytes = bak.readBytes()
        lockManager.lock()
        assertEquals(
            RestoreOutcome.OK,
            lockManager.restoreVaultFile(bytes),
            "the .bak must still restore",
        )
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        assertEquals(setOf(FIRST_ITEM, SECOND_ITEM), repository.getAllItems().map { it.title }.toSet())
    }

    // --- fixture -----------------------------------------------------------------------------

    /**
     * Leaves kit A installed in the live vault, in `vault.enc.bak`, and in two snapshots.
     *
     * The two saves matter and are not padding: the first puts a kit-less generation in the `.bak`,
     * the second is what moves the kit-A container there. The assertions below prove the fixture
     * actually did that — without them the revocation assertions would pass on a `.bak` that never
     * held kit A at all.
     */
    private suspend fun fixtureWithKitAEverywhere(): Pair<String, List<File>> {
        lockManager.unlock(password())
        repository.addItem(item(FIRST_ITEM))
        val kitA = assertNotNull(saveANewRecoveryKit(), "the first kit must commit")
        // A save under kit A, so the container the next write retires is one carrying kit A.
        repository.addItem(item(SECOND_ITEM))

        val bak = File(context.filesDir, AtomicVaultWriter.BACKUP_FILE)
        assertTrue(bak.isFile, "the fixture must leave a .bak behind, or this test proves nothing")
        val wordsA = kitA.split(" ")
        assertTrue(kitOpens(wordsA, bak), "the fixture must leave kit A's block in ${bak.name}")

        val snapshots = seedSnapshots(2)
        snapshots.forEach {
            assertTrue(kitOpens(wordsA, it), "the fixture must leave kit A's block in ${it.name}")
        }
        return kitA to snapshots
    }

    /** Whether [words] unwrap the data key from [file]'s recovery block. */
    private fun kitOpens(words: List<String>, file: File): Boolean =
        VaultKitMatch.isVaultRecoveryKit(words, VaultFileCodec.recoveryWrappedDek(file.readBytes()))

    /** Copies the live vault into `backups/`, exactly as RollingBackupManager does on each save. */
    private fun seedSnapshots(count: Int): List<File> {
        val dir = backupsDir().apply { mkdirs() }
        val live = File(context.filesDir, AtomicVaultWriter.VAULT_FILE).readBytes()
        return (1..count).map { n ->
            File(dir, "vault-2026091%d-00000%d.enc".format(n, n)).apply { writeBytes(live) }
        }
    }

    private suspend fun saveANewRecoveryKit(): String? {
        val mnemonic = lockManager.prepareRecoveryKit() ?: return null
        return if (lockManager.commitPreparedRecoveryKit()) mnemonic else null
    }

    private fun item(title: String): VaultItem {
        val now = Instant.now()
        return VaultItem(
            id = UUID.nameUUIDFromBytes(title.toByteArray()),
            title = title,
            createdAt = now,
            updatedAt = now,
            content = Login(username = "ada@example.com", password = "hunter2", passwordChangedAt = now),
        )
    }

    private fun backupsDir() = File(context.filesDir, StaleContainerSweeper.BACKUPS_DIR)

    private fun cleanupFiles() {
        File(context.filesDir, AtomicVaultWriter.VAULT_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.BACKUP_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.TEMP_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.BACKUP_FILE + ".sweep.tmp").delete()
        backupsDir().deleteRecursively()
    }

    private fun password(): CharArray = MASTER_PASSWORD.toCharArray()

    private companion object {
        const val MASTER_PASSWORD = "test-master-password-123"
        const val FIRST_ITEM = "Bank"
        const val SECOND_ITEM = "Mail"
    }
}

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
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The lost update: two writers editing one item at the same time, and one edit vanishing.
 *
 * This app really does have two writers on one vault — the autofill Save-confirm activity accepting
 * a browser's "update your saved password?" while an item editor, a file screen or an attachment
 * picker is open. Callers used to read the item outside [VaultRepository]'s mutation lock and write
 * a modified copy back inside it, so anything committed in the gap was replaced wholesale. Only the
 * password survived that, through `ItemMerge.preserveNewerPassword`; a title, folder, note or
 * attachment was gone with nothing shown to the user. [VaultRepository.updateWith] closes the gap
 * by doing the read, the edit and the commit under one lock.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrentItemUpdateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    /** The floor `KdfParams` enforces — this suite is about locking, and pays the KDF once. */
    private val kdfParams = KdfParams(memoryKib = 24_576, iterations = 3, parallelism = 2)

    @BeforeTest
    fun setup() {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
        repository = VaultRepository(lockManager)
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    /**
     * The defect in its smallest honest form: one writer renames the item, another rotates its
     * password, and both edits have to be in the vault afterwards regardless of who won the race.
     *
     * Repeated, because a single pair can interleave harmlessly by luck. Each round is a fresh
     * title and password, so a round that loses one is a round whose assertion names which.
     */
    @Test
    fun aRenameAndAPasswordChangeRacingOnOneItemBothSurvive() = runBlocking {
        lockManager.unlock(password())
        val id = seedLogin()
        repeat(ROUNDS) { round ->
            val title = "title-$round"
            val secret = "password-$round"
            listOf(
                async(Dispatchers.Default) {
                    repository.updateWith(id) { it.copy(title = title) }
                },
                async(Dispatchers.Default) {
                    repository.updateWith(id) { stored ->
                        stored.copy(content = (stored.content as Login).copy(password = secret))
                    }
                },
            ).awaitAll()
            val stored = repository.getAllItems().single { it.id == id }
            assertEquals(title, stored.title, "round $round lost the rename")
            assertEquals(
                secret,
                (stored.content as Login).password,
                "round $round lost the password change",
            )
        }
    }

    /**
     * The same defect counted rather than paired, which is what makes it reproduce every run rather
     * than sometimes: every writer appends one character to the title, so the final length *is* the
     * number of edits that survived. A read outside the lock drops some of them.
     */
    @Test
    fun everyConcurrentEditToOneItemLands() = runBlocking {
        lockManager.unlock(password())
        val id = seedLogin()
        (1..WRITERS).map {
            async(Dispatchers.Default) {
                repository.updateWith(id) { stored -> stored.copy(title = stored.title + "x") }
            }
        }.awaitAll()
        assertEquals(
            SEED_TITLE.length + WRITERS,
            repository.getAllItems().single { it.id == id }.title.length,
            "an edit was overwritten by a writer holding a stale copy of the item",
        )
    }

    private suspend fun seedLogin(): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.mutate { mutator ->
            mutator.items.add(
                VaultItem(
                    id = id,
                    title = SEED_TITLE,
                    createdAt = now,
                    updatedAt = now,
                    content = Login(username = "me", password = "seed"),
                ),
            )
        }
        return id
    }

    private fun createFreshVault() {
        val password = password()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, kdfParams)
        try {
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = kdfParams,
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

    private fun cleanupFiles() {
        File(context.filesDir, AtomicVaultWriter.VAULT_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.BACKUP_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.TEMP_FILE).delete()
        File(context.filesDir, DeviceKeyStore.FILE_NAME).delete()
        File(context.filesDir, StaleContainerSweeper.BACKUPS_DIR).deleteRecursively()
    }

    private fun password(): CharArray = "test-master-password-123".toCharArray()

    private companion object {
        const val SEED_TITLE = "seed"
        const val ROUNDS = 20
        const val WRITERS = 24
    }
}

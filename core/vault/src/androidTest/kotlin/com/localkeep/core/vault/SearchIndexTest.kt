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
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * The three things the search index must never get wrong.
 *
 * The index exists for speed — it took a keystroke over a 10,000-item vault from 237 ms to 51 ms
 * (`VaultScaleTest`) — and speed is not what can hurt anyone here. These cover the ways a cache
 * of decrypted text can:
 *
 *  1. **It must not change which items match.** Correctness is meant to be structural: a mutated
 *     item is a new instance, so it can never carry a stale entry, and a miss falls back to the
 *     live matcher. That is an argument, not evidence, and the argument is exactly the kind that
 *     stops being true when someone later "simplifies" the fallback away.
 *  2. **It must not survive `lock()`.** This is the one that matters. The index holds a lowercased
 *     copy of every title, username, URL and note, on a `@Singleton`, and nothing else in the
 *     process would have dropped it — so without the collector in `VaultRepository`'s `init`, a
 *     locked vault leaves its plaintext strongly reachable for the life of the app.
 *  3. **It must not grow without bound.** The entries roughly double the resident plaintext text,
 *     which is free on a 192 MiB heap cap and not obviously free on API 28's 48 MiB. The build
 *     stops at a share of `maxMemory()`, and stopping early is only safe because of (1).
 */
@RunWith(AndroidJUnit4::class)
class SearchIndexTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    /** The floor `KdfParams` enforces — these tests are about search, and pay the KDF three times. */
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
     * A rename has to be visible to the very next search, including when the old text was already
     * indexed by an earlier one. Written as the round trip a user performs — find it, rename it,
     * look for it again — rather than as an assertion about cache internals, so it keeps its
     * meaning if the caching strategy is replaced.
     */
    @Test
    fun anEditIsVisibleToTheNextSearch() = runBlocking {
        lockManager.unlock(password())
        seed("alpha bank", "beta store")

        assertEquals(listOf("alpha bank"), repository.search("alpha").map { it.title })

        val target = repository.search("alpha").single()
        repository.updateItem(target.copy(title = "gamma credit", updatedAt = Instant.now()))

        assertEquals(emptyList(), repository.search("alpha").map { it.title }, "the old title must stop matching")
        assertEquals(listOf("gamma credit"), repository.search("gamma").map { it.title })
    }

    /** Same round trip for an item that did not exist when the index was built. */
    @Test
    fun anItemAddedAfterASearchIsFound() = runBlocking {
        lockManager.unlock(password())
        seed("alpha bank")
        repository.search("alpha")

        seed("delta airlines")

        assertEquals(listOf("delta airlines"), repository.search("delta").map { it.title })
    }

    /**
     * The security-relevant one: locking must take the decrypted text with it.
     *
     * Fails without the `lockState` collector in [VaultRepository]'s `init` — the index would still
     * hold every indexed string, and being a `@Singleton` there is nothing else to release it.
     */
    @Test
    fun lockingDropsTheIndex() = runBlocking {
        lockManager.unlock(password())
        seed("alpha bank", "beta store")
        repository.search("alpha")
        assertEquals(2, repository.indexedItemCount, "the search should have built an index to drop")

        lockManager.lock()

        // The collector runs on another dispatcher, so this is "promptly", not "by the next line".
        withTimeout(5_000) {
            while (repository.indexedItemCount != 0) delay(10)
        }
    }

    /**
     * The cap must bound the index without changing a single search result.
     *
     * The index roughly doubles the resident plaintext text of the vault, which is free on the API
     * 35 image's 192 MiB heap cap and not obviously free on API 28's 48 MiB — the same cap a
     * 10,000-item save already cannot fit in (H-2). So the build stops at a share of `maxMemory()`.
     *
     * Everything that makes that safe is the fallback: an item with no index entry has its haystack
     * computed inline, so a partial index is a partial speed-up and never a wrong answer. This
     * asserts exactly that, with a budget small enough that most of the vault is left out — the
     * un-indexed items must still be findable, by the same queries, with the same results.
     *
     * Uses a tiny injected budget rather than a vault the size of the heap: the interesting
     * behaviour is "index is partial", not "device is small", and reproducing the latter honestly
     * would mean allocating until this test process died.
     */
    @Test
    fun aCappedIndexStillFindsEveryItem() = runBlocking {
        val titles = (0 until 40).map { "item-%02d unique-token-%02d".format(it, it) }
        lockManager.unlock(password())
        seed(*titles.toTypedArray())

        // Room for a couple of items at most; every title above is well over 20 characters.
        val capped = VaultRepository(lockManager, indexCharBudget = 40L)

        assertEquals(40, capped.getAllItems().size)
        for ((index, title) in titles.withIndex()) {
            assertEquals(
                listOf(title),
                capped.search("unique-token-%02d".format(index)).map { it.title },
                "item $index must be findable whether or not the index reached it",
            )
        }
        assertTrue(
            capped.indexedItemCount < titles.size,
            "the budget was 40 characters — the index must not have covered all ${titles.size} items",
        )
    }

    /**
     * Renaming a folder has to be visible to the very next search — the regression that the index's
     * entry-reuse rule exists to prevent.
     *
     * Entries are keyed on item **identity**, which is what makes an ordinary edit cheap: a commit
     * rebuilds the list leaving untouched items as the same instances, so only the edited one is
     * recomputed. A folder rename touches no item at all, so every item keeps its identity and
     * would keep its cached haystack — still carrying the old folder name, forever. Written as the
     * round trip a user performs, like `anEditIsVisibleToTheNextSearch` above, so it survives a
     * change of caching strategy.
     */
    @Test
    fun aFolderRenameIsVisibleToTheNextSearch() = runBlocking {
        lockManager.unlock(password())
        val folderId = UUID.randomUUID()
        seedInFolder(folderId, "Finance", "alpha bank")

        // Build the index against the old name first — without this the rename would be picked up
        // by a cold build and the test would pass with the invalidation removed.
        assertEquals(listOf("alpha bank"), repository.search("finance").map { it.title })

        repository.mutate { mutator ->
            val existing = mutator.folders.single { it.id == folderId }
            mutator.folders[mutator.folders.indexOf(existing)] =
                existing.copy(name = "Savings", updatedAt = Instant.now())
        }

        assertEquals(emptyList(), repository.search("finance").map { it.title }, "the old folder name must stop matching")
        assertEquals(listOf("alpha bank"), repository.search("savings").map { it.title })
    }

    /** An item is findable by its folder's name, not only by its own fields. */
    @Test
    fun anItemIsFoundByItsFolderName() = runBlocking {
        lockManager.unlock(password())
        val folderId = UUID.randomUUID()
        seedInFolder(folderId, "Travel", "alpha bank")
        seed("beta store")

        assertEquals(
            listOf("alpha bank"),
            repository.search("travel").map { it.title },
            "only the item filed in Travel may match its name",
        )
    }

    // --- fixture ---------------------------------------------------------------------------------

    private suspend fun seedInFolder(folderId: UUID, folderName: String, vararg titles: String) {
        repository.mutate { mutator ->
            val now = Instant.now()
            mutator.folders.add(Folder(id = folderId, name = folderName, updatedAt = now))
            titles.forEach { title ->
                mutator.items.add(
                    VaultItem(
                        id = UUID.randomUUID(),
                        title = title,
                        folderId = folderId,
                        createdAt = now,
                        updatedAt = now,
                        content = Login(username = "me"),
                    ),
                )
            }
        }
    }

    private suspend fun seed(vararg titles: String) {
        repository.mutate { mutator ->
            titles.forEach { title ->
                val now = Instant.now()
                mutator.items.add(
                    VaultItem(
                        id = UUID.randomUUID(),
                        title = title,
                        createdAt = now,
                        updatedAt = now,
                        content = Login(username = "me"),
                    ),
                )
            }
        }
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
}

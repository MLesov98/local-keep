package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * What a folder name means to search now that a folder is a location rather than a label.
 *
 * The repository's search stays global on purpose — the vault list's "search everywhere" offer
 * counts its results against the global set, so scoping down here would zero the counter and the
 * offer would claim there was nothing outside the folder. What changes is the haystack: an item in
 * "Work > Bank" is *in* Work, so the whole ancestor chain matches, one entry per ancestor.
 */
@RunWith(AndroidJUnit4::class)
class SubtreeSearchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    @BeforeTest
    fun setup() = runBlocking {
        FolderTestVault.cleanup(context)
        FolderTestVault.create(context)
        lockManager = VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }
        repository = VaultRepository(lockManager)
        lockManager.unlock(FolderTestVault.password())
        Unit
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        FolderTestVault.cleanup(context)
    }

    @Test
    fun anAncestorNameFindsAnItemNestedUnderIt() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        val id = seed("statement", bank.id)

        assertEquals(listOf(id), repository.search("work").map { it.id })
        assertEquals(listOf(id), repository.search("bank").map { it.id })
    }

    @Test
    fun aSiblingFolderNameDoesNotMatchAnItemOutsideIt() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        repository.createFolder("Cards", work.id)
        seed("statement", bank.id)

        assertTrue(repository.search("cards").isEmpty())
    }

    @Test
    fun theAncestorChainIsMatchedPerNameRatherThanAsOneJoinedString() = runBlocking {
        // A joined "work bank" haystack would match the query "k b", which is nonsense — matching
        // is substring-per-entry, so the entries have to stay separate.
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        seed("statement", bank.id)

        assertTrue(repository.search("k b").isEmpty())
    }

    @Test
    fun movingAFolderReindexesEveryItemInItsSubtree() = runBlocking {
        val work = repository.createFolder("Work")
        val personal = repository.createFolder("Personal")
        val bank = repository.createFolder("Bank", work.id)
        val id = seed("statement", bank.id)

        // Prime the index against the old chain, which is the state that used to go stale: the
        // items themselves do not change when their folder moves.
        assertEquals(listOf(id), repository.search("work").map { it.id })

        repository.moveFolder(bank.id, personal.id)

        assertTrue(repository.search("work").isEmpty())
        assertEquals(listOf(id), repository.search("personal").map { it.id })
    }

    @Test
    fun renamingAnAncestorIsImmediatelySearchable() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        val id = seed("statement", bank.id)
        assertEquals(listOf(id), repository.search("work").map { it.id })

        repository.renameFolder(work.id, "Employment")

        assertTrue(repository.search("work").isEmpty())
        assertEquals(listOf(id), repository.search("employment").map { it.id })
    }

    @Test
    fun folderNamesFoldDiacriticsLikeEveryOtherField() = runBlocking {
        val folder = repository.createFolder("Épargne")
        val id = seed("livret", folder.id)
        assertEquals(listOf(id), repository.search("epargne").map { it.id })
    }

    @Test
    fun searchStaysGlobalSoTheEscapeOfferCanCountWhatIsOutside() = runBlocking {
        val work = repository.createFolder("Work")
        val personal = repository.createFolder("Personal")
        seed("statement", work.id)
        seed("statement", personal.id)
        seed("statement", null)

        assertEquals(3, repository.search("statement").size)
    }

    private suspend fun seed(title: String, folderId: UUID?): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = title,
                folderId = folderId,
                createdAt = now,
                updatedAt = now,
                content = Login(username = "u", password = "p"),
            ),
        )
        return id
    }
}

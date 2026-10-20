package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * [VaultRepository.duplicateFolder] — a deep copy that shares nothing with the original.
 *
 * The one write path that mints a whole subtree at once, so the things worth asserting are that it
 * copies *everything the user can see* under the folder (nested folders and the items filed in
 * them — never trash, never a concealed record outside a reveal session), that it copies
 * *nothing by reference* (every id is new, the original is untouched), and that it obeys the same
 * two guards every other folder write does — a real folder, a sibling-unique name.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateFolderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    /** The "(copy)" wording the UI would resolve from a string resource; ordinal 1 is the first try. */
    private val copy: (String, Int) -> String = { name, ordinal ->
        if (ordinal == 1) "$name (copy)" else "$name (copy $ordinal)"
    }

    @BeforeTest
    fun setup() = runBlocking {
        FolderTestVault.cleanup(context)
        FolderTestVault.create(context)
        lockManager = VaultLockManager(context, com.localkeep.core.crypto.KeystoreManager(context))
            .also { it.autoLockTimeoutMs = 0 }
        repository = VaultRepository(lockManager)
        lockManager.unlock(FolderTestVault.password())
        Unit
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        FolderTestVault.cleanup(context)
    }

    private fun folders() =
        (lockManager.lockState.value as LockState.Unlocked).vault.folders

    private suspend fun seedItem(folderId: UUID?): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "item-$id",
                folderId = folderId,
                createdAt = now,
                updatedAt = now,
                content = Login(username = "u", password = "p"),
            ),
        )
        return id
    }

    @Test
    fun duplicateDeepCopiesSubtreeAndItemsWithFreshIds() = runBlocking {
        val work = repository.createFolder("Work")
        val clients = repository.createFolder("Clients", work.id)
        val itemInWork = seedItem(work.id)
        val itemInClients = seedItem(clients.id)

        val clone = repository.duplicateFolder(work.id, copy)

        // Root clone: new id, disambiguated name, same (null) parent as the original.
        assertEquals("Work (copy)", clone.name)
        assertNull(clone.parentId)
        assertNotEquals(work.id, clone.id)

        val folders = folders()
        // The nested folder is cloned under the clone's root, keeping its name.
        val cloneChild = folders.single { it.parentId == clone.id }
        assertEquals("Clients", cloneChild.name)
        assertNotEquals(clients.id, cloneChild.id)

        // Items are cloned under the matching cloned folder, with fresh ids; originals untouched.
        val items = repository.getAllItems()
        assertTrue(items.any { it.id == itemInWork && it.folderId == work.id })
        assertTrue(items.any { it.id == itemInClients && it.folderId == clients.id })
        assertEquals(1, items.count { it.folderId == clone.id && it.id != itemInWork })
        assertEquals(1, items.count { it.folderId == cloneChild.id && it.id != itemInClients })

        // Original tree intact: still exactly one "Work" with the original id.
        assertTrue(folders.any { it.id == work.id && it.name == "Work" })
    }

    @Test
    fun duplicateOfEmptyFolderMakesOneNewEmptyFolder() = runBlocking {
        val empty = repository.createFolder("Empty")
        val clone = repository.duplicateFolder(empty.id, copy)
        assertEquals("Empty (copy)", clone.name)
        assertEquals(0, repository.getAllItems().count { it.folderId == clone.id })
        assertEquals(2, folders().size)
    }

    @Test
    fun duplicateDisambiguatesWhenACopyNameAlreadyExists() = runBlocking {
        val work = repository.createFolder("Work")
        val first = repository.duplicateFolder(work.id, copy)
        val second = repository.duplicateFolder(work.id, copy)
        assertEquals("Work (copy)", first.name)
        assertEquals("Work (copy 2)", second.name)
    }

    @Test
    fun duplicateOfADepthEightFolderSucceedsAtTheSameDepth() = runBlocking {
        var parent = repository.createFolder("d1").id
        repeat(FolderTree.MAX_DEPTH - 1) { parent = repository.createFolder("d${it + 2}", parent).id }
        // The deepest folder sits at MAX_DEPTH; its clone is a sibling at the same depth, so it is legal.
        val clone = repository.duplicateFolder(parent, copy)
        assertTrue(clone.name.startsWith("d${FolderTree.MAX_DEPTH} (copy)"))
    }

    @Test
    fun duplicateRefusesAnUnknownFolder() = runBlocking {
        assertFailsWith<IllegalStateException> { repository.duplicateFolder(UUID.randomUUID(), copy) }
        Unit
    }

    private fun items() = (lockManager.lockState.value as LockState.Unlocked).vault.items

    /**
     * 2026-10-02 (S1-01): an item made invisible inside a visible folder was copied with its lease
     * cleared — one menu action outside a reveal session, and a permanently visible copy. The JVM
     * twin is `DuplicateCopiesOnlyWhatIsVisibleTest`; this one runs the real hide and save.
     */
    @Test
    fun duplicateDoesNotCopyAConcealedItemOutsideARevealSession() = runBlocking {
        val work = repository.createFolder("Work")
        val visible = seedItem(work.id)
        val concealed = seedItem(work.id)
        assertEquals(
            HideOutcome.Hidden,
            repository.hide(
                HiddenTarget.Item(concealed),
                until = Instant.now().plusSeconds(3600),
                firstPhrase = "swordfish please".toCharArray(),
            ),
            "control: the item really was hidden",
        )
        lockManager.concealHiddenSession()
        assertFalse(lockManager.revealSession.revealed.value, "control: no reveal session")

        val clone = repository.duplicateFolder(work.id, copy)

        val copies = items().filter { it.folderId == clone.id }
        assertEquals(listOf("item-$visible"), copies.map { it.title }, "only the visible item is copied")
        assertTrue(items().any { it.id == concealed && it.hiddenUntil != null }, "the original stays hidden")
    }

    /** 2026-10-02 (S1-02): trashed items and subfolders were copied with their trash markers. */
    @Test
    fun duplicateDoesNotCopyTrashedItemsOrSubfolders() = runBlocking {
        val work = repository.createFolder("Work")
        val old = repository.createFolder("Old", work.id)
        seedItem(old.id)
        val kept = seedItem(work.id)
        val binned = seedItem(work.id)
        repository.moveToTrash(binned)
        repository.trashFolder(old.id)
        val trashedBefore = items().count { it.deletedAt != null }

        val clone = repository.duplicateFolder(work.id, copy)

        assertTrue(folders().none { it.parentId == clone.id }, "the trashed subfolder is not copied")
        assertEquals(listOf("item-$kept"), items().filter { it.folderId == clone.id }.map { it.title })
        assertEquals(trashedBefore, items().count { it.deletedAt != null }, "Trash gains no rows")
    }
}

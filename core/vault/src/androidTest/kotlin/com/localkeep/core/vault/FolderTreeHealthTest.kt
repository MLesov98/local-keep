package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Login
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The way out of a folder tree the app cannot write to.
 *
 * The restore path commits raw vault bytes with no folder validation at all, so a snapshot can
 * arrive holding a cycle, a folder nested past the depth limit, or items naming folders that are
 * not in it. Since every folder-list write validates, such a vault would refuse *all* of them
 * forever — a write-brick with no user-visible cause. The health check finds it on unlock and the
 * repair is the only thing that can get past it.
 */
@RunWith(AndroidJUnit4::class)
class FolderTreeHealthTest {
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
    fun aHealthyVaultIsNotOfferedARepair() = runBlocking {
        val work = repository.createFolder("Work")
        repository.createFolder("Bank", work.id)

        assertFalse(repository.checkFolderTreeHealth().needsRepair)
        assertFalse(repository.needsFolderRepair)
    }

    @Test
    fun aCyclicTreeAndAnOrphanedItemAreBothFoundAndBothRepaired() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val itemId = commitRaw(
            folders = listOf(
                Folder(a, "A", parentId = b, parentUpdatedAt = Instant.parse("2020-01-01T00:00:00Z")),
                Folder(b, "B", parentId = a, parentUpdatedAt = Instant.parse("2024-01-01T00:00:00Z")),
            ),
            orphanFolderId = UUID.randomUUID(),
        )

        val status = repository.checkFolderTreeHealth()
        assertTrue(status.treeReasons.isNotEmpty())
        assertEquals(1, status.orphanedItemCount)
        assertTrue(repository.needsFolderRepair)

        val outcome = repository.repairFolderTree()

        assertEquals(1, outcome.unfiledItemCount)
        assertTrue(outcome.treeChanges.isNotEmpty())
        assertFalse(repository.needsFolderRepair)
        assertNull(repository.getAllItems().single { it.id == itemId }.folderId)
        assertTrue(FolderTree.validate(vault().folders).isValid)
        assertFalse(repository.checkFolderTreeHealth().needsRepair)
    }

    @Test
    fun aFolderWriteIsRefusedWhileTheTreeIsBrokenAndSucceedsAfterTheRepair() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        commitRaw(
            folders = listOf(Folder(a, "A", parentId = b), Folder(b, "B", parentId = a)),
            orphanFolderId = null,
        )

        // The write-brick, demonstrated rather than asserted from the outside: an ordinary create
        // cannot land while the stored tree is cyclic.
        runCatching { repository.createFolder("New") }.also { assertTrue(it.isFailure) }
        repository.repairFolderTree()
        repository.createFolder("New")

        assertTrue(vault().folders.any { it.name == "New" })
    }

    @Test
    fun theRepairFlagClearsWhenTheVaultLocks() = runBlocking {
        commitRaw(folders = emptyList(), orphanFolderId = UUID.randomUUID())
        assertTrue(repository.checkFolderTreeHealth().needsRepair)
        lockManager.lock()
        assertFalse(repository.needsFolderRepair)
    }

    /** Writes a vault straight through the lock manager, the way the restore path does. */
    private suspend fun commitRaw(folders: List<Folder>, orphanFolderId: UUID?): UUID {
        val now = Instant.now()
        val itemId = UUID.randomUUID()
        val current = vault()
        lockManager.commit(
            current.copy(
                folders = folders,
                items = listOf(
                    VaultItem(
                        id = itemId,
                        title = "orphan",
                        folderId = orphanFolderId,
                        createdAt = now,
                        updatedAt = now,
                        content = Login(username = "u", password = "p"),
                    ),
                ),
            ),
        )
        return itemId
    }

    private fun vault(): Vault = (lockManager.lockState.value as LockState.Unlocked).vault
}

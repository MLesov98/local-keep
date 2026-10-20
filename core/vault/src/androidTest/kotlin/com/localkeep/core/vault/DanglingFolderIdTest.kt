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
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * An item pointing at a folder that is gone, at every door it can come through.
 *
 * This is the one folder defect the user cannot see: the root list shows unfiled items and each
 * folder shows its own, so an item naming a folder that no longer exists appears in neither. The
 * fix is to null it — never to refuse the write, which would lose the edit the user just made — and
 * to *say so* on the two paths where the user expressed a folder, while staying silent on the ones
 * where they did not.
 */
@RunWith(AndroidJUnit4::class)
class DanglingFolderIdTest {
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
    fun addingAnItemIntoAFolderThatIsGoneFilesItNowhereAndSaysSo() = runBlocking {
        val outcome = repository.addItem(item(folderId = UUID.randomUUID()))
        assertEquals(AddItemOutcome.FOLDER_ABSENT, outcome)
        assertNull(repository.getAllItems().single().folderId)
    }

    @Test
    fun addingAnItemIntoALiveFolderReportsNothingUnusual() = runBlocking {
        val work = repository.createFolder("Work")
        assertEquals(AddItemOutcome.ADDED, repository.addItem(item(folderId = work.id)))
        assertEquals(work.id, repository.getAllItems().single().folderId)
    }

    @Test
    fun savingAnEditIntoAFolderThatIsGoneFilesItNowhereAndSaysSo() = runBlocking {
        val work = repository.createFolder("Work")
        val item = item(folderId = work.id)
        repository.addItem(item)
        removeFolderOutright(work.id)

        val outcome = repository.updateItem(item.copy(title = "renamed", folderId = work.id))

        assertEquals(UpdateOutcome.SAVED_FOLDER_ABSENT, outcome)
        val stored = repository.getAllItems().single()
        assertEquals("renamed", stored.title, "the user's edit must survive being unfiled")
        assertNull(stored.folderId)
    }

    @Test
    fun theFolderLossIsReportedAheadOfAPasswordConflictWhenOneSaveHitsBoth() = runBlocking {
        // Both races at once: the folder was deleted from the drawer *and* the autofill
        // save-confirm wrote a newer password, while this editor was open. The folder is what the
        // user has to be told, because the password is preserved either way and an unfiled item is
        // the one they will not find again.
        val work = repository.createFolder("Work")
        val original = item(folderId = work.id)
        repository.addItem(original)
        repository.updateWith(original.id) { stored ->
            stored.copy(
                updatedAt = Instant.now().plusSeconds(1),
                content = (stored.content as Login).copy(
                    password = "rotated",
                    passwordChangedAt = Instant.now().plusSeconds(1),
                ),
            )
        }
        removeFolderOutright(work.id)

        val outcome = repository.updateItem(
            original.copy(title = "renamed", folderId = work.id),
            expectedUpdatedAt = original.updatedAt,
        )

        assertEquals(UpdateOutcome.SAVED_FOLDER_ABSENT, outcome)
        val stored = repository.getAllItems().single()
        assertEquals("renamed", stored.title)
        assertNull(stored.folderId)
        assertEquals("rotated", (stored.content as Login).password, "the newer password still wins")
    }

    @Test
    fun theOptimisticConcurrencyContractIsUntouchedByTheFolderCheck() = runBlocking {
        val original = item(folderId = null)
        repository.addItem(original)
        repository.updateWith(original.id) { stored ->
            stored.copy(
                updatedAt = Instant.now().plusSeconds(1),
                content = (stored.content as Login).copy(
                    password = "rotated",
                    passwordChangedAt = Instant.now().plusSeconds(1),
                ),
            )
        }
        val outcome = repository.updateItem(
            original.copy(title = "renamed"),
            expectedUpdatedAt = original.updatedAt,
        )
        assertEquals(UpdateOutcome.KEPT_NEWER_PASSWORD, outcome)
    }

    @Test
    fun updateWithUnfilesSilentlyBecauseItsCallersCarryNoFolderIntent() = runBlocking {
        val work = repository.createFolder("Work")
        val item = item(folderId = work.id)
        repository.addItem(item)
        // A favourite toggle, the only kind of caller this path has.
        repository.mutate { it.folders.removeAll { folder -> folder.id == work.id } }

        val updated = repository.updateWith(item.id) { it.copy(favorite = true) }

        assertNull(updated.folderId)
        assertEquals(true, updated.favorite)
    }

    private fun item(folderId: UUID?): VaultItem {
        val now = Instant.now()
        return VaultItem(
            id = UUID.randomUUID(),
            title = "item",
            folderId = folderId,
            createdAt = now,
            updatedAt = now,
            content = Login(username = "u", password = "p"),
        )
    }

    /**
     * Drops a folder from the vault without touching its items.
     *
     * `deleteFolder` used to do this — it removed the folder and unfiled what was in it, so it was
     * the natural way to set up a dangling `folderId`. Since folders gained a trash it removes
     * nothing; the folder is still there, marked. A truly absent folder now comes only from a purge
     * or from a merge, so the state is written directly rather than produced by an API that no
     * longer produces it.
     */
    private suspend fun removeFolderOutright(id: UUID) {
        val current = (lockManager.lockState.value as LockState.Unlocked).vault
        lockManager.commit(current.copy(folders = current.folders.filterNot { it.id == id }))
    }
}

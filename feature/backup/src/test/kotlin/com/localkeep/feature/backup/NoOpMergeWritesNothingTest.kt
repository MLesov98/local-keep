package com.localkeep.feature.backup

import android.content.Context
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultMutator
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.FileBlobStore
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock

/**
 * A merge that brought nothing in must not save, because a save is what takes a rolling snapshot
 * (`RollingBackupManager` snapshots on every `onSaved`) and the snapshots are a fixed set of five.
 * Merging a file whose every item the vault already held — 0 new, 0 updated — used to push out the
 * oldest restore point for a vault byte-for-byte what it was.
 *
 * The repository stand-in mirrors the real `mutate`: the block runs, and only if it returns does
 * the commit happen. A block that throws commits nothing.
 */
class NoOpMergeWritesNothingTest {

    private val t0: Instant = Instant.parse("2026-10-16T09:00:00Z")

    private fun login(title: String, updatedAt: Instant = t0) = VaultItem(
        id = UUID.nameUUIDFromBytes(title.toByteArray()),
        title = title,
        createdAt = t0,
        updatedAt = updatedAt,
        content = Login(username = "u", password = "p"),
    )

    private fun vault(items: List<VaultItem>, folders: List<Folder> = emptyList()) =
        Vault(items = items, folders = folders, createdAt = t0, modifiedAt = t0)

    private class Recorder(val repository: VaultRepository) {
        var commits = 0
        var committedItems: List<VaultItem> = emptyList()
    }

    private fun repositoryHolding(existing: Vault): Recorder {
        val repository = mock<VaultRepository>()
        val recorder = Recorder(repository)
        org.mockito.kotlin.KStubbing(repository).apply {
            onBlocking { mutate(any()) } doSuspendableAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val block = invocation.arguments[0] as (VaultMutator) -> Unit
                val mutator = VaultMutator(existing)
                block(mutator)
                recorder.commits++
                recorder.committedItems = mutator.items.toList()
                null
            }
        }
        return recorder
    }

    private fun importer() = VaultImporter(mock<Context>(), mock<FileBlobStore>(), mock<VaultLockManager>())

    @Test
    fun aFileTheVaultAlreadyHoldsCommitsNothingAndStillReportsItsCounts() = runBlocking<Unit> {
        val items = listOf(login("GitHub"), login("Netflix"))
        val recorder = repositoryHolding(vault(items))

        val result = importer().mergeInto(recorder.repository, vault(items))

        assertEquals(0, recorder.commits, "nothing changed, so nothing is saved and no restore point is taken")
        assertIs<Outcome.Success<MergeCounts>>(result)
        assertEquals(MergeCounts(added = 0, updated = 0, unchanged = 2), result.value)
    }

    @Test
    fun anOlderCopyOfWhatTheVaultHoldsIsAlsoANoOp() = runBlocking<Unit> {
        val recorder = repositoryHolding(vault(listOf(login("GitHub", updatedAt = t0.plusSeconds(60)))))

        val result = importer().mergeInto(recorder.repository, vault(listOf(login("GitHub", updatedAt = t0))))

        assertEquals(0, recorder.commits)
        assertIs<Outcome.Success<MergeCounts>>(result)
        assertEquals(MergeCounts(added = 0, updated = 0, unchanged = 1), result.value)
    }

    @Test
    fun aNewItemStillCommits() = runBlocking<Unit> {
        // The control: without it a merge that never saves anything would pass the two above.
        val recorder = repositoryHolding(vault(listOf(login("GitHub"))))

        val result = importer().mergeInto(recorder.repository, vault(listOf(login("GitHub"), login("Netflix"))))

        assertEquals(1, recorder.commits)
        assertEquals(listOf("GitHub", "Netflix"), recorder.committedItems.map { it.title })
        assertIs<Outcome.Success<MergeCounts>>(result)
        assertEquals(MergeCounts(added = 1, updated = 0, unchanged = 1), result.value)
    }

    @Test
    fun aNewerCopyStillCommits() = runBlocking<Unit> {
        val recorder = repositoryHolding(vault(listOf(login("GitHub"))))

        val result = importer().mergeInto(recorder.repository, vault(listOf(login("GitHub", updatedAt = t0.plusSeconds(60)))))

        assertEquals(1, recorder.commits)
        assertIs<Outcome.Success<MergeCounts>>(result)
        assertEquals(MergeCounts(added = 0, updated = 1, unchanged = 0), result.value)
    }

    @Test
    fun aNewFolderStillCommitsEvenWhenNoItemChanged() = runBlocking<Unit> {
        // Item counts alone would call this a no-op and drop a folder the user is merging in.
        val items = listOf(login("GitHub"))
        val recorder = repositoryHolding(vault(items))

        val result = importer().mergeInto(
            recorder.repository,
            vault(items, folders = listOf(Folder(id = UUID.randomUUID(), name = "Work"))),
        )

        assertEquals(1, recorder.commits, "the folder is a change even though 0 items were added or updated")
        assertIs<Outcome.Success<MergeCounts>>(result)
    }
}

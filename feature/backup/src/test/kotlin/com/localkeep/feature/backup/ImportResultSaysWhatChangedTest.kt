package com.localkeep.feature.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultVisibilitySnapshot
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A `.vault` import says what it did, for Replace as well as Merge, and says when the Recovery Kit
 * the user holds for the file's vault does not open this one.
 *
 * Device report C3 (2026-10-04), the "move to a new phone" journey: the tester created a vault on the
 * new phone, Replace-restored the old phone's export into it, and got the two words "Import
 * complete". Merging the same file answered with three counts (finding 4). And the old phone's
 * Recovery Kit — which the tester had printed and believed still covered them — no longer opened
 * anything: the data now lives in a vault with a different key, and nothing at restore time said
 * so (finding 2). The only hint was a Settings row reading "Not saved yet".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportResultSaysWhatChangedTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val now: Instant = Instant.parse("2026-10-04T12:00:00Z")

    private fun login(title: String) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = now,
        updatedAt = now,
        content = Login(username = "u", password = "p"),
    )

    private val visibleItems = listOf(login("GitHub"), login("Netflix"))
    private val visibleFolders = listOf(Folder(id = UUID.randomUUID(), name = "Work"))

    /** Three items in the file; what the list shows afterwards is two of them. */
    private val staged = Vault(
        items = visibleItems + login("in the trash"),
        folders = visibleFolders,
        createdAt = now,
        modifiedAt = now,
    )

    private fun repositoryShowing(items: List<VaultItem>, folders: List<Folder>): VaultRepository {
        val repository = mock<VaultRepository>()
        whenever(repository.visibilitySnapshot()).thenReturn(
            VaultVisibilitySnapshot(
                liveItems = items,
                trashItems = emptyList(),
                liveFolders = folders,
                trashedFolderRows = emptyList(),
                concealedItemIds = emptySet(),
                concealedFolderIds = emptySet(),
                directlyHiddenItemIds = emptySet(),
                directlyHiddenFolderIds = emptySet(),
                nextExpiry = null,
                purgeableTrashItemCount = 0,
            ),
        )
        return repository
    }

    private suspend fun stagedViewModel(
        voidsRecoveryKit: Boolean,
        repository: VaultRepository = repositoryShowing(visibleItems, visibleFolders),
    ): BackupSettingsViewModel {
        val importer = mock<VaultImporter>()
        whenever(importer.readVault(any(), any())).thenReturn(
            Outcome.Success(
                VaultImporter.ImportResult(staged, missingBlobRefCount = 0, voidsRecoveryKit = voidsRecoveryKit),
            ),
        )
        whenever(importer.replaceWith(any(), any())).thenReturn(Outcome.Success(Unit))
        whenever(importer.mergeInto(any(), any())).thenReturn(
            Outcome.Success(MergeCounts(added = 3, updated = 0, unchanged = 0)),
        )
        val viewModel = BackupSettingsViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            vaultExporter = mock(),
            vaultImporter = importer,
            rollingBackupManager = mock(),
            vaultLockManager = mock<VaultLockManager>(),
            vaultRepository = repository,
        )
        viewModel.stageImport(mock<Uri>(), "file password".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `replace says how many items and folders the vault now holds`() = runTest(dispatcher) {
        val viewModel = stagedViewModel(voidsRecoveryKit = false)

        viewModel.confirmImport(ImportMode.REPLACE)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            BackupMessage.ImportComplete(
                BackupMessage.Res(R.string.backup_status_replace_complete, listOf(2, 1)),
                recoveryKitVoided = false,
            ),
            viewModel.state.value.status,
            "Replace must count what the vault list will show — two items, one folder — not the " +
                "file's three records and not nothing at all",
        )
    }

    @Test
    fun `replace from a vault whose kit cannot open this one says the old kit no longer works`() =
        runTest(dispatcher) {
            val viewModel = stagedViewModel(voidsRecoveryKit = true)

            viewModel.confirmImport(ImportMode.REPLACE)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                true,
                (viewModel.state.value.status as? BackupMessage.ImportComplete)?.recoveryKitVoided,
                "the user's printed kit opened the file's vault and does not open this one",
            )
        }

    @Test
    fun `merge from such a vault says it too, and keeps its own counts`() = runTest(dispatcher) {
        val viewModel = stagedViewModel(voidsRecoveryKit = true)

        viewModel.confirmImport(ImportMode.MERGE)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            BackupMessage.ImportComplete(
                BackupMessage.Res(R.string.backup_status_merge_complete, listOf(3, 0, 0)),
                recoveryKitVoided = true,
            ),
            viewModel.state.value.status,
        )
    }

    @Test
    fun `a backup of this same vault says nothing about the kit`() = runTest(dispatcher) {
        // The control: restoring your own vault's backup leaves its kit exactly as it was, and a
        // sentence telling that user their kit is dead would send them to revoke a working one.
        val viewModel = stagedViewModel(voidsRecoveryKit = false)

        viewModel.confirmImport(ImportMode.MERGE)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            false,
            (viewModel.state.value.status as? BackupMessage.ImportComplete)?.recoveryKitVoided,
        )
    }
}

package com.localkeep.feature.files

import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultVisibilitySnapshot
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for 15-1: `deleteAllMetadata` wrote back `stateWithSource(snapshot, result)`,
 * where `snapshot` was `_state.value` captured *before* its multi-second decrypt/strip/re-encrypt
 * IO. Any edit made while that IO ran — a typed title, a chosen folder — was silently reverted the
 * moment the cleanup finished, because the write-back started from the stale copy rather than
 * live state. `importFromPicker`, `onCaptured` and `save()` all re-read `_state.value` after
 * their own suspension for exactly this reason; this was the one path that did not.
 *
 * `photoPickerImport.replaceStagedFile`'s mocked answer changes the title mid-flight — on the real
 * IO thread the `withContext(Dispatchers.IO)` block actually dispatches to, mirroring the window in
 * which a user could keep typing while cleanup runs. `VaultLockManager` cannot be constructed for
 * real under Robolectric, so it is mocked (see `AttachmentPickerViewModelRevealTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataCleanupStaleSnapshotTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an edit made while metadata cleanup runs survives the write-back`() = runTest(dispatcher) {
        val itemId = UUID.randomUUID()
        val now = Instant.EPOCH
        val item = VaultItem(
            id = itemId,
            title = "Original title",
            createdAt = now,
            updatedAt = now,
            content = File(filename = "photo.jpg", mime = "image/jpeg", blobRef = "orig-blob"),
        )
        val repository = mock<VaultRepository>()
        whenever(repository.visibilitySnapshot()).thenReturn(
            VaultVisibilitySnapshot(
                liveItems = listOf(item),
                trashItems = emptyList(),
                liveFolders = emptyList<Folder>(),
                trashedFolderRows = emptyList(),
                concealedItemIds = emptySet(),
                concealedFolderIds = emptySet(),
                directlyHiddenItemIds = emptySet(),
                directlyHiddenFolderIds = emptySet(),
                nextExpiry = null,
                purgeableTrashItemCount = 0,
            ),
        )
        // FileItemEditViewModel's `init` collects this now; an unstubbed final `val` returns null
        // and the collector NPEs on construction. `Locked` is the flow's own seed and is ignored.
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow<VaultPresentationState>(VaultPresentationState.Locked))

        val lockManager = mock<VaultLockManager>()
        val lockState = MutableStateFlow<LockState>(
            LockState.Unlocked(dek = ByteArray(32), vault = Vault(createdAt = now, modifiedAt = now)),
        )
        whenever(lockManager.lockState).thenReturn(lockState)
        whenever(lockManager.sessionSnapshotOrNull()).thenReturn(
            VaultLockManager.SessionSnapshot(vault = Vault(createdAt = now, modifiedAt = now), dek = ByteArray(32)),
        )

        val fileBlobStore = mock<FileBlobStore>()
        whenever(fileBlobStore.decryptToBytes(any(), any())).thenReturn(ByteArray(8))

        val photoPickerImport = mock<PhotoPickerImport>()
        val cleaned = StagedFileSource(
            blobRef = "clean-blob",
            thumbnailRef = null,
            filename = "photo.jpg",
            mime = "image/jpeg",
            sizeBytes = 8L,
            generationId = UUID.randomUUID(),
            metadataState = MetadataCleanupState.REMOVED,
            sourceUri = null,
            capturedInApp = false,
            removedFromGallery = false,
            pendingGalleryDeleteUri = null,
        )

        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))

        val viewModel = FileItemEditViewModel(
            repository = repository,
            lockManager = lockManager,
            photoPickerImport = photoPickerImport,
            fileBlobStore = fileBlobStore,
            entitlement = entitlement,
            savedStateHandle = SavedStateHandle(mapOf(ROUTE_FILE_ITEM_ID to itemId.toString())),
        )
        assertEquals("Original title", viewModel.state.value.title)

        // Fires on the real IO thread the metadata-cleanup withContext dispatches to — the window
        // in which a user could rename the file while cleanup is still running. Returns the raw
        // success value, not `Result.success(cleaned)`: a suspend fun returning `Result<T>` uses
        // Kotlin's special unwrapped calling convention, and `thenAnswer` does not get that
        // unwrapping the way `thenReturn` does — wrapping here reproduces a ClassCastException
        // ("kotlin.Result cannot be cast to StagedFileSource") that the outer catch swallowed.
        whenever(photoPickerImport.replaceStagedFile(any(), any(), any())).thenAnswer {
            viewModel.onTitleChanged("Renamed mid-cleanup")
            cleaned
        }

        viewModel.deleteAllMetadata()

        var attempts = 0
        while (viewModel.state.value.isWorking && attempts < 50) {
            dispatcher.scheduler.advanceUntilIdle()
            if (viewModel.state.value.isWorking) Thread.sleep(10)
            attempts++
        }

        assertTrue(!viewModel.state.value.isWorking, "cleanup must have finished")
        assertEquals(
            "Renamed mid-cleanup",
            viewModel.state.value.title,
            "an edit made during cleanup must not be reverted by the pre-IO snapshot",
        )
        assertEquals(MetadataCleanupState.REMOVED, viewModel.state.value.metadataCleanupState)
    }
}

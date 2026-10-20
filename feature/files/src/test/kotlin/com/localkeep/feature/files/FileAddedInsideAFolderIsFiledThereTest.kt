package com.localkeep.feature.files

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.nav.PendingFolderSelection
import com.localkeep.core.vault.AddItemOutcome
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultStorageInventory
import com.localkeep.core.vault.VaultVisibilitySnapshot
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A file added from inside a folder is filed in that folder.
 *
 * Device evidence, 2026-10-04 on API 36 (foss release): Vault › "My Folder" (the FAB reads "Add to
 * My Folder") › File › pick › Save item — the editor showed FOLDER "None" and the file landed at
 * the top level. Secure note, 2FA, GPS, Recovery phrase and New folder all started in the folder.
 *
 * The add flow hands the browsed folder over through [PendingFolderSelection], and the file path
 * was cut out of it on 2026-08-12 because the files editor "has no folder field at all" — so a
 * pending it could not consume would have filed whatever the user added next. The field arrived on
 * 2026-08-19 and the hand-over never followed it. These pin the editor's half: it takes the
 * pending, starts in that folder, and saves the file there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileAddedInsideAFolderIsFiledThereTest {

    private val dispatcher = StandardTestDispatcher()

    private val folderId: UUID = UUID.randomUUID()
    private val folder = Folder(id = folderId, name = "My Folder")

    private lateinit var repository: VaultRepository
    private lateinit var lockManager: VaultLockManager
    private lateinit var photoPickerImport: PhotoPickerImport

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        LkNotices.clear()
        PendingFolderSelection.clear()
        repository = mock()
        lockManager = mock()
        photoPickerImport = mock()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow<VaultPresentationState>(VaultPresentationState.Locked))
        whenever(repository.visibilitySnapshot()).thenAnswer { snapshot() }
        whenever(repository.getCompleteStorageInventory())
            .thenAnswer { VaultStorageInventory(allItems = emptyList(), allFolders = listOf(folder)) }
        whenever(lockManager.lockState).thenReturn(
            MutableStateFlow<LockState>(LockState.Unlocked(dek = ByteArray(32), vault = vault())),
        )
        whenever(lockManager.sessionSnapshotOrNull())
            .thenReturn(VaultLockManager.SessionSnapshot(vault = vault(), dek = ByteArray(32)))
    }

    @After
    fun tearDown() {
        PendingFolderSelection.clear()
        LkNotices.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `a new file opened from inside a folder starts in that folder`() {
        PendingFolderSelection.request(folderId)
        val viewModel = newFileViewModel()

        viewModel.seedFolderFromPending()

        assertEquals(
            folderId,
            viewModel.state.value.folderId,
            "the FAB said \"Add to My Folder\"; the editor must open filed there, not at None",
        )
        assertNull(PendingFolderSelection.take(), "the request is consumed, so it cannot file the next add")
        assertFalse(
            viewModel.isDirty(),
            "a starting folder is not work — backing out of an untouched new file must not ask " +
                "to discard it",
        )
    }

    @Test
    fun `the saved file lands in that folder`() = runTest(dispatcher) {
        PendingFolderSelection.request(folderId)
        val viewModel = newFileViewModel()
        viewModel.seedFolderFromPending()
        importOneFile(viewModel)
        whenever(repository.addItem(any())).thenReturn(AddItemOutcome.ADDED)

        viewModel.save()
        advanceUntilIdle()

        val saved = argumentCaptor<VaultItem>()
        verify(repository).addItem(saved.capture())
        assertEquals(folderId, saved.firstValue.folderId, "the file was saved at the top level")
    }

    @Test
    fun `without a request the new file starts unfiled`() {
        // The control: the root's FAB, and every route that is not the add flow, sets nothing.
        val viewModel = newFileViewModel()

        viewModel.seedFolderFromPending()

        assertNull(viewModel.state.value.folderId)
    }

    @Test
    fun `a requested folder the picker does not offer is ignored and consumed`() {
        // Trashed or concealed between the tap and the editor opening: the picker cannot show it,
        // and a selection the user cannot see is indistinguishable from a wrong one.
        PendingFolderSelection.request(UUID.randomUUID())
        val viewModel = newFileViewModel()

        viewModel.seedFolderFromPending()

        assertNull(viewModel.state.value.folderId)
        assertNull(PendingFolderSelection.take())
    }

    // --- Fixtures -------------------------------------------------------------------------------

    private fun newFileViewModel(): FileItemEditViewModel {
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(true))
        return FileItemEditViewModel(
            repository = repository,
            lockManager = lockManager,
            photoPickerImport = photoPickerImport,
            fileBlobStore = mock(),
            entitlement = entitlement,
            savedStateHandle = SavedStateHandle(),
        )
    }

    private suspend fun importOneFile(viewModel: FileItemEditViewModel) {
        val staged = StagedFileSource(
            blobRef = "blob-new",
            thumbnailRef = null,
            filename = "photo.png",
            mime = "image/png",
            sizeBytes = 8L,
            generationId = UUID.randomUUID(),
            metadataState = MetadataCleanupState.PENDING,
            sourceUri = null,
            capturedInApp = false,
            removedFromGallery = false,
            pendingGalleryDeleteUri = null,
        )
        whenever(photoPickerImport.stageFile(any(), any(), any())).thenReturn(Result.success(staged))
        whenever(photoPickerImport.commitStagedFile(any(), any(), anyOrNull()))
            .thenReturn(Result.success(File(filename = "photo.png", mime = "image/png", blobRef = "blob-new")))
        viewModel.importFromPicker(Uri.parse("content://media/external/images/1"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("blob-new", viewModel.state.value.blobRef, "control: the import staged a file")
    }

    private fun vault() = Vault(
        createdAt = Instant.EPOCH,
        modifiedAt = Instant.EPOCH,
        items = emptyList(),
        folders = listOf(folder),
    )

    private fun snapshot() = VaultVisibilitySnapshot(
        liveItems = emptyList(),
        trashItems = emptyList(),
        liveFolders = listOf(folder),
        trashedFolderRows = emptyList(),
        concealedItemIds = emptySet(),
        concealedFolderIds = emptySet(),
        directlyHiddenItemIds = emptySet(),
        directlyHiddenFolderIds = emptySet(),
        nextExpiry = null,
        purgeableTrashItemCount = 0,
    )
}

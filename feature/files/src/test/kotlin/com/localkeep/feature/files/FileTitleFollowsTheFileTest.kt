package com.localkeep.feature.files

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.Vault
import com.localkeep.core.ui.LkNotices
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The name the editor suggests is the name of the file it is holding now.
 *
 * Device pass 2026-10-04 (C1 finding 7): From documents > `empty.csv`, then From gallery > `18.mp4`,
 * then Save — and "Name it" offered `empty.csv`. The first import wrote its filename into the title
 * as a suggestion, and every later one kept it because the title was no longer blank, so the sheet
 * proposed naming a video after the spreadsheet it replaced. A title the user actually typed is
 * theirs and stays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileTitleFollowsTheFileTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: VaultRepository
    private lateinit var lockManager: VaultLockManager
    private lateinit var photoPickerImport: PhotoPickerImport

    private val csv = Uri.parse("content://com.android.providers.downloads.documents/document/1")
    private val video = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/18")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        LkNotices.clear()
        repository = mock()
        lockManager = mock()
        photoPickerImport = mock()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow<VaultPresentationState>(VaultPresentationState.Locked))
        whenever(repository.visibilitySnapshot()).thenAnswer { snapshot() }
        whenever(repository.getCompleteStorageInventory())
            .thenAnswer { VaultStorageInventory(allItems = emptyList(), allFolders = emptyList()) }
        whenever(lockManager.lockState).thenReturn(
            MutableStateFlow<LockState>(LockState.Unlocked(dek = ByteArray(32), vault = vault())),
        )
        whenever(lockManager.sessionSnapshotOrNull())
            .thenAnswer { VaultLockManager.SessionSnapshot(vault = vault(), dek = ByteArray(32)) }
    }

    @After
    fun tearDown() {
        LkNotices.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `replacing a document with a gallery video suggests the video's name`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        stage(csv, "empty.csv", "text/csv")
        stage(video, "18.mp4", "video/mp4")

        viewModel.importFromPicker(csv, fromDocumentPicker = true)
        advanceUntilIdle()
        viewModel.importFromPicker(video)
        advanceUntilIdle()

        assertEquals(
            "18.mp4",
            namingSheetSeed(viewModel),
            "Name it was pre-filled with the name of the file this one replaced",
        )
    }

    @Test
    fun `a capture after a document suggests the capture's name`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        stage(csv, "empty.csv", "text/csv")
        whenever(photoPickerImport.stageCapturedFile(any(), any()))
            .thenReturn(Result.success(source("capture-1.jpg", "image/jpeg")))

        viewModel.importFromPicker(csv, fromDocumentPicker = true)
        advanceUntilIdle()
        viewModel.showCapture()
        viewModel.onCaptured(ByteArray(4))
        advanceUntilIdle()

        assertEquals("capture-1.jpg", namingSheetSeed(viewModel))
    }

    /** The control: only a suggestion follows the file; a title the user typed is left alone. */
    @Test
    fun `a title the user typed survives replacing the file`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        stage(csv, "empty.csv", "text/csv")
        stage(video, "18.mp4", "video/mp4")

        viewModel.importFromPicker(csv, fromDocumentPicker = true)
        advanceUntilIdle()
        viewModel.onTitleChanged("Tax return")
        viewModel.importFromPicker(video)
        advanceUntilIdle()

        assertEquals("Tax return", namingSheetSeed(viewModel))
    }

    /** What `FileItemEditScreen`'s naming sheet seeds its field with — `state.title.ifBlank { state.filename }`. */
    private fun namingSheetSeed(viewModel: FileItemEditViewModel): String =
        viewModel.state.value.let { it.title.ifBlank { it.filename } }

    private suspend fun stage(uri: Uri, filename: String, mime: String) {
        whenever(photoPickerImport.stageFile(eq(uri), any(), any())).thenReturn(Result.success(source(filename, mime)))
    }

    private fun source(filename: String, mime: String) = StagedFileSource(
        blobRef = "blob-$filename",
        thumbnailRef = null,
        filename = filename,
        mime = mime,
        sizeBytes = 8L,
        generationId = UUID.randomUUID(),
        metadataState = MetadataCleanupState.NOT_APPLICABLE,
        sourceUri = null,
        capturedInApp = false,
        removedFromGallery = false,
        pendingGalleryDeleteUri = null,
    )

    private fun buildViewModel(): FileItemEditViewModel {
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

    private fun vault() = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH)

    private fun snapshot() = VaultVisibilitySnapshot(
        liveItems = emptyList(),
        trashItems = emptyList(),
        liveFolders = emptyList(),
        trashedFolderRows = emptyList(),
        concealedItemIds = emptySet(),
        concealedFolderIds = emptySet(),
        directlyHiddenItemIds = emptySet(),
        directlyHiddenFolderIds = emptySet(),
        nextExpiry = null,
        purgeableTrashItemCount = 0,
    )
}

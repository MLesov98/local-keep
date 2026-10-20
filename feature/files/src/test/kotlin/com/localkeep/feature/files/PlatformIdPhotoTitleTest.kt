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
 * A photo the picker reports under its media-store id is titled "Photo 16 Oct 2026", not "1000000206.jpg".
 *
 * The system photo picker hands back the provider's row id as the display name, so the title field
 * a new File opened with was a ten-digit number and a screenshot's `91.png` was no better. Only a
 * name whose stem is all digits is replaced: `IMG_1234.jpg` and `Passport.png` say something. The
 * date-formatted title is resolved by the screen at composition and passed in, because a ViewModel
 * does not hold display strings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformIdPhotoTitleTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var photoPickerImport: PhotoPickerImport
    private lateinit var repository: VaultRepository
    private lateinit var lockManager: VaultLockManager

    private val first = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1")
    private val second = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/2")
    private val generated = "Photo 16 Oct 2026"

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
    fun `an image named by its media id gets the generated title`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        stage(first, "1000000206.jpg", "image/jpeg")

        viewModel.importFromPicker(first, generatedPhotoTitle = generated)
        advanceUntilIdle()

        assertEquals(generated, viewModel.state.value.title)
    }

    @Test
    fun `a named image keeps its filename as the title`() = runTest(dispatcher) {
        // The control: only an all-digits stem is a platform id.
        val viewModel = buildViewModel()
        stage(first, "IMG_1234.jpg", "image/jpeg")

        viewModel.importFromPicker(first, generatedPhotoTitle = generated)
        advanceUntilIdle()

        assertEquals("IMG_1234.jpg", viewModel.state.value.title)
    }

    @Test
    fun `a digits-only video keeps its filename, because the title says photo`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        stage(first, "18.mp4", "video/mp4")

        viewModel.importFromPicker(first, generatedPhotoTitle = generated)
        advanceUntilIdle()

        assertEquals("18.mp4", viewModel.state.value.title)
    }

    @Test
    fun `a title the user typed survives a digits-only image`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        stage(first, "IMG_1234.jpg", "image/jpeg")
        stage(second, "1000000206.jpg", "image/jpeg")

        viewModel.importFromPicker(first, generatedPhotoTitle = generated)
        advanceUntilIdle()
        viewModel.onTitleChanged("Passport")
        viewModel.importFromPicker(second, generatedPhotoTitle = generated)
        advanceUntilIdle()

        assertEquals("Passport", viewModel.state.value.title)
    }

    @Test
    fun `a generated title is a suggestion and follows the next file`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        stage(first, "1000000206.jpg", "image/jpeg")
        stage(second, "IMG_1234.jpg", "image/jpeg")

        viewModel.importFromPicker(first, generatedPhotoTitle = generated)
        advanceUntilIdle()
        viewModel.importFromPicker(second, generatedPhotoTitle = generated)
        advanceUntilIdle()

        assertEquals("IMG_1234.jpg", viewModel.state.value.title)
    }

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

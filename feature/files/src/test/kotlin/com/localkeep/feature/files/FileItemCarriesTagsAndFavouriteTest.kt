package com.localkeep.feature.files

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.LkNotices
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
 * 4C-11, 2026-09-20: a File was the one item type with no route to a tag anywhere — not in this
 * editor, not in the vault list's row menu — while the list's own search hint promises it searches
 * "tags, folders". The consequence measured on the device was that a vault organised by tag could
 * not reach its own scans and photographs with the handle its owner had given everything else.
 *
 * What makes a File findable by tag is [VaultItem.tags]: `VaultRepository.searchHaystack` yields
 * every item's tags for the index regardless of content type, so carrying one is the whole of what
 * was missing. These tests therefore pin the field arriving on the written [VaultItem], on both
 * write paths, rather than re-testing the index.
 *
 * The favourite flag rides along for the same reason 4A-F5/2B-6 put it on the item editor the same
 * day: it was settable only from the vault list's row menu, so a file already open in front of the
 * user could not be favourited without leaving it.
 *
 * `VaultRepository`/`VaultLockManager` are mocked for the reason `FileItemEditSaveOutcomeTest`
 * gives — their real construction chains open AndroidKeyStore and the vault file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileItemCarriesTagsAndFavouriteTest {

    private val dispatcher = StandardTestDispatcher()

    private val folderId: UUID = UUID.randomUUID()
    private val folder = Folder(id = folderId, name = "Passports")

    private val existingId: UUID = UUID.randomUUID()
    private val existing = VaultItem(
        id = existingId,
        title = "Passport scan",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        folderId = folderId,
        tags = listOf("travel"),
        favorite = true,
        content = File(filename = "passport.jpg", mime = "image/jpeg", blobRef = "blob-passport"),
    )

    private lateinit var repository: VaultRepository
    private lateinit var lockManager: VaultLockManager
    private lateinit var photoPickerImport: PhotoPickerImport

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
            .thenAnswer { VaultStorageInventory(allItems = listOf(existing), allFolders = listOf(folder)) }
        whenever(lockManager.lockState).thenReturn(
            MutableStateFlow<LockState>(LockState.Unlocked(dek = ByteArray(32), vault = vault())),
        )
        whenever(lockManager.sessionSnapshotOrNull())
            .thenReturn(VaultLockManager.SessionSnapshot(vault = vault(), dek = ByteArray(32)))
    }

    @After
    fun tearDown() {
        LkNotices.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `a new file is written with the tags and the favourite flag it was given`() =
        runTest(dispatcher) {
            val viewModel = newFileViewModelWithImport()
            viewModel.onTagInputChanged("travel")
            viewModel.addTag()
            viewModel.onTagInputChanged("2026")
            viewModel.addTag()
            viewModel.onFavoriteChanged(true)
            whenever(repository.addItem(any())).thenReturn(AddItemOutcome.ADDED)

            viewModel.save()
            advanceUntilIdle()

            val written = argumentCaptor<VaultItem>()
            verify(repository).addItem(written.capture())
            assertEquals(
                listOf("travel", "2026"),
                written.firstValue.tags,
                "the tags are what make a File reachable from search; dropping them here is the defect",
            )
            assertTrue(written.firstValue.favorite)
        }

    @Test
    fun `an existing file loads its stored tags and writes them back`() = runTest(dispatcher) {
        val viewModel = existingFileViewModel()
        advanceUntilIdle()

        assertEquals(
            listOf("travel"),
            viewModel.state.value.tags,
            "a stored tag the editor cannot see is a tag the next save silently drops",
        )
        assertTrue(viewModel.state.value.favorite)

        viewModel.onTagInputChanged("passport")
        viewModel.addTag()
        whenever(repository.updateWith(any(), any())).thenReturn(existing)

        viewModel.save()
        advanceUntilIdle()

        val mutate = argumentCaptor<(VaultItem) -> VaultItem>()
        verify(repository).updateWith(any(), mutate.capture())
        val stored = mutate.firstValue(existing)
        assertEquals(listOf("travel", "passport"), stored.tags)
        assertTrue(stored.favorite)
    }

    @Test
    fun `a duplicate tag is refused out loud, case-insensitively`() = runTest(dispatcher) {
        // The item editor's rule, and the reason it has one: "Work" and "work" are one tag, and a
        // silent refusal is the same signal to the user as a broken button.
        val viewModel = newFileViewModelWithImport()
        viewModel.onTagInputChanged("Travel")
        viewModel.addTag()
        viewModel.onTagInputChanged("travel")
        viewModel.addTag()

        assertEquals(listOf("Travel"), viewModel.state.value.tags)
        assertEquals("", viewModel.state.value.tagInput)
        assertEquals(R.string.files_edit_error_already_tagged, viewModel.state.value.error)
    }

    @Test
    fun `a removed tag is gone and a blank one is never added`() = runTest(dispatcher) {
        val viewModel = newFileViewModelWithImport()
        viewModel.onTagInputChanged("travel")
        viewModel.addTag()
        viewModel.onTagInputChanged("   ")
        viewModel.addTag()
        assertEquals(listOf("travel"), viewModel.state.value.tags)

        viewModel.removeTag("travel")
        assertEquals(emptyList(), viewModel.state.value.tags)
    }

    @Test
    fun `a tag and a favourite are unsaved work, a half-typed tag is not`() {
        // The discard prompt compares two `editableOnly` snapshots. A tag that did not count would
        // be lost on Back with no warning; a half-typed word in the input that *did* count would
        // arm the prompt over nothing, which is the folder-picker trap `editableOnly` already
        // documents.
        val pristine = FileItemEditUiState(itemId = existingId)
        assertNotEquals(pristine.editableOnly(), pristine.copy(tags = listOf("travel")).editableOnly())
        assertNotEquals(pristine.editableOnly(), pristine.copy(favorite = true).editableOnly())
        assertEquals(pristine.editableOnly(), pristine.copy(tagInput = "trav").editableOnly())
    }

    /** The control: a file saved without touching the section carries no tag and is not favourite. */
    @Test
    fun `an untouched new file is written with no tags and no favourite`() = runTest(dispatcher) {
        val viewModel = newFileViewModelWithImport()
        whenever(repository.addItem(any())).thenReturn(AddItemOutcome.ADDED)

        viewModel.save()
        advanceUntilIdle()

        val written = argumentCaptor<VaultItem>()
        verify(repository).addItem(written.capture())
        assertEquals(emptyList(), written.firstValue.tags)
        assertFalse(written.firstValue.favorite)
    }

    // --- Fixtures -------------------------------------------------------------------------------

    private suspend fun newFileViewModelWithImport(): FileItemEditViewModel {
        val staged = stagedSource()
        whenever(photoPickerImport.stageFile(any(), any(), any())).thenReturn(Result.success(staged))
        whenever(photoPickerImport.commitStagedFile(any(), any(), anyOrNull()))
            .thenReturn(Result.success(File(filename = "passport.jpg", mime = "image/jpeg", blobRef = "blob-new")))
        val viewModel = buildViewModel(SavedStateHandle())
        viewModel.importFromPicker(Uri.parse("content://media/external/images/1"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("blob-new", viewModel.state.value.blobRef, "control: the import staged a file")
        return viewModel
    }

    private fun existingFileViewModel(): FileItemEditViewModel {
        whenever(photoPickerImport.commitStagedFile(any(), any(), anyOrNull()))
            .thenReturn(Result.success(existing.content as File))
        return buildViewModel(SavedStateHandle(mapOf(ROUTE_FILE_ITEM_ID to existingId.toString())))
    }

    private fun buildViewModel(savedStateHandle: SavedStateHandle): FileItemEditViewModel {
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(true))
        return FileItemEditViewModel(
            repository = repository,
            lockManager = lockManager,
            photoPickerImport = photoPickerImport,
            fileBlobStore = mock(),
            entitlement = entitlement,
            savedStateHandle = savedStateHandle,
        )
    }

    private fun stagedSource() = StagedFileSource(
        blobRef = "blob-new",
        thumbnailRef = null,
        filename = "passport.jpg",
        mime = "image/jpeg",
        sizeBytes = 8L,
        generationId = UUID.randomUUID(),
        metadataState = MetadataCleanupState.PENDING,
        sourceUri = null,
        capturedInApp = false,
        removedFromGallery = false,
        pendingGalleryDeleteUri = null,
    )

    private fun vault() = Vault(
        createdAt = Instant.EPOCH,
        modifiedAt = Instant.EPOCH,
        items = listOf(existing),
        folders = listOf(folder),
    )

    private fun snapshot() = VaultVisibilitySnapshot(
        liveItems = listOf(existing),
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

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the file editor's save says happened, and what it leaves on disk when it is interrupted.
 *
 * **5-2.** `save()` threw away the [AddItemOutcome] `addItem` returns and used the outcome-less
 * `updateWith` on the edit path, so a folder deleted from the drawer between the pick and the Save
 * tap filed the file at the vault root and the screen said "Item saved" — the one outcome the user
 * cannot deduce from the list they land back on. `ItemEditViewModel` has mapped the identical
 * outcome to a notice of its own for a while; this screen draws the same folder field on new and
 * existing files alike, so it carries the same intent and owed the same sentence. **Revert either
 * half of the `folderAbsent` branch in `save()` — the `== AddItemOutcome.FOLDER_ABSENT` comparison,
 * or the `folderId != null && filed.folderId == null` line under it — and the matching test below
 * goes red**: the notice comes back as `files_notice_item_saved` and the form keeps naming a folder
 * the vault no longer has.
 *
 * **23-1 (the residual).** `stagedSourceIsCommitted = true` ran *after* the vault write returned,
 * and `VaultLockManager.commit`'s critical section has no suspension points — so a Discard tap
 * landing while that body executed let the write complete and then found the flag still false.
 * `discardUncommittedSource()` deleted the blob and thumbnail of an item that was by then in the
 * vault, leaving a readable record pointing at nothing. **Move `stagedSourceIsCommitted = true`
 * back below the `repository.addItem` call and the last test goes red.**
 *
 * `VaultRepository`/`VaultLockManager` are mocked for the reason `MetadataCleanupStaleSnapshotTest`
 * gives — their real construction chains open AndroidKeyStore and the vault file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileItemEditSaveOutcomeTest {

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

    // --- The new-item path, which returns an outcome -------------------------------------------

    @Test
    fun `a new file filed into a folder that was deleted mid-save is told it was filed nowhere`() =
        runTest(dispatcher) {
            val viewModel = newFileViewModelWithImport()
            viewModel.onFolderSelected(folderId)

            // What the repository does when the folder named by the item is gone by the time the
            // write lands: it stores the item unfiled and says so.
            whenever(repository.addItem(any())).thenReturn(AddItemOutcome.FOLDER_ABSENT)

            viewModel.save()
            advanceUntilIdle()

            assertEquals(
                LkNotices.Message.Res(R.string.files_notice_folder_absent),
                LkNotices.current.value?.message,
                "\"Item saved\" is true and useless here — it hides the only fact the user cannot " +
                    "see for themselves, which is that the file is not in the folder they chose",
            )
            assertNull(
                viewModel.state.value.folderId,
                "and the form must stop naming a folder the vault does not have",
            )
            assertTrue(viewModel.state.value.saved, "the file itself is in the vault either way")
        }

    @Test
    fun `an ordinary new save still says Item saved and keeps its folder`() = runTest(dispatcher) {
        // The control. A branch written slightly too wide would report "filed nowhere" on every
        // save, and a test that only asserts the notice for the race would pass with it broken.
        val viewModel = newFileViewModelWithImport()
        viewModel.onFolderSelected(folderId)
        whenever(repository.addItem(any())).thenReturn(AddItemOutcome.ADDED)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(
            LkNotices.Message.Res(R.string.files_notice_item_saved),
            LkNotices.current.value?.message,
        )
        assertEquals(folderId, viewModel.state.value.folderId)
    }

    // --- The edit path, which returns the item it stored ---------------------------------------

    @Test
    fun `an edited file whose folder was deleted mid-save is told it was filed nowhere`() =
        runTest(dispatcher) {
            val viewModel = existingFileViewModel()
            advanceUntilIdle()
            assertEquals(folderId, viewModel.state.value.folderId, "control: it is filed to begin with")

            // `updateWith` nulls a folderId that no longer names a live folder and returns what it
            // actually stored. Before the fix that return value was discarded.
            whenever(repository.updateWith(any(), any())).thenReturn(existing.copy(folderId = null))

            viewModel.save()
            advanceUntilIdle()

            assertEquals(
                LkNotices.Message.Res(R.string.files_notice_folder_absent),
                LkNotices.current.value?.message,
                "the edit path was silent here too, on the strength of a repository KDoc claiming " +
                    "the files editor carries no folder intent — it draws a folder picker",
            )
            assertNull(viewModel.state.value.folderId)
        }

    @Test
    fun `an edit that keeps its folder still says Item saved`() = runTest(dispatcher) {
        val viewModel = existingFileViewModel()
        advanceUntilIdle()
        whenever(repository.updateWith(any(), any())).thenReturn(existing)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(
            LkNotices.Message.Res(R.string.files_notice_item_saved),
            LkNotices.current.value?.message,
        )
        assertEquals(folderId, viewModel.state.value.folderId)
    }

    // --- 23-1: the ordering of the committed flag against the write it describes ----------------

    @Test
    fun `a discard landing inside the vault write does not delete the blob that write committed`() =
        runTest(dispatcher) {
            val viewModel = newFileViewModelWithImport()

            // The window the fix is about. `VaultLockManager.commit` runs a whole-file read, an
            // AEAD seal, a write and an fd.sync() with nothing that yields, so a Back-then-Discard
            // taken while it executes cannot interrupt it — the item lands. Firing the discard from
            // inside the stubbed write is the JVM-side stand-in for that moment.
            whenever(repository.addItem(any())).thenAnswer {
                viewModel.discardUncommittedSource()
                AddItemOutcome.ADDED
            }

            viewModel.save()
            advanceUntilIdle()

            verify(photoPickerImport, never()).discardStagedFile(any())
            assertTrue(viewModel.state.value.saved)
        }

    @Test
    fun `discarding a staged import that was never written still deletes its blob`() =
        runTest(dispatcher) {
            // The control for the ordering above: raising the flag early must not turn
            // `discardUncommittedSource` into a no-op for the case it exists for — an import the
            // user abandons before pressing Save at all, whose encrypted bytes nothing will ever
            // reference.
            val viewModel = newFileViewModelWithImport()

            viewModel.discardUncommittedSource()

            verify(photoPickerImport).discardStagedFile(any())
        }

    // --- Fixtures -------------------------------------------------------------------------------

    /** A `file/new` editor that has imported one file, so `save()` has something to commit. */
    private suspend fun newFileViewModelWithImport(): FileItemEditViewModel {
        val staged = stagedSource()
        whenever(photoPickerImport.stageFile(any(), any(), any())).thenReturn(Result.success(staged))
        whenever(photoPickerImport.commitStagedFile(any(), any(), anyOrNull()))
            .thenReturn(Result.success(File(filename = "passport.jpg", mime = "image/jpeg", blobRef = "blob-new")))
        val viewModel = buildViewModel(SavedStateHandle())
        viewModel.importFromPicker(Uri.parse("content://media/external/images/1"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("blob-new", viewModel.state.value.blobRef, "control: the import staged a file")
        assertTrue(viewModel.state.value.isNew)
        return viewModel
    }

    /** A `file/{itemId}/settings` editor over [existing], which loads filed into [folder]. */
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

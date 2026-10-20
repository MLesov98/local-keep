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
 * 4C-11's second half, 2026-09-21: a File can carry the user's own note.
 *
 * Tags and the favourite flag (the first half, [FileItemCarriesTagsAndFavouriteTest]) live on
 * [VaultItem] and needed no format change. A note does not exist anywhere on a File, so this is a
 * vault-format change — `File.notes`, protobuf field 8, schema 7 to 8, with the migration and the
 * downgrade refusal pinned by `FileNotesSchemaTest` in `:core:model` and the search arm by
 * `SearchIndexesFileNotesTest` in `:core:vault`.
 *
 * What is pinned *here* is the editor: that the field arrives on the written item on both write
 * paths, that a stored note is loaded rather than silently dropped by the next save, and that it
 * counts as unsaved work.
 *
 * `VaultRepository`/`VaultLockManager` are mocked for the reason `FileItemEditSaveOutcomeTest`
 * gives — their real construction chains open AndroidKeyStore and the vault file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileItemCarriesNotesTest {

    private val dispatcher = StandardTestDispatcher()

    private val folderId: UUID = UUID.randomUUID()
    private val folder = Folder(id = folderId, name = "Passports")

    private val storedNote = "Renewal due March 2029 — the office copy is the older one."

    private val existingId: UUID = UUID.randomUUID()
    private val existing = VaultItem(
        id = existingId,
        title = "Passport scan",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        folderId = folderId,
        content = File(
            filename = "passport.jpg",
            mime = "image/jpeg",
            blobRef = "blob-passport",
            notes = storedNote,
        ),
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
    fun `a new file is written with the note it was given`() = runTest(dispatcher) {
        val viewModel = newFileViewModelWithImport()
        viewModel.onNotesChanged("Scanned at the consulate, 12 March")
        whenever(repository.addItem(any())).thenReturn(AddItemOutcome.ADDED)

        viewModel.save()
        advanceUntilIdle()

        val written = argumentCaptor<VaultItem>()
        verify(repository).addItem(written.capture())
        assertEquals(
            "Scanned at the consulate, 12 March",
            (written.firstValue.content as File).notes,
            "the note is form state; `commitStagedFile` builds the File from the blob and knows " +
                "nothing about it, so a save that does not write it on drops it silently",
        )
    }

    @Test
    fun `an existing file loads its stored note and writes it back`() = runTest(dispatcher) {
        val viewModel = existingFileViewModel()
        advanceUntilIdle()

        assertEquals(
            storedNote,
            viewModel.state.value.notes,
            "a stored note the editor cannot see is a note the next save silently drops",
        )

        viewModel.onNotesChanged("$storedNote Reissued.")
        whenever(repository.updateWith(any(), any())).thenReturn(existing)

        viewModel.save()
        advanceUntilIdle()

        val mutate = argumentCaptor<(VaultItem) -> VaultItem>()
        verify(repository).updateWith(any(), mutate.capture())
        val stored = mutate.firstValue(existing)
        assertEquals("$storedNote Reissued.", (stored.content as File).notes)
    }

    @Test
    fun `a note is unsaved work`() {
        // The discard prompt compares two `editableOnly` snapshots, so a note that did not count
        // would be lost on Back with no warning — the same rule tags and the favourite flag get.
        val pristine = FileItemEditUiState(itemId = existingId)
        assertNotEquals(pristine.editableOnly(), pristine.copy(notes = "typed").editableOnly())
    }

    /** The control: a file saved without touching the section carries no note. */
    @Test
    fun `an untouched new file is written with no note`() = runTest(dispatcher) {
        val viewModel = newFileViewModelWithImport()
        whenever(repository.addItem(any())).thenReturn(AddItemOutcome.ADDED)

        viewModel.save()
        advanceUntilIdle()

        val written = argumentCaptor<VaultItem>()
        verify(repository).addItem(written.capture())
        assertEquals("", (written.firstValue.content as File).notes)
    }

    /**
     * The well itself, source-pinned for the reason `SecretFreeTextWellsSuppressImeLearningTest`
     * in `:feature:itemedit` gives: `KeyboardOptions` reach the platform through `EditorInfo`, not
     * through Compose semantics, so no Robolectric assertion can see them.
     *
     * `KeyboardType.Password` is what suppresses autocorrect and the IME's *learning* of the value
     * — the same treatment every other notes well in the app gets, and for the same reason: a
     * notes field is where the things with no dedicated box are typed.
     */
    @Test
    fun `the notes well is built from LkFormField and asks for a password keyboard`() {
        val screen = java.io.File(
            "src/main/kotlin/com/localkeep/feature/files/FileItemEditScreen.kt",
        ).readText()
        val well = screen.substringAfter("onValueChange = viewModel::onNotesChanged,", "")
            .substringBefore(")\n")

        assertTrue(well.isNotEmpty(), "the notes well moved; this test no longer reads it")
        assertTrue(
            "LkFormField(\n" in screen.substringBefore("onValueChange = viewModel::onNotesChanged,"),
            "the well must be an LkFormField, not a hand-assembled label + gap + well",
        )
        assertTrue(
            "keyboardType = KeyboardType.Password" in well,
            "the file's notes are typed into a keyboard that autocorrects and learns the value",
        )
    }

    /** And the read side: the details sheet has to show a note the editor accepted. */
    @Test
    fun `the details sheet renders the note`() {
        val viewer = java.io.File(
            "src/main/kotlin/com/localkeep/feature/files/SecureMediaViewer.kt",
        ).readText()
        val route = java.io.File(
            "src/main/kotlin/com/localkeep/feature/files/FileItemViewerScreen.kt",
        ).readText()

        assertTrue(
            "R.string.files_viewer_meta_notes" in viewer,
            "the details sheet must show the note, or it is write-only",
        )
        assertTrue("pageNotesById" in route, "the File route must hand its notes to the viewer")
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
        // Deliberately WITHOUT the note: `commitStagedFile` rebuilds the File from the staged blob,
        // which has never carried one, so a save that trusted its result verbatim would blank the
        // stored note on every ordinary edit.
        whenever(photoPickerImport.commitStagedFile(any(), any(), anyOrNull()))
            .thenReturn(
                Result.success(
                    File(filename = "passport.jpg", mime = "image/jpeg", blobRef = "blob-passport"),
                ),
            )
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

package com.localkeep.feature.files

import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
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
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The file editor's half of 3-1, and the residual of 14-2.
 *
 * **3-1 (sibling).** `loadItem` reads the visibility projection exactly once, so `file/{itemId}/
 * settings` was correct at the instant it opened and stale for as long as it stayed open. Reveal a
 * hidden file, open its settings, keep interacting: the reveal window closes, every other surface
 * conceals the file, and this screen went on showing its name, its folder and a thumbnail with a
 * viewer one tap away. **Revert the `repository.presentationState.collect` block in
 * `FileItemEditViewModel`'s `init` and the first test goes red** — `concealed` stays false and the
 * form keeps the filename.
 *
 * **14-2.** Saving an existing file that had left the projection took a bare `return@launch`, and
 * it took it *after* `commitStagedFile` had already written a fresh encrypted blob nothing would
 * ever reference. **Revert the guard now sitting above `commitStagedFile` in `save()` and the third
 * test goes red twice over**: `commitStagedFile` is called, and no error is ever shown.
 *
 * `VaultRepository`/`VaultLockManager` are mocked for the reason `MetadataCleanupStaleSnapshotTest`
 * gives — their real construction chains open AndroidKeyStore and the vault file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileItemEditConcealmentTest {

    private val dispatcher = StandardTestDispatcher()

    private val itemId: UUID = UUID.randomUUID()
    private val item = VaultItem(
        id = itemId,
        title = "Passport scan",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = File(filename = "passport.jpg", mime = "image/jpeg", blobRef = "blob-passport"),
    )

    private lateinit var repository: VaultRepository
    private lateinit var lockManager: VaultLockManager
    private lateinit var photoPickerImport: PhotoPickerImport
    private lateinit var presentation: MutableStateFlow<VaultPresentationState>

    /** What the projection currently shows. Reassigned mid-test to stand for a conceal. */
    private var live: List<VaultItem> = listOf(item)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        live = listOf(item)
        repository = mock()
        lockManager = mock()
        photoPickerImport = mock()
        presentation = MutableStateFlow(VaultPresentationState.Locked)
        whenever(repository.presentationState).thenReturn(presentation)
        // An answer, not a fixed return: the whole point is that the projection changes underneath
        // a screen that read it once at load.
        whenever(repository.visibilitySnapshot()).thenAnswer { snapshot(live) }
        whenever(repository.getCompleteStorageInventory())
            .thenAnswer { VaultStorageInventory(allItems = listOf(item), allFolders = emptyList()) }
        whenever(lockManager.lockState).thenReturn(
            MutableStateFlow<LockState>(
                LockState.Unlocked(
                    dek = ByteArray(32),
                    vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a conceal under the open editor empties it and asks the screen to leave`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals("passport.jpg", viewModel.state.value.filename, "control: the file is loaded to begin with")

        // The reveal window closed. Nothing this screen did, and no write it performed.
        live = emptyList()
        presentation.value = unlockedWith(liveItems = emptyList(), concealed = setOf(itemId))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.concealed, "FileItemEditScreen leaves on this flag")
        assertEquals("", state.filename, "and nothing about the file may still be on screen")
        assertEquals("", state.title)
        assertNull(state.blobRef, "above all not the reference the inline viewer decrypts from")
        assertFalse(state.showViewer)
    }

    @Test
    fun `an item merely absent from the projection is not a concealment`() = runTest(dispatcher) {
        // The control, and the one the item editor learned the hard way: absent is also true of an
        // item being deleted, of a save in flight, and of a screen that subscribed before this
        // StateFlow's `Locked` seed was replaced. Only *concealed and absent* means a lease.
        val viewModel = buildViewModel()
        advanceUntilIdle()

        presentation.value = unlockedWith(liveItems = emptyList(), concealed = emptySet())
        advanceUntilIdle()

        assertFalse(viewModel.state.value.concealed, "an ordinary absence must not empty the editor")
        assertEquals("passport.jpg", viewModel.state.value.filename)
    }

    @Test
    fun `saving a file that left the projection is refused before anything is written`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onTitleChanged("Passport scan, renamed")

        // Gone from the projection, but the presentation flow is left where it is — this is the
        // narrow race 14-2 describes, where the tap lands before the collector has said anything.
        live = emptyList()
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(
            R.string.files_edit_error_item_gone,
            state.error,
            "the refusal has to be said; this used to be a bare return@launch with no error, " +
                "no notice and no navigation, so the Save button simply re-enabled",
        )
        // `anyOrNull` for the folder id, which is null here: mockito's `any(Class)` excludes
        // nulls, so `any()` would fail to match and this `never()` would pass even if the commit
        // had run — a verification that cannot go red is worse than none.
        verify(photoPickerImport, never()).commitStagedFile(any(), any(), anyOrNull())
        verifyBlocking(repository, never()) { updateWith(any(), any()) }
        assertFalse(state.saved)
        assertFalse(state.isWorking, "the finally still runs, so the button comes back")
        assertEquals(
            "Passport scan, renamed",
            state.title,
            "and the edits stay in the form — the message says they are still here and that is true",
        )
    }

    @Test
    fun `an ordinary save still reaches the vault`() = runTest(dispatcher) {
        // The control for the guard above: a check written slightly too wide would refuse every
        // save, and a test that only asserts a refusal would pass with the feature broken.
        val committed = File(filename = "passport.jpg", mime = "image/jpeg", blobRef = "blob-passport")
        whenever(photoPickerImport.commitStagedFile(any(), any(), anyOrNull()))
            .thenReturn(Result.success(committed))
        whenever(repository.updateWith(any(), any())).thenReturn(item)

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onTitleChanged("Passport scan, renamed")

        viewModel.save()
        advanceUntilIdle()

        assertNull(viewModel.state.value.error, "a visible file saves exactly as before")
        assertTrue(viewModel.state.value.saved)
        verifyBlocking(repository) { updateWith(any(), any()) }
    }

    private fun buildViewModel(): FileItemEditViewModel {
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        return FileItemEditViewModel(
            repository = repository,
            lockManager = lockManager,
            photoPickerImport = photoPickerImport,
            fileBlobStore = mock(),
            entitlement = entitlement,
            savedStateHandle = SavedStateHandle(mapOf(ROUTE_FILE_ITEM_ID to itemId.toString())),
        )
    }

    private fun unlockedWith(liveItems: List<VaultItem>, concealed: Set<UUID>) =
        VaultPresentationState.Unlocked(snapshot(liveItems, concealed))

    private fun snapshot(items: List<VaultItem>, concealed: Set<UUID> = emptySet()) = VaultVisibilitySnapshot(
        liveItems = items,
        trashItems = emptyList(),
        liveFolders = emptyList<Folder>(),
        trashedFolderRows = emptyList(),
        concealedItemIds = concealed,
        concealedFolderIds = emptySet(),
        directlyHiddenItemIds = emptySet(),
        directlyHiddenFolderIds = emptySet(),
        nextExpiry = null,
        purgeableTrashItemCount = 0,
    )
}

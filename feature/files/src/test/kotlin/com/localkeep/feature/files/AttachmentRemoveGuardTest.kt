package com.localkeep.feature.files

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultLockManager
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two residuals of 2-3, plus the sibling gap 14-2's verdict named.
 *
 * **Re-entrancy.** Nothing gated the per-row trash buttons on any flag, so a second confirmed
 * removal could start while the first was still committing — two `updateWith` calls, each rewriting
 * the whole vault file, for one user intent. **Revert the `if (_state.value.isRemoving) return` at
 * the top of `AttachmentPickerViewModel.remove` and `a second removal cannot start while the first
 * is committing` goes red** with two commits instead of one.
 *
 * **The silent refusal.** `persist` returned rather than threw when the owning item had left the
 * visible projection, which put the refusal somewhere no caller could see it: `remove`'s own catch
 * arm — written precisely so a failed removal is never reported as a success — could not observe a
 * bare `return`. **Revert `throw ItemNotVisibleException()` back to `return` in `persist` and
 * `a removal refused by the projection is reported` goes red** with a null error.
 *
 * The refusal itself is deliberately unchanged and deliberately narrow: an item under a lease whose
 * reveal grant has expired must not be written through from this screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentRemoveGuardTest {

    private val dispatcher = StandardTestDispatcher()

    private val first = attachment("scan-front.jpg")
    private val second = attachment("scan-back.jpg")
    private val itemId: UUID = UUID.randomUUID()
    private val item = VaultItem(
        id = itemId,
        title = "Passport",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(),
        attachments = listOf(first, second),
    )

    private lateinit var repository: VaultRepository

    /** What the projection currently shows. Emptied mid-test to stand for a conceal. */
    private var live: List<VaultItem> = listOf(item)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        live = listOf(item)
        repository = mock()
        whenever(repository.visibilitySnapshot()).thenAnswer { snapshot(live) }
        whenever(repository.getCompleteStorageInventory())
            .thenAnswer { VaultStorageInventory(allItems = listOf(item), allFolders = emptyList()) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a second removal cannot start while the first is committing`() = runTest(dispatcher) {
        whenever(repository.updateWith(any(), any())).thenReturn(item.copy(attachments = listOf(second)))
        val viewModel = buildViewModel()

        // Both taps land before the first commit gets a chance to run: the coroutine is queued on a
        // StandardTestDispatcher and nothing advances between them. That is the real race — the
        // commit rewrites the whole vault file and is not instantaneous.
        viewModel.remove(first)
        viewModel.remove(second)
        advanceUntilIdle()

        verifyBlocking(repository, times(1)) { updateWith(any(), any()) }
    }

    @Test
    fun `the guard is released once the removal finishes`() = runTest(dispatcher) {
        whenever(repository.updateWith(any(), any())).thenReturn(item.copy(attachments = listOf(second)))
        val viewModel = buildViewModel()

        viewModel.remove(first)
        assertTrue(viewModel.state.value.isRemoving, "the trash buttons are disabled off this flag")
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRemoving, "a stuck flag would leave the row buttons dead")
        // The control for the test above: with the flag cleared a later removal must go through,
        // or the guard has simply broken deletion.
        viewModel.remove(second)
        advanceUntilIdle()
        verifyBlocking(repository, times(2)) { updateWith(any(), any()) }
    }

    @Test
    fun `a removal refused by the projection is reported`() = runTest(dispatcher) {
        val viewModel = buildViewModel()

        // The reveal window closed between opening this screen and confirming the removal.
        live = emptyList()
        viewModel.remove(first)
        advanceUntilIdle()

        assertEquals(
            R.string.files_attachments_error_remove_failed,
            viewModel.state.value.error,
            "the user watched a confirmation dialog close; something has to say the file is still there",
        )
        verifyBlocking(repository, times(0)) { updateWith(any(), any()) }
        assertFalse(viewModel.state.value.isRemoving, "and the flag is released on the failure path too")
    }

    @Test
    fun `an ordinary removal still commits and updates the list`() = runTest(dispatcher) {
        // The control for the refusal: a check written slightly too wide would refuse everything,
        // and the three tests above would all still pass.
        whenever(repository.updateWith(any(), any())).thenReturn(item.copy(attachments = listOf(second)))
        val viewModel = buildViewModel()

        viewModel.remove(first)
        advanceUntilIdle()

        assertNull(viewModel.state.value.error)
        assertEquals(listOf(second.id), viewModel.state.value.attachments.map { it.id })
    }

    private fun buildViewModel(): AttachmentPickerViewModel {
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        val viewModel = AttachmentPickerViewModel(
            repository = repository,
            lockManager = mock<VaultLockManager>(),
            fileBlobStore = mock<FileBlobStore>(),
            photoPickerImport = mock<PhotoPickerImport>(),
            entitlement = entitlement,
            context = ApplicationProvider.getApplicationContext(),
        )
        viewModel.load(itemId.toString())
        assertEquals(2, viewModel.state.value.attachments.size, "control: the item starts with both files")
        return viewModel
    }

    private fun attachment(filename: String) = Attachment(
        id = UUID.randomUUID(),
        filename = filename,
        mime = "image/jpeg",
        sizeBytes = 1_024L,
        blobRef = "blob-$filename",
    )

    private fun snapshot(items: List<VaultItem>) = VaultVisibilitySnapshot(
        liveItems = items,
        trashItems = emptyList(),
        liveFolders = emptyList<Folder>(),
        trashedFolderRows = emptyList(),
        concealedItemIds = emptySet(),
        concealedFolderIds = emptySet(),
        directlyHiddenItemIds = emptySet(),
        directlyHiddenFolderIds = emptySet(),
        nextExpiry = null,
        purgeableTrashItemCount = 0,
    )
}

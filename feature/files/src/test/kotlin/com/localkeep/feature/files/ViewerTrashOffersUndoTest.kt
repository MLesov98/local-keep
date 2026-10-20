package com.localkeep.feature.files

import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.model.File
import com.localkeep.core.model.MediaViewerFocus
import com.localkeep.core.model.MediaViewerFocusRequest
import com.localkeep.core.model.MediaViewerScope
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultVisibilitySnapshot
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Trashing a file from the file viewer says so, and can be undone, like every other trash route.
 *
 * Device pass 2026-10-04 (C1 finding 9): the row menu and the bulk bar post "Moved to trash" with
 * Undo, and so does the item detail screen since 4A-F7 — the viewer trashed the file and closed
 * without a word. Its own confirmation tells the user "this can be undone", and then offered
 * nothing to undo it with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerTrashOffersUndoTest {

    private val item = VaultItem(
        id = UUID.randomUUID(),
        title = "18.mp4",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = File(filename = "18.mp4", mime = "video/mp4", blobRef = "blob-18"),
    )
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        LkNotices.clear()
    }

    @After
    fun tearDown() {
        LkNotices.clear()
        MediaViewerFocus.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `a file trashed from the viewer is announced with an Undo that restores it`() = runTest {
        val viewModel = buildViewModel()
        // Both return the commit's Boolean; an unstubbed mock answers null and the unboxing throws.
        whenever(repository.moveToTrash(item.id)).thenReturn(true)
        whenever(repository.restoreFromTrash(item.id)).thenReturn(true)

        viewModel.deletePage(item.id.toString())

        val notice = assertNotNull(LkNotices.current.value, "the viewer trashed the file in silence")
        assertEquals(LkNotices.Message.Res(R.string.files_notice_moved_to_trash), notice.message)
        assertEquals(LkNotices.Message.Res(R.string.files_notice_undo), notice.actionLabel)

        notice.onAction!!.invoke()

        verify(repository).restoreFromTrash(item.id)
        assertEquals(LkNotices.Message.Res(R.string.files_notice_restored), LkNotices.current.value?.message)
    }

    /** The control: a trash that did not happen is reported on the viewer, never confirmed. */
    @Test
    fun `a failed trash posts no confirmation`() = runTest {
        val viewModel = buildViewModel()
        whenever(repository.moveToTrash(item.id)).thenThrow(IllegalStateException("write failed"))

        viewModel.deletePage(item.id.toString())

        assertNull(LkNotices.current.value)
        assertEquals(R.string.files_error_delete_failed, viewModel.state.value.actionError)
    }

    private fun buildViewModel(): FileItemViewerViewModel {
        repository = mock()
        whenever(repository.visibilitySnapshot()).thenReturn(snapshot())
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow<VaultPresentationState>(VaultPresentationState.Locked))
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(
            MutableStateFlow<LockState>(
                LockState.Unlocked(dek = ByteArray(32), vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH)),
            ),
        )
        MediaViewerFocus.request(
            item.id,
            MediaViewerFocusRequest(
                scope = MediaViewerScope.GlobalSearch,
                visibleOrderedItemIds = listOf(item.id),
                projectionVersion = 1L,
            ),
        )
        return FileItemViewerViewModel(
            repository = repository,
            lockManager = lockManager,
            fileBlobStore = mock(),
            savedStateHandle = SavedStateHandle(mapOf("itemId" to item.id.toString())),
        )
    }

    private fun snapshot() = VaultVisibilitySnapshot(
        liveItems = listOf(item),
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

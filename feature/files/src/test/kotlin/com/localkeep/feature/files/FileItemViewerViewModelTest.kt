package com.localkeep.feature.files

import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.MediaViewerFocus
import com.localkeep.core.model.MediaViewerFocusRequest
import com.localkeep.core.model.MediaViewerScope
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.SessionKey
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultVisibilitySnapshot
import com.localkeep.core.ui.RevealAuth
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileItemViewerViewModelTest {
    private val lockState = MutableStateFlow<LockState>(
        LockState.Unlocked(
            dek = ByteArray(32),
            vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
        ),
    )

    @AfterTest
    fun clearFocus() {
        MediaViewerFocus.clear()
    }

    @Test
    fun `missing start item fails closed instead of selecting another page`() {
        val present = fileItem("present")
        val missing = UUID.randomUUID()
        val vm = buildViewModel(
            routeId = missing,
            launchIds = listOf(present.id),
            items = listOf(present),
        )

        assertEquals(FileViewerPhase.Error, vm.state.value.phase)
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.exitRequested)
    }

    @Test
    fun `successful reveal unlocks the pager once and paging changes the selected id`() {
        val first = fileItem("first")
        val second = fileItem("second")
        val vm = buildViewModel(
            routeId = first.id,
            launchIds = listOf(first.id, second.id),
            items = listOf(first, second),
        )

        assertEquals(FileViewerPhase.Authenticating, vm.state.value.phase)
        whenever(lockManager.unlockedSessionToken()).thenReturn(7L)
        whenever(lockManager.sessionKeyOrNull(7L)).thenReturn(mock<SessionKey>())

        val token = vm.beginReveal()
        vm.completeReveal(token, RevealAuth.RevealOutcome.Success)
        vm.updateSelectedPage(second.id.toString())

        assertEquals(FileViewerPhase.Ready, vm.state.value.phase)
        assertEquals(second.id.toString(), vm.state.value.selectedPageId)
        assertEquals(2, vm.state.value.entries.size)
    }

    @Test
    fun `late successful authentication after lock never mounts content`() {
        val item = fileItem("photo")
        val vm = buildViewModel(routeId = item.id, launchIds = listOf(item.id), items = listOf(item))
        whenever(lockManager.sessionKeyOrNull(99L)).thenReturn(null)

        vm.completeReveal(99L, RevealAuth.RevealOutcome.Success)

        assertEquals(FileViewerPhase.Locked, vm.state.value.phase)
        assertTrue(vm.state.value.entries.isEmpty())
        assertFalse(viewerContentMayCompose(vm.state.value, dekAvailable = false))
    }

    private lateinit var lockManager: VaultLockManager

    private fun buildViewModel(
        routeId: UUID,
        launchIds: List<UUID>,
        items: List<VaultItem>,
    ): FileItemViewerViewModel {
        val repository = mock<VaultRepository>()
        whenever(repository.visibilitySnapshot()).thenReturn(snapshot(items))
        // Collected by the viewer's `init` now — see FileItemViewerConcealmentTest. Unstubbed, the
        // final `val` returns null and the collector NPEs on construction; `Locked` is the flow's
        // own seed and the collector ignores it.
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow<VaultPresentationState>(VaultPresentationState.Locked))
        lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(lockState)
        val focus = MediaViewerFocusRequest(
            scope = MediaViewerScope.GlobalSearch,
            visibleOrderedItemIds = launchIds,
            projectionVersion = 1L,
        )
        MediaViewerFocus.request(routeId, focus)
        return FileItemViewerViewModel(
            repository = repository,
            lockManager = lockManager,
            fileBlobStore = mock(),
            savedStateHandle = SavedStateHandle(mapOf("itemId" to routeId.toString())),
        )
    }

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
        // Deliberately required rather than defaulted: this count is what an Empty Trash
        // confirmation states before destroying that many items, and a default of 0 is the exact
        // shape of the bug it was added to fix. These fixtures hold no trash.
        purgeableTrashItemCount = 0,
    )

    private fun fileItem(title: String) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = File(filename = "$title.jpg", mime = "image/jpeg", blobRef = "blob-$title"),
    )
}

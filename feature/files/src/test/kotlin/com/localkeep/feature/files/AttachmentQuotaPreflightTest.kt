package com.localkeep.feature.files

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultStorageInventory
import com.localkeep.core.vault.VaultVisibilitySnapshot
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for 3-2: the attachment screen's three add buttons (generic picker, gallery,
 * camera) launched the system picker / photo picker / camera unconditionally, so a free vault
 * already at [AttachmentQuota.FREE_LIMIT] was refused only *after* the user had chosen a file or
 * taken a photograph — the camera path made someone grant the CAMERA permission and take a
 * photograph for an attach that could never succeed. `AttachmentPickerUiState.quotaFull` and
 * `refuseIfQuotaFull()` move the check ahead of any launcher/permission request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentQuotaPreflightTest {

    private fun buildViewModel(repository: VaultRepository, isPro: Boolean = false): AttachmentPickerViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(isPro))
        return AttachmentPickerViewModel(
            repository = repository,
            lockManager = mock<VaultLockManager>(),
            fileBlobStore = mock<FileBlobStore>(),
            photoPickerImport = mock<PhotoPickerImport>(),
            entitlement = entitlement,
            context = context,
        )
    }

    private fun repositoryHolding(items: List<VaultItem>): VaultRepository {
        val repository = mock<VaultRepository>()
        whenever(repository.visibilitySnapshot()).thenReturn(
            VaultVisibilitySnapshot(
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
            ),
        )
        // AttachmentQuota reads the complete storage inventory, not the visibility projection.
        whenever(repository.getCompleteStorageInventory()).thenReturn(
            VaultStorageInventory(allItems = items, allFolders = emptyList()),
        )
        return repository
    }

    private fun fileItem(): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        title = "f",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = File(filename = "f.jpg", mime = "image/jpeg", blobRef = "blob-${UUID.randomUUID()}"),
    )

    @Test
    fun `a free vault under the cap is not refused`() {
        val target = VaultItem(
            id = UUID.randomUUID(), title = "t", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
            content = Login(username = "u"),
        )
        val items = List(AttachmentQuota.FREE_LIMIT - 1) { fileItem() } + target
        val viewModel = buildViewModel(repositoryHolding(items))

        viewModel.load(target.id.toString())

        assertFalse(viewModel.state.value.quotaFull, "one slot remains, so the buttons must stay live")
        assertFalse(viewModel.refuseIfQuotaFull(), "a tap under the cap must not be refused")
    }

    @Test
    fun `a free vault already at the cap refuses the tap before any launcher runs`() {
        val target = VaultItem(
            id = UUID.randomUUID(), title = "t", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
            content = Login(username = "u"),
        )
        val items = List(AttachmentQuota.FREE_LIMIT) { fileItem() } + target
        val viewModel = buildViewModel(repositoryHolding(items))

        viewModel.load(target.id.toString())

        // This is the property the bug violated: the screen must already know it is full before
        // any button is tapped, not learn it from a failed attach() after the picker returns.
        assertTrue(viewModel.state.value.quotaFull, "the vault is already at FREE_LIMIT")

        val refused = viewModel.refuseIfQuotaFull()

        assertTrue(refused, "the tap must be refused up front")
        assertEquals(R.string.files_error_quota_exceeded, viewModel.state.value.error)
        assertNotNull(viewModel.state.value.quotaMustRemove)
    }

    @Test
    fun `a Pro vault is never refused, whatever it holds`() {
        val target = VaultItem(
            id = UUID.randomUUID(), title = "t", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
            content = Login(username = "u"),
        )
        val items = List(AttachmentQuota.FREE_LIMIT + 5) { fileItem() } + target
        val viewModel = buildViewModel(repositoryHolding(items), isPro = true)

        viewModel.load(target.id.toString())

        assertFalse(viewModel.state.value.quotaFull)
        assertFalse(viewModel.refuseIfQuotaFull())
    }
}

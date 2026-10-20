package com.localkeep.feature.files

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultStorageInventory
import com.localkeep.core.vault.VaultVisibilitySnapshot
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
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
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S3-13, 2026-10-02: while a picked file was being read, system Back and the header chevron were
 * both disabled and the pending row offered nothing, on the stated premise that the encrypt "is
 * about to finish on its own". For a not-yet-downloaded file from a cloud provider on a flaky
 * connection it does not: `openInputStream` and the chunked read have no timeout, so the only exit
 * was to background the app and wait for auto-lock.
 *
 * The provider's blocking read is modelled by an answer that waits on a latch on the IO thread the
 * ViewModel dispatches it to -- a coroutine cancellation cannot interrupt that, which is the point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachCancelTest {

    private val dispatcher = StandardTestDispatcher()
    private val itemId: UUID = UUID.randomUUID()
    private val item = VaultItem(
        id = itemId,
        title = "Passport",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(),
        attachments = emptyList(),
    )
    private val firstUri: Uri = Uri.parse("content://com.localkeep.test.cloud/first.pdf")
    private val secondUri: Uri = Uri.parse("content://com.localkeep.test.cloud/second.pdf")

    private lateinit var repository: VaultRepository
    private lateinit var blobStore: FileBlobStore
    private lateinit var photoPickerImport: PhotoPickerImport

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mock()
        whenever(repository.visibilitySnapshot()).thenAnswer { snapshot() }
        whenever(repository.getCompleteStorageInventory())
            .thenAnswer { VaultStorageInventory(allItems = listOf(item), allFolders = emptyList()) }
        blobStore = mock()
        photoPickerImport = mock()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun attachment(name: String) = Attachment(
        id = UUID.randomUUID(),
        filename = name,
        mime = "application/pdf",
        sizeBytes = 1_024L,
        blobRef = "blob-$name",
    )

    /** An import that returns [result] only once [release] opens, blocking its worker thread as a stalled read does. */
    private fun stallImport(uri: Uri, release: CountDownLatch, result: Attachment) {
        photoPickerImport.stub {
            onBlocking { importAttachment(eq(uri), any()) } doSuspendableAnswer {
                release.await()
                AttachmentImportResult.Success(result)
            }
        }
    }

    /**
     * Waits until the IO worker is inside the provider read. Cancelling before that point stops the
     * dispatch itself, so the import would never run and the test would prove nothing about a read
     * that is already stalled.
     */
    private suspend fun awaitImportStarted(uri: Uri) {
        verify(photoPickerImport, timeout(5_000)).importAttachment(eq(uri), any())
    }

    private fun buildViewModel(): AttachmentPickerViewModel {
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull())
            .thenAnswer { VaultLockManager.SessionSnapshot(vault = mock<Vault>(), dek = ByteArray(32)) }
        val viewModel = AttachmentPickerViewModel(
            repository = repository,
            lockManager = lockManager,
            fileBlobStore = blobStore,
            photoPickerImport = photoPickerImport,
            entitlement = entitlement,
            context = ApplicationProvider.getApplicationContext(),
        )
        viewModel.load(itemId.toString())
        return viewModel
    }

    @Test
    fun `cancelling a stalled attach frees the screen at once`() = runTest(dispatcher) {
        val release = CountDownLatch(1)
        stallImport(firstUri, release, attachment("first.pdf"))
        val viewModel = buildViewModel()

        viewModel.attach(firstUri)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isWorking && viewModel.state.value.cancellable, "control: the read is in flight")

        viewModel.cancelAttach()

        assertFalse(viewModel.state.value.isWorking, "Back and the header button are disabled off this flag")
        assertFalse(viewModel.state.value.cancellable)
        release.countDown()
    }

    @Test
    fun `a read that finishes after the cancel leaves nothing behind`() = runTest(dispatcher) {
        val release = CountDownLatch(1)
        val late = attachment("first.pdf")
        stallImport(firstUri, release, late)
        val viewModel = buildViewModel()

        viewModel.attach(firstUri)
        advanceUntilIdle()
        awaitImportStarted(firstUri)
        viewModel.cancelAttach()
        release.countDown()

        // The worker finishes on its own thread; its blob is deleted there because nothing will
        // ever reference it, and the cancelled coroutine never reaches `persist`.
        verify(blobStore, timeout(5_000)).deleteBlob(late.blobRef)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.attachments.isEmpty(), "a cancelled attach must not appear in the list")
        assertFalse(viewModel.state.value.isWorking)
    }

    @Test
    fun `the abandoned read cannot clear a newer attach`() = runTest(dispatcher) {
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val first = attachment("first.pdf")
        stallImport(firstUri, releaseFirst, first)
        stallImport(secondUri, releaseSecond, attachment("second.pdf"))
        val viewModel = buildViewModel()

        viewModel.attach(firstUri)
        advanceUntilIdle()
        awaitImportStarted(firstUri)
        viewModel.cancelAttach()
        viewModel.attach(secondUri)
        advanceUntilIdle()
        awaitImportStarted(secondUri)
        assertTrue(viewModel.state.value.isWorking, "control: the second attach is in flight")

        releaseFirst.countDown()
        verify(blobStore, timeout(5_000)).deleteBlob(first.blobRef)
        advanceUntilIdle()

        assertTrue(
            viewModel.state.value.isWorking,
            "the first attach's late finish cleared the flags of the second, which is still reading",
        )
        releaseSecond.countDown()
    }

    /** Control: an attach nobody cancels still completes and releases the screen. */
    @Test
    fun `an attach that is not cancelled still completes`() = runTest(dispatcher) {
        val done = attachment("first.pdf")
        stallImport(firstUri, CountDownLatch(0), done)
        whenever(repository.updateWith(any(), any())).thenReturn(item.copy(attachments = listOf(done)))
        val viewModel = buildViewModel()

        viewModel.attach(firstUri)
        // The import runs on a real IO thread and resumes the coroutine on the test dispatcher, so
        // alternate between draining that dispatcher and giving the worker a moment.
        repeat(250) {
            advanceUntilIdle()
            if (!viewModel.state.value.isWorking) return@repeat
            Thread.sleep(20)
        }

        assertEquals(listOf(done.id), viewModel.state.value.attachments.map { it.id })
        assertFalse(viewModel.state.value.isWorking)
        assertFalse(viewModel.state.value.cancellable)
    }

    private fun snapshot() = VaultVisibilitySnapshot(
        liveItems = listOf(item),
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

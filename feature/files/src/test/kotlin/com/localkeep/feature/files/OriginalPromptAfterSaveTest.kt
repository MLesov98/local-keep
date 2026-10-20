package com.localkeep.feature.files

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.File
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The question after Save about the original left outside the vault, reported from a phone
 * 2026-10-09: Android's own *"Allow LocalKeep to delete this photo?"* appeared with nothing before
 * it, read as the app demanding to destroy something, and did not say which copy it meant. And a
 * PDF or a spreadsheet brought in from Downloads was never offered anything at all.
 *
 * The decision is made in [originalPromptFor] and set by `save()` in the same write as `saved`; the
 * screen asks it, and leaves only once it is answered. Each test here fails on the code before the
 * change, which had no prompt field and no document route.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OriginalPromptAfterSaveTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: VaultRepository
    private lateinit var lockManager: VaultLockManager
    private lateinit var photoPickerImport: PhotoPickerImport
    private lateinit var fileBlobStore: FileBlobStore

    private val galleryUri = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/91")
    private val documentUri = Uri.parse("content://com.android.providers.downloads.documents/document/42")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        LkNotices.clear()
        repository = mock()
        lockManager = mock()
        photoPickerImport = mock()
        fileBlobStore = mock()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow<VaultPresentationState>(VaultPresentationState.Locked))
        whenever(repository.visibilitySnapshot()).thenAnswer { snapshot(emptyList()) }
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

    // --- The decision ---------------------------------------------------------------------------

    @Test
    fun `a saved gallery photo asks about its original, by the item's name`() = runTest(dispatcher) {
        val viewModel = savedAfterImport(staged(mime = "image/jpeg", galleryUri = galleryUri))
        assertEquals(OriginalPrompt(OriginalKind.PHOTO, "Passport"), viewModel.state.value.originalPrompt)
    }

    @Test
    fun `a saved gallery video asks about a video`() = runTest(dispatcher) {
        val viewModel = savedAfterImport(staged(mime = "video/mp4", galleryUri = galleryUri))
        assertEquals(OriginalKind.VIDEO, viewModel.state.value.originalPrompt?.kind)
    }

    /** The half that did not exist: a document from Downloads is offered too. */
    @Test
    fun `a saved local document asks about its original`() = runTest(dispatcher) {
        val viewModel = savedAfterImport(staged(mime = "application/pdf", documentUri = documentUri))
        assertEquals(OriginalKind.DOCUMENT, viewModel.state.value.originalPrompt?.kind)
    }

    /** The control: a capture never touched the gallery, and an undeletable document is not offered. */
    @Test
    fun `nothing is asked when there is no original to offer`() = runTest(dispatcher) {
        val viewModel = savedAfterImport(staged(mime = "image/jpeg"))
        assertTrue(viewModel.state.value.saved)
        assertNull(viewModel.state.value.originalPrompt)
    }

    @Test
    fun `keeping the original clears the question and what it was about`() = runTest(dispatcher) {
        val viewModel = savedAfterImport(staged(mime = "image/jpeg", galleryUri = galleryUri))
        viewModel.keepOriginal()
        val state = viewModel.state.value
        assertNull(state.originalPrompt)
        assertNull(state.pendingDeleteUri)
        assertFalse(state.originalDeleteInFlight)
    }

    // --- The gallery route ------------------------------------------------------------------------

    @Test
    fun `the screen waits while Android's dialog is up, and says what it answered`() = runTest(dispatcher) {
        val viewModel = savedAfterImport(staged(mime = "image/jpeg", galleryUri = galleryUri))
        viewModel.onGalleryDeleteLaunched()
        assertTrue(viewModel.state.value.originalDeleteInFlight, "leaving now would drop Android's answer")
        assertNull(viewModel.state.value.originalPrompt)

        viewModel.onGalleryDeleteResult(deleted = false)
        assertFalse(viewModel.state.value.originalDeleteInFlight)
        assertEquals(
            LkNotices.Message.Res(R.string.files_notice_original_kept_gallery),
            LkNotices.current.value?.message,
        )
    }

    // --- The document route -----------------------------------------------------------------------

    @Test
    fun `a confirmed document delete removes the original once the vault copy reads back`() = runTest(dispatcher) {
        val viewModel = savedAfterImport(staged(mime = "application/pdf", documentUri = documentUri))
        whenever(fileBlobStore.authenticates(any(), any())).thenReturn(true)
        whenever(photoPickerImport.deleteOriginalDocument(any())).thenReturn(true)

        viewModel.deleteOriginalDocument()
        awaitIdle { !viewModel.state.value.originalDeleteInFlight }

        verify(photoPickerImport).deleteOriginalDocument(documentUri)
        val state = viewModel.state.value
        assertTrue(state.removedFromGallery)
        assertFalse(state.originalDeleteInFlight)
        assertNull(state.pendingDocumentDeleteUri)
        assertEquals(
            LkNotices.Message.Res(R.string.files_notice_original_deleted_document),
            LkNotices.current.value?.message,
        )
    }

    /**
     * There is no bin on this route, so the only copy left would be the vault's. One that does not
     * authenticate must stop the delete — and say the original is still there.
     */
    @Test
    fun `a vault copy that does not read back stops the delete`() = runTest(dispatcher) {
        val viewModel = savedAfterImport(staged(mime = "application/pdf", documentUri = documentUri))
        whenever(fileBlobStore.authenticates(any(), any())).thenReturn(false)

        viewModel.deleteOriginalDocument()
        awaitIdle { !viewModel.state.value.originalDeleteInFlight }

        verify(photoPickerImport, never()).deleteOriginalDocument(any())
        assertFalse(viewModel.state.value.removedFromGallery)
        assertFalse(viewModel.state.value.originalDeleteInFlight)
        assertEquals(
            LkNotices.Message.Res(R.string.files_notice_original_delete_failed),
            LkNotices.current.value?.message,
        )
    }

    // --- Pure decisions ---------------------------------------------------------------------------

    /**
     * Seen on emulator-5554 2026-10-09: the "Name it" sheet stayed up under Android's dialog. The
     * control half matters as much: with nothing to ask, the sheet must *not* close before the
     * screen leaves, or the form flickers between the two (`checkNoDismissThenNavigate`).
     */
    @Test
    fun `the naming sheet gives way to the question, and only to the question`() {
        assertFalse(namingSheetVisible(requested = true, prompt = OriginalPrompt(OriginalKind.PHOTO, "x")))
        assertTrue(namingSheetVisible(requested = true, prompt = null))
        assertFalse(namingSheetVisible(requested = false, prompt = null))
    }

    @Test
    fun `a document is offered only from a local provider that can delete it and granted write`() {
        val flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        assertTrue(OriginalDocuments.isOfferable("com.android.providers.downloads.documents", flags, canWrite = true))
        assertTrue(OriginalDocuments.isOfferable("com.android.externalstorage.documents", flags, canWrite = true))
        // A cloud provider is never offered, whatever it reports: a Drive file can be someone else's too.
        assertFalse(OriginalDocuments.isOfferable("com.google.android.apps.docs.storage", flags, canWrite = true))
        assertFalse(OriginalDocuments.isOfferable("com.android.providers.downloads.documents", 0, canWrite = true))
        assertFalse(OriginalDocuments.isOfferable("com.android.providers.downloads.documents", flags, canWrite = false))
        assertFalse(OriginalDocuments.isOfferable(null, flags, canWrite = true))
    }

    @Test
    fun `the metadata note says only what the bytes showed`() {
        assertEquals(MetadataNote.FOUND, metadataNoteFor(MetadataCleanupState.PENDING, MetadataPresence.PRESENT))
        assertEquals(MetadataNote.NONE, metadataNoteFor(MetadataCleanupState.PENDING, MetadataPresence.ABSENT))
        assertEquals(MetadataNote.MAYBE, metadataNoteFor(MetadataCleanupState.PENDING, MetadataPresence.UNKNOWN))
        // Still probing: say nothing rather than show the hedge and then take it back.
        assertEquals(MetadataNote.NONE, metadataNoteFor(MetadataCleanupState.PENDING, null))
        assertEquals(MetadataNote.REMOVED, metadataNoteFor(MetadataCleanupState.REMOVED, MetadataPresence.ABSENT))
    }

    @Test
    fun `the file card names the type, not the MIME string`() {
        assertEquals("PNG", fileTypeLabel("91.png", "image/png"))
        assertEquals("XLSX", fileTypeLabel("budget.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals("PDF", fileTypeLabel("scan", "application/pdf"))
        assertEquals("FILE", fileTypeLabel("blob", "application/octet-stream"))
    }

    // --- A reopened image is read, not assumed ----------------------------------------------------

    /**
     * Reported 2026-10-09: "This image may still carry location and camera data" on every open —
     * over a screenshot with nothing in it, and over a photo already cleaned. Reopening now reads
     * the stored bytes.
     */
    @Test
    fun `reopening a clean image says nothing about metadata, and a tagged one says it carries some`() =
        runTest(dispatcher) {
            assertEquals(MetadataPresence.ABSENT, presenceAfterReopening(png()))
            assertEquals(MetadataPresence.PRESENT, presenceAfterReopening(png(pngChunk("tEXt", "Software".toByteArray()))))
        }

    private fun TestScope.presenceAfterReopening(bytes: ByteArray): MetadataPresence? {
        val id = UUID.randomUUID()
        val item = VaultItem(
            id = id,
            title = "Screenshot",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = File(filename = "shot.png", mime = "image/png", blobRef = "blob-$id"),
        )
        whenever(repository.visibilitySnapshot()).thenAnswer { snapshot(listOf(item)) }
        whenever(fileBlobStore.openDecryptingSource(any(), any())).thenAnswer { ByteArrayInputStream(bytes) }
        val viewModel = buildViewModel(SavedStateHandle(mapOf(ROUTE_FILE_ITEM_ID to id.toString())))
        awaitIdle { viewModel.state.value.metadataPresence != null }
        return viewModel.state.value.metadataPresence
    }

    /** Both of these read on Dispatchers.IO, which the test scheduler does not drive. */
    private fun TestScope.awaitIdle(done: () -> Boolean) {
        repeat(300) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    // --- Fixtures ---------------------------------------------------------------------------------

    private suspend fun savedAfterImport(source: StagedFileSource): FileItemEditViewModel {
        whenever(photoPickerImport.stageFile(any(), any(), any())).thenReturn(Result.success(source))
        whenever(photoPickerImport.commitStagedFile(any(), any(), anyOrNull()))
            .thenReturn(Result.success(File(filename = source.filename, mime = source.mime, blobRef = source.blobRef)))
        whenever(repository.addItem(any())).thenReturn(AddItemOutcome.ADDED)
        val viewModel = buildViewModel(SavedStateHandle())
        viewModel.importFromPicker(galleryUri)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.onTitleChanged("Passport")
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.saved, "control: the save itself went through")
        return viewModel
    }

    private fun staged(mime: String, galleryUri: Uri? = null, documentUri: Uri? = null) = StagedFileSource(
        blobRef = "blob-new",
        thumbnailRef = null,
        filename = "original." + mime.substringAfter('/'),
        mime = mime,
        sizeBytes = 8L,
        generationId = UUID.randomUUID(),
        metadataState = MetadataCleanupState.NOT_APPLICABLE,
        sourceUri = null,
        capturedInApp = galleryUri == null && documentUri == null,
        removedFromGallery = false,
        pendingGalleryDeleteUri = galleryUri,
        hasGalleryOriginal = galleryUri != null,
        deletableOriginalDocument = documentUri,
    )

    private fun buildViewModel(handle: SavedStateHandle): FileItemEditViewModel {
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(true))
        return FileItemEditViewModel(
            repository = repository,
            lockManager = lockManager,
            photoPickerImport = photoPickerImport,
            fileBlobStore = fileBlobStore,
            entitlement = entitlement,
            savedStateHandle = handle,
        )
    }

    private fun vault() = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH)

    private fun snapshot(items: List<VaultItem>) = VaultVisibilitySnapshot(
        liveItems = items,
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

    private fun intBe(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun pngChunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(intBe(data.size))
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        out.write(intBe(CRC32().apply { update(typeBytes); update(data) }.value.toInt()))
        return out.toByteArray()
    }

    private fun png(vararg extra: ByteArray): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            pngChunk("IHDR", ByteArray(13)) + extra.fold(ByteArray(0), ByteArray::plus) +
            pngChunk("IDAT", ByteArray(4)) + pngChunk("IEND")
}

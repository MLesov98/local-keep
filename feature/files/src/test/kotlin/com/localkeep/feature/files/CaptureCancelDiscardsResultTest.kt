package com.localkeep.feature.files

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultPresentationState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Regression test for 15-3: `dismissCapture` used to only flip `showCapture` false while
 * `onCaptured`'s strip-plus-encrypt pipeline kept running underneath the form the user had just
 * returned to. A Cancel tap during a slow capture was then silently overwritten a moment later by
 * the very photograph the user had just cancelled — and if a gallery import was already staged,
 * `replaceStagedSource` deleted its uncommitted blob first, losing it for good.
 *
 * `photoPickerImport.stageCapturedFile`'s mocked answer calls `dismissCapture()` itself, which
 * reproduces the exact race the verifier confirmed: cancellation is requested *while the pipeline
 * call is already in flight*, not before it starts and not after it returns. `VaultLockManager`
 * cannot be constructed for real under Robolectric (its chain reaches `KeystoreManager`), so it is
 * mocked, matching `AttachmentPickerViewModelRevealTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureCancelDiscardsResultTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun gallerySource() = StagedFileSource(
        blobRef = "gallery-blob",
        thumbnailRef = null,
        filename = "gallery.jpg",
        mime = "image/jpeg",
        sizeBytes = 100L,
        generationId = UUID.randomUUID(),
        metadataState = MetadataCleanupState.PENDING,
        sourceUri = null,
        capturedInApp = false,
        removedFromGallery = false,
        pendingGalleryDeleteUri = null,
    )

    private fun captureSource() = StagedFileSource(
        blobRef = "capture-blob",
        thumbnailRef = null,
        filename = "capture-1.jpg",
        mime = "image/jpeg",
        sizeBytes = 200L,
        generationId = UUID.randomUUID(),
        metadataState = MetadataCleanupState.PENDING,
        sourceUri = null,
        capturedInApp = true,
        removedFromGallery = true,
        pendingGalleryDeleteUri = null,
    )

    private fun buildViewModel(photoPickerImport: PhotoPickerImport): FileItemEditViewModel {
        val lockManager = mock<VaultLockManager>()
        val lockState = MutableStateFlow<LockState>(
            LockState.Unlocked(dek = ByteArray(32), vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH)),
        )
        whenever(lockManager.lockState).thenReturn(lockState)
        whenever(lockManager.sessionSnapshotOrNull()).thenReturn(
            VaultLockManager.SessionSnapshot(
                vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
                dek = ByteArray(32),
            ),
        )
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        // Stubbed because FileItemEditViewModel's `init` now collects it (concealment while the
        // editor is open). An unstubbed final `val` returns null and the collector NPEs on
        // construction. `Locked` is the flow's own seed and the collector ignores it.
        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow<VaultPresentationState>(VaultPresentationState.Locked))
        return FileItemEditViewModel(
            repository = repository,
            lockManager = lockManager,
            photoPickerImport = photoPickerImport,
            fileBlobStore = mock(),
            entitlement = entitlement,
            savedStateHandle = SavedStateHandle(),
        )
    }

    @Test
    fun `cancelling during an in-flight capture drops its result instead of overwriting the form`() = runTest(dispatcher) {
        val photoPickerImport = mock<PhotoPickerImport>()
        val gallery = gallerySource()
        val capture = captureSource()
        whenever(photoPickerImport.stageFile(any(), any(), any())).thenReturn(Result.success(gallery))

        val viewModel = buildViewModel(photoPickerImport)

        // Stage a gallery import first — the earlier selection whose blob must survive.
        viewModel.importFromPicker(Uri.parse("content://com.localkeep.test/gallery"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("gallery-blob", viewModel.state.value.blobRef)

        // The mocked answer fires exactly where the real pipeline would be mid-encrypt — this is
        // the race: the user taps Cancel while stageCapturedFile is still in flight. Returns the
        // raw success value, not `Result.success(capture)`: a suspend fun returning `Result<T>`
        // uses Kotlin's special unwrapped calling convention, and `thenAnswer` (unlike
        // `thenReturn`) does not get mockito-kotlin's coroutine-aware unwrapping — verified by
        // reproducing a ClassCastException("kotlin.Result cannot be cast to StagedFileSource")
        // with `Result.success(capture)` here.
        whenever(photoPickerImport.stageCapturedFile(any(), any())).thenAnswer {
            viewModel.dismissCapture()
            capture
        }

        viewModel.onCaptured(ByteArray(4))
        dispatcher.scheduler.advanceUntilIdle()

        // The form must still show the gallery import, not the cancelled capture.
        assertEquals("gallery-blob", viewModel.state.value.blobRef)
        assertEquals("gallery.jpg", viewModel.state.value.filename)
        // The cancelled capture's blob must be discarded, not left as an orphan.
        verify(photoPickerImport).discardStagedFile(capture)
        // And the gallery blob must never have been touched by the (skipped) replacement.
        verify(photoPickerImport, never()).discardStagedFile(gallery)
    }
}

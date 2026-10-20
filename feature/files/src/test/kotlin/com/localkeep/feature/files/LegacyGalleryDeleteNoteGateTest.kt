package com.localkeep.feature.files

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test, 2026-09-13.
 *
 * On Android 9 and 10 nothing can delete a gallery original programmatically, so the file editor
 * shows a note asking the user to do it by hand. It was gated on the OS version and `capturedInApp`
 * alone, which is every picker import — so a PDF or a text file brought in through "From documents",
 * which never had a gallery original at all, was told to go and remove something from the Gallery
 * app that was never there. `PhotoPickerImport.stageFile` already knows the answer: it suppresses
 * `pendingGalleryDeleteUri` on exactly that signal. The signal just never reached the screen.
 *
 * Two halves are checked here, because the gap was between them: the predicate the composable calls,
 * and the flag's journey from `stageFile` into `FileItemEditUiState`. The mocked import is what lets
 * the second half run on the JVM — `stageFile` itself needs a ContentResolver and a blob store —
 * which is why the `!fromDocumentPicker` line inside it is pinned on the source instead.
 *
 * `VaultLockManager` cannot be constructed for real under Robolectric (its chain reaches
 * `KeystoreManager`), so it is mocked, matching `CaptureCancelDiscardsResultTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyGalleryDeleteNoteGateTest {

    private val dispatcher = StandardTestDispatcher()

    /** Android 9. The note exists for 28 and 29 only; 30 is where `createDeleteRequest` arrives. */
    private val androidNine = 28

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- the predicate the composable calls ------------------------------------------------

    @Test
    fun `a file with no gallery original is not told to go and delete one`() {
        assertFalse(
            legacyGalleryDeleteNoteVisible(
                sdkInt = androidNine,
                capturedInApp = false,
                hasGalleryOriginal = false,
            ),
            "a document-picker import has no gallery original, so the note names an action the " +
                "reader cannot carry out",
        )
    }

    /** Control: where a gallery original really does exist, the note is still the right thing. */
    @Test
    fun `a gallery import on Android 9 still gets the note`() {
        assertTrue(
            legacyGalleryDeleteNoteVisible(
                sdkInt = androidNine,
                capturedInApp = false,
                hasGalleryOriginal = true,
            ),
        )
    }

    /**
     * Measured on the API 28 image, 2026-10-09: below API 30 the gallery route falls back to the
     * system document picker, and the URI it returns can be deleted through `DocumentsContract` —
     * the same route a PDF from Downloads takes. Where that is on offer, the app asks the question
     * itself and deletes for the user, so a note telling them to do it by hand would be wrong.
     */
    @Test
    fun `a gallery import on Android 9 whose original the app can delete gets the question, not the note`() {
        assertFalse(
            legacyGalleryDeleteNoteVisible(
                sdkInt = androidNine,
                capturedInApp = false,
                hasGalleryOriginal = true,
                originalDeletable = true,
            ),
        )
    }

    /** And the link that makes it so: below API 30 a gallery pick is checked for deletability too. */
    @Test
    fun `stageFile checks a gallery pick for a deletable original where Android cannot be asked`() {
        val start = importSource.indexOf("suspend fun stageFile(")
        val end = importSource.indexOf("suspend fun stageCapturedFile(")
        val body = importSource.substring(start, end)
        assertTrue(
            "if (fromDocumentPicker || !MediaDeleter.isDeleteRequestSupported())" in body,
            "below API 30 a gallery pick is a document URI and must be offered the document route",
        )
    }

    /** Controls: the two conditions that were already there must keep holding. */
    @Test
    fun `the note stays off above Android 10 and for in-app captures`() {
        assertFalse(
            legacyGalleryDeleteNoteVisible(sdkInt = 30, capturedInApp = false, hasGalleryOriginal = true),
            "API 30 can raise a delete request, so the manual instruction is obsolete there",
        )
        assertFalse(
            legacyGalleryDeleteNoteVisible(sdkInt = androidNine, capturedInApp = true, hasGalleryOriginal = true),
            "a photograph taken inside the app was never in the gallery",
        )
    }

    // --- the flag's journey from the import into the form ----------------------------------

    @Test
    fun `a document-picker import leaves the form with no gallery original`() = runTest(dispatcher) {
        val photoPickerImport = mock<PhotoPickerImport>()
        whenever(photoPickerImport.stageFile(any(), any(), any()))
            .thenReturn(Result.success(stagedSource(hasGalleryOriginal = false)))
        val viewModel = buildViewModel(photoPickerImport)

        viewModel.importFromPicker(
            Uri.parse("content://com.localkeep.test/document"),
            fromDocumentPicker = true,
        )
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.hasGalleryOriginal, "the import's own answer never reached the form")
        assertFalse(
            legacyGalleryDeleteNoteVisible(androidNine, state.capturedInApp, state.hasGalleryOriginal),
        )
    }

    /** Control: the gallery route must come out the other side with the note still eligible. */
    @Test
    fun `a gallery import leaves the form with its original recorded`() = runTest(dispatcher) {
        val photoPickerImport = mock<PhotoPickerImport>()
        whenever(photoPickerImport.stageFile(any(), any(), any()))
            .thenReturn(Result.success(stagedSource(hasGalleryOriginal = true)))
        val viewModel = buildViewModel(photoPickerImport)

        viewModel.importFromPicker(Uri.parse("content://com.localkeep.test/gallery"))
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.hasGalleryOriginal)
        assertTrue(
            legacyGalleryDeleteNoteVisible(androidNine, state.capturedInApp, state.hasGalleryOriginal),
        )
    }

    // --- the one link the JVM cannot drive --------------------------------------------------

    private val importSource: String =
        File("src/main/kotlin/com/localkeep/feature/files/PhotoPickerImport.kt").readText()

    /**
     * `stageFile` needs a ContentResolver and a real blob store, so the tests above mock it and
     * cannot prove it answers correctly. This is that link: the flag is derived from the same
     * `fromDocumentPicker` parameter `pendingGalleryDeleteUri` is already derived from, and derived
     * *without* the API-30 half, which is null for every import on the releases the note is for.
     */
    @Test
    fun `stageFile derives the gallery original from the picker it was given`() {
        val start = importSource.indexOf("suspend fun stageFile(")
        val end = importSource.indexOf("suspend fun stageCapturedFile(")
        assertTrue(start >= 0, "stageFile is gone from PhotoPickerImport.kt")
        assertTrue(end > start, "stageCapturedFile no longer follows stageFile — the bound is wrong")

        val stageFileBody = importSource.substring(start, end)
        assertTrue(
            "pendingGalleryDeleteUri =" in stageFileBody,
            "the slice does not reach the staged source it is asserting about",
        )
        assertTrue(
            "hasGalleryOriginal = !fromDocumentPicker" in stageFileBody,
            "stageFile knows whether the file came from the gallery and no longer says so, so the " +
                "editor is back to guessing from capturedInApp alone",
        )
    }

    // --- harness ----------------------------------------------------------------------------

    private fun stagedSource(hasGalleryOriginal: Boolean) = StagedFileSource(
        blobRef = "staged-blob",
        thumbnailRef = null,
        filename = if (hasGalleryOriginal) "holiday.jpg" else "contract.pdf",
        mime = if (hasGalleryOriginal) "image/jpeg" else "application/pdf",
        sizeBytes = 100L,
        generationId = UUID.randomUUID(),
        metadataState = if (hasGalleryOriginal) {
            MetadataCleanupState.PENDING
        } else {
            MetadataCleanupState.NOT_APPLICABLE
        },
        sourceUri = null,
        capturedInApp = false,
        removedFromGallery = false,
        // Null on both branches, and that is the point of the finding: `createDeleteRequest` is API
        // 30, so this field is null for every import on the two releases the note is written for.
        pendingGalleryDeleteUri = null,
        hasGalleryOriginal = hasGalleryOriginal,
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
}

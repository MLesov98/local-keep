package com.localkeep.feature.files

import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.ui.RevealAuth
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultPresentationState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A file item's content cost two different prices depending on which door you came through.
 *
 * The standalone viewer route challenges on every load — `FileItemViewerViewModel` starts in
 * `Authenticating` whatever the item says. The editor's own View button challenged only when the
 * item had `reprompt` set, so for every other file, Settings → View reached the decrypted bytes
 * with nothing asked. Both doors open the same blob, and a gate that one route enforces and
 * another does not is a gate the user cannot rely on.
 *
 * The mocks follow AttachmentPickerViewModelRevealTest: none of the methods under test touch the
 * repository, the lock manager or the blob store, so nothing is stubbed beyond the constructor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileViewChallengeTest {

    private fun buildViewModel(): FileItemEditViewModel {
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow<LockState>(LockState.Locked))
        whenever(lockManager.onSaved).thenReturn(MutableSharedFlow())
        // Stubbed because FileItemEditViewModel's `init` now collects it (concealment while the
        // editor is open). An unstubbed final `val` returns null and the collector NPEs on
        // construction. `Locked` is the flow's own seed and the collector ignores it.
        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow<VaultPresentationState>(VaultPresentationState.Locked))
        return FileItemEditViewModel(
            repository = repository,
            lockManager = lockManager,
            photoPickerImport = mock<PhotoPickerImport>(),
            fileBlobStore = mock<FileBlobStore>(),
            entitlement = entitlement,
            savedStateHandle = SavedStateHandle(),
        )
    }

    @Test
    fun `opening a file with no reprompt flag still has to pass the challenge`() {
        val viewModel = buildViewModel()
        assertFalse(viewModel.state.value.reprompt, "control: this is the ungated case, not a reprompt item")

        viewModel.showViewer()

        val state = viewModel.state.value
        assertTrue(state.pendingViewerReveal, "the challenge must be parked")
        assertFalse(
            state.showViewer,
            "and nothing may open until it passes — asking and showing at the same time is not a gate",
        )
    }

    @Test
    fun `only a passed challenge opens the file`() {
        val viewModel = buildViewModel()
        viewModel.showViewer()

        viewModel.onViewerRevealAuthenticated()

        assertTrue(viewModel.state.value.showViewer)
        assertFalse(viewModel.state.value.pendingViewerReveal)
    }

    @Test
    fun `a refused challenge says which refusal it was, and opens nothing`() {
        val viewModel = buildViewModel()
        viewModel.showViewer()

        viewModel.onViewerRevealDenied(viewerRevealMessage(RevealAuth.RevealOutcome.UserCancelled))

        val state = viewModel.state.value
        assertFalse(state.showViewer)
        assertFalse(state.pendingViewerReveal)
        assertEquals(
            R.string.files_viewer_auth_cancelled,
            state.error,
            "a View button that silently does nothing reads as a broken app, not a declined prompt",
        )
    }

    /** The same distinction the standalone route makes: cancelled is not rejected. */
    @Test
    fun `cancelling is never reported as a rejected credential`() {
        assertEquals(
            R.string.files_viewer_auth_cancelled,
            viewerRevealMessage(RevealAuth.RevealOutcome.UserCancelled),
        )
        assertEquals(
            R.string.files_viewer_auth_denied,
            viewerRevealMessage(RevealAuth.RevealOutcome.Denied),
        )
        assertEquals(
            R.string.files_viewer_auth_unavailable,
            viewerRevealMessage(RevealAuth.RevealOutcome.Unavailable),
        )
        assertEquals(
            R.string.files_viewer_auth_failed,
            viewerRevealMessage(RevealAuth.RevealOutcome.Error(7)),
        )
    }
}

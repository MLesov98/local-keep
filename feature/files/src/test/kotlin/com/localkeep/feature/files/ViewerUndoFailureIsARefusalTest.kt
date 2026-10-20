package com.localkeep.feature.files

import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.model.Vault
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.Config

/**
 * The viewer's "Moved to trash · Undo" announces a failed restore as a refusal.
 *
 * "Couldn't put it back. It is still in Trash." arrived with the success check and buzz, which told
 * the user the opposite of the sentence beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerUndoFailureIsARefusalTest {

    @BeforeTest
    @AfterTest
    fun reset() = LkNotices.clear()

    /** Trashes through the viewer's real path, then presses the Undo on the notice it posted. */
    private fun trashThenUndo(repository: VaultRepository) {
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow<VaultPresentationState>(VaultPresentationState.Locked))
        runBlocking {
            whenever(repository.moveToTrash(org.mockito.kotlin.any())).doSuspendableAnswer { true }
        }
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(
            MutableStateFlow<LockState>(
                LockState.Unlocked(
                    dek = ByteArray(32),
                    vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
                ),
            ),
        )
        val viewModel = FileItemViewerViewModel(
            repository = repository,
            lockManager = lockManager,
            fileBlobStore = mock(),
            savedStateHandle = SavedStateHandle(mapOf("itemId" to UUID.randomUUID().toString())),
        )
        viewModel.deletePage(UUID.randomUUID().toString())
        ShadowLooper.idleMainLooper()
        LkNotices.current.value!!.onAction!!.invoke()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a failed restore is a refusal with its own sentence`() {
        val repository = mock<VaultRepository>()
        runBlocking {
            whenever(repository.restoreFromTrash(org.mockito.kotlin.any()))
                .doSuspendableAnswer { error("vault write failed") }
        }
        trashThenUndo(repository)
        val notice = LkNotices.current.value!!
        assertEquals(LkNotices.Message.Res(R.string.files_notice_restore_failed), notice.message)
        assertEquals(LkNotices.Tone.Refusal, notice.tone)
    }

    /** The control: a restore that worked stays a success. */
    @Test
    fun `a successful restore stays a success`() {
        val repository = mock<VaultRepository>()
        runBlocking {
            whenever(repository.restoreFromTrash(org.mockito.kotlin.any())).doSuspendableAnswer { true }
        }
        trashThenUndo(repository)
        val notice = LkNotices.current.value!!
        assertEquals(LkNotices.Message.Res(R.string.files_notice_restored), notice.message)
        assertEquals(LkNotices.Tone.Success, notice.tone)
    }
}

package com.localkeep.feature.files

import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.MediaViewerFocus
import com.localkeep.core.model.MediaViewerFocusRequest
import com.localkeep.core.model.MediaViewerScope
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.RevealAuth
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.SessionKey
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
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
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for 3-1: the standalone file viewer stayed mounted on concealed media.
 *
 * The viewer's only subscription was `lockManager.lockState`, and its projection re-ran at three
 * moments only — construction, `retry()`, and the `ON_RESUME` that drives `revalidate()`. So the
 * one case it could not see was the one that matters: still in the foreground, still resumed, and
 * the 120 s reveal window closing on `RevealSession`'s own timer. Every other surface conceals the
 * item at that instant; this screen carried on showing it, decrypted and swipeable, because paging
 * a media viewer extends the auto-lock while the reveal deadline does not move.
 *
 * The fix subscribes to `repository.presentationState` — the same projection every other surface
 * reads — and re-runs the existing intersection through `revalidate()`. **Revert the
 * `repository.presentationState.collect` block in `FileItemViewerViewModel`'s `init` and the first
 * two tests below go red**: nothing re-projects, so the concealed page stays in `entries` and the
 * concealed *open* page stays `Ready`.
 *
 * `VaultRepository` and `VaultLockManager` are mocked for the reason
 * `MetadataCleanupStaleSnapshotTest` gives: their real construction chains open AndroidKeyStore and
 * the vault file, neither of which exists under Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileItemViewerConcealmentTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: VaultRepository
    private lateinit var lockManager: VaultLockManager
    private lateinit var presentation: MutableStateFlow<VaultPresentationState>

    /** What the projection currently shows. Reassigned mid-test to stand for a conceal. */
    private var live: List<VaultItem> = emptyList()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mock()
        lockManager = mock()
        presentation = MutableStateFlow(VaultPresentationState.Locked)
        whenever(repository.presentationState).thenReturn(presentation)
        // An answer, not a fixed return: the whole point is that the projection changes underneath
        // a screen that has already read it once.
        whenever(repository.visibilitySnapshot()).thenAnswer { snapshot(live) }
        whenever(lockManager.lockState).thenReturn(
            MutableStateFlow<LockState>(
                LockState.Unlocked(
                    dek = ByteArray(32),
                    vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        MediaViewerFocus.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `a page concealed while the viewer is open stops being swipeable`() = runTest(dispatcher) {
        val open = fileItem("open")
        val other = fileItem("other")
        live = listOf(open, other)
        val viewModel = readyViewer(open, listOf(open, other))
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.entries.size, "control: both pages are live to begin with")

        // The reveal window closed: `other` is under a lease again, so the projection no longer
        // carries it. Nothing the user did, and no write this screen performed.
        live = listOf(open)
        emitProjection()
        advanceUntilIdle()

        assertEquals(
            listOf(open.id),
            viewModel.state.value.entries.map { it.item.id },
            "the concealed page must leave the pager rather than stay mounted and decryptable",
        )
        assertEquals(FileViewerPhase.Ready, viewModel.state.value.phase, "the page still open is unaffected")
    }

    @Test
    fun `the open page being concealed closes the viewer`() = runTest(dispatcher) {
        val open = fileItem("open")
        val other = fileItem("other")
        live = listOf(open, other)
        val viewModel = readyViewer(open, listOf(open, other))
        advanceUntilIdle()

        live = listOf(other)
        emitProjection()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(FileViewerPhase.Error, state.phase)
        assertTrue(state.exitRequested, "the route must close rather than clamp to a neighbouring page")
        assertTrue(state.entries.isEmpty())
        assertEquals(R.string.files_viewer_out_of_scope, state.outcomeMessage)
        assertFalse(
            viewerContentMayCompose(state, dekAvailable = true),
            "and the renderer must not stay mounted even though the session key is still borrowable",
        )
    }

    /**
     * Regression for 7-1. The staleness check was OR'd across two ids — the page on screen *and*
     * the page the route launched with — so paging on from a revealed file to a sibling that was
     * never hidden, then sitting past the 120 s reveal deadline, closed the viewer with
     * `files_viewer_out_of_scope` over a photo that was still perfectly live. **Restore the
     * `entries.none { it.item.id.toString() == itemId.toString() } ||` disjunct in `projectCurrent`
     * and this goes red**: the launch item leaves the projection, the first disjunct fires on its
     * own, and the viewer exits.
     *
     * Nothing is released by keying off the open page. The remaining disjunct still fails closed
     * when the page actually on screen is concealed — that is the test above — and a concealed
     * sibling has already been dropped from `entries` by the projection either way.
     */
    @Test
    fun `losing the launch page while looking at a live sibling does not close the viewer`() =
        runTest(dispatcher) {
            val open = fileItem("open")
            val other = fileItem("other")
            live = listOf(open, other)
            val viewModel = readyViewer(open, listOf(open, other))
            advanceUntilIdle()

            // The user swipes on. `updateSelectedPage` writes `selectedPageId` and never `itemId`,
            // which is the launch key `MediaViewerFocus.take` was called with.
            viewModel.updateSelectedPage(other.id.toString())
            assertEquals(other.id.toString(), viewModel.state.value.selectedPageId)

            // The reveal window closes on the file they *came from*, not the one they are looking at.
            live = listOf(other)
            emitProjection()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(
                FileViewerPhase.Ready,
                state.phase,
                "the page on screen was never concealed, so there is nothing to evict the user from",
            )
            assertFalse(state.exitRequested)
            assertEquals(
                listOf(other.id),
                state.entries.map { it.item.id },
                "the concealed launch page still leaves the pager — it is only the exit that was wrong",
            )
        }

    @Test
    fun `a projection arriving before the reveal is answered leaves the gate alone`() = runTest(dispatcher) {
        // The control. A collector that re-projected on every emission regardless of phase would
        // empty the screen while the biometric prompt is still up — and `completeReveal` would then
        // mount a pager with no pages. `revalidate` is Ready-only, and this is what that buys.
        val open = fileItem("open")
        live = listOf(open)
        val viewModel = buildViewer(open, listOf(open))
        advanceUntilIdle()
        assertEquals(FileViewerPhase.Authenticating, viewModel.state.value.phase)

        live = emptyList()
        emitProjection()
        advanceUntilIdle()

        assertEquals(FileViewerPhase.Authenticating, viewModel.state.value.phase)
        assertEquals(1, viewModel.state.value.entries.size)
        assertFalse(
            viewerContentMayCompose(viewModel.state.value, dekAvailable = true),
            "nothing is decrypted at this phase regardless — that is the gate this control protects",
        )
    }

    @Test
    fun `an unchanged projection is not treated as a concealment`() = runTest(dispatcher) {
        // The second control: the flow re-emits on every save and every lease boundary, most of
        // which change nothing. A viewer that closed on any emission would be unusable.
        val open = fileItem("open")
        live = listOf(open)
        val viewModel = readyViewer(open, listOf(open))
        advanceUntilIdle()

        emitProjection()
        emitProjection()
        advanceUntilIdle()

        assertEquals(FileViewerPhase.Ready, viewModel.state.value.phase)
        assertEquals(1, viewModel.state.value.entries.size)
    }

    private fun emitProjection() {
        // A fresh snapshot object each time: StateFlow drops a value equal to the one it holds, and
        // a lease boundary that changes nothing must still reach the collector.
        presentation.value = VaultPresentationState.Unlocked(mock())
    }

    private fun buildViewer(open: VaultItem, launch: List<VaultItem>): FileItemViewerViewModel {
        MediaViewerFocus.request(
            open.id,
            MediaViewerFocusRequest(
                scope = MediaViewerScope.GlobalSearch,
                visibleOrderedItemIds = launch.map { it.id },
                projectionVersion = 1L,
            ),
        )
        return FileItemViewerViewModel(
            repository = repository,
            lockManager = lockManager,
            fileBlobStore = mock(),
            savedStateHandle = SavedStateHandle(mapOf("itemId" to open.id.toString())),
        )
    }

    /** A viewer past its reveal challenge — the state 3-1 is about. */
    private fun readyViewer(open: VaultItem, launch: List<VaultItem>): FileItemViewerViewModel {
        val viewModel = buildViewer(open, launch)
        whenever(lockManager.unlockedSessionToken()).thenReturn(7L)
        whenever(lockManager.sessionKeyOrNull(7L)).thenReturn(mock<SessionKey>())
        viewModel.completeReveal(viewModel.beginReveal(), RevealAuth.RevealOutcome.Success)
        assertEquals(FileViewerPhase.Ready, viewModel.state.value.phase)
        return viewModel
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

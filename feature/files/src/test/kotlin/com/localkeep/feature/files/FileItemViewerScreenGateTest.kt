package com.localkeep.feature.files

import com.localkeep.core.model.File
import com.localkeep.core.model.MediaViewerEntry
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileItemViewerScreenGateTest {
    private val entry = MediaViewerEntry(
        item = VaultItem(
            id = UUID.randomUUID(),
            title = "photo",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = File(filename = "photo.jpg", mime = "image/jpeg", blobRef = "blob"),
        ),
        file = File(filename = "photo.jpg", mime = "image/jpeg", blobRef = "blob"),
        folderPath = emptyList(),
    )

    @Test
    fun `renderer stays unmounted before successful authentication`() {
        val state = FileItemViewerUiState(
            entries = listOf(entry),
            phase = FileViewerPhase.Authenticating,
        )

        assertFalse(viewerContentMayCompose(state, dekAvailable = true))
    }

    @Test
    fun `renderer mounts only for ready state with a private dek`() {
        val state = FileItemViewerUiState(
            entries = listOf(entry),
            phase = FileViewerPhase.Ready,
        )

        assertFalse(viewerContentMayCompose(state, dekAvailable = false))
        assertTrue(viewerContentMayCompose(state, dekAvailable = true))
    }

    @Test
    fun `exit request prevents a stale renderer from remaining mounted`() {
        val state = FileItemViewerUiState(
            entries = listOf(entry),
            phase = FileViewerPhase.Ready,
            exitRequested = true,
        )

        assertFalse(viewerContentMayCompose(state, dekAvailable = true))
    }

    @Test
    fun `a cancelled prompt is not reported as a rejected credential`() {
        // The user pressed Cancel. Saying "not accepted" tells them their credential was refused,
        // which never happened — and on a fingerprint sensor that is the difference between "try
        // again" and "something is wrong with my finger".
        val state = FileItemViewerUiState(
            entries = listOf(entry),
            phase = FileViewerPhase.Denied,
            outcomeMessage = R.string.files_viewer_auth_cancelled,
        )

        assertEquals(R.string.files_viewer_auth_cancelled, viewerGateMessage(state))
    }

    @Test
    fun `a gate with no recorded outcome still falls back to the phase`() {
        // The control: without this, "always return outcomeMessage" would pass the test above and
        // render a blank body for any gate reached before an attempt was classified.
        assertEquals(
            R.string.files_viewer_auth_unavailable,
            viewerGateMessage(FileItemViewerUiState(entries = listOf(entry), phase = FileViewerPhase.Unavailable)),
        )
        assertEquals(
            R.string.files_viewer_auth_denied,
            viewerGateMessage(FileItemViewerUiState(entries = listOf(entry), phase = FileViewerPhase.Denied)),
        )
    }
}

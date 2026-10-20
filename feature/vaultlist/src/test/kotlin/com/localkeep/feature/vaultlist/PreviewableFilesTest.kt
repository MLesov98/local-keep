package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Attachment
import com.localkeep.core.model.File
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the trash preview is allowed to offer for opening.
 *
 * A File item keeps its file in its own `File` payload; every other type keeps files in
 * `VaultItem.attachments`. The preview read only `attachments`, so a photograph moved to the trash
 * — a File item, whose whole content is that photograph — opened a dialog saying "No files attached
 * to this entry" with nothing to tap. Reported 2026-09-03.
 *
 * The control is [`a Login's attachments are offered unchanged`]: it must stay green, or a change
 * that simply offered every item its own id would read as a pass.
 */
class PreviewableFilesTest {

    private fun item(content: ItemContent, attachments: List<Attachment> = emptyList()) = VaultItem(
        id = UUID.randomUUID(),
        title = "Passport",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        attachments = attachments,
        content = content,
    )

    private fun attachment(name: String) = Attachment(
        id = UUID.randomUUID(),
        filename = name,
        mime = "image/png",
        sizeBytes = 10,
        blobRef = "blob-$name",
    )

    @Test
    fun `a File item offers its own payload`() {
        val subject = item(
            File(
                filename = "passport.png",
                mime = "image/png",
                sizeBytes = 2048,
                blobRef = "blob-payload",
                thumbnailRef = "thumb-payload",
            ),
        )

        val files = previewableFiles(subject)

        assertEquals(1, files.size)
        // Addressed by the item's own id, the same way `FileItemViewerScreen` projects one — so the
        // id the row hands to the viewer resolves in both places.
        assertEquals(subject.id, files[0].id)
        assertEquals("passport.png", files[0].filename)
        assertEquals("blob-payload", files[0].blobRef)
        assertEquals("thumb-payload", files[0].thumbnailRef)
    }

    @Test
    fun `a File item lists its payload before its attachments`() {
        val extra = attachment("scan-back.png")
        val subject = item(
            File(filename = "front.png", mime = "image/png", blobRef = "blob-front"),
            attachments = listOf(extra),
        )

        val files = previewableFiles(subject)

        assertEquals(listOf("front.png", "scan-back.png"), files.map { it.filename })
    }

    @Test
    fun `a File item with no blob offers nothing`() {
        // A half-written File item — the picker was cancelled before anything was encrypted. Naming
        // a blob that does not exist would send the viewer to a decrypt that cannot succeed.
        val files = previewableFiles(item(File(filename = "front.png", blobRef = "")))

        assertTrue(files.isEmpty(), "expected nothing previewable, got $files")
    }

    @Test
    fun `a Login's attachments are offered unchanged`() {
        val one = attachment("id-front.png")
        val two = attachment("id-back.png")

        val files = previewableFiles(item(Login(username = "me"), listOf(one, two)))

        assertEquals(listOf(one, two), files)
    }

    @Test
    fun `a Note with nothing attached offers nothing`() {
        assertTrue(previewableFiles(item(Note(body = "hi"))).isEmpty())
    }
}

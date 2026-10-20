package com.localkeep.core.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaViewerProjectionTest {
    private val now = Instant.EPOCH

    @Test
    fun `root scope keeps only unfiled standalone files in rendered order`() {
        val folder = UUID.randomUUID()
        val first = item(folderId = null, title = "first")
        val nested = item(folderId = folder, title = "nested")
        val note = VaultItem(
            id = UUID.randomUUID(), title = "note", createdAt = now, updatedAt = now, content = Note("body"),
        )

        val result = projectMediaViewerEntries(
            orderedItems = listOf(nested, note, first),
            folders = listOf(Folder(folder, "Work")),
            scope = MediaViewerScope.RootUnfiled,
        )

        assertEquals(listOf(first.id), result.map { it.item.id })
    }

    @Test
    fun `folder scope includes descendants and keeps each file path`() {
        val root = UUID.randomUUID()
        val child = UUID.randomUUID()
        val rootFile = item(folderId = root, title = "root")
        val childFile = item(folderId = child, title = "child")
        val outside = item(folderId = null, title = "outside")
        val folders = listOf(
            Folder(root, "Work"),
            Folder(child, "Receipts", parentId = root),
        )

        val result = projectMediaViewerEntries(
            orderedItems = listOf(rootFile, outside, childFile),
            folders = folders,
            scope = MediaViewerScope.Folder(root),
        )

        assertEquals(listOf(rootFile.id, childFile.id), result.map { it.item.id })
        assertEquals(listOf("Work"), result.first().folderPath)
        assertEquals(listOf("Work", "Receipts"), result.last().folderPath)
    }

    @Test
    fun `global search respects the captured visible id order and excludes non-files`() {
        val first = item(folderId = null, title = "same")
        val second = item(folderId = null, title = "same")
        val note = VaultItem(
            id = UUID.randomUUID(), title = "same", createdAt = now, updatedAt = now, content = Note("body"),
        )

        val result = projectMediaViewerEntries(
            orderedItems = listOf(second, note, first),
            folders = emptyList(),
            scope = MediaViewerScope.GlobalSearch,
            visibleOrderedItemIds = listOf(first.id, note.id, second.id),
        )

        assertEquals(listOf(first.id, second.id), result.map { it.item.id })
    }

    @Test
    fun `shared ordering is deterministic and keeps direct folder files before descendants`() {
        val folder = UUID.randomUUID()
        val direct = item(folderId = folder, title = "same")
        val descendant = item(folderId = UUID.randomUUID(), title = "same")
        val ordered = orderVaultItems(listOf(descendant, direct), directFolderId = folder)

        assertEquals(listOf(direct.id, descendant.id), ordered.map { it.id })
        assertEquals(
            orderVaultItems(listOf(direct, descendant)).map { it.id },
            orderVaultItems(listOf(descendant, direct)).map { it.id },
        )
    }

    private fun item(folderId: UUID?, title: String): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        folderId = folderId,
        createdAt = now,
        updatedAt = now,
        content = File(filename = "$title.jpg", mime = "image/jpeg", blobRef = "blob-$title"),
    )
}

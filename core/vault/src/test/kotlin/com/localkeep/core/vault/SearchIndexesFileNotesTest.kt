package com.localkeep.core.vault

import com.localkeep.core.model.File
import com.localkeep.core.model.VaultItem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A File's `notes` field must be searchable, like `Login.notes`, `Card.notes` and `Note.body`.
 *
 * `File.notes` arrived with schema 8 (4C-11, 2026-09-21). It is written into the File editor's
 * "Folder, tags and more" section and shown on the file's details sheet, and the vault list's own
 * search hint promises "notes" — so a note the haystack could not see would be prose the user
 * typed and then could not find, which is the same shape as the gap adding the field closed.
 * Nothing about it is masked: the filename and MIME beside it are already indexed.
 *
 * Calls `matchesSearchQuery`, the same predicate `VaultRepository.search` filters on, with the
 * needle pre-normalised exactly as `search` normalises it.
 */
class SearchIndexesFileNotesTest {

    private fun fileWith(notes: String): VaultItem = VaultItem(
        id = java.util.UUID.randomUUID(),
        title = "Passport scan",
        content = File(
            filename = "passport.jpg",
            mime = "image/jpeg",
            sizeBytes = 918_273L,
            blobRef = "blob-1",
            notes = notes,
        ),
        createdAt = java.time.Instant.EPOCH,
        updatedAt = java.time.Instant.EPOCH,
    )

    private fun VaultItem.matches(query: String): Boolean =
        matchesSearchQuery(
            query.trim().lowercase(java.util.Locale.ROOT).foldDiacritics(),
            folderPath = emptyList(),
        )

    @Test
    fun `file notes are searchable`() {
        val item = fileWith("Renewal due March 2029, the London office holds the older copy")
        assertTrue(
            item.matches("renewal due"),
            "a File's notes must enter the search haystack, same as Login.notes and Card.notes",
        )
        assertTrue(item.matches("london"), "any word in the notes must match, not just a prefix")
    }

    @Test
    fun `a file with no note is unaffected`() {
        val item = fileWith("")
        assertTrue(item.matches("passport"), "the filename and title still match")
        assertTrue(!item.matches("renewal"), "an empty note must not widen what matches")
    }
}

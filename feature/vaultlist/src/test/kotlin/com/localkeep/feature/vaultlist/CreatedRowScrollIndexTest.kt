package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Making a folder on a long Folders screen scrolls to the row it made.
 *
 * The new row sorts into place, so on a list longer than the screen it could land off it, the name
 * well emptied, and nothing at all showed that anything had happened.
 */
class CreatedRowScrollIndexTest {

    private fun row(name: String) = FolderRow(Folder(id = UUID.randomUUID(), name = name), itemCount = 0)

    private val rows = listOf(row("A"), row("B"), row("C"))

    @Test
    fun scrollsToTheCreatedRow() {
        assertEquals(2, createdRowScrollIndex(rows, rows[2].folder.id, leadingItems = 0))
    }

    @Test
    fun countsTheItemsAboveTheFolderRows() {
        assertEquals(3, createdRowScrollIndex(rows, rows[2].folder.id, leadingItems = 1))
    }

    @Test
    fun doesNothingBeforeTheRowExists() {
        // The id is published a beat before the re-read that adds the row; an index computed from
        // a row that is not there would scroll to whatever sits at that position.
        assertNull(createdRowScrollIndex(rows, UUID.randomUUID(), leadingItems = 0))
    }

    @Test
    fun doesNothingWhenNothingWasCreated() {
        assertNull(createdRowScrollIndex(rows, null, leadingItems = 0))
    }
}

package com.localkeep.feature.itemedit

import com.localkeep.core.model.Attachment
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Saving an edit keeps the attachments the attachments screen changed while the form was open.
 *
 * O4-01, 2026-10-02 (blocker, data loss): open an item, Edit, change a field, tap "Manage
 * attachments", attach a passport scan, come back, Save. The editor wrote the attachment list it
 * had loaded — before the scan existed — so the record was dropped with outcome UPDATED and no
 * error, and `BlobGarbageCollector` deleted the unreferenced blob at the next unlock. The mirror
 * case brought back a file the user had just removed there.
 *
 * Asserted on [attachmentsForEdit] because `ItemEditViewModel` cannot be built on a JVM (see
 * `ItemEditViewModelTest`); the instrumented
 * `anAttachmentAddedWhileTheFormIsDirtySurvivesItsSave` drives the whole save.
 */
class EditKeepsAttachmentsMadeElsewhereTest {

    private fun attachment(name: String) = Attachment(id = UUID.randomUUID(), filename = name, blobRef = name)

    private val scan = attachment("passport.jpg")
    private val receipt = attachment("receipt.pdf")
    private val page = attachment("document-1.jpg")

    @Test
    fun `a file attached while the form was open survives the save`() {
        val written = attachmentsForEdit(stored = listOf(scan), loaded = emptyList(), pending = emptyList())

        assertEquals(listOf(scan), written, "the editor wrote its load-time list over the new attachment")
    }

    @Test
    fun `a file removed while the form was open stays removed`() {
        val written = attachmentsForEdit(stored = emptyList(), loaded = listOf(receipt), pending = emptyList())

        assertEquals(emptyList(), written, "the save resurrected an attachment the user deleted")
    }

    /** The editor's own photographs are still appended — the reason the list is written at all. */
    @Test
    fun `the editor's pending photographs are appended to what is stored`() {
        val written = attachmentsForEdit(stored = listOf(scan), loaded = emptyList(), pending = listOf(page))

        assertEquals(listOf(scan, page), written)
    }

    /** A save repeated after a conflict reload finds its photographs already stored. */
    @Test
    fun `a photograph already on the stored item is not added twice`() {
        val written = attachmentsForEdit(stored = listOf(page), loaded = listOf(page), pending = listOf(page))

        assertEquals(listOf(page), written)
    }

    /** The control: with nothing stored to read, the loaded list is what there is. */
    @Test
    fun `with no stored record the loaded list is kept`() {
        val written = attachmentsForEdit(stored = null, loaded = listOf(receipt), pending = listOf(page))

        assertEquals(listOf(receipt, page), written)
    }
}

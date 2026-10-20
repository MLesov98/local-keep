package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A row whose item carries attachments has to say so.
 *
 * 4C-12, on a device 2026-09-20: `Wifi note (BW)` with two encrypted files attached rendered as
 * monogram + title + "Secure note" and announced `desc='Wifi note (BW), Secure note'` — identical
 * to every other note. The item *detail* has shown a paperclip and "2 files" all along, so the data
 * was there and only the row omitted it.
 *
 * Drawn **and** spoken. The row already carries one silent marker (the type icon, recorded in
 * `VaultItemRow`'s own comment as a known gap) and a second one would widen that gap rather than
 * close this finding for a screen-reader user.
 *
 * Source-scanned in this module's established style ([NewFolderCardAutoFocusTest] records the full
 * reasoning): the claim is which resources a composable reaches for, which needs a device to
 * observe and which the source answers exactly.
 */
class RowAttachmentIndicatorTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultItemRow.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    @Test
    fun `the row counts its item's attachments`() {
        assertTrue(
            "item.attachments.size" in text(),
            "the row must read the attachment count off the item it is drawing",
        )
    }

    @Test
    fun `it draws the paperclip, not some other glyph`() {
        // LkIcons.kt owns glyph meaning and its KDoc calls Attachment "a paperclip: file
        // attachments". Borrowing another one would name the wrong act — the failure that had
        // LkIcons.Eye standing in for a camera at seven call sites.
        assertTrue("LkIcons.Attachment" in text(), "the indicator must be the paperclip")
    }

    @Test
    fun `and it says it out loud`() {
        val code = text()
        assertTrue(
            "R.plurals.vaultlist_row_attachments" in code,
            "the count must be a plural resource, never \"\$n file\" assembled in Kotlin",
        )
        assertTrue(
            "R.string.vaultlist_row_description_attachments" in code,
            "the row's contentDescription must carry the attachment clause, or the indicator is a " +
                "marker a screen reader cannot see",
        )
    }
}

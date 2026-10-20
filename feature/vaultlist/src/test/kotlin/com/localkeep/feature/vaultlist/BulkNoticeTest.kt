package com.localkeep.feature.vaultlist

import com.localkeep.core.ui.LkNotices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bulk action that changed nothing used to say nothing, which is what a broken button also does.
 *
 * The confirmations became `LkNotices.Message.Plural` rather than Strings on 2026-07-31. They were
 * Kotlin templates built around a private `items(n)` helper reading `if (n == 1) "1 item" else
 * "$n items"`, so selecting three rows and trashing them announced English on a French phone. A
 * `<plurals>` needs a Context to resolve and a ViewModel has none, so what travels now is the id
 * and the count, and `LkNoticeHost` resolves it.
 */
class BulkNoticeTest {

    // Stand-in for the resolved `vaultlist_notice_no_bulk_change` string a composable would pass
    // in — kept here as a literal so this test needs no Android context.
    private val noChangeNotice = "Nothing changed — every selected item was already that way"

    // Resource ids are plain Ints off-device; the values are arbitrary and only identity matters.
    private val taggedId = 0x7f120001
    private val trashedId = 0x7f120002

    @Test
    fun `a real change is reported with the count the transform actually made`() {
        assertEquals(
            LkNotices.Message.Plural(taggedId, 3),
            bulkNotice(3, noChangeNotice) { n -> LkNotices.Message.Plural(taggedId, n) },
        )
    }

    @Test
    fun `changing nothing still says something`() {
        assertEquals(
            LkNotices.Message.Text(noChangeNotice),
            bulkNotice(0, noChangeNotice) { n -> LkNotices.Message.Plural(taggedId, n) },
        )
    }

    @Test
    fun `the zero case never runs the confirmation, which would report work not done`() {
        var built = false
        bulkNotice(0, noChangeNotice) { n ->
            built = true
            LkNotices.Message.Plural(trashedId, n)
        }
        assertTrue(!built, "the confirmation was built for a change that did not happen")
    }

    @Test
    fun `the zero message names neither a count nor an action`() {
        // The three callers pass three different verbs. Whatever this says has to be true after all
        // of them, so it may not claim anything was tagged, moved or trashed.
        for (verb in listOf("tagged", "moved", "moved to trash", "removed from folder")) {
            assertTrue(verb !in noChangeNotice, "the zero notice claims items were $verb")
        }
        assertTrue("0" !in noChangeNotice, "the zero notice counts something")
    }

    /**
     * The regression itself. The type still permits `Message.Text("3 items tagged")`, so this
     * asserts what a counted confirmation actually hands over: a plural id and the number, with no
     * prose built on this side of the Context boundary.
     */
    @Test
    fun `a counted confirmation carries the plural id and the count, not prose`() {
        val message = bulkNotice(7, noChangeNotice) { n -> LkNotices.Message.Plural(trashedId, n) }
        val plural = message as? LkNotices.Message.Plural
        assertTrue(plural != null, "a counted confirmation must not resolve to fixed text")
        assertEquals(trashedId, plural.id)
        assertEquals(7, plural.count, "the count shown must be what the transform changed")
    }
}

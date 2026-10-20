package com.localkeep.feature.importwizard

import com.localkeep.core.import.ImportMerger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A preview row's second line must say which thing the row collided with.
 *
 * F19 split the *headline* — "1 already looks like an entry you have" is a claim about the vault and
 * was being made about rows that merely repeated an earlier row of the same export. The per-row
 * reason underneath it was not split, so an in-file repeat went on reading "you already have a newer
 * copy" about an entry the vault had never held. The headline said one thing and the row said the
 * opposite, on the same screen. Found on a device by the pre-iOS sweep 2026-09-11.
 *
 * Nothing held the two halves together, which is why only one of them was fixed. These cases are
 * that hold.
 */
class ImportRowReasonTest {

    @Test
    fun `a row that repeats an earlier row of the same file does not claim the vault has it`() {
        val reason = assertNotNull(
            rowReason(ImportMerger.Disposition.ALREADY_CURRENT, collidesWithVault = false),
        )
        assertEquals(R.string.import_disposition_already_in_file, reason.res)
    }

    @Test
    fun `a row the vault really does have keeps the vault phrasing`() {
        val reason = assertNotNull(
            rowReason(ImportMerger.Disposition.ALREADY_CURRENT, collidesWithVault = true),
        )
        assertEquals(R.string.import_disposition_already_current, reason.res)
    }

    @Test
    fun `an update against an earlier row of the same file does not claim the vault has it`() {
        val reason = assertNotNull(
            rowReason(ImportMerger.Disposition.UPDATES_EXISTING, collidesWithVault = false),
        )
        assertEquals(R.string.import_disposition_updates_earlier_row, reason.res)
    }

    /** The one string of the four that names what it hit, and so takes a second format argument. */
    @Test
    fun `an update against the vault names the entry it collides with`() {
        val reason = assertNotNull(
            rowReason(ImportMerger.Disposition.UPDATES_EXISTING, collidesWithVault = true),
        )
        assertEquals(R.string.import_disposition_updates_existing, reason.res)
        assertTrue(reason.namesCollision, "this is the only case that takes the collidesWith argument")
    }

    /**
     * "You already have a newer copy" is a comparison, and a CSV or Aegis export carries no dates to
     * compare with — its rows are stamped at the import instant. The merge keeps the vault's copy
     * (S1), and the row has to say that rather than claim a recency it cannot know.
     */
    @Test
    fun `an undated export does not claim the vault copy is newer`() {
        val reason = assertNotNull(
            rowReason(
                ImportMerger.Disposition.ALREADY_CURRENT,
                collidesWithVault = true,
                timestampsKnown = false,
            ),
        )
        assertEquals(R.string.import_disposition_undated_keeps_yours, reason.res)
    }

    /** A same-file repeat says the same thing whatever the format's dates are worth. */
    @Test
    fun `an undated in-file repeat keeps the in-file phrasing`() {
        val reason = assertNotNull(
            rowReason(
                ImportMerger.Disposition.ALREADY_CURRENT,
                collidesWithVault = false,
                timestampsKnown = false,
            ),
        )
        assertEquals(R.string.import_disposition_already_in_file, reason.res)
    }

    /** A new row has nothing to explain; the row shows its own subtitle. */
    @Test
    fun `a new row has no reason line`() {
        assertNull(rowReason(ImportMerger.Disposition.NEW, collidesWithVault = false))
        assertNull(rowReason(ImportMerger.Disposition.NEW, collidesWithVault = true))
    }

    /** If any two of these ever resolve to the same string, the distinction above is cosmetic. */
    @Test
    fun `the five reasons are five distinct strings`() {
        val ids = listOf(
            R.string.import_disposition_already_in_file,
            R.string.import_disposition_already_current,
            R.string.import_disposition_updates_earlier_row,
            R.string.import_disposition_updates_existing,
            R.string.import_disposition_undated_keeps_yours,
        )
        assertEquals(ids.size, ids.toSet().size, "the five row reasons must be five distinct strings")
    }
}

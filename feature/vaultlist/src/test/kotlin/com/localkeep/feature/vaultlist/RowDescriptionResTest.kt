package com.localkeep.feature.vaultlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The vault row's screen-reader description picks up favourite state (UX sweep S2, spec §3).
 *
 * Until this branch existed the row announced only title and subtitle, so a TalkBack user could not
 * tell a favourited row from an ordinary one — the star is a bare image node and the state was
 * carried only in a custom-action name. These pin the resource choice without composing the row,
 * for the same reason as [FolderFilterStateTest]: composition needs Android, but the decision is a
 * pure function. The rendered wording ("…, favourite") is enforced per language by lint's
 * MissingTranslation gate and confirmed visually on a device via `uiautomator dump`.
 */
class RowDescriptionResTest {

    @Test
    fun `a favourited row uses the favourite description variant`() {
        assertEquals(R.string.vaultlist_row_description_favourite, rowDescriptionRes(favorite = true))
    }

    @Test
    fun `an ordinary row uses the plain description`() {
        assertEquals(R.string.vaultlist_row_description, rowDescriptionRes(favorite = false))
    }

    @Test
    fun `the two states resolve to different strings`() {
        // The whole point: favourite state must be distinguishable. If a refactor ever collapsed
        // these back to one resource the announcement would stop carrying the state again.
        assertNotEquals(rowDescriptionRes(favorite = true), rowDescriptionRes(favorite = false))
    }
}

package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An item says when it arrived and when it last changed.
 *
 * 2026-09-15: no created, added or updated date anywhere in the app — not on the detail screen
 * (`shots/132-bw-item-detail.png`), not in the editor, not in its filing sheet
 * (`shots/133-details-sheet.png`). Meanwhile the health dashboard ships an `Old passwords (90+
 * days)` category whose entire basis is a date the user cannot see, and the import wizard's merge
 * decisions turn on the same timestamps (F-36). A screen cannot ask someone to act on a date it
 * will not show them.
 *
 * FAILS WITHOUT THE FIX: `ItemEditUiState` carried no `createdAt`/`updatedAt`/`passwordChangedAt`
 * at all, so `loadItem` dropped every one of them on the floor and no screen could have drawn them.
 */
class ItemDetailShowsItsDatesTest {

    private val viewModel = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt").readText()
    private val detail = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt").readText()

    @Test
    fun `a new item has no dates, because it has not been written`() {
        val fresh = ItemEditUiState()
        assertTrue(fresh.createdAt == null && fresh.updatedAt == null && fresh.passwordChangedAt == null)
    }

    @Test
    fun `loading an item keeps the dates the vault holds`() {
        val load = viewModel.substringAfter("fun loadItem(id: String) {", "").substringBefore("\n    fun ")
        assertTrue("createdAt = item.createdAt" in load, "loadItem drops the item's creation date")
        assertTrue("updatedAt = item.updatedAt" in load, "loadItem drops the item's last-changed date")
        assertTrue(
            "passwordChangedAt = content.passwordChangedAt" in load,
            "loadItem drops the date the health dashboard's 'old password' bucket counts from",
        )
    }

    @Test
    fun `the detail screen draws them, through the formatter the password history already uses`() {
        val group = detail.substringAfter("val created = state.createdAt", "").substringBefore("\"Never left the device\"")
        assertTrue("R.string.itemedit_detail_dates_heading" in group)
        assertTrue("R.string.itemedit_detail_added_label" in group)
        assertTrue("R.string.itemedit_detail_updated_label" in group)
        assertTrue("R.string.itemedit_detail_password_changed_label" in group)
        // One date format for the whole screen: a localised medium date, no hand-written pattern.
        assertTrue(group.count { it == '\n' } > 0 && "asLocalDate()" in group)
    }

    /**
     * Two absences that are deliberate. An importer never sets `passwordChangedAt` (see
     * `ItemMerge`), and no other type has a password at all — so the row is shown only where the
     * vault genuinely recorded one. "Password changed: —" is a worse answer than no row.
     */
    @Test
    fun `the password-changed row is for Logins that actually carry the date`() {
        val group = detail.substringAfter("val created = state.createdAt", "").substringBefore("\"Never left the device\"")
        assertTrue("?.takeIf { state.type == ItemType.LOGIN }" in group)
        assertTrue("if (passwordChanged != null)" in group)
    }
}

package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An item's favourite flag can be set from the item (2026-09-20, sweep 2B-6 and 4A-F5).
 *
 * The bug this closes: the flag lived only on the vault list's row long-press and overflow menu. A
 * full dump of the editor with "Folder, tags and more" expanded matched no node containing
 * `favourite`, `favorite` or `star`, and the detail screen — which the list announces as
 * "Amazon, emily@enpass.io, favorite" — held only Back, Move to trash, Edit and the field
 * sections: no control and no indicator. So the one screen showing the item said nothing about a
 * property of that item, and the only way to change it was to leave.
 *
 * The two halves behave differently on purpose, and that is what is pinned here: the editor's
 * checkbox is form state saved with everything else, while the detail's star lands on the tap,
 * because the detail screen has no Save button (the same argument `clearPasswordHistory` records).
 */
class FavouriteIsReachableFromTheItemTest {

    private val viewModel =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt").readText()
    private val screen =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
    private val detail =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt").readText()

    @Test
    fun `the flag is part of the editor's state`() {
        assertTrue(ItemEditUiState().favorite == false, "a new item starts unfavourited")
        assertEquals(true, ItemEditUiState(favorite = true).favorite)
    }

    /**
     * And it is content, so it survives the strip that decides "are there unsaved edits?" —
     * otherwise ticking the box would leave the form clean and Back would bin the change with no
     * discard prompt.
     */
    @Test
    fun `ticking the box is an unsaved edit`() {
        val plain = ItemEditUiState(title = "Amazon")
        assertTrue(plain.editableOnly() != plain.copy(favorite = true).editableOnly())
        assertTrue(plain.copy(favorite = true).editableOnly().favorite)
    }

    @Test
    fun `the editor loads it and saves it`() {
        assertTrue("favorite = item.favorite" in viewModel, "the editor never reads the stored flag")
        assertEquals(
            2,
            Regex("favorite = snapshot\\.favorite").findAll(viewModel).count(),
            "both save branches — a new item and an edit — must write the flag",
        )
    }

    @Test
    fun `the editor offers the control under Folder, tags and more`() {
        val more = screen.substringAfter("TagEditor(state, viewModel)", "")
            .substringBefore("RepromptRow(state, viewModel)")
        assertTrue(more.isNotEmpty(), "the more panel moved; this test no longer reads it")
        assertTrue("R.string.itemedit_favourite_checkbox_label" in more)
        assertTrue("viewModel::onFavoriteChanged" in more)
    }

    /**
     * And the collapsed summary says so, since that summary is what makes collapsing the panel
     * safe — a flag set behind a closed disclosure with nothing naming it is the unreachability
     * this change is undoing, one level down.
     */
    @Test
    fun `the collapsed summary names it`() {
        assertTrue("R.string.itemedit_edit_more_summary_favourite" in screen)
    }

    @Test
    fun `the detail screen shows it and toggles it`() {
        val bar = detail.substringAfter("R.string.itemedit_detail_back", "")
            .substringBefore("R.string.itemedit_detail_trash_icon_label")
        assertTrue(bar.isNotEmpty(), "the detail top bar moved; this test no longer reads it")
        assertTrue("LkIcons.Star" in bar, "the detail screen still has no favourite indicator")
        assertTrue("viewModel::toggleFavoriteNow" in bar, "the indicator does not toggle")
        assertTrue(
            "R.string.itemedit_detail_favourite_remove" in bar &&
                "R.string.itemedit_detail_favourite_add" in bar,
            "the control's label does not carry its state, so a screen reader gets nothing",
        )
    }

    /**
     * The detail's toggle writes through instead of marking something dirty — there is no Save on
     * that screen to land it — and deliberately does not bump `updatedAt`: marking an item is not
     * editing it.
     */
    @Test
    fun `the detail toggle lands on the tap`() {
        val fn = viewModel.substringAfter("fun toggleFavoriteNow() {", "").substringBefore("\n    }")
        assertTrue(fn.isNotEmpty(), "toggleFavoriteNow is gone")
        assertTrue("repository.updateWith(id) { it.copy(favorite = !it.favorite) }" in fn)
        assertTrue("copy(favorite = committed.favorite)" in fn, "the star is not mirrored from the write")
    }
}

package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A word typed into a phrase slot but not yet committed is unsaved work, so Back asks before it goes.
 *
 * `editableOnly()` strips `phraseDraft` as capture-screen presentation, which is right while the
 * draft only mirrors its slot (focusing a slot loads its word). But a draft that has been *typed
 * over* is the one place the user's input lives only there: [ItemEditUiState.withPendingPhraseDraftCommitted]
 * is what Save already uses to rescue it. Back compared the stripped form against the baseline, saw
 * no difference, and dropped the word without a prompt. The comparison now rescues the draft the way
 * Save does, so the word counts as an edit — and a draft that merely mirrors its slot still does not.
 *
 * Asserted on the state comparison [ItemEditViewModel.isDirty] runs, not through the ViewModel,
 * which needs a repository and a lock manager (the split `EmptyNewItemIsNotDirtyTest` records).
 */
class PhrasePendingWordIsUnsavedWorkTest {

    private val untouched = ItemEditUiState(
        type = ItemEditUiState.ItemType.PHRASE,
        phraseWords = List(12) { "" },
        phraseFocus = 0,
        phraseDraft = "",
    )

    @Test
    fun `a typed word that was never committed differs from the untouched phrase`() {
        val typed = untouched.copy(phraseDraft = "abandon")
        assertNotEquals(
            untouched.editableOnly(),
            typed.editableOnly(),
            "Back would drop the typed word without asking",
        )
    }

    @Test
    fun `a typed correction over a stored word differs from the stored phrase`() {
        val stored = untouched.copy(phraseWords = List(12) { "ability" }, phraseFocus = 3, phraseDraft = "ability")
        val corrected = stored.copy(phraseDraft = "absent")
        assertNotEquals(stored.editableOnly(), corrected.editableOnly())
    }

    /** The control: focusing a slot loads its word into the draft, and that is not an edit. */
    @Test
    fun `a draft that only mirrors its slot is not an edit`() {
        val stored = untouched.copy(phraseWords = List(12) { "ability" }, phraseFocus = -1, phraseDraft = "")
        val focused = stored.copy(phraseFocus = 3, phraseDraft = "ability")
        assertEquals(stored.editableOnly(), focused.editableOnly())
    }

    /** The control: a blank draft over a stored word is left to the checksum gate, never an edit by itself. */
    @Test
    fun `a cleared draft is not an edit`() {
        val stored = untouched.copy(phraseWords = List(12) { "ability" }, phraseFocus = -1, phraseDraft = "")
        val cleared = stored.copy(phraseFocus = 3, phraseDraft = "")
        assertEquals(stored.editableOnly(), cleared.editableOnly())
    }
}

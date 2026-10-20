package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The pending-phrase-draft flush that [ItemEditViewModel.save] runs before it reads the words.
 *
 * The hazard, reported by an adversarial review of the recovery-phrase item: `focusPhraseSlot` loads
 * a slot's word into `phraseDraft` to edit, but only `commitPhraseWord` (the autocomplete chip)
 * writes it back. A user who typed a correction into a focused slot and pressed Save without tapping
 * the chip had the edit dropped and the old word saved — silently, with Save still enabled. Here the
 * lost edit is a seed word, so the wrong phrase can be filed for a wallet. Same shape as the pending
 * folder name Save already flushes (2026-07-29). This is the one place that rule is written down.
 */
class PhraseDraftFlushOnSaveTest {

    private fun phrase(
        words: List<String>,
        focus: Int,
        draft: String,
    ) = ItemEditUiState(
        type = ItemEditUiState.ItemType.PHRASE,
        phraseWords = words,
        phraseFocus = focus,
        phraseDraft = draft,
    )

    @Test
    fun `an edit typed into a focused slot but not chip-committed is kept, not dropped`() {
        val before = phrase(words = listOf("abandon", "ability", "able"), focus = 1, draft = "absent")
        val after = before.withPendingPhraseDraftCommitted()
        assertEquals(listOf("abandon", "absent", "able"), after.phraseWords)
        // Still in the field, and no longer pending: equal to its slot, so a second flush is a no-op.
        // It used to be emptied, which blanked the slot a refused save was naming (C1, 2026-10-04 —
        // see PhraseRefusedSaveKeepsTheTypedWordTest).
        assertEquals("absent", after.phraseDraft)
        assertSame(after, after.withPendingPhraseDraftCommitted())
    }

    @Test
    fun `focusing a slot without changing its word writes nothing`() {
        val before = phrase(words = listOf("abandon", "ability", "able"), focus = 1, draft = "ability")
        assertSame(before, before.withPendingPhraseDraftCommitted())
    }

    @Test
    fun `a blank draft is left to the checksum gate, never written as a blank slot`() {
        // A cleared field must not silently blank a filled slot on save; if the user meant to remove a
        // word the resulting short phrase fails its checksum and Save is refused, which is the signal.
        val before = phrase(words = listOf("abandon", "ability", "able"), focus = 1, draft = "")
        assertSame(before, before.withPendingPhraseDraftCommitted())
    }

    @Test
    fun `a fresh capture whose last word was chip-committed flushes nothing`() {
        // commitPhraseWord clears the draft and parks focus on the next gap (or -1 when full); either
        // way there is no pending edit to flush.
        val before = phrase(words = listOf("abandon", "ability", ""), focus = 2, draft = "")
        assertSame(before, before.withPendingPhraseDraftCommitted())
    }

    @Test
    fun `focus outside the grid is ignored`() {
        val before = phrase(words = listOf("abandon", "ability", "able"), focus = -1, draft = "absent")
        assertSame(before, before.withPendingPhraseDraftCommitted())
    }

    @Test
    fun `a non-phrase item is never rewritten by the phrase flush`() {
        val login = ItemEditUiState(
            type = ItemEditUiState.ItemType.LOGIN,
            phraseWords = listOf("abandon"),
            phraseFocus = 0,
            phraseDraft = "absent",
        )
        assertSame(login, login.withPendingPhraseDraftCommitted())
    }
}

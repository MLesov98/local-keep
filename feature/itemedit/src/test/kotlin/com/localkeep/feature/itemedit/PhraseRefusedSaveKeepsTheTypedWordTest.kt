package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A word typed into the focused slot stays where the user typed it, and Save tells the truth about it.
 *
 * Device pass 2026-10-04 (C1 finding 11): "zzzz" typed into slot 12 of a twelve-word grid that
 * already failed its checksum, keyboard hidden, Save tapped. The save was refused, correctly, with
 * "Slot 12 is not a BIP-39 word" — and the slot came back empty. `save()` flushes the pending draft
 * into the grid first ([PhraseDraftFlushOnSaveTest]), and the flush emptied the draft as it went; the
 * focused slot and the entry field both draw the draft, so the word the refusal was about vanished
 * from the screen that named it.
 *
 * And Save had looked enabled: its gate read the committed grid, which still held twelve real words
 * failing only their checksum — a warning, not a block — while the field showed what the press would
 * actually commit.
 */
class PhraseRefusedSaveKeepsTheTypedWordTest {

    private fun phrase(words: List<String>, focus: Int, draft: String) = ItemEditUiState(
        type = ItemEditUiState.ItemType.PHRASE,
        phraseWords = words,
        phraseWordCount = words.size,
        phraseFocus = focus,
        phraseDraft = draft,
    )

    /** Twelve real words whose checksum fails: saveable, with the coral warning (4C-14). */
    private val failingChecksum = List(12) { "abandon" }

    @Test
    fun `the flush leaves the typed word in the field it was typed into`() {
        val after = phrase(failingChecksum, focus = 11, draft = "zzzz").withPendingPhraseDraftCommitted()

        assertEquals("zzzz", after.phraseWords[11])
        assertEquals("zzzz", after.phraseDraft, "the refused word was cleared out of the focused slot")
    }

    @Test
    fun `Save is not offered while the typed word would refuse it`() {
        assertTrue(
            phrase(failingChecksum, focus = 11, draft = "zzzz").phraseSaveBlocked,
            "Save looked enabled over a word that is not on the BIP-39 list",
        )
    }

    /** The control: a real word typed in, not yet committed, is what a press would save. */
    @Test
    fun `a real word waiting in the field does not hold Save back`() {
        // abandon x11 + about is the canonical valid twelve-word phrase.
        assertFalse(phrase(failingChecksum, focus = 11, draft = "about").phraseSaveBlocked)
    }

    /** The control: nothing typed, and the checksum warning stays a warning. */
    @Test
    fun `a grid with no pending word is judged as before`() {
        assertFalse(phrase(failingChecksum, focus = 11, draft = "").phraseSaveBlocked)
        assertFalse(phrase(failingChecksum, focus = 11, draft = "abandon").phraseSaveBlocked)
    }
}

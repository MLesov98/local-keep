package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Picking a smaller word count does not throw away the words past it.
 *
 * Device pass 2026-10-04 (C1 finding 6): 24 words in, tap the 15 or the 12 chip, and words 13–24
 * were gone — no confirmation, no Undo, and tapping 24 again brought back 24 empty slots. The count
 * chips sit in a row of five 48dp plates, so a near-miss on the one beside the selected count is the
 * ordinary way to get here, and what it destroyed was typed recovery-phrase words, which are
 * typically copied off the only paper that holds them. [PhraseCountIsGatedTest] covers the *stored*
 * phrase behind the reveal gate; this is the capture case, where nothing gates the tap.
 *
 * The words past the count are held until the count grows again or the item is saved. What saves is
 * still exactly the grid on screen — the last test pins that.
 */
class PhraseCountChangeKeepsWordsTest {

    /** The first 24 words of the BIP-39 English list: real words, distinct, so a shuffle would show. */
    private val words24 = listOf(
        "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract",
        "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid",
        "acoustic", "acquire", "across", "act", "action", "actor", "actress", "actual",
    )

    private fun phrase(words: List<String>, focus: Int = -1, draft: String = "") = ItemEditUiState(
        type = ItemEditUiState.ItemType.PHRASE,
        phraseWords = words,
        phraseWordCount = words.size,
        phraseFocus = focus,
        phraseDraft = draft,
    )

    @Test
    fun `shrinking and growing back restores the words that were cut`() {
        val back = phrase(words24).withPhraseWordCount(12).withPhraseWordCount(24)

        assertEquals(words24, back.phraseWords, "words 13-24 were dropped by a tap on the 12 chip")
    }

    @Test
    fun `stepping down through another count keeps them too`() {
        val back = phrase(words24).withPhraseWordCount(15).withPhraseWordCount(12).withPhraseWordCount(24)

        assertEquals(words24, back.phraseWords)
    }

    @Test
    fun `growing part of the way brings back that part, in order`() {
        val partway = phrase(words24).withPhraseWordCount(12).withPhraseWordCount(15)

        assertEquals(words24.take(15), partway.phraseWords)
    }

    @Test
    fun `a word being typed into the focused slot is kept by a count change`() {
        val typing = phrase(words24.take(11) + "", focus = 11, draft = "absent")

        val after = typing.withPhraseWordCount(15)

        assertEquals("absent", after.phraseWords[11], "the half-entered word was dropped by the chip")
    }

    /** A paste replaces the phrase, so words held from the one it replaced must not come back. */
    @Test
    fun `a paste after a shrink does not bring the old words back`() {
        val pasted = phrase(words24).withPhraseWordCount(12)
            .withPastedPhrase(List(12) { "zoo" })
            .withPhraseWordCount(24)

        assertEquals(List(12) { "zoo" } + List(12) { "" }, pasted.phraseWords)
    }

    /** The control: what is saved is the grid on screen, never the words held past it. */
    @Test
    fun `the saved phrase is only the chosen count`() {
        val shrunk = phrase(words24).withPhraseWordCount(12)

        assertEquals(words24.take(12), shrunk.phraseWords)
        assertEquals(words24.take(12), shrunk.toSeedPhrase().words)
    }
}

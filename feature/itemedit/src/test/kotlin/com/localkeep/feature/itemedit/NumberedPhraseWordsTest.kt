package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A revealed seed phrase carries its word numbers, exactly as the app's own Recovery Kit does.
 *
 * `shots/085-phrase-revealed.png`, 2026-09-15: twelve words, three to a line, unnumbered — so
 * transcribing word 9 onto steel meant counting across rows, in the one flow this item type exists
 * for. The app's Recovery Kit numbers its own words `01`–`24` (`shots/195`), and the stored item
 * type was the only place it did not (F-28).
 *
 * FAILS WITHOUT THE FIX: `PhraseDetail` drew `state.phraseWords.joinToString(" ")` into the revealed
 * row and there was no `numberedPhraseWords` at all.
 */
class NumberedPhraseWordsTest {

    @Test
    fun `every word carries its position, zero-padded to two digits`() {
        assertEquals(
            "01\u00A0abandon 02\u00A0ability 03\u00A0able",
            numberedPhraseWords(listOf("abandon", "ability", "able")),
        )
    }

    @Test
    fun `the tenth word and beyond stay two digits, so the column aligns`() {
        val numbered = numberedPhraseWords(List(12) { "word" })
        assertTrue("09\u00A0word" in numbered)
        assertTrue("12\u00A0word" in numbered)
        // The control: every pair is exactly two digits, so nothing un-padded slipped in beside them.
        assertTrue(
            numbered.split(" ").all { Regex("""\d{2}\u00A0word""").matches(it) },
            "an index was not two digits wide: $numbered",
        )
    }

    /**
     * A number must never be separated from the word it numbers. Three-to-a-line wrapping is what
     * this whole fix is about, and an ordinary space between `07` and its word would let a wrap land
     * exactly where it does the most damage.
     */
    @Test
    fun `the index and its word are joined by a non-breaking space`() {
        val numbered = numberedPhraseWords(listOf("abandon", "ability"))
        assertTrue('\u00A0' in numbered, "the index can be wrapped away from its word")
        // Separated from each other by an ordinary space, so a break between pairs is still allowed.
        assertEquals(1, numbered.count { it == ' ' })
    }

    @Test
    fun `blank slots are dropped rather than numbered, so no position is wrong`() {
        assertEquals(
            "01\u00A0abandon 02\u00A0able",
            numberedPhraseWords(listOf("abandon", "", "able")),
        )
    }

    /**
     * Presentation only. Copy must still write the bare phrase — a wallet asked to restore from
     * "01 abandon 02 ability" has been handed something it cannot read — and a screen reader must
     * still hear the phrase rather than a list of numbers, which is why `SecretReadField` keeps
     * `value` for both and takes the numbering as a separate `displayValue`.
     */
    @Test
    fun `the numbering is drawn, never copied or spoken`() {
        val detail = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt").readText()
        val secretReadField = detail.substringAfter("internal fun SecretReadField(", "")
            .substringBefore("private fun ItemType.readableName()")
        assertTrue(
            "ClipboardHelper.copy(context, label, value)" in secretReadField,
            "the clipboard is no longer written from the raw value",
        )
        assertTrue(
            "Modifier.secretFieldSemantics(label, revealed, value)" in secretReadField,
            "the screen reader is no longer given the raw value",
        )
        assertTrue(
            "if (shown) displayValue else" in secretReadField,
            "the revealed row no longer draws the display form",
        )
        val phraseDetail = detail.substringAfter("private fun PhraseDetail(", "").substringBefore("\n@Composable")
        assertTrue(
            "displayValue = numberedPhraseWords(state.phraseWords)" in phraseDetail,
            "the recovery phrase row stopped numbering its words",
        )
    }
}

package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How many words a stored recovery phrase has is part of the secret, and waits behind the same gate.
 *
 * `shots/083-phrase-detail2.png`, 2026-09-15: an item badged `REPROMPT`, with `Recovery phrase,
 * hidden` and a copy button reading `unlocks first`, rendered `12 words` in clear the moment the
 * screen opened. The gate worked — revealing the phrase raised a BiometricPrompt — and published a
 * fact about what it was refusing to show, one row above the refusal (F-26).
 *
 * FAILS WITHOUT THE FIX: before `phraseCountHidden` existed, `PhraseDetail` passed the plural
 * straight into its `ReadField` and the editor's chooser drew the selected length unconditionally,
 * so both screens stated the count whatever `revealedFields` held.
 */
class PhraseCountIsGatedTest {

    private val stored = ItemEditUiState(
        type = ItemEditUiState.ItemType.PHRASE,
        phraseWordCount = 12,
        phraseGateRequired = true,
    )

    @Test
    fun `a stored phrase hides its length until the gate is passed`() {
        assertTrue(stored.phraseCountHidden, "a locked recovery phrase is publishing its word count")
    }

    @Test
    fun `passing the gate shows the length with the words`() {
        val revealed = stored.copy(revealedFields = setOf(com.localkeep.core.model.PHRASE_FIELD))
        assertFalse(revealed.phraseCountHidden)
    }

    /**
     * The control case, and the reason this follows `phraseGateRequired` rather than the item's
     * `reprompt` flag: a phrase being typed in is not a disclosure. Hiding the length there would
     * hide the user's own choice from them mid-capture.
     */
    @Test
    fun `a phrase being captured never hides its length`() {
        val capture = stored.copy(phraseGateRequired = false, reprompt = true)
        assertFalse(capture.phraseCountHidden, "the capture screen hid the count the user is choosing")
    }

    @Test
    fun `both screens ask the one question, rather than each deciding for itself`() {
        // The defect was not that one screen got it wrong — it was that neither screen asked.
        val detail = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt").readText()
        val editor = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
        val phraseDetail = detail.substringAfter("private fun PhraseDetail(", "").substringBefore("\n@Composable")
        assertTrue(
            "state.phraseCountHidden" in phraseDetail,
            "the detail screen's word-count row no longer consults the reveal gate",
        )
        val phraseFields = editor.substringAfter("private fun PhraseFields(", "").substringBefore("── words: header")
        assertTrue(
            "state.phraseCountHidden" in phraseFields,
            "the editor's word-count chooser no longer consults the reveal gate",
        )
    }
}

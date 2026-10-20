package com.localkeep.feature.itemedit

import com.localkeep.core.vault.SeedPhraseStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 4C-14, 2026-09-20: twelve real BIP-39 words whose checksum does not verify could not be saved at
 * all — Save stayed dimmed with `desc='Save, available once the recovery phrase is valid'` — so the
 * one person this item type exists for, someone transcribing a phrase off a plate they can no
 * longer read, was left with nowhere to put what they had. The decision taken was to let it save
 * and keep warning: coral on the editor banner, and coral again on the detail for as long as the
 * item exists.
 *
 * What did **not** change is the floor. A grid with a blank slot, a slot holding a word no wallet
 * will ever accept, and this vault's own Recovery Kit are all still refused — those are the three
 * states where saving would store something the user did not mean. Each is a control here, true
 * before this change and after, so a property that stopped reading the grid fails loudly instead of
 * passing empty.
 */
class ChecksumInvalidPhraseIsSaveableTest {

    /** The canonical BIP-39 vector: 11 x "abandon" + "about" is checksum-valid. */
    private val validPhrase = List(11) { "abandon" } + "about"

    /** Every word is real; the twelfth encodes no valid checksum over the other eleven. */
    private val checksumInvalidPhrase = List(12) { "abandon" }

    private fun phraseState(words: List<String>, isVaultKit: Boolean = false) = ItemEditUiState(
        type = ItemEditUiState.ItemType.PHRASE,
        phraseWords = words,
        phraseIsVaultKit = isVaultKit,
    )

    @Test
    fun `a phrase of real words whose checksum fails is saveable`() {
        val state = phraseState(checksumInvalidPhrase)

        // The premise: this really is the checksum verdict, not a bad word or a short grid.
        assertEquals(SeedPhraseStatus.CHECKSUM, state.phraseStatus)

        assertFalse(
            state.phraseSaveBlocked,
            "a checksum-invalid recovery phrase must be saveable with a warning (4C-14), not refused",
        )
        assertNull(
            state.phraseSaveDisabledReasonRes,
            "Save is enabled, so it must carry no 'available once the phrase is valid' reason",
        )
    }

    @Test
    fun `a blank slot still blocks the save`() {
        val state = phraseState(List(11) { "abandon" } + "")

        assertEquals(SeedPhraseStatus.INCOMPLETE, state.phraseStatus)
        assertTrue(state.phraseSaveBlocked, "an unfinished grid is not a phrase and must stay refused")
        assertEquals(R.string.itemedit_phrase_save_disabled_a11y, state.phraseSaveDisabledReasonRes)
    }

    /**
     * `SeedPhraseValidator.status` answers CHECKSUM for a grid of real words at *any* length — a
     * wrong word count is a BIP-39 objection like any other, and its `checksumValid` catch cannot
     * tell the two apart. The UI never builds such a grid (the word-count chooser resizes the list),
     * but the property must not read "eleven real words, checksum failed, therefore saveable".
     */
    @Test
    fun `eleven real words are an unfinished phrase, not a failed checksum`() {
        val state = phraseState(List(11) { "abandon" })

        assertEquals(SeedPhraseStatus.CHECKSUM, state.phraseStatus, "premise: the validator says so")
        assertTrue(state.phraseSaveBlocked, "a grid at no BIP-39 length is not a phrase yet")
        assertEquals(R.string.itemedit_phrase_save_disabled_a11y, state.phraseSaveDisabledReasonRes)
    }

    /** Every length BIP-39 defines takes the warning, not the block. */
    @Test
    fun `a failed checksum is saveable at each of the five lengths`() {
        listOf(12, 15, 18, 21, 24).forEach { n ->
            val state = phraseState(List(n) { "abandon" })
            assertEquals(SeedPhraseStatus.CHECKSUM, state.phraseStatus, "premise at $n words")
            assertFalse(state.phraseSaveBlocked, "$n real words with a failed checksum must save")
        }
    }

    @Test
    fun `a word that is not on the BIP-39 list still blocks the save`() {
        val state = phraseState(List(11) { "abandon" } + "marbel")

        assertEquals(SeedPhraseStatus.BADWORD, state.phraseStatus)
        assertTrue(
            state.phraseSaveBlocked,
            "a misspelled word is fatal on restore and no warning makes it storable",
        )
        assertEquals(R.string.itemedit_phrase_save_disabled_a11y, state.phraseSaveDisabledReasonRes)
    }

    @Test
    fun `this vault's own Recovery Kit still blocks the save`() {
        val state = phraseState(validPhrase, isVaultKit = true)

        assertEquals(SeedPhraseStatus.VAULTKIT, state.phraseStatus)
        assertTrue(state.phraseSaveBlocked, "the key to the vault must not go inside the vault")
        assertEquals(
            R.string.itemedit_phrase_save_disabled_vaultkit_a11y,
            state.phraseSaveDisabledReasonRes,
        )
    }

    /** The control on the other side: a valid phrase was saveable before and still is. */
    @Test
    fun `a checksum-valid phrase is still saveable`() {
        val state = phraseState(validPhrase)

        assertEquals(SeedPhraseStatus.VALID, state.phraseStatus)
        assertFalse(state.phraseSaveBlocked)
    }

    /**
     * The warning has to outlive the form that accepted it, which is the whole of why saving is
     * safe to allow. A source scan rather than a render, for the reason
     * `PhraseRevealColourMatchesOtherScreensTest` gives: this module has no Robolectric wiring.
     */
    @Test
    fun `the detail screen warns in coral when the stored phrase does not verify`() {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt")
        assertTrue(src.exists(), "ItemDetailScreen.kt not found at ${src.absolutePath}")
        val text = src.readText()

        val start = text.indexOf("private fun PhraseDetail(")
        assertTrue(start > 0, "PhraseDetail not found — did it move?")
        val end = text.indexOf("R.string.itemedit_detail_phrase_passphrase_label", start)
        assertTrue(end > start, "the passphrase field that ends PhraseDetail was not found after it")
        val body = text.substring(start, end)

        assertTrue(
            "state.phraseStatus == SeedPhraseStatus.CHECKSUM" in body,
            "the stored-phrase detail must key its warning on the checksum verdict",
        )
        assertTrue(
            "R.string.itemedit_detail_phrase_checksum_invalid" in body,
            "the warning must be the resource, not an assembled sentence",
        )
        assertTrue(
            "accent = LkTheme.colors.coralText" in body,
            "coral, not amber: LkColors gives amber to 'nothing is wrong', and a phrase that will " +
                "not restore is a failure",
        )
    }
}

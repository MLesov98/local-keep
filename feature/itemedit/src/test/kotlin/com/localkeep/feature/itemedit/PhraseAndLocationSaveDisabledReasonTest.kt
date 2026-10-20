package com.localkeep.feature.itemedit

import com.localkeep.core.vault.SeedPhraseStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F49, 2026-09-13 (device walk): the seed-phrase and location Save buttons were dimmed with no
 * reason in their accessibility name. The reason is keyed on the real blocking condition
 * ([ItemEditUiState.phraseStatus]) rather than on the button's `enabled`, which also folds in
 * `isSaving`/`readOnly` — the V21 precedent
 * (`core_ui_naming_confirm_disabled_a11y`, `LkNamingSheet.kt`) this mirrors.
 *
 * The phrase half is keyed on the *status*, not on [ItemEditUiState.phraseSaveBlocked]: that
 * boolean folds four distinct verdicts into one, and one of them — VAULTKIT — is checksum-**valid**
 * and refused anyway, so the single sentence "available once the recovery phrase is valid" told a
 * screen-reader user to repair a phrase that was already correct.
 *
 * The location half was removed on 2026-10-09: a Location item no longer draws Save until a reading
 * has been kept (`locationActions`, pinned by [LocationActionSlotTest]), so there is no dimmed Save
 * left to explain.
 */
class PhraseAndLocationSaveDisabledReasonTest {

    // The canonical BIP-39 test vector: 11 x "abandon" + "about" is checksum-valid.
    private val validPhrase = List(11) { "abandon" } + "about"

    @Test
    fun `an incomplete recovery phrase gives a reason`() {
        val state = ItemEditUiState(
            type = ItemEditUiState.ItemType.PHRASE,
            phraseWords = List(11) { "abandon" }, // one short of the 12-word grid
        )
        assertEquals(
            R.string.itemedit_phrase_save_disabled_a11y,
            state.phraseSaveDisabledReasonRes,
        )
    }

    /**
     * The name this test used to carry — "a checksum-valid recovery phrase has no reason to give" —
     * was the false assumption that put a wrong sentence on the button below. Checksum-valid is not
     * the same as unblocked: [ItemEditUiState.phraseStatus] layers VAULTKIT *on top of* a passing
     * BIP-39 check, so a Recovery-Kit phrase is both valid and refused. This case is the one where
     * the old name was true, and it is now named for the condition it actually covers.
     */
    @Test
    fun `a checksum-valid phrase that is not this vault's own kit has no reason to give`() {
        val state = ItemEditUiState(type = ItemEditUiState.ItemType.PHRASE, phraseWords = validPhrase)
        assertNull(state.phraseSaveDisabledReasonRes)
    }

    /**
     * A Recovery-Kit phrase is blocked while being perfectly valid, so the reason must not be the
     * "…once the recovery phrase is valid" sentence: that instructs a screen-reader user to fix
     * something that is not broken, and names no action that could ever satisfy it. Its own key,
     * not the banner title reused — `Modifier.labelled` replaces a node's accessible name, so
     * borrowing the banner's sentence would have left the button with no "Save" in its name at all.
     */
    @Test
    fun `this vault's own Recovery Kit words name the kit, not an invalid phrase`() {
        val state = ItemEditUiState(
            type = ItemEditUiState.ItemType.PHRASE,
            phraseWords = validPhrase,
            phraseIsVaultKit = true,
        )
        assertEquals(SeedPhraseStatus.VAULTKIT, state.phraseStatus)
        assertEquals(
            R.string.itemedit_phrase_save_disabled_vaultkit_a11y,
            state.phraseSaveDisabledReasonRes,
        )
    }

    /** The control: a phrase this incomplete on a Login item (impossible in the real UI, but the
     * property must not fire on the raw field contents alone) gives no reason — only PHRASE items
     * are ever save-blocked on their words. */
    @Test
    fun `an incomplete phrase field on a non-phrase item gives no reason`() {
        val state = ItemEditUiState(type = ItemEditUiState.ItemType.LOGIN, phraseWords = List(3) { "abandon" })
        assertNull(state.phraseSaveDisabledReasonRes)
    }
}

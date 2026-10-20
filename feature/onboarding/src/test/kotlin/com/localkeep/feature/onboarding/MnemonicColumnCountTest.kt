package com.localkeep.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The column-count decision behind [MnemonicGrid]'s recovery-word list.
 *
 * At fontScale 2.0 an 8-character BIP-39 word — `universe`, `umbrella`, `tomorrow`, `together`, all
 * in the bundled wordlist — does not fit its column. The auditor's "silently unusable Recovery Kit"
 * claim does not hold: the word wraps into the row rather than clipping, because `MnemonicWord` sets
 * no `maxLines` and the whole grid sits in an unbounded-height scroller, so no character is ever
 * lost. What is real is legibility: a wrapped 24-word transcription list is exactly the kind of
 * thing a user re-checks character by character while copying it onto paper, and a wrapped word
 * reads worse there than anywhere else in the app.
 *
 * `MnemonicGrid` is shared by two screens at different widths — this one pads 26dp for a 155.5dp
 * cell, `RecoveryKitScreen` pads 20dp for 161.5dp — so a dp cutoff written for either caller would
 * be wrong for the other, and onboarding is the one that breaks first. [mnemonicColumnCount] takes
 * the actual measured container width and the actual measured longest-word width instead, which is
 * correct for both. The constants below are representative density-1 pixel values standing in for
 * dp, in the idiom `ErrorRevealPinTest` (`:core:ui`) already uses for this same reason: the point is
 * the arithmetic, not a specific device's font metrics.
 */
class MnemonicColumnCountTest {

    private companion object {
        // 2 * 155.5dp cell + 12dp spacing, and 2 * 161.5dp + 12dp — see MnemonicGrid's own comment.
        const val ONBOARDING_WIDTH = 323f
        const val RECOVERY_KIT_WIDTH = 335f
        const val SPACING = 12f
        const val INDEX_GAP = 8f

        // A two-digit index ("24") in monoCaption, and an 8-character word in monoField, at
        // fontScale 1.0.
        const val INDEX_WIDTH_1X = 13f
        const val WORD_WIDTH_1X = 67f
    }

    /**
     * Inert at fontScale 1.0. Both the index and the word roughly double as fontScale climbs, and
     * only once they do does the word stop fitting its own column — this is the state every
     * existing screenshot was taken against, and it must not move.
     */
    @Test
    fun `inert at fontScale 1x`() {
        assertEquals(
            2,
            mnemonicColumnCount(ONBOARDING_WIDTH, SPACING, INDEX_WIDTH_1X, INDEX_GAP, WORD_WIDTH_1X),
        )
    }

    /** The regression: the same 8-character word, doubled for fontScale 2.0, no longer fits. */
    @Test
    fun `an 8-character word drops to one column at fontScale 2x`() {
        val columns = mnemonicColumnCount(
            containerWidthPx = ONBOARDING_WIDTH,
            columnSpacingPx = SPACING,
            indexWidthPx = INDEX_WIDTH_1X * 2,
            indexGapPx = INDEX_GAP,
            wordWidthPx = WORD_WIDTH_1X * 2,
        )
        assertEquals(1, columns, "'universe' at fontScale 2.0 should no longer fit a 155.5dp cell")
    }

    /**
     * Why a hardcoded per-screen cutoff would be wrong: the same word at the same width fits
     * RecoveryKitScreen's wider cell but not onboarding's narrower one. Onboarding is the one that
     * breaks first, so the threshold has to come from what this instance actually measured.
     */
    @Test
    fun `onboarding breaks before RecoveryKitScreen, at the same word width`() {
        val wordWidthPx = 137f

        val onboarding = mnemonicColumnCount(ONBOARDING_WIDTH, SPACING, INDEX_WIDTH_1X, INDEX_GAP, wordWidthPx)
        val recoveryKit = mnemonicColumnCount(RECOVERY_KIT_WIDTH, SPACING, INDEX_WIDTH_1X, INDEX_GAP, wordWidthPx)

        assertEquals(1, onboarding, "onboarding's narrower cell should have dropped to one column")
        assertEquals(2, recoveryKit, "RecoveryKitScreen's wider cell should still hold the same word")
    }

    /** The boundary: exactly filling the budget stays at two columns; one px over drops it. */
    @Test
    fun `the boundary sits at exactly the budget`() {
        val cellWidth = (ONBOARDING_WIDTH - SPACING) / 2f
        val budget = cellWidth - INDEX_WIDTH_1X - INDEX_GAP

        assertEquals(2, mnemonicColumnCount(ONBOARDING_WIDTH, SPACING, INDEX_WIDTH_1X, INDEX_GAP, budget))
        assertEquals(1, mnemonicColumnCount(ONBOARDING_WIDTH, SPACING, INDEX_WIDTH_1X, INDEX_GAP, budget + 1f))
    }

    /**
     * Before the first layout pass there is nothing to compare against, and guessing would collapse
     * every mnemonic to one column for a frame on every screen, not just the ones that need it.
     * Unmeasured means unchanged — the same idiom `errorRevealHeightPx` uses in `:core:ui`
     * ("an unmeasured viewport clamps nothing").
     */
    @Test
    fun `unmeasured stays at two columns`() {
        assertEquals(2, mnemonicColumnCount(0f, SPACING, INDEX_WIDTH_1X, INDEX_GAP, WORD_WIDTH_1X))
        assertEquals(2, mnemonicColumnCount(ONBOARDING_WIDTH, SPACING, INDEX_WIDTH_1X, INDEX_GAP, 0f))
    }
}

package com.localkeep.feature.itemedit

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the itemedit defects found by the 2026-08-29 sequential persona walk.
 *
 * Each was reproduced on a device first, then traced to a mechanism that was **not** the one
 * originally reported — three of the walk's itemedit findings were refuted outright on
 * verification, and two more had their mechanism corrected. These pin the mechanisms that turned
 * out to be real.
 *
 * Asserted on pure state functions rather than through the ViewModel, which needs a repository, a
 * lock manager and an entitlement — the same split `EmptyNewItemIsNotDirtyTest` and the other pure
 * tests in this module use. Each fix was refactored to expose one, precisely because the bug had
 * been living somewhere a JVM test could not reach.
 */
class PersonaWalkFixesTest {

    /**
     * One instance, copied from. Two separately-constructed defaults are never equal because
     * `itemId` defaults to a random UUID — a trap `EmptyNewItemIsNotDirtyTest` documents.
     */
    private val base = ItemEditUiState()

    // ── F04-qa-1: opening an existing item and leaving it untouched claimed unsaved edits ────────

    /**
     * Fails without the fix.
     *
     * `loadItem()` anchors the unsaved-change baseline at the end of its own run, but `loadFolders()`
     * is the next call in `init` and it is what fills `folderItemCounts`. With that field left in
     * the comparison, the baseline was anchored against an empty map and every later comparison
     * differed by it — so backing out of *any* existing item's editor, having touched nothing,
     * always raised "Discard changes?".
     *
     * New items dodged it only because the screen calls `presetType()` afterwards, which re-anchors
     * a second time. An existing item gets no such second anchor, which is why this reproduced on
     * every saved item and on no new one.
     */
    @Test
    fun `folder item counts are not the user's content`() {
        val counted = base.copy(folderItemCounts = mapOf(UUID.randomUUID() to 4))
        assertEquals(
            base.editableOnly(),
            counted.editableOnly(),
            "the folder picker's row counts counted as an unsaved edit",
        )
    }

    /** The control: something the user really did type must still register. */
    @Test
    fun `a typed title is still an unsaved edit`() {
        assertNotEquals(base.editableOnly(), base.copy(title = "Bank").editableOnly())
    }

    // ── F03-qa-2: the auto-title froze one keystroke before the domain was finished ──────────────

    /**
     * Fails without the fix, and this is the exact keystroke that broke.
     *
     * The old guard was `title.isBlank()`. `hostOf` accepts a host as soon as its TLD reaches two
     * characters, so at "github.co" the title stopped being blank and the guard shut the derivation
     * off — permanently, because it never becomes blank again. The final "m" was ignored and the
     * item was named "github.co".
     */
    @Test
    fun `a title we derived stays open to being re-derived`() {
        assertTrue(titleIsStillDerived(title = "github.co", lastDerived = "github.co"))
    }

    /** Blank has nothing to protect, so derivation may start. */
    @Test
    fun `a blank title is open to derivation`() {
        assertTrue(titleIsStillDerived(title = "", lastDerived = null))
    }

    /**
     * The guard the fix must not break: a name the user chose is never overwritten by a later URL
     * edit. This is also why the fix could not simply be "always re-derive" — and why it could not
     * be a "user has typed a title" flag either, since an item loaded from the vault has a title
     * nobody flagged.
     */
    @Test
    fun `a hand-typed title is left alone`() {
        assertFalse(titleIsStillDerived(title = "My bank", lastDerived = "github.com"))
    }

    /** An existing item loaded from the vault has never derived anything, so its title is safe. */
    @Test
    fun `a loaded item's title is not treated as ours`() {
        assertFalse(titleIsStillDerived(title = "Twitter", lastDerived = null))
    }

    // ── F03-qa-3: a typed recovery-phrase word vanished when moving to another slot ──────────────

    private val phrase = base.copy(
        type = ItemEditUiState.ItemType.PHRASE,
        phraseWordCount = 12,
        phraseWords = List(12) { "" },
    )

    /**
     * Fails without the fix.
     *
     * Focusing a slot used to overwrite the draft with the incoming slot's stored word, so the word
     * typed into the slot being left was dropped. Nothing else wrote to `phraseWords` except the
     * autocomplete chip, which is small and easy to miss — and every cue said the word had landed.
     */
    @Test
    fun `moving to another slot keeps the word just typed`() {
        val typed = phrase.copy(phraseFocus = 0, phraseDraft = "abandon")
        val moved = typed.withPhraseSlotFocused(1)
        assertEquals("abandon", moved.phraseWords[0], "the typed word was discarded on navigation")
        assertEquals(1, moved.phraseFocus)
        assertEquals("", moved.phraseDraft, "the new slot's empty word should be loaded")
    }

    /** Filling the grid by tapping through slots must accumulate, not overwrite one cell. */
    @Test
    fun `tapping through several slots accumulates every word`() {
        val filled = listOf("abandon", "ability", "able").foldIndexed(phrase) { i, state, word ->
            state.withPhraseSlotFocused(i).copy(phraseDraft = word)
        }.withPhraseSlotFocused(3)
        assertEquals(listOf("abandon", "ability", "able"), filled.phraseWords.take(3))
    }

    /** Returning to a filled slot loads its word for correction rather than blanking it. */
    @Test
    fun `focusing a filled slot loads its word`() {
        val filled = phrase.copy(phraseWords = List(12) { if (it == 2) "able" else "" })
        assertEquals("able", filled.withPhraseSlotFocused(2).phraseDraft)
    }

    /** An out-of-range index changes nothing — the ViewModel relied on this before the extraction. */
    @Test
    fun `an out of range slot is ignored`() {
        val typed = phrase.copy(phraseFocus = 0, phraseDraft = "abandon")
        assertEquals(typed, typed.withPhraseSlotFocused(99))
    }
}

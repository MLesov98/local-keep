package com.localkeep.feature.importwizard

import com.localkeep.core.import.ImportMerger
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * Backing out of a finished preview asks first; backing out of a running parse still does not; and
 * answering "Discard" actually leaves.
 *
 * `back()` emptied `rows` unconditionally. On the Preview step that is a minute of Argon2 plus every
 * keep/skip and duplicate decision the user made over the rows, thrown away by one tap on an arrow,
 * while every other work-losing exit in this app — the item editor's `leave()`, the file editor's,
 * the backup screen's back handler — asks first. Found 2026-08-30.
 *
 * [a running parse is never put behind the dialog] is the one that matters most, and it is why this
 * is not simply "confirm on Back". Backing out is the *only* way to cancel a running KDBX parse, and
 * `cancelParseThenDiscard` exists for precisely that; a dialog in front of it would put a question
 * between the user and the escape hatch for an operation they had already decided to abandon, and
 * nothing is lost by abandoning a parse that has produced no rows yet.
 *
 * **These assertions drive [backOutcome], not the source text.** They used to read the body of
 * `back()` as a string, because the ViewModel needs a Context, a VaultRepository and a
 * VaultLockManager to construct and this module ships no Robolectric. That is why the Discard button
 * shipped dead: the old `confirming clears the flag before it goes back` case asserted that
 * `confirmDiscard = false` appeared before `back()` in the source of `confirmDiscard()`, which was
 * true, and which never mattered — `back()` never read the flag. It re-derived the guard from the
 * step and the rows, re-raised the dialog it was answering, and a populated preview had no working
 * backward exit at all. Found on a device by the pre-iOS sweep 2026-09-11.
 */
class ImportPreviewDiscardGuardTest {

    private fun row() = PreviewRow(
        id = UUID.randomUUID(),
        title = "row",
        subtitle = "",
        disposition = ImportMerger.Disposition.NEW,
        collidesWith = null,
        selected = true,
        item = VaultItem(
            id = UUID.randomUUID(),
            title = "row",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = Login(username = "u", password = "p"),
        ),
    )

    private fun populatedPreview() = ImportUiState(
        step = ImportUiState.Step.PREVIEW,
        rows = listOf(row()),
    )

    @Test
    fun `a populated preview asks before it is discarded`() {
        val outcome = backOutcome(populatedPreview(), parsing = false, discardConfirmed = false)
        assertEquals(BackOutcome.AskToDiscard, outcome)
    }

    /**
     * The regression this class exists for now.
     *
     * Answering the dialog must leave the preview. While `confirmDiscard()` routed through the
     * guarded path, this returned [BackOutcome.AskToDiscard] — the same dialog, re-raised by the
     * act of answering it — and the button did nothing on a device, every single time.
     */
    @Test
    fun `confirming the dialog actually leaves the preview`() {
        val outcome = backOutcome(populatedPreview(), parsing = false, discardConfirmed = true)
        val leave = assertIs<BackOutcome.Leave>(
            outcome,
            "answering Discard must leave the preview, not re-raise the dialog that asked",
        )
        assertNotEquals(ImportUiState.Step.PREVIEW, leave.next.step, "Discard must leave the Preview step")
        assertEquals(ImportUiState.Step.INSTRUCTIONS, leave.next.step)
        assertEquals(emptyList(), leave.next.rows, "the rows the user discarded must be gone")
        assertEquals(false, leave.next.confirmDiscard, "the dialog must not be left standing")
        assertEquals(true, leave.cancelParse, "leaving PREVIEW still tears down the parse buffer")
    }

    /** The guard that keeps the cancel path free, and the whole reason the condition is compound. */
    @Test
    fun `a running parse is never put behind the dialog`() {
        val parsingPreview = populatedPreview().copy(isWorking = true)
        val outcome = backOutcome(parsingPreview, parsing = true, discardConfirmed = false)
        val leave = assertIs<BackOutcome.Leave>(
            outcome,
            "a dialog in front of a running parse would block the only way to cancel it",
        )
        assertEquals(true, leave.cancelParse)
    }

    /** An empty preview has nothing to lose, so it leaves without a question. */
    @Test
    fun `an empty preview leaves without asking`() {
        val empty = ImportUiState(step = ImportUiState.Step.PREVIEW)
        assertIs<BackOutcome.Leave>(backOutcome(empty, parsing = false, discardConfirmed = false))
    }

    /** A vault write in flight refuses Back outright, so it cannot land mid-commit. */
    @Test
    fun `a write in flight refuses back`() {
        val writing = ImportUiState(step = ImportUiState.Step.PREVIEW, isWorking = true)
        assertEquals(
            BackOutcome.Refuse,
            backOutcome(writing, parsing = false, discardConfirmed = false),
        )
    }
}

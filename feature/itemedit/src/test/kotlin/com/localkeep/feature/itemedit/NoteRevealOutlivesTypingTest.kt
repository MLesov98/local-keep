package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A reprompt note stays open while the user is typing into it.
 *
 * O1-03, 2026-10-02: a revealed reprompt note re-locked every 30 s whatever the user was doing. The
 * gate in `NoteFields` swaps the editable well for a masked plate, so the field, the caret and the
 * keyboard went mid-sentence and the next word needed another fingerprint. The phrase grid had
 * already solved this — `touchPhraseReveal` restarts its timer on every interaction — and the note
 * had no equivalent. Ticking Reprompt on a *new* note was worse: it hid what the user had just typed
 * behind a prompt, though nothing was being disclosed to them.
 *
 * Read from source, as [NoteRepromptGateTest] reads the same gate: the timer lives in a ViewModel a
 * JVM cannot build, and the gate in a private composable that needs it.
 */
class NoteRevealOutlivesTypingTest {

    @Test
    fun `typing into a revealed note restarts its auto-hide`() {
        val setter = block(VIEW_MODEL, "fun onNoteBodyChanged(", "\n    }\n")
        assertTrue(
            "NOTE_BODY_FIELD in _state.value.revealedFields" in setter &&
                "scheduleAutoHide(NOTE_BODY_FIELD)" in setter,
            "onNoteBodyChanged does not restart the note's reveal timer, so the masked plate replaces " +
                "the field 30 s after the reveal however much the user is typing:\n$setter",
        )
    }

    @Test
    fun `a new note is not masked behind its own reprompt`() {
        val fields = block(SCREEN, "private fun NoteFields(", "private fun ")
        val gate = fields.lineSequence().firstOrNull { it.trimStart().startsWith("val noteGated =") }
        assertTrue(gate != null, "NoteFields no longer declares `val noteGated` — update the anchor")
        assertTrue(
            "!state.isNew" in gate,
            "the note gate applies to a note still being written: `${gate.trim()}`",
        )
    }

    /** The control: a stored note's gate still reads the reprompt flag and the reveal key. */
    @Test
    fun `a stored note is still gated on its reprompt flag`() {
        val fields = block(SCREEN, "private fun NoteFields(", "private fun ")
        val gate = fields.lineSequence().first { it.trimStart().startsWith("val noteGated =") }
        assertTrue("state.reprompt" in gate && "NOTE_BODY_FIELD !in state.revealedFields" in gate, gate)
    }

    private companion object {
        const val SCREEN = "src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt"
        const val VIEW_MODEL = "src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt"

        /** Asserts its own anchor: `substringAfter` on a missing one returns the whole file. */
        fun block(path: String, start: String, end: String): String {
            val file = File(path)
            assertTrue(file.isFile, "expected source file at ${file.absolutePath}")
            val text = file.readText()
            assertTrue(start in text, "anchor '$start' is gone from $path — update the anchor")
            return text.substringAfter(start).substringBefore(end)
        }
    }
}

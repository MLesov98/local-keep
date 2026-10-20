package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * No editor focuses a field on entry, and the note editor opens small (2026-10-14).
 *
 * History: on 2026-09-20 (release sweep 4B-7) an empty note was made to open with the caret in it,
 * because the detail screen's empty state says "Tap to write one" and the tap landed in an editor
 * with no focus. The principal reversed that: the keyboard came up over half the form on every
 * entry, including entries made to change the folder or the tags. The body well is now small enough
 * when empty to leave the whole form visible, and the tap is what opens the keyboard.
 *
 * Source-pinned: what is asserted is that [NoteFields] holds no focus request and that its well
 * grows from a floor to a ceiling instead of being one fixed height.
 */
class NoteEditorOpensReadyToWriteTest {

    private val screen =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()

    private val noteFields = screen
        .substringAfter("private fun NoteFields(", "")
        .substringBefore("\n@Composable")

    @Test
    fun `the note body is not focused on entry`() {
        assertTrue(noteFields.isNotEmpty(), "NoteFields moved; this test no longer reads it")
        assertFalse("requestFocus()" in noteFields, "the note body asks for focus on entry again")
        assertFalse("FocusRequester" in noteFields, "a focus requester is back in the note editor")
        assertFalse(".focusRequester(" in noteFields, "something is wired to a focus requester")
    }

    @Test
    fun `no effect in the note editor waits for frames to take focus`() {
        assertFalse("withFrameNanos" in noteFields, "the two-frame focus request is back")
        assertFalse("LaunchedEffect" in noteFields, "an effect runs on entry to the note editor")
    }

    /**
     * Small when empty, growing as you type: the well's floor and ceiling are different values. It
     * was `minHeight = noteHeight, maxHeight = noteHeight` - one fixed height of 42% of the screen
     * whatever the body held.
     */
    @Test
    fun `the well grows from a floor to a ceiling`() {
        assertTrue("minHeight = NOTE_MIN_HEIGHT," in noteFields, "the floor is not the named constant")
        assertTrue("maxHeight = noteMaxHeight," in noteFields, "the ceiling is gone")
        assertFalse("minHeight = noteMaxHeight" in noteFields, "the well is one fixed height again")
        val floor = Regex("NOTE_MIN_HEIGHT = (\\d+)\\.dp").find(screen)?.groupValues?.get(1)?.toInt()
        assertTrue(floor != null && floor in 120..160, "the floor is not about five body lines: $floor")
    }

    /**
     * And the detail screen still makes the promise this keeps — otherwise the fix is answering a
     * question nobody asks any more.
     */
    @Test
    fun `the detail screen still invites the write`() {
        val detail =
            File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt").readText()
        assertTrue("R.string.itemedit_detail_note_empty_tap" in detail)
    }
}

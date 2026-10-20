package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The notes cap refuses growth, never shrinking.
 *
 * QA 2026-10-03: a ~300,000-character note froze the editor. The cap is on what the editor lets a
 * note grow to; a note already past it — an import, which never went through the editor — must
 * still be editable downwards, or the cap would hold the user's own text hostage.
 */
class NoteCapTest {

    @Test
    fun `growth up to the cap is taken`() {
        assertEquals("ab", noteAfterEdit(previous = "a", proposed = "ab"))
        val atCap = "a".repeat(MAX_NOTE_CHARS)
        assertEquals(atCap, noteAfterEdit(previous = "", proposed = atCap))
    }

    @Test
    fun `growth past the cap is refused whole`() {
        assertNull(noteAfterEdit(previous = "", proposed = "a".repeat(MAX_NOTE_CHARS + 1)))
    }

    @Test
    fun `a note already over the cap can still be shortened`() {
        val imported = "c".repeat(MAX_NOTE_CHARS + 10)
        assertEquals(imported.dropLast(1), noteAfterEdit(previous = imported, proposed = imported.dropLast(1)))
    }
}

package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The note editor's body sits inside a `GuardedSecretField`, so the platform menu cannot copy it.
 *
 * S5-06, 2026-10-02: long-press in the note body, Select all, Copy — and the whole note went to the
 * clipboard through the system toolbar, without ClipboardHelper's `EXTRA_IS_SENSITIVE` flag, its
 * 30 s wipe, or its pending record for the sweep. A note body is where the secrets that fit no
 * structured field end up, which is why the detail screen has never offered a Copy for it; the
 * editor reopened the door. Same shape, same reading as [PhraseWordFieldIsClipboardGuardedTest],
 * whose control (the title field stays unguarded) covers blanket-wrapping here too.
 */
class NoteBodyIsClipboardGuardedTest {

    private val screen = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")

    /** The extent of every `GuardedSecretField {` lambda, by counting braces from its opening one. */
    private fun guardedBlocks(text: String): List<IntRange> {
        val blocks = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val start = text.indexOf("GuardedSecretField {", from)
            if (start < 0) return blocks
            var depth = 0
            var i = text.indexOf('{', start)
            while (i < text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            blocks.add(start..i)
                            break
                        }
                    }
                }
                i++
            }
            from = start + 1
        }
    }

    @Test
    fun `the note body field is wrapped in a clipboard-guarded toolbar`() {
        assertTrue(screen.exists(), "ItemEditScreen.kt not found at ${screen.absolutePath}")
        val text = screen.readText()

        // The body well's own wiring, which nothing else on the screen carries.
        val marker = "onValueChanged = viewModel::onNoteBodyChanged,"
        val at = text.indexOf(marker)
        assertTrue(at > 0, "$marker not found — did the note body field move or get renamed?")
        assertTrue(
            guardedBlocks(text).any { at in it },
            "The note body field is not inside a GuardedSecretField block. Copy/Cut on it use the " +
                "platform toolbar, skipping ClipboardHelper's sensitive flag and auto-clear.",
        )
    }
}

package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which controls on the file editor stay live while the screen is already busy.
 *
 * Source-scanning rather than a Compose test, following [PlaybackScreenAwakeTest]: each of these is
 * a claim about which argument one call site passes, this module's unit-test classpath carries no
 * Compose UI test artifact, and rendering the editor would need a real blob, a real session key and
 * a device besides. The question the source answers exactly is the question being asked.
 *
 * **6-2.** The `View` button carried no `enabled`, so the viewer opened during an import — and the
 * viewer's delete calls `removeFile()`. Every import/capture/cleanup pipeline finishes by writing
 * its result over the form with `stateWithSource`, so the delete cleared the file, the import
 * landed seconds later, and the record came back holding a file the user had neither deleted nor
 * chosen to keep, with nothing said. Gating the way in is the fix; a `removeFile()` that silently
 * refused would be a confirmed delete that does nothing.
 *
 * **6-3.** `From gallery` and `Capture` had the same gap with a smaller consequence:
 * `importFromPicker` and `onCaptured` both open with a bare `if (_state.value.isWorking) return`,
 * so a second pick during a slow first import vanished with no error at all.
 *
 * **Delete any one of the four `enabled = !state.isWorking` arguments named below and the first
 * test goes red.** The second is the control — it is the same file, and most of its buttons must
 * stay enabled, so an assertion that only counted occurrences would pass on a blanket change.
 */
class FileEditorInFlightControlsTest {

    private val source: String =
        File("src/main/kotlin/com/localkeep/feature/files/FileItemEditScreen.kt").readText()

    /**
     * The composable call whose `text` argument names [stringKey]: the nearest preceding
     * `LkSecondaryButton(` or `LkPrimaryButton(`, read to its balanced closing paren.
     */
    private fun buttonCallNaming(stringKey: String): String {
        // The closing paren is part of the marker on purpose: `R.string.files_edit_view` is a
        // prefix of `R.string.files_edit_viewer_reveal_title`, which appears earlier in the file,
        // and a prefix match would have measured the wrong call site entirely.
        val marker = "R.string.$stringKey)"
        val at = source.indexOf(marker)
        assertTrue(at >= 0, "no call site names $stringKey any more")
        val open = maxOf(source.lastIndexOf("LkSecondaryButton(", at), source.lastIndexOf("LkPrimaryButton(", at))
        assertTrue(open >= 0, "$stringKey is no longer inside a button call")
        return balancedFrom(source.indexOf('(', open))
    }

    /** From the paren at [start] to its balanced partner. */
    private fun balancedFrom(start: Int): String {
        var depth = 0
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        error("unbalanced parentheses from $start")
    }

    /**
     * The file card's header row — the way into the viewer since 2026-10-09, when the full-width
     * View button became the card's own tap target. The guard moved with it.
     */
    private fun viewRowClickable(): String {
        val card = source.indexOf("private fun FileCard(")
        assertTrue(card >= 0, "FileCard is gone — where does the editor open the viewer now?")
        val clickable = source.indexOf(".clickable(", card)
        assertTrue(clickable > card, "the file card no longer has a tap target")
        return balancedFrom(source.indexOf('(', clickable))
    }

    @Test
    fun `nothing that would race an in-flight import stays tappable during one`() {
        assertTrue(
            "enabled = !state.isWorking" in viewRowClickable() && "onClick = onView" in viewRowClickable(),
            "opening the viewer must be disabled while an import, capture or metadata cleanup runs",
        )
        // The three import sources live in one sheet now (2026-10-09), so the guard sits on the two
        // controls that open it: "Replace file…" on a form holding a file, "Add a file" on an empty
        // one. Delete metadata rewrites the file and has carried it since before any of this.
        for (key in listOf("files_edit_replace_file", "files_edit_delete_metadata")) {
            assertTrue(
                "enabled = !state.isWorking" in buttonCallNaming(key),
                "$key must be disabled while an import, capture or metadata cleanup is running",
            )
        }
        // The empty state's button, found from the empty state: its label is also the plate's
        // accessibility label, so the first mention of the key is not the button.
        val empty = source.indexOf("LkEmptyState(", source.indexOf("FileCard(\n"))
        assertTrue(empty >= 0, "the File editor's empty state is gone")
        val addButton = balancedFrom(source.indexOf('(', source.indexOf("LkPrimaryButton(", empty)))
        assertTrue("R.string.files_edit_empty_icon_tap_label" in addButton, "the empty state's button no longer adds a file")
        assertTrue("enabled = !state.isWorking" in addButton, "Add a file must be disabled while an import is running")
    }

    @Test
    fun `the guard is on those controls and not sprayed across the screen`() {
        // The control. A change that disabled every control mid-import — or a test that merely
        // counted occurrences of the guard — would satisfy the assertion above while making the
        // screen unusable, so one neighbouring control that must NOT carry it is asserted too.
        // Manage attachments navigates away; it touches neither the staged source nor the form.
        val attachments = source.indexOf("R.string.files_edit_manage_attachments)")
        assertTrue(attachments >= 0, "Manage attachments is gone")
        val row = balancedFrom(source.indexOf('(', source.lastIndexOf("FileNavRow(", attachments)))
        assertTrue(
            "enabled = !state.isWorking" !in row,
            "Manage attachments has never needed the guard and must not have grown one",
        )
        // Four since 2026-10-09: the view row, Replace, Add a file and Delete metadata. The three
        // per-source buttons (gallery, capture, documents) that carried it before are one sheet now.
        assertEquals(
            4,
            Regex(Regex.escape("enabled = !state.isWorking")).findAll(source).count(),
            "a fifth occurrence means the guard was sprayed rather than placed",
        )
    }
}

package com.localkeep.app

import com.localkeep.core.ui.LkNotices
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 4A-F7, 2026-09-20: trashing an item from its detail screen was the one trash route with no way
 * back. The row menu posts "Moved to trash · Undo" and the selection bar posts it for a batch;
 * the detail screen showed a confirmation dialog and then nothing at all, so one action had three
 * different safety nets depending on where it was taken from.
 *
 * The reason it stayed that way is the thing these tests pin. Setting `trashed` pops the detail
 * screen, which clears `ItemEditViewModel` — so an undo posted from there would close over a
 * cancelled `viewModelScope` and silently do nothing, which is worse than offering none. The fix is
 * an owner that outlives the screen ([TrashUndoBus]), reached by handing the notice to the host on
 * the way out. Every link in that chain is asserted here, because any one of them going missing
 * restores the old behaviour in a way that still compiles.
 *
 * Source scans for three of the four, for the reason `DroppedStagedRestoreNoticeTest` gives: this
 * module has no mocking framework, and `VaultRepository`'s real construction chain opens
 * AndroidKeyStore and the vault file.
 */
class DetailTrashOffersUndoTest {

    private val viewModelSource = File(
        "../feature/itemedit/src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt",
    ).readText()

    private val detailSource = File(
        "../feature/itemedit/src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt",
    ).readText()

    private val navSource =
        File("src/main/kotlin/com/localkeep/app/navigation/AppNavGraph.kt").readText()

    @AfterTest
    fun tearDown() = LkNotices.clear()

    @Test
    fun `the offered notice carries an Undo, and the long window that makes one readable`() {
        LkNotices.clear()

        TrashUndoBus.offerUndo(
            id = UUID.randomUUID(),
            movedNotice = "Moved to trash",
            undoLabel = "Undo",
            restoredNotice = "Restored",
            restoreFailedNotice = "Couldn't put it back. It is still in Trash.",
        )

        val notice = assertNotNull(LkNotices.current.value, "the detail's trash must confirm at all")
        assertEquals(LkNotices.Message.Text("Moved to trash"), notice.message)
        assertEquals(
            LkNotices.Message.Text("Undo"),
            notice.actionLabel,
            "without an action label LkNotices draws no button, whatever onAction holds",
        )
        assertNotNull(notice.onAction, "the button has to do something")
        assertEquals(
            LkNotices.ACTION_MS,
            notice.durationMs,
            "an undo the user cannot read in time is not an undo — LkNotices gives an actionable " +
                "notice the longer window, and it derives that from onAction being non-null",
        )
    }

    @Test
    fun `tapping Undo before anything is registered does nothing rather than crashing`() {
        // The window between process start and `LocalKeepApplication.onCreate` running the
        // registration. Nothing can be on screen to trash in it, but a null repository must not
        // take the notice host down with it.
        LkNotices.clear()
        TrashUndoBus.offerUndo(UUID.randomUUID(), "m", "u", "r", "f")

        LkNotices.current.value?.onAction?.invoke()
    }

    @Test
    fun `the ViewModel no longer posts the confirmation itself`() {
        val body = bounded(viewModelSource, "fun moveToTrash()", "\n    }")
        assertTrue(
            "copy(trashed = true)" in body,
            "the control: the write still has to be what sends the screen back; found:\n$body",
        )
        assertTrue(
            "LkNotices.post" !in body,
            "a notice posted here is posted by a ViewModel that is about to be cleared, so its " +
                "Undo would run on a cancelled scope — the whole defect. Found:\n$body",
        )
    }

    @Test
    fun `the detail screen hands the trash to the host before it leaves`() {
        val body = bounded(detailSource, "LaunchedEffect(state.trashed)", "\n    }")
        val handOff = body.indexOf("onTrashed(")
        val leave = body.indexOf("onBack()")
        assertTrue(handOff > 0, "the trashed effect must hand the item to the host; found:\n$body")
        assertTrue(
            leave > handOff,
            "and it must do so before popping itself, or the strings it resolved go with it",
        )
    }

    @Test
    fun `the nav graph wires that hand-off to the app-lifetime undo`() {
        val body = bounded(navSource, "ItemDetailScreen(", "\n                }")
        assertTrue(
            "onTrashed =" in body,
            "the detail's onTrashed defaults to a no-op, so an unwired host is a silent trash " +
                "again — exactly the finding. Found:\n$body",
        )
        assertTrue(
            "TrashUndoBus.offerUndo(" in body,
            "and it has to reach the owner that outlives the screen, not a local lambda",
        )
    }

    private fun bounded(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertTrue(start > 0, "region start \"$from\" not found — did it move?")
        val end = source.indexOf(to, start)
        assertTrue(end > start, "region end \"$to\" not found after it — region unbounded")
        return source.substring(start, end)
    }
}

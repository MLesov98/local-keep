package com.localkeep.app

import com.localkeep.app.navigation.AppNavGraph
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A lock that drops a staged `.vault` restore must owe the next unlock a notice — and it must not
 * owe one just because someone was reading the backup screen.
 *
 * `MainActivity`'s lock path (`sealToUnlock`, shared by the lock collector and the back-stack watcher) decided this from `AppNavGraph.droppedEditRoute(currentRoute)`,
 * which names the import wizard and has never named `backup`. So a restore that cost a file pick,
 * a typed password and an Argon2id derivation vanished with no word at all (F37, sweep
 * `final-20260913-fable`).
 *
 * **The wrong fix is the obvious one**, and this test's second half exists to keep it out. Adding
 * `"backup"` to `droppedEditRoute` would post the notice to everyone an auto-lock caught on that
 * screen with nothing staged — reading about cloud backup, checking when they last exported — and
 * that is the same mistake the route list already carries a scar from: a broad `startsWith("item/")`
 * announcing discarded edits to people who had only viewed an item. The route says where the user
 * was; only the ViewModel knows what they had, and `BackupStaging` is how that reaches an Activity
 * about to pop the ViewModel's own back-stack entry.
 */
class DroppedStagedRestoreNoticeTest {

    private val mainActivity =
        File("src/main/kotlin/com/localkeep/app/MainActivity.kt").readText()

    @Test
    fun `the lock collector asks the ViewModel, not the route`() {
        val handler = bounded("fun sealToUnlock(", "navController.navigate(AppNavGraph.UNLOCK)")
        assertTrue(
            handler.contains("BackupStaging.take()"),
            "a staged restore dropped by the lock owes the next unlock a notice, and nothing on " +
                "the route can answer whether one was staged; found:\n$handler",
        )
        assertTrue(
            handler.contains("AppNavGraph.droppedEditRoute(route)"),
            "the control: the editor routes must keep posting the notice they always did",
        )
    }

    /**
     * Read and cleared where the route is read, which is *before* the inclusive pop below it. The
     * pop destroys the ViewModel that owns the answer, so a read afterwards would always be false.
     */
    @Test
    fun `the flag is taken before the pop that destroys the ViewModel`() {
        val handler = bounded("fun sealToUnlock(", "navController.navigate(AppNavGraph.UNLOCK)")
        val take = handler.indexOf("BackupStaging.take()")
        val assign = handler.indexOf("editDroppedByLock =")
        assertTrue(take >= 0 && assign >= 0, "both halves must be present; found:\n$handler")
        assertTrue(
            take < assign,
            "the flag has to be taken before it is used, and both before the pop",
        )
    }

    @Test
    fun `the backup route is still not an editor route`() {
        assertFalse(
            AppNavGraph.droppedEditRoute(AppNavGraph.BACKUP),
            "naming the route here would post \"your unsaved changes were discarded\" to every " +
                "auto-lock on a screen someone was merely reading — verdict 5-1 of the sweep " +
                "records this as re-introducing a defect this file already fixed once",
        )
        // The control: the route list still answers for the things it is actually about.
        assertTrue(AppNavGraph.droppedEditRoute(AppNavGraph.IMPORT))
        assertTrue(AppNavGraph.droppedEditRoute("item/{itemId}/edit"))
    }

    /**
     * A slice bounded at both ends, or a loud failure. `substringAfter`/`substringBefore` return
     * the whole receiver when the delimiter is absent, so a renamed anchor would widen the slice to
     * a 600-line Activity in which `editDroppedByLock` also appears twice at the unlock branch.
     */
    private fun bounded(after: String, before: String): String {
        assertTrue(mainActivity.contains(after), "anchor \"$after\" no longer occurs in MainActivity")
        val tail = mainActivity.substringAfter(after)
        assertTrue(tail.contains(before), "delimiter \"$before\" no longer occurs after \"$after\"")
        return tail.substringBefore(before)
    }
}

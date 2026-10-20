package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The editor keeps a back guard while it is committing a save.
 *
 * `ItemEditScreen` composed `LkEncryptingScreen` and `return`ed *before* its own `BackHandler` was
 * declared, so for the whole encrypt there was no back guard on the screen at all — `LkSavedScreen`
 * brings one, `LkEncryptingScreen` does not, and the only other handler in the app is `AppShell`'s,
 * enabled solely while the drawer is open. One Back gesture during the write popped the editor.
 *
 * What that costs, stated accurately, because the obvious answer is wrong: **the write is not
 * aborted.** `VaultLockManager.commit` runs its body inside `withContext(IO)` with no suspension
 * point between there and `AtomicVaultWriter.write`, and none of the encode/encrypt/write path is
 * `suspend`, so cancelling `viewModelScope` cannot stop it — the edit lands and the item appears in
 * the list. What is thrown away is everything the user was owed *about* it: the outcome notices,
 * any failure message, and the vault list's highlight of the row that just changed. There is one
 * genuine loss window on top of that, and it is why this is a guard rather than a nicety: if the
 * job is cancelled while still *suspended* waiting on the repository's mutation mutex behind a
 * concurrent writer, the write never starts and the edit is gone with nothing said.
 *
 * The identity photograph route was already covered by accident — its own handler is disabled
 * during a save, and `ItemEditScreen`'s handler *was* composed on that path because the identity
 * branch sits below it. The asymmetry was unintentional, so the fix is to hoist rather than to give
 * `LkEncryptingScreen` a handler of its own (which would then have to be threaded through the
 * identity flow that does not need one).
 *
 * Source scan for the reason `IdentityStartScrollableTest` gives: this module's Compose UI tests
 * never reach idle. Both claims are paired with controls that held before the fix.
 */
class SaveOwnsBackTest {

    private fun screen(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
        assertTrue(src.exists(), "ItemEditScreen.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val start = text.indexOf("fun ItemEditScreen(")
        assertTrue(start > 0, "ItemEditScreen( not found — did it get renamed?")
        return text.substring(start)
    }

    @Test
    fun `the back guard is composed before the encrypting screen returns`() {
        val text = screen()
        val handler = text.indexOf("BackHandler(enabled = !showFolderPicker)")
        val payoffBranch = text.indexOf("if (!identityPhotoRoute) {")
        // The control: the branch that draws the encrypting and saved screens is still there and
        // still returns past the form. True before the fix and after.
        assertTrue(payoffBranch > 0, "the payoff branch is gone — did the screen get restructured?")
        assertTrue(handler > 0, "the editor's BackHandler is gone")
        assertTrue(
            handler < payoffBranch,
            "the BackHandler is declared after the branch that returns for LkEncryptingScreen, so " +
                "no back guard is composed for the length of the save. LkEncryptingScreen composes " +
                "none of its own.",
        )
    }

    @Test
    fun `leaving is refused while a save is in flight`() {
        val text = screen()
        val at = text.indexOf("val leave: () -> Boolean = {")
        assertTrue(at > 0, "the leave lambda is gone — did it get renamed?")
        val body = text.substring(at, text.indexOf("\n    }", at))
        // The control: leaving still asks whether there is anything to lose. True before and after.
        assertTrue("viewModel.isDirty()" in body, "leave no longer consults isDirty — region wrong?")
        assertTrue(
            "state.isSaving" in body,
            "leave does not treat an in-flight save as un-leaveable. isDirty() compares the form " +
                "against the baseline and can answer false mid-commit, so the guard pops the screen " +
                "anyway and the save's outcome — including a failure — is never reported.",
        )
    }
}

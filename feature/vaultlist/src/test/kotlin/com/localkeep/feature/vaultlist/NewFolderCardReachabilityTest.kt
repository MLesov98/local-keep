package com.localkeep.feature.vaultlist

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Opening the new-folder card has to put it on screen, and its error has to render beside it.
 *
 * Both measured on emulator-5554, 2026-09-20, on a vault with 18 folders:
 *
 *  - **2C-F1** — tapping "New folder" produced no EditText in the dump and `mInputShown=false`; the
 *    only thing that changed anywhere on screen was the button relabelling itself Cancel. The card
 *    is the last child of a `verticalScroll` Column about six screens long, and nothing scrolled.
 *  - **2C-F2** — typing an existing sibling name and pressing Create put
 *    `A folder with that name already exists.` at `[153,473][990,529]`, the top of the list, six
 *    screens above the button just pressed. The button read as dead.
 *
 * The error half is a pure function of the state and is asserted as one. The scroll half is a
 * `BringIntoViewRequester`, which is layout behaviour a JVM test cannot exercise, so it is
 * source-scanned in the manner this module already uses for the same card
 * ([NewFolderCardAutoFocusTest], which records the full reasoning: a composed assertion that passes
 * with and without the production change discriminates nothing).
 */
class NewFolderCardReachabilityTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/FolderScreen.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    // ---- 2C-F2: whose error is it ----

    @Test
    fun `an error raised while the card is open belongs to the card`() {
        val state = FolderUiState(
            showNewFolder = true,
            newName = "Work",
            error = R.string.vaultlist_folders_error_name_exists,
        )
        assertEquals(
            R.string.vaultlist_folders_error_name_exists,
            state.composerError,
            "the card must render the clash itself — at the top of a six-screen list it is invisible",
        )
        assertNull(state.listError, "and the screen must not render it a second time at the top")
    }

    @Test
    fun `an error raised with the card closed stays with the screen`() {
        val state = FolderUiState(error = R.string.vaultlist_folders_error_action)
        assertNull(state.composerError)
        assertEquals(R.string.vaultlist_folders_error_action, state.listError)
    }

    @Test
    fun `a rename clash stays with the screen even while the card is open`() {
        // `startRenaming` does not close the card, so both can be open at once and the rename's
        // clash is about a row, not about the field in the card.
        val state = FolderUiState(
            showNewFolder = true,
            renamingId = UUID.randomUUID(),
            error = R.string.vaultlist_folders_error_name_exists,
        )
        assertNull(state.composerError)
        assertEquals(R.string.vaultlist_folders_error_name_exists, state.listError)
    }

    @Test
    fun `no error means no error anywhere`() {
        val state = FolderUiState(showNewFolder = true)
        assertNull(state.composerError)
        assertNull(state.listError)
    }

    // ---- 2C-F1: the card scrolls itself into view ----

    @Test
    fun `the card asks to be brought into view once it has been laid out`() {
        val code = text()
        assertTrue("private fun NewFolderCard(" in code, "read a FolderScreen with no new-folder card")
        assertTrue(
            "bringIntoViewRequester(reveal)" in code && "reveal.bringIntoView()" in code,
            "the new-folder card must scroll itself into view — appended to a six-screen list, " +
                "opening it is otherwise invisible (2C-F1)",
        )
        // A prefix, not the whole key list: the effect is also keyed on the keyboard's inset since
        // 2026-10-05, which `NewFolderCardStaysAboveTheKeyboardTest` drives on a device.
        assertTrue(
            "onSizeChanged { cardSize = it }" in code && "LaunchedEffect(cardSize" in code,
            "the request has to be made after the card is laid out, and re-made when it grows — " +
                "which is what the inline error appearing inside it does (2C-F2)",
        )
    }

    @Test
    fun `the screen renders the card's error inside the card, not above the list`() {
        val code = text()
        assertTrue(
            "state.composerError?.let" in code,
            "the card must render FolderUiState.composerError",
        )
        assertTrue(
            "state.listError?.let" in code,
            "the screen's top-of-list inline error must read listError, or the card's error is " +
                "drawn twice — once beside the field and once six screens away",
        )
    }

    // ---- keyboard up on a short window ----

    /**
     * On a 360x640 window the pinned header and Cancel left the list shorter than the card, so with
     * the keyboard up the parent line and Create — and after a create, the name field — were cut off
     * by the list's viewport. `NewFolderCardStaysAboveTheKeyboardTest` measures it on a device, but
     * CI's only emulator is Pixel-sized and never reaches the state, so the wiring is pinned here.
     */
    @Test
    fun `the screen follows the shared keyboard-up rule`() {
        val code = text()
        assertTrue(
            "rememberLkKeyboardUnpinsChrome()" in code,
            "FolderScreen must read the shared keyboard-up rule, or a short window keeps its header and " +
                "Cancel pinned around a list too short for the New folder card",
        )
        assertTrue(
            "if (!unpinned) header(true)" in code && "if (!unpinned) action(true)" in code,
            "the header and the action must leave their pinned places while the rule says so",
        )
    }
}

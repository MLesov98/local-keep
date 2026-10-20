package com.localkeep.core.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Starting "New folder" on the picker screen has to put the name field on screen.
 *
 * 2C-F5, on a device 2026-09-20 (vault list → FAB → New folder): on arrival the screen showed the
 * folder tree and a disabled `Create folder, type a name first`, while the `NEW FOLDER NAME` well
 * was ten scroll gestures down, past every folder. A screen that tells you to type a name and shows
 * you nowhere to type it.
 *
 * The card sits *under* the tree deliberately — `Item Flows.dc.html`, and [LkFolderPicker]'s own
 * KDoc: "the field opens under the list rather than on a new screen, so the folders it will sit
 * among stay visible", and the flow wants choose-where before name. `LkNewFolderCard`'s comment
 * already said the card "is scrolled to"; that scroll had simply never been written. So the fix is
 * the missing scroll, not a field pinned at the top, which would undo the comp.
 *
 * Source-scanned in this file's established style ([LkFolderPickerChevronA11yTest],
 * `NamingSheetAutoFocusTest`): the claim is about layout behaviour under a scroll container, which
 * `createComposeRule` answers identically with and without the requester.
 */
class LkFolderPickerNamingReachabilityTest {

    private val source = File("src/main/kotlin/com/localkeep/core/ui/theme/LkFolderPicker.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    @Test
    fun `the naming card asks to be brought into view once it has been laid out`() {
        val code = text()
        assertTrue("private fun LkNewFolderCard(" in code, "read a file with no naming card in it")
        assertTrue(
            "bringIntoViewRequester(reveal)" in code && "reveal.bringIntoView()" in code,
            "the naming card must scroll itself into view; appended below the whole tree, opening " +
                "it is otherwise invisible (2C-F5)",
        )
        assertTrue(
            "onSizeChanged { cardSize = it }" in code && "LaunchedEffect(cardSize, treeArrived, imeBottom)" in code,
            "the request has to be made after the card is laid out, re-made when it grows — which " +
                "is what the duplicate-name hint does — and re-made when the tree arrives after it " +
                "(device pass 2026-10-04; driven by LkFolderPickerNameFieldOnArrivalTest)",
        )
    }

    /**
     * The control. Without it the assertion above would also pass against a card that had been
     * moved to the top of the screen, which is the change the comp rules out.
     */
    @Test
    fun `the card is still appended under the folder rows, not pinned above them`() {
        val code = text()
        val rowsAt = code.indexOf("rows.forEach { row ->")
        val cardAt = code.indexOf("LkNewFolderCard(\n")
        assertTrue(rowsAt > 0, "the picker no longer lists folder rows")
        assertTrue(cardAt > 0, "the picker no longer renders the naming card")
        assertTrue(
            rowsAt < cardAt,
            "the naming card must stay under the tree — the folders it will join have to stay " +
                "visible while it is open",
        )
    }
}

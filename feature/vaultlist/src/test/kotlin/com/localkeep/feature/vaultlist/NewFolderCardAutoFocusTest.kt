package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Folders screen's new-folder card must not ask for focus, because asking for focus is what
 * raised the keyboard over the parent chooser sitting directly beneath the field.
 *
 * `core:ui`'s [com.localkeep.core.ui.theme.LkFolderPicker] dropped its own focus request on
 * 2026-08-20, reported against it as: "I click and immediately keyboard opens.. when if user wants
 * to add a folder in a subfolder somewhere? exceptionally bad UX". This screen is the second place
 * a folder can be made, its KDoc claims the two agree about what making one looks like, and it kept
 * its request — so for a fortnight they disagreed about the keyboard. Measured on emulator-5554
 * 2026-09-04 with `adb shell dumpsys input_method`: `mInputShown=true` the instant "New folder" was
 * tapped, with "Choose where the new folder goes" rendered just above the IME.
 *
 * If anything the argument is stronger here than in the picker: this card carries the parent
 * chooser, so the order the flow wants is choose-where then name, and the keyboard covers the tree
 * the user is choosing from.
 *
 * **Why this reads the source instead of driving the component.** [NamingSheetAutoFocusTest] in
 * `core:ui` records the reasoning in full and this is the same shape of defect: under
 * `createComposeRule` the focused-node assertion passes with *and* without the requester, so it
 * discriminates nothing, and a test that has never been red for the right reason reads as coverage
 * while providing none. The unambiguous fact is that the code does not contain the call.
 *
 * If a future author needs focus here, deleting this test is the honest way to do it — and then the
 * keyboard is back, which is the conversation this file exists to force.
 */
class NewFolderCardAutoFocusTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/FolderScreen.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    @Test
    fun `the folders screen requests focus for nothing`() {
        val code = text().lineSequence()
            // Comments explain why the focus request is gone; they must not trip the check, or the
            // explanation becomes unwritable and the next author gets the rule without the reason.
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "requestFocus" in code,
            "FolderScreen asks for focus again. That reopens the software keyboard over the new " +
                "folder's own parent chooser — see the KDoc above, and re-measure with " +
                "dumpsys input_method before deciding this is wanted.",
        )
    }

    /**
     * The control. The assertion above would also pass against an empty file, a renamed file, or a
     * file this test cannot find — so prove the thing being read is really the Folders screen, and
     * really still the one carrying the new-folder card with a parent chooser in it.
     */
    @Test
    fun `the file being read really is the folders screen with its new folder card`() {
        val text = text()
        assertTrue("fun FolderScreen(" in text, "read a file that is not FolderScreen")
        assertTrue("private fun NewFolderCard(" in text, "read a FolderScreen with no new-folder card")
        assertTrue("choosingParent" in text, "read a new-folder card with no parent chooser")
    }
}

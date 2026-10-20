package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Starting a selection puts the keyboard away.
 *
 * The search field keeps its focus while rows are long-pressed, and the bulk bar floats at the
 * bottom of the screen with no IME inset — so with search focused, "Select multiple…" opened the
 * mode under a keyboard that covered Move, Tag and Trash. On a device pass of the release build
 * (2026-10-04) a tap aimed at Trash typed a letter into the search box instead. The short-window
 * variant carries the actions in the header, but its list is what the keyboard then covers, so the
 * effect has to run in both: it sits in the header column, which composes in both.
 *
 * ### Why this reads the source
 *
 * Same limitation as [SearchFocusRequestTest]: `VaultListScreen` is Hilt-backed and never reaches
 * idle under Robolectric's compose rule, and a miniature of the effect would test the miniature.
 * The behaviour itself was checked on emulator-5556 with `dumpsys input_method`.
 */
class SelectionDropsTheKeyboardTest {

    private fun screen(): String {
        val file = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }

    @Test
    fun `entering selection clears focus and hides the keyboard`() {
        val code = screen()
        val start = code.indexOf("LaunchedEffect(state.selecting) {")
        if (start < 0) fail("no effect keyed on state.selecting — nothing puts the keyboard away when selection starts")
        val body = code.substring(start, code.indexOf("\n                }", start))
        assertTrue("if (state.selecting)" in body, "the effect must act on entering selection only: $body")
        assertTrue(
            "focusManager.clearFocus()" in body,
            "clearing focus is what ends the search field's editing session: $body",
        )
        assertTrue(
            "keyboardController?.hide()" in body,
            "and the explicit hide is the belt to that — see the reveal effect's note: $body",
        )
    }

    /** The control: the file read is still the list whose bulk bar the keyboard was covering. */
    @Test
    fun `the file being read is the vault list with its bulk bar`() {
        val code = screen()
        assertTrue("fun VaultListScreen(" in code, "read a file that is not VaultListScreen")
        assertTrue("BulkActionBar(" in code, "read a vault list with no bulk action bar")
        assertTrue(".focusRequester(searchFocus)" in code, "read a vault list with no focusable search field")
    }
}

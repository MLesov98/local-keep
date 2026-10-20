package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A new item's title fills itself from what the form holds, and the field sits below the type's own
 * fields rather than at the top of the form.
 *
 * Asked for on 2026-10-05: a location opened on an empty TITLE (REQUIRED) well before anything had
 * been captured, and a 2FA code — already named by its scan — still showed the same required
 * field. The suggestion is `suggestedTitle` (pinned per type by `TitleSuggestionTest`); what is
 * pinned here is that the screen applies it until the user types, and stops once they do.
 *
 * Source-pinned: the wiring is Compose state on the screen.
 */
class NewItemNamesItselfTest {

    private val screen = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()

    @Test
    fun `a new item's title follows the suggestion until the user types one`() {
        val effect = screen.substringAfter("LaunchedEffect(titleSuggestion) {", "").substringBefore("\n    }")
        assertTrue(
            "state.isNew && !titleTyped" in effect,
            "the title is no longer filled from the form, or it overwrites a title the user typed",
        )
        assertTrue("onSuggested(titleSuggestion)" in effect)
        // The screen hands the effect the ViewModel's suggestion entry point, never `onTitleChanged`.
        assertTrue("AutoTitleEffect(state, titleTyped, viewModel::onTitleSuggested)" in screen)
        assertTrue("val titleSuggestion = rememberSuggestedTitle(state)" in screen)
    }

    @Test
    fun `typing into the title stops the auto-fill`() {
        val field = screen.substringAfter("if (!nothingToNameYet) {", "").substringBefore("isError = state.titleError")
        assertTrue("titleTyped = true" in field, "a typed title would be overwritten by the next suggestion")
    }

    @Test
    fun `the title field comes after the type's own fields`() {
        val swap = screen.indexOf("LkSectionSwap(state.type)")
        val title = screen.indexOf("R.string.itemedit_edit_title_field_label")
        assertTrue(swap > 0 && title > swap, "the title field is back at the top of the form")
    }

    /** A 2FA code before its scan and a location before its fix have nothing to be named after. */
    @Test
    fun `the field waits for a scan or a fix`() {
        val gate = screen.substringAfter("val nothingToNameYet =", "").substringBefore("if (!nothingToNameYet)")
        assertTrue("ItemType.TOTP -> state.totpSecret.isBlank()" in gate)
        assertTrue("ItemType.COORDINATE -> state.coordinateFix == null" in gate)
    }

    @Test
    fun `the label no longer says required`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue("<string name=\"itemedit_edit_title_field_label\">Title</string>" in strings)
        assertFalse("Title (required)" in strings)
    }

    /** A login opens named "Login N"; the list it avoids is plain data the ViewModel loads. */
    @Test
    fun `the login fallback is chosen against loaded titles, not resolved in the ViewModel`() {
        val vm = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt").readText()
        val load = vm.substringAfter("private fun loadFolders()", "").substringBefore("\n    }\n")
        assertTrue("it.content is Login" in load, "existing titles are not limited to logins")
        assertTrue("existingLoginTitles = loginTitles" in load)
        assertFalse("itemedit_title_suggestion_login" in vm, "the ViewModel resolves a display string")
        // Stripped like the folder counts, or every existing item's Back asks to discard nothing.
        assertTrue("existingLoginTitles = emptySet()," in vm.substringAfter("fun ItemEditUiState.editableOnly()"))
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue("<string name=\"itemedit_title_suggestion_login\">Login %1\$d</string>" in strings)
    }
}
